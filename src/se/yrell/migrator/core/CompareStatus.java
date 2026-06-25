package se.yrell.migrator.core;

/** Summary status for one AR System model object comparison. */
public enum CompareStatus {
    EQUAL("Equal"),
    CHANGED("Different"),
    MISSING_IN_TARGET("Missing in target"),
    MISSING_IN_SOURCE("Missing in source"),
    ERROR("Error"),
    UNKNOWN("Unknown");

    private final String label;

    CompareStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
