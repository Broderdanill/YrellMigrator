package se.yrell.migrator.ui.dialogs;

import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.bmc.BmcContainerContentMigrator;
import se.yrell.migrator.bmc.BmcContainerContentMigrator.ContainerContentPreview;
import se.yrell.migrator.core.MigrationPlan;

/** Confirmation dialog for the dry-run migration plan. */
public final class MigrationPlanDialog extends TitleAreaDialog {
    private static final int COPY_PLAN_ID = IDialogConstants.CLIENT_ID + 1;
    private static final int DETAILS_ID = IDialogConstants.CLIENT_ID + 2;

    private final MigrationPlan plan;
    private Button includeContainerContentButton;
    private boolean includeContainerContent;
    private Text planText;
    private GridData detailsLayoutData;
    private boolean detailsVisible;

    public MigrationPlanDialog(Shell parentShell, MigrationPlan plan) {
        super(parentShell);
        this.plan = plan;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        boolean hasWarnings = plan != null && plan.hasWarnings();
        setTitle("Migration Plan");
        setMessage(hasWarnings
                ? "The plan is ready, but has warnings. Review the summary before starting."
                : "The plan is ready. Use Details if you need the technical dry-run text.",
                hasWarnings ? IMessageProvider.WARNING : IMessageProvider.INFORMATION);
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        layout.verticalSpacing = 8;
        container.setLayout(layout);

        createSummary(container);
        createPhaseTable(container);
        createWarningSummary(container);
        createContainerPreview(container);
        createContainerOption(container);
        createTechnicalDetails(container);
        return area;
    }

    private void createSummary(Composite parent) {
        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(2, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        header.setLayout(layout);

        CLabel summary = new CLabel(header, SWT.NONE);
        summary.setImage(sharedImage(plan != null && plan.hasWarnings() ? ISharedImages.IMG_OBJS_WARN_TSK : ISharedImages.IMG_OBJS_INFO_TSK));
        summary.setText(summaryText());
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label direction = new Label(parent, SWT.WRAP);
        direction.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        direction.setText(directionText());
    }

    private void createPhaseTable(Composite parent) {
        Table table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, false);
        data.widthHint = 720;
        data.heightHint = 160;
        table.setLayoutData(data);

        TableColumn phaseColumn = new TableColumn(table, SWT.LEFT);
        phaseColumn.setText("Phase");
        phaseColumn.setWidth(430);
        TableColumn countColumn = new TableColumn(table, SWT.RIGHT);
        countColumn.setText("Objects");
        countColumn.setWidth(120);
        TableColumn noteColumn = new TableColumn(table, SWT.LEFT);
        noteColumn.setText("Status");
        noteColumn.setWidth(160);

        if (plan == null || plan.getPhaseCounts().isEmpty()) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setImage(sharedImage(ISharedImages.IMG_OBJS_ERROR_TSK));
            item.setText(new String[] { "No executable steps", "0", "Nothing to migrate" });
            return;
        }
        for (Map.Entry<String, Integer> entry : plan.getPhaseCounts().entrySet()) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setImage(sharedImage(phaseImage(entry.getKey())));
            item.setText(new String[] { entry.getKey(), String.valueOf(entry.getValue()), phaseStatus(entry.getKey()) });
        }
    }

    private void createWarningSummary(Composite parent) {
        if (plan == null || !plan.hasWarnings()) {
            CLabel ok = new CLabel(parent, SWT.NONE);
            ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            ok.setImage(sharedImage(ISharedImages.IMG_OBJS_INFO_TSK));
            ok.setText("No dependency warnings were found in the selected plan.");
            return;
        }
        CLabel warning = new CLabel(parent, SWT.WRAP);
        warning.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        warning.setImage(sharedImage(ISharedImages.IMG_OBJS_WARN_TSK));
        warning.setText(warningText());
    }


    private void createContainerPreview(Composite parent) {
        if (plan == null || !plan.containsContainer()) {
            return;
        }
        Group group = new Group(parent, SWT.NONE);
        group.setText("Application / Packing List content preview");
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        group.setLayout(new GridLayout(1, false));

        Text previewText = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.heightHint = 88;
        previewText.setLayoutData(data);
        previewText.setText(containerPreviewText());
    }

    private String containerPreviewText() {
        StringBuilder b = new StringBuilder();
        BmcContainerContentMigrator previewer = new BmcContainerContentMigrator(null);
        int containers = 0;
        for (CompareResult row : plan.getOrderedRows()) {
            if (row == null) {
                continue;
            }
            String type = row.getObjectType() == null ? "" : row.getObjectType().toLowerCase(java.util.Locale.ENGLISH).replace(" ", "");
            if (type.indexOf("application") < 0 && type.indexOf("packinglist") < 0) {
                continue;
            }
            containers++;
            if (containers > 1) {
                b.append('\n');
            }
            b.append(row.getObjectType()).append(" ").append(row.getObjectName()).append(": ");
            ContainerContentPreview preview = previewer.previewContent(row, plan.getDirection());
            b.append(preview.toSummary());
        }
        if (containers == 0) {
            return "No container rows were found in this plan.";
        }
        b.append("\n\nContent migration is best-effort and runs after the container itself has been migrated. You can clear the checkbox below to migrate only the container definition.");
        return b.toString();
    }

    private void createContainerOption(Composite parent) {
        if (plan != null && plan.containsContainer()) {
            includeContainerContentButton = new Button(parent, SWT.CHECK);
            includeContainerContentButton.setText("Also migrate discoverable Application / Packing List content");
            includeContainerContentButton.setToolTipText("Best-effort migration of content that BMC exposes from the container. The container definition itself is always included. Packing List content is enabled by default.");
            includeContainerContentButton.setSelection(containsPackingList(plan));
        }
    }

    private void createTechnicalDetails(Composite parent) {
        planText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        detailsLayoutData = new GridData(SWT.FILL, SWT.FILL, true, true);
        detailsLayoutData.widthHint = 820;
        detailsLayoutData.heightHint = 260;
        detailsLayoutData.exclude = true;
        planText.setLayoutData(detailsLayoutData);
        planText.setVisible(false);
        planText.setText(plan == null ? "No migration plan was generated." : plan.toReport(false));
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, COPY_PLAN_ID, "Copy Plan", false);
        createButton(parent, DETAILS_ID, "Details...", false);
        createButton(parent, IDialogConstants.OK_ID, "Migrate", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == COPY_PLAN_ID) {
            copyPlanToClipboard();
            return;
        }
        if (buttonId == DETAILS_ID) {
            toggleDetails();
            return;
        }
        if (buttonId == IDialogConstants.OK_ID) {
            includeContainerContent = includeContainerContentButton != null && includeContainerContentButton.getSelection();
        }
        super.buttonPressed(buttonId);
    }

    public boolean isIncludeContainerContent() {
        return includeContainerContent;
    }

    private boolean containsPackingList(MigrationPlan plan) {
        if (plan == null) {
            return false;
        }
        for (CompareResult row : plan.getOrderedRows()) {
            String type = row == null ? "" : row.getObjectType();
            String normalized = type == null ? "" : type.toLowerCase(java.util.Locale.ENGLISH).replace(" ", "");
            if (normalized.indexOf("packinglist") >= 0) {
                return true;
            }
        }
        return false;
    }

    private void toggleDetails() {
        if (planText == null || planText.isDisposed() || detailsLayoutData == null) {
            return;
        }
        detailsVisible = !detailsVisible;
        detailsLayoutData.exclude = !detailsVisible;
        planText.setVisible(detailsVisible);
        Button button = getButton(DETAILS_ID);
        if (button != null && !button.isDisposed()) {
            button.setText(detailsVisible ? "Hide Details" : "Details...");
        }
        getShell().layout(true, true);
        getShell().pack(true);
    }

    private String summaryText() {
        if (plan == null) {
            return "No migration plan was generated.";
        }
        StringBuilder b = new StringBuilder();
        b.append(plan.getPlannedCount()).append(" object(s) will be migrated");
        if (plan.getSkippedCount() > 0) {
            b.append(" · ").append(plan.getSkippedCount()).append(" skipped");
        }
        if (plan.getWarnings().size() > 0) {
            b.append(" · ").append(plan.getWarnings().size()).append(" warning(s)");
        }
        return b.toString();
    }

    private String directionText() {
        if (plan == null) {
            return "Source and target could not be resolved.";
        }
        String source = plan.getSourceName().length() == 0 ? "<unknown>" : plan.getSourceName();
        String target = plan.getTargetName().length() == 0 ? "<unknown>" : plan.getTargetName();
        return source + "  →  " + target;
    }

    private String warningText() {
        StringBuilder b = new StringBuilder();
        if (plan.getSkippedCount() > 0) {
            b.append(plan.getSkippedCount()).append(" selected row(s) will be skipped. ");
        }
        if (!plan.getWarnings().isEmpty()) {
            b.append(plan.getWarnings().size()).append(" dependency/binary warning(s). ");
            b.append("Use Details or Copy Plan for the full list.");
        }
        return b.toString();
    }

    private String phaseStatus(String phase) {
        if (phase != null && phase.toLowerCase(java.util.Locale.ENGLISH).indexOf("support") >= 0) {
            return "Best-effort";
        }
        return "Ready";
    }

    private String phaseImage(String phase) {
        if (phase != null && phase.toLowerCase(java.util.Locale.ENGLISH).indexOf("support") >= 0) {
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

    private void copyPlanToClipboard() {
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] { plan == null ? "" : plan.toReport(true) }, new TextTransfer[] { TextTransfer.getInstance() });
            setMessage("Plan copied to clipboard.", IMessageProvider.INFORMATION);
        } finally {
            clipboard.dispose();
        }
    }
}
