package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable migration plan shown before executing object migration. */
public final class MigrationPlan {
    private final MigrationDirection direction;
    private final String sourceName;
    private final String targetName;
    private final List<MigrationPlanStep> steps;
    private final List<String> warnings;
    private final int selectedCount;
    private final int skippedCount;
    private final boolean containsContainer;

    MigrationPlan(MigrationDirection direction, String sourceName, String targetName,
            List<MigrationPlanStep> steps, List<String> warnings, int selectedCount, int skippedCount,
            boolean containsContainer) {
        this.direction = direction == null ? MigrationDirection.SOURCE_TO_TARGET : direction;
        this.sourceName = empty(sourceName);
        this.targetName = empty(targetName);
        this.steps = Collections.unmodifiableList(new ArrayList<MigrationPlanStep>(steps == null ? Collections.<MigrationPlanStep>emptyList() : steps));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings == null ? Collections.<String>emptyList() : warnings));
        this.selectedCount = selectedCount;
        this.skippedCount = skippedCount;
        this.containsContainer = containsContainer;
    }

    public MigrationDirection getDirection() {
        return direction;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getTargetName() {
        return targetName;
    }

    public List<MigrationPlanStep> getSteps() {
        return steps;
    }

    public List<CompareResult> getOrderedRows() {
        List<CompareResult> rows = new ArrayList<CompareResult>();
        for (MigrationPlanStep step : steps) {
            if (step != null && step.getRow() != null) {
                rows.add(step.getRow());
            }
        }
        return rows;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Map<String, Integer> getPhaseCounts() {
        Map<String, Integer> phases = new LinkedHashMap<String, Integer>();
        for (MigrationPlanStep step : steps) {
            if (step == null) {
                continue;
            }
            String phase = step.getPhase();
            Integer count = phases.get(phase);
            phases.put(phase, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
        return Collections.unmodifiableMap(phases);
    }

    public int getPlannedCount() {
        return steps.size();
    }

    public int getWarningCount() {
        return warnings.size() + (skippedCount > 0 ? 1 : 0);
    }

    public int getSelectedCount() {
        return selectedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public boolean containsContainer() {
        return containsContainer;
    }

    public boolean hasWarnings() {
        return skippedCount > 0 || !warnings.isEmpty();
    }

    public String toReport(boolean includeAllRows) {
        StringBuilder b = new StringBuilder();
        b.append("Direction: ").append(direction.getLabel()).append('\n');
        b.append("From: ").append(sourceName.length() == 0 ? "<unknown>" : sourceName).append('\n');
        b.append("To:   ").append(targetName.length() == 0 ? "<unknown>" : targetName).append('\n');
        b.append("Selected: ").append(selectedCount).append('\n');
        b.append("Planned:  ").append(steps.size()).append('\n');
        if (skippedCount > 0) {
            b.append("Skipped:  ").append(skippedCount).append(" unsupported/invalid row(s)\n");
        }
        b.append('\n');

        b.append("Phase summary\n");
        Map<String, Integer> phases = new LinkedHashMap<String, Integer>();
        for (MigrationPlanStep step : steps) {
            String phase = step.getPhase();
            Integer count = phases.get(phase);
            phases.put(phase, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
        if (phases.isEmpty()) {
            b.append("  No executable steps.\n");
        } else {
            for (Map.Entry<String, Integer> e : phases.entrySet()) {
                b.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        b.append('\n');

        b.append("Planned order\n");
        int max = includeAllRows ? steps.size() : Math.min(steps.size(), 80);
        for (int i = 0; i < max; i++) {
            MigrationPlanStep step = steps.get(i);
            b.append("  ").append(step.getNumber()).append(". ")
                    .append(step.getAction().getLabel()).append(" ")
                    .append(step.getObjectType()).append(" — ")
                    .append(step.getObjectName());
            String key = step.getContextKey(direction);
            if (key.length() > 0) {
                b.append("  [Key / ID: ").append(key).append(']');
            }
            String form = step.getPrimaryForm(direction);
            if (form.length() > 0) {
                b.append("  [Form: ").append(form).append(']');
            }
            if (step.getNote().length() > 0) {
                b.append("  — ").append(step.getNote());
            }
            b.append('\n');
        }
        if (!includeAllRows && steps.size() > max) {
            b.append("  ... ").append(steps.size() - max).append(" more row(s) ...\n");
        }
        b.append('\n');

        if (!warnings.isEmpty()) {
            b.append("Warnings / dependency hints\n");
            for (String warning : warnings) {
                b.append("  - ").append(warning).append('\n');
            }
            b.append('\n');
        }

        b.append("Execution notes\n");
        b.append("  - Target objects with the same name/key will be updated or overwritten. Missing target objects will be created.\n");
        b.append("  - Group and Role catalog rows are planned before forms and workflow because permissions often reference Group IDs.\n");
        b.append("  - Computed Group rows can still be deferred and retried during execution if AR reports missing group dependencies.\n");
        b.append("  - Applications and Packing Lists can optionally migrate discoverable content after the container object.\n");
        b.append("  - This is a dry-run plan generated from the current comparison/cache state; execution can still fail if the live server state has changed.\n");
        return b.toString();
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
