package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bmc.arsys.api.Timestamp;
import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.bmc.ObjectTypeRegistry;

/** Immutable-ish result object used by the Yrell Migrator - Differences view. */
public final class CompareResult {
    private CompareStatus status = CompareStatus.UNKNOWN;
    private String sourceServer = "";
    private String targetServer = "";
    private String objectType = "";
    private String objectName = "";
    private int differenceCount = -1;
    private String detail = "";
    private CompareEvidence evidence = CompareEvidence.UNKNOWN;
    private String evidenceDetail = "";
    private Timestamp sourceModified;
    private Timestamp targetModified;
    private String sourceChangedBy = "";
    private String targetChangedBy = "";
    private String sourceCustomizationType = "";
    private String targetCustomizationType = "";
    private String sourceContextKey = "";
    private String targetContextKey = "";
    private String sourceFormType = "";
    private String targetFormType = "";
    private String sourcePrimaryForm = "";
    private String targetPrimaryForm = "";
    private String sourceWorkflowState = "";
    private String targetWorkflowState = "";
    private IStore sourceStore;
    private IStore targetStore;
    private IModelType modelType;
    private IModelItem sourceItem;
    private IModelItem targetItem;
    private List<DiffDetail> differenceDetails = new ArrayList<DiffDetail>();

    public CompareStatus getStatus() {
        return status;
    }

    public void setStatus(CompareStatus status) {
        this.status = status == null ? CompareStatus.UNKNOWN : status;
    }

    public String getSourceServer() {
        return sourceServer;
    }

    public void setSourceServer(String sourceServer) {
        this.sourceServer = nullToEmpty(sourceServer);
    }

    public String getTargetServer() {
        return targetServer;
    }

    public void setTargetServer(String targetServer) {
        this.targetServer = nullToEmpty(targetServer);
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = nullToEmpty(objectType);
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = nullToEmpty(objectName);
    }

    public int getDifferenceCount() {
        return differenceCount;
    }

    public void setDifferenceCount(int differenceCount) {
        this.differenceCount = differenceCount;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = nullToEmpty(detail);
    }

    public CompareEvidence getEvidence() {
        return evidence;
    }

    public void setEvidence(CompareEvidence evidence) {
        this.evidence = evidence == null ? CompareEvidence.UNKNOWN : evidence;
    }

    public String getEvidenceLabel() {
        return evidence == null ? CompareEvidence.UNKNOWN.getLabel() : evidence.getLabel();
    }

    public String getEvidenceDetail() {
        return evidenceDetail;
    }

    public void setEvidenceDetail(String evidenceDetail) {
        this.evidenceDetail = nullToEmpty(evidenceDetail);
    }

    public String getEvidenceTooltip() {
        String description = evidence == null ? CompareEvidence.UNKNOWN.getDescription() : evidence.getDescription();
        if (evidenceDetail.length() == 0) {
            return description;
        }
        return description + "\n" + evidenceDetail;
    }

    public Timestamp getSourceModified() {
        return sourceModified;
    }

    public void setSourceModified(Timestamp sourceModified) {
        this.sourceModified = sourceModified;
    }

    public Timestamp getTargetModified() {
        return targetModified;
    }

    public void setTargetModified(Timestamp targetModified) {
        this.targetModified = targetModified;
    }

    public String getSourceChangedBy() {
        return sourceChangedBy;
    }

    public void setSourceChangedBy(String sourceChangedBy) {
        this.sourceChangedBy = nullToEmpty(sourceChangedBy);
    }

    public String getTargetChangedBy() {
        return targetChangedBy;
    }

    public void setTargetChangedBy(String targetChangedBy) {
        this.targetChangedBy = nullToEmpty(targetChangedBy);
    }

    public String getSourceCustomizationType() {
        return sourceCustomizationType;
    }

    public void setSourceCustomizationType(String sourceCustomizationType) {
        this.sourceCustomizationType = normalizeCustomizationType(sourceCustomizationType);
    }

    public String getTargetCustomizationType() {
        return targetCustomizationType;
    }

    public void setTargetCustomizationType(String targetCustomizationType) {
        this.targetCustomizationType = normalizeCustomizationType(targetCustomizationType);
    }

    public String getCustomizationTypeSummary() {
        // A missing object must not be displayed as "base" on the missing side.
        // Example: a custom form that only exists in source should show "custom", not
        // "custom → base". Only infer "base" when both sides actually exist.
        String source = effectiveCustomizationType(sourceCustomizationType, status != CompareStatus.MISSING_IN_SOURCE);
        String target = effectiveCustomizationType(targetCustomizationType, status != CompareStatus.MISSING_IN_TARGET);
        if (source.length() == 0 && target.length() == 0) {
            return "";
        }
        if (source.length() == 0) {
            return target;
        }
        if (target.length() == 0) {
            return source;
        }
        if (source.equalsIgnoreCase(target)) {
            return source;
        }
        return source + " → " + target;
    }

    private String effectiveCustomizationType(String value, boolean objectExistsOnThisSide) {
        if (value != null && value.length() > 0) {
            return value;
        }
        if (!objectExistsOnThisSide) {
            return "";
        }
        return isCustomizationAwareType(objectType) ? "base" : "";
    }

    private static boolean isCustomizationAwareType(String type) {
        return ObjectTypeRegistry.isCustomizationAware(type);
    }


    public String getSourceFormType() {
        return sourceFormType;
    }

    public void setSourceFormType(String sourceFormType) {
        this.sourceFormType = nullToEmpty(sourceFormType);
    }

    public String getTargetFormType() {
        return targetFormType;
    }

    public void setTargetFormType(String targetFormType) {
        this.targetFormType = nullToEmpty(targetFormType);
    }

    public String getFormTypeSummary() {
        String source = status == CompareStatus.MISSING_IN_SOURCE ? "" : sourceFormType;
        String target = status == CompareStatus.MISSING_IN_TARGET ? "" : targetFormType;
        if (source.length() == 0 && target.length() == 0) return "";
        if (source.length() == 0) return target;
        if (target.length() == 0) return source;
        if (source.equalsIgnoreCase(target)) return source;
        return source + " → " + target;
    }

    public String getSourcePrimaryForm() {
        return sourcePrimaryForm;
    }

    public void setSourcePrimaryForm(String sourcePrimaryForm) {
        this.sourcePrimaryForm = nullToEmpty(sourcePrimaryForm);
    }

    public String getTargetPrimaryForm() {
        return targetPrimaryForm;
    }

    public void setTargetPrimaryForm(String targetPrimaryForm) {
        this.targetPrimaryForm = nullToEmpty(targetPrimaryForm);
    }

    public String getPrimaryFormSummary() {
        String source = status == CompareStatus.MISSING_IN_SOURCE ? "" : sourcePrimaryForm;
        String target = status == CompareStatus.MISSING_IN_TARGET ? "" : targetPrimaryForm;
        if (source.length() == 0 && target.length() == 0) return "";
        if (source.length() == 0) return target;
        if (target.length() == 0) return source;
        if (source.equalsIgnoreCase(target)) return source;
        return source + " → " + target;
    }

    public String getSourceWorkflowState() {
        return sourceWorkflowState;
    }

    public void setSourceWorkflowState(String sourceWorkflowState) {
        this.sourceWorkflowState = normalizeWorkflowState(sourceWorkflowState);
    }

    public String getTargetWorkflowState() {
        return targetWorkflowState;
    }

    public void setTargetWorkflowState(String targetWorkflowState) {
        this.targetWorkflowState = normalizeWorkflowState(targetWorkflowState);
    }

    public String getWorkflowStateSummary() {
        String source = status == CompareStatus.MISSING_IN_SOURCE ? "" : sourceWorkflowState;
        String target = status == CompareStatus.MISSING_IN_TARGET ? "" : targetWorkflowState;
        if (source.length() == 0 && target.length() == 0) return "";
        if (source.length() == 0) return target;
        if (target.length() == 0) return source;
        if (source.equalsIgnoreCase(target)) return source;
        return source + " → " + target;
    }

    public String getSourceContextKey() {
        return sourceContextKey;
    }

    public void setSourceContextKey(String sourceContextKey) {
        this.sourceContextKey = nullToEmpty(sourceContextKey);
    }

    public String getTargetContextKey() {
        return targetContextKey;
    }

    public void setTargetContextKey(String targetContextKey) {
        this.targetContextKey = nullToEmpty(targetContextKey);
    }

    public String getContextKeySummary() {
        if (sourceContextKey.length() == 0 && targetContextKey.length() == 0) {
            return "";
        }
        if (sourceContextKey.length() == 0) {
            return targetContextKey;
        }
        if (targetContextKey.length() == 0) {
            return sourceContextKey;
        }
        if (sourceContextKey.equalsIgnoreCase(targetContextKey)) {
            return sourceContextKey;
        }
        return sourceContextKey + " → " + targetContextKey;
    }

    public IStore getSourceStore() {
        return sourceStore;
    }

    public void setSourceStore(IStore sourceStore) {
        this.sourceStore = sourceStore;
    }

    public IStore getTargetStore() {
        return targetStore;
    }

    public void setTargetStore(IStore targetStore) {
        this.targetStore = targetStore;
    }

    public IModelType getModelType() {
        return modelType;
    }

    public void setModelType(IModelType modelType) {
        this.modelType = modelType;
    }

    public IModelItem getSourceItem() {
        return sourceItem;
    }

    public void setSourceItem(IModelItem sourceItem) {
        this.sourceItem = sourceItem;
    }

    public IModelItem getTargetItem() {
        return targetItem;
    }

    public void setTargetItem(IModelItem targetItem) {
        this.targetItem = targetItem;
    }

    public List<DiffDetail> getDifferenceDetails() {
        return Collections.unmodifiableList(differenceDetails);
    }

    public void setDifferenceDetails(List<DiffDetail> differenceDetails) {
        this.differenceDetails = new ArrayList<DiffDetail>();
        if (differenceDetails != null) {
            this.differenceDetails.addAll(differenceDetails);
        }
    }

    public boolean hasStructuredDetails() {
        return differenceDetails != null && !differenceDetails.isEmpty();
    }

    public boolean canOpenDetailedCompare() {
        return sourceStore != null && targetStore != null && modelType != null
                && objectName != null && objectName.length() > 0
                && status != CompareStatus.MISSING_IN_TARGET
                && status != CompareStatus.MISSING_IN_SOURCE
                && status != CompareStatus.ERROR;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeWorkflowState(String value) {
        String text = nullToEmpty(value).trim();
        if (text.length() == 0 || "null".equalsIgnoreCase(text) || "unknown".equalsIgnoreCase(text)) {
            return "";
        }
        String lower = text.toLowerCase(java.util.Locale.ENGLISH);
        if (lower.indexOf("disable") >= 0 || "false".equals(lower) || "0".equals(lower) || "2".equals(lower)) {
            return "Disabled";
        }
        if (lower.indexOf("enable") >= 0 || "true".equals(lower) || "1".equals(lower)) {
            return "Enabled";
        }
        return text;
    }

    private static String normalizeCustomizationType(String value) {
        String text = nullToEmpty(value).trim();
        if (text.length() == 0 || "-".equals(text) || "--".equals(text)
                || "null".equalsIgnoreCase(text) || "unknown".equalsIgnoreCase(text)) {
            return "";
        }
        String lower = text.toLowerCase(java.util.Locale.ENGLISH);
        java.util.regex.Matcher propMatcher = java.util.regex.Pattern
                .compile("(?:^|[^0-9])90015[^0-9-]*(-?\\d+)").matcher(text);
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
            // NONE=0, OVERLAY=2, CUSTOM=4. Object overlay-state values such as 1
            // must not be shown as customization type.
            if (numeric == 4) {
                return "custom";
            }
            if (numeric == 2) {
                return "overlay";
            }
            if (numeric == 0) {
                return "base";
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

}
