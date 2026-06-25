package se.yrell.migrator.core;

/** Operator-facing outcome for one migration result row. */
public enum MigrationOutcome {
    CREATED("Created", true, false),
    UPDATED("Updated", true, false),
    VERIFIED("Verified", true, false),
    SKIPPED("Skipped", true, true),
    WARNING("Warning", true, true),
    STILL_DIFFERENT("Still different", true, true),
    CANCELLED("Cancelled", false, true),
    FAILED("Failed", false, false),
    UNKNOWN("Unknown", false, true);

    private final String label;
    private final boolean success;
    private final boolean warning;

    MigrationOutcome(String label, boolean success, boolean warning) {
        this.label = label;
        this.success = success;
        this.warning = warning;
    }

    public String getLabel() {
        return label;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isWarning() {
        return warning;
    }
}
