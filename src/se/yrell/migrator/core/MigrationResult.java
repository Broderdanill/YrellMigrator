package se.yrell.migrator.core;

/** Result from migrating one object or catalog row. */
public final class MigrationResult {
    private final CompareResult compareResult;
    private final MigrationOutcome outcome;
    private final String detail;

    private MigrationResult(CompareResult compareResult, MigrationOutcome outcome, String detail) {
        this.compareResult = compareResult;
        this.outcome = outcome == null ? MigrationOutcome.UNKNOWN : outcome;
        this.detail = detail == null ? "" : detail;
    }

    public static MigrationResult success(CompareResult compareResult, boolean created, String detail) {
        return new MigrationResult(compareResult, created ? MigrationOutcome.CREATED : MigrationOutcome.UPDATED, detail);
    }

    public static MigrationResult verified(CompareResult compareResult, String detail) {
        return new MigrationResult(compareResult, MigrationOutcome.VERIFIED, detail);
    }

    public static MigrationResult warning(CompareResult compareResult, String detail) {
        return new MigrationResult(compareResult, MigrationOutcome.WARNING, detail);
    }

    public static MigrationResult stillDifferent(CompareResult compareResult, String detail) {
        return new MigrationResult(compareResult, MigrationOutcome.STILL_DIFFERENT, detail);
    }

    public static MigrationResult skipped(CompareResult compareResult, String detail) {
        return new MigrationResult(compareResult, MigrationOutcome.SKIPPED, detail);
    }

    public static MigrationResult failure(CompareResult compareResult, String detail) {
        return new MigrationResult(compareResult, MigrationOutcome.FAILED, detail);
    }

    public static MigrationResult cancelled(CompareResult compareResult, String detail) {
        return new MigrationResult(compareResult, MigrationOutcome.CANCELLED, detail);
    }

    public MigrationResult withVerification(CompareResult refreshedCompareResult, MigrationOutcome verifiedOutcome, String verifiedDetail) {
        return new MigrationResult(refreshedCompareResult == null ? compareResult : refreshedCompareResult,
                verifiedOutcome == null ? outcome : verifiedOutcome,
                verifiedDetail == null ? detail : verifiedDetail);
    }

    public static MigrationResult reclassified(MigrationResult original, CompareResult refreshedCompareResult,
            MigrationOutcome verifiedOutcome, String verifiedDetail) {
        if (original == null) {
            return new MigrationResult(refreshedCompareResult, verifiedOutcome, verifiedDetail);
        }
        return original.withVerification(refreshedCompareResult, verifiedOutcome, verifiedDetail);
    }

    public static MigrationResult appendDetail(MigrationResult original, String extraDetail) {
        if (original == null) {
            return warning(null, extraDetail);
        }
        String base = original.getDetail();
        String extra = extraDetail == null ? "" : extraDetail;
        String detail = base == null || base.length() == 0 ? extra : (extra.length() == 0 ? base : base + "\n" + extra);
        return original.withVerification(original.getCompareResult(), original.getOutcome(), detail);
    }

    public CompareResult getCompareResult() {
        return compareResult;
    }

    public MigrationOutcome getOutcome() {
        return outcome;
    }

    public String getOutcomeLabel() {
        return outcome.getLabel();
    }

    public boolean isSuccess() {
        return outcome.isSuccess();
    }

    public boolean isWarning() {
        return outcome.isWarning();
    }

    public boolean isCreated() {
        return outcome == MigrationOutcome.CREATED;
    }

    public String getDetail() {
        return detail;
    }
}
