package se.yrell.migrator.core;

/**
 * Describes how trustworthy a comparison row is and where the displayed diff came from.
 *
 * The status column answers "what differs"; this value answers "how do we know". Keeping this
 * explicit is important because Yrell Migrator can intentionally use fast metadata or cached
 * snapshots instead of opening every object from the AR server on each search.
 */
public enum CompareEvidence {
    LIVE("Live", 0, "Object definitions were opened from the connected Developer Studio/AR server session."),
    CACHED_SNAPSHOT("Snapshot", 1, "Compared from full local definition snapshots created by Sync."),
    REUSED_SNAPSHOT("Reused snapshot", 2, "Compared from cached snapshots that were kept because metadata did not prove a refresh was required."),
    METADATA_ONLY("Metadata only", 3, "Row was derived from lightweight metadata. Definition properties were not compared."),
    CACHE_ERROR("Cache error", 4, "The local definition cache contains an error for at least one side."),
    UNKNOWN("Unknown", 5, "The comparison source could not be determined.");

    private final String label;
    private final int rank;
    private final String description;

    CompareEvidence(String label, int rank, String description) {
        this.label = label;
        this.rank = rank;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public int getRank() {
        return rank;
    }

    public String getDescription() {
        return description;
    }
}
