package se.yrell.migrator.core;

/** One executable row in a definition/catalog migration plan. */
public final class MigrationPlanStep {
    public enum Action {
        CREATE("Create"),
        UPDATE("Update"),
        OVERWRITE("Overwrite"),
        VERIFY("Verify"),
        UNKNOWN("Migrate");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final int number;
    private final CompareResult row;
    private final Action action;
    private final String phase;
    private final String note;

    MigrationPlanStep(int number, CompareResult row, Action action, String phase, String note) {
        this.number = number;
        this.row = row;
        this.action = action == null ? Action.UNKNOWN : action;
        this.phase = noteToEmpty(phase).length() == 0 ? "Objects" : phase;
        this.note = noteToEmpty(note);
    }

    public int getNumber() {
        return number;
    }

    public CompareResult getRow() {
        return row;
    }

    public Action getAction() {
        return action;
    }

    public String getPhase() {
        return phase;
    }

    public String getNote() {
        return note;
    }

    public String getObjectType() {
        return row == null ? "" : noteToEmpty(row.getObjectType());
    }

    public String getObjectName() {
        return row == null ? "" : noteToEmpty(row.getObjectName());
    }

    public String getContextKey(MigrationDirection direction) {
        if (row == null) {
            return "";
        }
        String key = direction == MigrationDirection.TARGET_TO_SOURCE ? row.getTargetContextKey() : row.getSourceContextKey();
        if (key == null || key.trim().length() == 0) {
            key = row.getContextKeySummary();
        }
        return noteToEmpty(key).trim();
    }

    public String getPrimaryForm(MigrationDirection direction) {
        if (row == null) {
            return "";
        }
        String form = direction == MigrationDirection.TARGET_TO_SOURCE ? row.getTargetPrimaryForm() : row.getSourcePrimaryForm();
        if (form == null || form.trim().length() == 0) {
            form = row.getPrimaryFormSummary();
        }
        return noteToEmpty(form).trim();
    }

    private static String noteToEmpty(String value) {
        return value == null ? "" : value;
    }
}
