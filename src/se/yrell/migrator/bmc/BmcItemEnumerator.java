package se.yrell.migrator.bmc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;

import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.item.ItemList;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcMetadataCache.SyncProgress;

/**
 * Version-tolerant object-list enumeration helper.
 *
 * Different Developer Studio versions expose object-list metadata through different store methods.
 * The normal compare path may still hydrate individual items when needed, but metadata sync must not
 * deep-read every workflow object. Active Link/Filter providers can be very slow or block in some
 * versions, so metadata sync uses bounded strategy calls and continues to the next type on timeout.
 */
public final class BmcItemEnumerator {
    private BmcItemEnumerator() {
    }

    /**
     * Existing full item enumeration used only by direct/non-cached search fallbacks. This can be slower
     * because it may hydrate name-only objects. Metadata cache sync should use getMetadataItems/getNamesFast.
     */
    public static List<IModelItem> getItems(IStore store, IModelType type) {
        Map<String, IModelItem> result = new TreeMap<String, IModelItem>(String.CASE_INSENSITIVE_ORDER);
        if (store == null || type == null) {
            return new ArrayList<IModelItem>();
        }

        addItems(result, safeGetItemList(store, type, "getItemList"));
        if (result.isEmpty()) {
            addItems(result, safeGetList(store, type, false));
        }
        if (result.isEmpty()) {
            addItems(result, safeGetList(store, type, true));
        }

        // Some BMC providers expose only names until the individual item is requested. This is kept for
        // non-cache searches, but deliberately not used by metadata sync because it can deep-read thousands
        // of workflow objects.
        for (String name : getNames(store, type, false)) {
            if (name == null || name.length() == 0 || result.containsKey(name)) {
                continue;
            }
            IModelItem item = safeGetItem(store, type, name);
            if (item != null) {
                result.put(item.getName(), item);
            }
        }
        return new ArrayList<IModelItem>(result.values());
    }

    /**
     * Safe metadata-only item enumeration. It tries cheap object-list providers first and never calls
     * getItem(type,name) for every returned name. Each strategy is bounded by timeoutSeconds.
     */
    public static List<IModelItem> getMetadataItems(final IStore store, final IModelType type, SyncProgress progress,
            IProgressMonitor monitor, int timeoutSeconds, boolean aggressive) {
        Map<String, IModelItem> result = new TreeMap<String, IModelItem>(String.CASE_INSENSITIVE_ORDER);
        if (store == null || type == null) {
            return new ArrayList<IModelItem>();
        }
        addItems(result, timedItems("getItemList", store, type, timeoutSeconds, progress, monitor, new Callable<Iterable<IModelItem>>() {
            public Iterable<IModelItem> call() throws Exception {
                return safeGetItemList(store, type, "getItemList");
            }
        }));
        if (result.isEmpty()) {
            addItems(result, timedItems("getList(false)", store, type, timeoutSeconds, progress, monitor, new Callable<Iterable<IModelItem>>() {
                public Iterable<IModelItem> call() throws Exception {
                    return safeGetList(store, type, false);
                }
            }));
        }
        if (result.isEmpty() && aggressive) {
            addItems(result, timedItems("getList(true)", store, type, timeoutSeconds, progress, monitor, new Callable<Iterable<IModelItem>>() {
                public Iterable<IModelItem> call() throws Exception {
                    return safeGetList(store, type, true);
                }
            }));
        }
        return new ArrayList<IModelItem>(result.values());
    }

    public static List<String> getNames(IStore store, IModelType type) {
        return getNames(store, type, true);
    }

    /** Metadata-friendly name enumeration. Does not include/hydrate full items. */
    public static List<String> getNamesFast(final IStore store, final IModelType type, SyncProgress progress,
            IProgressMonitor monitor, int timeoutSeconds) {
        Map<String, String> result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (store == null || type == null) {
            return new ArrayList<String>();
        }
        addNames(result, timedNames("getNameList()", store, type, timeoutSeconds, progress, monitor, new Callable<Collection<?>>() {
            public Collection<?> call() throws Exception {
                return safeGetNameList(store, type, -1L);
            }
        }));
        if (result.isEmpty()) {
            addNames(result, timedNames("getNameList(0)", store, type, timeoutSeconds, progress, monitor, new Callable<Collection<?>>() {
                public Collection<?> call() throws Exception {
                    return safeGetNameList(store, type, 0L);
                }
            }));
        }
        return new ArrayList<String>(result.values());
    }

    private static List<String> getNames(IStore store, IModelType type, boolean includeItems) {
        Map<String, String> result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (store == null || type == null) {
            return new ArrayList<String>();
        }
        if (includeItems) {
            for (IModelItem item : getItems(store, type)) {
                if (item != null && item.getName() != null) {
                    result.put(item.getName(), item.getName());
                }
            }
        }
        addNames(result, safeGetNameList(store, type, -1L));
        // A timestamp of 0 is a useful fallback in some versions because it means "changed since epoch".
        if (result.isEmpty()) {
            addNames(result, safeGetNameList(store, type, 0L));
        }
        return new ArrayList<String>(result.values());
    }

    public static IModelItem findItem(IStore store, IModelType type, String name) {
        if (store == null || type == null || name == null) {
            return null;
        }
        IModelItem item = safeGetItem(store, type, name);
        if (item != null) {
            return item;
        }
        for (IModelItem candidate : getItems(store, type)) {
            if (candidate != null && name.equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private static Iterable<IModelItem> timedItems(final String strategyName, final IStore store, final IModelType type,
            int timeoutSeconds, SyncProgress progress, IProgressMonitor monitor, final Callable<Iterable<IModelItem>> callable) {
        if (monitor != null && monitor.isCanceled()) {
            return null;
        }
        report(progress, store.getName() + ": " + type.getTypeName() + " - trying " + strategyName + "...");
        List<IModelItem> list = runWithTimeout(strategyName, store, type, timeoutSeconds, progress, new Callable<List<IModelItem>>() {
            public List<IModelItem> call() throws Exception {
                List<IModelItem> copied = new ArrayList<IModelItem>();
                Iterable<IModelItem> iterable = callable.call();
                if (iterable != null) {
                    for (IModelItem item : iterable) {
                        if (item != null) {
                            copied.add(item);
                        }
                    }
                }
                return copied;
            }
        });
        if (list != null) {
            report(progress, store.getName() + ": " + type.getTypeName() + " - " + strategyName + " returned " + list.size() + " item(s).");
        }
        return list;
    }

    private static Collection<?> timedNames(final String strategyName, final IStore store, final IModelType type,
            int timeoutSeconds, SyncProgress progress, IProgressMonitor monitor, final Callable<Collection<?>> callable) {
        if (monitor != null && monitor.isCanceled()) {
            return null;
        }
        report(progress, store.getName() + ": " + type.getTypeName() + " - trying " + strategyName + "...");
        List<Object> list = runWithTimeout(strategyName, store, type, timeoutSeconds, progress, new Callable<List<Object>>() {
            public List<Object> call() throws Exception {
                Collection<?> values = callable.call();
                return values == null ? new ArrayList<Object>() : new ArrayList<Object>(values);
            }
        });
        if (list != null) {
            report(progress, store.getName() + ": " + type.getTypeName() + " - " + strategyName + " returned " + list.size() + " name(s).");
        }
        return list;
    }

    private static <T> T runWithTimeout(final String strategyName, final IStore store, final IModelType type,
            int timeoutSeconds, SyncProgress progress, Callable<T> callable) {
        int timeout = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "Yrell Migrator metadata " + safeName(store) + " " + type.getTypeName() + " " + strategyName);
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<T> future = executor.submit(callable);
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            String message = safeName(store) + ": " + type.getTypeName() + " - " + strategyName + " timed out after " + timeout + " second(s). Skipping this strategy.";
            Activator.logWarning(message, ex);
            report(progress, message);
            return null;
        } catch (Throwable ex) {
            Activator.logWarning("Could not enumerate " + strategyName + " for " + type.getTypeName() + " on " + safeName(store), ex);
            report(progress, safeName(store) + ": " + type.getTypeName() + " - " + strategyName + " failed: " + safeMessage(ex));
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void addItems(Map<String, IModelItem> result, Iterable<IModelItem> list) {
        if (list == null) {
            return;
        }
        for (IModelItem item : list) {
            if (item != null && item.getName() != null && item.getName().length() > 0) {
                result.put(item.getName(), item);
            }
        }
    }

    private static void addNames(Map<String, String> result, Collection<?> names) {
        if (names == null) {
            return;
        }
        for (Object value : names) {
            if (value != null) {
                String name = String.valueOf(value);
                if (name.length() > 0) {
                    result.put(name, name);
                }
            }
        }
    }

    private static IModelItem safeGetItem(IStore store, IModelType type, String name) {
        try {
            return store.getItem(type, name);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static Iterable<IModelItem> safeGetItemList(IStore store, IModelType type, String methodName) {
        try {
            ItemList<IModelItem> list = store.getItemList(type);
            return list == null ? null : list;
        } catch (Throwable ex) {
            Activator.logWarning("Could not enumerate getItemList for " + type.getTypeName() + " on " + store.getName(), ex);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterable<IModelItem> safeGetList(IStore store, IModelType type, boolean refresh) {
        try {
            Object list = store.getList(type, refresh);
            if (list instanceof Iterable) {
                return (Iterable<IModelItem>) list;
            }
        } catch (Throwable ex) {
            Activator.logWarning("Could not enumerate getList(" + refresh + ") for " + type.getTypeName() + " on " + store.getName(), ex);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Collection<?> safeGetNameList(IStore store, IModelType type, long changedSince) {
        try {
            if (changedSince >= 0) {
                Method method = store.getClass().getMethod("getNameList", new Class[] { IModelType.class, long.class });
                Object value = method.invoke(store, new Object[] { type, Long.valueOf(changedSince) });
                return value instanceof Collection ? (Collection<?>) value : null;
            }
            Method method = store.getClass().getMethod("getNameList", new Class[] { IModelType.class });
            Object value = method.invoke(store, new Object[] { type });
            return value instanceof Collection ? (Collection<?>) value : null;
        } catch (Throwable ex) {
            return null;
        }
    }

    private static void report(SyncProgress progress, String message) {
        if (progress != null && message != null && message.length() > 0) {
            try {
                progress.report(message);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String safeName(IStore store) {
        try {
            return store == null ? "" : store.getName();
        } catch (Throwable ex) {
            return "";
        }
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        Throwable cause = ex.getCause();
        if (cause != null && cause.getMessage() != null && cause.getMessage().length() > 0) {
            return cause.getMessage();
        }
        String message = ex.getMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }
}
