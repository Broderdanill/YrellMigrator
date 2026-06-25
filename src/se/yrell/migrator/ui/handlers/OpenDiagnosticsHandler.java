package se.yrell.migrator.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import se.yrell.migrator.Activator;
import se.yrell.migrator.ui.dialogs.DiagnosticsDialog;

/** Opens the Yrell Migrator diagnostics dialog from the main menu. */
public final class OpenDiagnosticsHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            Shell shell = getShell();
            new DiagnosticsDialog(shell).open();
            return null;
        } catch (Exception ex) {
            Activator.logError("Could not open Yrell Migrator diagnostics dialog.", ex);
            throw new ExecutionException("Could not open Yrell Migrator diagnostics", ex);
        }
    }

    private Shell getShell() {
        IWorkbenchWindow window = null;
        try {
            window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        } catch (Throwable ignored) {
        }
        if (window != null && window.getShell() != null) {
            return window.getShell();
        }
        try {
            return PlatformUI.getWorkbench().getDisplay().getActiveShell();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
