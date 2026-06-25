package se.yrell.migrator.ui.dialogs;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.bmc.arsys.studio.model.store.IStore;

import se.yrell.migrator.bmc.BmcFormDataCsvExporter;

/** Collects options for exporting form entries to CSV. */
public final class FormDataExportOptionsDialog extends TitleAreaDialog {
    public static final class StoreChoice {
        private final String label;
        private final IStore store;

        public StoreChoice(String label, IStore store) {
            this.label = label;
            this.store = store;
        }

        public String getLabel() { return label; }
        public IStore getStore() { return store; }
    }

    private final String formName;
    private final List<StoreChoice> storeChoices;

    private ComboViewer environmentViewer;
    private Text maxRowsText;
    private Text qualificationText;
    private Text delimiterText;

    private BmcFormDataCsvExporter.Options options;

    public FormDataExportOptionsDialog(Shell parentShell, String formName, List<StoreChoice> storeChoices) {
        super(parentShell);
        this.formName = formName;
        this.storeChoices = storeChoices == null ? new ArrayList<StoreChoice>() : storeChoices;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Export Form Data to CSV");
        setMessage("Choose which environment and which form entries should be exported.");

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

        label(container, "Environment:");
        environmentViewer = new ComboViewer(container, SWT.READ_ONLY | SWT.BORDER);
        Combo environmentCombo = environmentViewer.getCombo();
        environmentCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        environmentViewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof StoreChoice) {
                    StoreChoice choice = (StoreChoice) element;
                    IStore store = choice.getStore();
                    String storeText = store == null ? "" : store.getName();
                    return choice.getLabel() + " - " + storeText;
                }
                return super.getText(element);
            }
        });
        environmentViewer.add((Object[]) storeChoices.toArray(new StoreChoice[storeChoices.size()]));
        if (!storeChoices.isEmpty()) {
            environmentViewer.setSelection(new StructuredSelection(storeChoices.get(0)));
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

        label(container, "Delimiter:");
        delimiterText = new Text(container, SWT.BORDER);
        delimiterText.setText(";");
        delimiterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        delimiterText.setMessage("; , or \\t");

        Label note = new Label(container, SWT.WRAP);
        note.setText("CSV is written as UTF-8. Default delimiter is semicolon, which matches Swedish CSV/Excel conventions.");
        GridData nd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        nd.widthHint = 520;
        note.setLayoutData(nd);

        return area;
    }

    @Override
    protected void okPressed() {
        IStructuredSelection selection = (IStructuredSelection) environmentViewer.getSelection();
        Object first = selection == null ? null : selection.getFirstElement();
        if (!(first instanceof StoreChoice) || ((StoreChoice) first).getStore() == null) {
            setErrorMessage("Choose an environment to export from.");
            return;
        }
        int maxRows = parseMaxRows();
        if (maxRows < 0) {
            setErrorMessage("Max rows must be blank, 0, or a positive number.");
            return;
        }
        String delimiter = delimiterText.getText();
        if (delimiter == null || delimiter.trim().length() == 0) {
            setErrorMessage("Delimiter cannot be blank. Use ;, , or \\t.");
            return;
        }

        options = new BmcFormDataCsvExporter.Options();
        options.setStore(((StoreChoice) first).getStore());
        options.setFormName(formName);
        options.setMaxRows(maxRows);
        options.setQualification(qualificationText.getText());
        options.setDelimiter(delimiter);
        super.okPressed();
    }

    public BmcFormDataCsvExporter.Options getOptions() {
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
