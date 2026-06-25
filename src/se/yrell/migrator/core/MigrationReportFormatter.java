package se.yrell.migrator.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Formats object migration results in one place so the main view and the
 * Developer Studio context action produce the same operator-facing report.
 */
public final class MigrationReportFormatter {
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private MigrationReportFormatter() {
    }

    public static final class Summary {
        private final int total;
        private final int succeeded;
        private final int created;
        private final int updated;
        private final int verified;
        private final int skipped;
        private final int warnings;
        private final int stillDifferent;
        private final int cancelled;
        private final int failed;
        private final long startedAtMillis;
        private final long finishedAtMillis;
        private final String scope;
        private final MigrationDirection direction;

        private Summary(MigrationDirection direction, List<MigrationResult> results, long startedAtMillis, long finishedAtMillis, String scope) {
            long end = finishedAtMillis <= 0L ? System.currentTimeMillis() : finishedAtMillis;
            long start = startedAtMillis <= 0L || startedAtMillis > end ? end : startedAtMillis;
            this.startedAtMillis = start;
            this.finishedAtMillis = end;
            this.scope = empty(scope).length() == 0 ? "Object migration" : empty(scope);
            this.direction = direction;

            int t = 0;
            int ok = 0;
            int c = 0;
            int u = 0;
            int v = 0;
            int s = 0;
            int w = 0;
            int sd = 0;
            int cn = 0;
            int f = 0;
            if (results != null) {
                for (MigrationResult result : results) {
                    if (result == null) {
                        continue;
                    }
                    t++;
                    MigrationOutcome outcome = result.getOutcome();
                    if (outcome == null) {
                        outcome = MigrationOutcome.UNKNOWN;
                    }
                    if (result.isSuccess()) {
                        ok++;
                    }
                    if (result.isWarning()) {
                        w++;
                    }
                    switch (outcome) {
                    case CREATED:
                        c++;
                        break;
                    case UPDATED:
                        u++;
                        break;
                    case VERIFIED:
                        v++;
                        break;
                    case SKIPPED:
                        s++;
                        break;
                    case WARNING:
                        break;
                    case STILL_DIFFERENT:
                        sd++;
                        break;
                    case CANCELLED:
                        cn++;
                        break;
                    case FAILED:
                    case UNKNOWN:
                    default:
                        f++;
                        break;
                    }
                }
            }
            this.total = t;
            this.succeeded = ok;
            this.created = c;
            this.updated = u;
            this.verified = v;
            this.skipped = s;
            this.warnings = w;
            this.stillDifferent = sd;
            this.cancelled = cn;
            this.failed = f;
        }

        public int getTotal() { return total; }
        public int getSucceeded() { return succeeded; }
        public int getCreated() { return created; }
        public int getUpdated() { return updated; }
        public int getVerified() { return verified; }
        public int getSkipped() { return skipped; }
        public int getWarnings() { return warnings; }
        public int getStillDifferent() { return stillDifferent; }
        public int getCancelled() { return cancelled; }
        public int getFailed() { return failed; }
        public long getStartedAtMillis() { return startedAtMillis; }
        public long getFinishedAtMillis() { return finishedAtMillis; }
        public String getScope() { return scope; }
        public MigrationDirection getDirection() { return direction; }
        public String getDurationLabel() { return formatDuration(finishedAtMillis - startedAtMillis); }
        public boolean hasProblems() { return failed > 0 || warnings > 0 || cancelled > 0; }
        public boolean hasFailures() { return failed > 0; }
    }

    public static Summary summarize(MigrationDirection direction, List<MigrationResult> results,
            long startedAtMillis, long finishedAtMillis, String scope) {
        return new Summary(direction, results, startedAtMillis, finishedAtMillis, scope);
    }

    public static String formatObjectMigrationReport(MigrationDirection direction, List<MigrationResult> results,
            long startedAtMillis, long finishedAtMillis, String scope) {
        Summary summary = summarize(direction, results, startedAtMillis, finishedAtMillis, scope);
        StringBuilder b = new StringBuilder();

        b.append("Scope: ").append(summary.getScope()).append('\n');
        b.append("Direction: ").append(direction == null ? "<unknown>" : direction.getLabel()).append('\n');
        b.append("Started: ").append(formatTime(summary.getStartedAtMillis())).append('\n');
        b.append("Finished: ").append(formatTime(summary.getFinishedAtMillis())).append('\n');
        b.append("Duration: ").append(summary.getDurationLabel()).append('\n');
        b.append("Total result rows: ").append(summary.getTotal()).append('\n');
        b.append("Succeeded: ").append(summary.getSucceeded()).append('\n');
        b.append("Created: ").append(summary.getCreated()).append('\n');
        b.append("Updated: ").append(summary.getUpdated()).append('\n');
        b.append("Verified: ").append(summary.getVerified()).append('\n');
        b.append("Skipped: ").append(summary.getSkipped()).append('\n');
        b.append("Warnings: ").append(summary.getWarnings()).append('\n');
        b.append("Still different after migration: ").append(summary.getStillDifferent()).append('\n');
        b.append("Cancelled: ").append(summary.getCancelled()).append('\n');
        b.append("Failed: ").append(summary.getFailed()).append('\n');

        if (results != null && !results.isEmpty()) {
            b.append('\n').append("Per object").append('\n');
            int index = 0;
            for (MigrationResult result : results) {
                index++;
                b.append("  ").append(index).append(". ");
                appendObjectSummary(b, result);
                b.append('\n');
            }
        }

        if (summary.getWarnings() > 0) {
            b.append('\n').append("Warnings").append('\n');
            if (results != null) {
                for (MigrationResult result : results) {
                    if (result != null && result.isWarning()) {
                        b.append("  - ");
                        appendObjectIdentity(b, result.getCompareResult());
                        String cause = DiffSummaryAnalyzer.shortCause(result.getCompareResult());
                        if (cause.length() > 0) {
                            b.append(": ").append(cause).append(". ");
                        } else {
                            b.append(": ");
                        }
                        String risk = DiffRiskClassifier.oneLine(result.getCompareResult());
                        if (risk.length() > 0 && risk.indexOf("Risk: none") < 0) {
                            b.append(risk).append(' ');
                        }
                        b.append(empty(result.getDetail())).append('\n');
                    }
                }
            }
        }

        if (summary.getFailed() > 0) {
            b.append('\n').append("Failures").append('\n');
            if (results != null) {
                for (MigrationResult result : results) {
                    if (result != null && (result.getOutcome() == MigrationOutcome.FAILED || result.getOutcome() == MigrationOutcome.UNKNOWN)) {
                        b.append("  - ");
                        appendObjectIdentity(b, result.getCompareResult());
                        b.append(": ").append(empty(result.getDetail())).append('\n');
                    }
                }
            }
        }
        return b.toString();
    }

    public static int countFailures(List<MigrationResult> results) {
        int failed = 0;
        if (results != null) {
            for (MigrationResult result : results) {
                if (result != null && (result.getOutcome() == MigrationOutcome.FAILED || result.getOutcome() == MigrationOutcome.UNKNOWN)) {
                    failed++;
                }
            }
        }
        return failed;
    }

    public static int countWarnings(List<MigrationResult> results) {
        int warnings = 0;
        if (results != null) {
            for (MigrationResult result : results) {
                if (result != null && result.isWarning()) {
                    warnings++;
                }
            }
        }
        return warnings;
    }

    public static int countSuccesses(List<MigrationResult> results) {
        int succeeded = 0;
        if (results != null) {
            for (MigrationResult result : results) {
                if (result != null && result.isSuccess()) {
                    succeeded++;
                }
            }
        }
        return succeeded;
    }

    private static void appendObjectSummary(StringBuilder b, MigrationResult result) {
        if (result == null) {
            b.append("UNKNOWN Unknown object");
            return;
        }
        b.append(result.getOutcomeLabel()).append("  ");
        appendObjectIdentity(b, result.getCompareResult());
        String cause = DiffSummaryAnalyzer.shortCause(result.getCompareResult());
        if (cause.length() > 0 && (result.getOutcome() == MigrationOutcome.STILL_DIFFERENT || result.isWarning())) {
            b.append("  - Cause: ").append(cause);
        }
        if (result.getOutcome() == MigrationOutcome.STILL_DIFFERENT || result.isWarning()) {
            String risk = DiffRiskClassifier.oneLine(result.getCompareResult());
            if (risk.length() > 0 && risk.indexOf("Risk: none") < 0) {
                b.append("  ").append(risk);
            }
        }
        String detail = empty(result.getDetail());
        if (detail.length() > 0) {
            b.append(cause.length() > 0 && (result.getOutcome() == MigrationOutcome.STILL_DIFFERENT || result.isWarning()) ? "  Detail: " : "  - ")
                    .append(detail.replace('\r', ' ').replace('\n', ' '));
        }
    }

    public static String objectIdentity(MigrationResult result) {
        StringBuilder b = new StringBuilder();
        appendObjectIdentity(b, result == null ? null : result.getCompareResult());
        return b.toString();
    }

    private static void appendObjectIdentity(StringBuilder b, CompareResult row) {
        if (row == null) {
            b.append("Unknown object");
            return;
        }
        String type = empty(row.getObjectType());
        String name = empty(row.getObjectName());
        if (type.length() > 0) {
            b.append(type).append(' ');
        }
        b.append(name.length() == 0 ? "<unnamed>" : name);
        String key = empty(row.getContextKeySummary());
        if (key.length() > 0) {
            b.append(" [Key / ID: ").append(key).append(']');
        }
    }

    private static String formatTime(long millis) {
        try {
            return new SimpleDateFormat(DATE_PATTERN, Locale.ENGLISH).format(new Date(millis));
        } catch (RuntimeException ex) {
            return Long.toString(millis);
        }
    }

    private static String formatDuration(long millis) {
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

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
