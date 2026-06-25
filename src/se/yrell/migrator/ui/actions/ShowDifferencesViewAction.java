package se.yrell.migrator.ui.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import se.yrell.migrator.Activator;
import se.yrell.migrator.ui.views.DifferencesView;

/** Opens the Yrell Migrator view. */
public final class ShowDifferencesViewAction extends Action {
    public ShowDifferencesViewAction() {
        setText("Open Yrell Migrator view");
    }

    @Override
    public void run() {
        try {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            page.showView(DifferencesView.ID);
        } catch (Exception ex) {
            Activator.logError("Could not open Yrell Migrator view.", ex);
        }
    }
}
