package se.yrell.migrator.ui.actions;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.item.ItemList;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.ui.views.objectlist.actions.BaseObjectListAction;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcDiffLauncher;
import se.yrell.migrator.bmc.BmcModelAdapter;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.ComparisonSession;
import se.yrell.migrator.ui.views.DifferencesView;

/** Compares the selected Developer Studio object-list items with another connected server. */
public final class CompareWithEnvironmentAction extends BaseObjectListAction {
    private final BmcModelAdapter adapter = new BmcModelAdapter();

    public CompareWithEnvironmentAction(ItemList<IModelItem> items) {
        setText("Compare with Environment...");
        setToolTipText("Compare selected AR System objects with another connected server");
        setItems(items);
    }

    @Override
    public void run() {
        ItemList<IModelItem> selected = getItems();
        if (selected == null || selected.isEmpty()) {
            showInfo("No AR System objects are selected.");
            return;
        }

        IModelItem firstItem = selected.getFirstItem();
        IStore sourceStore = firstItem == null ? null : firstItem.getStore();
        List<IStore> targetStores = adapter.getConnectedStoresExcluding(sourceStore);
        if (targetStores.isEmpty()) {
            showInfo("Connect to at least one additional AR System server before comparing.");
            return;
        }

        final IStore targetStore = chooseTargetStore(targetStores);
        if (targetStore == null) {
            return;
        }

        final List<IModelItem> items = toList(selected);
        Job compareJob = new Job("Compare AR System objects") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Comparing AR System objects", items.size());
                final List<CompareResult> results = new ArrayList<CompareResult>();
                for (IModelItem item : items) {
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    results.add(adapter.compare(item, targetStore, monitor));
                    monitor.worked(1);
                }
                monitor.done();

                Display.getDefault().asyncExec(new Runnable() {
                    public void run() {
                        showResults(items, targetStore, results);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        compareJob.setUser(true);
        compareJob.schedule();
    }

    private IStore chooseTargetStore(List<IStore> stores) {
        Shell shell = getShell();
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof IStore) {
                    IStore store = (IStore) element;
                    return store.getName() + "  (" + store.getUser() + ")";
                }
                return super.getText(element);
            }
        });
        dialog.setTitle("Compare with Environment");
        dialog.setMessage("Choose the connected target server/environment to compare with:");
        dialog.setElements(stores.toArray(new IStore[stores.size()]));
        dialog.setMultipleSelection(false);
        if (dialog.open() != ElementListSelectionDialog.OK) {
            return null;
        }
        Object[] result = dialog.getResult();
        return result != null && result.length > 0 && result[0] instanceof IStore ? (IStore) result[0] : null;
    }

    private List<IModelItem> toList(ItemList<IModelItem> selected) {
        List<IModelItem> list = new ArrayList<IModelItem>();
        for (IModelItem item : selected) {
            if (item != null) {
                list.add(item);
            }
        }
        return list;
    }

    private void showResults(List<IModelItem> selectedItems, IStore targetStore, List<CompareResult> results) {
        String label = createSessionLabel(selectedItems, targetStore);
        ComparisonSession session = new ComparisonSession(label, results);
        DifferencesView.show(session);

        if (results.size() == 1) {
            CompareResult only = results.get(0);
            if (only.getStatus() == CompareStatus.CHANGED && BmcDiffLauncher.isDetailedCompareAvailable()) {
                BmcDiffLauncher.openCompare(only.getSourceStore(), only.getTargetStore(), only.getModelType(), only.getObjectName());
            }
        }
    }

    private String createSessionLabel(List<IModelItem> selectedItems, IStore targetStore) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return "Compare";
        }
        IModelItem first = selectedItems.get(0);
        String sourceName = first.getStore() == null ? "source" : first.getStore().getName();
        String targetName = targetStore == null ? "target" : targetStore.getName();
        return sourceName + " → " + targetName;
    }

    private Shell getShell() {
        try {
            return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        } catch (RuntimeException ex) {
            return Display.getDefault().getActiveShell();
        }
    }

    private void showInfo(String message) {
        MessageDialog.openInformation(getShell(), "Yrell Migrator", message);
    }
}
