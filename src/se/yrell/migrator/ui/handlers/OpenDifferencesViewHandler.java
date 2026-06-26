package se.yrell.migrator.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import se.yrell.migrator.Activator;
import se.yrell.migrator.ui.perspectives.YrellMigratorPerspective;
import se.yrell.migrator.ui.views.DifferencesView;

/** Standard Eclipse command handler that opens the dedicated Yrell Migrator perspective/view. */
public final class OpenDifferencesViewHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                throw new ExecutionException("No active workbench window is available.");
            }
            IWorkbenchPage page = null;
            try {
                page = PlatformUI.getWorkbench().showPerspective(YrellMigratorPerspective.ID, window);
            } catch (Throwable perspectiveError) {
                Activator.logWarning("Could not switch to the Yrell Migrator perspective; opening the view in the current perspective instead.", perspectiveError);
                page = window.getActivePage();
            }
            if (page == null) {
                page = window.getActivePage();
            }
            if (page == null) {
                throw new ExecutionException("No active workbench page is available.");
            }
            page.showView(DifferencesView.ID);
            return null;
        } catch (ExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            Activator.logError("Could not open Yrell Migrator view from command.", ex);
            throw new ExecutionException("Could not open Yrell Migrator view", ex);
        }
    }
}
