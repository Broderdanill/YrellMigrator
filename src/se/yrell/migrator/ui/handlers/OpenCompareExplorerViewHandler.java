package se.yrell.migrator.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import se.yrell.migrator.Activator;
import se.yrell.migrator.ui.views.DifferencesView;

/**
 * Backwards-compatible command handler for the original openExplorer command.
 * The registered view id is unchanged, but it now opens the consolidated Yrell Migrator view.
 */
public final class OpenCompareExplorerViewHandler extends AbstractHandler {
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            page.showView(DifferencesView.ID);
            return null;
        } catch (Exception ex) {
            Activator.logError("Could not open Yrell Migrator view from legacy command.", ex);
            throw new ExecutionException("Could not open Yrell Migrator", ex);
        }
    }
}
