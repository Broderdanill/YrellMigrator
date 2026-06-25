package se.yrell.migrator.ui.dialogs;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import se.yrell.migrator.Activator;
import se.yrell.migrator.diagnostics.DiagnosticsReport;

/** Shows a local diagnostics report that can be copied into bug reports. */
public final class DiagnosticsDialog extends TitleAreaDialog {
    private static final int COPY_ID = IDialogConstants.CLIENT_ID + 1;
    private static final int COPY_SANITIZED_ID = IDialogConstants.CLIENT_ID + 2;
    private Text reportText;

    public DiagnosticsDialog(Shell parentShell) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Yrell Migrator Diagnostics");
        setMessage("Local runtime, Developer Studio, settings and cache information. No AR server is contacted.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        container.setLayout(layout);

        reportText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.widthHint = 760;
        data.heightHint = 480;
        reportText.setLayoutData(data);

        Label note = new Label(container, SWT.WRAP);
        note.setText("Tip: use Copy sanitized for tickets outside your secure environment. Copy full includes local paths that can help internal troubleshooting.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        refreshReport();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, COPY_SANITIZED_ID, "Copy sanitized", false);
        createButton(parent, COPY_ID, "Copy full", false);
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == COPY_ID) {
            copyToClipboard(false);
            return;
        }
        if (buttonId == COPY_SANITIZED_ID) {
            copyToClipboard(true);
            return;
        }
        super.buttonPressed(buttonId);
    }

    private void refreshReport() {
        String report;
        try {
            report = DiagnosticsReport.create();
        } catch (Throwable ex) {
            Activator.logError("Could not create Yrell Migrator diagnostics report.", ex);
            report = "Could not create diagnostics report: " + safeMessage(ex);
        }
        if (reportText != null && !reportText.isDisposed()) {
            reportText.setText(report == null ? "" : report);
        }
    }

    private void copyToClipboard(boolean sanitized) {
        if (reportText == null || reportText.isDisposed()) {
            return;
        }
        Clipboard clipboard = null;
        try {
            String text = sanitized ? DiagnosticsReport.sanitize(reportText.getText()) : reportText.getText();
            clipboard = new Clipboard(getShell().getDisplay());
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
            MessageDialog.openInformation(getShell(), "Diagnostics copied", sanitized
                    ? "A sanitized diagnostics report was copied to the clipboard."
                    : "The full diagnostics report was copied to the clipboard.");
        } catch (Throwable ex) {
            Activator.logError("Could not copy Yrell Migrator diagnostics report to clipboard.", ex);
            MessageDialog.openError(getShell(), "Copy failed", "Could not copy diagnostics report: " + safeMessage(ex));
        } finally {
            if (clipboard != null) {
                clipboard.dispose();
            }
        }
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }
}
