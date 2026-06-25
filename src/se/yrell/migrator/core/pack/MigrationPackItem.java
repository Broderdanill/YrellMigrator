package se.yrell.migrator.core.pack;

import se.yrell.migrator.bmc.BmcDataMigrator;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.MigrationDirection;

/** One local item in a Yrell Migrator migration pack. */
public final class MigrationPackItem {
    public static final String KIND_DEFINITION = "definition";
    public static final String KIND_FORM_DATA = "formData";

    private String kind = KIND_DEFINITION;
    private MigrationDirection direction = MigrationDirection.SOURCE_TO_TARGET;
    private String objectType = "";
    private String objectName = "";
    private String formName = "";
    private String sourceServer = "";
    private String targetServer = "";
    private String contextKey = "";
    private String qualification = "";
    private int maxRows;
    private BmcDataMigrator.ConflictMode conflictMode = BmcDataMigrator.ConflictMode.PRESERVE_ID_OVERWRITE;
    private BmcDataMigrator.AttachmentPolicy attachmentPolicy = BmcDataMigrator.AttachmentPolicy.SKIP_ATTACHMENTS;
    private boolean filterToTargetWritableFields = true;
    private boolean runWorkflow;
    private boolean dryRun;
    private String payloadType = "";
    private String payloadBase64 = "";
    private int embeddedRowCount;
    private int embeddedObjectCount;
    private long capturedAtMillis;
    private String captureSummary = "";
    private long addedAtMillis = System.currentTimeMillis();
    private String lastRunOutcome = "";
    private String lastRunMessage = "";
    private long lastRunAtMillis;

    public static MigrationPackItem definition(CompareResult row, MigrationDirection direction, String contextKey) {
        MigrationPackItem item = new MigrationPackItem();
        item.kind = KIND_DEFINITION;
        item.direction = direction == null ? MigrationDirection.SOURCE_TO_TARGET : direction;
        if (row != null) {
            item.objectType = text(row.getObjectType());
            item.objectName = text(row.getObjectName());
            item.formName = text(row.getPrimaryFormSummary());
            if (item.direction == MigrationDirection.TARGET_TO_SOURCE) {
                item.sourceServer = text(row.getTargetServer());
                item.targetServer = text(row.getSourceServer());
            } else {
                item.sourceServer = text(row.getSourceServer());
                item.targetServer = text(row.getTargetServer());
            }
        }
        item.contextKey = text(contextKey);
        return item;
    }

    public static MigrationPackItem formData(BmcDataMigrator.Options options, MigrationDirection direction) {
        MigrationPackItem item = new MigrationPackItem();
        item.kind = KIND_FORM_DATA;
        item.direction = direction == null ? MigrationDirection.SOURCE_TO_TARGET : direction;
        if (options != null) {
            item.formName = text(options.getFormName());
            item.objectName = item.formName;
            item.objectType = "Form data";
            item.sourceServer = options.getSourceStore() == null ? "" : text(options.getSourceStore().getName());
            item.targetServer = options.getTargetStore() == null ? "" : text(options.getTargetStore().getName());
            item.qualification = text(options.getQualification());
            item.maxRows = options.getMaxRows();
            item.conflictMode = options.getConflictMode();
            item.attachmentPolicy = options.getAttachmentPolicy();
            item.filterToTargetWritableFields = options.isFilterToTargetWritableFields();
            item.runWorkflow = options.isRunWorkflow();
            item.dryRun = options.isDryRun();
        }
        return item;
    }


    public int executionPhase() {
        if (isFormData()) {
            return 900;
        }
        String type = normalizeToken(objectType);
        if (type.indexOf("group") >= 0 || type.indexOf("role") >= 0) {
            return 20;
        }
        if (type.indexOf("image") >= 0) {
            return 30;
        }
        if (type.indexOf("form") >= 0) {
            return 100;
        }
        if (type.indexOf("menu") >= 0) {
            return 110;
        }
        if (type.indexOf("supportfile") >= 0 || type.indexOf("support") >= 0) {
            return 115;
        }
        if (type.indexOf("message") >= 0 || type.indexOf("template") >= 0 || type.indexOf("report") >= 0) {
            return 130;
        }
        if (type.indexOf("guide") >= 0) {
            return 200;
        }
        if (type.indexOf("activelink") >= 0 || type.indexOf("filter") >= 0 || type.indexOf("escalation") >= 0) {
            return 220;
        }
        if (type.indexOf("webservice") >= 0 || type.indexOf("application") >= 0
                || type.indexOf("packinglist") >= 0 || type.indexOf("association") >= 0
                || type.indexOf("distributed") >= 0) {
            return 300;
        }
        return 500;
    }

    public String executionPhaseLabel() {
        int phase = executionPhase();
        if (phase == 20) return "01 Security data";
        if (phase == 30) return "02 Images";
        if (phase == 100) return "03 Forms";
        if (phase == 110) return "04 Menus";
        if (phase == 115) return "05 Support files";
        if (phase == 130) return "06 Catalog data";
        if (phase == 200) return "07 Guides";
        if (phase == 220) return "08 Workflow";
        if (phase == 300) return "09 Containers";
        if (phase == 900) return "10 Form data";
        return "99 Other definitions";
    }

    public String stableKey() {
        StringBuilder key = new StringBuilder();
        key.append(kind).append('|').append(direction == null ? "" : direction.name()).append('|')
                .append(sourceServer).append('|').append(targetServer).append('|')
                .append(objectType).append('|').append(objectName).append('|')
                .append(formName).append('|').append(qualification).append('|').append(maxRows);
        return key.toString().toLowerCase(java.util.Locale.ENGLISH);
    }

    public boolean isDefinition() { return KIND_DEFINITION.equals(kind); }
    public boolean isFormData() { return KIND_FORM_DATA.equals(kind); }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = text(kind).length() == 0 ? KIND_DEFINITION : text(kind); }
    public MigrationDirection getDirection() { return direction == null ? MigrationDirection.SOURCE_TO_TARGET : direction; }
    public void setDirection(MigrationDirection direction) { this.direction = direction == null ? MigrationDirection.SOURCE_TO_TARGET : direction; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = text(objectType); }
    public String getObjectName() { return objectName; }
    public void setObjectName(String objectName) { this.objectName = text(objectName); }
    public String getFormName() { return formName; }
    public void setFormName(String formName) { this.formName = text(formName); }
    public String getSourceServer() { return sourceServer; }
    public void setSourceServer(String sourceServer) { this.sourceServer = text(sourceServer); }
    public String getTargetServer() { return targetServer; }
    public void setTargetServer(String targetServer) { this.targetServer = text(targetServer); }
    public String getContextKey() { return contextKey; }
    public void setContextKey(String contextKey) { this.contextKey = text(contextKey); }
    public String getQualification() { return qualification; }
    public void setQualification(String qualification) { this.qualification = text(qualification); }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = Math.max(0, maxRows); }
    public BmcDataMigrator.ConflictMode getConflictMode() { return conflictMode == null ? BmcDataMigrator.ConflictMode.PRESERVE_ID_OVERWRITE : conflictMode; }
    public void setConflictMode(BmcDataMigrator.ConflictMode conflictMode) { this.conflictMode = conflictMode == null ? BmcDataMigrator.ConflictMode.PRESERVE_ID_OVERWRITE : conflictMode; }
    public BmcDataMigrator.AttachmentPolicy getAttachmentPolicy() { return attachmentPolicy == null ? BmcDataMigrator.AttachmentPolicy.SKIP_ATTACHMENTS : attachmentPolicy; }
    public void setAttachmentPolicy(BmcDataMigrator.AttachmentPolicy attachmentPolicy) { this.attachmentPolicy = attachmentPolicy == null ? BmcDataMigrator.AttachmentPolicy.SKIP_ATTACHMENTS : attachmentPolicy; }
    public boolean isFilterToTargetWritableFields() { return filterToTargetWritableFields; }
    public void setFilterToTargetWritableFields(boolean filterToTargetWritableFields) { this.filterToTargetWritableFields = filterToTargetWritableFields; }
    public boolean isRunWorkflow() { return runWorkflow; }
    public void setRunWorkflow(boolean runWorkflow) { this.runWorkflow = runWorkflow; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public String getPayloadType() { return payloadType; }
    public void setPayloadType(String payloadType) { this.payloadType = text(payloadType); }
    public String getPayloadBase64() { return payloadBase64; }
    public void setPayloadBase64(String payloadBase64) { this.payloadBase64 = text(payloadBase64); }
    public int getEmbeddedRowCount() { return embeddedRowCount; }
    public void setEmbeddedRowCount(int embeddedRowCount) { this.embeddedRowCount = Math.max(0, embeddedRowCount); }
    public int getEmbeddedObjectCount() { return embeddedObjectCount; }
    public void setEmbeddedObjectCount(int embeddedObjectCount) { this.embeddedObjectCount = Math.max(0, embeddedObjectCount); }
    public long getCapturedAtMillis() { return capturedAtMillis; }
    public void setCapturedAtMillis(long capturedAtMillis) { this.capturedAtMillis = Math.max(0L, capturedAtMillis); }
    public String getCaptureSummary() { return captureSummary; }
    public void setCaptureSummary(String captureSummary) { this.captureSummary = text(captureSummary); }
    public boolean hasEmbeddedPayload() { return payloadType.length() > 0 && payloadBase64.length() > 0; }
    public long getAddedAtMillis() { return addedAtMillis; }
    public void setAddedAtMillis(long addedAtMillis) { this.addedAtMillis = addedAtMillis <= 0 ? System.currentTimeMillis() : addedAtMillis; }
    public String getLastRunOutcome() { return lastRunOutcome; }
    public void setLastRunOutcome(String lastRunOutcome) { this.lastRunOutcome = text(lastRunOutcome); }
    public String getLastRunMessage() { return lastRunMessage; }
    public void setLastRunMessage(String lastRunMessage) { this.lastRunMessage = text(lastRunMessage); }
    public long getLastRunAtMillis() { return lastRunAtMillis; }
    public void setLastRunAtMillis(long lastRunAtMillis) { this.lastRunAtMillis = Math.max(0L, lastRunAtMillis); }
    public void markLastRun(String outcome, String message) {
        setLastRunOutcome(outcome);
        setLastRunMessage(message);
        setLastRunAtMillis(System.currentTimeMillis());
    }
    public String lastRunSummary() {
        if (lastRunOutcome.length() == 0) return "Not run";
        String msg = lastRunMessage.length() == 0 ? "" : " — " + lastRunMessage;
        if (msg.length() > 160) msg = msg.substring(0, 157) + "...";
        return lastRunOutcome + msg;
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ENGLISH).replace(" ", "").replace("_", "").replace("-", "");
    }
}

