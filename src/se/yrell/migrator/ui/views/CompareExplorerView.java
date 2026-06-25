package se.yrell.migrator.ui.views;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.bmc.arsys.studio.model.store.IStore;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcDefinitionCache;
import se.yrell.migrator.bmc.BmcModelAdapter;
import se.yrell.migrator.bmc.BmcTypeGroup;
import se.yrell.migrator.bmc.BmcMetadataCache.SyncProgress;
import se.yrell.migrator.bmc.BmcMetadataCache.CacheStats;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.ComparisonSession;
import se.yrell.migrator.ui.dialogs.CacheMaintenanceDialog;

/**
 * Global compare/search workbench view.
 *
 * This is the broader workflow complement to right-click compare: choose environments, scope and a
 * object-name query by default, then compare all matching objects directly into Yrell Migrator - Differences. Use type:, user: or any: for broader matching.
 */
public final class CompareExplorerView extends ViewPart {
    public static final String ID = "se.yrell.migrator.views.explorer";

    private Combo sourceCombo;
    private Combo targetCombo;
    private org.eclipse.swt.widgets.List typeList;
    private Text queryText;
    private Button includeSourceOnlyButton;
    private Button includeTargetOnlyButton;
    private Button showEqualButton;
    private Button searchButton;
    private CLabel statusLabel;
    private Text activityLog;

    private final BmcModelAdapter adapter = new BmcModelAdapter();
    private List<IStore> stores = new ArrayList<IStore>();
    private List<BmcTypeGroup> groups = new ArrayList<BmcTypeGroup>();

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        CLabel header = new CLabel(root, SWT.NONE);
        header.setText("Search cached AR definition differences across two connected environments.");
        header.setImage(sharedImage(ISharedImages.IMG_OBJ_FOLDER));
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createEnvironmentGroup(root);
        createScopeGroup(root);
        createOptionsGroup(root);
        createButtons(root);
        createActivityLog(root);

        statusLabel = new CLabel(root, SWT.BORDER);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setImage(sharedImage(ISharedImages.IMG_OBJS_INFO_TSK));

        loadStoresAndTypes();
    }

    private void createEnvironmentGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Environments");
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        group.setLayout(new GridLayout(4, false));

        Label sourceLabel = new Label(group, SWT.NONE);
        sourceLabel.setText("Source:");
        sourceCombo = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY);
        sourceCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sourceCombo.setToolTipText("Definition side considered source when results are shown/migrated");

        Label targetLabel = new Label(group, SWT.NONE);
        targetLabel.setText("Target:");
        targetCombo = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY);
        targetCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        targetCombo.setToolTipText("Definition side considered target when results are shown/migrated");
    }

    private void createScopeGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Search scope");
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        group.setLayout(new GridLayout(2, false));

        Label queryLabel = new Label(group, SWT.NONE);
        queryLabel.setText("Search:");
        queryText = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        queryText.setMessage("object name, e.g. AR* or AR%. Use type:, user: or any: for other fields");
        GridData queryData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        queryData.widthHint = 360;
        queryText.setLayoutData(queryData);

        Label typeLabel = new Label(group, SWT.NONE);
        typeLabel.setText("Object types:");
        typeLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        typeList = new org.eclipse.swt.widgets.List(group, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL);
        GridData typeData = new GridData(SWT.FILL, SWT.FILL, true, false);
        typeData.heightHint = 96;
        typeData.widthHint = 360;
        typeList.setLayoutData(typeData);
        typeList.setToolTipText("Select one or more object types. Select 'All object types' to include everything, including forms.");
        queryText.addTraverseListener(new TraverseListener() {
            @Override
            public void keyTraversed(TraverseEvent event) {
                if (event.detail == SWT.TRAVERSE_RETURN) {
                    event.doit = false;
                    runCachedSearch();
                }
            }
        });
        queryText.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetDefaultSelected(SelectionEvent event) {
                runCachedSearch();
            }
        });
    }

    private void createOptionsGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Result options");
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        group.setLayout(new GridLayout(3, false));

        includeSourceOnlyButton = new Button(group, SWT.CHECK);
        includeSourceOnlyButton.setText("Include source-only");
        includeSourceOnlyButton.setSelection(true);
        includeSourceOnlyButton.setToolTipText("Show objects that exist in source but not in target");

        includeTargetOnlyButton = new Button(group, SWT.CHECK);
        includeTargetOnlyButton.setText("Include target-only");
        includeTargetOnlyButton.setSelection(true);
        includeTargetOnlyButton.setToolTipText("Show objects that exist in target but not in source");

        showEqualButton = new Button(group, SWT.CHECK);
        showEqualButton.setText("Show equal rows");
        showEqualButton.setSelection(CompareSettings.load().isShowEqualByDefault());
        showEqualButton.setToolTipText("Show rows whose cached definition fingerprints match");
    }

    private void createButtons(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 0;
        row.setLayout(layout);

        searchButton = new Button(row, SWT.PUSH);
        searchButton.setText("Search");
        searchButton.setImage(sharedImage("IMG_ETOOL_SEARCH"));
        searchButton.setToolTipText("Search the local definition cache. Does not open server definitions. Run Sync first.");
        searchButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                runCachedSearch();
            }
        });

        Button fullSyncButton = new Button(row, SWT.PUSH);
        fullSyncButton.setText("Sync");
        fullSyncButton.setImage(sharedImage(ISharedImages.IMG_ELCL_SYNCED));
        fullSyncButton.setToolTipText("Build/update the persistent definition cache for all known object types in all connected environments. Object type selections only affect Search.");
        fullSyncButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                runFullSync();
            }
        });

        Button maintenanceButton = new Button(row, SWT.PUSH);
        maintenanceButton.setText("Cache...");
        maintenanceButton.setImage(sharedImage(ISharedImages.IMG_OBJ_FILE));
        maintenanceButton.setToolTipText("Show local cache size and clean orphan snapshot files");
        maintenanceButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                new CacheMaintenanceDialog(getSite().getShell(), new BmcDefinitionCache()).open();
            }
        });

    }

    private void createActivityLog(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Activity");
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        group.setLayout(new GridLayout(1, false));

        activityLog = new Text(group, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.heightHint = 120;
        activityLog.setLayoutData(data);
        activityLog.setText("Ready. Cache sync and compare progress will be shown here.\n");
    }

    private void loadStoresAndTypes() {
        stores = adapter.getConnectedStores();
        groups = adapter.getSearchTypeGroups();

        sourceCombo.removeAll();
        targetCombo.removeAll();
        for (IStore store : stores) {
            sourceCombo.add(store.getName());
            targetCombo.add(store.getName());
        }
        if (stores.size() > 0) {
            sourceCombo.select(0);
        }
        if (stores.size() > 1) {
            targetCombo.select(1);
        } else if (stores.size() > 0) {
            targetCombo.select(0);
        }

        typeList.removeAll();
        for (BmcTypeGroup group : groups) {
            typeList.add(group.getLabel());
        }
        if (!groups.isEmpty()) {
            int defaultIndex = 0;
            for (int i = 0; i < groups.size(); i++) {
                String label = groups.get(i).getLabel();
                if ("Definitions - common".equalsIgnoreCase(label)) {
                    defaultIndex = i;
                    break;
                }
            }
            typeList.select(defaultIndex);
        }
        updateStatus("Ready. Connected environments: " + stores.size() + ". Object type choices: " + groups.size() + ". Run Sync, then Search. Press Enter in Search to run the same cached search. Sync filter default: " + CompareSettings.load().describeSyncFilters() + ".", ISharedImages.IMG_OBJS_INFO_TSK);
    }


    private void runCachedSearch() {
        final IStore source = selectedStore(sourceCombo);
        final IStore target = selectedStore(targetCombo);
        final BmcTypeGroup typeGroup = selectedTypeGroup();
        if (source == null || target == null) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "Select both a source and a target environment. Both must be connected in Developer Studio.");
            return;
        }
        if (source == target || source.getName().equalsIgnoreCase(target.getName())) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "Select two different environments.");
            return;
        }
        if (typeGroup == null || typeGroup.getTypes().isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "No object types are available for the selected scope.");
            return;
        }
        final String query = queryText.getText();
        final boolean includeSourceOnly = includeSourceOnlyButton.getSelection();
        final boolean includeTargetOnly = includeTargetOnlyButton.getSelection();
        final boolean showEqual = showEqualButton.getSelection();
        final int max = CompareSettings.load().getSearchMaxResults();
        final Display display = getViewSite().getShell().getDisplay();
        setBusy(true);
        clearActivityLog();
        appendActivity("Search started. Source=" + source.getName() + ", Target=" + target.getName()
                + ", Scope=" + typeGroup.getLabel() + ", Search=" + (query == null || query.trim().length() == 0 ? "*" : query.trim())
                + ", Max=" + max + ".");
        updateStatus("Searching cached definition differences for " + typeGroup.getLabel() + "...", ISharedImages.IMG_OBJS_INFO_TSK);

        Job job = new Job("Yrell Migrator cached diff search") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                SyncProgress progress = new SyncProgress() {
                    public void report(final String message) {
                        if (display == null || display.isDisposed()) {
                            return;
                        }
                        display.asyncExec(new Runnable() {
                            public void run() {
                                appendActivity(message);
                                updateStatus(message, ISharedImages.IMG_OBJS_INFO_TSK);
                            }
                        });
                    }
                };
                final List<CompareResult> rows = adapter.cachedCompareSearch(source, target, typeGroup.getTypes(), query,
                        includeSourceOnly, includeTargetOnly, showEqual, max, monitor, progress);
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            finishCachedSearch(source, target, typeGroup, query, rows, showEqual, max);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void runFullSync() {
        final BmcTypeGroup typeGroup = syncAllTypeGroup();
        if (typeGroup == null || typeGroup.getTypes().isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "No object types are available for Sync.");
            return;
        }
        if (stores == null || stores.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "No connected environments were found in Developer Studio.");
            return;
        }
        final List<IStore> syncStores = new ArrayList<IStore>(stores);
        final Display display = getViewSite().getShell().getDisplay();
        setBusy(true);
        clearActivityLog();
        CompareSettings fullSyncSettings = CompareSettings.load();
        appendActivity("Sync started. Scope=" + typeGroup.getLabel() + " (Search selection is ignored for Sync), environments=" + storeNames(syncStores) + ".");
        appendActivity("Sync name filter: " + fullSyncSettings.describeSyncFilters() + ".");
        appendActivity("Sync builds/updates the persistent full definition snapshot cache. Searches and details use this cache; live server refresh is optional.");
        updateStatus("Sync for " + syncStores.size() + " environment(s)...", ISharedImages.IMG_ELCL_SYNCED);
        Job job = new Job("Yrell Migrator sync") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                SyncProgress progress = new SyncProgress() {
                    public void report(final String message) {
                        if (display == null || display.isDisposed()) {
                            return;
                        }
                        display.asyncExec(new Runnable() {
                            public void run() {
                                appendActivity(message);
                                updateStatus(message, ISharedImages.IMG_ELCL_SYNCED);
                            }
                        });
                    }
                };
                final CacheStats stats = adapter.syncDefinitionCache(syncStores, typeGroup.getTypes(), monitor, progress);
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            setBusy(false);
                            appendActivity("Sync complete. Metadata summary: " + stats.toSummary() + ". Definition snapshots are ready for cached details.");
                            updateStatus("Sync complete. You can now search cached diffs.", ISharedImages.IMG_OBJS_INFO_TSK);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }


    private BmcTypeGroup syncAllTypeGroup() {
        java.util.LinkedHashSet<com.bmc.arsys.studio.model.type.IModelType> allTypes = new java.util.LinkedHashSet<com.bmc.arsys.studio.model.type.IModelType>();
        for (BmcTypeGroup group : groups) {
            if (group != null && group.getTypes() != null) {
                allTypes.addAll(group.getTypes());
            }
        }
        return new BmcTypeGroup("All object types", new java.util.ArrayList<com.bmc.arsys.studio.model.type.IModelType>(allTypes));
    }

    private String storeNames(List<IStore> syncStores) {
        if (syncStores == null || syncStores.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (IStore store : syncStores) {
            if (store == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(store.getName());
        }
        return builder.toString();
    }

    private void clearActivityLog() {
        if (activityLog != null && !activityLog.isDisposed()) {
            activityLog.setText("");
        }
    }

    private void appendActivity(String text) {
        if (activityLog == null || activityLog.isDisposed() || text == null || text.length() == 0) {
            return;
        }
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        activityLog.append("[" + timestamp + "] " + text + "\n");
        activityLog.setSelection(activityLog.getCharCount());
    }


    private void finishCachedSearch(IStore source, IStore target, BmcTypeGroup typeGroup, String query, List<CompareResult> rows, boolean showEqual, int max) {
        if (searchButton == null || searchButton.isDisposed()) {
            return;
        }
        setBusy(false);
        List<CompareResult> visibleRows = new ArrayList<CompareResult>();
        int candidates = 0;
        int missing = 0;
        int equalMetadata = 0;
        int errors = 0;
        for (CompareResult row : rows) {
            if (row.getStatus() == CompareStatus.MISSING_IN_TARGET || row.getStatus() == CompareStatus.MISSING_IN_SOURCE) {
                missing++;
            } else if (row.getStatus() == CompareStatus.EQUAL) {
                equalMetadata++;
            } else if (row.getStatus() == CompareStatus.ERROR) {
                errors++;
            } else {
                candidates++;
            }
            if (showEqual || row.getStatus() != CompareStatus.EQUAL) {
                visibleRows.add(row);
            }
        }
        String label = "Search: " + source.getName() + " → " + target.getName() + ", " + typeGroup.getLabel()
                + (query == null || query.trim().length() == 0 ? "" : ", search=\"" + query.trim() + "\"");
        DifferencesView.show(new ComparisonSession(label, visibleRows));
        String suffix = rows.size() >= max ? " (limited by max results " + max + ")" : "";
        appendActivity("Search complete. " + rows.size() + " object(s)" + suffix + ": " + candidates + " changed, "
                + missing + " missing, " + equalMetadata + " equal, " + errors + " errors. Details are available from the local Sync cache; use Refresh Selected from Server only if the cache is stale.");
        updateStatus("Search complete. " + rows.size() + " object(s)" + suffix + ": " + candidates + " changed, "
                + missing + " missing, " + equalMetadata + " equal, " + errors + " errors.",
                errors > 0 ? ISharedImages.IMG_OBJS_ERROR_TSK : candidates + missing > 0 ? ISharedImages.IMG_OBJS_WARN_TSK : ISharedImages.IMG_OBJS_INFO_TSK);
    }

    private IStore selectedStore(Combo combo) {
        int index = combo == null ? -1 : combo.getSelectionIndex();
        if (index < 0 || index >= stores.size()) {
            return null;
        }
        return stores.get(index);
    }

    private BmcTypeGroup selectedTypeGroup() {
        int[] indices = typeList == null ? new int[0] : typeList.getSelectionIndices();
        if (indices.length == 0) {
            return null;
        }
        java.util.LinkedHashSet<com.bmc.arsys.studio.model.type.IModelType> selectedTypes = new java.util.LinkedHashSet<com.bmc.arsys.studio.model.type.IModelType>();
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index < 0 || index >= groups.size()) {
                continue;
            }
            BmcTypeGroup group = groups.get(index);
            if (label.length() > 0) {
                label.append(", ");
            }
            label.append(group.getLabel());
            selectedTypes.addAll(group.getTypes());
        }
        if (selectedTypes.isEmpty()) {
            return null;
        }
        return new BmcTypeGroup(label.toString(), new java.util.ArrayList<com.bmc.arsys.studio.model.type.IModelType>(selectedTypes));
    }

    private void setBusy(boolean busy) {
        if (searchButton != null && !searchButton.isDisposed()) {
            searchButton.setEnabled(!busy);
        }
    }

    private void updateStatus(String text, String imageKey) {
        if (statusLabel != null && !statusLabel.isDisposed()) {
            statusLabel.setText(text == null ? "" : text);
            statusLabel.setImage(sharedImage(imageKey));
        }
    }

    @Override
    public void setFocus() {
        if (queryText != null && !queryText.isDisposed()) {
            queryText.setFocus();
        }
    }

    private org.eclipse.swt.graphics.Image sharedImage(String key) {
        try {
            return PlatformUI.getWorkbench().getSharedImages().getImage(key);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private org.eclipse.jface.resource.ImageDescriptor sharedDescriptor(String key) {
        try {
            return PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(key);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
