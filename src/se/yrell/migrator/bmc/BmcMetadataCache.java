package se.yrell.migrator.bmc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.api.Timestamp;
import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.Activator;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.config.CompareSettings.SyncDecision;

/**
 * Persistent metadata cache for global object searches.
 *
 * It stores only object-list metadata (server, type, name, last update, changed-by). The actual
 * compare still reloads the selected object definitions from AR System. This keeps repeated global
 * searches fast without making migration/compare decisions from stale full definitions.
 */
public final class BmcMetadataCache {
    private static final String FILE_NAME = "metadata-cache.tsv";
    private final Map<String, CacheEntry> entries = new LinkedHashMap<String, CacheEntry>();
    private boolean loaded;

    public interface SyncProgress {
        void report(String message);
    }

    public synchronized CacheStats refresh(IStore store, List<IModelType> types, boolean full, IProgressMonitor monitor) {
        return refresh(store, types, full, monitor, null);
    }

    public synchronized CacheStats refresh(IStore store, List<IModelType> types, boolean full, IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        loadIfNeeded();
        CacheStats stats = new CacheStats();
        if (store == null || types == null) {
            return stats;
        }
        String server = store.getName();
        stats.stores = 1;
        safeMonitor.beginTask((full ? "Full" : "Quick") + " metadata cache sync for " + server, types.size());
        report(progress, (full ? "Full" : "Quick") + " sync started for " + server + " (" + types.size() + " object type(s)).");
        int typeIndex = 0;
        for (IModelType type : types) {
            if (safeMonitor.isCanceled()) {
                report(progress, "Sync cancelled while processing " + server + ".");
                break;
            }
            if (type == null) {
                safeMonitor.worked(1);
                continue;
            }
            typeIndex++;
            String typeName = type.getTypeName();
            safeMonitor.subTask(server + " - " + typeName + " (" + typeIndex + "/" + types.size() + ")");
            report(progress, server + ": reading " + typeName + " (" + typeIndex + "/" + types.size() + ")...");
            Map<String, CacheEntry> currentType = new TreeMap<String, CacheEntry>(String.CASE_INSENSITIVE_ORDER);
            int skippedByInclude = 0;
            int skippedByExclude = 0;
            int skippedByCustomization = 0;
            int skippedBaseCustomization = 0;
            int includedNonCustomizable = 0;
            String firstIncludeSkip = null;
            String firstExcludeSkip = null;
            String firstCustomizationSkip = null;
            try {
                CompareSettings settings = CompareSettings.load();
                int timeoutSeconds = settings.getMetadataEnumerationTimeoutSeconds();
                boolean aggressive = settings.isMetadataAggressiveEnumeration();
                report(progress, server + ": " + typeName + " - metadata enumeration mode=" + (aggressive ? "aggressive" : "safe")
                        + ", timeout=" + timeoutSeconds + "s per strategy.");
                report(progress, server + ": " + typeName + " - sync filter: " + settings.describeSyncFilters() + ".");

                for (IModelItem item : BmcItemEnumerator.getMetadataItems(store, type, progress, safeMonitor, timeoutSeconds, aggressive)) {
                    if (item == null || item.getName() == null) {
                        continue;
                    }
                    CacheEntry entry = fromItem(server, typeName, item);
                    SyncDecision decision = settings.getSyncDecision(typeName, entry.name);
                    if (!decision.isIncluded()) {
                        if ("matched by exclude filter".equals(decision.getReason())) {
                            skippedByExclude++;
                            if (firstExcludeSkip == null) {
                                firstExcludeSkip = entry.name + " matched exclude '" + decision.getExcludePattern() + "'";
                            }
                        } else {
                            skippedByInclude++;
                            if (firstIncludeSkip == null) {
                                firstIncludeSkip = entry.name + " did not match include '" + decision.getIncludePattern() + "'";
                            }
                        }
                        continue;
                    }
                    SyncDecision customizationDecision = settings.getCustomizationSyncDecision(typeName, entry.name, entry.customizationType);
                    if (!customizationDecision.isIncluded()) {
                        skippedByCustomization++;
                        if ("base".equals(customizationDecision.getCustomizationType())) {
                            skippedBaseCustomization++;
                        }
                        if (firstCustomizationSkip == null) {
                            firstCustomizationSkip = entry.name + " customization=" + customizationDecision.getCustomizationType()
                                    + " not in cache selection '" + customizationDecision.getIncludePattern() + "'";
                        }
                        continue;
                    }
                    if (customizationDecision.getReason() != null && customizationDecision.getReason().indexOf("non-customizable") >= 0) {
                        includedNonCustomizable++;
                    }
                    currentType.put(entry.name, entry);
                }
                for (String name : BmcItemEnumerator.getNamesFast(store, type, progress, safeMonitor, timeoutSeconds)) {
                    if (name == null || name.length() == 0 || currentType.containsKey(name)) {
                        continue;
                    }
                    CacheEntry entry = new CacheEntry(server, typeName, name, Long.MIN_VALUE, "", "", "");
                    SyncDecision decision = settings.getSyncDecision(typeName, entry.name);
                    if (!decision.isIncluded()) {
                        if ("matched by exclude filter".equals(decision.getReason())) {
                            skippedByExclude++;
                            if (firstExcludeSkip == null) {
                                firstExcludeSkip = entry.name + " matched exclude '" + decision.getExcludePattern() + "'";
                            }
                        } else {
                            skippedByInclude++;
                            if (firstIncludeSkip == null) {
                                firstIncludeSkip = entry.name + " did not match include '" + decision.getIncludePattern() + "'";
                            }
                        }
                        continue;
                    }
                    SyncDecision customizationDecision = settings.getCustomizationSyncDecision(typeName, entry.name, entry.customizationType);
                    if (!customizationDecision.isIncluded()) {
                        skippedByCustomization++;
                        if ("base".equals(customizationDecision.getCustomizationType())) {
                            skippedBaseCustomization++;
                        }
                        if (firstCustomizationSkip == null) {
                            firstCustomizationSkip = entry.name + " customization=" + customizationDecision.getCustomizationType()
                                    + " not in cache selection '" + customizationDecision.getIncludePattern() + "'";
                        }
                        continue;
                    }
                    if (customizationDecision.getReason() != null && customizationDecision.getReason().indexOf("non-customizable") >= 0) {
                        includedNonCustomizable++;
                    }
                    currentType.put(entry.name, entry);
                }
                if (currentType.isEmpty()) {
                    report(progress, server + ": " + typeName + " - no metadata was returned. If this type is expected to exist, try enabling aggressive metadata enumeration in Window > Preferences > Yrell Migrator.");
                }
            } catch (Throwable ex) {
                stats.errors++;
                Activator.logWarning("Could not sync metadata cache for " + typeName + " on " + server, ex);
                report(progress, server + ": ERROR reading " + typeName + " - " + safeMessage(ex));
            }

            int beforeAdded = stats.added;
            int beforeUpdated = stats.updated;
            int beforeUnchanged = stats.unchanged;
            int beforeRemoved = stats.removed;

            // Update incrementally for both full and quick sync. Older builds removed the whole
            // server/type slice first, which made every full sync look like "all added + all removed"
            // and also discarded useful metadata comparison information.
            for (CacheEntry entry : currentType.values()) {
                CacheEntry old = entries.get(entry.key());
                if (old == null) {
                    stats.added++;
                } else if (!old.sameMetadata(entry)) {
                    stats.updated++;
                } else {
                    stats.unchanged++;
                }
                entries.put(entry.key(), entry);
            }

            // Sync and quick sync both know the complete current name list for this type,
            // so stale entries can be removed in either mode.
            stats.removed += removeMissing(server, typeName, currentType.keySet());
            stats.types++;
            stats.objects += currentType.size();
            if (firstIncludeSkip != null) {
                report(progress, server + ": " + typeName + " - sample include skip: " + firstIncludeSkip + ".");
            }
            if (firstExcludeSkip != null) {
                report(progress, server + ": " + typeName + " - sample exclude skip: " + firstExcludeSkip + ".");
            }
            if (firstCustomizationSkip != null) {
                report(progress, server + ": " + typeName + " - sample customization skip: " + firstCustomizationSkip + ".");
            }
            stats.skippedByCustomization += skippedByCustomization;
            stats.skippedBaseCustomization += skippedBaseCustomization;
            stats.includedNonCustomizable += includedNonCustomizable;
            stats.addTypeStats(server, typeName, currentType.size(), skippedByInclude, skippedByExclude, skippedByCustomization, skippedBaseCustomization, includedNonCustomizable);
            report(progress, server + ": " + typeName + " - " + currentType.size() + " object(s) after sync filter"
                    + ((skippedByInclude + skippedByExclude + skippedByCustomization + includedNonCustomizable) > 0 ? " (" + skippedByInclude + " skipped by include, " + skippedByExclude + " skipped by exclude, " + skippedByCustomization + " skipped by customization"
                            + (skippedBaseCustomization > 0 ? ", " + skippedBaseCustomization + " base skipped" : "")
                            + (includedNonCustomizable > 0 ? ", " + includedNonCustomizable + " non-customizable included" : "") + ")" : "") + ", "
                    + (stats.added - beforeAdded) + " added, "
                    + (stats.updated - beforeUpdated) + " updated, "
                    + (stats.unchanged - beforeUnchanged) + " unchanged"
                    + ((stats.removed - beforeRemoved) > 0 ? ", " + (stats.removed - beforeRemoved) + " removed" : "") + ".");
            safeMonitor.worked(1);
        }
        safeMonitor.done();
        report(progress, server + ": writing metadata cache to disk...");
        save();
        report(progress, server + ": sync complete. " + stats.toSummary() + ".");
        return stats;
    }

    public synchronized List<CacheEntry> find(IStore store, List<IModelType> types, String query) {
        loadIfNeeded();
        if (store == null || types == null) {
            return Collections.emptyList();
        }
        String server = store.getName();
        List<String> typeNames = new ArrayList<String>();
        for (IModelType type : types) {
            if (type != null) {
                typeNames.add(type.getTypeName().toLowerCase(Locale.ENGLISH));
            }
        }
        SearchPattern pattern = SearchPattern.compile(query);
        List<CacheEntry> result = new ArrayList<CacheEntry>();
        for (CacheEntry entry : entries.values()) {
            if (!entry.server.equalsIgnoreCase(server)) {
                continue;
            }
            if (!typeNames.contains(entry.type.toLowerCase(Locale.ENGLISH))) {
                continue;
            }
            if (pattern.matchesEntry(entry.name, entry.type, entry.changedBy)) {
                result.add(entry);
            }
        }
        Collections.sort(result);
        return result;
    }

    public synchronized boolean hasAny(IStore store, List<IModelType> types) {
        return !find(store, types, "*").isEmpty();
    }

    /** Updates one metadata cache row from a live Developer Studio item and writes the cache to disk. */
    public synchronized CacheEntry putFromItem(String server, String typeName, IModelItem item) {
        loadIfNeeded();
        if (item == null || item.getName() == null) {
            return null;
        }
        CacheEntry entry = fromItem(server, typeName, item);
        entries.put(entry.key(), entry);
        save();
        return entry;
    }

    /** Adds or updates one metadata cache row and writes the cache to disk. */
    public synchronized void putEntry(CacheEntry entry) {
        loadIfNeeded();
        if (entry == null) {
            return;
        }
        entries.put(entry.key(), entry);
        save();
    }

    /** Removes one metadata cache row and writes the cache to disk. */
    public synchronized boolean remove(String server, String typeName, String name) {
        loadIfNeeded();
        CacheEntry entry = new CacheEntry(server, typeName, name, Long.MIN_VALUE, "", "", "");
        boolean removed = entries.remove(entry.key()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    private int removeMissing(String server, String typeName, Collection<String> currentNames) {
        List<String> remove = new ArrayList<String>();
        for (CacheEntry entry : entries.values()) {
            if (entry.server.equalsIgnoreCase(server) && entry.type.equalsIgnoreCase(typeName) && !containsIgnoreCase(currentNames, entry.name)) {
                remove.add(entry.key());
            }
        }
        for (String key : remove) {
            entries.remove(key);
        }
        return remove.size();
    }

    private boolean containsIgnoreCase(Collection<String> values, String wanted) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    private CacheEntry fromItem(String server, String typeName, IModelItem item) {
        long modified = timestampValue(item.getLastUpdateTime());
        String changedBy = item.getLastChangedBy() == null ? "" : item.getLastChangedBy();
        return new CacheEntry(server, typeName, item.getName(), modified, changedBy, customizationType(item), contextKey(item));
    }


    public static String contextKey(Object object) {
        if (object == null) {
            return "";
        }
        String[] valueKeys = new String[] { "Group ID", "GroupId", "GroupID", "Role ID", "RoleId", "RoleID", "ID", "Id" };
        for (int i = 0; i < valueKeys.length; i++) {
            try {
                Method method = object.getClass().getMethod("getValue", new Class[] { String.class });
                Object value = method.invoke(object, new Object[] { valueKeys[i] });
                String text = simpleValue(value);
                if (text.length() > 0) {
                    return text;
                }
            } catch (Throwable ignored) {
            }
        }
        String[] getters = new String[] { "getGroupID", "getGroupId", "getGroupIdValue", "getRoleID", "getRoleId", "getRoleIdValue", "getID", "getId" };
        for (int i = 0; i < getters.length; i++) {
            try {
                Method method = object.getClass().getMethod(getters[i], new Class[0]);
                Object value = method.invoke(object, new Object[0]);
                String text = simpleValue(value);
                if (text.length() > 0 && !text.equals(String.valueOf(object.hashCode()))) {
                    return text;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String simpleValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number || value instanceof CharSequence) {
            return String.valueOf(value).trim();
        }
        try {
            Method method = value.getClass().getMethod("intValue", new Class[0]);
            Object nested = method.invoke(value, new Object[0]);
            if (nested != null) {
                return String.valueOf(nested).trim();
            }
        } catch (Throwable ignored) {
        }
        String text = String.valueOf(value).trim();
        return text.startsWith(value.getClass().getName() + "@") ? "" : text;
    }

    public static String customizationType(Object object) {
        if (object == null) {
            return "";
        }

        // Most object-list rows in Developer Studio are APIItem subclasses. Their
        // getOverlayProperty() value is the same customization integer as the internal
        // CustomizationType enum: NONE=0, OVERLAY=2, CUSTOM=4. Use this explicit accessor
        // before generic reflection so values are not accidentally displayed as raw numbers.
        String fromApiItem = customizationTypeFromApiItem(object);
        if (fromApiItem.length() > 0) {
            return fromApiItem;
        }

        // Developer Studio exposes customization information reliably through an internal
        // ICustomizableObject contract for objects that can actually be overlaid/customized
        // (forms/workflow/menus etc). Use it first and keep non-customizable resources such as
        // Group/Role/Image blank instead of forcing them to "base".
        String fromCustomizable = customizationTypeFromCustomizableInterface(object);
        if (fromCustomizable.length() > 0) {
            return fromCustomizable;
        }

        // Some object implementations keep the AR object property directly in getValue(...).
        // In exported DEF this is object-prop 90015: 1/2 = overlay, 4 = custom; absent = base.
        // We only infer base when the object told us it is customizable; otherwise blank is safer.
        String fromValueAccessor = customizationTypeFromValueAccessor(object);
        if (fromValueAccessor.length() > 0) {
            return fromValueAccessor;
        }

        String[] getters = new String[] {
                "getCustomizationType", "getCustomization", "getOverlayType", "getOverlay",
                "getCustomType", "getCustomisationType", "getOriginType", "getObjectOverlayState",
                "getOverlayInfo", "getOverlayProperty", "getCustomizationInfo", "getObjectProperties", "getProperties",
                "isOverlay", "isCustom", "isBase", "isOverlaid", "isCustomized" };
        for (int i = 0; i < getters.length; i++) {
            String getter = getters[i];
            try {
                Method method = object.getClass().getMethod(getter, new Class[0]);
                Object value = method.invoke(object, new Object[0]);
                if (value instanceof Boolean) {
                    boolean flag = ((Boolean) value).booleanValue();
                    if (!flag) {
                        continue;
                    }
                    String lowerGetter = getter.toLowerCase(Locale.ENGLISH);
                    if (lowerGetter.indexOf("overlay") >= 0 || lowerGetter.indexOf("overlaid") >= 0) {
                        return "overlay";
                    }
                    if (lowerGetter.indexOf("custom") >= 0 || lowerGetter.indexOf("customized") >= 0) {
                        return "custom";
                    }
                    if (lowerGetter.indexOf("base") >= 0 || lowerGetter.indexOf("origin") >= 0) {
                        return "base";
                    }
                }
                String normalized = normalizeCustomizationType(value);
                if (normalized.length() > 0) {
                    return normalized;
                }
                String nested = normalizeCustomizationType(invokeNestedValue(value));
                if (nested.length() > 0) {
                    return nested;
                }
            } catch (Throwable ignored) {
            }
        }

        // Name fallback only after all real metadata probes. In Helix overlays/custom objects often
        // use __c, but this should not override a real base/overlay value.
        try {
            if (object instanceof IModelItem) {
                String name = ((IModelItem) object).getName();
                if (name != null && name.toLowerCase(Locale.ENGLISH).endsWith("__c")) {
                    return "custom";
                }
            }
        } catch (Throwable ignored) {
        }

        return normalizeCustomizationType(String.valueOf(object));
    }

    private static String customizationTypeFromApiItem(Object object) {
        try {
            Method method = object.getClass().getMethod("getOverlayProperty", new Class[0]);
            Object value = method.invoke(object, new Object[0]);
            String normalized = normalizeCustomizationType(value);
            if (normalized.length() > 0) {
                return normalized;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getValue = object.getClass().getMethod("getValue", new Class[] { String.class });
            Object value = getValue.invoke(object, new Object[] { "OVERLAY_PROP" });
            String normalized = normalizeCustomizationType(value);
            if (normalized.length() > 0) {
                return normalized;
            }
            value = getValue.invoke(object, new Object[] { "OVERLAY_PROP_STRING" });
            normalized = normalizeCustomizationType(value);
            if (normalized.length() > 0) {
                return normalized;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String customizationTypeFromCustomizableInterface(Object object) {
        try {
            Class<?> customizable = Class.forName("com.bmc.arsys.studio.model.internal.ICustomizableObject");
            if (!customizable.isInstance(object)) {
                return "";
            }
            Class<?> enumClass = Class.forName("com.bmc.arsys.studio.model.internal.ICustomizableObject$CustomizationType");
            Method equalsMethod = customizable.getMethod("customizationTypeEquals", new Class[] { enumClass });
            Object[] constants = enumClass.getEnumConstants();
            Object none = null;
            for (int i = 0; constants != null && i < constants.length; i++) {
                Object constant = constants[i];
                String name = String.valueOf(constant);
                Boolean match = (Boolean) equalsMethod.invoke(object, new Object[] { constant });
                if (match != null && match.booleanValue()) {
                    if ("CUSTOM".equalsIgnoreCase(name)) {
                        return "custom";
                    }
                    if ("OVERLAY".equalsIgnoreCase(name)) {
                        return "overlay";
                    }
                    if ("NONE".equalsIgnoreCase(name)) {
                        return "base";
                    }
                }
                if ("NONE".equalsIgnoreCase(name)) {
                    none = constant;
                }
            }
            // If it implements the customizable contract but no explicit value was matched, treat
            // absence of object-prop 90015 as base, matching exported DEF behavior.
            if (none != null) {
                return "base";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String customizationTypeFromValueAccessor(Object object) {
        String[] keys = new String[] {
                "90015", "object-prop 90015", "objectProp90015", "Object Prop 90015",
                "OVERLAY_PROP", "OVERLAY_PROP_STRING",
                "Customization Type", "customizationType", "CustomizationType", "overlayType", "overlayProp" };
        for (int i = 0; i < keys.length; i++) {
            try {
                Method method = object.getClass().getMethod("getValue", new Class[] { String.class });
                Object value = method.invoke(object, new Object[] { keys[i] });
                String normalized = normalizeCustomizationType(value);
                if (normalized.length() > 0) {
                    return normalized;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static Object invokeNestedValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object direct = map.get("90015");
            if (direct == null) {
                direct = map.get(Integer.valueOf(90015));
            }
            if (direct != null) {
                return direct;
            }
        }
        String[] names = new String[] { "getValue", "getName", "getLabel", "getType", "toInt", "intValue", "name" };
        for (int i = 0; i < names.length; i++) {
            try {
                Method method = value.getClass().getMethod(names[i], new Class[0]);
                Object nested = method.invoke(value, new Object[0]);
                if (nested != null && String.valueOf(nested).trim().length() > 0) {
                    return nested;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static String normalizeCustomizationType(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.length() == 0 || "-".equals(text) || "--".equals(text)
                || "null".equalsIgnoreCase(text) || "unknown".equalsIgnoreCase(text)) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ENGLISH);

        Matcher propMatcher = Pattern.compile("(?:^|[^0-9])90015[^0-9-]*(-?\\d+)").matcher(text);
        if (propMatcher.find()) {
            String fromInt = customizationTypeFromIntString(propMatcher.group(1));
            if (fromInt.length() > 0) {
                return fromInt;
            }
        }

        String fromInt = customizationTypeFromIntString(text);
        if (fromInt.length() > 0) {
            return fromInt;
        }
        if (lower.indexOf("custom") >= 0 || lower.endsWith("__c")) {
            return "custom";
        }
        if (lower.indexOf("overlay") >= 0 || lower.indexOf("overlaid") >= 0) {
            return "overlay";
        }
        if (lower.indexOf("base") >= 0 || lower.indexOf("origin") >= 0 || "none".equals(lower)) {
            return "base";
        }
        return "";
    }

    private static String customizationTypeFromIntString(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (!trimmed.matches("[-+]?\\d+")) {
            return "";
        }
        try {
            int numeric = Integer.parseInt(trimmed);
            // Developer Studio ICustomizableObject.CustomizationType.toInt():
            // NONE=0, OVERLAY=2, CUSTOM=4. Do not treat arbitrary bit values as
            // customization types; values like ObjectOverlayState.NEW=1 are overlay
            // state metadata, not the customization type shown in object lists.
            if (numeric == 4) {
                return "custom";
            }
            if (numeric == 2) {
                return "overlay";
            }
            if (numeric == 0) {
                return "base";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static long timestampValue(Timestamp timestamp) {
        if (timestamp == null) {
            return Long.MIN_VALUE;
        }
        try {
            return timestamp.getValue();
        } catch (Throwable ex) {
            return Long.MIN_VALUE;
        }
    }

    private synchronized void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = getCacheFile();
        if (file == null || !file.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().length() == 0) {
                    continue;
                }
                String[] parts = line.split("\\t", -1);
                if (parts.length < 5) {
                    continue;
                }
                String customizationType = parts.length >= 6 ? unescape(parts[5]) : "";
                String contextKey = parts.length >= 7 ? unescape(parts[6]) : "";
                CacheEntry entry = new CacheEntry(unescape(parts[0]), unescape(parts[1]), unescape(parts[2]), parseLong(parts[3]), unescape(parts[4]), customizationType, contextKey);
                entries.put(entry.key(), entry);
            }
        } catch (Exception ex) {
            Activator.logWarning("Could not read Yrell Migrator metadata cache.", ex);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    private synchronized void save() {
        File file = getCacheFile();
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
            writer.write("# Yrell Migrator metadata cache. Safe to delete.\n");
            for (CacheEntry entry : entries.values()) {
                writer.write(escape(entry.server));
                writer.write('\t');
                writer.write(escape(entry.type));
                writer.write('\t');
                writer.write(escape(entry.name));
                writer.write('\t');
                writer.write(String.valueOf(entry.modified));
                writer.write('\t');
                writer.write(escape(entry.changedBy));
                writer.write('\t');
                writer.write(escape(entry.customizationType));
                writer.write('\t');
                writer.write(escape(entry.contextKey));
                writer.write('\n');
            }
        } catch (Exception ex) {
            Activator.logWarning("Could not write Yrell Migrator metadata cache.", ex);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
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

    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }

    private File getCacheFile() {
        Activator plugin = Activator.getDefault();
        if (plugin != null) {
            IPath state = plugin.getStateLocation();
            if (state != null) {
                return state.append(FILE_NAME).toFile();
            }
        }
        String home = System.getProperty("user.home");
        return home == null ? null : new File(new File(home, ".yrell-migrator"), FILE_NAME);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return Long.MIN_VALUE;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value == null ? "" : value;
        }
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaping) {
                if (c == 't') builder.append('\t');
                else if (c == 'n') builder.append('\n');
                else if (c == 'r') builder.append('\r');
                else builder.append(c);
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else {
                builder.append(c);
            }
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }

    public static final class CacheStats {
        public int added;
        public int updated;
        public int unchanged;
        public int removed;
        public int errors;
        public int stores;
        public int types;
        public int objects;
        public int skippedByCustomization;
        public int skippedBaseCustomization;
        public int includedNonCustomizable;
        private final List<TypeStats> typeStats = new ArrayList<TypeStats>();

        public void add(CacheStats other) {
            if (other == null) {
                return;
            }
            added += other.added;
            updated += other.updated;
            unchanged += other.unchanged;
            removed += other.removed;
            errors += other.errors;
            stores += other.stores;
            types += other.types;
            objects += other.objects;
            skippedByCustomization += other.skippedByCustomization;
            skippedBaseCustomization += other.skippedBaseCustomization;
            includedNonCustomizable += other.includedNonCustomizable;
            typeStats.addAll(other.typeStats);
        }

        public void addTypeStats(String server, String typeName, int cached, int skippedByInclude, int skippedByExclude,
                int skippedByCustomization, int skippedBaseCustomization, int includedNonCustomizable) {
            typeStats.add(new TypeStats(server, typeName, cached, skippedByInclude, skippedByExclude,
                    skippedByCustomization, skippedBaseCustomization, includedNonCustomizable));
        }

        public List<TypeStats> getTypeStats() {
            return Collections.unmodifiableList(typeStats);
        }

        public String toSummary() {
            return stores + " environment(s), " + types + " type(s), " + objects + " object(s), "
                    + added + " added, " + updated + " updated, " + unchanged + " unchanged"
                    + (removed > 0 ? ", " + removed + " removed" : "")
                    + (skippedByCustomization > 0 ? ", " + skippedByCustomization + " skipped by customization" : "")
                    + (skippedBaseCustomization > 0 ? " (" + skippedBaseCustomization + " Base)" : "")
                    + (includedNonCustomizable > 0 ? ", " + includedNonCustomizable + " non-customizable/catalog included" : "")
                    + (errors > 0 ? ", " + errors + " errors" : "");
        }

        public String toTypeSummary(int maxLines) {
            if (typeStats.isEmpty()) {
                return "";
            }
            int limit = maxLines <= 0 ? typeStats.size() : Math.min(maxLines, typeStats.size());
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                if (b.length() > 0) {
                    b.append('\n');
                }
                b.append(typeStats.get(i).toSummary());
            }
            if (limit < typeStats.size()) {
                b.append('\n').append("... ").append(typeStats.size() - limit).append(" more type summary row(s)");
            }
            return b.toString();
        }
    }

    public static final class TypeStats {
        public final String server;
        public final String typeName;
        public final int cached;
        public final int skippedByInclude;
        public final int skippedByExclude;
        public final int skippedByCustomization;
        public final int skippedBaseCustomization;
        public final int includedNonCustomizable;

        TypeStats(String server, String typeName, int cached, int skippedByInclude, int skippedByExclude,
                int skippedByCustomization, int skippedBaseCustomization, int includedNonCustomizable) {
            this.server = server == null ? "" : server;
            this.typeName = typeName == null ? "" : typeName;
            this.cached = cached;
            this.skippedByInclude = skippedByInclude;
            this.skippedByExclude = skippedByExclude;
            this.skippedByCustomization = skippedByCustomization;
            this.skippedBaseCustomization = skippedBaseCustomization;
            this.includedNonCustomizable = includedNonCustomizable;
        }

        public String toSummary() {
            StringBuilder b = new StringBuilder();
            b.append(server).append(": ").append(typeName).append(" - ").append(cached).append(" cached");
            if (skippedBaseCustomization > 0) {
                b.append(", ").append(skippedBaseCustomization).append(" Base skipped");
            } else if (skippedByCustomization > 0) {
                b.append(", ").append(skippedByCustomization).append(" skipped by customization");
            }
            if (includedNonCustomizable > 0) {
                b.append(", ").append(includedNonCustomizable).append(" included as non-customizable/catalog");
            }
            if (skippedByInclude > 0 || skippedByExclude > 0) {
                b.append(", ").append(skippedByInclude).append(" skipped by include, ")
                        .append(skippedByExclude).append(" skipped by exclude");
            }
            return b.toString();
        }
    }

    public static final class CacheEntry implements Comparable<CacheEntry> {
        public final String server;
        public final String type;
        public final String name;
        public final long modified;
        public final String changedBy;
        public final String customizationType;
        public final String contextKey;

        public CacheEntry(String server, String type, String name, long modified, String changedBy) {
            this(server, type, name, modified, changedBy, "");
        }

        public CacheEntry(String server, String type, String name, long modified, String changedBy, String customizationType) {
            this(server, type, name, modified, changedBy, customizationType, "");
        }

        public CacheEntry(String server, String type, String name, long modified, String changedBy, String customizationType, String contextKey) {
            this.server = server == null ? "" : server;
            this.type = type == null ? "" : type;
            this.name = name == null ? "" : name;
            this.modified = modified;
            this.changedBy = changedBy == null ? "" : changedBy;
            this.customizationType = customizationType == null ? "" : customizationType;
            this.contextKey = contextKey == null ? "" : contextKey;
        }

        public String key() {
            return server.toLowerCase(Locale.ENGLISH) + '\u0000' + type.toLowerCase(Locale.ENGLISH) + '\u0000' + name.toLowerCase(Locale.ENGLISH);
        }

        boolean sameMetadata(CacheEntry other) {
            return other != null && modified == other.modified && changedBy.equals(other.changedBy)
                    && contextKey.equals(other.contextKey) && customizationType.equals(other.customizationType);
        }

        public boolean hasReliableMetadata() {
            return modified != Long.MIN_VALUE || (changedBy != null && changedBy.length() > 0);
        }

        public int compareTo(CacheEntry other) {
            int result = String.CASE_INSENSITIVE_ORDER.compare(type, other.type);
            if (result == 0) {
                result = String.CASE_INSENSITIVE_ORDER.compare(name, other.name);
            }
            return result;
        }
    }

    public static final class SearchPattern {
        private static final int FIELD_NAME = 0;
        private static final int FIELD_TYPE = 1;
        private static final int FIELD_USER = 2;
        private static final int FIELD_ANY = 3;

        private final String original;
        private final String regex;
        private final boolean wildcard;
        private final int field;

        private SearchPattern(String original, String regex, boolean wildcard, int field) {
            this.original = original == null ? "" : original;
            this.regex = regex;
            this.wildcard = wildcard;
            this.field = field;
        }

        public static SearchPattern compile(String query) {
            String value = query == null ? "" : query.trim();
            int field = FIELD_NAME;

            String lower = value.toLowerCase(Locale.ENGLISH);
            if (lower.startsWith("name:")) {
                value = value.substring(5).trim();
                field = FIELD_NAME;
            } else if (lower.startsWith("type:")) {
                value = value.substring(5).trim();
                field = FIELD_TYPE;
            } else if (lower.startsWith("objecttype:")) {
                value = value.substring(11).trim();
                field = FIELD_TYPE;
            } else if (lower.startsWith("user:")) {
                value = value.substring(5).trim();
                field = FIELD_USER;
            } else if (lower.startsWith("changedby:")) {
                value = value.substring(10).trim();
                field = FIELD_USER;
            } else if (lower.startsWith("changed-by:")) {
                value = value.substring(11).trim();
                field = FIELD_USER;
            } else if (lower.startsWith("any:")) {
                value = value.substring(4).trim();
                field = FIELD_ANY;
            } else if (lower.startsWith("all:")) {
                value = value.substring(4).trim();
                field = FIELD_ANY;
            }

            if (value.length() == 0) {
                return new SearchPattern("", null, true, field);
            }
            if ("*".equals(value) || "%".equals(value)) {
                return new SearchPattern(value, null, true, field);
            }
            boolean hasWildcard = value.indexOf('*') >= 0 || value.indexOf('%') >= 0 || value.indexOf('?') >= 0 || value.indexOf('_') >= 0;
            if (!hasWildcard) {
                return new SearchPattern(value.toLowerCase(Locale.ENGLISH), null, false, field);
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '*' || c == '%') {
                    builder.append(".*");
                } else if (c == '?' || c == '_') {
                    builder.append('.');
                } else {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        builder.append('\\');
                    }
                    builder.append(c);
                }
            }
            return new SearchPattern(value, "(?i)^" + builder.toString() + "$", true, field);
        }

        public boolean matchesEntry(String name, String type, String changedBy) {
            if (field == FIELD_TYPE) {
                return matches(type);
            }
            if (field == FIELD_USER) {
                return matches(changedBy);
            }
            if (field == FIELD_ANY) {
                return matches(name) || matches(type) || matches(changedBy);
            }
            return matches(name);
        }

        public boolean matches(String value) {
            if (value == null) {
                value = "";
            }
            if (original.length() == 0 || "*".equals(original) || "%".equals(original)) {
                return true;
            }
            if (regex != null) {
                return value.matches(regex);
            }
            return value.toLowerCase(Locale.ENGLISH).indexOf(original) >= 0;
        }

        public boolean isWildcard() {
            return wildcard;
        }
    }
}
