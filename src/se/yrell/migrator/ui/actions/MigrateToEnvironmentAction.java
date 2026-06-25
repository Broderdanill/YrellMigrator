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

import se.yrell.migrator.bmc.BmcCatalogDataMigrator;
import se.yrell.migrator.bmc.BmcContainerContentMigrator;
import se.yrell.migrator.bmc.BmcModelAdapter;
import se.yrell.migrator.bmc.BmcSupportFileMigrator;
import se.yrell.migrator.bmc.BmcWorkflowMigrator;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationPlan;
import se.yrell.migrator.core.MigrationPlanner;
import se.yrell.migrator.core.MigrationReportFormatter;
import se.yrell.migrator.core.MigrationResult;
import se.yrell.migrator.core.ObjectMigrationExecutor;
import se.yrell.migrator.ui.dialogs.MigrationPlanDialog;
import se.yrell.migrator.ui.dialogs.MigrationReportDialog;

/** Migrates selected Developer Studio objects to another connected environment. */
public final class MigrateToEnvironmentAction extends BaseObjectListAction {
    private final BmcModelAdapter adapter = new BmcModelAdapter();
    private final BmcWorkflowMigrator migrator = new BmcWorkflowMigrator();
    private final BmcCatalogDataMigrator catalogDataMigrator = new BmcCatalogDataMigrator();
    private final BmcSupportFileMigrator supportFileMigrator = new BmcSupportFileMigrator();
    private final BmcContainerContentMigrator containerContentMigrator = new BmcContainerContentMigrator(migrator);
    private final MigrationPlanner planner = new MigrationPlanner();

    public MigrateToEnvironmentAction(ItemList<IModelItem> items) {
        setText("Migrate to Environment...");
        setToolTipText("Migrate selected Forms, workflow objects, Menus, Images and Support Files to another connected server");
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
        final IStore sourceStore = firstItem == null ? null : firstItem.getStore();
        if (sourceStore == null || !sourceStore.isConnected()) {
            showInfo("The selected source environment is not connected.");
            return;
        }

        final List<IModelItem> items = toMigratableList(selected);
        if (items.isEmpty()) {
            showInfo("No migratable objects are selected. This action currently supports Forms, workflow objects, Guides, Menus, Images and Support Files.");
            return;
        }
        if (items.size() != selected.size()) {
            boolean keepGoing = MessageDialog.openQuestion(getShell(), "Migrate to Environment",
                    (selected.size() - items.size()) + " selected object(s) will be skipped because they are not supported by this migrator.\n\nContinue with " + items.size() + " object(s)?");
            if (!keepGoing) {
                return;
            }
        }
        for (IModelItem item : items) {
            if (item.getStore() == null || !sourceStore.getName().equalsIgnoreCase(item.getStore().getName())) {
                showInfo("Please migrate objects from one source environment at a time.");
                return;
            }
        }

        List<IStore> targetStores = adapter.getConnectedStoresExcluding(sourceStore);
        if (targetStores.isEmpty()) {
            showInfo("Connect to at least one additional AR System server before migrating.");
            return;
        }
        final IStore targetStore = chooseTargetStore(targetStores);
        if (targetStore == null) {
            return;
        }

        final List<CompareResult> rows = new ArrayList<CompareResult>();
        for (IModelItem item : items) {
            rows.add(createResult(sourceStore, targetStore, item));
        }
        final MigrationPlan plan = planner.buildPlan(rows, rows, MigrationDirection.SOURCE_TO_TARGET, Math.max(0, selected.size() - items.size()));
        MigrationPlanDialog dialog = new MigrationPlanDialog(getShell(), plan);
        if (dialog.open() != MigrationPlanDialog.OK) {
            return;
        }
        final boolean includeContainerContent = dialog.isIncludeContainerContent();

        Job job = new Job("Migrate AR objects to " + targetStore.getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final long startedAtMillis = System.currentTimeMillis();
                ObjectMigrationExecutor executor = new ObjectMigrationExecutor(adapter, migrator,
                        catalogDataMigrator, supportFileMigrator, containerContentMigrator);
                ObjectMigrationExecutor.Execution execution = executor.execute(plan, includeContainerContent, monitor,
                        ObjectMigrationExecutor.Listener.NOOP);
                final long finishedAtMillis = System.currentTimeMillis();
                final List<MigrationResult> results = execution.getResults();
                final String report = MigrationReportFormatter.formatObjectMigrationReport(MigrationDirection.SOURCE_TO_TARGET,
                        results, startedAtMillis, finishedAtMillis, "Developer Studio context action");
                final MigrationReportFormatter.Summary summary = MigrationReportFormatter.summarize(MigrationDirection.SOURCE_TO_TARGET,
                        results, startedAtMillis, finishedAtMillis, "Developer Studio context action");
                final int bad = MigrationReportFormatter.countFailures(results);
                final int warn = MigrationReportFormatter.countWarnings(results);
                Display.getDefault().asyncExec(new Runnable() {
                    public void run() {
                        showCompleted(report, bad + warn, results, summary);
                    }
                });
                return execution.isCancelled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private CompareResult createResult(IStore sourceStore, IStore targetStore, IModelItem item) {
        CompareResult row = new CompareResult();
        row.setSourceStore(sourceStore);
        row.setTargetStore(targetStore);
        row.setModelType(item.getItemType());
        row.setObjectName(item.getName());
        row.setObjectType(item.getItemType() == null ? "" : item.getItemType().getTypeName());
        row.setSourceServer(sourceStore == null ? "" : sourceStore.getName());
        row.setTargetServer(targetStore == null ? "" : targetStore.getName());
        row.setSourceItem(item);
        row.setSourceModified(item.getLastUpdateTime());
        row.setSourceChangedBy(item.getLastChangedBy());
        row.setStatus(CompareStatus.CHANGED);
        return row;
    }

    private List<IModelItem> toMigratableList(ItemList<IModelItem> selected) {
        List<IModelItem> list = new ArrayList<IModelItem>();
        for (IModelItem item : selected) {
            if (item != null && (migrator.isMigratableType(item.getItemType()) || supportFileMigrator.isSupportFileType(item.getItemType()))) {
                list.add(item);
            }
        }
        return list;
    }

    private IStore chooseTargetStore(List<IStore> stores) {
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getShell(), new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof IStore) {
                    IStore store = (IStore) element;
                    return store.getName() + "  (" + store.getUser() + ")";
                }
                return super.getText(element);
            }
        });
        dialog.setTitle("Migrate to Environment");
        dialog.setMessage("Choose the connected target server/environment to migrate to:");
        dialog.setElements(stores.toArray(new IStore[stores.size()]));
        dialog.setMultipleSelection(false);
        if (dialog.open() != ElementListSelectionDialog.OK) {
            return null;
        }
        Object[] result = dialog.getResult();
        return result != null && result.length > 0 && result[0] instanceof IStore ? (IStore) result[0] : null;
    }

    private void showCompleted(String report, int problems, List<MigrationResult> results, MigrationReportFormatter.Summary summary) {
        new MigrationReportDialog(getShell(), "Object Migration Report",
                problems > 0 ? "Object migration completed with warnings." : "Object migration completed.",
                report, problems > 0, results, summary).open();
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
