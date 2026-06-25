package se.yrell.migrator.ui.dialogs;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.DiffSummaryAnalyzer;
import se.yrell.migrator.core.MigrationOutcome;
import se.yrell.migrator.core.MigrationReportFormatter;
import se.yrell.migrator.core.MigrationReportFormatter.Summary;
import se.yrell.migrator.core.MigrationResult;

/** Shows a concise migration report after object or data migration. */
public final class MigrationReportDialog extends TitleAreaDialog {
    private static final int COPY_REPORT_ID = IDialogConstants.CLIENT_ID + 1;
    private static final int SAVE_REPORT_ID = IDialogConstants.CLIENT_ID + 2;
    private static final int DETAILS_ID = IDialogConstants.CLIENT_ID + 3;
    private static final int COMPARE_AGAIN_ID = IDialogConstants.CLIENT_ID + 4;
    private static final int RETRY_ID = IDialogConstants.CLIENT_ID + 5;

    private static final String FILTER_ALL = "all";
    private static final String FILTER_SUCCEEDED = "succeeded";
    private static final String FILTER_PROBLEMS = "problems";

    private final String title;
    private final String message;
    private final String report;
    private final boolean warnings;
    private final List<MigrationResult> objectResults;
    private final Summary summary;
    private final Runnable compareAgainAction;
    private final String compareAgainLabel;
    private final Runnable retryAction;
    private final String retryLabel;
    private Text reportText;
    private GridData reportTextLayoutData;
    private boolean detailsVisible;
    private Table objectTable;
    private Combo resultFilterCombo;
    private Color successBackground;
    private Color warningBackground;
    private Color failureBackground;

    public MigrationReportDialog(Shell parentShell, String title, String message, String report, boolean warnings) {
        this(parentShell, title, message, report, warnings, null, null);
    }

    public MigrationReportDialog(Shell parentShell, String title, String message, String report, boolean warnings,
            List<MigrationResult> objectResults, Summary summary) {
        this(parentShell, title, message, report, warnings, objectResults, summary, null, null);
    }

    public MigrationReportDialog(Shell parentShell, String title, String message, String report, boolean warnings,
            List<MigrationResult> objectResults, Summary summary, Runnable compareAgainAction, String compareAgainLabel) {
        this(parentShell, title, message, report, warnings, objectResults, summary,
                compareAgainAction, compareAgainLabel, null, null);
    }

    public MigrationReportDialog(Shell parentShell, String title, String message, String report, boolean warnings,
            List<MigrationResult> objectResults, Summary summary, Runnable compareAgainAction, String compareAgainLabel,
            Runnable retryAction, String retryLabel) {
        super(parentShell);
        this.title = title == null ? "Migration Report" : title;
        this.message = message == null ? "Migration completed." : message;
        this.report = report == null ? "" : report;
        this.warnings = warnings;
        this.objectResults = objectResults == null ? Collections.<MigrationResult>emptyList() : new ArrayList<MigrationResult>(objectResults);
        this.summary = summary;
        this.compareAgainAction = compareAgainAction;
        this.compareAgainLabel = compareAgainLabel == null || compareAgainLabel.length() == 0 ? "Compare Migrated Again" : compareAgainLabel;
        this.retryAction = retryAction;
        this.retryLabel = retryLabel == null || retryLabel.length() == 0 ? "Retry Problems" : retryLabel;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(title);
        setMessage(message, messageSeverity());
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        layout.verticalSpacing = 8;
        container.setLayout(layout);

        if (isObjectReport()) {
            createObjectSummary(container);
            createResultFilter(container);
            createObjectTable(container);
            createReportText(container, false);
        } else {
            createGenericSummary(container);
            createReportText(container, true);
        }
        return area;
    }

    private void createObjectSummary(Composite parent) {
        CLabel status = new CLabel(parent, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setImage(sharedImage(summary != null && summary.hasFailures()
                ? ISharedImages.IMG_OBJS_ERROR_TSK
                : (summary != null && summary.hasProblems() ? ISharedImages.IMG_OBJS_WARN_TSK : ISharedImages.IMG_OBJS_INFO_TSK)));
        status.setText(objectHeadline());

        Composite counts = new Composite(parent, SWT.NONE);
        counts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout countLayout = new GridLayout(7, true);
        countLayout.marginWidth = 0;
        countLayout.marginHeight = 0;
        counts.setLayout(countLayout);
        addCount(counts, "Succeeded", summary == null ? 0 : summary.getSucceeded());
        addCount(counts, "Failed", summary == null ? 0 : summary.getFailed());
        addCount(counts, "Created", summary == null ? 0 : summary.getCreated());
        addCount(counts, "Updated", summary == null ? 0 : summary.getUpdated());
        addCount(counts, "Still different", summary == null ? 0 : summary.getStillDifferent());
        addCount(counts, "Warnings", summary == null ? 0 : summary.getWarnings());
        addCount(counts, "Cancelled", summary == null ? 0 : summary.getCancelled());

        Label meta = new Label(parent, SWT.WRAP);
        meta.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        meta.setText(objectMetaText());
    }

    private void addCount(Composite parent, String label, int count) {
        CLabel item = new CLabel(parent, SWT.BORDER);
        item.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        item.setText(label + ": " + count);
        item.setImage(sharedImage(countImage(label, count)));
    }

    private void createResultFilter(Composite parent) {
        Composite filter = new Composite(parent, SWT.NONE);
        filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        filter.setLayout(layout);

        Label label = new Label(filter, SWT.NONE);
        label.setText("Show result rows:");

        resultFilterCombo = new Combo(filter, SWT.READ_ONLY | SWT.DROP_DOWN);
        GridData data = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        data.widthHint = 240;
        resultFilterCombo.setLayoutData(data);
        addFilterItem("All result rows", FILTER_ALL);
        addFilterItem("Succeeded", FILTER_SUCCEEDED);
        addFilterItem("Warnings / problems", FILTER_PROBLEMS);
        addOutcomeFilterItem(MigrationOutcome.FAILED);
        addOutcomeFilterItem(MigrationOutcome.CREATED);
        addOutcomeFilterItem(MigrationOutcome.UPDATED);
        addOutcomeFilterItem(MigrationOutcome.VERIFIED);
        addOutcomeFilterItem(MigrationOutcome.WARNING);
        addOutcomeFilterItem(MigrationOutcome.STILL_DIFFERENT);
        addOutcomeFilterItem(MigrationOutcome.SKIPPED);
        addOutcomeFilterItem(MigrationOutcome.CANCELLED);
        addOutcomeFilterItem(MigrationOutcome.UNKNOWN);
        resultFilterCombo.select(0);
        resultFilterCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                populateObjectTable();
            }
        });
    }

    private void addOutcomeFilterItem(MigrationOutcome outcome) {
        int count = countOutcome(outcome);
        if (count <= 0) {
            return;
        }
        addFilterItem(outcome.getLabel() + " (" + count + ")", outcome.name());
    }

    private void addFilterItem(String label, String key) {
        if (resultFilterCombo == null || resultFilterCombo.isDisposed()) {
            return;
        }
        resultFilterCombo.add(label);
        resultFilterCombo.setData("filter." + (resultFilterCombo.getItemCount() - 1), key);
    }

    private void createObjectTable(Composite parent) {
        objectTable = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        objectTable.setHeaderVisible(true);
        objectTable.setLinesVisible(true);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.widthHint = 820;
        data.heightHint = 240;
        objectTable.setLayoutData(data);

        addColumn(objectTable, "Result", 120, SWT.LEFT);
        addColumn(objectTable, "Object type", 135, SWT.LEFT);
        addColumn(objectTable, "Object name", 240, SWT.LEFT);
        addColumn(objectTable, "Reason", 260, SWT.LEFT);
        addColumn(objectTable, "Detail", 300, SWT.LEFT);
        populateObjectTable();
    }

    private void populateObjectTable() {
        if (objectTable == null || objectTable.isDisposed()) {
            return;
        }
        objectTable.setRedraw(false);
        try {
            objectTable.removeAll();
            for (MigrationResult result : objectResults) {
                if (result == null || !matchesResultFilter(result)) {
                    continue;
                }
                CompareResult row = result.getCompareResult();
                TableItem item = new TableItem(objectTable, SWT.NONE);
                item.setImage(sharedImage(outcomeImage(result.getOutcome())));
                item.setText(new String[] {
                        result.getOutcomeLabel(),
                        row == null ? "" : safe(row.getObjectType()),
                        row == null ? "" : safe(row.getObjectName()),
                        shorten(reasonFor(result)),
                        shorten(result.getDetail())
                });
                applyOutcomeColors(item, result);
                item.setData(result);
            }
        } finally {
            objectTable.setRedraw(true);
        }
    }

    private boolean matchesResultFilter(MigrationResult result) {
        String key = selectedResultFilterKey();
        if (FILTER_ALL.equals(key)) {
            return true;
        }
        if (FILTER_SUCCEEDED.equals(key)) {
            return result.isSuccess();
        }
        if (FILTER_PROBLEMS.equals(key)) {
            return result.isWarning() || !result.isSuccess();
        }
        try {
            return result.getOutcome() == MigrationOutcome.valueOf(key);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private String selectedResultFilterKey() {
        if (resultFilterCombo == null || resultFilterCombo.isDisposed()) {
            return FILTER_ALL;
        }
        int index = resultFilterCombo.getSelectionIndex();
        if (index < 0) {
            return FILTER_ALL;
        }
        Object value = resultFilterCombo.getData("filter." + index);
        return value == null ? FILTER_ALL : value.toString();
    }

    private int countOutcome(MigrationOutcome outcome) {
        int count = 0;
        for (MigrationResult result : objectResults) {
            if (result != null && result.getOutcome() == outcome) {
                count++;
            }
        }
        return count;
    }

    private void applyOutcomeColors(TableItem item, MigrationResult result) {
        if (item == null || item.isDisposed() || result == null) {
            return;
        }
        ensureOutcomeColors();
        MigrationOutcome outcome = result.getOutcome();
        if (outcome == MigrationOutcome.FAILED || outcome == MigrationOutcome.UNKNOWN || outcome == MigrationOutcome.CANCELLED) {
            item.setBackground(failureBackground);
            item.setForeground(objectTable.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        } else if (result.isWarning()) {
            item.setBackground(warningBackground);
            item.setForeground(objectTable.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));
        } else if (result.isSuccess()) {
            item.setBackground(successBackground);
            item.setForeground(objectTable.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
        }
    }

    private void ensureOutcomeColors() {
        if (successBackground != null && !successBackground.isDisposed()) {
            return;
        }
        successBackground = new Color(getShell().getDisplay(), 235, 248, 236);
        warningBackground = new Color(getShell().getDisplay(), 255, 246, 212);
        failureBackground = new Color(getShell().getDisplay(), 255, 230, 230);
    }

    private void createGenericSummary(Composite parent) {
        CLabel status = new CLabel(parent, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setImage(sharedImage(warnings ? ISharedImages.IMG_OBJS_WARN_TSK : ISharedImages.IMG_OBJS_INFO_TSK));
        status.setText(message);
    }

    private void createReportText(Composite parent, boolean visibleInitially) {
        reportText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        reportTextLayoutData = new GridData(SWT.FILL, SWT.FILL, true, true);
        reportTextLayoutData.widthHint = isObjectReport() ? 820 : 760;
        reportTextLayoutData.heightHint = isObjectReport() ? 230 : 380;
        reportTextLayoutData.exclude = !visibleInitially;
        reportText.setLayoutData(reportTextLayoutData);
        reportText.setVisible(visibleInitially);
        reportText.setText(report);
        detailsVisible = visibleInitially;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, COPY_REPORT_ID, "Copy Report", false);
        createButton(parent, SAVE_REPORT_ID, "Save...", false);
        if (isObjectReport()) {
            createButton(parent, DETAILS_ID, "Details...", false);
            if (retryAction != null && hasRetryableRows()) {
                createButton(parent, RETRY_ID, retryLabel, false);
            }
            if (compareAgainAction != null) {
                createButton(parent, COMPARE_AGAIN_ID, compareAgainLabel, false);
            }
        }
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == COPY_REPORT_ID) {
            copyReportToClipboard();
            return;
        }
        if (buttonId == SAVE_REPORT_ID) {
            saveReportToFile();
            return;
        }
        if (buttonId == DETAILS_ID) {
            toggleDetails();
            return;
        }
        if (buttonId == RETRY_ID) {
            Runnable action = retryAction;
            close();
            if (action != null) {
                action.run();
            }
            return;
        }
        if (buttonId == COMPARE_AGAIN_ID) {
            Runnable action = compareAgainAction;
            close();
            if (action != null) {
                action.run();
            }
            return;
        }
        super.buttonPressed(buttonId);
    }

    @Override
    public boolean close() {
        disposeColor(successBackground);
        disposeColor(warningBackground);
        disposeColor(failureBackground);
        successBackground = null;
        warningBackground = null;
        failureBackground = null;
        return super.close();
    }

    private void disposeColor(Color color) {
        if (color != null && !color.isDisposed()) {
            color.dispose();
        }
    }

    private boolean isObjectReport() {
        return !objectResults.isEmpty() && summary != null;
    }

    private boolean hasRetryableRows() {
        for (MigrationResult result : objectResults) {
            if (isRetryable(result)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRetryable(MigrationResult result) {
        if (result == null) {
            return false;
        }
        MigrationOutcome outcome = result.getOutcome();
        return outcome == MigrationOutcome.FAILED
                || outcome == MigrationOutcome.UNKNOWN
                || outcome == MigrationOutcome.WARNING
                || outcome == MigrationOutcome.STILL_DIFFERENT
                || outcome == MigrationOutcome.CANCELLED;
    }

    private int messageSeverity() {
        if (summary != null && summary.hasFailures()) {
            return IMessageProvider.ERROR;
        }
        if (warnings || (summary != null && summary.hasProblems())) {
            return IMessageProvider.WARNING;
        }
        return IMessageProvider.INFORMATION;
    }

    private String objectHeadline() {
        if (summary == null) {
            return message;
        }
        if (summary.hasFailures()) {
            return "Migration failed for " + summary.getFailed() + " of " + summary.getTotal() + " result row(s).";
        }
        if (summary.getCancelled() > 0) {
            return "Migration cancelled. " + summary.getTotal() + " result row(s) are included in this partial report.";
        }
        if (summary.hasProblems()) {
            return "Migration completed with " + summary.getWarnings() + " warning(s).";
        }
        return "Migration completed successfully.";
    }

    private String objectMetaText() {
        if (summary == null) {
            return "";
        }
        String direction = summary.getDirection() == null ? "<unknown direction>" : summary.getDirection().getLabel();
        return summary.getScope() + " · " + direction + " · Duration: " + summary.getDurationLabel();
    }

    private void toggleDetails() {
        if (reportText == null || reportText.isDisposed() || reportTextLayoutData == null) {
            return;
        }
        detailsVisible = !detailsVisible;
        reportTextLayoutData.exclude = !detailsVisible;
        reportText.setVisible(detailsVisible);
        org.eclipse.swt.widgets.Button button = getButton(DETAILS_ID);
        if (button != null && !button.isDisposed()) {
            button.setText(detailsVisible ? "Hide Details" : "Details...");
        }
        getShell().layout(true, true);
        getShell().pack(true);
    }

    private void addColumn(Table table, String title, int width, int alignment) {
        TableColumn column = new TableColumn(table, alignment);
        column.setText(title);
        column.setWidth(width);
    }

    private String countImage(String label, int count) {
        if ("Failed".equals(label) && count > 0) {
            return ISharedImages.IMG_OBJS_ERROR_TSK;
        }
        if (("Warnings".equals(label) || "Still different".equals(label) || "Cancelled".equals(label)) && count > 0) {
            return ISharedImages.IMG_OBJS_WARN_TSK;
        }
        return ISharedImages.IMG_OBJS_INFO_TSK;
    }

    private String outcomeImage(MigrationOutcome outcome) {
        if (outcome == MigrationOutcome.FAILED || outcome == MigrationOutcome.UNKNOWN) {
            return ISharedImages.IMG_OBJS_ERROR_TSK;
        }
        if (outcome == MigrationOutcome.WARNING || outcome == MigrationOutcome.SKIPPED
                || outcome == MigrationOutcome.STILL_DIFFERENT || outcome == MigrationOutcome.CANCELLED) {
            return ISharedImages.IMG_OBJS_WARN_TSK;
        }
        return ISharedImages.IMG_OBJS_INFO_TSK;
    }

    private Image sharedImage(String key) {
        try {
            return PlatformUI.getWorkbench().getSharedImages().getImage(key);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String reasonFor(MigrationResult result) {
        if (result == null) {
            return "";
        }
        CompareResult row = result.getCompareResult();
        String cause = DiffSummaryAnalyzer.shortCause(row);
        if (cause.length() > 0 && (result.getOutcome() == MigrationOutcome.STILL_DIFFERENT || result.isWarning())) {
            return cause;
        }
        if (result.getOutcome() == MigrationOutcome.CREATED) {
            return "Created in target";
        }
        if (result.getOutcome() == MigrationOutcome.UPDATED) {
            return "Updated and verified";
        }
        if (result.getOutcome() == MigrationOutcome.VERIFIED) {
            return "Already equal / verified";
        }
        if (result.getOutcome() == MigrationOutcome.FAILED) {
            return "Migration failed";
        }
        if (result.getOutcome() == MigrationOutcome.CANCELLED) {
            return "Cancelled by user";
        }
        return cause;
    }

    private String shorten(String text) {
        String value = safe(text).replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= 180 ? value : value.substring(0, 177) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void copyReportToClipboard() {
        Clipboard clipboard = null;
        try {
            clipboard = new Clipboard(getShell().getDisplay());
            clipboard.setContents(new Object[] { report }, new Transfer[] { TextTransfer.getInstance() });
            setMessage("Report copied to clipboard.", IMessageProvider.INFORMATION);
        } catch (RuntimeException ex) {
            MessageDialog.openError(getShell(), "Copy Report", "Could not copy the report to clipboard: " + safeMessage(ex));
        } finally {
            if (clipboard != null) {
                clipboard.dispose();
            }
        }
    }

    private void saveReportToFile() {
        FileDialog dialog = new FileDialog(getShell(), SWT.SAVE);
        dialog.setText("Save Migration Report");
        dialog.setFileName(defaultReportFileName());
        dialog.setFilterExtensions(new String[] { "*.txt", "*.*" });
        String path = dialog.open();
        if (path == null || path.trim().length() == 0) {
            return;
        }
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8);
            writer.write(report);
            writer.flush();
            setMessage("Report saved to " + path, IMessageProvider.INFORMATION);
        } catch (Exception ex) {
            MessageDialog.openError(getShell(), "Save Report", "Could not save the report: " + safeMessage(ex));
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String defaultReportFileName() {
        String cleaned = title.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (cleaned.length() == 0) {
            cleaned = "yrell-migrator-report";
        }
        return cleaned + ".txt";
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }
}
