package se.yrell.migrator.core;

/**
 * Reclassifies a successful migration result using the targeted post-migration
 * compare row. This keeps the UI result honest: a save can succeed while the
 * source and target are still different because of permissions, audit settings,
 * binary payloads, unsupported API members or delayed AR/Developer Studio caches.
 */
public final class MigrationVerifier {
    private MigrationVerifier() {
    }

    public static MigrationResult classify(MigrationResult original, CompareResult refreshed) {
        if (original == null) {
            return MigrationResult.warning(refreshed, "Post-migration verification had no migration result to classify.");
        }
        if (!original.isSuccess()) {
            return original;
        }
        if (refreshed == null) {
            return MigrationResult.warning(original.getCompareResult(), append(original.getDetail(),
                    "Post-migration verification did not return a comparison row."));
        }

        String verificationDetail = verificationDetail(refreshed);
        if (refreshed.getStatus() == CompareStatus.EQUAL) {
            return MigrationResult.reclassified(original, refreshed, original.getOutcome(), append(original.getDetail(),
                    "Post-migration verification: source and target are now equal."));
        }
        if (refreshed.getStatus() == CompareStatus.CHANGED || refreshed.getDifferenceCount() > 0) {
            return MigrationResult.reclassified(original, refreshed, MigrationOutcome.STILL_DIFFERENT, append(original.getDetail(),
                    "Post-migration verification: source and target are still different." + verificationDetail));
        }
        if (refreshed.getStatus() == CompareStatus.MISSING_IN_SOURCE || refreshed.getStatus() == CompareStatus.MISSING_IN_TARGET) {
            return MigrationResult.reclassified(original, refreshed, MigrationOutcome.WARNING, append(original.getDetail(),
                    "Post-migration verification could not find the object on both sides. Status: "
                            + refreshed.getStatus().getLabel() + "." + verificationDetail));
        }
        if (refreshed.getStatus() == CompareStatus.ERROR || refreshed.getStatus() == CompareStatus.UNKNOWN) {
            return MigrationResult.reclassified(original, refreshed, MigrationOutcome.WARNING, append(original.getDetail(),
                    "Post-migration verification was inconclusive. Status: " + refreshed.getStatus().getLabel() + "." + verificationDetail));
        }
        return MigrationResult.reclassified(original, refreshed, original.getOutcome(), append(original.getDetail(),
                "Post-migration verification completed. Status: " + refreshed.getStatus().getLabel() + "." + verificationDetail));
    }

    private static String verificationDetail(CompareResult refreshed) {
        if (refreshed == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        String detail = safe(refreshed.getDetail()).trim();
        if (detail.length() > 0) {
            b.append(" ").append(oneLine(detail));
        }
        if (refreshed.getDifferenceCount() > 0) {
            b.append(" Difference count: ").append(refreshed.getDifferenceCount()).append('.');
        }
        String summary = DiffSummaryAnalyzer.verificationSummary(refreshed);
        if (summary.length() > 0) {
            b.append(summary);
        }
        return b.toString();
    }

    private static String append(String first, String second) {
        String a = safe(first).trim();
        String b = safe(second).trim();
        if (a.length() == 0) {
            return b;
        }
        if (b.length() == 0) {
            return a;
        }
        return a + "\n" + b;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
