package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import se.yrell.migrator.bmc.ObjectTypeRegistry;

/** Builds a deterministic, reviewable plan before migration execution. */
public final class MigrationPlanner {
    public MigrationPlan buildPlan(List<CompareResult> selectedRows, List<CompareResult> migratableRows, MigrationDirection direction, int skippedCount) {
        List<CompareResult> ordered = orderDefinitionMigration(migratableRows);
        List<MigrationPlanStep> steps = new ArrayList<MigrationPlanStep>();
        int i = 0;
        for (CompareResult row : ordered) {
            i++;
            steps.add(new MigrationPlanStep(i, row, actionFor(row, direction), phaseFor(row), noteFor(row, direction)));
        }
        List<String> warnings = buildWarnings(ordered, direction);
        boolean containsContainer = false;
        for (CompareResult row : ordered) {
            if (isContainerType(row)) {
                containsContainer = true;
                break;
            }
        }
        String sourceName = describeSource(ordered, direction);
        String targetName = describeTarget(ordered, direction);
        int selectedCount = selectedRows == null ? ordered.size() + skippedCount : selectedRows.size();
        return new MigrationPlan(direction, sourceName, targetName, steps, warnings, selectedCount, skippedCount, containsContainer);
    }

    public List<CompareResult> orderDefinitionMigration(List<CompareResult> rows) {
        List<CompareResult> ordered = new ArrayList<CompareResult>();
        if (rows != null) {
            ordered.addAll(rows);
        }
        Collections.sort(ordered, new Comparator<CompareResult>() {
            public int compare(CompareResult left, CompareResult right) {
                int weight = migrationTypeWeight(left) - migrationTypeWeight(right);
                if (weight != 0) {
                    return weight;
                }
                int type = safe(left == null ? null : left.getObjectType()).compareToIgnoreCase(safe(right == null ? null : right.getObjectType()));
                if (type != 0) {
                    return type;
                }
                return safe(left == null ? null : left.getObjectName()).compareToIgnoreCase(safe(right == null ? null : right.getObjectName()));
            }
        });
        return ordered;
    }

    private MigrationPlanStep.Action actionFor(CompareResult row, MigrationDirection direction) {
        if (row == null || row.getStatus() == null) {
            return MigrationPlanStep.Action.UNKNOWN;
        }
        if (direction == MigrationDirection.SOURCE_TO_TARGET) {
            if (row.getStatus() == CompareStatus.MISSING_IN_TARGET) {
                return MigrationPlanStep.Action.CREATE;
            }
            if (row.getStatus() == CompareStatus.MISSING_IN_SOURCE) {
                return MigrationPlanStep.Action.UNKNOWN;
            }
        } else {
            if (row.getStatus() == CompareStatus.MISSING_IN_SOURCE) {
                return MigrationPlanStep.Action.CREATE;
            }
            if (row.getStatus() == CompareStatus.MISSING_IN_TARGET) {
                return MigrationPlanStep.Action.UNKNOWN;
            }
        }
        if (row.getStatus() == CompareStatus.CHANGED) {
            return MigrationPlanStep.Action.UPDATE;
        }
        if (row.getStatus() == CompareStatus.EQUAL) {
            return MigrationPlanStep.Action.VERIFY;
        }
        return MigrationPlanStep.Action.OVERWRITE;
    }

    private String noteFor(CompareResult row, MigrationDirection direction) {
        if (row == null) {
            return "";
        }
        if (row.getStatus() == CompareStatus.ERROR) {
            return "row has comparison error; migration will still use the selected side if available";
        }
        String customization = row.getCustomizationTypeSummary();
        if (customization != null && customization.toLowerCase(Locale.ENGLISH).indexOf("overlay") >= 0) {
            return "overlay/schema refresh warmup is run after successful migration";
        }
        if (isContainerType(row)) {
            return "container content can be included as a separate best-effort step";
        }
        if (isSupportFileType(row)) {
            return "best-effort Support File migration; binary APIs differ between Developer Studio versions";
        }
        if (isBinaryType(row)) {
            return "binary object; live compare adds fingerprint/profile evidence when byte data is exposed";
        }
        String type = normalizedType(row);
        if (type.equals("group") || type.equals("grouptype")) {
            return "catalog row; computed groups may be retried after dependencies";
        }
        return "";
    }

    private String phaseFor(CompareResult row) {
        String type = normalizedType(row);
        String registryPhase = ObjectTypeRegistry.phaseFor(row == null ? null : row.getObjectType());
        if (registryPhase != null && registryPhase.length() > 0 && !"Other definitions".equals(registryPhase)) {
            return registryPhase;
        }
        if (isGroupOrRoleCatalogType(type)) {
            return "Groups / Roles";
        }
        if (type.indexOf("menu") >= 0) {
            return "Menus";
        }
        if (type.indexOf("form") >= 0) {
            return "Forms";
        }
        if (isCatalogDataTypeName(type)) {
            return "Catalog data";
        }
        if (type.indexOf("association") >= 0) {
            return "Associations";
        }
        if (isBinaryType(row) || isSupportFileType(row)) {
            return "Images / Support files";
        }
        if (type.indexOf("activelink") >= 0 || type.indexOf("filter") >= 0 || type.indexOf("escalation") >= 0) {
            return "Workflow";
        }
        if (type.indexOf("guide") >= 0) {
            return "Guides";
        }
        if (isContainerType(row)) {
            return "Containers";
        }
        return "Other definitions";
    }

    private List<String> buildWarnings(List<CompareResult> ordered, MigrationDirection direction) {
        List<String> warnings = new ArrayList<String>();
        warnings.addAll(findGroupDependencyHints(ordered, direction, selectedGroupIds(ordered, direction)));
        for (CompareResult row : ordered) {
            if (row == null) {
                continue;
            }
            String type = normalizedType(row);
            String formType = formTypeSummary(row).toLowerCase(Locale.ENGLISH);
            if (type.indexOf("form") >= 0 && (formType.indexOf("join") >= 0 || formType.indexOf("view") >= 0 || formType.indexOf("vendor") >= 0)) {
                warnings.add(row.getObjectType() + " " + row.getObjectName() + " is a " + formTypeSummary(row)
                        + " form. Include referenced base/regular forms in the same migration when possible.");
            }
            if (row.getStatus() == CompareStatus.ERROR) {
                warnings.add(row.getObjectType() + " " + row.getObjectName() + " has comparison status Error. Refresh the row before migration if possible.");
            }
            if (type.indexOf("association") >= 0) {
                warnings.add(row.getObjectType() + " " + row.getObjectName()
                        + " is an Association. Migration is best-effort and depends on how this Developer Studio build exposes association definitions.");
            }
            if (isSupportFileType(row)) {
                warnings.add(row.getObjectType() + " " + row.getObjectName()
                        + " is a Support File. Migration is best-effort and depends on how this Developer Studio build exposes binary support file content.");
            } else if (isBinaryType(row)) {
                warnings.add(row.getObjectType() + " " + row.getObjectName()
                        + " is binary-like. Use live Refresh Selected after migration if you need byte-level fingerprint evidence.");
            }
        }
        return unique(warnings);
    }

    private List<String> unique(List<String> values) {
        List<String> result = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String value : values) {
            if (value != null && value.trim().length() > 0 && seen.add(value.trim())) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private Set<String> selectedGroupIds(List<CompareResult> candidates, MigrationDirection direction) {
        Set<String> ids = new LinkedHashSet<String>();
        if (candidates == null) {
            return ids;
        }
        for (CompareResult row : candidates) {
            if (row != null && isGroupType(row.getObjectType())) {
                String key = direction == MigrationDirection.TARGET_TO_SOURCE ? row.getTargetContextKey() : row.getSourceContextKey();
                if (key != null && key.trim().length() > 0) {
                    ids.add(key.trim());
                }
            }
        }
        return ids;
    }

    private List<String> findGroupDependencyHints(List<CompareResult> candidates, MigrationDirection direction, Set<String> selectedGroupIds) {
        List<String> warnings = new ArrayList<String>();
        if (candidates == null) {
            return warnings;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (CompareResult row : candidates) {
            if (row == null || row.getDifferenceDetails() == null) {
                continue;
            }
            for (DiffDetail d : row.getDifferenceDetails()) {
                String text = (safe(d.getArea()) + " " + safe(d.getProperty()) + " " + safe(d.getSourceValue()) + " " + safe(d.getTargetValue())).toLowerCase(Locale.ENGLISH);
                if (text.indexOf("group") < 0 && text.indexOf("permission") < 0) {
                    continue;
                }
                for (String id : extractIntegerTokens(safe(d.getSourceValue()) + " " + safe(d.getTargetValue()))) {
                    if (id.length() < 2) {
                        continue;
                    }
                    if (!selectedGroupIds.contains(id) && seen.add(row.getObjectName() + ":" + id)) {
                        warnings.add(row.getObjectType() + " " + row.getObjectName()
                                + " may reference Group ID " + id + ". Select/migrate the Group row first if it is missing in target.");
                    }
                }
            }
        }
        return warnings;
    }

    private List<String> extractIntegerTokens(String text) {
        List<String> values = new ArrayList<String>();
        if (text == null) {
            return values;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b\\d{2,}\\b").matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private String describeSource(List<CompareResult> rows, MigrationDirection direction) {
        CompareResult first = firstRow(rows);
        if (first == null) {
            return "";
        }
        return direction == MigrationDirection.TARGET_TO_SOURCE ? first.getTargetServer() : first.getSourceServer();
    }

    private String describeTarget(List<CompareResult> rows, MigrationDirection direction) {
        CompareResult first = firstRow(rows);
        if (first == null) {
            return "";
        }
        return direction == MigrationDirection.TARGET_TO_SOURCE ? first.getSourceServer() : first.getTargetServer();
    }

    private CompareResult firstRow(List<CompareResult> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isContainerType(CompareResult row) {
        String type = normalizedType(row);
        return type.indexOf("application") >= 0 || type.indexOf("packinglist") >= 0 || type.indexOf("container") >= 0;
    }

    private boolean isGroupType(String type) {
        String value = safe(type).toLowerCase(Locale.ENGLISH).replace(" ", "");
        return value.equals("group") || value.equals("grouptype");
    }

    private boolean isCatalogDataTypeName(String normalizedType) {
        return normalizedType != null
                && (isGroupOrRoleCatalogType(normalizedType)
                || normalizedType.equals("message")
                || normalizedType.equals("report")
                || normalizedType.equals("reporttype")
                || normalizedType.equals("template"));
    }

    private boolean isGroupOrRoleCatalogType(String normalizedType) {
        return normalizedType != null
                && (normalizedType.equals("group")
                || normalizedType.equals("grouptype")
                || normalizedType.equals("role")
                || normalizedType.equals("roletype"));
    }

    private boolean isBinaryType(CompareResult row) {
        String type = normalizedType(row);
        return type.indexOf("image") >= 0 || type.indexOf("binary") >= 0 || type.indexOf("attachment") >= 0;
    }

    private boolean isSupportFileType(CompareResult row) {
        String type = normalizedType(row);
        return type.indexOf("supportfile") >= 0;
    }

    private int migrationTypeWeight(CompareResult result) {
        String type = normalizedType(result);
        if (isGroupOrRoleCatalogType(type)) {
            return 10 + groupRoleMigrationWeight(result);
        }
        if (type.indexOf("menu") >= 0) {
            return 20;
        }
        if (type.indexOf("form") >= 0) {
            return 30 + formMigrationTypeWeight(result);
        }
        if (isCatalogDataTypeName(type)) {
            return 40;
        }
        if (type.indexOf("association") >= 0) {
            return 44;
        }
        if (isBinaryType(result) || isSupportFileType(result)) {
            return 45;
        }
        if (type.indexOf("activelink") >= 0) {
            return 50;
        }
        if (type.indexOf("filter") >= 0) {
            return 60;
        }
        if (type.indexOf("escalation") >= 0) {
            return 70;
        }
        if (type.indexOf("guide") >= 0) {
            return 80;
        }
        if (isContainerType(result)) {
            return 90;
        }
        return 100;
    }

    private int groupRoleMigrationWeight(CompareResult result) {
        String type = normalizedType(result);
        if (type.equals("group") || type.equals("grouptype")) {
            return 0;
        }
        if (type.equals("role") || type.equals("roletype")) {
            return 1;
        }
        return 2;
    }

    private int formMigrationTypeWeight(CompareResult result) {
        String formType = formTypeSummary(result).toLowerCase(Locale.ENGLISH);
        int base;
        if (formType.indexOf("regular") >= 0) {
            base = 0;
        } else if (formType.indexOf("display") >= 0) {
            base = 1;
        } else if (formType.indexOf("join") >= 0) {
            base = 2;
        } else if (formType.indexOf("view") >= 0) {
            base = 3;
        } else if (formType.indexOf("vendor") >= 0) {
            base = 4;
        } else {
            base = 5;
        }
        return (base * 10) + 5;
    }

    private String formTypeSummary(CompareResult result) {
        if (result == null) {
            return "";
        }
        String value = result.getFormTypeSummary();
        return value == null ? "" : value;
    }

    private String normalizedType(CompareResult result) {
        return safe(result == null ? null : result.getObjectType()).toLowerCase(Locale.ENGLISH).replace(" ", "");
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
