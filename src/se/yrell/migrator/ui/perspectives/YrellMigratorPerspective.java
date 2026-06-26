package se.yrell.migrator.ui.perspectives;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

import se.yrell.migrator.ui.views.DifferencesView;

/**
 * Dedicated Developer Studio perspective for Yrell Migrator.
 *
 * The migrator needs substantially more horizontal and vertical space than a small utility view.
 * This perspective hides the editor area and opens the migrator as the only fixed standalone view.
 */
public final class YrellMigratorPerspective implements IPerspectiveFactory {
    public static final String ID = "se.yrell.migrator.perspective";

    public void createInitialLayout(IPageLayout layout) {
        String editorArea = layout.getEditorArea();
        layout.setEditorAreaVisible(false);
        layout.addStandaloneView(DifferencesView.ID, false, IPageLayout.LEFT, 1.0f, editorArea);
        try {
            layout.getViewLayout(DifferencesView.ID).setCloseable(false);
            layout.getViewLayout(DifferencesView.ID).setMoveable(false);
        } catch (Throwable ignored) {
            // Older Eclipse/Developer Studio builds can be conservative about view layout flags.
        }
    }
}
