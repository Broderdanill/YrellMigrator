package se.yrell.migrator.ui.util;

import java.util.Locale;

import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Small helper for long-running Eclipse jobs.
 *
 * The goal is not to change migration behavior, only to make the workbench progress
 * area and the Yrell Migrator activity log more useful when thousands of objects or
 * rows are processed. The helper deliberately has no SWT dependencies so it can be
 * reused by context actions and the main view.
 */
public final class ProgressMessages {
    private final String verb;
    private final int total;
    private final long startedAtMillis;

    public ProgressMessages(String verb, int total) {
        this.verb = verb == null || verb.length() == 0 ? "Processing" : verb;
        this.total = Math.max(0, total);
        this.startedAtMillis = System.currentTimeMillis();
    }

    public String step(int index, String objectType, String objectName) {
        StringBuilder b = new StringBuilder();
        b.append(verb).append(' ');
        if (total > 0) {
            b.append(Math.max(0, index)).append('/').append(total);
        } else {
            b.append(Math.max(0, index));
        }
        String identity = identity(objectType, objectName);
        if (identity.length() > 0) {
            b.append(" · ").append(identity);
        }
        b.append(" · elapsed ").append(elapsedLabel());
        return b.toString();
    }

    public String phase(String phase, int index, String objectType, String objectName) {
        String base = step(index, objectType, objectName);
        if (phase == null || phase.length() == 0) {
            return base;
        }
        return base + " · " + phase;
    }

    public String elapsedLabel() {
        return formatDuration(System.currentTimeMillis() - startedAtMillis);
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public static boolean isCancelled(IProgressMonitor monitor) {
        return monitor != null && monitor.isCanceled();
    }

    public static String cancelRequested(String nextSafePoint) {
        String suffix = safe(nextSafePoint);
        return suffix.length() == 0
                ? "Cancel requested - stopping at the next safe point."
                : "Cancel requested - stopping after " + suffix + ".";
    }

    public static void markCancelRequested(IProgressMonitor monitor, String nextSafePoint) {
        if (monitor != null && monitor.isCanceled()) {
            monitor.subTask(cancelRequested(nextSafePoint));
        }
    }

    public static String identity(String objectType, String objectName) {
        String type = safe(objectType);
        String name = safe(objectName);
        if (type.length() == 0) {
            return name;
        }
        if (name.length() == 0) {
            return type;
        }
        return type + " " + name;
    }

    public static String formatDuration(long millis) {
        long safe = Math.max(0L, millis);
        long seconds = safe / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        seconds = seconds % 60L;
        minutes = minutes % 60L;
        if (hours > 0L) {
            return String.format(Locale.ENGLISH, "%dh %02dm %02ds", Long.valueOf(hours), Long.valueOf(minutes), Long.valueOf(seconds));
        }
        if (minutes > 0L) {
            return String.format(Locale.ENGLISH, "%dm %02ds", Long.valueOf(minutes), Long.valueOf(seconds));
        }
        return String.format(Locale.ENGLISH, "%ds", Long.valueOf(seconds));
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
