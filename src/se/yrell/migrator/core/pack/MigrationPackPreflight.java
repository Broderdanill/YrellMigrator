package se.yrell.migrator.core.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bmc.arsys.api.Constants;
import com.bmc.arsys.api.Field;
import com.bmc.arsys.studio.model.store.ARServerStore;
import com.bmc.arsys.studio.model.store.IEntryStore;
import com.bmc.arsys.studio.model.store.IStore;

import se.yrell.migrator.bmc.BmcDataMigrator;

/** Lightweight validation before a Migration Pack is run against connected Developer Studio environments. */
public final class MigrationPackPreflight {
    private final MigrationPack pack;
    private final List<IStore> stores;
    private int errors;
    private int warnings;
    private int embeddedDefinitions;
    private int embeddedDataItems;
    private int embeddedRows;
    private final List<String> lines = new ArrayList<String>();

    public MigrationPackPreflight(MigrationPack pack, List<IStore> stores) {
        this.pack = pack;
        this.stores = stores == null ? new ArrayList<IStore>() : stores;
    }

    public Result run() {
        lines.clear();
        errors = 0;
        warnings = 0;
        embeddedDefinitions = 0;
        embeddedDataItems = 0;
        embeddedRows = 0;
        if (pack == null) {
            error("No Migration Pack is loaded.");
            return result();
        }
        lines.add("Migration Pack preflight");
        lines.add("Name: " + pack.getName());
        lines.add("Items: " + pack.size() + " (definitions " + pack.getDefinitionCount() + ", form data " + pack.getFormDataCount() + ")");
        lines.add("Connected environments: " + connectedEnvironmentSummary());
        lines.add("");
        if (pack.isEmpty()) {
            warning("The Migration Pack is empty.");
        }
        Set<String> targets = new LinkedHashSet<String>();
        Set<String> seenKeys = new LinkedHashSet<String>();
        Set<String> formDefinitions = new LinkedHashSet<String>();
        Map<String, Integer> targetCounts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> phaseCounts = new LinkedHashMap<String, Integer>();
        int index = 0;
        for (MigrationPackItem item : pack.getItems()) {
            index++;
            if (item == null) {
                error(index + ". Empty pack row.");
                continue;
            }
            if (item.getTargetServer().length() > 0) {
                targets.add(item.getTargetServer());
                Integer count = targetCounts.get(item.getTargetServer());
                targetCounts.put(item.getTargetServer(), Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            }
            String phase = item.executionPhaseLabel();
            Integer phaseCount = phaseCounts.get(phase);
            phaseCounts.put(phase, Integer.valueOf(phaseCount == null ? 1 : phaseCount.intValue() + 1));
            if (!seenKeys.add(item.stableKey())) {
                warning(index + ". Duplicate pack row: " + displayName(item) + ". Only one of these rows may be meaningful.");
            }
            if (item.isDefinition() && isFormDefinition(item)) {
                formDefinitions.add(normalize(item.getObjectName()));
            }
            validateItem(index, item);
        }
        validateFormDataDefinitions(formDefinitions);
        lines.add("");
        lines.add("Target environments in package: " + (targets.isEmpty() ? "none" : targets.toString()));
        if (!targetCounts.isEmpty()) {
            lines.add("Items by target: " + targetCounts.toString());
        }
        if (!phaseCounts.isEmpty()) {
            lines.add("Execution phases: " + phaseCounts.toString());
            appendRunOrderPreview();
        }
        lines.add("Embedded definitions: " + embeddedDefinitions);
        lines.add("Embedded data items: " + embeddedDataItems);
        lines.add("Embedded rows: " + embeddedRows);
        lines.add("Validation bypass for entry data: required + pattern/menu checks");
        lines.add("Workflow during data import: per item setting");
        lines.add("");
        lines.add(errors == 0 ? "Result: OK to run." : "Result: Fix errors or retarget the pack before running.");
        return result();
    }

    private void appendRunOrderPreview() {
        if (pack == null || pack.getItems() == null || pack.getItems().isEmpty()) {
            return;
        }
        List<MigrationPackItem> ordered = pack.orderedForRun(pack.getItems());
        lines.add("Planned run order:");
        int shown = 0;
        for (MigrationPackItem item : ordered) {
            if (item == null) continue;
            shown++;
            if (shown > 20) {
                lines.add("  ... " + (ordered.size() - 20) + " additional item(s)");
                break;
            }
            lines.add("  " + shown + ". " + item.executionPhaseLabel() + " — " + displayName(item));
        }
    }

    private void validateItem(int index, MigrationPackItem item) {
        String label = index + ". " + (item.isFormData() ? "Form data " + item.getFormName() : item.getObjectType() + " " + item.getObjectName());
        IStore target = findStore(item.getTargetServer());
        if (target == null) {
            error(label + " — target is not connected: " + item.getTargetServer());
        } else if (!target.isConnected()) {
            error(label + " — target exists but is not connected: " + item.getTargetServer());
        }
        if (item.getTargetServer().length() == 0) {
            error(label + " — no target environment is set. Use Retarget before running.");
        }
        if (!item.hasEmbeddedPayload()) {
            error(label + " — no embedded payload. Re-add/export this row with v0.80.0 or later.");
        } else if (item.isFormData() || MigrationPackPayloadService.PAYLOAD_ENTRY_DATA.equals(item.getPayloadType())
                || MigrationPackPayloadService.PAYLOAD_ENTRY_DATA_XML.equals(item.getPayloadType())) {
            embeddedDataItems++;
            embeddedRows += item.getEmbeddedRowCount();
            if (item.getEmbeddedRowCount() == 0) {
                warning(label + " — embedded data scope contains zero row(s). It will run but will not change target data.");
            }
            if (target != null && target.isConnected() && !(target instanceof IEntryStore)) {
                error(label + " — target does not support entry-data import.");
            }
            if (target != null && target.isConnected() && item.getFormName().length() > 0) {
                validateTargetForm(label, target, item.getFormName(), item.getAttachmentPolicy());
            }
        } else if (MigrationPackPayloadService.PAYLOAD_AR_DEFINITION.equals(item.getPayloadType())) {
            embeddedDefinitions++;
            if (target != null && target.isConnected() && !(target instanceof ARServerStore)) {
                error(label + " — target does not expose AR definition import APIs.");
            }
        } else {
            error(label + " — unsupported payload type: " + item.getPayloadType());
        }
    }


    private void validateFormDataDefinitions(Set<String> formDefinitions) {
        if (pack == null || pack.getItems() == null) {
            return;
        }
        for (MigrationPackItem item : pack.getItems()) {
            if (item == null || !item.isFormData()) {
                continue;
            }
            String form = normalize(item.getFormName());
            if (form.length() > 0 && !formDefinitions.contains(form)) {
                warning("Form data " + item.getFormName() + " does not include the form definition in this pack. That is OK if the form already exists in target; otherwise add the Form definition too.");
            }
        }
    }

    private boolean isFormDefinition(MigrationPackItem item) {
        String type = normalize(item.getObjectType());
        return "form".equals(type) || type.endsWith("form") || type.indexOf("form") >= 0;
    }

    private String displayName(MigrationPackItem item) {
        if (item == null) return "<empty>";
        return (item.isFormData() ? "Form data " + item.getFormName() : item.getObjectType() + " " + item.getObjectName())
                + " → " + item.getTargetServer();
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(java.util.Locale.ENGLISH);
    }

    private void validateTargetForm(String label, IStore target, String formName, BmcDataMigrator.AttachmentPolicy attachmentPolicy) {
        if (!(target instanceof ARServerStore)) {
            warning(label + " — target field metadata cannot be checked for form " + formName + ".");
            return;
        }
        try {
            List<Field> fields = ((ARServerStore) target).getContext().getListFieldObjects(formName);
            int writable = 0;
            int attachments = 0;
            int displayOrControl = 0;
            if (fields != null) {
                for (Field field : fields) {
                    if (field == null) continue;
                    if (field.getFieldType() == Constants.AR_FIELD_TYPE_ATTACH) attachments++;
                    if (field.getFieldOption() == Constants.AR_FIELD_OPTION_DISPLAY || isDisplayOnlyDataType(field.getDataType())) {
                        displayOrControl++;
                    }
                    if (isWritableEntryField(field, attachmentPolicy == BmcDataMigrator.AttachmentPolicy.INCLUDE_ATTACHMENTS)) {
                        writable++;
                    }
                }
            }
            lines.add(label + " — target form OK: " + formName + ", writable fields " + writable
                    + ", display/control fields skipped " + displayOrControl + ", attachments " + attachments + ".");
        } catch (Throwable ex) {
            error(label + " — target form/fields could not be read: " + formName + " (" + safeMessage(ex) + ")");
        }
    }

    private boolean isWritableEntryField(Field field, boolean includeAttachments) {
        if (field == null || field.getFieldID() <= 0) return false;
        int fieldType = field.getFieldType();
        if (fieldType != Constants.AR_FIELD_TYPE_DATA && fieldType != Constants.AR_FIELD_TYPE_ATTACH) return false;
        if (!includeAttachments && fieldType == Constants.AR_FIELD_TYPE_ATTACH) return false;
        if (field.getFieldOption() == Constants.AR_FIELD_OPTION_DISPLAY) return false;
        return !isDisplayOnlyDataType(field.getDataType());
    }

    private boolean isDisplayOnlyDataType(int dataType) {
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
                return true;
            default:
                return false;
        }
    }

    private IStore findStore(String name) {
        if (name == null || name.length() == 0) return null;
        for (IStore store : stores) {
            if (store != null && store.getName() != null && store.getName().equalsIgnoreCase(name)) return store;
        }
        return null;
    }

    private String connectedEnvironmentSummary() {
        List<String> names = new ArrayList<String>();
        for (IStore store : stores) {
            if (store != null && store.isConnected() && store.getName() != null) names.add(store.getName());
        }
        return names.isEmpty() ? "none" : names.toString();
    }

    private void error(String text) { errors++; lines.add("ERROR: " + text); }
    private void warning(String text) { warnings++; lines.add("WARNING: " + text); }

    private String safeMessage(Throwable ex) {
        if (ex == null) return "unknown";
        String msg = ex.getLocalizedMessage();
        return msg == null || msg.length() == 0 ? ex.getClass().getName() : msg;
    }

    private Result result() {
        StringBuilder b = new StringBuilder();
        for (String line : lines) b.append(line).append('\n');
        return new Result(errors, warnings, embeddedDefinitions, embeddedDataItems, embeddedRows, b.toString());
    }

    public static final class Result {
        private final int errors;
        private final int warnings;
        private final int embeddedDefinitions;
        private final int embeddedDataItems;
        private final int embeddedRows;
        private final String report;
        Result(int errors, int warnings, int embeddedDefinitions, int embeddedDataItems, int embeddedRows, String report) {
            this.errors = errors;
            this.warnings = warnings;
            this.embeddedDefinitions = embeddedDefinitions;
            this.embeddedDataItems = embeddedDataItems;
            this.embeddedRows = embeddedRows;
            this.report = report == null ? "" : report;
        }
        public int getErrors() { return errors; }
        public int getWarnings() { return warnings; }
        public int getEmbeddedDefinitions() { return embeddedDefinitions; }
        public int getEmbeddedDataItems() { return embeddedDataItems; }
        public int getEmbeddedRows() { return embeddedRows; }
        public boolean canRun() { return errors == 0; }
        public String getReport() { return report; }
        public String summary() {
            return (canRun() ? "OK" : "Errors") + " — errors " + errors + ", warnings " + warnings
                    + ", embedded definitions " + embeddedDefinitions + ", embedded data rows " + embeddedRows;
        }
    }
}
