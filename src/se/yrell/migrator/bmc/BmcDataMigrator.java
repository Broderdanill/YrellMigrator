package se.yrell.migrator.bmc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.api.Constants;
import com.bmc.arsys.api.Entry;
import com.bmc.arsys.api.Field;
import com.bmc.arsys.api.OutputInteger;
import com.bmc.arsys.api.QualifierInfo;
import com.bmc.arsys.studio.model.ModelException;
import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.store.ARServerStore;
import com.bmc.arsys.studio.model.store.IEntryStore;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.Activator;

/** Migrates form data entries through the AR System entry API exposed by Developer Studio. */
public final class BmcDataMigrator {
    public static final int DEFAULT_PAGE_SIZE = 100;

    public enum ConflictMode {
        PRESERVE_ID_ERROR("Preserve Request ID, fail on conflict", Constants.AR_MERGE_ENTRY_DUP_ERROR),
        PRESERVE_ID_OVERWRITE("Preserve Request ID, replace existing target row", Constants.AR_MERGE_ENTRY_DUP_OVERWRITE),
        PRESERVE_ID_MERGE("Preserve Request ID, merge into existing target row", Constants.AR_MERGE_ENTRY_DUP_MERGE),
        NEW_ID_ON_CONFLICT("Preserve Request ID, create new row on conflict", Constants.AR_MERGE_ENTRY_DUP_NEW_ID),
        ALWAYS_NEW_ID("Always create new target rows", Constants.AR_MERGE_ENTRY_GEN_NEW_ID);

        private final String label;
        private final int mergeOption;

        ConflictMode(String label, int mergeOption) {
            this.label = label;
            this.mergeOption = mergeOption;
        }

        public String getLabel() {
            return label;
        }

        public int getMergeOption() {
            return mergeOption;
        }
    }

    public enum AttachmentPolicy {
        SKIP_ATTACHMENTS("Skip attachment fields during data migration"),
        INCLUDE_ATTACHMENTS("Include attachment fields if the AR API exposes them");

        private final String label;

        AttachmentPolicy(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum EntryKeyStrategy {
        REQUEST_ID("Request ID / Entry ID");

        private final String label;

        EntryKeyStrategy(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class Options {
        private IStore sourceStore;
        private IStore targetStore;
        private String formName;
        private String qualification;
        private int maxRows;
        private ConflictMode conflictMode = ConflictMode.PRESERVE_ID_OVERWRITE;
        private AttachmentPolicy attachmentPolicy = AttachmentPolicy.SKIP_ATTACHMENTS;
        private EntryKeyStrategy entryKeyStrategy = EntryKeyStrategy.REQUEST_ID;
        private boolean filterToTargetWritableFields = true;
        private boolean runWorkflow;
        private boolean dryRun;
        private int pageSize = DEFAULT_PAGE_SIZE;

        public IStore getSourceStore() { return sourceStore; }
        public void setSourceStore(IStore sourceStore) { this.sourceStore = sourceStore; }
        public IStore getTargetStore() { return targetStore; }
        public void setTargetStore(IStore targetStore) { this.targetStore = targetStore; }
        public String getFormName() { return formName; }
        public void setFormName(String formName) { this.formName = formName; }
        public String getQualification() { return qualification; }
        public void setQualification(String qualification) { this.qualification = qualification; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
        public ConflictMode getConflictMode() { return conflictMode; }
        public void setConflictMode(ConflictMode conflictMode) { this.conflictMode = conflictMode == null ? ConflictMode.PRESERVE_ID_OVERWRITE : conflictMode; }
        public AttachmentPolicy getAttachmentPolicy() { return attachmentPolicy; }
        public void setAttachmentPolicy(AttachmentPolicy attachmentPolicy) { this.attachmentPolicy = attachmentPolicy == null ? AttachmentPolicy.SKIP_ATTACHMENTS : attachmentPolicy; }
        public EntryKeyStrategy getEntryKeyStrategy() { return entryKeyStrategy; }
        public void setEntryKeyStrategy(EntryKeyStrategy entryKeyStrategy) { this.entryKeyStrategy = entryKeyStrategy == null ? EntryKeyStrategy.REQUEST_ID : entryKeyStrategy; }
        public boolean isFilterToTargetWritableFields() { return filterToTargetWritableFields; }
        public void setFilterToTargetWritableFields(boolean filterToTargetWritableFields) { this.filterToTargetWritableFields = filterToTargetWritableFields; }
        public boolean isRunWorkflow() { return runWorkflow; }
        public void setRunWorkflow(boolean runWorkflow) { this.runWorkflow = runWorkflow; }
        public boolean isDryRun() { return dryRun; }
        public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
        public int getPageSize() { return pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    }

    public static final class Result {
        private int read;
        private int migrated;
        private int failed;
        private int skippedFieldValues;
        private int skippedAttachmentFieldValues;
        private int fieldValuesSent;
        private String fieldPolicySummary = "";
        private StringBuilder failures = new StringBuilder();

        public int getRead() { return read; }
        public int getMigrated() { return migrated; }
        public int getFailed() { return failed; }
        public int getSkippedFieldValues() { return skippedFieldValues; }
        public int getSkippedAttachmentFieldValues() { return skippedAttachmentFieldValues; }
        public int getFieldValuesSent() { return fieldValuesSent; }
        public String getFieldPolicySummary() { return fieldPolicySummary; }
        public String getFailures() { return failures.toString(); }
        private void addFailure(String text) {
            failed++;
            if (failures.length() < 2000) {
                if (failures.length() > 0) failures.append('\n');
                failures.append(text);
            }
        }
    }

    public static final class Preview {
        private String formName = "";
        private int sourceRows = -1;
        private int plannedRows;
        private boolean countKnown;
        private boolean fieldMetadataKnown;
        private int sampledFieldValues;
        private int includedFieldValues;
        private int skippedFieldValues;
        private int skippedAttachmentFieldValues;
        private int targetOnlyMissingOrNonWritableFieldValues;
        private String keyStrategyLabel = EntryKeyStrategy.REQUEST_ID.getLabel();
        private String attachmentPolicyLabel = AttachmentPolicy.SKIP_ATTACHMENTS.getLabel();
        private String fieldPolicySummary = "";
        private List<String> sampleEntryIds = new ArrayList<String>();
        private StringBuilder warnings = new StringBuilder();

        public String getFormName() { return formName; }
        public int getSourceRows() { return sourceRows; }
        public int getPlannedRows() { return plannedRows; }
        public boolean isCountKnown() { return countKnown; }
        public boolean isFieldMetadataKnown() { return fieldMetadataKnown; }
        public int getSampledFieldValues() { return sampledFieldValues; }
        public int getIncludedFieldValues() { return includedFieldValues; }
        public int getSkippedFieldValues() { return skippedFieldValues; }
        public int getSkippedAttachmentFieldValues() { return skippedAttachmentFieldValues; }
        public int getTargetOnlyMissingOrNonWritableFieldValues() { return targetOnlyMissingOrNonWritableFieldValues; }
        public String getKeyStrategyLabel() { return keyStrategyLabel; }
        public String getAttachmentPolicyLabel() { return attachmentPolicyLabel; }
        public String getFieldPolicySummary() { return fieldPolicySummary; }
        public List<String> getSampleEntryIds() { return sampleEntryIds; }
        public String getWarnings() { return warnings.toString(); }
        public boolean hasWarnings() { return warnings.length() > 0; }

        private void addWarning(String warning) {
            if (warning == null || warning.length() == 0) {
                return;
            }
            if (warnings.length() > 0) {
                warnings.append('\n');
            }
            warnings.append(warning);
        }
    }

    public boolean isFormType(IModelType type) {
        if (type == null) {
            return false;
        }
        return isFormType(type.getTypeName(), type.getClass().getName());
    }

    public boolean isFormItem(IModelItem item) {
        return item != null && isFormType(item.getItemType());
    }

    public boolean isFormType(String typeName, String className) {
        String combined = ((typeName == null ? "" : typeName) + " " + (className == null ? "" : className)).toLowerCase(Locale.ENGLISH).replace(" ", "");
        return combined.equals("form") || combined.contains("formtype") || combined.endsWith("form");
    }

    public Preview preview(Options options, IProgressMonitor monitor) throws ModelException {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        validate(options);
        IEntryStore source = (IEntryStore) options.getSourceStore();
        String form = options.getFormName();
        QualifierInfo qualifier = parseQualification(options.getSourceStore(), form, options.getQualification());
        int maxRows = Math.max(0, options.getMaxRows());
        int sampleSize = Math.min(5, Math.max(1, options.getPageSize()));

        Preview preview = new Preview();
        preview.formName = form;
        safeMonitor.subTask("Previewing form data migration for " + form);
        OutputInteger total = new OutputInteger();
        List<Entry> entries = source.getListEntryObjects(form, qualifier, 0, sampleSize, null, null, false, total);
        int totalRows = outputIntegerValue(total);
        if (totalRows >= 0) {
            preview.countKnown = true;
            preview.sourceRows = totalRows;
        } else {
            preview.countKnown = false;
            preview.sourceRows = entries == null ? 0 : entries.size();
            preview.addWarning("The AR API did not return a reliable total row count; the preview is based on the first page only.");
        }
        if (maxRows == 0) {
            preview.plannedRows = preview.sourceRows;
        } else if (preview.countKnown) {
            preview.plannedRows = Math.min(preview.sourceRows, maxRows);
        } else {
            preview.plannedRows = Math.min(maxRows, preview.sourceRows);
        }
        if (entries != null) {
            for (Entry entry : entries) {
                preview.sampleEntryIds.add(safeEntryId(entry));
            }
        }
        FieldPolicy fieldPolicy = buildFieldPolicy(options, form, safeMonitor);
        populatePreviewFieldInfo(preview, entries, options, fieldPolicy);
        boolean blankQualification = options.getQualification() == null || options.getQualification().trim().length() == 0;
        if (maxRows == 0) {
            preview.addWarning("Max rows is blank/0, so all matching source rows are in scope.");
        }
        if (!options.isDryRun() && blankQualification && maxRows == 0) {
            preview.addWarning("Write mode has no qualification and no row limit. This may touch every source row that the AR API returns.");
        }
        if (!options.isDryRun() && blankQualification && maxRows > 0) {
            preview.addWarning("Write mode has no qualification. Only the row limit limits the scope.");
        }
        if (preview.countKnown && preview.plannedRows == 0) {
            preview.addWarning("No source rows matched the current qualification/row limit.");
        }
        if (!options.isDryRun() && options.getConflictMode() == ConflictMode.PRESERVE_ID_OVERWRITE) {
            preview.addWarning("Existing target rows with the same Request ID will be replaced.");
        }
        if (!options.isDryRun() && options.getConflictMode() == ConflictMode.ALWAYS_NEW_ID) {
            preview.addWarning("New Request IDs will be generated, which can create duplicate business data.");
        }
        if (!options.isDryRun() && options.isRunWorkflow()) {
            preview.addWarning("Workflow will run during merge; filters/escalations/active links may modify data or trigger integrations.");
        }
        if (!preview.isFieldMetadataKnown() && options.isFilterToTargetWritableFields()) {
            preview.addWarning("Target field metadata could not be read. Field filtering will fall back to sending the entry values returned by the AR API.");
        }
        if (preview.getTargetOnlyMissingOrNonWritableFieldValues() > 0) {
            preview.addWarning(preview.getTargetOnlyMissingOrNonWritableFieldValues() + " sampled field value(s) are missing or not writable in target and will be skipped in write mode.");
        }
        if (preview.getSkippedAttachmentFieldValues() > 0) {
            preview.addWarning(preview.getSkippedAttachmentFieldValues() + " sampled attachment field value(s) will be skipped by the selected attachment policy.");
        }
        if (!options.isDryRun() && preview.plannedRows > 1000) {
            preview.addWarning("More than 1000 entries are planned for write. Consider a dry run or a narrower qualification first.");
        }
        return preview;
    }

    public Result migrate(Options options, IProgressMonitor monitor) throws ModelException {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        validate(options);
        IEntryStore source = (IEntryStore) options.getSourceStore();
        IEntryStore target = (IEntryStore) options.getTargetStore();
        String form = options.getFormName();
        int maxRows = Math.max(0, options.getMaxRows());
        int pageSize = Math.max(1, options.getPageSize());
        QualifierInfo qualifier = parseQualification(options.getSourceStore(), form, options.getQualification());
        int mergeOption = safeDataMergeOptions(options.getConflictMode().getMergeOption(), options.isRunWorkflow());
        FieldPolicy fieldPolicy = buildFieldPolicy(options, form, safeMonitor);

        Result result = new Result();
        result.fieldPolicySummary = fieldPolicy.summary(options);
        int offset = 0;
        int remaining = maxRows == 0 ? Integer.MAX_VALUE : maxRows;
        safeMonitor.beginTask((options.isDryRun() ? "Dry-run data migration for " : "Migrating data for ") + form, maxRows == 0 ? IProgressMonitor.UNKNOWN : maxRows);
        while (!safeMonitor.isCanceled() && remaining > 0) {
            int batchSize = Math.min(pageSize, remaining);
            safeMonitor.subTask("Reading " + form + " entries " + (offset + 1) + "-" + (offset + batchSize));
            OutputInteger total = new OutputInteger();
            List<Entry> entries = source.getListEntryObjects(form, qualifier, offset, batchSize, null, null, false, total);
            if (entries == null || entries.isEmpty()) {
                break;
            }
            for (Entry entry : entries) {
                if (safeMonitor.isCanceled() || remaining <= 0) {
                    break;
                }
                result.read++;
                String entryId = safeEntryId(entry);
                safeMonitor.subTask((options.isDryRun() ? "Dry-run " : "Migrating ") + form + " " + entryId);
                try {
                    if (options.isDryRun()) {
                        // Dry-run intentionally reads the source entries only. It does not call mergeEntry
                        // and therefore does not need to load all entries into memory or touch the target.
                        result.migrated++;
                    } else {
                        Entry outbound = prepareEntryForTarget(entry, fieldPolicy, options, result);
                        target.mergeEntry(form, outbound, mergeOption);
                        result.migrated++;
                    }
                } catch (Throwable ex) {
                    result.addFailure(entryId + ": " + safeMessage(ex));
                }
                remaining--;
                safeMonitor.worked(1);
            }
            offset += entries.size();
            if (entries.size() < batchSize) {
                break;
            }
            // Encourage the VM to release large entry batches before the next page.
            entries.clear();
        }
        safeMonitor.done();
        return result;
    }

    private FieldPolicy buildFieldPolicy(Options options, String form, IProgressMonitor monitor) {
        FieldPolicy policy = new FieldPolicy();
        policy.filterToTargetWritableFields = options == null || options.isFilterToTargetWritableFields();
        policy.attachmentPolicy = options == null ? AttachmentPolicy.SKIP_ATTACHMENTS : options.getAttachmentPolicy();
        if (options == null || !(options.getTargetStore() instanceof ARServerStore)) {
            policy.metadataKnown = false;
            policy.metadataMessage = "Target field metadata is not available for this store type.";
            return policy;
        }
        try {
            if (monitor != null) {
                monitor.subTask("Reading target field metadata for " + form);
            }
            List<Field> fields = ((ARServerStore) options.getTargetStore()).getContext().getListFieldObjects(form);
            policy.metadataKnown = true;
            if (fields != null) {
                for (Field field : fields) {
                    if (field == null || field.getFieldID() <= 0) {
                        continue;
                    }
                    int id = field.getFieldID();
                    if (isAttachmentField(field)) {
                        policy.attachmentFieldIds.add(Integer.valueOf(id));
                    }
                    if (isEntryDataField(field)) {
                        policy.targetEntryFieldIds.add(Integer.valueOf(id));
                    }
                    if (isWritableEntryField(field, options.getAttachmentPolicy() == AttachmentPolicy.INCLUDE_ATTACHMENTS)) {
                        policy.writableTargetFieldIds.add(Integer.valueOf(id));
                    }
                }
            }
        } catch (Throwable ex) {
            policy.metadataKnown = false;
            policy.metadataMessage = "Could not read target field metadata: " + safeMessage(ex);
            Activator.logWarning("Could not read target field metadata for data migration form " + form + ".", ex);
        }
        return policy;
    }

    private void populatePreviewFieldInfo(Preview preview, List<Entry> entries, Options options, FieldPolicy policy) {
        if (preview == null) {
            return;
        }
        preview.keyStrategyLabel = options == null ? EntryKeyStrategy.REQUEST_ID.getLabel() : options.getEntryKeyStrategy().getLabel();
        preview.attachmentPolicyLabel = options == null ? AttachmentPolicy.SKIP_ATTACHMENTS.getLabel() : options.getAttachmentPolicy().getLabel();
        preview.fieldMetadataKnown = policy != null && policy.metadataKnown;
        preview.fieldPolicySummary = policy == null ? "No field policy was built." : policy.summary(options);
        if (entries == null) {
            return;
        }
        for (Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            for (Object raw : entry.entrySet()) {
                if (!(raw instanceof Map.Entry)) {
                    continue;
                }
                Map.Entry pair = (Map.Entry) raw;
                if (!(pair.getKey() instanceof Integer)) {
                    continue;
                }
                Integer fieldId = (Integer) pair.getKey();
                preview.sampledFieldValues++;
                FieldDecision decision = decideField(fieldId, policy, options);
                if (decision == FieldDecision.INCLUDE) {
                    preview.includedFieldValues++;
                } else if (decision == FieldDecision.SKIP_ATTACHMENT) {
                    preview.skippedFieldValues++;
                    preview.skippedAttachmentFieldValues++;
                } else {
                    preview.skippedFieldValues++;
                    preview.targetOnlyMissingOrNonWritableFieldValues++;
                }
            }
        }
    }

    private Entry prepareEntryForTarget(Entry entry, FieldPolicy policy, Options options, Result result) {
        if (entry == null) {
            return null;
        }
        if (policy == null || (!policy.metadataKnown && options.getAttachmentPolicy() == AttachmentPolicy.INCLUDE_ATTACHMENTS)) {
            if (result != null) {
                result.fieldValuesSent += entry.size();
            }
            return entry;
        }
        Entry filtered = new Entry();
        filtered.setEntryId(entry.getEntryId());
        for (Object raw : entry.entrySet()) {
            if (!(raw instanceof Map.Entry)) {
                continue;
            }
            Map.Entry pair = (Map.Entry) raw;
            Object key = pair.getKey();
            if (!(key instanceof Integer)) {
                continue;
            }
            Integer fieldId = (Integer) key;
            FieldDecision decision = decideField(fieldId, policy, options);
            if (decision == FieldDecision.INCLUDE) {
                filtered.put(fieldId, (com.bmc.arsys.api.Value) pair.getValue());
                if (result != null) {
                    result.fieldValuesSent++;
                }
            } else {
                if (result != null) {
                    result.skippedFieldValues++;
                    if (decision == FieldDecision.SKIP_ATTACHMENT) {
                        result.skippedAttachmentFieldValues++;
                    }
                }
            }
        }
        return filtered;
    }

    /**
     * AR merge options used by data migration.
     *
     * Data migration should copy already-existing rows without letting target-side field
     * validation rewrite or reject otherwise valid historical data. In AR System, menu
     * validation is part of the pattern validation path, so AR_MERGE_NO_PATTERNS_INCREMENT
     * also avoids failures caused by Pattern/Menu Match on character fields.
     */
    public static int safeDataMergeOptions(int baseMergeOption, boolean runWorkflow) {
        int option = baseMergeOption
                | Constants.AR_MERGE_NO_REQUIRED_INCREMENT
                | Constants.AR_MERGE_NO_PATTERNS_INCREMENT;
        if (!runWorkflow) {
            option = option | Constants.AR_MERGE_NO_WORKFLOW_FIRED | Constants.AR_MERGE_NO_ASSOCIATION_FIRED;
        }
        return option;
    }

    private FieldDecision decideField(Integer fieldId, FieldPolicy policy, Options options) {
        if (fieldId == null) {
            return FieldDecision.SKIP_NON_WRITABLE;
        }
        if (policy != null && options != null && options.getAttachmentPolicy() == AttachmentPolicy.SKIP_ATTACHMENTS
                && policy.attachmentFieldIds.contains(fieldId)) {
            return FieldDecision.SKIP_ATTACHMENT;
        }
        if (policy != null && policy.metadataKnown && options != null && options.isFilterToTargetWritableFields()
                && !policy.writableTargetFieldIds.contains(fieldId)) {
            return FieldDecision.SKIP_NON_WRITABLE;
        }
        return FieldDecision.INCLUDE;
    }

    private boolean isWritableEntryField(Field field, boolean includeAttachments) {
        if (!isEntryDataField(field)) {
            return false;
        }
        if (!includeAttachments && isAttachmentField(field)) {
            return false;
        }
        return true;
    }

    private boolean isEntryDataField(Field field) {
        if (field == null || field.getFieldID() <= 0) {
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

    private boolean isAttachmentField(Field field) {
        return field != null && field.getFieldType() == Constants.AR_FIELD_TYPE_ATTACH;
    }

    private static enum FieldDecision {
        INCLUDE,
        SKIP_ATTACHMENT,
        SKIP_NON_WRITABLE
    }

    private static final class FieldPolicy {
        boolean metadataKnown;
        boolean filterToTargetWritableFields;
        AttachmentPolicy attachmentPolicy = AttachmentPolicy.SKIP_ATTACHMENTS;
        String metadataMessage = "";
        Set<Integer> targetEntryFieldIds = new LinkedHashSet<Integer>();
        Set<Integer> writableTargetFieldIds = new LinkedHashSet<Integer>();
        Set<Integer> attachmentFieldIds = new LinkedHashSet<Integer>();

        String summary(Options options) {
            StringBuilder text = new StringBuilder();
            text.append("Key strategy: ").append(options == null ? EntryKeyStrategy.REQUEST_ID.getLabel() : options.getEntryKeyStrategy().getLabel());
            text.append("; attachment policy: ").append(options == null ? AttachmentPolicy.SKIP_ATTACHMENTS.getLabel() : options.getAttachmentPolicy().getLabel());
            text.append("; target field filtering: ").append(options == null || options.isFilterToTargetWritableFields() ? "enabled" : "disabled");
            text.append("; validation bypass: required + pattern/menu checks");
            if (!metadataKnown) {
                if (metadataMessage != null && metadataMessage.length() > 0) {
                    text.append("; metadata: ").append(metadataMessage);
                } else {
                    text.append("; metadata: unavailable");
                }
            } else {
                text.append("; target entry fields: ").append(targetEntryFieldIds.size());
                text.append("; writable target fields: ").append(writableTargetFieldIds.size());
                text.append("; attachment fields: ").append(attachmentFieldIds.size());
            }
            return text.toString();
        }
    }

    private void validate(Options options) throws ModelException {
        if (options == null) {
            throw new ModelException("No data migration options were supplied.");
        }
        if (!(options.getSourceStore() instanceof IEntryStore) || !options.getSourceStore().isConnected()) {
            throw new ModelException("Source environment does not support form entry access or is not connected.");
        }
        if (!(options.getTargetStore() instanceof IEntryStore) || !options.getTargetStore().isConnected()) {
            throw new ModelException("Target environment does not support form entry access or is not connected.");
        }
        if (options.getFormName() == null || options.getFormName().trim().length() == 0) {
            throw new ModelException("No form name was supplied.");
        }
    }

    private QualifierInfo parseQualification(IStore store, String formName, String qualification) throws ModelException {
        if (qualification == null || qualification.trim().length() == 0) {
            return null;
        }
        try {
            if (store instanceof ARServerStore) {
                return ((ARServerStore) store).getContext().parseQualification(formName, qualification);
            }
        } catch (Throwable ex) {
            throw new ModelException("Could not parse qualification: " + safeMessage(ex));
        }
        try {
            return store.decodeQualification(qualification);
        } catch (Throwable ex) {
            throw new ModelException("Could not parse qualification. Use AR qualification syntax, for example 'Status' = \"Enabled\". " + safeMessage(ex));
        }
    }

    private int outputIntegerValue(OutputInteger total) {
        if (total == null) {
            return -1;
        }
        String[] methods = new String[] { "intValue", "getValue", "getIntValue" };
        for (int i = 0; i < methods.length; i++) {
            try {
                java.lang.reflect.Method method = total.getClass().getMethod(methods[i], new Class[0]);
                Object value = method.invoke(total, new Object[0]);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        String[] fields = new String[] { "value", "intValue" };
        for (int i = 0; i < fields.length; i++) {
            try {
                java.lang.reflect.Field field = total.getClass().getField(fields[i]);
                Object value = field.get(total);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            String text = String.valueOf(total).trim();
            return Integer.parseInt(text);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String safeEntryId(Entry entry) {
        if (entry == null) {
            return "<null>";
        }
        String id = entry.getEntryId();
        return id == null || id.length() == 0 ? entry.getKey() : id;
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }
}
