package se.yrell.migrator.bmc;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationResult;

/**
 * Best-effort content migration for container-like Developer Studio objects such as Applications
 * and Packing Lists. BMC exposes membership differently per object/version, so this class uses
 * both known getters and reflection. The important improvement over the early implementation is
 * that String based membership such as getForms(), getActiveLinks() and getMembers() is resolved
 * into real IModelType/name pairs before migration.
 */
public final class BmcContainerContentMigrator {
    private static final int MAX_CONTENT_ITEMS = 750;
    private final BmcWorkflowMigrator workflowMigrator;

    public BmcContainerContentMigrator(BmcWorkflowMigrator workflowMigrator) {
        this.workflowMigrator = workflowMigrator;
    }

    public boolean isContainerType(CompareResult result) {
        if (result == null) {
            return false;
        }
        String type = normalize(result.getObjectType());
        return type.indexOf("application") >= 0 || type.indexOf("packinglist") >= 0;
    }


    public ContainerContentPreview previewContent(CompareResult container, MigrationDirection direction) {
        if (container == null || direction == null || !isContainerType(container)) {
            return ContainerContentPreview.empty("No Application/Packing List content preview is needed.");
        }
        IStore fromStore = direction == MigrationDirection.SOURCE_TO_TARGET ? container.getSourceStore() : container.getTargetStore();
        if (fromStore == null || container.getModelType() == null) {
            return ContainerContentPreview.empty("Content preview is not available because source store/type metadata is missing.");
        }
        try {
            Object sourceObject = fromStore.getObject(container.getModelType(), container.getObjectName());
            if (sourceObject == null) {
                return ContainerContentPreview.empty("Content preview is not available because the container object could not be loaded.");
            }
            List<ContentRef> refs = discoverContent(sourceObject, fromStore);
            sortDependencyFriendly(refs);
            Map<String, TypeCount> perType = new LinkedHashMap<String, TypeCount>();
            List<String> sample = new ArrayList<String>();
            int index = 0;
            for (ContentRef ref : refs) {
                String refTypeName = ref.type == null ? "" : ref.type.getTypeName();
                typeCount(perType, refTypeName).planned++;
                if (sample.size() < 12) {
                    sample.add(refTypeName + " " + ref.name);
                }
                index++;
            }
            return new ContainerContentPreview(refs.size(), contentSummary(perType), sample, refs.size() >= MAX_CONTENT_ITEMS);
        } catch (Throwable ex) {
            return ContainerContentPreview.empty("Content preview failed: " + (ex.getLocalizedMessage() == null ? ex.getClass().getName() : ex.getLocalizedMessage()));
        }
    }

    public MigrationResult migrateContent(CompareResult container, MigrationDirection direction, IProgressMonitor monitor) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        if (container == null || direction == null || !isContainerType(container)) {
            return MigrationResult.verified(container, "No container content migration was needed.");
        }
        IStore fromStore = direction == MigrationDirection.SOURCE_TO_TARGET ? container.getSourceStore() : container.getTargetStore();
        IStore toStore = direction == MigrationDirection.SOURCE_TO_TARGET ? container.getTargetStore() : container.getSourceStore();
        if (fromStore == null || toStore == null || container.getModelType() == null) {
            return MigrationResult.failure(container, "Container content could not be migrated because source/target/type metadata is missing.");
        }
        try {
            Object sourceObject = fromStore.getObject(container.getModelType(), container.getObjectName());
            if (sourceObject == null) {
                return MigrationResult.failure(container, "Could not load container object to inspect its content.");
            }
            List<ContentRef> refs = discoverContent(sourceObject, fromStore);
            sortDependencyFriendly(refs);
            if (refs.isEmpty()) {
                return MigrationResult.warning(container, "Container was migrated. No content references were exposed by this Developer Studio API version.");
            }
            int ok = 0;
            int failed = 0;
            int cancelled = 0;
            Map<String, TypeCount> perType = new LinkedHashMap<String, TypeCount>();
            StringBuilder failureText = new StringBuilder();
            int index = 0;
            for (ContentRef ref : refs) {
                if (safeMonitor.isCanceled()) {
                    cancelled = refs.size() - index;
                    break;
                }
                index++;
                String refTypeName = ref.type == null ? "" : ref.type.getTypeName();
                typeCount(perType, refTypeName).planned++;
                safeMonitor.subTask("Migrating container content " + index + "/" + refs.size() + ": " + refTypeName + " " + ref.name);
                CompareResult child = new CompareResult();
                child.setStatus(CompareStatus.CHANGED);
                child.setSourceStore(fromStore);
                child.setTargetStore(toStore);
                child.setModelType(ref.type);
                child.setObjectType(refTypeName);
                child.setObjectName(ref.name);
                child.setSourceServer(fromStore.getName());
                child.setTargetServer(toStore.getName());
                MigrationResult migrated = workflowMigrator.migrate(child, direction, safeMonitor);
                if (migrated.isSuccess()) {
                    ok++;
                    typeCount(perType, refTypeName).succeeded++;
                } else {
                    failed++;
                    typeCount(perType, refTypeName).failed++;
                    if (failureText.length() < 1200) {
                        if (failureText.length() > 0) failureText.append("; ");
                        failureText.append(refTypeName).append(' ').append(ref.name).append(": ").append(migrated.getDetail());
                    }
                }
            }
            String summary = "Content discovered: " + refs.size() + " item(s). " + contentSummary(perType)
                    + "Migrated " + ok + " content item(s) referenced by " + container.getObjectType() + " " + container.getObjectName() + ".";
            if (cancelled > 0 || safeMonitor.isCanceled()) {
                return MigrationResult.cancelled(container, summary + " Cancelled before " + cancelled + " remaining content item(s) were processed.");
            }
            if (failed > 0) {
                return MigrationResult.failure(container, "Container object was migrated, but " + failed + " of " + refs.size()
                        + " content item(s) failed. " + contentSummary(perType) + failureText.toString());
            }
            return MigrationResult.success(container, false, summary);
        } catch (Throwable ex) {
            return MigrationResult.failure(container, ex.getLocalizedMessage() == null ? ex.getClass().getName() : ex.getLocalizedMessage());
        }
    }

    private List<ContentRef> discoverContent(Object sourceObject, IStore store) {
        List<ContentRef> refs = new ArrayList<ContentRef>();
        Set<String> seen = new HashSet<String>();

        collectKnownGetter(sourceObject, store, refs, seen, "getForms", "Form");
        collectKnownGetter(sourceObject, store, refs, seen, "getApplicationForms", "Form");
        collectKnownGetter(sourceObject, store, refs, seen, "getDataForms", "Form");
        collectKnownGetter(sourceObject, store, refs, seen, "getStatisticsForms", "Form");
        collectKnownGetter(sourceObject, store, refs, seen, "getFormAccessPoints", "Form");
        collectKnownGetter(sourceObject, store, refs, seen, "getActiveLinks", "Active Link");
        collectKnownGetter(sourceObject, store, refs, seen, "getFilters", "Filter");
        collectKnownGetter(sourceObject, store, refs, seen, "getEscalations", "Escalation");
        collectKnownGetter(sourceObject, store, refs, seen, "getActiveLinkGuides", "Active Link Guide");
        collectKnownGetter(sourceObject, store, refs, seen, "getActiveLinkGuideAccessPoints", "Active Link Guide");
        collectKnownGetter(sourceObject, store, refs, seen, "getFilterGuides", "Filter Guide");
        collectKnownGetter(sourceObject, store, refs, seen, "getFilterGuideAccessPoints", "Filter Guide");
        collectKnownGetter(sourceObject, store, refs, seen, "getMenus", "Menu");
        collectKnownGetter(sourceObject, store, refs, seen, "getApplications", "Application");
        collectKnownGetter(sourceObject, store, refs, seen, "getPackingLists", "Packing List");
        collectKnownGetter(sourceObject, store, refs, seen, "getWebServices", "Web Service");
        collectKnownGetter(sourceObject, store, refs, seen, "getImages", "Image");
        collectKnownGetter(sourceObject, store, refs, seen, "getDistributedMaps", "Distributed Map");
        collectKnownGetter(sourceObject, store, refs, seen, "getDistributedPools", "Distributed Pool");
        collectKnownGetter(sourceObject, store, refs, seen, "getContent", null);
        collectKnownGetter(sourceObject, store, refs, seen, "getMembers", null);

        Method[] methods = sourceObject.getClass().getMethods();
        for (int i = 0; i < methods.length && refs.size() < MAX_CONTENT_ITEMS; i++) {
            Method method = methods[i];
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ENGLISH);
            if (!(name.indexOf("content") >= 0 || name.indexOf("member") >= 0 || name.indexOf("item") >= 0 || name.indexOf("reference") >= 0 || name.indexOf("object") >= 0)) {
                continue;
            }
            if (name.equals("getclass") || name.equals("getname") || name.equals("gettype") || name.equals("getobjecttype")) {
                continue;
            }
            try {
                Object value = method.invoke(sourceObject, new Object[0]);
                collectRefs(value, null, refs, seen, store);
            } catch (Throwable ignored) {
            }
        }
        return refs;
    }

    private void collectKnownGetter(Object sourceObject, IStore store, List<ContentRef> refs, Set<String> seen, String getter, String typeName) {
        if (sourceObject == null || getter == null || refs.size() >= MAX_CONTENT_ITEMS) {
            return;
        }
        try {
            Method method = sourceObject.getClass().getMethod(getter, new Class[0]);
            IModelType explicitType = typeName == null ? null : findType(store, typeName);
            Object value = method.invoke(sourceObject, new Object[0]);
            collectRefs(value, explicitType, refs, seen, store);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("rawtypes")
    private void collectRefs(Object value, IModelType explicitType, List<ContentRef> refs, Set<String> seen, IStore store) {
        if (value == null || refs.size() >= MAX_CONTENT_ITEMS) {
            return;
        }
        if (value instanceof IModelItem) {
            IModelItem item = (IModelItem) value;
            addRef(reflectedType(item), item.getName(), refs, seen);
            return;
        }
        if (value instanceof CharSequence) {
            addRef(explicitType, String.valueOf(value).trim(), refs, seen);
            return;
        }
        if (value instanceof Map) {
            Map map = (Map) value;
            for (Object entryObject : map.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObject;
                Object key = entry.getKey();
                Object mapValue = entry.getValue();
                if (key instanceof IModelType) {
                    collectRefs(mapValue, (IModelType) key, refs, seen, store);
                } else if (explicitType != null) {
                    collectRefs(key, explicitType, refs, seen, store);
                    collectRefs(mapValue, explicitType, refs, seen, store);
                } else {
                    collectRefs(key, null, refs, seen, store);
                    collectRefs(mapValue, null, refs, seen, store);
                }
                if (refs.size() >= MAX_CONTENT_ITEMS) return;
            }
            return;
        }
        if (value instanceof Collection) {
            for (Object child : (Collection<?>) value) {
                collectRefs(child, explicitType, refs, seen, store);
                if (refs.size() >= MAX_CONTENT_ITEMS) return;
            }
            return;
        }
        if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) {
                collectRefs(child, explicitType, refs, seen, store);
                if (refs.size() >= MAX_CONTENT_ITEMS) return;
            }
            return;
        }
        Class<?> clazz = value.getClass();
        if (clazz.isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len && refs.size() < MAX_CONTENT_ITEMS; i++) {
                collectRefs(Array.get(value, i), explicitType, refs, seen, store);
            }
            return;
        }
        IModelType type = explicitType == null ? reflectedType(value) : explicitType;
        String name = reflectedName(value);
        if (type != null && name.length() > 0) {
            addRef(type, name, refs, seen);
        }
    }

    private void addRef(IModelType type, String name, List<ContentRef> refs, Set<String> seen) {
        if (type == null || name == null) {
            return;
        }
        String clean = name.trim();
        if (clean.length() == 0 || clean.indexOf('@') >= 0 || "null".equalsIgnoreCase(clean)) {
            return;
        }
        String key = type.getTypeName().toLowerCase(Locale.ENGLISH) + "\u0000" + clean.toLowerCase(Locale.ENGLISH);
        if (seen.add(key)) {
            refs.add(new ContentRef(type, clean));
        }
    }

    private IModelType findType(IStore store, String typeName) {
        if (store == null || typeName == null || typeName.length() == 0) {
            return null;
        }
        String wanted = normalize(typeName);
        try {
            Set<IModelType> types = store.getSupportedTypes();
            if (types == null) {
                return null;
            }
            IModelType fallback = null;
            for (IModelType type : types) {
                if (type == null || type.getTypeName() == null) {
                    continue;
                }
                String current = normalize(type.getTypeName());
                if (current.equals(wanted)) {
                    return type;
                }
                if (fallback == null && (current.indexOf(wanted) >= 0 || wanted.indexOf(current) >= 0)) {
                    fallback = type;
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private IModelType reflectedType(Object value) {
        String[] methods = new String[] { "getType", "getModelType", "getObjectType", "getItemType" };
        for (int i = 0; i < methods.length; i++) {
            try {
                Object type = value.getClass().getMethod(methods[i], new Class[0]).invoke(value, new Object[0]);
                if (type instanceof IModelType) {
                    return (IModelType) type;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String reflectedName(Object value) {
        String[] methods = new String[] { "getName", "getObjectName", "getItemName", "getLabel", "getFormName", "getFieldName" };
        for (int i = 0; i < methods.length; i++) {
            try {
                Object name = value.getClass().getMethod(methods[i], new Class[0]).invoke(value, new Object[0]);
                if (name != null && String.valueOf(name).trim().length() > 0) {
                    return String.valueOf(name).trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private void sortDependencyFriendly(List<ContentRef> refs) {
        if (refs == null || refs.size() < 2) {
            return;
        }
        Collections.sort(refs, new Comparator<ContentRef>() {
            public int compare(ContentRef left, ContentRef right) {
                int weight = refWeight(left) - refWeight(right);
                if (weight != 0) {
                    return weight;
                }
                String leftType = left == null || left.type == null ? "" : left.type.getTypeName();
                String rightType = right == null || right.type == null ? "" : right.type.getTypeName();
                int type = leftType.compareToIgnoreCase(rightType);
                if (type != 0) {
                    return type;
                }
                String leftName = left == null || left.name == null ? "" : left.name;
                String rightName = right == null || right.name == null ? "" : right.name;
                return leftName.compareToIgnoreCase(rightName);
            }
        });
    }

    private int refWeight(ContentRef ref) {
        String type = ref == null || ref.type == null ? "" : normalize(ref.type.getTypeName());
        if (type.indexOf("menu") >= 0) return 10;
        if (type.equals("form") || type.indexOf("form") >= 0) return 20;
        if (type.indexOf("activelink") >= 0) return 30;
        if (type.indexOf("filter") >= 0) return 40;
        if (type.indexOf("escalation") >= 0) return 50;
        if (type.indexOf("guide") >= 0) return 60;
        if (type.indexOf("webservice") >= 0) return 70;
        if (type.indexOf("application") >= 0 || type.indexOf("packinglist") >= 0) return 90;
        return 80;
    }

    private TypeCount typeCount(Map<String, TypeCount> perType, String typeName) {
        String key = typeName == null || typeName.length() == 0 ? "<unknown>" : typeName;
        TypeCount count = perType.get(key);
        if (count == null) {
            count = new TypeCount(key);
            perType.put(key, count);
        }
        return count;
    }

    private String contentSummary(Map<String, TypeCount> perType) {
        if (perType == null || perType.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("Content by type: ");
        int index = 0;
        for (TypeCount count : perType.values()) {
            if (index++ > 0) {
                b.append("; ");
            }
            b.append(count.typeName).append(' ').append(count.succeeded).append('/').append(count.planned);
            if (count.failed > 0) {
                b.append(" failed=").append(count.failed);
            }
        }
        b.append(". ");
        return b.toString();
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ENGLISH).replace(" ", "").replace("_", "");
    }

    private static final class TypeCount {
        final String typeName;
        int planned;
        int succeeded;
        int failed;
        TypeCount(String typeName) {
            this.typeName = typeName == null ? "" : typeName;
        }
    }


    public static final class ContainerContentPreview {
        private final int total;
        private final String byTypeSummary;
        private final List<String> sampleItems;
        private final boolean truncated;
        private final String message;

        ContainerContentPreview(int total, String byTypeSummary, List<String> sampleItems, boolean truncated) {
            this.total = total;
            this.byTypeSummary = byTypeSummary == null ? "" : byTypeSummary;
            this.sampleItems = Collections.unmodifiableList(new ArrayList<String>(sampleItems == null ? Collections.<String>emptyList() : sampleItems));
            this.truncated = truncated;
            this.message = "";
        }

        static ContainerContentPreview empty(String message) {
            ContainerContentPreview preview = new ContainerContentPreview(0, "", Collections.<String>emptyList(), false, message);
            return preview;
        }

        private ContainerContentPreview(int total, String byTypeSummary, List<String> sampleItems, boolean truncated, String message) {
            this.total = total;
            this.byTypeSummary = byTypeSummary == null ? "" : byTypeSummary;
            this.sampleItems = Collections.unmodifiableList(new ArrayList<String>(sampleItems == null ? Collections.<String>emptyList() : sampleItems));
            this.truncated = truncated;
            this.message = message == null ? "" : message;
        }

        public int getTotal() { return total; }
        public String getByTypeSummary() { return byTypeSummary; }
        public List<String> getSampleItems() { return sampleItems; }
        public boolean isTruncated() { return truncated; }
        public String getMessage() { return message; }

        public String toSummary() {
            if (message.length() > 0) {
                return message;
            }
            StringBuilder b = new StringBuilder();
            b.append(total).append(" content item(s) discovered");
            if (byTypeSummary.length() > 0) {
                b.append(". ").append(byTypeSummary.trim());
            }
            if (!sampleItems.isEmpty()) {
                b.append(" Sample: ");
                for (int i = 0; i < sampleItems.size(); i++) {
                    if (i > 0) b.append("; ");
                    b.append(sampleItems.get(i));
                }
                if (truncated) {
                    b.append("; ...");
                }
            }
            return b.toString();
        }
    }

    private static final class ContentRef {
        final IModelType type;
        final String name;
        ContentRef(IModelType type, String name) {
            this.type = type;
            this.name = name;
        }
    }
}
