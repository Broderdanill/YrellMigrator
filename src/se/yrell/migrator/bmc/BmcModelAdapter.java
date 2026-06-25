package se.yrell.migrator.bmc;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.api.Timestamp;
import com.bmc.arsys.studio.model.ModelException;
import com.bmc.arsys.studio.model.compare.Difference;
import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.mask.IModelMaskObject;
import com.bmc.arsys.studio.model.mask.IMaskOptions;
import com.bmc.arsys.studio.model.store.IFormObject;
import com.bmc.arsys.studio.model.store.IModelObject;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.store.StoreManager;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.core.CompareEvidence;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.DiffDetail;
import se.yrell.migrator.bmc.BmcMetadataCache.CacheEntry;
import se.yrell.migrator.bmc.BmcDefinitionCache.DefinitionEntry;
import se.yrell.migrator.bmc.BmcMetadataCache.CacheStats;
import se.yrell.migrator.bmc.BmcMetadataCache.SearchPattern;
import se.yrell.migrator.bmc.BmcMetadataCache.SyncProgress;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.Activator;
import se.yrell.migrator.config.CompareSettings;

/**
 * Thin adapter around the public Developer Studio model API.
 *
 * The UI and result model deliberately do not depend on BMC diff internals. Detailed compare is
 * launched by {@link BmcDiffLauncher} through reflection so this adapter remains stable across
 * Developer Studio releases as long as the public model API is kept compatible.
 */
public final class BmcModelAdapter {
    private CompareSettings settings = CompareSettings.load();
    private final Map<String, IModelObject> objectCache = new LinkedHashMap<String, IModelObject>();
    private final BmcMetadataCache metadataCache = new BmcMetadataCache();
    private final BmcDefinitionCache definitionCache = new BmcDefinitionCache();
    private final BmcBinaryObjectFingerprintService binaryFingerprintService = new BmcBinaryObjectFingerprintService();

    public void clearCache() {
        objectCache.clear();
    }

    public void reloadSettings() {
        this.settings = CompareSettings.load();
    }

    public BmcDefinitionCache.CacheStartupStats getCacheStartupStatsFast() {
        return definitionCache.startupStatsFast();
    }

    public BmcDefinitionCache.CacheLoadStats getLastDefinitionCacheLoadStats() {
        return definitionCache.lastLoadStats();
    }

    public List<IStore> getConnectedStoresExcluding(IStore sourceStore) {
        Collection<IStore> stores = StoreManager.getInstance().getStores();
        List<IStore> result = new ArrayList<IStore>();
        if (stores == null) {
            return result;
        }
        String sourceName = sourceStore == null ? null : sourceStore.getName();
        for (IStore store : stores) {
            if (store == null || !store.isConnected()) {
                continue;
            }
            if (sourceName != null && sourceName.equalsIgnoreCase(store.getName())) {
                continue;
            }
            result.add(store);
        }
        return result;
    }

    public CompareResult compare(IModelItem sourceItem, IStore targetStore, IProgressMonitor monitor) {
        if (sourceItem == null) {
            CompareResult result = new CompareResult();
            result.setEvidence(CompareEvidence.UNKNOWN);
            result.setEvidenceDetail("No comparison could be started because no source object was selected.");
            result.setStatus(CompareStatus.ERROR);
            result.setDetail("No source object selected.");
            return result;
        }
        return compare(sourceItem.getStore(), targetStore, sourceItem.getItemType(), sourceItem.getName(), monitor);
    }

    /**
     * Compares by key while preserving the original source/target orientation.
     * Useful after migration because a row may have changed on either side and needs to be refreshed.
     */
    public CompareResult compare(IStore sourceStore, IStore targetStore, IModelType type, String name, IProgressMonitor monitor) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        CompareResult result = new CompareResult();
        result.setEvidence(CompareEvidence.LIVE);
        result.setEvidenceDetail("Live compare opens source and target objects through the Developer Studio model API.");

        result.setSourceStore(sourceStore);
        result.setTargetStore(targetStore);
        result.setModelType(type);
        result.setObjectName(name);
        result.setObjectType(type == null ? "" : type.getTypeName());
        result.setSourceServer(sourceStore == null ? "" : sourceStore.getName());
        result.setTargetServer(targetStore == null ? "" : targetStore.getName());

        if (sourceStore == null || !sourceStore.isConnected()) {
            result.setStatus(CompareStatus.ERROR);
            result.setDetail("Source server is not connected.");
            return result;
        }
        if (targetStore == null || !targetStore.isConnected()) {
            result.setStatus(CompareStatus.ERROR);
            result.setDetail("Target server is not connected.");
            return result;
        }
        if (type == null || name == null || name.length() == 0) {
            result.setStatus(CompareStatus.ERROR);
            result.setDetail("Selected object has no usable type or name.");
            return result;
        }

        try {
            safeMonitor.subTask("Checking " + type.getTypeName() + " " + name);
            IModelItem sourceItem = findTargetItem(sourceStore, type, name);
            IModelItem targetItem = findTargetItem(targetStore, type, name);
            result.setSourceItem(sourceItem);
            result.setTargetItem(targetItem);
            if (sourceItem != null) {
                result.setSourceModified(sourceItem.getLastUpdateTime());
                result.setSourceChangedBy(sourceItem.getLastChangedBy());
                result.setSourceCustomizationType(BmcMetadataCache.customizationType(sourceItem));
            }
            if (targetItem != null) {
                result.setTargetModified(targetItem.getLastUpdateTime());
                result.setTargetChangedBy(targetItem.getLastChangedBy());
                result.setTargetCustomizationType(BmcMetadataCache.customizationType(targetItem));
            }

            IModelObject sourceObject = null;
            IModelObject targetObject = null;
            try {
                sourceObject = getFreshObject(sourceStore, type, name, sourceItem);
            } catch (ModelException ex) {
                // Missing source is handled below. This path is normal for target-only rows.
            }
            try {
                targetObject = getFreshObject(targetStore, type, name, targetItem);
            } catch (ModelException ex) {
                // Missing target is handled below. This path is normal for source-only rows.
            }

            fillLiveDisplayMetadata(result, sourceObject, targetObject);

            if (sourceObject == null && targetObject == null) {
                result.setStatus(CompareStatus.ERROR);
                result.setDifferenceCount(-1);
                result.setDetail("Object does not exist in either environment or could not be loaded by type/name.");
                return result;
            }
            if (sourceObject == null) {
                result.setStatus(CompareStatus.MISSING_IN_SOURCE);
                result.setDifferenceCount(-1);
                result.setDetail("Object exists in target but not in source.");
                return result;
            }
            if (targetObject == null) {
                result.setStatus(CompareStatus.MISSING_IN_TARGET);
                result.setDifferenceCount(-1);
                result.setDetail("Object exists in source but not in target.");
                return result;
            }

            DifferenceReport differences = compareObjects(sourceObject, targetObject);
            appendBinaryProfileDifferences(result.getObjectType(), sourceObject, targetObject, differences);
            result.setDifferenceDetails(differences.getDetails());
            result.setDifferenceCount(differences.getCount());
            if (differences.getCount() == 0) {
                result.setStatus(CompareStatus.EQUAL);
                result.setDetail("No definition differences detected. Config: " + settings.describeLocation());
            } else {
                result.setStatus(CompareStatus.CHANGED);
                result.setDetail(differences.toSummary(settings.getMaxSummaryItems()));
            }
            return result;
        } catch (Exception ex) {
            result.setStatus(CompareStatus.ERROR);
            result.setDifferenceCount(-1);
            result.setDetail(ex.getLocalizedMessage() == null ? ex.getClass().getName() : ex.getLocalizedMessage());
            return result;
        }
    }

    private IModelItem findTargetItem(IStore targetStore, IModelType type, String name) throws ModelException {
        return BmcItemEnumerator.findItem(targetStore, type, name);
    }

    private IModelObject getFreshObject(IStore store, IModelType type, String name, IModelItem item) throws ModelException {
        if (store == null || type == null || name == null || name.length() == 0) {
            return null;
        }
        String cacheKey = createCacheKey(store, type, name, item);
        if (settings.isCacheObjects() && objectCache.containsKey(cacheKey)) {
            return objectCache.get(cacheKey);
        }

        // Use the type/name overload first. This is important for workflow objects in some Developer
        // Studio versions where getItemList(type) is not populated until object-list providers are warmed.
        IModelObject object = null;
        try {
            object = store.getObject(type, name);
        } catch (ModelException ex) {
            if (item != null) {
                object = store.getObject(item);
            } else {
                throw ex;
            }
        }

        if (object != null && settings.isForceReloadObjects()) {
            try {
                store.reloadObject(type, object, null);
            } catch (Throwable ignored) {
                // Some Developer Studio/BMC versions do not accept null criteria flags for all object types.
                // The compare remains usable with the object returned by getObject().
            }
        }

        warmObject(object);
        if (settings.isCacheObjects()) {
            objectCache.put(cacheKey, object);
        }
        return object;
    }



    private IModelObject getFreshObjectNoCache(IStore store, IModelType type, String name) throws ModelException {
        if (store == null || type == null || name == null || name.length() == 0) {
            return null;
        }
        IModelObject object = store.getObject(type, name);
        if (object != null && settings.isForceReloadObjects()) {
            try {
                store.reloadObject(type, object, null);
            } catch (Throwable ignored) {
            }
        }
        warmObject(object);
        return object;
    }

    private String createCacheKey(IStore store, IModelType type, String name, IModelItem item) {
        StringBuilder key = new StringBuilder();
        key.append(store == null ? "" : store.getName()).append('|');
        key.append(type == null ? "" : type.getTypeName()).append('|');
        key.append(name == null ? "" : name);
        if (item != null && item.getLastUpdateTime() != null) {
            key.append('|').append(item.getLastUpdateTime().toString());
        }
        return key.toString();
    }

    private void warmObject(IModelObject object) {
        if (object instanceof IFormObject) {
            IFormObject form = (IFormObject) object;
            try {
                form.getFields();
            } catch (Throwable ignored) {
            }
            try {
                form.getViews();
            } catch (Throwable ignored) {
            }
        }
    }

    private void appendBinaryProfileDifferences(String objectType, IModelObject sourceObject, IModelObject targetObject, DifferenceReport report) {
        if (report == null || !binaryFingerprintService.isBinaryLikeType(objectType)) {
            return;
        }
        List<DiffDetail> binaryDetails = binaryFingerprintService.compare(objectType, sourceObject, targetObject);
        for (int i = 0; i < binaryDetails.size(); i++) {
            report.add(binaryDetails.get(i));
        }
    }

    private DifferenceReport compareObjects(IModelObject sourceObject, IModelObject targetObject) {
        DifferenceReport report = new DifferenceReport();
        if (sourceObject == null || targetObject == null) {
            return report;
        }

        compareMaskObject("Object", sourceObject, targetObject, report, true);
        if (settings.isDeepCompareForms() && sourceObject instanceof IFormObject && targetObject instanceof IFormObject) {
            compareForms((IFormObject) sourceObject, (IFormObject) targetObject, report);
        }
        return report;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void compareMaskObject(String label, IModelObject sourceObject, IModelObject targetObject, DifferenceReport report, boolean allowStrictFallback) {
        if (sourceObject == null || targetObject == null) {
            return;
        }

        int before = report.getCount();
        Set<Integer> ignoreMaskOptions = createIgnoreMaskOptions(sourceObject);
        try {
            Object differences = sourceObject.compareTo(ignoreMaskOptions, (IModelMaskObject) targetObject);
            if (differences instanceof List) {
                addDifferences(label, (List<Difference>) differences, report);
            }
        } catch (Throwable ex) {
            report.add(new DiffDetail(label, "compare error", safeMessage(ex), "", "error"));
        }

        // Some BMC form/model compare implementations intentionally skip nested or display details.
        // The reflective scan adds user-friendly property names; the mask scan remains a last resort.
        if (allowStrictFallback && settings.isFingerprintFallback()) {
            int beforeReflect = report.getCount();
            reflectivePropertyScan(label, sourceObject, targetObject, report);
            if (report.getCount() == beforeReflect && report.getCount() == before) {
                strictMaskScan(label, sourceObject, targetObject, report, ignoreMaskOptions);
            }
        }
    }

    private Set<Integer> createIgnoreMaskOptions(IModelObject object) {
        Set<Integer> ignoreMaskOptions = new LinkedHashSet<Integer>();
        if (settings.isUseBmcDefaultIgnores() && object != null
                && object.getSupportedMaskOptions() != null
                && object.getSupportedMaskOptions().getDefaultIgnore() != null) {
            ignoreMaskOptions.addAll(object.getSupportedMaskOptions().getDefaultIgnore());
        }
        ignoreMaskOptions.addAll(settings.getIgnoreMaskIds());
        return ignoreMaskOptions;
    }

    private void addDifferences(String label, List<Difference> differences, DifferenceReport report) {
        if (differences == null || differences.isEmpty()) {
            return;
        }
        for (Difference difference : differences) {
            addDifference(label, difference, report);
        }
    }

    private void addDifference(String label, Difference difference, DifferenceReport report) {
        if (difference == null) {
            return;
        }
        String name = difference.getName();
        if (name == null || name.length() == 0) {
            name = difference.toString();
        }
        if (settings.shouldIgnoreDifferenceName(name)) {
            return;
        }
        String path = join(label, name);
        if (difference.hasChildren() && difference.getChildren() != null && !difference.getChildren().isEmpty()) {
            for (Difference child : difference.getChildren()) {
                addDifference(path, child, report);
            }
        } else {
            String sourceValue = modelPropertyValue(invokeNoArg(difference, "getLeft"));
            String targetValue = modelPropertyValue(invokeNoArg(difference, "getRight"));
            String kind = stringValue(invokeNoArg(difference, "getPropertyType"));
            if (kind.length() == 0) {
                kind = "BMC model";
            }
            report.add(new DiffDetail(label, name, displayValue(sourceValue), displayValue(targetValue), kind));
        }
    }

    private String modelPropertyValue(Object property) {
        if (property == null) {
            return "";
        }
        Object value = invokeNoArg(property, "getValue");
        if (value != null) {
            return stableValue(value);
        }
        Object name = invokeNoArg(property, "getName");
        if (name != null) {
            return String.valueOf(name);
        }
        return String.valueOf(property);
    }

    private void strictMaskScan(String label, IModelObject sourceObject, IModelObject targetObject, DifferenceReport report, Set<Integer> ignoreMaskOptions) {
        Set<Integer> supported = new LinkedHashSet<Integer>();
        addSupportedMaskOptions(sourceObject, supported);
        addSupportedMaskOptions(targetObject, supported);
        supported.removeAll(ignoreMaskOptions);
        if (supported.isEmpty()) {
            return;
        }
        for (Integer mask : supported) {
            if (mask == null) {
                continue;
            }
            Object left = safeMaskValue(sourceObject, mask);
            Object right = safeMaskValue(targetObject, mask);
            String leftStable = stableValue(left);
            String rightStable = stableValue(right);
            if (!leftStable.equals(rightStable)) {
                report.add(new DiffDetail(label, "mask " + mask, displayValue(leftStable), displayValue(rightStable), "mask"));
            }
        }
    }

    private void addSupportedMaskOptions(IModelObject object, Set<Integer> supported) {
        if (object == null || object.getSupportedMaskOptions() == null) {
            return;
        }
        IMaskOptions options = object.getSupportedMaskOptions();
        if (options.getSupported() != null) {
            supported.addAll(options.getSupported());
        }
    }

    private Object safeMaskValue(IModelObject object, Integer mask) {
        try {
            return object.getMaskValue(mask);
        } catch (Throwable ex) {
            return "<mask-error:" + safeMessage(ex) + ">";
        }
    }

    private void compareForms(IFormObject sourceForm, IFormObject targetForm, DifferenceReport report) {
        compareComponentMap("Field", toComponentMap(safeCollection(invokeNoArg(sourceForm, "getFields"))),
                toComponentMap(safeCollection(invokeNoArg(targetForm, "getFields"))), report);
        compareComponentMap("View", toComponentMap(safeCollection(invokeNoArg(sourceForm, "getViews"))),
                toComponentMap(safeCollection(invokeNoArg(targetForm, "getViews"))), report);
    }

    private void compareComponentMap(String label, Map<String, Object> source, Map<String, Object> target, DifferenceReport report) {
        Set<String> keys = new LinkedHashSet<String>();
        keys.addAll(source.keySet());
        keys.addAll(target.keySet());
        for (String key : keys) {
            Object left = source.get(key);
            Object right = target.get(key);
            if (left == null && right != null) {
                report.add(new DiffDetail(label + " " + key, "exists", "", "present", "missing in source"));
            } else if (left != null && right == null) {
                report.add(new DiffDetail(label + " " + key, "exists", "present", "", "missing in target"));
            } else if (left instanceof IModelObject && right instanceof IModelObject) {
                compareMaskObject(label + " " + key, (IModelObject) left, (IModelObject) right, report, true);
            } else if (!stableValue(left).equals(stableValue(right))) {
                report.add(new DiffDetail(label + " " + key, "definition", displayValue(stableValue(left)), displayValue(stableValue(right)), "property"));
            }
        }
    }

    private void reflectivePropertyScan(String label, Object sourceObject, Object targetObject, DifferenceReport report) {
        if (sourceObject == null || targetObject == null) {
            return;
        }
        Map<String, Method> sourceGetters = getterMap(sourceObject);
        Map<String, Method> targetGetters = getterMap(targetObject);
        Set<String> properties = new LinkedHashSet<String>();
        properties.addAll(sourceGetters.keySet());
        properties.addAll(targetGetters.keySet());
        for (String property : properties) {
            if (settings.shouldIgnoreFingerprintMember(property) || settings.shouldIgnoreDifferenceName(property)) {
                continue;
            }
            Method sourceGetter = sourceGetters.get(property);
            Method targetGetter = targetGetters.get(property);
            if (sourceGetter == null || targetGetter == null) {
                continue;
            }
            Object left = invokeGetter(sourceObject, sourceGetter);
            Object right = invokeGetter(targetObject, targetGetter);
            String leftStable = stableValue(left);
            String rightStable = stableValue(right);
            if (!leftStable.equals(rightStable)) {
                report.add(new DiffDetail(label, property, displayValue(leftStable), displayValue(rightStable), "property"));
            }
        }
    }

    private Map<String, Method> getterMap(Object object) {
        Map<String, Method> result = new TreeMap<String, Method>(String.CASE_INSENSITIVE_ORDER);
        if (object == null) {
            return result;
        }
        Method[] methods = object.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if (isGetter(method)) {
                result.put(propertyName(method), method);
            }
        }
        return result;
    }

    private Object invokeGetter(Object target, Method method) {
        try {
            return method.invoke(target, new Object[0]);
        } catch (Throwable ex) {
            return "<error: " + safeMessage(ex) + ">";
        }
    }

    private Collection<?> safeCollection(Object value) {
        if (value instanceof Collection) {
            return (Collection<?>) value;
        }
        return Collections.emptyList();
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName, new Class[0]);
            return method.invoke(target, new Object[0]);
        } catch (Throwable ex) {
            return null;
        }
    }

    private Map<String, Object> toComponentMap(Collection<?> values) {
        Map<String, Object> result = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
        if (values == null) {
            return result;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            result.put(componentKey(value), value);
        }
        return result;
    }

    private String componentKey(Object value) {
        return componentKey(null, value);
    }

    private String componentKey(String group, Object value) {
        if ("Fields".equalsIgnoreCase(group)) {
            String fieldKey = fieldComponentKey(value);
            if (fieldKey.length() > 0) {
                return fieldKey;
            }
        }
        String permissionKey = permissionComponentKey(value);
        if (permissionKey.length() > 0) {
            return permissionKey;
        }
        Object fieldId = invokeNoArg(value, "getFieldID");
        if (fieldId == null) {
            fieldId = invokeNoArg(value, "getFieldId");
        }
        String name = stringValue(invokeNoArg(value, "getName"));
        if (fieldId instanceof Number) {
            return fieldId.toString() + (name.length() == 0 ? "" : " " + name);
        }
        String label = stringValue(invokeNoArg(value, "getLabel"));
        if (label.length() > 0) {
            return label;
        }
        String alias = stringValue(invokeNoArg(value, "getAlias"));
        if (alias.length() > 0) {
            return alias;
        }
        if (name.length() > 0) {
            return name;
        }
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private String fieldComponentKey(Object value) {
        Object fieldId = firstNonNull(value, new String[] { "getFieldID", "getFieldId", "getId" });
        if (!(fieldId instanceof Number)) {
            return "";
        }
        int id = ((Number) fieldId).intValue();
        String name = firstNonEmpty(value, new String[] { "getName", "getDatabaseName", "getLabel" });
        String canonical = canonicalSystemFieldName(id, name);
        return String.valueOf(id) + (canonical.length() == 0 ? "" : " " + canonical);
    }

    private String canonicalSystemFieldName(int fieldId, String candidate) {
        switch (fieldId) {
            case 1: return "Request ID";
            case 2: return "Submitter";
            case 3: return "Create Date";
            case 4: return "Assigned To";
            case 5: return "Last Modified By";
            case 6: return "Modified Date";
            case 7: return "Status";
            case 8: return "Short Description";
            case 15: return "Status History";
            case 112: return "Assignee Group";
            default:
                return candidate == null ? "" : candidate.trim();
        }
    }

    private boolean isFieldNameProperty(String property) {
        if (property == null) {
            return false;
        }
        String p = property.toLowerCase(Locale.ENGLISH);
        return "name".equals(p) || "databasename".equals(p) || "fieldname".equals(p);
    }

    private String permissionComponentKey(Object value) {
        if (value == null) {
            return "";
        }
        String className = value.getClass().getName().toLowerCase(Locale.ENGLISH);
        Object group = firstNonNull(value, new String[] { "getGroupID", "getGroupId", "getGroup", "getGroupName" });
        Object perm = firstNonNull(value, new String[] { "getPermissionValue", "getPermission", "getPermissionType" });
        if (group == null && className.indexOf("permission") < 0) {
            return "";
        }
        String groupText = stableDisplay(group);
        String permText = stableDisplay(perm);
        if (groupText.length() == 0) {
            return "";
        }
        return "Group " + groupText + (permText.length() == 0 ? "" : " / " + permText);
    }

    private Object firstNonNull(Object object, String[] getterNames) {
        if (object == null || getterNames == null) {
            return null;
        }
        for (int i = 0; i < getterNames.length; i++) {
            Object value = invokeNoArg(object, getterNames[i]);
            if (value != null) {
                String stable = stableDisplay(value);
                if (stable.length() > 0 && !"[]".equals(stable) && !"{}".equals(stable)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String stableValue(Object value) {
        return stableValue(value, 0, new IdentityHashMap<Object, Boolean>());
    }

    private String stableValue(Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return "null";
        }
        if (depth > 5) {
            return String.valueOf(value);
        }
        Class<?> type = value.getClass();
        if (isSimpleValue(type)) {
            return String.valueOf(value);
        }
        if (seen.containsKey(value)) {
            return "<cycle>";
        }
        seen.put(value, Boolean.TRUE);
        try {
            if (type.isArray()) {
                int length = Array.getLength(value);
                StringBuilder builder = new StringBuilder("[");
                for (int i = 0; i < length; i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(stableValue(Array.get(value, i), depth + 1, seen));
                }
                return builder.append(']').toString();
            }
            if (value instanceof Collection) {
                StringBuilder builder = new StringBuilder("[");
                int index = 0;
                for (Object element : (Collection<?>) value) {
                    if (index++ > 0) {
                        builder.append(',');
                    }
                    builder.append(stableValue(element, depth + 1, seen));
                }
                return builder.append(']').toString();
            }
            if (value instanceof Map) {
                TreeMap<String, String> map = new TreeMap<String, String>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    map.put(stableValue(entry.getKey(), depth + 1, seen), stableValue(entry.getValue(), depth + 1, seen));
                }
                return map.toString();
            }
            return reflectValue(value, depth, seen);
        } finally {
            seen.remove(value);
        }
    }

    private boolean isSimpleValue(Class<?> type) {
        return type.isPrimitive()
                || String.class.equals(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class.equals(type)
                || Character.class.equals(type)
                || Enum.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || type.getName().startsWith("java.time.");
    }

    private String reflectValue(Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        Method[] methods = value.getClass().getMethods();
        Arrays.sort(methods, new Comparator<Method>() {
            public int compare(Method left, Method right) {
                return left.getName().compareTo(right.getName());
            }
        });
        StringBuilder builder = new StringBuilder(value.getClass().getName()).append('{');
        int added = 0;
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if (!isGetter(method)) {
                continue;
            }
            String property = propertyName(method);
            if (settings.shouldIgnoreFingerprintMember(property) || settings.shouldIgnoreDifferenceName(property)) {
                continue;
            }
            try {
                Object memberValue = method.invoke(value, new Object[0]);
                if (added++ > 0) {
                    builder.append(',');
                }
                builder.append(property).append('=').append(stableValue(memberValue, depth + 1, seen));
            } catch (Throwable ignored) {
                // Skip getters that need server state or throw in older BMC versions.
            }
        }
        return builder.append('}').toString();
    }

    private boolean isGetter(Method method) {
        if (method == null || method.getParameterTypes().length != 0 || void.class.equals(method.getReturnType())) {
            return false;
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            return false;
        }
        String name = method.getName();
        if ("getClass".equals(name)) {
            return false;
        }
        return name.startsWith("get") || name.startsWith("is");
    }

    private String propertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            name = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2) {
            name = name.substring(2);
        }
        return name.length() == 0 ? method.getName() : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private String join(String parent, String child) {
        if (parent == null || parent.length() == 0) {
            return child == null ? "" : child;
        }
        if (child == null || child.length() == 0) {
            return parent;
        }
        return parent + " / " + child;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String displayValue(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        int max = 4000;
        if (text.length() > max) {
            return text.substring(0, max) + " ...";
        }
        return text;
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }



    public List<IStore> getConnectedStores() {
        Collection<IStore> stores = StoreManager.getInstance().getStores();
        List<IStore> result = new ArrayList<IStore>();
        if (stores == null) {
            return result;
        }
        for (IStore store : stores) {
            if (store != null && store.isConnected()) {
                result.add(store);
            }
        }
        Collections.sort(result, new Comparator<IStore>() {
            public int compare(IStore left, IStore right) {
                return String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName());
            }
        });
        return result;
    }

    public List<BmcTypeGroup> getSearchTypeGroups() {
        return BmcTypeCatalog.getGroups();
    }

    public CacheStats refreshMetadataCache(IStore sourceStore, IStore targetStore, List<IModelType> types, boolean full, IProgressMonitor monitor) {
        List<IStore> stores = new ArrayList<IStore>();
        if (sourceStore != null) {
            stores.add(sourceStore);
        }
        if (targetStore != null && (sourceStore == null || !targetStore.getName().equalsIgnoreCase(sourceStore.getName()))) {
            stores.add(targetStore);
        }
        return refreshMetadataCache(stores, types, full, monitor, null);
    }

    public CacheStats refreshMetadataCache(List<IStore> stores, List<IModelType> types, boolean full, IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        CacheStats total = new CacheStats();
        if (types == null || stores == null) {
            return total;
        }
        List<IStore> connectedStores = new ArrayList<IStore>();
        for (IStore store : stores) {
            if (store != null && store.isConnected() && !containsStore(connectedStores, store)) {
                connectedStores.add(store);
            }
        }
        safeMonitor.beginTask((full ? "Full" : "Quick") + " metadata cache sync", connectedStores.size() * Math.max(1, types.size()));
        if (progress != null) {
            progress.report((full ? "Full" : "Quick") + " cache sync queued for " + connectedStores.size() + " environment(s) and " + types.size() + " object type(s).");
        }
        for (IStore store : connectedStores) {
            if (safeMonitor.isCanceled()) {
                if (progress != null) {
                    progress.report("Metadata cache sync cancelled.");
                }
                break;
            }
            CacheStats stats = metadataCache.refresh(store, types, full, safeMonitor, progress);
            total.add(stats);
            safeMonitor.worked(Math.max(1, types.size()));
        }
        safeMonitor.done();
        if (progress != null) {
            progress.report("Metadata cache sync finished: " + total.toSummary() + ".");
            String typeSummary = total.toTypeSummary(24);
            if (typeSummary.length() > 0) {
                progress.report("Cache policy type summary:\n" + typeSummary);
            }
        }
        return total;
    }


    /**
     * Builds or updates the persistent full definition snapshot cache.
     *
     * This is intentionally Migrator-like: first enumerate objects, then hydrate only objects whose
     * cache snapshot is missing or whose metadata changed since the previous sync. Search/compare can
     * then use the local definition fingerprints without opening every object again.
     */
    public CacheStats syncDefinitionCache(List<IStore> stores, List<IModelType> types, IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        CacheStats total = new CacheStats();
        if (stores == null || types == null) {
            return total;
        }
        List<IStore> connectedStores = new ArrayList<IStore>();
        for (IStore store : stores) {
            if (store != null && store.isConnected() && !containsStore(connectedStores, store)) {
                connectedStores.add(store);
            }
        }
        report(progress, "Sync started. Step 1/2: refreshing object metadata for " + connectedStores.size()
                + " environment(s), " + types.size() + " type(s). Definition cache will deep-read missing/changed objects and store property-level snapshots.");
        CacheStats metadataStats = refreshMetadataCache(connectedStores, types, true, safeMonitor, progress);
        total.add(metadataStats);

        safeMonitor.beginTask("Yrell Migrator full definition cache sync", connectedStores.size() * Math.max(1, types.size()));
        CompareSettings syncSettings = CompareSettings.load();
        report(progress, "Sync step 2/2: updating full definition snapshots from metadata cache"
                + (syncSettings.isSyncForceRefreshDefinitions() ? " (forced rebuild enabled)." : ".")
                + " Cache customization policy is " + syncSettings.describeSyncCacheCustomizationTypes()
                + "; Base rows skipped by metadata sync are not deep-read.");
        for (IStore store : connectedStores) {
            if (safeMonitor.isCanceled()) {
                report(progress, "Sync cancelled before processing " + store.getName() + ".");
                break;
            }
            for (IModelType type : types) {
                if (safeMonitor.isCanceled()) {
                    break;
                }
                if (type == null) {
                    continue;
                }
                String typeName = typeName(type);
                List<CacheEntry> metadata = metadataCache.find(store, Collections.singletonList(type), "*");
                int refreshed = 0;
                int reused = 0;
                int errors = 0;
                int skippedErrors = 0;
                int needsRefresh = 0;
                String firstReuseReason = null;
                String firstRefreshReason = null;
                for (CacheEntry entry : metadata) {
                    BmcDefinitionCache.RefreshReason reason = definitionCache.getRefreshReason(entry);
                    if (reason.shouldRefresh()) {
                        needsRefresh++;
                        if (firstRefreshReason == null) {
                            firstRefreshReason = entry.name + " (" + reason.getReason() + ")";
                        }
                    } else if (firstReuseReason == null) {
                        firstReuseReason = entry.name + " (" + reason.getReason() + ")";
                    }
                }
                report(progress, store.getName() + ": " + typeName + " - checking " + metadata.size()
                        + " cached metadata row(s): " + needsRefresh + " need snapshot refresh, "
                        + (metadata.size() - needsRefresh) + " can be reused.");
                if (firstRefreshReason != null) {
                    report(progress, store.getName() + ": " + typeName + " - first refresh candidate: " + firstRefreshReason + ".");
                }
                if (firstReuseReason != null) {
                    report(progress, store.getName() + ": " + typeName + " - first reused snapshot: " + firstReuseReason + ".");
                }
                Set<String> currentNames = new LinkedHashSet<String>();
                int index = 0;
                for (CacheEntry entry : metadata) {
                    if (safeMonitor.isCanceled()) {
                        break;
                    }
                    currentNames.add(entry.name);
                    index++;
                    BmcDefinitionCache.RefreshReason refreshReason = definitionCache.getRefreshReason(entry);
                    boolean lightweightType = shouldUseLightweightSnapshot(typeName);
                    if (!refreshReason.shouldRefresh()) {
                        if (lightweightType && refreshReason.getReason().indexOf("previous error") >= 0) {
                            // Expensive/container-like types now use metadata-only snapshots by default.
                            // If an older run stored a timeout/error for one of them, replace it with a
                            // lightweight snapshot so future incremental syncs can progress without retrying
                            // the slow Developer Studio hydration path.
                        } else {
                            reused++;
                            // Keep display metadata fresh without rebuilding the deep snapshot.
                            definitionCache.updateMetadataOnly(entry);
                            if (refreshReason.getReason().indexOf("previous error") >= 0) {
                                skippedErrors++;
                            }
                            continue;
                        }
                    }
                    try {
                        if (lightweightType) {
                            if (refreshed == 0 || refreshed % 100 == 0) {
                                report(progress, store.getName() + ": " + typeName + " - lightweight metadata-caching " + index + "/" + metadata.size()
                                        + ": " + entry.name + " (deep-cache disabled for this expensive type)");
                            }
                            String snapshot = metadataOnlySnapshot(entry, typeName);
                            definitionCache.putSnapshot(entry, fingerprintText(snapshot), snapshot, BmcDefinitionCache.CURRENT_SNAPSHOT_KIND);
                            refreshed++;
                            continue;
                        }
                        if (refreshed == 0 || refreshed % 25 == 0) {
                            report(progress, store.getName() + ": " + typeName + " - deep-caching " + index + "/" + metadata.size()
                                    + ": " + entry.name + " (" + refreshReason.getReason() + ", heap " + heapSummary() + ")");
                        }
                        SnapshotLoadResult snapshot = loadSnapshotWithTimeout(store, type, entry, safeMonitor, progress);
                        if (snapshot.error != null && snapshot.error.length() > 0) {
                            definitionCache.putError(entry, snapshot.error);
                            errors++;
                            report(progress, store.getName() + ": " + typeName + " - ERROR caching " + entry.name + ": " + snapshot.error);
                        } else {
                            CacheEntry snapshotEntry = withSnapshotCustomization(entry, snapshot.customizationType);
                            definitionCache.putSnapshot(snapshotEntry, fingerprintText(snapshot.snapshot), snapshot.snapshot, BmcDefinitionCache.CURRENT_SNAPSHOT_KIND);
                            refreshed++;
                        }
                        if (index % 10 == 0) {
                            objectCache.clear();
                        }
                    } catch (Throwable ex) {
                        definitionCache.putError(entry, safeMessage(ex));
                        errors++;
                        report(progress, store.getName() + ": " + typeName + " - ERROR caching " + entry.name + ": " + safeMessage(ex));
                    }
                }
                objectCache.clear();
                int removed = definitionCache.removeMissing(store.getName(), typeName, currentNames);
                definitionCache.save();
                report(progress, store.getName() + ": " + typeName + " - definition snapshot cache complete. "
                        + refreshed + " refreshed, " + reused + " reused"
                        + (skippedErrors > 0 ? ", " + skippedErrors + " previous errors kept" : "")
                        + (removed > 0 ? ", " + removed + " removed" : "")
                        + (errors > 0 ? ", " + errors + " errors" : "") + ".");
                safeMonitor.worked(1);
            }
        }
        definitionCache.save();
        safeMonitor.done();
        report(progress, "Sync complete. Definition snapshot cache is ready; cached searches can show details without opening live server definitions.");
        return total;
    }

    /**
     * Refreshes the definition-cache snapshot for one object immediately after a migration.
     * This avoids requiring a manual Sync before cached search/details reflect the migrated object.
     */
    public boolean refreshDefinitionCacheForObject(IStore store, IModelType type, String name, IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        if (store == null || !store.isConnected() || type == null || name == null || name.length() == 0) {
            return false;
        }
        String typeName = typeName(type);
        try {
            safeMonitor.subTask("Refreshing local cache for " + store.getName() + " / " + typeName + " " + name);
            IModelItem item = findTargetItem(store, type, name);
            long modified = item == null ? Long.MIN_VALUE : BmcMetadataCache.timestampValue(item.getLastUpdateTime());
            String changedBy = item == null || item.getLastChangedBy() == null ? "" : item.getLastChangedBy();
            CacheEntry entry = new CacheEntry(store.getName(), typeName, name, modified, changedBy);
            if (shouldUseLightweightSnapshot(typeName)) {
                String snapshot = metadataOnlySnapshot(entry, typeName);
                definitionCache.putSnapshot(entry, fingerprintText(snapshot), snapshot, BmcDefinitionCache.CURRENT_SNAPSHOT_KIND);
                definitionCache.save();
                report(progress, "Refreshed lightweight cache snapshot for " + store.getName() + ": " + typeName + " " + name + ".");
                return true;
            }
            SnapshotLoadResult snapshot = loadSnapshotWithTimeout(store, type, entry, safeMonitor, progress);
            if (snapshot.error != null && snapshot.error.length() > 0) {
                definitionCache.putError(entry, snapshot.error);
                definitionCache.save();
                report(progress, "Could not refresh cache snapshot for " + store.getName() + ": " + typeName + " " + name + " - " + snapshot.error);
                return false;
            }
            CacheEntry snapshotEntry = withSnapshotCustomization(entry, snapshot.customizationType);
            definitionCache.putSnapshot(snapshotEntry, fingerprintText(snapshot.snapshot), snapshot.snapshot, BmcDefinitionCache.CURRENT_SNAPSHOT_KIND);
            definitionCache.save();
            report(progress, "Refreshed cache snapshot for " + store.getName() + ": " + typeName + " " + name + ".");
            return true;
        } catch (Throwable ex) {
            report(progress, "Could not refresh cache snapshot for " + store.getName() + ": " + typeName + " " + name + " - " + safeMessage(ex));
            return false;
        } finally {
            objectCache.clear();
        }
    }


    private boolean shouldUseLightweightSnapshot(String typeName) {
        CompareSettings settings = CompareSettings.load();
        if (isMetadataOnlyReferenceType(typeName)) {
            return true;
        }
        if (isBinaryOrSupportType(typeName) && !settings.isSyncDeepCacheBinaryObjects()) {
            return true;
        }
        if (isExpensiveReferenceType(typeName) && !settings.isSyncDeepCacheExpensiveObjects()) {
            return true;
        }
        return false;
    }

    private boolean isMetadataOnlyReferenceType(String typeName) {
        String lower = typeName == null ? "" : typeName.toLowerCase(Locale.ENGLISH).replace(" ", "");
        return lower.equals("group")
                || lower.equals("role")
                || lower.equals("grouptype")
                || lower.equals("roletype");
    }

    private boolean isBinaryOrSupportType(String typeName) {
        String lower = typeName == null ? "" : typeName.toLowerCase(Locale.ENGLISH);
        return lower.equals("image")
                || lower.equals("support file")
                || lower.indexOf("supportfile") >= 0
                || lower.indexOf("binary") >= 0;
    }

    private boolean isExpensiveReferenceType(String typeName) {
        String lower = typeName == null ? "" : typeName.toLowerCase(Locale.ENGLISH);
        return lower.equals("packing list")
                || lower.indexOf("packinglist") >= 0
                || lower.equals("report")
                || lower.equals("report type")
                || lower.indexOf("reporttype") >= 0
                || lower.equals("template")
                || lower.equals("web service")
                || lower.indexOf("webservice") >= 0
                || lower.indexOf("web service") >= 0
                || lower.equals("application")
                || lower.indexOf("applicationtype") >= 0
                || lower.equals("flashboard")
                || lower.indexOf("flashboard") >= 0
                || lower.equals("flashboard variable")
                || lower.equals("data visualization definition")
                || lower.indexOf("datavisualization") >= 0
                || lower.indexOf("data visualization") >= 0;
    }


    private boolean shouldCompareMetadataContextKey(String typeName) {
        // Key / ID is useful to display in the overview, but for container/reference objects such
        // as Packing List and Application it is often an environment-local internal ID. Including
        // it in the fingerprint made rows stay Different immediately after a successful migration.
        // Keep it as compare-significant only for object types where the ID is functional, notably
        // Group/Role permission IDs.
        String lower = typeName == null ? "" : typeName.toLowerCase(Locale.ENGLISH).replace(" ", "");
        return lower.equals("group") || lower.equals("grouptype") || lower.equals("role") || lower.equals("roletype");
    }

    private String metadataOnlySnapshot(CacheEntry entry, String typeName) {
        Map<String, String> lines = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        String area = "Main Settings";
        addProperty(lines, area, "Name", entry == null ? "" : entry.name);
        addProperty(lines, area, "Object type", typeName == null ? "" : typeName);
        addProperty(lines, area, "Cache mode", isMetadataOnlyReferenceType(typeName) ? "Metadata-only reference snapshot" : "Lightweight metadata snapshot");
        if (entry != null) {
            addProperty(lines, area, "Last update time", String.valueOf(entry.modified));
            addProperty(lines, area, "Last changed by", entry.changedBy);
            addProperty(lines, area, "Customization type", entry.customizationType);
            if (shouldCompareMetadataContextKey(typeName)) {
                addProperty(lines, area, "Key / ID", entry.contextKey);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> line : lines.entrySet()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line.getKey()).append('=').append(line.getValue());
        }
        return builder.toString();
    }

    private SnapshotLoadResult loadSnapshotWithTimeout(final IStore store, final IModelType type, final CacheEntry entry,
            final IProgressMonitor monitor, SyncProgress progress) {
        int timeout = CompareSettings.load().getCompareObjectTimeoutSeconds();
        if (timeout <= 0) {
            timeout = 180;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "Yrell Migrator snapshot " + typeName(type) + " " + (entry == null ? "" : entry.name));
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<SnapshotLoadResult> future = executor.submit(new Callable<SnapshotLoadResult>() {
            public SnapshotLoadResult call() {
                try {
                    if (monitor != null && monitor.isCanceled()) {
                        return SnapshotLoadResult.error("Cancelled.");
                    }
                    IModelObject object = getFreshObjectNoCache(store, type, entry.name);
                    if (object == null) {
                        return SnapshotLoadResult.error("Object could not be loaded.");
                    }
                    String snapshot = canonicalSnapshot(object);
                    if (snapshot == null || snapshot.trim().length() == 0) {
                        return SnapshotLoadResult.error("Object loaded, but canonical snapshot was empty.");
                    }
                    String customizationType = BmcMetadataCache.customizationType(object);
                    return SnapshotLoadResult.ok(snapshot, customizationType);
                } catch (Throwable ex) {
                    return SnapshotLoadResult.error(safeMessage(ex));
                }
            }
        });
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            Activator.logWarning("Yrell Migrator definition snapshot timed out for " + typeName(type) + " " + (entry == null ? "" : entry.name), ex);
            return SnapshotLoadResult.error("Snapshot refresh timed out after " + timeout + " second(s). Use a narrower Sync include filter or increase compare.objectTimeoutSeconds.");
        } catch (Throwable ex) {
            return SnapshotLoadResult.error(safeMessage(ex));
        } finally {
            executor.shutdownNow();
        }
    }

    private CacheEntry withSnapshotCustomization(CacheEntry entry, String customizationType) {
        if (entry == null || customizationType == null || customizationType.length() == 0
                || customizationType.equalsIgnoreCase(entry.customizationType)) {
            return entry;
        }
        return new CacheEntry(entry.server, entry.type, entry.name, entry.modified, entry.changedBy, customizationType);
    }

    private static final class SnapshotLoadResult {
        final String snapshot;
        final String error;
        final String customizationType;

        private SnapshotLoadResult(String snapshot, String error, String customizationType) {
            this.snapshot = snapshot == null ? "" : snapshot;
            this.error = error == null ? "" : error;
            this.customizationType = customizationType == null ? "" : customizationType;
        }

        static SnapshotLoadResult ok(String snapshot, String customizationType) {
            return new SnapshotLoadResult(snapshot, "", customizationType);
        }

        static SnapshotLoadResult error(String error) {
            return new SnapshotLoadResult("", error, "");
        }
    }

    /** Returns cached compare rows without opening server definitions. */
    public List<CompareResult> cachedCompareSearch(IStore sourceStore, IStore targetStore, List<IModelType> types, String query,
            boolean includeSourceOnly, boolean includeTargetOnly, int maxResults, IProgressMonitor monitor, SyncProgress progress) {
        return cachedCompareSearch(sourceStore, targetStore, types, query, includeSourceOnly, includeTargetOnly,
                true, maxResults, monitor, progress);
    }

    /** Returns cached compare rows without opening server definitions. */
    public List<CompareResult> cachedCompareSearch(IStore sourceStore, IStore targetStore, List<IModelType> types, String query,
            boolean includeSourceOnly, boolean includeTargetOnly, boolean includeEqualRows, int maxResults, IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        List<CompareResult> results = new ArrayList<CompareResult>();
        if (sourceStore == null || targetStore == null || types == null || types.isEmpty()) {
            return results;
        }
        Map<String, SearchKey> keys = new TreeMap<String, SearchKey>(String.CASE_INSENSITIVE_ORDER);
        addDefinitionSearchKeys(keys, sourceStore, types, query, true, true);
        if (progress != null) {
            BmcDefinitionCache.CacheLoadStats loadStats = definitionCache.lastLoadStats();
            if (loadStats != null && loadStats.loadMillis > 0) {
                report(progress, "Definition cache startup: " + loadStats.mode + " loaded " + loadStats.entries + " row(s) in " + loadStats.loadMillis + " ms.");
            }
        }
        if (includeTargetOnly) {
            addDefinitionSearchKeys(keys, targetStore, types, query, true, false);
        }
        report(progress, "Cached search matched " + keys.size() + " unique object key(s) for query '" + safeQuery(query) + "'. No server definitions were opened.");
        int limit = maxResults <= 0 ? CompareSettings.load().getSearchMaxResults() : maxResults;
        if (keys.size() > limit) {
            report(progress, "Cached search matched " + keys.size() + " object key(s). Up to " + limit
                    + " visible row(s) will be returned. Equal rows can be skipped without reading snapshot files.");
        }
        int scanned = 0;
        int skippedEqual = 0;
        int skippedSourceOnly = 0;
        int skippedTargetOnly = 0;
        for (SearchKey key : keys.values()) {
            if (safeMonitor.isCanceled() || results.size() >= limit) {
                break;
            }
            scanned++;
            if (!includeEqualRows && isEqualCachedPair(key)) {
                skippedEqual++;
                continue;
            }
            CompareResult result = createCachedResult(sourceStore, targetStore, key);
            if (!includeSourceOnly && result.getStatus() == CompareStatus.MISSING_IN_TARGET) {
                skippedSourceOnly++;
                continue;
            }
            if (!includeTargetOnly && result.getStatus() == CompareStatus.MISSING_IN_SOURCE) {
                skippedTargetOnly++;
                continue;
            }
            if (!includeEqualRows && result.getStatus() == CompareStatus.EQUAL) {
                skippedEqual++;
                continue;
            }
            results.add(result);
        }
        report(progress, "Cached search finished. Scanned " + scanned + " key(s), returned " + results.size()
                + " row(s)" + (skippedEqual > 0 ? ", skipped " + skippedEqual + " equal" : "")
                + (skippedSourceOnly > 0 ? ", skipped " + skippedSourceOnly + " source-only" : "")
                + (skippedTargetOnly > 0 ? ", skipped " + skippedTargetOnly + " target-only" : "")
                + ". Details are available from the Sync cache. Use Refresh Selected from Server only if you suspect the local cache is stale.");
        return results;
    }

    private boolean isEqualCachedPair(SearchKey key) {
        if (key == null || key.sourceDefinition == null || key.targetDefinition == null) {
            return false;
        }
        DefinitionEntry source = key.sourceDefinition;
        DefinitionEntry target = key.targetDefinition;
        if (source.error.length() > 0 || target.error.length() > 0) {
            return false;
        }
        return source.fingerprint.length() > 0 && source.fingerprint.equals(target.fingerprint);
    }

    private void addDefinitionSearchKeys(Map<String, SearchKey> keys, IStore store, List<IModelType> types, String query, boolean include, boolean sourceSide) {
        if (!include || store == null || types == null) {
            return;
        }
        List<DefinitionEntry> cached = definitionCache.find(store, types, query);
        for (DefinitionEntry entry : cached) {
            IModelType type = findTypeByName(types, entry.type);
            if (type != null) {
                SearchKey key = keys.get(new SearchKey(type, entry.name).toKey());
                if (key == null) {
                    key = new SearchKey(type, entry.name);
                    keys.put(key.toKey(), key);
                }
                if (sourceSide) {
                    key.sourceDefinition = entry;
                } else {
                    key.targetDefinition = entry;
                }
            }
        }
    }

    private CompareResult createCachedResult(IStore sourceStore, IStore targetStore, SearchKey key) {
        CompareResult result = new CompareResult();
        result.setEvidence(cacheEvidence(key.sourceDefinition, key.targetDefinition));
        result.setEvidenceDetail(cacheEvidenceDetail(key.sourceDefinition, key.targetDefinition));
        result.setSourceStore(sourceStore);
        result.setTargetStore(targetStore);
        result.setModelType(key.type);
        result.setObjectName(key.name);
        result.setObjectType(typeName(key.type));
        result.setSourceServer(sourceStore == null ? "" : sourceStore.getName());
        result.setTargetServer(targetStore == null ? "" : targetStore.getName());
        DefinitionEntry source = key.sourceDefinition;
        DefinitionEntry target = key.targetDefinition;
        if (source != null) {
            result.setSourceModified(toTimestamp(source.modified));
            result.setSourceChangedBy(source.changedBy);
            result.setSourceCustomizationType(source.customizationType);
            result.setSourceContextKey(source.contextKey);
        }
        if (target != null) {
            result.setTargetModified(toTimestamp(target.modified));
            result.setTargetChangedBy(target.changedBy);
            result.setTargetCustomizationType(target.customizationType);
            result.setTargetContextKey(target.contextKey);
        }
        fillCachedDisplayMetadata(result, source, target);
        if (source == null && target == null) {
            result.setEvidence(CompareEvidence.UNKNOWN);
            result.setEvidenceDetail("No local definition cache row was found for either side.");
            result.setStatus(CompareStatus.ERROR);
            result.setDetail("No definition cache row found for this object. Run Sync.");
            return result;
        }
        if (source == null) {
            result.setStatus(CompareStatus.MISSING_IN_SOURCE);
            result.setDetail("Cached compare: object exists in target cache but not in source cache.");
            return result;
        }
        if (target == null) {
            result.setStatus(CompareStatus.MISSING_IN_TARGET);
            result.setDetail("Cached compare: object exists in source cache but not in target cache.");
            return result;
        }
        if (source.error.length() > 0 || target.error.length() > 0) {
            result.setEvidence(CompareEvidence.CACHE_ERROR);
            result.setEvidenceDetail(cacheEvidenceDetail(source, target));
            result.setStatus(CompareStatus.ERROR);
            result.setDifferenceCount(-1);
            result.setDetail("Cached compare error. Source=" + source.error + "; Target=" + target.error);
            return result;
        }
        if (source.fingerprint.length() == 0 || target.fingerprint.length() == 0) {
            result.setEvidence(CompareEvidence.METADATA_ONLY);
            result.setEvidenceDetail(cacheEvidenceDetail(source, target));
            result.setStatus(CompareStatus.UNKNOWN);
            result.setDifferenceCount(-1);
            result.setDetail("Definition fingerprint is missing. Run Sync before searching.");
            return result;
        }
        if (source.fingerprint.equals(target.fingerprint)) {
            // Fast path: equal rows do not need property details, so avoid reading two gzip snapshot
            // files for every unchanged object during broad searches.
            result.setStatus(CompareStatus.EQUAL);
            result.setDifferenceCount(0);
            result.setDetail("Cached compare: definition snapshots are equal. No server definitions were opened.");
        } else {
            String sourceSnapshot = definitionCache.getSnapshot(source);
            String targetSnapshot = definitionCache.getSnapshot(target);
            if ((sourceSnapshot == null || sourceSnapshot.trim().length() == 0)
                    || (targetSnapshot == null || targetSnapshot.trim().length() == 0)) {
                result.setEvidence(CompareEvidence.METADATA_ONLY);
                result.setEvidenceDetail(cacheEvidenceDetail(source, target));
                result.setStatus(CompareStatus.UNKNOWN);
                result.setDifferenceCount(-1);
                result.setDetail("Definition cache row has fingerprint but no property snapshot. Run Sync again; snapshots are stored as memory-optimized gzip files.");
                return result;
            }
            DifferenceReport cachedReport = diffCachedSnapshots(sourceSnapshot, targetSnapshot);
            result.setDifferenceDetails(cachedReport.getDetails());
            result.setDifferenceCount(cachedReport.getCount() == 0 ? 1 : cachedReport.getCount());
            result.setStatus(CompareStatus.CHANGED);
            if (cachedReport.getCount() == 0) {
                result.setStatus(CompareStatus.EQUAL);
                result.setDifferenceCount(0);
                result.setDetail("Cached compare: only ignored/internal snapshot differences were found. Run Sync or adjust ignore rules if you need to rebuild this row.");
            } else {
                result.setDetail("Cached compare from Sync snapshot: " + cachedReport.toSummary(settings.getMaxSummaryItems()));
            }
        }
        return result;
    }

    private DifferenceReport diffCachedSnapshots(String sourceSnapshot, String targetSnapshot) {
        DifferenceReport report = new DifferenceReport();
        Map<String, String> source = parseSnapshot(sourceSnapshot);
        Map<String, String> target = parseSnapshot(targetSnapshot);
        removeNoisySnapshotKeys(source);
        removeNoisySnapshotKeys(target);
        Set<String> keys = new LinkedHashSet<String>();
        keys.addAll(source.keySet());
        keys.addAll(target.keySet());

        // Component-level reporting makes the details view much closer to Migrator.
        // If an entire Field/View/Action exists only on one side, show one clear row for that
        // component instead of flooding the user with every nested property underneath it.
        Set<String> sourceOnlyComponents = missingComponentPrefixes(source, target);
        Set<String> targetOnlyComponents = missingComponentPrefixes(target, source);

        for (String prefix : sourceOnlyComponents) {
            report.add(new DiffDetail(prettySnapshotArea(prefix), "Exists", "present", "", "missing in target"));
        }
        for (String prefix : targetOnlyComponents) {
            report.add(new DiffDetail(prettySnapshotArea(prefix), "Exists", "", "present", "missing in source"));
        }

        List<String> changedKeys = new ArrayList<String>(keys);
        Collections.sort(changedKeys, new Comparator<String>() {
            public int compare(String left, String right) {
                int area = areaSortWeight(left) - areaSortWeight(right);
                if (area != 0) {
                    return area;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(left, right);
            }
        });

        for (String key : changedKeys) {
            if (isUnderAny(key, sourceOnlyComponents) || isUnderAny(key, targetOnlyComponents)) {
                continue;
            }
            String left = source.get(key);
            String right = target.get(key);
            if (left == null && right != null) {
                report.add(new DiffDetail(snapshotArea(key), snapshotProperty(key), "", displayValue(right), "missing in source"));
            } else if (left != null && right == null) {
                report.add(new DiffDetail(snapshotArea(key), snapshotProperty(key), displayValue(left), "", "missing in target"));
            } else if (left != null && right != null && !left.equals(right)) {
                report.add(new DiffDetail(snapshotArea(key), snapshotProperty(key), displayValue(left), displayValue(right), classifySnapshotDifference(key)));
            }
        }
        return report;
    }

    private void removeNoisySnapshotKeys(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        List<String> remove = new ArrayList<String>();
        for (String key : snapshot.keySet()) {
            if (isNoisySnapshotKey(key)) {
                remove.add(key);
            }
        }
        for (String key : remove) {
            snapshot.remove(key);
        }
    }

    private boolean isNoisySnapshotKey(String key) {
        if (key == null) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ENGLISH);
        if (settings.shouldIgnoreDifferenceName(key) || settings.shouldIgnoreFingerprintMember(key)) {
            return true;
        }
        String displayedKey = snapshotArea(key) + "/" + snapshotProperty(key);
        if (settings.shouldIgnoreDifferenceName(displayedKey) || settings.shouldIgnoreFingerprintMember(displayedKey)) {
            return true;
        }
        if (settings.shouldIgnoreDifferenceName(snapshotProperty(key)) || settings.shouldIgnoreFingerprintMember(snapshotProperty(key))) {
            return true;
        }
        if (lower.indexOf("supportedmaskoptions") >= 0 || lower.endsWith("/objecttype") || lower.endsWith(".objecttype")
                || lower.endsWith("/cache mode") || lower.endsWith("/last update time") || lower.endsWith("/last changed by")
                || lower.endsWith("/request id") || lower.endsWith("/requestid") || lower.endsWith("/entry id") || lower.endsWith("/entryid")) {
            return true;
        }
        if (lower.startsWith("object/")) {
            return lower.equals("object/fields")
                    || lower.equals("object/views")
                    || lower.equals("object/indexinfo")
                    || lower.equals("object/tablefieldlist")
                    || lower.equals("object/activelinks")
                    || lower.equals("object/filters")
                    || lower.equals("object/escalations");
        }
        return false;
    }

    private Set<String> missingComponentPrefixes(Map<String, String> presentSide, Map<String, String> otherSide) {
        Set<String> prefixes = new LinkedHashSet<String>();
        for (String key : presentSide.keySet()) {
            if (!key.endsWith("/exists")) {
                continue;
            }
            String prefix = key.substring(0, key.length() - "/exists".length());
            if (!otherSide.containsKey(key) && isComponentArea(prefix)) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    private boolean isUnderAny(String key, Set<String> prefixes) {
        if (key == null || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (key.equals(prefix + "/exists") || key.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isComponentArea(String area) {
        if (area == null) {
            return false;
        }
        return area.startsWith("Fields / ")
                || area.startsWith("Views / ")
                || area.startsWith("If Actions / ")
                || area.startsWith("Else Actions / ")
                || area.startsWith("Menu Items / ")
                || area.startsWith("Indexes / ")
                || area.startsWith("Permissions / ")
                || area.startsWith("Guide Membership / ");
    }

    private String prettySnapshotArea(String prefix) {
        if (prefix == null || prefix.length() == 0) {
            return "Object";
        }
        return prefix.replace("Fields / ", "Field ")
                .replace("Views / ", "View ")
                .replace("If Actions / ", "If Action ")
                .replace("Else Actions / ", "Else Action ")
                .replace("Menu Items / ", "Menu Item ")
                .replace("Indexes / ", "Index ")
                .replace("Permissions / ", "Permission ")
                .replace("Guide Membership / ", "Guide Membership ");
    }

    private String classifySnapshotDifference(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ENGLISH);
        if (lower.indexOf("audit") >= 0) {
            return "audit";
        }
        if (lower.indexOf("qualification") >= 0 || lower.indexOf("run if") >= 0) {
            return "qualification";
        }
        if (lower.indexOf("action") >= 0 || lower.indexOf("mapping") >= 0 || lower.indexOf("value") >= 0) {
            return "action";
        }
        if (lower.indexOf("permission") >= 0) {
            return "permission";
        }
        if (lower.indexOf("field") >= 0 || lower.indexOf("label") >= 0 || lower.indexOf("datatype") >= 0 || lower.indexOf("fieldtype") >= 0) {
            return "field";
        }
        if (lower.indexOf("view") >= 0 || lower.indexOf("display") >= 0) {
            return "view";
        }
        return "property";
    }

    private int areaSortWeight(String key) {
        String area = snapshotArea(key);
        String lower = area.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("main settings")) return 10;
        if (lower.startsWith("associated forms")) return 20;
        if (lower.startsWith("execution")) return 30;
        if (lower.startsWith("qualification")) return 40;
        if (lower.startsWith("fields") || lower.startsWith("field ")) return 50;
        if (lower.startsWith("views") || lower.startsWith("view ")) return 60;
        if (lower.startsWith("if action")) return 70;
        if (lower.startsWith("else action")) return 80;
        if (lower.startsWith("menus") || lower.startsWith("menu item")) return 90;
        if (lower.startsWith("permissions") || lower.startsWith("permission")) return 100;
        return 500;
    }

    private Map<String, String> parseSnapshot(String snapshot) {
        Map<String, String> result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (snapshot == null || snapshot.length() == 0) {
            return result;
        }
        String[] lines = snapshot.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String rawKey = line.substring(0, equals);
            if (isRawAuditInfoSnapshotKey(rawKey)) {
                continue;
            }
            String key = normalizeSnapshotKey(rawKey);
            String value = normalizeSnapshotValue(key, line.substring(equals + 1));
            result.put(key, value);
        }
        return result;
    }


    private boolean isRawAuditInfoSnapshotKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ENGLISH).trim();
        return lower.endsWith("/auditinfo") || lower.endsWith("/audit info");
    }

    private String normalizeSnapshotKey(String key) {
        if (key == null || !key.startsWith("Fields / ")) {
            return key;
        }
        int start = "Fields / ".length();
        int delimiter = key.indexOf(" / ", start);
        int slash = key.indexOf('/', start);
        int end = delimiter >= 0 ? delimiter : slash;
        if (end <= start) {
            return key;
        }
        String component = key.substring(start, end).trim();
        String normalized = normalizeFieldComponentLabel(component);
        if (normalized.equals(component)) {
            return key;
        }
        return key.substring(0, start) + normalized + key.substring(end);
    }

    private String normalizeFieldComponentLabel(String component) {
        if (component == null) {
            return "";
        }
        String text = component.trim();
        if (text.length() == 0) {
            return text;
        }
        int pos = 0;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        if (pos == 0) {
            return text;
        }
        try {
            int id = Integer.parseInt(text.substring(0, pos));
            String suffix = pos < text.length() ? text.substring(pos).trim() : "";
            String canonical = canonicalSystemFieldName(id, suffix);
            return String.valueOf(id) + (canonical.length() == 0 ? "" : " " + canonical);
        } catch (RuntimeException ignored) {
            return text;
        }
    }

    private String normalizeSnapshotValue(String key, String value) {
        if (key == null || value == null || !key.startsWith("Fields / ")) {
            return value;
        }
        String lower = key.toLowerCase(Locale.ENGLISH);
        if (!(lower.endsWith("/name") || lower.endsWith("/databasename") || lower.endsWith("/fieldname"))) {
            return value;
        }
        int id = fieldIdFromSnapshotKey(key);
        return id >= 0 ? canonicalSystemFieldName(id, value) : value;
    }

    private int fieldIdFromSnapshotKey(String key) {
        if (key == null || !key.startsWith("Fields / ")) {
            return -1;
        }
        int start = "Fields / ".length();
        int pos = start;
        while (pos < key.length() && Character.isDigit(key.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(start, pos));
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private String snapshotArea(String key) {
        if (key == null || key.length() == 0) {
            return "Object";
        }
        int slash = key.lastIndexOf('/');
        String area = slash >= 0 ? key.substring(0, slash).trim() : key.trim();
        if (isComponentArea(area)) {
            return prettySnapshotArea(area);
        }
        int nested = area.indexOf(" / ");
        if (nested > 0) {
            return area.substring(0, nested).trim();
        }
        return area.length() == 0 ? "Object" : area;
    }

    private String snapshotProperty(String key) {
        if (key == null || key.length() == 0) {
            return "definition";
        }
        int slash = key.lastIndexOf('/');
        return slash >= 0 && slash + 1 < key.length() ? prettifySnapshotProperty(key.substring(slash + 1)) : prettifySnapshotProperty(key);
    }

    private String prettifySnapshotProperty(String property) {
        if (property == null) {
            return "";
        }
        String p = property;
        if (p.equals("exists")) return "Exists";
        if (p.equals("name")) return "Name";
        if (p.equals("label")) return "Label";
        if (p.equals("fieldId")) return "Field ID";
        if (p.equals("fieldType")) return "Field type";
        if (p.equals("dataType")) return "Data type";
        if (p.equals("executeOn")) return "Execute on";
        if (p.equals("executionOrder")) return "Execution order";
        if (p.equals("runIfQualification")) return "Run If qualification";
        if (p.equals("elseIfQualification")) return "Else If qualification";
        if (p.equals("qualification")) return "Qualification";
        if (p.equals("executeMask")) return "Execute on";
        if (p.equals("ifActions")) return "If actions";
        if (p.equals("elseActions")) return "Else actions";
        if (p.equals("customizationType")) return "Customization type";
        if (p.equals("overlayType")) return "Overlay type";
        if (p.equals("formName")) return "Form name";
        if (p.equals("fieldName")) return "Field name";
        if (p.equals("menuName")) return "Menu name";
        if (p.equals("defaultValue")) return "Default value";
        if (p.equals("displayProperties")) return "Display properties";
        if (p.equals("selectionValues")) return "Selection values";
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    private String canonicalSnapshot(IModelObject object) {
        Map<String, String> lines = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (object == null) {
            return "";
        }

        String modelType = stableDisplay(firstGetterValue(object, new String[] { "getItemType", "getType", "getModelType" }));
        String className = object.getClass().getName();
        boolean handled = false;

        if (object instanceof IFormObject || containsAny(modelType, className, new String[] { "form" })) {
            collectFormSnapshot((IFormObject) (object instanceof IFormObject ? object : null), object, lines);
            handled = true;
        } else if (containsAny(modelType, className, new String[] { "active link", "activelink" })) {
            collectWorkflowSnapshot("Active Link", object, lines);
            handled = true;
        } else if (containsAny(modelType, className, new String[] { "filter" })) {
            collectWorkflowSnapshot("Filter", object, lines);
            handled = true;
        } else if (containsAny(modelType, className, new String[] { "escalation" })) {
            collectWorkflowSnapshot("Escalation", object, lines);
            handled = true;
        } else if (containsAny(modelType, className, new String[] { "menu" })) {
            collectMenuSnapshot(object, lines);
            handled = true;
        } else if (containsAny(modelType, className, new String[] { "association" })) {
            collectWorkflowSnapshot("Association", object, lines);
            handled = true;
        } else if (containsAny(modelType, className, new String[] { "application", "packinglist", "packing list" })) {
            collectContainerSnapshot(object, lines);
            handled = true;
        }

        if (!handled || lines.isEmpty()) {
            // Last resort for object types that do not yet have a semantic renderer.
            collectSnapshot("Object", object, lines, 0, new IdentityHashMap<Object, Boolean>());
        }

        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : lines.entrySet()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }


    private void fillCachedDisplayMetadata(CompareResult result, DefinitionEntry source, DefinitionEntry target) {
        if (result == null) {
            return;
        }
        boolean formType = isFormTypeName(result.getObjectType());
        boolean workflowType = isWorkflowTypeName(result.getObjectType());
        if (source != null) {
            String sourceSnapshot = definitionCache.getSnapshot(source);
            if (formType) {
                result.setSourceFormType(extractFormTypeFromSnapshot(sourceSnapshot));
                result.setSourcePrimaryForm(result.getObjectName());
            } else {
                result.setSourcePrimaryForm(extractPrimaryFormFromSnapshot(sourceSnapshot));
            }
            if (workflowType) {
                result.setSourceWorkflowState(extractWorkflowStateFromSnapshot(sourceSnapshot));
            }
        }
        if (target != null) {
            String targetSnapshot = definitionCache.getSnapshot(target);
            if (formType) {
                result.setTargetFormType(extractFormTypeFromSnapshot(targetSnapshot));
                result.setTargetPrimaryForm(result.getObjectName());
            } else {
                result.setTargetPrimaryForm(extractPrimaryFormFromSnapshot(targetSnapshot));
            }
            if (workflowType) {
                result.setTargetWorkflowState(extractWorkflowStateFromSnapshot(targetSnapshot));
            }
        }
    }

    private void fillLiveDisplayMetadata(CompareResult result, IModelObject sourceObject, IModelObject targetObject) {
        if (result == null) {
            return;
        }
        boolean formType = isFormTypeName(result.getObjectType());
        boolean workflowType = isWorkflowTypeName(result.getObjectType());
        if (sourceObject != null) {
            if (formType) {
                result.setSourceFormType(extractFormTypeFromObject(sourceObject));
                result.setSourcePrimaryForm(result.getObjectName());
            } else {
                result.setSourcePrimaryForm(extractPrimaryFormFromObject(sourceObject));
            }
            if (workflowType) {
                result.setSourceWorkflowState(extractWorkflowStateFromObject(sourceObject));
            }
        }
        if (targetObject != null) {
            if (formType) {
                result.setTargetFormType(extractFormTypeFromObject(targetObject));
                result.setTargetPrimaryForm(result.getObjectName());
            } else {
                result.setTargetPrimaryForm(extractPrimaryFormFromObject(targetObject));
            }
            if (workflowType) {
                result.setTargetWorkflowState(extractWorkflowStateFromObject(targetObject));
            }
        }
    }

    private String extractFormTypeFromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.length() == 0) {
            return "";
        }
        Map<String, String> map = parseSnapshot(snapshot);
        String value = firstSnapshotValue(map, new String[] {
                "Main Settings/Form Type", "Main Settings/formType", "Main Settings/schemaType", "Main Settings/type", "Main Settings/Type" });
        return normalizeFormType(value);
    }

    private String extractPrimaryFormFromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.length() == 0) {
            return "";
        }
        Map<String, String> map = parseSnapshot(snapshot);
        String value = firstSnapshotValue(map, new String[] {
                "Associated Forms/Primary Form", "Associated Forms/Primary form", "Associated Forms/Form",
                "Associated Forms/Forms", "Main Settings/Primary Form", "Main Settings/primaryForm",
                "Main Settings/primaryFormName", "Main Settings/formName", "Main Settings/Form name",
                "Main Settings/form" });
        return compactFormList(value);
    }

    private String extractWorkflowStateFromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.length() == 0) {
            return "";
        }
        Map<String, String> map = parseSnapshot(snapshot);
        String value = firstSnapshotValueExactOrSuffix(map, new String[] {
                "Main Settings/Enabled", "Main Settings/enabled", "Main Settings/Enable", "Main Settings/enable",
                "Main Settings/Disabled", "Main Settings/disabled" },
                new String[] { "/enabled", "/enable", "/disabled" });
        boolean inverted = snapshotKeyLooksDisabled(map, value);
        return normalizeWorkflowState(value, inverted);
    }

    private String extractFormTypeFromObject(Object object) {
        if (object == null) {
            return "";
        }
        String type = "";
        String[] getters = new String[] { "getSchemaType", "getFormType", "getType" };
        for (int i = 0; i < getters.length && type.length() == 0; i++) {
            try {
                Method method = object.getClass().getMethod(getters[i], new Class[0]);
                Object value = method.invoke(object, new Object[0]);
                type = normalizeFormType(stableDisplay(value));
            } catch (Throwable ignored) {
            }
        }
        return type;
    }

    private String extractPrimaryFormFromObject(Object object) {
        if (object == null) {
            return "";
        }
        Object value = firstGetterValue(object, new String[] {
                "getPrimaryForm", "getPrimaryFormName", "getPrimaryFormNames",
                "getAssociatedForms", "getAssociatedFormNames", "getAssociatedSchemaNames",
                "getFormNames", "getForms", "getFormList", "getSchemaName", "getSchemaNames",
                "getForm", "getFormName", "getApplicationForms", "getDataForms" });
        return compactFormList(stableDisplay(value));
    }

    private String extractWorkflowStateFromObject(Object object) {
        if (object == null) {
            return "";
        }
        String enabled = firstGetterDisplayValue(object, new String[] {
                "isEnabled", "getEnabled", "isEnable", "getEnable", "getStatus", "getEnableStatus" });
        if (enabled.length() > 0) {
            return normalizeWorkflowState(enabled, false);
        }
        String disabled = firstGetterDisplayValue(object, new String[] { "isDisabled", "getDisabled", "getDisable" });
        return normalizeWorkflowState(disabled, true);
    }

    private String firstGetterDisplayValue(Object object, String[] getters) {
        if (object == null || getters == null) {
            return "";
        }
        for (int i = 0; i < getters.length; i++) {
            Object value = invokeNoArg(object, getters[i]);
            String text = stableDisplay(value);
            if (text.length() > 0 && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return "";
    }

    private String compactFormList(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() == 0 || "null".equalsIgnoreCase(text)) {
            return "";
        }
        text = text.replace('\r', ' ').replace('\n', ' ');
        text = text.replace('[', ' ').replace(']', ' ').replace('{', ' ').replace('}', ' ');
        while (text.indexOf("  ") >= 0) {
            text = text.replace("  ", " ");
        }
        text = text.trim();
        int eq = text.indexOf('=');
        if (eq > 0 && eq + 1 < text.length() && text.indexOf(',') < 0) {
            text = text.substring(eq + 1).trim();
        }
        return text;
    }

    private String firstSnapshotValue(Map<String, String> map, String[] keys) {
        if (map == null || map.isEmpty() || keys == null) {
            return "";
        }
        for (int i = 0; i < keys.length; i++) {
            String value = map.get(keys[i]);
            if (value != null && value.trim().length() > 0) {
                return value.trim();
            }
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ENGLISH);
            if (key.endsWith("/form type") || key.endsWith("/formtype") || key.endsWith("/schematype")) {
                return entry.getValue() == null ? "" : entry.getValue().trim();
            }
        }
        return "";
    }

    private String firstSnapshotValueExactOrSuffix(Map<String, String> map, String[] exactKeys, String[] suffixes) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        if (exactKeys != null) {
            for (int i = 0; i < exactKeys.length; i++) {
                String value = map.get(exactKeys[i]);
                if (value != null && value.trim().length() > 0) {
                    return value.trim();
                }
            }
        }
        if (suffixes != null) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ENGLISH);
                for (int i = 0; i < suffixes.length; i++) {
                    if (key.endsWith(suffixes[i])) {
                        String value = entry.getValue() == null ? "" : entry.getValue().trim();
                        if (value.length() > 0) {
                            return value;
                        }
                    }
                }
            }
        }
        return "";
    }

    private boolean snapshotKeyLooksDisabled(Map<String, String> map, String value) {
        if (map == null || value == null || value.length() == 0) {
            return false;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String entryValue = entry.getValue() == null ? "" : entry.getValue().trim();
            if (entryValue.equals(value)) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ENGLISH);
                return key.endsWith("/disabled");
            }
        }
        return false;
    }

    private String normalizeWorkflowState(String value, boolean invertedBoolean) {
        String text = value == null ? "" : value.trim();
        if (text.length() == 0 || "null".equalsIgnoreCase(text)) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ENGLISH);
        boolean disabled = lower.indexOf("disable") >= 0 || lower.equals("false") || lower.equals("0") || lower.equals("2");
        boolean enabled = lower.indexOf("enable") >= 0 || lower.equals("true") || lower.equals("1");
        if (invertedBoolean && (lower.equals("true") || lower.equals("1"))) {
            return "Disabled";
        }
        if (invertedBoolean && (lower.equals("false") || lower.equals("0"))) {
            return "Enabled";
        }
        if (disabled) {
            return "Disabled";
        }
        if (enabled) {
            return "Enabled";
        }
        return text;
    }

    private boolean isFormTypeName(String objectType) {
        String type = objectType == null ? "" : objectType.trim().toLowerCase(Locale.ENGLISH);
        return type.equals("form") || type.equals("formtype");
    }

    private boolean isWorkflowTypeName(String objectType) {
        String type = objectType == null ? "" : objectType.trim().toLowerCase(Locale.ENGLISH).replace(" ", "");
        return type.equals("activelink") || type.equals("filter") || type.equals("escalation")
                || type.equals("activelinkguide") || type.equals("filterguide");
    }

    private void addFormTypeSnapshot(Object object, Map<String, String> lines) {
        String type = "";
        String[] getters = new String[] { "getSchemaType", "getFormType", "getType" };
        for (int i = 0; i < getters.length && type.length() == 0; i++) {
            try {
                Method method = object.getClass().getMethod(getters[i], new Class[0]);
                Object value = method.invoke(object, new Object[0]);
                type = normalizeFormType(stableDisplay(value));
            } catch (Throwable ignored) {
            }
        }
        if (type.length() > 0) {
            addProperty(lines, "Main Settings", "Form Type", type);
        }
    }

    private void addAuditSnapshot(Object object, Map<String, String> lines) {
        Object auditInfo = firstGetterValue(object, new String[] { "getAuditInfo", "getAudit" });
        if (auditInfo == null) {
            return;
        }
        String display = stableDisplay(auditInfo);
        if (display.length() == 0 || "null".equalsIgnoreCase(display)) {
            return;
        }
        String formName = auditInfoValue(auditInfo, new String[] {
                "getAuditFormName", "getAuditSchemaName", "getAuditForm", "getFormName", "getSchemaName", "getName", "getForm" });
        String enabled = auditInfoValue(auditInfo, new String[] {
                "isEnabled", "getEnabled", "isAuditEnabled", "getAuditEnabled", "isEnable", "getEnable" });
        String style = auditInfoValue(auditInfo, new String[] {
                "getAuditStyle", "getStyle", "getType", "getAuditType", "getMode" });
        addProperty(lines, "Audit", "Enabled", enabled.length() == 0 ? "true" : enabled);
        if (formName.length() > 0) {
            addProperty(lines, "Audit", "Audit Form", formName);
        }
        if (style.length() > 0 && style.toLowerCase(Locale.ENGLISH).indexOf("auditinfo") < 0) {
            addProperty(lines, "Audit", "Style", style);
        }
    }

    private String auditInfoValue(Object auditInfo, String[] getters) {
        if (auditInfo == null || getters == null) {
            return "";
        }
        for (int i = 0; i < getters.length; i++) {
            Object value = invokeNoArg(auditInfo, getters[i]);
            String text = stableDisplay(value);
            if (text.length() > 0 && text.indexOf('@') < 0 && text.toLowerCase(Locale.ENGLISH).indexOf("auditinfo") < 0) {
                return text;
            }
        }
        return "";
    }

    private String normalizeFormType(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() == 0) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ENGLISH);
        if (lower.indexOf("display") >= 0 || lower.indexOf("dialog") >= 0) {
            return "Display only";
        }
        if (lower.indexOf("regular") >= 0) {
            return "Regular";
        }
        if (lower.indexOf("join") >= 0) {
            return "Join";
        }
        if (lower.indexOf("vendor") >= 0) {
            return "Vendor";
        }
        if (lower.indexOf("view") >= 0) {
            return "View";
        }
        if (text.matches("[-+]?\\d+")) {
            try {
                int numeric = Integer.parseInt(text);
                if (numeric == 1) return "Regular";
                if (numeric == 2) return "Join";
                if (numeric == 3) return "View";
                if (numeric == 4) return "Display only";
                if (numeric == 5) return "Vendor";
            } catch (RuntimeException ignored) {
            }
        }
        return text;
    }

    private void collectFormSnapshot(IFormObject form, IModelObject object, Map<String, String> lines) {
        addMainSettings(object, lines, new String[] {
                "name", "customizationType", "overlayType", "type", "formType", "schemaType", "owner", "helpText", "description", "archiveInfo" });
        addFormTypeSnapshot(object, lines);
        addAuditSnapshot(object, lines);
        Object fields = form == null ? invokeNoArg(object, "getFields") : invokeNoArg(form, "getFields");
        Object views = form == null ? invokeNoArg(object, "getViews") : invokeNoArg(form, "getViews");
        addComponentSnapshots("Fields", safeCollection(fields), lines, new String[] {
                "fieldID", "fieldId", "id", "name", "databaseName", "label", "fieldType", "dataType", "length", "option", "defaultValue",
                "permissions", "helpText", "displayProperties", "limit", "characterLimit", "selectionValues", "menuName", "index", "visible", "required", "defaultPermission" });
        addComponentSnapshots("Views", safeCollection(views), lines, new String[] {
                "name", "label", "locale", "viewType", "permissions", "properties", "displayProperties", "fieldProperties", "width", "height" });
        addSemanticCollection("Indexes", object, lines, new String[] { "getIndexes", "getIndexList" }, new String[] { "name", "fieldIds", "unique", "definition" });
        addSemanticCollection("Permissions", object, lines, new String[] { "getPermissions", "getPermissionList" }, new String[] { "group", "groupID", "groupId", "permission", "permissionValue" });
    }

    private void collectWorkflowSnapshot(String workflowKind, IModelObject object, Map<String, String> lines) {
        addMainSettings(object, lines, new String[] {
                "name", "customizationType", "overlayType", "enabled", "enable", "order", "executionOrder", "workflowConnect", "owner", "helpText", "description" });
        addFirstValue(lines, "Associated Forms", "Forms", object, new String[] {
                "getPrimaryForm", "getPrimaryFormName", "getPrimaryFormNames",
                "getAssociatedForms", "getAssociatedFormNames", "getAssociatedSchemaNames",
                "getFormNames", "getForms", "getFormList", "getSchemaName", "getSchemaNames",
                "getForm", "getFormName" });
        addFirstValue(lines, "Execution", "Execute On", object, new String[] {
                "getExecuteOn", "getExecuteMask", "getExecutionOptions", "getExecutionMask" });
        addFirstValue(lines, "Qualification", "Run If", object, new String[] {
                "getRunIfQualification", "getQualification", "getQuery", "getRunIf" });
        addFirstValue(lines, "Qualification", "Else If", object, new String[] {
                "getElseIfQualification", "getElseQualification" });

        addActionSnapshots("If Actions", object, lines, new String[] {
                "getIfActions", "getIfActionList", "getIfAction", "getIfList", "getThenActions", "getThenActionList" });
        addActionSnapshots("Else Actions", object, lines, new String[] {
                "getElseActions", "getElseActionList", "getElseAction", "getElseList" });
        addSemanticCollection("Guide Membership", object, lines, new String[] {
                "getGuideNames", "getGuides", "getGuideList" }, new String[] { "name", "order", "definition" });
        addSemanticCollection("Permissions", object, lines, new String[] {
                "getPermissions", "getPermissionList" }, new String[] { "group", "groupId", "permission", "definition" });

        if (lines.isEmpty()) {
            addProperty(lines, "Main Settings", "definition", stableDisplay(object));
        }
    }

    private void collectMenuSnapshot(IModelObject object, Map<String, String> lines) {
        addMainSettings(object, lines, new String[] {
                "name", "customizationType", "overlayType", "menuType", "type", "refreshCode", "qualification", "server", "formName", "fieldName", "owner", "helpText" });
        addSemanticCollection("Menu Items", object, lines, new String[] {
                "getItems", "getMenuItems", "getMenuList", "getLevels" }, new String[] { "label", "value", "qualification", "definition" });
        if (lines.isEmpty()) {
            addProperty(lines, "Main Settings", "definition", stableDisplay(object));
        }
    }

    private void collectContainerSnapshot(IModelObject object, Map<String, String> lines) {
        addMainSettings(object, lines, new String[] {
                "name", "customizationType", "overlayType", "primaryForm", "primaryFormName", "owner", "helpText", "description" });
        String primaryForm = extractPrimaryFormFromObject(object);
        if (primaryForm.length() > 0) {
            addProperty(lines, "Associated Forms", "Primary Form", primaryForm);
        }
        List<ContainerRef> refs = discoverContainerRefs(object);
        if (refs.isEmpty()) {
            addProperty(lines, "Content", "discovered", "No content references exposed by this Developer Studio API version");
        } else {
            Collections.sort(refs, new Comparator<ContainerRef>() {
                public int compare(ContainerRef left, ContainerRef right) {
                    int byType = left.type.compareToIgnoreCase(right.type);
                    return byType != 0 ? byType : left.name.compareToIgnoreCase(right.name);
                }
            });
            for (ContainerRef ref : refs) {
                addProperty(lines, "Content / " + ref.type + " / " + ref.name, "exists", "present");
            }
        }
    }

    private List<ContainerRef> discoverContainerRefs(Object sourceObject) {
        List<ContainerRef> refs = new ArrayList<ContainerRef>();
        Set<String> seen = new LinkedHashSet<String>();
        if (sourceObject == null) {
            return refs;
        }
        Method[] methods = sourceObject.getClass().getMethods();
        for (int i = 0; i < methods.length && refs.size() < 1000; i++) {
            Method method = methods[i];
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            String methodName = method.getName().toLowerCase(Locale.ENGLISH);
            if (!(methodName.indexOf("content") >= 0 || methodName.indexOf("member") >= 0 || methodName.indexOf("item") >= 0
                    || methodName.indexOf("reference") >= 0 || methodName.indexOf("object") >= 0 || methodName.indexOf("container") >= 0)) {
                continue;
            }
            if (methodName.equals("getclass") || methodName.equals("getname") || methodName.equals("gettype") || methodName.equals("getobjecttype")) {
                continue;
            }
            try {
                Object value = method.invoke(sourceObject, new Object[0]);
                collectContainerRefs(value, refs, seen, 0);
            } catch (Throwable ignored) {
            }
        }
        return refs;
    }

    private void collectContainerRefs(Object value, List<ContainerRef> refs, Set<String> seen, int depth) {
        if (value == null || depth > 4 || refs.size() >= 1000) {
            return;
        }
        if (value instanceof IModelItem) {
            IModelItem item = (IModelItem) value;
            addContainerRef(typeNameFromObject(item), item.getName(), refs, seen);
            return;
        }
        Class<?> valueClass = value.getClass();
        if (isSimpleValue(valueClass) || value instanceof Timestamp) {
            return;
        }
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length && refs.size() < 1000; i++) {
                collectContainerRefs(Array.get(value, i), refs, seen, depth + 1);
            }
            return;
        }
        if (value instanceof Collection) {
            for (Object child : (Collection<?>) value) {
                collectContainerRefs(child, refs, seen, depth + 1);
                if (refs.size() >= 1000) return;
            }
            return;
        }
        if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) {
                collectContainerRefs(child, refs, seen, depth + 1);
                if (refs.size() >= 1000) return;
            }
            return;
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object key = entry.getKey();
                Object child = entry.getValue();
                if (key instanceof IModelType) {
                    collectContainerRefsWithType(child, ((IModelType) key).getTypeName(), refs, seen, depth + 1);
                } else {
                    collectContainerRefs(key, refs, seen, depth + 1);
                    collectContainerRefs(child, refs, seen, depth + 1);
                }
                if (refs.size() >= 1000) return;
            }
            return;
        }
        String type = typeNameFromObject(value);
        String name = firstNonEmpty(value, new String[] { "getName", "getObjectName", "getItemName", "getLabel" });
        if (type.length() > 0 && name.length() > 0) {
            addContainerRef(type, name, refs, seen);
        }
        if (valueClass.getName().startsWith("com.bmc.arsys") && depth < 2) {
            Map<String, Method> getters = getterMap(value);
            for (Map.Entry<String, Method> getter : getters.entrySet()) {
                String property = getter.getKey().toLowerCase(Locale.ENGLISH);
                if (property.indexOf("content") >= 0 || property.indexOf("member") >= 0 || property.indexOf("item") >= 0
                        || property.indexOf("reference") >= 0 || property.indexOf("object") >= 0 || property.indexOf("type") >= 0 || property.indexOf("name") >= 0) {
                    Object child = invokeGetter(value, getter.getValue());
                    collectContainerRefs(child, refs, seen, depth + 1);
                }
            }
        }
    }

    private void collectContainerRefsWithType(Object value, String explicitType, List<ContainerRef> refs, Set<String> seen, int depth) {
        if (value == null || explicitType == null || explicitType.trim().length() == 0 || depth > 5 || refs.size() >= 1000) {
            return;
        }
        if (value instanceof CharSequence) {
            addContainerRef(explicitType, String.valueOf(value).trim(), refs, seen);
            return;
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length && refs.size() < 1000; i++) {
                collectContainerRefsWithType(Array.get(value, i), explicitType, refs, seen, depth + 1);
            }
            return;
        }
        if (value instanceof Collection) {
            for (Object child : (Collection<?>) value) {
                collectContainerRefsWithType(child, explicitType, refs, seen, depth + 1);
                if (refs.size() >= 1000) return;
            }
            return;
        }
        if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) {
                collectContainerRefsWithType(child, explicitType, refs, seen, depth + 1);
                if (refs.size() >= 1000) return;
            }
            return;
        }
        String name = firstNonEmpty(value, new String[] { "getName", "getObjectName", "getItemName", "getLabel", "getFormName", "getFieldName" });
        if (name.length() > 0) {
            addContainerRef(explicitType, name, refs, seen);
        }
    }

    private String typeNameFromObject(Object object) {
        Object type = firstGetterValue(object, new String[] { "getType", "getModelType", "getObjectType", "getItemType" });
        if (type instanceof IModelType) {
            return ((IModelType) type).getTypeName();
        }
        String text = stableDisplay(type);
        if (text.length() > 0 && text.indexOf('@') < 0 && !"null".equalsIgnoreCase(text)) {
            return text;
        }
        String className = object == null ? "" : object.getClass().getSimpleName();
        return className.replace("Type", "").replace("Object", "");
    }

    private void addContainerRef(String type, String name, List<ContainerRef> refs, Set<String> seen) {
        if (type == null || name == null) {
            return;
        }
        String cleanType = prettifySnapshotProperty(type.trim());
        String cleanName = name.trim();
        if (cleanType.length() == 0 || cleanName.length() == 0 || cleanName.indexOf('@') >= 0) {
            return;
        }
        String key = cleanType.toLowerCase(Locale.ENGLISH) + "\u0000" + cleanName.toLowerCase(Locale.ENGLISH);
        if (seen.add(key)) {
            refs.add(new ContainerRef(cleanType, cleanName));
        }
    }

    private static final class ContainerRef {
        final String type;
        final String name;
        ContainerRef(String type, String name) {
            this.type = type == null ? "" : type;
            this.name = name == null ? "" : name;
        }
    }

    private void addMainSettings(Object object, Map<String, String> lines, String[] properties) {
        for (int i = 0; i < properties.length; i++) {
            addNamedPropertySmart(lines, "Main Settings", object, properties[i]);
        }
    }

    private void addComponentSnapshots(String group, Collection<?> components, Map<String, String> lines, String[] preferredProperties) {
        if (components == null) {
            return;
        }
        for (Object component : components) {
            if (component == null) {
                continue;
            }
            String label = componentKey(group, component);
            String area = group + " / " + label;
            addProperty(lines, area, "exists", "present");
            boolean any = false;
            for (int i = 0; i < preferredProperties.length; i++) {
                any |= addNamedPropertySmart(lines, area, component, preferredProperties[i]);
            }
            if (!any) {
                addProperty(lines, group + " / " + label, "definition", stableDisplay(component));
            }
        }
    }

    private void addSemanticCollection(String group, Object object, Map<String, String> lines, String[] getterNames, String[] preferredProperties) {
        Object value = firstGetterValue(object, getterNames);
        if (value == null) {
            return;
        }
        Collection<?> collection = safeCollection(value);
        if (!collection.isEmpty()) {
            int index = 0;
            for (Object element : collection) {
                index++;
                String label = componentKey(element);
                if (label == null || label.length() == 0 || label.indexOf('@') >= 0) {
                    label = String.valueOf(index);
                }
                boolean any = false;
                for (int i = 0; i < preferredProperties.length; i++) {
                    any |= addNamedPropertySmart(lines, group + " / " + label, element, preferredProperties[i]);
                }
                if (!any) {
                    addProperty(lines, group + " / " + label, "definition", stableDisplay(element));
                }
            }
        } else {
            addProperty(lines, group, "definition", stableDisplay(value));
        }
    }

    private void addActionSnapshots(String group, Object object, Map<String, String> lines, String[] getterNames) {
        Object value = firstGetterValue(object, getterNames);
        if (value == null) {
            return;
        }
        Collection<?> collection = safeCollection(value);
        if (!collection.isEmpty()) {
            int index = 0;
            for (Object action : collection) {
                index++;
                String label = String.valueOf(index);
                String type = firstNonEmpty(action, new String[] { "getActionType", "getType", "getActionName", "getName" });
                if (type.length() > 0) {
                    label = label + " " + type;
                }
                String area = group + " / " + label;
                addProperty(lines, area, "type", type.length() == 0 ? action.getClass().getSimpleName() : type);
                addNamedPropertySmart(lines, area, action, "fieldId");
                addNamedPropertySmart(lines, area, action, "fieldName");
                addNamedPropertySmart(lines, area, action, "value");
                addNamedPropertySmart(lines, area, action, "qualification");
                addNamedPropertySmart(lines, area, action, "mapping");
                addNamedPropertySmart(lines, area, action, "server");
                addNamedPropertySmart(lines, area, action, "formName");
                addSimpleGetterSnapshot(lines, area, action, 18);
                addProperty(lines, area, "definition", stableDisplay(action));
            }
        } else {
            addProperty(lines, group, "definition", stableDisplay(value));
        }
    }

    private boolean addNamedProperty(Map<String, String> lines, String area, Object object, String property) {
        if (object == null || property == null || shouldSkipSnapshotProperty(property)) {
            return false;
        }
        Object value = invokeProperty(object, property);
        if (value == null) {
            return false;
        }
        addProperty(lines, area, property, stableDisplay(value));
        return true;
    }

    private boolean addNamedPropertySmart(Map<String, String> lines, String area, Object object, String property) {
        if (object == null || property == null || shouldSkipSnapshotProperty(property)) {
            return false;
        }
        Object value = invokeProperty(object, property);
        if (value == null) {
            return false;
        }
        if (area != null && area.startsWith("Fields / ") && isFieldNameProperty(property)) {
            Object fieldId = firstNonNull(object, new String[] { "getFieldID", "getFieldId", "getId" });
            if (fieldId instanceof Number) {
                String canonical = canonicalSystemFieldName(((Number) fieldId).intValue(), stableDisplay(value));
                if (canonical.length() > 0) {
                    value = canonical;
                }
            }
        }
        addStructuredValue(lines, area, property, value, 0);
        return true;
    }

    private void addStructuredValue(Map<String, String> lines, String area, String property, Object value, int depth) {
        if (value == null || shouldSkipSnapshotProperty(property)) {
            return;
        }
        Class<?> type = value.getClass();
        if (isSimpleValue(type) || value instanceof Timestamp || depth >= 2) {
            addProperty(lines, area, property, stableDisplay(value));
            return;
        }
        if (type.isArray()) {
            int length = Array.getLength(value);
            if (length == 0) {
                return;
            }
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                addStructuredValue(lines, area + " / " + prettifySnapshotProperty(property) + " " + (i + 1), "definition", element, depth + 1);
            }
            return;
        }
        if (value instanceof Collection) {
            int index = 0;
            for (Object element : (Collection<?>) value) {
                index++;
                String label = componentKey(element);
                if (label == null || label.length() == 0 || label.indexOf('@') >= 0) {
                    label = String.valueOf(index);
                }
                addStructuredValue(lines, area + " / " + prettifySnapshotProperty(property) + " " + label, "definition", element, depth + 1);
            }
            return;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stableDisplay(entry.getKey());
                addStructuredValue(lines, area + " / " + prettifySnapshotProperty(property) + " " + key, "value", entry.getValue(), depth + 1);
            }
            return;
        }

        Map<String, Method> getters = getterMap(value);
        int added = 0;
        for (Map.Entry<String, Method> getter : getters.entrySet()) {
            String child = getter.getKey();
            if (shouldSkipSnapshotProperty(child) || "definition".equalsIgnoreCase(child)) {
                continue;
            }
            Object childValue = invokeGetter(value, getter.getValue());
            if (childValue == null) {
                continue;
            }
            if (isSimpleValue(childValue.getClass()) || childValue instanceof Timestamp) {
                addProperty(lines, area, property + "." + child, stableDisplay(childValue));
                added++;
            }
            if (added >= 24) {
                break;
            }
        }
        if (added == 0) {
            addProperty(lines, area, property, stableDisplay(value));
        }
    }

    private void addSimpleGetterSnapshot(Map<String, String> lines, String area, Object object, int maxProperties) {
        if (object == null) {
            return;
        }
        Map<String, Method> getters = getterMap(object);
        int added = 0;
        for (Map.Entry<String, Method> getter : getters.entrySet()) {
            String property = getter.getKey();
            if (shouldSkipSnapshotProperty(property) || property.equalsIgnoreCase("definition") || property.equalsIgnoreCase("type")) {
                continue;
            }
            Object value = invokeGetter(object, getter.getValue());
            if (value == null) {
                continue;
            }
            Class<?> type = value.getClass();
            if (isSimpleValue(type) || value instanceof Timestamp || value instanceof Collection || type.isArray()) {
                addStructuredValue(lines, area, property, value, 0);
                added++;
            }
            if (added >= maxProperties) {
                break;
            }
        }
    }

    private void addFirstValue(Map<String, String> lines, String area, String property, Object object, String[] getterNames) {
        Object value = firstGetterValue(object, getterNames);
        if (value != null) {
            addProperty(lines, area, property, stableDisplay(value));
        }
    }

    private void addProperty(Map<String, String> lines, String area, String property, String value) {
        if (lines == null || shouldSkipSnapshotProperty(property)) {
            return;
        }
        String text = value == null ? "" : value.trim();
        if (text.length() == 0) {
            return;
        }
        lines.put(area + "/" + property, text);
    }

    private Object invokeProperty(Object object, String property) {
        if (object == null || property == null || property.length() == 0) {
            return null;
        }
        if ("customizationType".equalsIgnoreCase(property)) {
            String customization = BmcMetadataCache.customizationType(object);
            return customization.length() == 0 ? null : customization;
        }
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        Object value = invokeNoArg(object, "get" + suffix);
        if (value == null && suffix.endsWith("Id")) {
            value = invokeNoArg(object, "get" + suffix.substring(0, suffix.length() - 2) + "ID");
        }
        if (value == null && suffix.indexOf("ID") >= 0) {
            value = invokeNoArg(object, "get" + suffix.replace("ID", "Id"));
        }
        if (value == null) {
            value = invokeNoArg(object, "is" + suffix);
        }
        return value;
    }

    private Object firstGetterValue(Object object, String[] getterNames) {
        if (object == null || getterNames == null) {
            return null;
        }
        for (int i = 0; i < getterNames.length; i++) {
            Object value = invokeNoArg(object, getterNames[i]);
            if (value != null) {
                String stable = stableDisplay(value);
                if (stable.length() > 0 && !"[]".equals(stable) && !"{}".equals(stable)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String firstNonEmpty(Object object, String[] getterNames) {
        Object value = firstGetterValue(object, getterNames);
        return value == null ? "" : stableDisplay(value);
    }

    private boolean containsAny(String left, String right, String[] needles) {
        String haystack = ((left == null ? "" : left) + " " + (right == null ? "" : right)).toLowerCase(Locale.ENGLISH);
        for (int i = 0; i < needles.length; i++) {
            if (haystack.indexOf(needles[i].toLowerCase(Locale.ENGLISH)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private String stableDisplay(Object value) {
        return displayValue(cleanStableValue(stableValue(value)));
    }

    private String cleanStableValue(String text) {
        if (text == null) {
            return "";
        }
        String value = text;
        value = value.replace("com.bmc.arsys.studio.model.ar.", "");
        value = value.replace("com.bmc.arsys.studio.model.", "");
        value = value.replace("com.bmc.arsys.api.", "");
        value = value.replace("java.lang.", "");
        return value;
    }

    private void addComponentSnapshots(String group, Collection<?> components, Map<String, String> lines) {
        if (components == null) {
            return;
        }
        for (Object component : components) {
            String label = componentKey(component);
            collectSnapshot(group + " " + label, component, lines, 0, new IdentityHashMap<Object, Boolean>());
        }
    }

    private void collectSnapshot(String path, Object object, Map<String, String> lines, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (object == null || depth > 3 || seen.containsKey(object)) {
            return;
        }
        seen.put(object, Boolean.TRUE);
        try {
            Map<String, Method> getters = getterMap(object);
            for (Map.Entry<String, Method> entry : getters.entrySet()) {
                String property = entry.getKey();
                if (shouldSkipSnapshotProperty(property)) {
                    continue;
                }
                Object value = invokeGetter(object, entry.getValue());
                if (value == null) {
                    lines.put(path + "/" + property, "");
                    continue;
                }
                if (isExpandableSnapshotValue(value, depth)) {
                    collectSnapshot(path + "/" + property, value, lines, depth + 1, seen);
                } else {
                    lines.put(path + "/" + property, displayValue(stableValue(value)));
                }
            }
        } finally {
            seen.remove(object);
        }
    }

    private boolean shouldSkipSnapshotProperty(String property) {
        if (property == null || property.length() == 0) {
            return true;
        }
        if (settings.shouldIgnoreFingerprintMember(property) || settings.shouldIgnoreDifferenceName(property)) {
            return true;
        }
        String lower = property.toLowerCase(Locale.ENGLISH);
        return lower.equals("objecttype")
                || lower.endsWith(".objecttype")
                || lower.equals("supportedmaskoptions")
                || lower.equals("store")
                || lower.equals("class")
                || lower.equals("dirty")
                || lower.equals("initialized")
                || lower.equals("lastupdatetime")
                || lower.equals("lastchangedby")
                || lower.equals("modifieddate")
                || lower.equals("cache mode")
                || lower.equals("auditinfo")
                || lower.equals("audit info")
                || lower.equals("supportedmaskoptions")
                || lower.indexOf("listener") >= 0
                || lower.indexOf("maskoptions") >= 0
                || lower.indexOf("adapter") >= 0;
    }

    private boolean isExpandableSnapshotValue(Object value, int depth) {
        if (value == null || depth >= 2) {
            return false;
        }
        Class<?> type = value.getClass();
        if (isSimpleValue(type) || type.isArray() || value instanceof Collection || value instanceof Map) {
            return false;
        }
        String className = type.getName();
        return className.startsWith("com.bmc.arsys") && className.indexOf("MaskOptions") < 0;
    }

    private String fingerprintText(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception ex) {
            return String.valueOf(text == null ? 0 : text.hashCode());
        }
    }

    private String fingerprintObject(IModelObject object) {
        return fingerprintText(canonicalSnapshot(object));
    }

    private List<IStore> selectedStores(IStore sourceStore, IStore targetStore) {
        List<IStore> stores = new ArrayList<IStore>();
        if (sourceStore != null) {
            stores.add(sourceStore);
        }
        if (targetStore != null && (sourceStore == null || !targetStore.getName().equalsIgnoreCase(sourceStore.getName()))) {
            stores.add(targetStore);
        }
        return stores;
    }

    private boolean containsStore(List<IStore> stores, IStore candidate) {
        if (candidate == null || stores == null) {
            return false;
        }
        for (IStore store : stores) {
            if (store != null && store.getName().equalsIgnoreCase(candidate.getName())) {
                return true;
            }
        }
        return false;
    }


    /**
     * Builds a metadata-only candidate list from the persistent cache.
     *
     * No full object definitions are opened here. This is intentionally fast and is meant as the
     * first step for broad searches in legacy metadata-plan mode.
     */
    public List<CompareResult> planSearch(IStore sourceStore, IStore targetStore, List<IModelType> types, String query,
            boolean includeSourceOnly, boolean includeTargetOnly, boolean refreshMetadataFirst, int maxResults,
            IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        List<CompareResult> results = new ArrayList<CompareResult>();
        if (sourceStore == null || targetStore == null || types == null || types.isEmpty()) {
            return results;
        }
        if (refreshMetadataFirst || !metadataCache.hasAny(sourceStore, types) || (includeTargetOnly && !metadataCache.hasAny(targetStore, types))) {
            report(progress, "Metadata cache is missing or quick-sync was requested. Updating selected environment metadata before planning...");
            refreshMetadataCache(selectedStores(sourceStore, includeTargetOnly ? targetStore : null), types, false, safeMonitor, progress);
        }

        Map<String, SearchKey> keys = new TreeMap<String, SearchKey>(String.CASE_INSENSITIVE_ORDER);
        addCachedSearchKeys(keys, sourceStore, types, query, true, true);
        if (includeTargetOnly) {
            addCachedSearchKeys(keys, targetStore, types, query, true, false);
        }
        report(progress, "Metadata plan matched " + keys.size() + " unique object key(s) for query '" + safeQuery(query) + "'.");

        int limit = maxResults <= 0 ? CompareSettings.load().getSearchMaxResults() : maxResults;
        if (keys.size() > limit) {
            report(progress, "Metadata plan matched " + keys.size() + " object key(s), but only the first " + limit
                    + " will be shown. Narrow the search or raise 'Max objects compared from global search' in Preferences.");
        }
        int count = 0;
        for (SearchKey key : keys.values()) {
            if (safeMonitor.isCanceled() || count >= limit) {
                break;
            }
            CompareResult result = createCandidateResult(sourceStore, targetStore, key);
            if (!includeSourceOnly && result.getStatus() == CompareStatus.MISSING_IN_TARGET) {
                count++;
                continue;
            }
            if (!includeTargetOnly && result.getStatus() == CompareStatus.MISSING_IN_SOURCE) {
                count++;
                continue;
            }
            results.add(result);
            count++;
        }
        report(progress, "Metadata plan finished. Returned " + results.size() + " candidate row(s). No definitions were deep-read.");
        return results;
    }

    private CompareResult createCandidateResult(IStore sourceStore, IStore targetStore, SearchKey key) {
        CompareResult result = new CompareResult();
        result.setEvidence(CompareEvidence.METADATA_ONLY);
        result.setEvidenceDetail("Lightweight metadata cache row. Run Sync for snapshot-level details or Refresh Selected from Server for live verification.");
        result.setSourceStore(sourceStore);
        result.setTargetStore(targetStore);
        result.setModelType(key.type);
        result.setObjectName(key.name);
        result.setObjectType(typeName(key.type));
        result.setSourceServer(sourceStore == null ? "" : sourceStore.getName());
        result.setTargetServer(targetStore == null ? "" : targetStore.getName());
        if (key.sourceEntry != null) {
            result.setSourceModified(toTimestamp(key.sourceEntry.modified));
            result.setSourceChangedBy(key.sourceEntry.changedBy);
            result.setSourceCustomizationType(key.sourceEntry.customizationType);
            result.setSourceContextKey(key.sourceEntry.contextKey);
        }
        if (key.targetEntry != null) {
            result.setTargetModified(toTimestamp(key.targetEntry.modified));
            result.setTargetChangedBy(key.targetEntry.changedBy);
            result.setTargetCustomizationType(key.targetEntry.customizationType);
            result.setTargetContextKey(key.targetEntry.contextKey);
        }
        if (key.sourceEntry == null && key.targetEntry == null) {
            result.setStatus(CompareStatus.ERROR);
            result.setDetail("No metadata found for this object in either environment.");
            return result;
        }
        if (key.sourceEntry == null) {
            result.setStatus(CompareStatus.MISSING_IN_SOURCE);
            result.setDetail("Metadata plan: object exists in target cache but not in source cache. Deep compare not run.");
            return result;
        }
        if (key.targetEntry == null) {
            result.setStatus(CompareStatus.MISSING_IN_TARGET);
            result.setDetail("Metadata plan: object exists in source cache but not in target cache. Deep compare not run.");
            return result;
        }
        if (key.sourceEntry.hasReliableMetadata() && key.targetEntry.hasReliableMetadata()
                && key.sourceEntry.modified == key.targetEntry.modified
                && stringEquals(key.sourceEntry.changedBy, key.targetEntry.changedBy)) {
            result.setStatus(CompareStatus.EQUAL);
            result.setDifferenceCount(0);
            result.setDetail("Metadata plan: source and target metadata match. Full definition deep compare not run.");
        } else {
            result.setStatus(CompareStatus.UNKNOWN);
            result.setDifferenceCount(-1);
            result.setDetail("Metadata plan: object exists in both environments but metadata differs or is incomplete. Run Sync to build cached details, or Refresh Selected from Server for live verification.");
        }
        return result;
    }



    private CompareEvidence cacheEvidence(DefinitionEntry source, DefinitionEntry target) {
        if (source == null && target == null) {
            return CompareEvidence.UNKNOWN;
        }
        if ((source != null && source.error.length() > 0) || (target != null && target.error.length() > 0)) {
            return CompareEvidence.CACHE_ERROR;
        }
        if ((source != null && source.fingerprint.length() == 0) || (target != null && target.fingerprint.length() == 0)) {
            return CompareEvidence.METADATA_ONLY;
        }
        if ((source != null && source.modified == Long.MIN_VALUE) || (target != null && target.modified == Long.MIN_VALUE)) {
            return CompareEvidence.REUSED_SNAPSHOT;
        }
        return CompareEvidence.CACHED_SNAPSHOT;
    }

    private String cacheEvidenceDetail(DefinitionEntry source, DefinitionEntry target) {
        StringBuilder detail = new StringBuilder();
        appendCacheSide(detail, "Source", source);
        appendCacheSide(detail, "Target", target);
        return detail.toString();
    }

    private void appendCacheSide(StringBuilder detail, String label, DefinitionEntry entry) {
        if (detail.length() > 0) {
            detail.append(" | ");
        }
        detail.append(label).append(": ");
        if (entry == null) {
            detail.append("not cached");
            return;
        }
        if (entry.error.length() > 0) {
            detail.append("error=").append(entry.error);
            return;
        }
        if (entry.fingerprint.length() == 0) {
            detail.append("metadata only/no fingerprint");
        } else {
            detail.append("fingerprint ").append(shortFingerprint(entry.fingerprint));
        }
        if (entry.sourceKind.length() > 0) {
            detail.append(", kind=").append(entry.sourceKind);
        }
        if (entry.modified == Long.MIN_VALUE) {
            detail.append(", last update unavailable");
        }
        if (entry.cachedAt > 0L) {
            detail.append(", cached ").append(new java.util.Date(entry.cachedAt));
        }
    }

    private String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() == 0) {
            return "";
        }
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }

    private Timestamp toTimestamp(long value) {
        if (value == Long.MIN_VALUE) {
            return null;
        }
        try {
            return new Timestamp(value);
        } catch (Throwable ex) {
            return null;
        }
    }

    private boolean stringEquals(String left, String right) {
        if (left == null) {
            return right == null || right.length() == 0;
        }
        return left.equals(right == null ? "" : right);
    }

    public List<CompareResult> compareSearch(IStore sourceStore, IStore targetStore, List<IModelType> types, String query,
            boolean includeSourceOnly, boolean includeTargetOnly, boolean useMetadataCache, boolean refreshMetadataFirst, int maxResults, IProgressMonitor monitor) {
        return compareSearch(sourceStore, targetStore, types, query, includeSourceOnly, includeTargetOnly,
                useMetadataCache, refreshMetadataFirst, maxResults, monitor, null);
    }

    public List<CompareResult> compareSearch(IStore sourceStore, IStore targetStore, List<IModelType> types, String query,
            boolean includeSourceOnly, boolean includeTargetOnly, boolean useMetadataCache, boolean refreshMetadataFirst,
            int maxResults, IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        List<CompareResult> results = new ArrayList<CompareResult>();
        if (sourceStore == null || targetStore == null || types == null || types.isEmpty()) {
            return results;
        }

        // Object cache is intended to avoid duplicate reads during one run, not to keep stale server
        // definitions across separate searches.
        clearCache();

        Map<String, SearchKey> keys = new TreeMap<String, SearchKey>(String.CASE_INSENSITIVE_ORDER);
        if (useMetadataCache) {
            if (refreshMetadataFirst || !metadataCache.hasAny(sourceStore, types) || (includeTargetOnly && !metadataCache.hasAny(targetStore, types))) {
                report(progress, "Metadata cache is missing or quick-sync was requested. Updating selected environment metadata before compare...");
                refreshMetadataCache(selectedStores(sourceStore, includeTargetOnly ? targetStore : null), types, false, safeMonitor, progress);
            }
            addCachedSearchKeys(keys, sourceStore, types, query, true, true);
            if (includeTargetOnly) {
                addCachedSearchKeys(keys, targetStore, types, query, true, false);
            }
            report(progress, "Metadata search matched " + keys.size() + " unique object key(s) for query '" + safeQuery(query) + "'.");
        } else {
            report(progress, "Searching directly against Developer Studio object lists. This can be slow for large workflow scopes.");
            addSearchKeys(keys, sourceStore, types, query, true, safeMonitor);
            if (includeTargetOnly) {
                addSearchKeys(keys, targetStore, types, query, true, safeMonitor);
            }
            report(progress, "Direct search matched " + keys.size() + " unique object key(s) for query '" + safeQuery(query) + "'.");
        }

        int limit = maxResults <= 0 ? CompareSettings.load().getSearchMaxResults() : maxResults;
        int total = Math.min(limit, keys.size());
        int timeoutSeconds = CompareSettings.load().getCompareObjectTimeoutSeconds();
        if (keys.size() > limit) {
            report(progress, "Search matched " + keys.size() + " object key(s), but only the first " + limit
                    + " will be refreshed from the live servers. Narrow the search or raise 'Max objects compared from global search' in Preferences.");
        }
        if (total == 0) {
            report(progress, "No matching objects found to compare.");
            return results;
        }
        report(progress, "Starting deep compare for " + total + " object(s). Timeout per object=" + timeoutSeconds + "s.");

        safeMonitor.beginTask("Comparing AR objects", total);
        int count = 0;
        for (SearchKey key : keys.values()) {
            if (safeMonitor.isCanceled() || count >= limit) {
                break;
            }
            int current = count + 1;
            report(progress, "Deep comparing " + current + "/" + total + ": " + typeName(key.type) + " " + key.name);
            CompareResult result = compareWithTimeout(sourceStore, targetStore, key.type, key.name, safeMonitor, timeoutSeconds, progress, current, total);
            if (!includeSourceOnly && result.getStatus() == CompareStatus.MISSING_IN_TARGET) {
                count++;
                safeMonitor.worked(1);
                continue;
            }
            if (!includeTargetOnly && result.getStatus() == CompareStatus.MISSING_IN_SOURCE) {
                count++;
                safeMonitor.worked(1);
                continue;
            }
            results.add(result);
            count++;
            safeMonitor.worked(1);
        }
        safeMonitor.done();
        report(progress, "Deep compare finished. Returned " + results.size() + " row(s).");
        return results;
    }

    public CompareResult refreshCompareWithTimeout(final IStore sourceStore, final IStore targetStore, final IModelType type,
            final String name, final IProgressMonitor monitor, SyncProgress progress) {
        int timeoutSeconds = CompareSettings.load().getCompareObjectTimeoutSeconds();
        int timeout = timeoutSeconds <= 0 ? 180 : timeoutSeconds;
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "Yrell Migrator selected refresh " + typeName(type) + " " + name);
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<CompareResult> future = executor.submit(new Callable<CompareResult>() {
            public CompareResult call() {
                return refreshCompareFromServer(sourceStore, targetStore, type, name, monitor, progress);
            }
        });
        try {
            return future.get(timeout * 2L, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            CompareResult result = baseResult(sourceStore, targetStore, type, name);
            result.setStatus(CompareStatus.ERROR);
            result.setDifferenceCount(-1);
            result.setDetail("Selected refresh timed out after " + (timeout * 2L)
                    + " second(s). The object cache was not trusted; try Sync, or increase compare.objectTimeoutSeconds.");
            report(progress, "TIMEOUT refreshing selected row: " + typeName(type) + " " + name + ".");
            return result;
        } catch (Throwable ex) {
            CompareResult result = baseResult(sourceStore, targetStore, type, name);
            result.setStatus(CompareStatus.ERROR);
            result.setDifferenceCount(-1);
            result.setDetail(safeMessage(ex));
            report(progress, "ERROR refreshing selected row: " + typeName(type) + " " + name + " - " + safeMessage(ex));
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private CompareResult refreshCompareFromServer(IStore sourceStore, IStore targetStore, IModelType type,
            String name, IProgressMonitor monitor, SyncProgress progress) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        CompareResult base = baseResult(sourceStore, targetStore, type, name);
        if (sourceStore == null || !sourceStore.isConnected()) {
            base.setStatus(CompareStatus.ERROR);
            base.setDetail("Source server is not connected.");
            return base;
        }
        if (targetStore == null || !targetStore.isConnected()) {
            base.setStatus(CompareStatus.ERROR);
            base.setDetail("Target server is not connected.");
            return base;
        }
        if (type == null || name == null || name.length() == 0) {
            base.setStatus(CompareStatus.ERROR);
            base.setDetail("Selected object has no usable type or name.");
            return base;
        }

        String typeName = typeName(type);
        report(progress, "Refreshing selected row from server: " + typeName + " " + name
                + " (source and target cache snapshots will be rebuilt if the object exists).");
        objectCache.clear();
        RefreshSide source = refreshOneSideFromServer(sourceStore, type, name, safeMonitor, progress);
        RefreshSide target = refreshOneSideFromServer(targetStore, type, name, safeMonitor, progress);

        SearchKey key = new SearchKey(type, name);
        key.sourceDefinition = source.definition;
        key.targetDefinition = target.definition;
        CompareResult result = createCachedResult(sourceStore, targetStore, key);
        result.setSourceItem(source.item);
        result.setTargetItem(target.item);
        if (source.missing && target.missing) {
            result.setStatus(CompareStatus.ERROR);
            result.setDifferenceCount(-1);
            result.setDetail("Object was not found in either environment during selected refresh. Removed stale cache rows for both sides.");
        } else if (source.message.length() > 0 || target.message.length() > 0) {
            StringBuilder detail = new StringBuilder(result.getDetail() == null ? "" : result.getDetail());
            if (source.message.length() > 0) {
                if (detail.length() > 0) {
                    detail.append(" ");
                }
                detail.append("Source refresh: ").append(source.message).append('.');
            }
            if (target.message.length() > 0) {
                if (detail.length() > 0) {
                    detail.append(" ");
                }
                detail.append("Target refresh: ").append(target.message).append('.');
            }
            result.setDetail(detail.toString());
        }
        report(progress, "Selected row refreshed: " + typeName + " " + name + " -> " + result.getStatus().name() + ".");
        return result;
    }

    private RefreshSide refreshOneSideFromServer(IStore store, IModelType type, String name,
            IProgressMonitor monitor, SyncProgress progress) {
        RefreshSide side = new RefreshSide();
        String typeName = typeName(type);
        if (store == null || !store.isConnected()) {
            side.error = true;
            side.message = "server is not connected";
            return side;
        }
        try {
            monitor.subTask("Refreshing " + store.getName() + " / " + typeName + " " + name);
            IModelItem item = null;
            try {
                item = findTargetItem(store, type, name);
            } catch (ModelException missing) {
                item = null;
            }
            if (item == null) {
                metadataCache.remove(store.getName(), typeName, name);
                definitionCache.remove(store.getName(), typeName, name);
                side.missing = true;
                side.message = "object was not found; stale local cache row removed";
                report(progress, store.getName() + ": " + typeName + " " + name + " not found; removed stale cache rows.");
                return side;
            }
            side.item = item;
            CacheEntry entry = metadataCache.putFromItem(store.getName(), typeName, item);
            if (entry == null) {
                long modified = BmcMetadataCache.timestampValue(item.getLastUpdateTime());
                String changedBy = item.getLastChangedBy() == null ? "" : item.getLastChangedBy();
                entry = new CacheEntry(store.getName(), typeName, item.getName(), modified, changedBy,
                        BmcMetadataCache.customizationType(item), BmcMetadataCache.contextKey(item));
                metadataCache.putEntry(entry);
            }
            if (shouldUseLightweightSnapshot(typeName)) {
                String snapshot = metadataOnlySnapshot(entry, typeName);
                definitionCache.putSnapshot(entry, fingerprintText(snapshot), snapshot, BmcDefinitionCache.CURRENT_SNAPSHOT_KIND);
                definitionCache.save();
                side.definition = definitionCache.get(store.getName(), typeName, name);
                side.message = "lightweight snapshot refreshed";
                return side;
            }
            SnapshotLoadResult snapshot = loadSnapshotWithTimeout(store, type, entry, monitor, progress);
            if (snapshot.error != null && snapshot.error.length() > 0) {
                definitionCache.putError(entry, snapshot.error);
                definitionCache.save();
                side.definition = definitionCache.get(store.getName(), typeName, name);
                side.error = true;
                side.message = snapshot.error;
                return side;
            }
            CacheEntry snapshotEntry = withSnapshotCustomization(entry, snapshot.customizationType);
            if (snapshotEntry != entry) {
                metadataCache.putEntry(snapshotEntry);
            }
            definitionCache.putSnapshot(snapshotEntry, fingerprintText(snapshot.snapshot), snapshot.snapshot, BmcDefinitionCache.CURRENT_SNAPSHOT_KIND);
            definitionCache.save();
            side.definition = definitionCache.get(store.getName(), typeName, name);
            side.message = "snapshot refreshed";
            return side;
        } catch (Throwable ex) {
            side.error = true;
            side.message = safeMessage(ex);
            report(progress, store.getName() + ": ERROR refreshing " + typeName + " " + name + " - " + safeMessage(ex));
            return side;
        } finally {
            objectCache.clear();
        }
    }

    private CompareResult baseResult(IStore sourceStore, IStore targetStore, IModelType type, String name) {
        CompareResult result = new CompareResult();
        result.setSourceStore(sourceStore);
        result.setTargetStore(targetStore);
        result.setModelType(type);
        result.setObjectName(name);
        result.setObjectType(typeName(type));
        result.setSourceServer(sourceStore == null ? "" : sourceStore.getName());
        result.setTargetServer(targetStore == null ? "" : targetStore.getName());
        return result;
    }

    private static final class RefreshSide {
        IModelItem item;
        DefinitionEntry definition;
        boolean missing;
        boolean error;
        String message = "";
    }

    private CompareResult compareWithTimeout(final IStore sourceStore, final IStore targetStore, final IModelType type,
            final String name, final IProgressMonitor monitor, int timeoutSeconds, SyncProgress progress, int current, int total) {
        int timeout = timeoutSeconds <= 0 ? 180 : timeoutSeconds;
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "Yrell Migrator deep compare " + typeName(type) + " " + name);
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<CompareResult> future = executor.submit(new Callable<CompareResult>() {
            public CompareResult call() {
                return compare(sourceStore, targetStore, type, name, monitor);
            }
        });
        try {
            CompareResult result = future.get(timeout, TimeUnit.SECONDS);
            report(progress, "Compared " + current + "/" + total + ": " + typeName(type) + " " + name + " -> " + result.getStatus().name());
            return result;
        } catch (TimeoutException ex) {
            future.cancel(true);
            CompareResult result = new CompareResult();
            result.setSourceStore(sourceStore);
            result.setTargetStore(targetStore);
            result.setModelType(type);
            result.setObjectName(name);
            result.setObjectType(typeName(type));
            result.setSourceServer(sourceStore == null ? "" : sourceStore.getName());
            result.setTargetServer(targetStore == null ? "" : targetStore.getName());
            result.setStatus(CompareStatus.ERROR);
            result.setDifferenceCount(-1);
            result.setDetail("Deep compare timed out after " + timeout + " second(s). Narrow the search, increase compare.objectTimeoutSeconds, or compare this object individually.");
            report(progress, "TIMEOUT " + current + "/" + total + ": " + typeName(type) + " " + name + " after " + timeout + "s.");
            Activator.logWarning("Yrell Migrator deep compare timed out for " + typeName(type) + " " + name, ex);
            return result;
        } catch (Throwable ex) {
            CompareResult result = new CompareResult();
            result.setSourceStore(sourceStore);
            result.setTargetStore(targetStore);
            result.setModelType(type);
            result.setObjectName(name);
            result.setObjectType(typeName(type));
            result.setSourceServer(sourceStore == null ? "" : sourceStore.getName());
            result.setTargetServer(targetStore == null ? "" : targetStore.getName());
            result.setStatus(CompareStatus.ERROR);
            result.setDifferenceCount(-1);
            result.setDetail(safeMessage(ex));
            report(progress, "ERROR " + current + "/" + total + ": " + typeName(type) + " " + name + " - " + safeMessage(ex));
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    public List<IModelItem> searchItems(IStore store, List<IModelType> types, String query, int maxResults, IProgressMonitor monitor) {
        List<IModelItem> result = new ArrayList<IModelItem>();
        if (store == null || types == null) {
            return result;
        }
        Map<String, SearchKey> keys = new TreeMap<String, SearchKey>(String.CASE_INSENSITIVE_ORDER);
        addSearchKeys(keys, store, types, query, true, monitor == null ? new NullProgressMonitor() : monitor);
        int limit = maxResults <= 0 ? Integer.MAX_VALUE : maxResults;
        for (SearchKey key : keys.values()) {
            if (result.size() >= limit) {
                break;
            }
            try {
                IModelItem item = findTargetItem(store, key.type, key.name);
                if (item != null) {
                    result.add(item);
                }
            } catch (ModelException ignored) {
            }
        }
        return result;
    }

    private void addCachedSearchKeys(Map<String, SearchKey> keys, IStore store, List<IModelType> types, String query, boolean include, boolean sourceSide) {
        if (!include || store == null || types == null) {
            return;
        }
        List<CacheEntry> cached = metadataCache.find(store, types, query);
        for (CacheEntry entry : cached) {
            IModelType type = findTypeByName(types, entry.type);
            if (type != null) {
                SearchKey key = keys.get(new SearchKey(type, entry.name).toKey());
                if (key == null) {
                    key = new SearchKey(type, entry.name);
                    keys.put(key.toKey(), key);
                }
                if (sourceSide) {
                    key.sourceEntry = entry;
                } else {
                    key.targetEntry = entry;
                }
            }
        }
    }

    private IModelType findTypeByName(List<IModelType> types, String typeName) {
        if (types == null || typeName == null) {
            return null;
        }
        for (IModelType type : types) {
            if (type != null && typeName.equalsIgnoreCase(type.getTypeName())) {
                return type;
            }
        }
        return null;
    }

    private void addSearchKeys(Map<String, SearchKey> keys, IStore store, List<IModelType> types, String query, boolean include,
            IProgressMonitor monitor) {
        if (!include || store == null || types == null) {
            return;
        }
        SearchPattern pattern = SearchPattern.compile(query);
        for (IModelType type : types) {
            if (monitor != null && monitor.isCanceled()) {
                return;
            }
            if (type == null) {
                continue;
            }
            try {
                int before = keys.size();
                for (IModelItem item : BmcItemEnumerator.getItems(store, type)) {
                    if (item == null || item.getName() == null) {
                        continue;
                    }
                    if (!matchesSearch(item, type, pattern)) {
                        continue;
                    }
                    SearchKey key = new SearchKey(type, item.getName());
                    keys.put(key.toKey(), key);
                }

                // Fallback for providers that expose a name list but no item list until the object is opened.
                for (String name : BmcItemEnumerator.getNames(store, type)) {
                    if (name == null || name.length() == 0) {
                        continue;
                    }
                    if (!pattern.matchesEntry(name, type.getTypeName(), "")) {
                        continue;
                    }
                    SearchKey key = new SearchKey(type, name);
                    keys.put(key.toKey(), key);
                }
                if (keys.size() == before) {
                    Activator.logInfo("Yrell Migrator: no objects matched/enumerated for type " + type.getTypeName() + " on " + store.getName() + ".");
                }
            } catch (Throwable ex) {
                Activator.logWarning("Could not enumerate " + type.getTypeName() + " on " + store.getName(), ex);
            }
        }
    }

    private boolean matchesSearch(IModelItem item, IModelType fallbackType, SearchPattern pattern) {
        if (pattern == null) {
            return true;
        }
        IModelType type = item.getItemType() == null ? fallbackType : item.getItemType();
        return pattern.matchesEntry(item.getName(),
                type == null ? "" : type.getTypeName(),
                item.getLastChangedBy());
    }

    private String heapSummary() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long max = rt.maxMemory() / (1024L * 1024L);
        return used + " MB / " + max + " MB";
    }

    private void report(SyncProgress progress, String message) {
        if (progress != null && message != null && message.length() > 0) {
            try {
                progress.report(message);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private String safeQuery(String query) {
        return query == null || query.trim().length() == 0 ? "*" : query.trim();
    }

    private static String typeName(IModelType type) {
        return type == null ? "" : type.getTypeName();
    }

    private static final class SearchKey {
        final IModelType type;
        final String name;
        CacheEntry sourceEntry;
        CacheEntry targetEntry;
        DefinitionEntry sourceDefinition;
        DefinitionEntry targetDefinition;

        SearchKey(IModelType type, String name) {
            this.type = type;
            this.name = name;
        }

        String toKey() {
            return (type == null ? "" : type.getTypeName()) + "\u0000" + name;
        }
    }

    private static final class DifferenceReport {
        private final List<DiffDetail> items = new ArrayList<DiffDetail>();

        int getCount() {
            return items.size();
        }

        List<DiffDetail> getDetails() {
            return new ArrayList<DiffDetail>(items);
        }

        void add(DiffDetail item) {
            if (item == null) {
                return;
            }
            String summary = item.toSummary();
            for (DiffDetail existing : items) {
                if (existing.toSummary().equals(summary)
                        && existing.getSourceValue().equals(item.getSourceValue())
                        && existing.getTargetValue().equals(item.getTargetValue())) {
                    return;
                }
            }
            items.add(item);
        }

        String toSummary(int maxItems) {
            if (items.isEmpty()) {
                return "Different.";
            }
            StringBuilder builder = new StringBuilder();
            int limit = Math.max(1, maxItems);
            for (int i = 0; i < items.size() && i < limit; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(items.get(i).toSummary());
            }
            if (items.size() > limit) {
                builder.append(", ... +").append(items.size() - limit).append(" more");
            }
            return builder.toString();
        }
    }

    private String createDifferenceSummary(List<Difference> differences) {
        if (differences == null || differences.isEmpty()) {
            return "Different.";
        }
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (Difference difference : differences) {
            if (difference == null) {
                continue;
            }
            String name = difference.getName();
            if (name == null || name.length() == 0) {
                name = difference.toString();
            }
            if (name != null && name.length() > 0) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(name);
                added++;
            }
            if (added >= 5) {
                break;
            }
        }
        if (builder.length() == 0) {
            return "Different.";
        }
        if (differences.size() > added) {
            builder.append(", ...");
        }
        return builder.toString();
    }
}
