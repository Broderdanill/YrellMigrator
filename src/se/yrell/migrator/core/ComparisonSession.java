package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** A snapshot of one compare run shown in the Differences view. */
public final class ComparisonSession {
    private final List<CompareResult> results;
    private final Date createdAt;
    private final String label;

    public ComparisonSession(String label, List<CompareResult> results) {
        this.label = label == null ? "" : label;
        this.results = Collections.unmodifiableList(new ArrayList<CompareResult>(results));
        this.createdAt = new Date();
    }

    public List<CompareResult> getResults() {
        return results;
    }

    public Date getCreatedAt() {
        return new Date(createdAt.getTime());
    }

    public String getLabel() {
        return label;
    }

    public int size() {
        return results.size();
    }
}
