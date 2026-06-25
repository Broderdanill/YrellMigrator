package se.yrell.migrator.bmc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.LinkedHashSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.api.Constants;
import com.bmc.arsys.api.Entry;
import com.bmc.arsys.api.Field;
import com.bmc.arsys.api.OutputInteger;
import com.bmc.arsys.api.QualifierInfo;
import com.bmc.arsys.studio.model.ModelException;
import com.bmc.arsys.studio.model.store.IEntryStore;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.store.ARServerStore;

import se.yrell.migrator.Activator;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationResult;

/**
 * Generic migrator for catalog/data-backed object types that are safer to copy as AR entries than
 * through Developer Studio's definition/object API.
 *
 * The migrator is intentionally mapping based. A mapping tells the plugin which AR form holds a
 * visible object type and which field is the natural business key. The selected CompareResult name
 * is used as that key value. Target rows with the same key are deleted before the source row is
 * recreated, so Request ID differences do not matter.
 */
public final class BmcCatalogDataMigrator {
    private static final String PREFIX = "dataObject.";
    private static final int DEFAULT_MAX_MATCHES = 25;
    private final Map<String, Mapping> mappings;

    public BmcCatalogDataMigrator() {
        mappings = loadMappings();
    }

    public boolean canMigrate(CompareResult result, MigrationDirection direction) {
        if (result == null || direction == null || mappingFor(result) == null) {
            return false;
        }
        CompareStatus status = result.getStatus();
        if (status == CompareStatus.ERROR) {
            return false;
        }
        if (direction == MigrationDirection.SOURCE_TO_TARGET && status == CompareStatus.MISSING_IN_SOURCE) {
            return false;
        }
        if (direction == MigrationDirection.TARGET_TO_SOURCE && status == CompareStatus.MISSING_IN_TARGET) {
            return false;
        }
        IStore source = direction == MigrationDirection.SOURCE_TO_TARGET ? result.getSourceStore() : result.getTargetStore();
        IStore target = direction == MigrationDirection.SOURCE_TO_TARGET ? result.getTargetStore() : result.getSourceStore();
        return source instanceof IEntryStore && target instanceof IEntryStore && source.isConnected() && target.isConnected()
                && result.getObjectName() != null && result.getObjectName().length() > 0;
    }

    public MigrationResult migrate(CompareResult result, MigrationDirection direction, IProgressMonitor monitor) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        Mapping mapping = mappingFor(result);
        if (mapping == null) {
            return MigrationResult.failure(result, "No data-object mapping exists for " + (result == null ? "this row" : result.getObjectType()) + ".");
        }
        if (!canMigrate(result, direction)) {
            return MigrationResult.failure(result, "The row is not valid for data-object migration in the selected direction.");
        }
        IEntryStore source = (IEntryStore) (direction == MigrationDirection.SOURCE_TO_TARGET ? result.getSourceStore() : result.getTargetStore());
        IEntryStore target = (IEntryStore) (direction == MigrationDirection.SOURCE_TO_TARGET ? result.getTargetStore() : result.getSourceStore());
        String keyValue = result.getObjectName();
        try {
            safeMonitor.subTask("Reading " + mapping.objectType + " " + keyValue);
            List<Entry> sourceRows = findRows(source, mapping, keyValue, 0, mapping.maxMatches);
            if (sourceRows.isEmpty()) {
                return MigrationResult.failure(result, "No source row was found in " + mapping.formName + " where " + mapping.keyField + " = " + keyValue + ".");
            }
            if (sourceRows.size() > 1 && !mapping.allowMultipleSourceRows) {
                return MigrationResult.failure(result, "More than one source row matched " + mapping.keyField + " = " + keyValue + ". Refine the data-object mapping before migration.");
            }

            safeMonitor.subTask("Refreshing target schema for " + mapping.formName);
            warmTargetSchema(target, mapping.formName);

            safeMonitor.subTask("Reading existing target " + mapping.objectType + " rows");
            List<Entry> targetRows = findRows(target, mapping, keyValue, 0, mapping.maxMatches);
            List<String> targetIds = new ArrayList<String>();
            for (Entry targetRow : targetRows) {
                String id = entryId(targetRow);
                if (id != null && id.length() > 0) {
                    targetIds.add(id);
                }
            }

            safeMonitor.subTask("Saving target " + mapping.objectType + " rows");
            int created = 0;
            int updated = 0;
            int deleted = 0;
            int skippedFieldCount = 0;
            Set<Integer> writableTargetFields = writableTargetFieldIdsWithRetry(target, mapping.formName, 2, 350L);
            for (int i = 0; i < sourceRows.size(); i++) {
                Entry sourceRow = sourceRows.get(i);
                Entry fullSourceRow = fullEntry(source, mapping.formName, sourceRow);
                Entry clean = copyForCreate(fullSourceRow, mapping, writableTargetFields);
                skippedFieldCount += Math.max(0, fullSourceRow.size() - clean.size());
                String existingTargetId = i < targetIds.size() ? targetIds.get(i) : null;
                try {
                    boolean wasCreated = saveCleanEntry(target, mapping.formName, clean, existingTargetId);
                    if (wasCreated) {
                        created++;
                    } else {
                        updated++;
                    }
                } catch (Throwable saveError) {
                    if (!looksLikeMissingFieldError(saveError)) {
                        throw saveError;
                    }
                    // Field metadata can occasionally be stale immediately after an overlay is created.
                    // Re-read the target schema with a short retry window and then retry once, but still
                    // never send display/control fields such as panel holders. These are visible in
                    // Developer Studio but cannot be written as entry data.
                    writableTargetFields = writableTargetFieldIdsWithRetry(target, mapping.formName, 3, 650L);
                    if (writableTargetFields == null || writableTargetFields.isEmpty()) {
                        throw saveError;
                    }
                    Entry filtered = copyForCreate(fullSourceRow, mapping, writableTargetFields);
                    skippedFieldCount += Math.max(0, clean.size() - filtered.size());
                    boolean wasCreated = saveCleanEntry(target, mapping.formName, filtered, existingTargetId);
                    if (wasCreated) {
                        created++;
                    } else {
                        updated++;
                    }
                }
            }
            // Remove duplicate target rows only after the intended rows were saved successfully. This is
            // safer for Group/Role rows than delete-then-create, because computed groups may temporarily
            // fail until their referenced groups have been imported.
            for (int i = sourceRows.size(); i < targetIds.size(); i++) {
                target.deleteEntry(mapping.formName, targetIds.get(i), 0);
                deleted++;
            }
            String extra = skippedFieldCount > 0
                    ? " Skipped " + skippedFieldCount + " source field value(s) that are not writable entry-data fields in target. Developer Studio can show display/control fields from an overlay, but AR merge cannot write values to them."
                    : "";
            return MigrationResult.success(result, targetRows.isEmpty(), "Migrated data object " + mapping.objectType + " " + keyValue
                    + " through form " + mapping.formName + ". Created " + created + ", updated " + updated + ", deleted duplicate target row(s) " + deleted
                    + ". Required-field and pattern/menu checks were bypassed during AR merge." + extra);
        } catch (Throwable ex) {
            return MigrationResult.failure(result, safeMessage(ex));
        }
    }

    private List<Entry> findRows(IEntryStore store, Mapping mapping, String keyValue, int offset, int limit) throws ModelException {
        List<String> attempted = new ArrayList<String>();
        List<Entry> firstValidRows = null;
        ModelException lastUnknownFieldError = null;
        for (String keyField : mapping.keyFieldCandidates()) {
            if (keyField == null || keyField.trim().length() == 0) {
                continue;
            }
            String trimmedKeyField = keyField.trim();
            attempted.add(trimmedKeyField);
            try {
                QualifierInfo q = parseQualification(store, mapping.formName, "'" + trimmedKeyField + "' = \"" + escape(keyValue) + "\"");
                OutputInteger total = new OutputInteger();
                List<Entry> rows = store.getListEntryObjects(mapping.formName, q, offset,
                        limit <= 0 ? DEFAULT_MAX_MATCHES : limit, null, null, false, total);
                List<Entry> safeRows = rows == null ? new ArrayList<Entry>() : rows;
                if (!safeRows.isEmpty()) {
                    return safeRows;
                }
                if (firstValidRows == null) {
                    firstValidRows = safeRows;
                }
            } catch (ModelException ex) {
                if (!looksLikeUnknownFieldError(ex)) {
                    throw ex;
                }
                lastUnknownFieldError = ex;
            }
        }
        if (firstValidRows != null) {
            return firstValidRows;
        }
        if (lastUnknownFieldError != null) {
            throw new ModelException("Could not locate a usable key field on form " + mapping.formName
                    + " for " + mapping.objectType + ". Tried: " + join(attempted) + ". Last error: "
                    + safeMessage(lastUnknownFieldError));
        }
        return new ArrayList<Entry>();
    }

    private Entry fullEntry(IEntryStore store, String formName, Entry row) {
        String id = entryId(row);
        if (id == null || id.length() == 0) {
            return row;
        }
        try {
            // getListEntryObjects can return only the form's get-list fields on some Developer Studio/API
            // versions. Read the full row before create so required fields such as Group: Long Group
            // Name are preserved. Passing null asks AR API for all fields.
            Entry full = store.getEntry(formName, id, null);
            return full == null || full.isEmpty() ? row : full;
        } catch (Throwable ex) {
            Activator.logWarning("Could not read full data-object row from " + formName + " before migration; using list row fields.", ex);
            return row;
        }
    }

    private Entry copyForCreate(Entry source, Mapping mapping, Set<Integer> validTargetFields) {
        Entry copy = new Entry();
        if (mapping.copyFieldIds.isEmpty()) {
            copy.putAll(source);
        } else {
            for (Integer fieldId : mapping.copyFieldIds) {
                if (source.containsKey(fieldId)) {
                    copy.put(fieldId, source.get(fieldId));
                }
            }
        }
        copy.setEntryId(null);
        for (Integer fieldId : mapping.excludeFieldIds) {
            copy.remove(fieldId);
        }
        if (validTargetFields != null && !validTargetFields.isEmpty()) {
            List<Integer> remove = new ArrayList<Integer>();
            for (Integer fieldId : copy.keySet()) {
                if (!validTargetFields.contains(fieldId)) {
                    remove.add(fieldId);
                }
            }
            for (Integer fieldId : remove) {
                copy.remove(fieldId);
            }
        }
        return copy;
    }

    private boolean saveCleanEntry(IEntryStore target, String formName, Entry clean, String existingTargetId) throws ModelException {
        Entry toSave = new Entry();
        toSave.putAll(clean);
        if (existingTargetId != null && existingTargetId.length() > 0) {
            toSave.setEntryId(existingTargetId);
            target.mergeEntry(formName, toSave, BmcDataMigrator.safeDataMergeOptions(Constants.AR_MERGE_ENTRY_DUP_OVERWRITE, false));
            return false;
        }
        toSave.setEntryId(null);
        target.mergeEntry(formName, toSave, BmcDataMigrator.safeDataMergeOptions(Constants.AR_MERGE_ENTRY_GEN_NEW_ID, false));
        return true;
    }

    public boolean shouldRetryAfterDependencies(MigrationResult result) {
        if (result == null || result.isSuccess()) {
            return false;
        }
        return looksLikeDependencyError(result.getDetail());
    }

    private boolean looksLikeDependencyError(String text) {
        String msg = text == null ? "" : text.toLowerCase(Locale.ENGLISH);
        return msg.indexOf("group does not exist") >= 0
                || (msg.indexOf("does not exist on server") >= 0 && msg.indexOf("group") >= 0)
                || (msg.indexOf("computed") >= 0 && msg.indexOf("group") >= 0);
    }

    private boolean looksLikeUnknownFieldError(Throwable error) {
        String msg = safeMessage(error).toLowerCase(Locale.ENGLISH);
        return msg.indexOf("unknown field") >= 0
                || msg.indexOf("field referenced") >= 0
                || (msg.indexOf("field") >= 0 && msg.indexOf("query") >= 0 && msg.indexOf("unknown") >= 0);
    }

    private boolean looksLikeMissingFieldError(Throwable error) {
        String msg = safeMessage(error).toLowerCase(Locale.ENGLISH);
        return msg.indexOf("field") >= 0 && (msg.indexOf("does not exist") >= 0 || msg.indexOf("not exist") >= 0 || msg.indexOf("unknown") >= 0);
    }

    private void warmTargetSchema(IEntryStore store, String formName) {
        // Developer Studio can display a newly migrated overlay before AR entry APIs accept all
        // schema metadata. A cheap read of the live field list before data migration makes this path
        // deterministic in most environments and gives the server a chance to refresh internal caches.
        writableTargetFieldIdsWithRetry(store, formName, 2, 350L);
    }

    private Set<Integer> writableTargetFieldIdsWithRetry(IEntryStore store, String formName, int attempts, long sleepMillis) {
        Set<Integer> last = null;
        int tries = Math.max(1, attempts);
        for (int i = 0; i < tries; i++) {
            last = writableTargetFieldIds(store, formName);
            if (last != null && !last.isEmpty()) {
                return last;
            }
            if (i + 1 < tries && sleepMillis > 0L) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last;
    }

    private Set<Integer> writableTargetFieldIds(IEntryStore store, String formName) {
        if (!(store instanceof ARServerStore)) {
            return null;
        }
        try {
            List<Field> fields = ((ARServerStore) store).getContext().getListFieldObjects(formName);
            Set<Integer> writable = new LinkedHashSet<Integer>();
            if (fields == null) {
                return writable;
            }
            for (Field field : fields) {
                if (isWritableEntryField(field)) {
                    writable.add(Integer.valueOf(field.getFieldID()));
                }
            }
            return writable;
        } catch (Throwable ex) {
            Activator.logWarning("Could not read writable target field list for " + formName + " before data-object create.", ex);
            return null;
        }
    }

    private boolean isWritableEntryField(Field field) {
        if (field == null) {
            return false;
        }
        int id = field.getFieldID();
        if (id <= 0) {
            return false;
        }
        int fieldType = field.getFieldType();
        if (fieldType != Constants.AR_FIELD_TYPE_DATA && fieldType != Constants.AR_FIELD_TYPE_ATTACH) {
            return false;
        }
        int option = field.getFieldOption();
        if (option == Constants.AR_FIELD_OPTION_DISPLAY) {
            return false;
        }
        int dataType = field.getDataType();
        switch (dataType) {
            case Constants.AR_DATA_TYPE_CONTROL:
            case Constants.AR_DATA_TYPE_TABLE:
            case Constants.AR_DATA_TYPE_COLUMN:
            case Constants.AR_DATA_TYPE_PAGE:
            case Constants.AR_DATA_TYPE_PAGE_HOLDER:
            case Constants.AR_DATA_TYPE_ATTACH_POOL:
            case Constants.AR_DATA_TYPE_VIEW:
            case Constants.AR_DATA_TYPE_DISPLAY:
            case Constants.AR_DATA_TYPE_TRIM:
                return false;
            default:
                return true;
        }
    }

    private QualifierInfo parseQualification(IStore store, String formName, String qualification) throws ModelException {
        try {
            if (store instanceof ARServerStore) {
                return ((ARServerStore) store).getContext().parseQualification(formName, qualification);
            }
        } catch (Throwable ex) {
            throw new ModelException("Could not parse data-object qualification: " + safeMessage(ex));
        }
        try {
            return store.decodeQualification(qualification);
        } catch (Throwable ex) {
            throw new ModelException("Could not parse data-object qualification: " + qualification + ". " + safeMessage(ex));
        }
    }

    private Mapping mappingFor(CompareResult result) {
        if (result == null) {
            return null;
        }
        String key = normalize(result.getObjectType());
        return mappings.get(key);
    }

    private static Map<String, Mapping> loadMappings() {
        Map<String, Mapping> map = new LinkedHashMap<String, Mapping>();
        // Conservative built-ins. Installations can disable/override these in yrell-migrator.properties.
        put(map, new Mapping("Group", "Group", "Group Name", "Name"));
        put(map, new Mapping("GroupType", "Group", "Group Name", "Name"));
        put(map, new Mapping("Role", "Roles", "Role Name", "Name"));
        put(map, new Mapping("RoleType", "Roles", "Role Name", "Name"));
        put(map, new Mapping("Message", "AR System Message Catalog", "Message Number"));
        put(map, new Mapping("Report", "Report", "Report Name", "Name", "Report"));
        put(map, new Mapping("Template", "AR System Email Templates", "Template Name", "Name", "Template"));
        loadExternalMappings(map);
        return map;
    }

    private static void put(Map<String, Mapping> map, Mapping mapping) {
        if (mapping != null && mapping.enabled) {
            map.put(normalize(mapping.objectType), mapping);
        }
    }

    private static void loadExternalMappings(Map<String, Mapping> map) {
        File file = findConfigFile();
        if (file == null || !file.isFile()) {
            return;
        }
        Properties props = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
            String types = props.getProperty(PREFIX + "types", "");
            for (String type : split(types)) {
                String base = PREFIX + normalize(type) + ".";
                boolean enabled = booleanProp(props, base + "enabled", true);
                String form = props.getProperty(base + "form", "").trim();
                String key = props.getProperty(base + "keyField", "").trim();
                if (!enabled) {
                    map.remove(normalize(type));
                    continue;
                }
                if (form.length() == 0 || key.length() == 0) {
                    continue;
                }
                Mapping mapping = new Mapping(type, form, key);
                mapping.maxMatches = intProp(props, base + "maxMatches", DEFAULT_MAX_MATCHES);
                mapping.allowMultipleSourceRows = booleanProp(props, base + "allowMultipleSourceRows", false);
                mapping.excludeFieldIds = parseIntegerList(props.getProperty(base + "excludeFieldIds", Mapping.DEFAULT_EXCLUDES));
                mapping.copyFieldIds = parseIntegerList(props.getProperty(base + "copyFieldIds", ""));
                map.put(normalize(type), mapping);
            }
        } catch (IOException ex) {
            Activator.logWarning("Could not read data-object mappings from Yrell Migrator config.", ex);
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static File findConfigFile() {
        String explicit = System.getProperty(CompareSettings.SYSTEM_PROPERTY);
        if (explicit != null && explicit.trim().length() > 0) {
            return new File(explicit.trim());
        }
        String env = System.getenv(CompareSettings.ENV_PROPERTY);
        if (env != null && env.trim().length() > 0) {
            return new File(env.trim());
        }
        String installArea = System.getProperty("osgi.install.area");
        if (installArea == null || installArea.trim().length() == 0) {
            installArea = System.getProperty("eclipse.home.location");
        }
        if (installArea != null && installArea.trim().length() > 0) {
            try {
                URI uri = URI.create(installArea.trim());
                File base = "file".equalsIgnoreCase(uri.getScheme()) ? new File(uri) : new File(installArea.trim());
                File candidate = new File(base, CompareSettings.CONFIG_FILE_NAME);
                if (candidate.isFile()) {
                    return candidate;
                }
            } catch (Throwable ignored) {
            }
        }
        String home = System.getProperty("user.home");
        if (home != null && home.trim().length() > 0) {
            File candidate = new File(new File(home, ".yrell-migrator"), CompareSettings.CONFIG_FILE_NAME);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        File working = new File(CompareSettings.CONFIG_FILE_NAME);
        return working.isFile() ? working : null;
    }

    private static List<Integer> parseIntegerList(String text) {
        List<Integer> result = new ArrayList<Integer>();
        for (String part : split(text)) {
            try {
                result.add(Integer.valueOf(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static List<String> split(String text) {
        if (text == null || text.trim().length() == 0) {
            return new ArrayList<String>();
        }
        return Arrays.asList(text.split("[,;]"));
    }

    private static boolean booleanProp(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim()) || "yes".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    private static int intProp(Properties props, String key, int defaultValue) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(defaultValue)).trim());
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.length() == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
        }
        return out.toString();
    }

    private static String entryId(Entry entry) {
        if (entry == null) {
            return "";
        }
        String id = entry.getEntryId();
        return id == null || id.length() == 0 ? entry.getKey() : id;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ENGLISH).replace(" ", "");
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }

    private static final class Mapping {
        static final String DEFAULT_EXCLUDES = "1,3,5,6,15";
        final String objectType;
        final String formName;
        final String keyField;
        final List<String> keyFieldAliases = new ArrayList<String>();
        boolean enabled = true;
        int maxMatches = DEFAULT_MAX_MATCHES;
        boolean allowMultipleSourceRows;
        List<Integer> excludeFieldIds = parseIntegerList(DEFAULT_EXCLUDES);
        /** Optional allow-list. Empty means copy all fields except excludeFieldIds. */
        List<Integer> copyFieldIds = new ArrayList<Integer>();

        Mapping(String objectType, String formName, String keyField, String... aliases) {
            this.objectType = objectType == null ? "" : objectType.trim();
            this.formName = formName == null ? "" : formName.trim();
            this.keyField = keyField == null ? "" : keyField.trim();
            addKeyFieldAlias(this.keyField);
            if (aliases != null) {
                for (int i = 0; i < aliases.length; i++) {
                    addKeyFieldAlias(aliases[i]);
                }
            }
        }

        List<String> keyFieldCandidates() {
            return keyFieldAliases.isEmpty() ? Arrays.asList(new String[] { keyField }) : keyFieldAliases;
        }

        private void addKeyFieldAlias(String alias) {
            String value = alias == null ? "" : alias.trim();
            if (value.length() == 0) {
                return;
            }
            for (String existing : keyFieldAliases) {
                if (existing.equalsIgnoreCase(value)) {
                    return;
                }
            }
            keyFieldAliases.add(value);
        }
    }
}
