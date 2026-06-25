package se.yrell.migrator.ui.actions;

import java.util.Collections;
import java.util.List;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;

import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.item.ItemList;
import com.bmc.arsys.studio.ui.views.objectlist.actions.BaseObjectListAction;
import com.bmc.arsys.studio.ui.views.objectlist.actions.ObjectListActionProvider;

/** Adds Yrell Migrator actions to Developer Studio object-list context menus. */
public final class CompareWithEnvironmentActionProvider extends ObjectListActionProvider {

    @Override
    public List<BaseObjectListAction> getHookableActions(String groupName) {
        return Collections.emptyList();
    }

    @Override
    public void fillContextMenu(IMenuManager menu) {
        ItemList<IModelItem> selectedItems = getItems();
        if (selectedItems == null || selectedItems.isEmpty()) {
            return;
        }

        MenuManager submenu = new MenuManager("Yrell Migrator");
        submenu.add(new CompareWithEnvironmentAction(copy(selectedItems)));
        submenu.add(new MigrateToEnvironmentAction(copy(selectedItems)));
        submenu.add(new MigrateFormDataToEnvironmentAction(copy(selectedItems)));
        submenu.add(new Separator());
        submenu.add(new ShowDifferencesViewAction());

        boolean appended = false;
        try {
            menu.appendToGroup("group.openwith", submenu);
            appended = true;
        } catch (IllegalArgumentException ignored) {
            // Some future Developer Studio view may not have the same menu group.
        }
        if (!appended) {
            menu.add(new Separator());
            menu.add(submenu);
        }
    }

    private ItemList<IModelItem> copy(ItemList<IModelItem> source) {
        ItemList<IModelItem> copy = new ItemList<IModelItem>();
        copy.addAll(source);
        return copy;
    }
}
