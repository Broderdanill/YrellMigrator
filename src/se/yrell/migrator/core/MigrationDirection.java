package se.yrell.migrator.core;

/** Direction for copying one workflow definition between the two compared environments. */
public enum MigrationDirection {
    SOURCE_TO_TARGET("Source → Target"),
    TARGET_TO_SOURCE("Target → Source");

    private final String label;

    MigrationDirection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
