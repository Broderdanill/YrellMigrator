package se.yrell.migrator.ui.dialogs;

import java.util.List;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.bmc.arsys.studio.model.store.IStore;

import se.yrell.migrator.bmc.BmcDataMigrator;
import se.yrell.migrator.bmc.BmcDataMigrator.AttachmentPolicy;
import se.yrell.migrator.bmc.BmcDataMigrator.ConflictMode;

/** Collects form-data migration options. */
public final class DataMigrationOptionsDialog extends TitleAreaDialog {
    private final String formName;
    private final IStore sourceStore;
    private final List<IStore> targetStores;
    private final IStore fixedTargetStore;

    private ComboViewer targetViewer;
    private Text maxRowsText;
    private Text qualificationText;
    private ComboViewer conflictViewer;
    private Button includeAttachmentsButton;
    private Button filterTargetFieldsButton;
    private Button runWorkflowButton;
    private Button dryRunButton;

    private BmcDataMigrator.Options options;

    public DataMigrationOptionsDialog(Shell parentShell, String formName, IStore sourceStore, List<IStore> targetStores) {
        super(parentShell);
        this.formName = formName;
        this.sourceStore = sourceStore;
        this.targetStores = targetStores;
        this.fixedTargetStore = null;
    }

    public DataMigrationOptionsDialog(Shell parentShell, String formName, IStore sourceStore, IStore targetStore) {
        super(parentShell);
        this.formName = formName;
        this.sourceStore = sourceStore;
        this.fixedTargetStore = targetStore;
        this.targetStores = null;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Migrate Form Data");
        setMessage("Choose how data entries should be copied from source to target.");

        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        layout.horizontalSpacing = 10;
        layout.verticalSpacing = 8;
        container.setLayout(layout);

        label(container, "Form:");
        Label formLabel = new Label(container, SWT.NONE);
        formLabel.setText(formName == null ? "" : formName);
        formLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        label(container, "Source:");
        Label sourceLabel = new Label(container, SWT.NONE);
        sourceLabel.setText(sourceStore == null ? "" : sourceStore.getName());
        sourceLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        label(container, "Target:");
        if (fixedTargetStore != null) {
            Label targetLabel = new Label(container, SWT.NONE);
            targetLabel.setText(fixedTargetStore.getName());
            targetLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        } else {
            targetViewer = new ComboViewer(container, SWT.READ_ONLY | SWT.BORDER);
            Combo combo = targetViewer.getCombo();
            combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            targetViewer.setLabelProvider(new LabelProvider() {
                @Override
                public String getText(Object element) {
                    if (element instanceof IStore) {
                        IStore store = (IStore) element;
                        return store.getName() + "  (" + store.getUser() + ")";
                    }
                    return super.getText(element);
                }
            });
            targetViewer.add(targetStores == null ? new Object[0] : targetStores.toArray(new IStore[targetStores.size()]));
            if (targetStores != null && !targetStores.isEmpty()) {
                targetViewer.setSelection(new StructuredSelection(targetStores.get(0)));
            }
        }

        label(container, "Max rows:");
        maxRowsText = new Text(container, SWT.BORDER);
        maxRowsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        maxRowsText.setMessage("0 or blank = all rows");

        label(container, "Qualification:");
        qualificationText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData qd = new GridData(SWT.FILL, SWT.FILL, true, true);
        qd.heightHint = 70;
        qualificationText.setLayoutData(qd);
        qualificationText.setMessage("Optional AR qualification, for example 'Status' = \"Enabled\"");

        label(container, "Key strategy:");
        Label keyLabel = new Label(container, SWT.NONE);
        keyLabel.setText("Request ID / Entry ID");
        keyLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        label(container, "On Request ID conflict:");
        conflictViewer = new ComboViewer(container, SWT.READ_ONLY | SWT.BORDER);
        conflictViewer.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        conflictViewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof ConflictMode ? ((ConflictMode) element).getLabel() : super.getText(element);
            }
        });
        conflictViewer.add((Object[]) ConflictMode.values());
        conflictViewer.setSelection(new StructuredSelection(ConflictMode.PRESERVE_ID_OVERWRITE));

        label(container, "Field safety:");
        Composite fieldSafety = new Composite(container, SWT.NONE);
        fieldSafety.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout fieldLayout = new GridLayout(1, false);
        fieldLayout.marginWidth = 0;
        fieldLayout.marginHeight = 0;
        fieldSafety.setLayout(fieldLayout);
        filterTargetFieldsButton = new Button(fieldSafety, SWT.CHECK);
        filterTargetFieldsButton.setText("Send only writable target entry fields when field metadata is available");
        filterTargetFieldsButton.setSelection(true);
        filterTargetFieldsButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        includeAttachmentsButton = new Button(fieldSafety, SWT.CHECK);
        includeAttachmentsButton.setText("Include attachment fields if the AR API exposes them");
        includeAttachmentsButton.setSelection(false);
        includeAttachmentsButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        label(container, "Workflow:");
        runWorkflowButton = new Button(container, SWT.CHECK);
        runWorkflowButton.setText("Run workflow during merge");
        runWorkflowButton.setSelection(false);
        runWorkflowButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        label(container, "Preview:");
        dryRunButton = new Button(container, SWT.CHECK);
        dryRunButton.setText("Dry run only - count/read rows without writing to target");
        dryRunButton.setSelection(true);
        dryRunButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label warning = new Label(container, SWT.WRAP);
        warning.setText("Default is dry run. Write mode now preflights row scope, key strategy, attachment policy and sampled field values before target rows are touched.");
        GridData wd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        wd.widthHint = 520;
        warning.setLayoutData(wd);

        return area;
    }

    @Override
    protected void okPressed() {
        IStore target = fixedTargetStore;
        if (target == null && targetViewer != null) {
            IStructuredSelection selection = (IStructuredSelection) targetViewer.getSelection();
            Object first = selection == null ? null : selection.getFirstElement();
            if (first instanceof IStore) {
                target = (IStore) first;
            }
        }
        if (target == null) {
            setErrorMessage("Choose a target environment.");
            return;
        }
        int maxRows = parseMaxRows();
        if (maxRows < 0) {
            setErrorMessage("Max rows must be blank, 0, or a positive number.");
            return;
        }
        IStructuredSelection conflictSelection = (IStructuredSelection) conflictViewer.getSelection();
        Object conflict = conflictSelection == null ? null : conflictSelection.getFirstElement();

        options = new BmcDataMigrator.Options();
        options.setSourceStore(sourceStore);
        options.setTargetStore(target);
        options.setFormName(formName);
        options.setMaxRows(maxRows);
        options.setQualification(qualificationText.getText());
        options.setConflictMode(conflict instanceof ConflictMode ? (ConflictMode) conflict : ConflictMode.PRESERVE_ID_OVERWRITE);
        options.setAttachmentPolicy(includeAttachmentsButton != null && includeAttachmentsButton.getSelection()
                ? AttachmentPolicy.INCLUDE_ATTACHMENTS : AttachmentPolicy.SKIP_ATTACHMENTS);
        options.setFilterToTargetWritableFields(filterTargetFieldsButton == null || filterTargetFieldsButton.getSelection());
        options.setRunWorkflow(runWorkflowButton.getSelection());
        options.setDryRun(dryRunButton != null && dryRunButton.getSelection());
        super.okPressed();
    }

    public BmcDataMigrator.Options getOptions() {
        return options;
    }

    private int parseMaxRows() {
        String text = maxRowsText.getText();
        if (text == null || text.trim().length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void label(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }
}
