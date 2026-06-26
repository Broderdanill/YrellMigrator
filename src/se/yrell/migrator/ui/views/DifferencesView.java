package se.yrell.migrator.ui.views;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.jface.viewers.ILazyContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcCatalogDataMigrator;
import se.yrell.migrator.bmc.BmcContainerContentMigrator;
import se.yrell.migrator.bmc.BmcDataMigrator;
import se.yrell.migrator.bmc.BmcFormDataCsvExporter;
import se.yrell.migrator.bmc.BmcDefinitionCache;
import se.yrell.migrator.bmc.BmcDiffLauncher;
import se.yrell.migrator.bmc.BmcItemEnumerator;
import se.yrell.migrator.bmc.BmcModelAdapter;
import se.yrell.migrator.bmc.BmcTypeGroup;
import se.yrell.migrator.bmc.BmcMetadataCache.CacheStats;
import se.yrell.migrator.bmc.BmcMetadataCache.SyncProgress;
import se.yrell.migrator.bmc.BmcObjectOpener;
import se.yrell.migrator.bmc.BmcSupportFileMigrator;
import se.yrell.migrator.bmc.BmcWorkflowMigrator;
import se.yrell.migrator.bmc.ObjectTypeRegistry;
import se.yrell.migrator.core.CompareEvidence;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.ComparisonSession;
import se.yrell.migrator.core.DiffDetail;
import se.yrell.migrator.core.DiffSummaryAnalyzer;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationPlan;
import se.yrell.migrator.core.MigrationPlanner;
import se.yrell.migrator.core.MigrationReportFormatter;
import se.yrell.migrator.core.MigrationResult;
import se.yrell.migrator.core.MigrationVerifier;
import se.yrell.migrator.core.ObjectMigrationExecutor;
import se.yrell.migrator.core.pack.MigrationPack;
import se.yrell.migrator.core.pack.MigrationPackItem;
import se.yrell.migrator.core.pack.MigrationPackStorage;
import se.yrell.migrator.core.pack.MigrationPackPayloadService;
import se.yrell.migrator.core.pack.MigrationPackPreflight;
import se.yrell.migrator.core.backup.MigrationBackupService;
import se.yrell.migrator.ui.util.UiStrings;
import se.yrell.migrator.ui.util.ProgressMessages;
import se.yrell.migrator.ui.dialogs.DifferenceDetailsDialog;
import se.yrell.migrator.ui.dialogs.DataMigrationOptionsDialog;
import se.yrell.migrator.ui.dialogs.MigrationReportDialog;
import se.yrell.migrator.ui.dialogs.MigrationPlanDialog;
import se.yrell.migrator.ui.dialogs.FormDataExportOptionsDialog;

/**
 * AR object comparison overview.
 *
 * The view focuses on navigation first: filter to the interesting differences, open the
 * source/target object, jump into BMC Developer Studio's native detailed compare editor, and
 * explicitly migrate selected objects after confirmation.
 */
public final class DifferencesView extends ViewPart {
    public static final String ID = "se.yrell.migrator.views.explorer";

    private static final int LARGE_REPOSITORY_THRESHOLD = 25000;
    private static final int LARGE_REPOSITORY_AUTO_SORT_LIMIT = 50000;
    private static final int LARGE_REPOSITORY_PERF_LOG_THRESHOLD_MS = 250;
    private static final int FILTER_DEBOUNCE_MS = 350;

    private static final int COL_STATUS = 0;
    private static final int COL_NAME = 1;
    private static final int COL_TYPE = 2;
    private static final int COL_CUSTOMIZATION_TYPE = 3;
    private static final int COL_FORM = 4;
    private static final int COL_FORM_TYPE = 5;
    private static final int COL_CONTEXT = 6;
    private static final int COL_OPEN = 7;
    private static final int COL_SOURCE_MODIFIED = 8;
    private static final int COL_TARGET_MODIFIED = 9;
    private static final int COL_SOURCE_CHANGED_BY = 10;
    private static final int COL_TARGET_CHANGED_BY = 11;

    private TableViewer viewer;
    private CLabel summaryLabel;
    private CLabel overviewStatusLabel;
    private Combo sourceCombo;
    private Combo targetCombo;
    private Button syncButton;
    private Button reloadButton;
    private Button cacheButton;
    private Table typeNavigator;
    private ProgressBar operationProgress;
    private Text activityLog;
    private Text searchText;
    private Combo typeCombo;
    private Text changedByText;
    private Button modifiedAfterEnabledButton;
    private DateTime modifiedAfterDate;
    private Button changedButton;
    private Button missingButton;
    private Button equalButton;
    private Button errorButton;
    private Button stillDifferentButton;
    private Button permissionsDiffButton;
    private Button auditDiffButton;
    private Button customizationBaseButton;
    private Button customizationCustomButton;
    private Button customizationOverlayButton;

    private Action deepCompareSelectedAction;
    private Action openDetailedAction;
    private Action openSourceAction;
    private Action openBmcCompareAction;
    private Action technicalDiagnosticsAction;
    private Action openTargetAction;
    private Action migrateSourceToTargetAction;
    private Action migrateTargetToSourceAction;
    private Action migrateSourceDataToTargetAction;
    private Action migrateTargetDataToSourceAction;
    private Action exportFormDataCsvAction;
    private Action resetFiltersAction;
    private Action exportCsvAction;
    private Action exportDetailsCsvAction;
    private Action copyNamesAction;
    private Action addDefinitionsToPackSourceToTargetAction;
    private Action addDefinitionsToPackTargetToSourceAction;
    private Action addFormDataToPackSourceToTargetAction;
    private Action addFormDataToPackTargetToSourceAction;


    private final BmcWorkflowMigrator workflowMigrator = new BmcWorkflowMigrator();
    private final BmcCatalogDataMigrator catalogDataMigrator = new BmcCatalogDataMigrator();
    private final BmcSupportFileMigrator supportFileMigrator = new BmcSupportFileMigrator();
    private final BmcContainerContentMigrator containerContentMigrator = new BmcContainerContentMigrator(workflowMigrator);
    private final BmcDataMigrator dataMigrator = new BmcDataMigrator();
    private final BmcFormDataCsvExporter formDataCsvExporter = new BmcFormDataCsvExporter();
    private final BmcModelAdapter modelAdapter = new BmcModelAdapter();
    private final MigrationPlanner migrationPlanner = new MigrationPlanner();
    private final MigrationPackStorage migrationPackStorage = new MigrationPackStorage();
    private final MigrationPackPayloadService migrationPackPayloadService = new MigrationPackPayloadService();
    private final MigrationBackupService migrationBackupService = new MigrationBackupService();
    private MigrationPack migrationPack = new MigrationPack();
    private org.eclipse.swt.widgets.TabFolder mainTabs;
    private org.eclipse.swt.widgets.TabItem migrationPackTabItem;
    private TableViewer packViewer;
    private CLabel packSummaryLabel;
    private String lastMigrationPackRunReport = "";
    private String lastMigrationPackPreflightReport = "";
    private List<IStore> stores = new ArrayList<IStore>();
    private List<BmcTypeGroup> groups = new ArrayList<BmcTypeGroup>();
    private String selectedNavigatorType;
    private final Map<Integer, String> typeNavigatorIndex = new LinkedHashMap<Integer, String>();
    private final Map<String, Image> objectTypeImages = new LinkedHashMap<String, Image>();
    private ResultComparator comparator;
    private boolean rebuildingFilterOptions;
    private ComparisonSession session = new ComparisonSession("No comparison", new ArrayList<CompareResult>());
    private final List<CompareResult> visibleResults = new ArrayList<CompareResult>();
    private final ResultFilter resultFilter = new ResultFilter();
    private final ResultUiIndex emptyResultUiIndex = new ResultUiIndex(null);
    private final Map<CompareResult, ResultUiIndex> resultUiIndex = new IdentityHashMap<CompareResult, ResultUiIndex>();
    private CountSummary currentScopeCountSummary = new CountSummary();
    private boolean scopeCountSummaryValid;
    private boolean largeRepositoryMode;
    private boolean environmentAndTypeLoadComplete;
    private long lastInitialLoadMillis;
    private boolean automaticSortSkipped;
    private boolean userSortRequested;
    private long lastRefreshMillis;
    private long lastFilterMillis;
    private long lastSortMillis;
    private long lastIndexMillis;
    private long lastPerformanceLogAtMillis;
    private int lastFilterInputRows;
    private int lastFilterMatchedRows;
    private int lastIndexedRows;
    private int filterRefreshGeneration;
    private boolean pendingFilterRefreshRebuildOptions;

    public static void show(ComparisonSession session) {
        try {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            DifferencesView view = (DifferencesView) page.showView(ID);
            view.setSession(session);
        } catch (Exception ex) {
            Activator.logError("Could not show Yrell Migrator view.", ex);
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        org.eclipse.swt.widgets.TabFolder tabs = new org.eclipse.swt.widgets.TabFolder(parent, SWT.NONE);
        this.mainTabs = tabs;
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite root = new Composite(tabs, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        org.eclipse.swt.widgets.TabItem compareTab = new org.eclipse.swt.widgets.TabItem(tabs, SWT.NONE);
        compareTab.setText("Compare");
        compareTab.setImage(sharedImage(ISharedImages.IMG_OBJ_FOLDER));
        compareTab.setControl(root);

        createEnvironmentGroup(root);

        SashForm sash = new SashForm(root, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createTypeNavigator(sash);

        Composite right = new Composite(sash, SWT.NONE);
        right.setLayout(new GridLayout(1, false));

        // v0.68.0: keep the Differences view visually quieter. The long overview/status
        // line above the search field did not add useful action context in large environments,
        // so updateSummaryText() now becomes a no-op unless a future UI explicitly creates it.
        summaryLabel = null;

        createFilterBar(right);
        createViewer(right);
        createToolbarActions();
        hookContextMenu();
        sash.setWeights(new int[] { 24, 76 });

        overviewStatusLabel = new CLabel(root, SWT.BORDER);
        overviewStatusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        overviewStatusLabel.setImage(sharedImage(ISharedImages.IMG_OBJS_INFO_TSK));

        operationProgress = new ProgressBar(root, SWT.INDETERMINATE);
        operationProgress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        operationProgress.setVisible(false);
        ((GridData) operationProgress.getLayoutData()).exclude = true;

        createActivityLog(root);
        createMigrationPackTab(tabs);
        createSettingsTab(tabs);

        // Keep view creation lightweight. Developer Studio type discovery can be slow in large
        // installations, so load environments/object types after the SWT controls have painted.
        updateOverviewStatus("Opening Yrell Migrator...", ISharedImages.IMG_OBJS_INFO_TSK);
        // Show the same in-view progress indicator during startup/cache loading as we do for
        // long-running migration/sync actions. This avoids the impression that Developer Studio
        // has frozen while the existing disk cache is being read.
        setBusy(true);
        loadStoresAndTypesAsync();
        refresh(true);
    }

    private void createEnvironmentGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Environments");
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        group.setLayout(new GridLayout(8, false));

        new Label(group, SWT.NONE).setText("Source:");
        sourceCombo = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY);
        sourceCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sourceCombo.setToolTipText("Source environment for comparison and migration.");
        sourceCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                reloadOverviewFromCache(false);
            }
        });

        new Label(group, SWT.NONE).setText("Target:");
        targetCombo = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY);
        targetCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        targetCombo.setToolTipText("Target environment for comparison and migration.");
        targetCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                reloadOverviewFromCache(false);
            }
        });

        syncButton = new Button(group, SWT.PUSH);
        syncButton.setText("Sync");
        syncButton.setImage(sharedImage(ISharedImages.IMG_ELCL_SYNCED));
        syncButton.setToolTipText("Update the local definition cache for all known object types. Search/type filters do not limit Sync.");
        syncButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                runFullSync();
            }
        });

        cacheButton = new Button(group, SWT.PUSH);
        cacheButton.setText("Cache...");
        cacheButton.setImage(sharedImage(ISharedImages.IMG_OBJ_FILE));
        cacheButton.setToolTipText("Show local cache size and clean orphan snapshot files.");
        cacheButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                new se.yrell.migrator.ui.dialogs.CacheMaintenanceDialog(getSite().getShell(), new BmcDefinitionCache()).open();
            }
        });

        reloadButton = new Button(group, SWT.PUSH);
        reloadButton.setText("Refresh overview");
        reloadButton.setImage(sharedImage(ISharedImages.IMG_ELCL_SYNCED));
        reloadButton.setToolTipText("Reload this overview from the local cache without contacting the servers.");
        reloadButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                reloadOverviewFromCache(true);
            }
        });


        Button settingsButton = new Button(group, SWT.PUSH);
        settingsButton.setText("Settings");
        settingsButton.setImage(sharedImage(ISharedImages.IMG_OBJ_ELEMENT));
        settingsButton.setToolTipText("Open the Settings tab inside Yrell Migrator.");
        settingsButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                org.eclipse.swt.widgets.Control c = group;
                while (c != null && !(c instanceof org.eclipse.swt.widgets.TabFolder)) {
                    c = c.getParent();
                }
                if (c instanceof org.eclipse.swt.widgets.TabFolder) {
                    org.eclipse.swt.widgets.TabFolder folder = (org.eclipse.swt.widgets.TabFolder) c;
                    if (folder.getItemCount() > 1) {
                        folder.setSelection(1);
                    }
                }
            }
        });
    }

    private void createTypeNavigator(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Object types");
        group.setLayout(new GridLayout(1, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        typeNavigator = new Table(group, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.FULL_SELECTION);
        typeNavigator.setHeaderVisible(false);
        typeNavigator.setLinesVisible(false);
        typeNavigator.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        typeNavigator.setToolTipText("Color guide: red = errors, yellow = missing, blue = different, green/gray = equal only.");
        final org.eclipse.swt.widgets.TableColumn navigatorColumn = new org.eclipse.swt.widgets.TableColumn(typeNavigator, SWT.LEFT);
        navigatorColumn.setText("Object type");
        navigatorColumn.setWidth(260);
        typeNavigator.addListener(SWT.Resize, event -> {
            org.eclipse.swt.graphics.Rectangle area = typeNavigator.getClientArea();
            if (area != null && area.width > 0) {
                navigatorColumn.setWidth(area.width);
            }
        });
        typeNavigator.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                selectedNavigatorType = selectedNavigatorTypeFromList();
                refresh(false);
            }
        });
    }


    private void createMigrationPackTab(org.eclipse.swt.widgets.TabFolder tabs) {
        Composite root = new Composite(tabs, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        migrationPackTabItem = new org.eclipse.swt.widgets.TabItem(tabs, SWT.NONE);
        migrationPackTabItem.setText("Migration Pack");
        migrationPackTabItem.setImage(sharedImage(ISharedImages.IMG_OBJ_FOLDER));
        migrationPackTabItem.setControl(root);

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout buttonLayout = new GridLayout(15, false);
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttons.setLayout(buttonLayout);

        Button rename = new Button(buttons, SWT.PUSH);
        rename.setText("Rename...");
        rename.setImage(sharedImage(ISharedImages.IMG_OBJ_ELEMENT));
        rename.setToolTipText("Rename the current local Migration Pack. The name is stored in exported .ympack files.");
        rename.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                renameMigrationPack();
            }
        });

        Button run = new Button(buttons, SWT.PUSH);
        run.setText("Run Pack...");
        run.setImage(sharedImage(ISharedImages.IMG_ELCL_SYNCED));
        run.setToolTipText("Run all definitions and form-data scopes currently in the local migration pack.");
        run.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                runMigrationPack();
            }
        });

        Button runSelected = new Button(buttons, SWT.PUSH);
        runSelected.setText("Run selected...");
        runSelected.setImage(sharedImage(ISharedImages.IMG_TOOL_FORWARD));
        runSelected.setToolTipText("Run only selected Migration Pack rows. If nothing is selected, use Run Pack instead.");
        runSelected.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                runSelectedMigrationPackItems();
            }
        });

        Button preflight = new Button(buttons, SWT.PUSH);
        preflight.setText("Preflight...");
        preflight.setImage(sharedImage(ISharedImages.IMG_OBJS_INFO_TSK));
        preflight.setToolTipText("Validate embedded payloads and target environment readiness before running the pack.");
        preflight.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                showMigrationPackPreflight();
            }
        });

        Button retarget = new Button(buttons, SWT.PUSH);
        retarget.setText("Retarget...");
        retarget.setImage(sharedImage(ISharedImages.IMG_TOOL_FORWARD));
        retarget.setToolTipText("Change target environment for selected pack rows, or all rows if none are selected.");
        retarget.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                retargetMigrationPackItems();
            }
        });

        Button order = new Button(buttons, SWT.PUSH);
        order.setText("Order for run");
        order.setImage(sharedImage(ISharedImages.IMG_TOOL_FORWARD));
        order.setToolTipText("Sort the pack into the recommended execution order: security/catalog, forms, menus, workflow, then form data.");
        order.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                orderMigrationPackForRun();
            }
        });

        Button moveUp = new Button(buttons, SWT.PUSH);
        moveUp.setText("Move up");
        moveUp.setToolTipText("Move selected pack rows up in the visual order.");
        moveUp.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                moveSelectedPackItems(true);
            }
        });

        Button moveDown = new Button(buttons, SWT.PUSH);
        moveDown.setText("Move down");
        moveDown.setToolTipText("Move selected pack rows down in the visual order.");
        moveDown.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                moveSelectedPackItems(false);
            }
        });

        Button saveReport = new Button(buttons, SWT.PUSH);
        saveReport.setText("Save report...");
        saveReport.setImage(sharedImage(ISharedImages.IMG_ETOOL_SAVEAS_EDIT));
        saveReport.setToolTipText("Save the latest Migration Pack run/preflight report to a text file.");
        saveReport.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                saveLatestMigrationPackReport();
            }
        });

        Button export = new Button(buttons, SWT.PUSH);
        export.setText("Export...");
        export.setImage(sharedImage(ISharedImages.IMG_ETOOL_SAVEAS_EDIT));
        export.setToolTipText("Export this local migration pack to a portable ZIP file.");
        export.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                exportMigrationPack();
            }
        });

        Button imp = new Button(buttons, SWT.PUSH);
        imp.setText("Import...");
        imp.setImage(sharedImage(ISharedImages.IMG_OBJ_FOLDER));
        imp.setToolTipText("Import a Migration Pack ZIP, .ympack or legacy .hlxpack file and replace the current local pack.");
        imp.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                importMigrationPack();
            }
        });

        Button restoreBackup = new Button(buttons, SWT.PUSH);
        restoreBackup.setText("Restore backup...");
        restoreBackup.setImage(sharedImage(ISharedImages.IMG_TOOL_UNDO));
        restoreBackup.setToolTipText("Restore a .ymbackup file created before a migration run.");
        restoreBackup.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                restoreMigrationBackup();
            }
        });

        Button remove = new Button(buttons, SWT.PUSH);
        remove.setText("Remove selected");
        remove.setImage(sharedImage(ISharedImages.IMG_ETOOL_CLEAR));
        remove.setToolTipText("Remove selected rows from the local migration pack.");
        remove.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                removeSelectedPackItems();
            }
        });

        Button clearStatus = new Button(buttons, SWT.PUSH);
        clearStatus.setText("Clear status");
        clearStatus.setToolTipText("Clear Last run status for selected pack rows, or all rows if none are selected.");
        clearStatus.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                clearMigrationPackLastRunStatus();
            }
        });

        Button clear = new Button(buttons, SWT.PUSH);
        clear.setText("Clear Pack");
        clear.setImage(sharedImage(ISharedImages.IMG_ETOOL_CLEAR));
        clear.setToolTipText("Clear the current local migration pack.");
        clear.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                clearMigrationPack();
            }
        });

        packSummaryLabel = new CLabel(root, SWT.BORDER);
        packSummaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        packSummaryLabel.setImage(sharedImage(ISharedImages.IMG_OBJS_INFO_TSK));

        packViewer = new TableViewer(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = packViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        packViewer.setContentProvider(ArrayContentProvider.getInstance());

        addPackColumn("Kind", 105, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                return item.isFormData() ? "Form data" : "Definition";
            }
            @Override
            public Image getImage(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                return item.isFormData() ? sharedImage(ISharedImages.IMG_OBJ_FILE) : sharedImage(ISharedImages.IMG_OBJ_ELEMENT);
            }
        });
        addPackColumn("Direction", 130, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((MigrationPackItem) element).getDirection().getLabel();
            }
        });
        addPackColumn("Phase", 130, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((MigrationPackItem) element).executionPhaseLabel();
            }
        });
        addPackColumn("Type", 120, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((MigrationPackItem) element).getObjectType();
            }
        });
        addPackColumn("Name / Form", 260, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                return item.isFormData() ? item.getFormName() : item.getObjectName();
            }
        });
        addPackColumn("Rows / Qualification", 260, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                if (!item.isFormData()) {
                    String embedded = item.hasEmbeddedPayload() ? "embedded" : "not embedded";
                    if (item.getEmbeddedRowCount() > 0) embedded = item.getEmbeddedRowCount() + " embedded row(s)";
                    if (item.getEmbeddedObjectCount() > 0) embedded = item.getEmbeddedObjectCount() + " embedded object(s)";
                    return item.getContextKey().length() == 0 ? embedded : item.getContextKey() + " — " + embedded;
                }
                String rows = item.hasEmbeddedPayload()
                        ? item.getEmbeddedRowCount() + " embedded row(s)"
                        : (item.getMaxRows() <= 0 ? "all matching rows" : item.getMaxRows() + " row(s) max");
                String q = item.getQualification();
                return q == null || q.trim().length() == 0 ? rows : rows + " — " + q.trim();
            }
            @Override
            public String getToolTipText(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                if (!item.isFormData()) {
                    return item.getCaptureSummary().length() == 0 ? item.getContextKey() : item.getCaptureSummary();
                }
                return "Embedded: " + (item.hasEmbeddedPayload() ? item.getEmbeddedRowCount() + " row(s)" : "no")
                        + "\nCapture: " + item.getCaptureSummary()
                        + "\nConflict: " + item.getConflictMode().getLabel()
                        + "\nAttachments: " + item.getAttachmentPolicy().getLabel()
                        + "\nWorkflow: " + (item.isRunWorkflow() ? "run" : "suppressed")
                        + "\nMode: " + (item.isDryRun() ? "dry run" : "write");
            }
        });
        addPackColumn("Source → Target", 230, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                return item.getSourceServer() + " → " + item.getTargetServer();
            }
        });
        addPackColumn("Last run", 240, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                return item.lastRunSummary();
            }
            @Override
            public String getToolTipText(Object element) {
                MigrationPackItem item = (MigrationPackItem) element;
                return item.lastRunSummary();
            }
        });
        refreshMigrationPackView();
    }

    private void addPackColumn(String title, int width, ColumnLabelProvider provider) {
        TableViewerColumn column = new TableViewerColumn(packViewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setMoveable(true);
        column.setLabelProvider(provider);
    }

    private void refreshMigrationPackView() {
        if (packViewer != null && packViewer.getControl() != null && !packViewer.getControl().isDisposed()) {
            packViewer.setInput(migrationPack.getItems());
            packViewer.refresh();
        }
        if (packSummaryLabel != null && !packSummaryLabel.isDisposed()) {
            packSummaryLabel.setText(migrationPack.getName() + " — " + migrationPack.size() + " item(s), "
                    + migrationPack.getDefinitionCount() + " definition(s), " + migrationPack.getFormDataCount()
                    + " form-data scope(s). " + migrationPack.environmentSummary());
        }
        if (migrationPackTabItem != null && !migrationPackTabItem.isDisposed()) {
            migrationPackTabItem.setText("Migration Pack" + (migrationPack.size() > 0 ? " (" + migrationPack.size() + ")" : ""));
        }
    }

    private void addDefinitionsToMigrationPack(MigrationDirection direction) {
        List<CompareResult> selected = getSelectedResults();
        List<CompareResult> candidates = getMigratableResults(selected, direction);
        if (candidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack",
                    "No selected definition rows can be added for " + direction.getLabel() + ".");
            return;
        }
        int added = 0;
        int duplicates = 0;
        int failedCapture = 0;
        StringBuilder failures = new StringBuilder();
        for (CompareResult row : migrationPlanner.orderDefinitionMigration(candidates)) {
            MigrationPackItem item = MigrationPackItem.definition(row, direction, contextColumnText(row));
            try {
                migrationPackPayloadService.captureDefinition(item, row, direction, new NullProgressMonitor());
            } catch (Throwable ex) {
                failedCapture++;
                if (failures.length() < 1200) {
                    if (failures.length() > 0) failures.append('\n');
                    failures.append(row.getObjectType()).append(' ').append(row.getObjectName()).append(": ").append(safeMessage(ex));
                }
                continue;
            }
            boolean ok = migrationPack.add(item);
            if (ok) added++; else duplicates++;
        }
        refreshMigrationPackView();
        switchToMigrationPackTab();
        appendActivity("Added " + added + " embedded definition item(s) to Migration Pack" + (duplicates > 0 ? " (" + duplicates + " duplicate(s) skipped)" : "") + (failedCapture > 0 ? " (" + failedCapture + " capture failure(s))" : "") + ".");
        if (failedCapture > 0) {
            MessageDialog.openWarning(getSite().getShell(), "Migration Pack", "Some selected definitions could not be embedded and were not added:\n\n" + failures.toString());
        }
    }

    private void addFormDataToMigrationPack(MigrationDirection direction) {
        List<CompareResult> selected = getSelectedResults();
        List<CompareResult> candidates = getFormDataMigratableResults(selected, direction);
        if (candidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack",
                    "No selected Form rows can be added as form-data scopes for " + direction.getLabel() + ".");
            return;
        }
        final CompareResult first = candidates.get(0);
        final IStore sourceStore = direction == MigrationDirection.SOURCE_TO_TARGET ? first.getSourceStore() : first.getTargetStore();
        final IStore targetStore = direction == MigrationDirection.SOURCE_TO_TARGET ? first.getTargetStore() : first.getSourceStore();
        for (CompareResult row : candidates) {
            IStore rowSource = direction == MigrationDirection.SOURCE_TO_TARGET ? row.getSourceStore() : row.getTargetStore();
            IStore rowTarget = direction == MigrationDirection.SOURCE_TO_TARGET ? row.getTargetStore() : row.getSourceStore();
            if (!sameStore(sourceStore, rowSource) || !sameStore(targetStore, rowTarget)) {
                MessageDialog.openInformation(getSite().getShell(), "Migration Pack",
                        "Please add form data for one source/target environment pair at a time.");
                return;
            }
        }
        DataMigrationOptionsDialog dialog = new DataMigrationOptionsDialog(getSite().getShell(),
                candidates.size() == 1 ? candidates.get(0).getObjectName() : candidates.size() + " selected forms",
                sourceStore, targetStore);
        if (dialog.open() != DataMigrationOptionsDialog.OK) {
            return;
        }
        BmcDataMigrator.Options template = dialog.getOptions();
        if (template == null) {
            return;
        }
        int added = 0;
        int duplicates = 0;
        int failedCapture = 0;
        StringBuilder failures = new StringBuilder();
        for (CompareResult row : candidates) {
            BmcDataMigrator.Options opts = copyDataOptions(template);
            opts.setFormName(row.getObjectName());
            MigrationPackItem item = MigrationPackItem.formData(opts, direction);
            try {
                migrationPackPayloadService.captureFormData(item, opts, new NullProgressMonitor());
            } catch (Throwable ex) {
                failedCapture++;
                if (failures.length() < 1200) {
                    if (failures.length() > 0) failures.append('\n');
                    failures.append(row.getObjectName()).append(": ").append(safeMessage(ex));
                }
                continue;
            }
            boolean ok = migrationPack.add(item);
            if (ok) added++; else duplicates++;
        }
        refreshMigrationPackView();
        switchToMigrationPackTab();
        appendActivity("Added " + added + " embedded form-data scope(s) to Migration Pack" + (duplicates > 0 ? " (" + duplicates + " duplicate(s) skipped)" : "") + (failedCapture > 0 ? " (" + failedCapture + " capture failure(s))" : "") + ".");
        if (failedCapture > 0) {
            MessageDialog.openWarning(getSite().getShell(), "Migration Pack", "Some selected form-data scopes could not be embedded and were not added:\n\n" + failures.toString());
        }
    }

    private void switchToMigrationPackTab() {
        if (mainTabs != null && !mainTabs.isDisposed() && migrationPackTabItem != null && !migrationPackTabItem.isDisposed()) {
            mainTabs.setSelection(migrationPackTabItem);
        }
    }


    private void renameMigrationPack() {
        InputDialog dialog = new InputDialog(getSite().getShell(), "Rename Migration Pack",
                "Enter a name for the current Migration Pack:", migrationPack.getName(), null);
        if (dialog.open() != Window.OK) {
            return;
        }
        String name = dialog.getValue() == null ? "" : dialog.getValue().trim();
        if (name.length() == 0) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "The Migration Pack name cannot be empty.");
            return;
        }
        migrationPack.setName(name);
        refreshMigrationPackView();
        appendActivity("Migration Pack renamed to " + name + ".");
    }

    private void orderMigrationPackForRun() {
        if (migrationPack == null || migrationPack.isEmpty()) {
            return;
        }
        migrationPack.sortForRun();
        refreshMigrationPackView();
        appendActivity("Migration Pack sorted into recommended execution order.");
    }

    private void moveSelectedPackItems(boolean up) {
        List<MigrationPackItem> selected = getSelectedPackItems();
        if (selected.isEmpty()) {
            return;
        }
        if (up) {
            migrationPack.moveUp(selected);
        } else {
            migrationPack.moveDown(selected);
        }
        refreshMigrationPackView();
    }

    private void clearMigrationPackLastRunStatus() {
        if (migrationPack == null || migrationPack.isEmpty()) {
            return;
        }
        List<MigrationPackItem> selected = getSelectedPackItems();
        migrationPack.clearLastRun(selected);
        refreshMigrationPackView();
        appendActivity("Cleared Migration Pack Last run status for " + (selected.isEmpty() ? migrationPack.size() : selected.size()) + " row(s).");
    }

    private List<MigrationPackItem> getSelectedPackItems() {
        List<MigrationPackItem> items = new ArrayList<MigrationPackItem>();
        if (packViewer == null || packViewer.getControl().isDisposed()) {
            return items;
        }
        IStructuredSelection selection = (IStructuredSelection) packViewer.getSelection();
        for (Object element : selection.toArray()) {
            if (element instanceof MigrationPackItem) {
                items.add((MigrationPackItem) element);
            }
        }
        return items;
    }

    private void showMigrationPackPreflight() {
        MigrationPackPreflight.Result result = new MigrationPackPreflight(migrationPack, stores).run();
        lastMigrationPackPreflightReport = result.getReport();
        new MigrationReportDialog(getSite().getShell(), "Migration Pack Preflight",
                result.canRun() ? "Migration Pack is ready to run." : "Migration Pack has preflight errors.",
                result.getReport(), !result.canRun() || result.getWarnings() > 0).open();
    }

    private void runSelectedMigrationPackItems() {
        List<MigrationPackItem> selected = getSelectedPackItems();
        if (selected.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "Select one or more pack rows to run, or use Run Pack to process everything.");
            return;
        }
        runMigrationPackItems(selected, "selected Migration Pack row(s)");
    }

    private MigrationPack packForItems(List<MigrationPackItem> items, String suffix) {
        MigrationPack pack = new MigrationPack();
        pack.setName(migrationPack.getName() + (suffix == null || suffix.length() == 0 ? "" : " — " + suffix));
        pack.clear();
        if (items != null) {
            for (MigrationPackItem item : items) {
                pack.add(item);
            }
        }
        return pack;
    }

    private void saveLatestMigrationPackReport() {
        String report = lastMigrationPackRunReport != null && lastMigrationPackRunReport.length() > 0
                ? lastMigrationPackRunReport : lastMigrationPackPreflightReport;
        if (report == null || report.length() == 0) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "There is no Migration Pack report to save yet. Run Preflight or Run Pack first.");
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Save Migration Pack Report");
        dialog.setFilterExtensions(new String[] { "*.txt", "*.*" });
        dialog.setFileName(safeFileName(migrationPack.getName()) + "-report.txt");
        String path = dialog.open();
        if (path == null || path.length() == 0) {
            return;
        }
        if (!path.toLowerCase(Locale.ENGLISH).endsWith(".txt")) {
            path = path + ".txt";
        }
        try {
            OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            try {
                out.write(report);
            } finally {
                out.close();
            }
            appendActivity("Migration Pack report saved to " + path + ".");
        } catch (Throwable ex) {
            Activator.logError("Could not save Migration Pack report.", ex);
            MessageDialog.openError(getSite().getShell(), "Migration Pack", "Could not save report: " + safeMessage(ex));
        }
    }

    private void retargetMigrationPackItems() {
        if (migrationPack == null || migrationPack.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "The current Migration Pack is empty.");
            return;
        }
        List<String> names = connectedStoreNames();
        if (names.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "No connected target environments were found.");
            return;
        }
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider());
        dialog.setTitle("Retarget Migration Pack");
        dialog.setMessage("Choose the connected environment that selected rows should be migrated into. If no rows are selected, all pack rows are retargeted.");
        dialog.setElements(names.toArray(new String[names.size()]));
        if (dialog.open() != Window.OK || dialog.getFirstResult() == null) {
            return;
        }
        String newTarget = String.valueOf(dialog.getFirstResult());
        List<MigrationPackItem> selected = getSelectedPackItems();
        List<MigrationPackItem> items = selected.isEmpty() ? new ArrayList<MigrationPackItem>(migrationPack.getItems()) : selected;
        for (MigrationPackItem item : items) {
            if (item != null) {
                item.setTargetServer(newTarget);
            }
        }
        migrationPack.touch();
        refreshMigrationPackView();
        appendActivity("Retargeted " + items.size() + " Migration Pack row(s) to " + newTarget + ".");
    }

    private List<String> connectedStoreNames() {
        List<String> names = new ArrayList<String>();
        for (IStore store : stores) {
            if (store != null && store.isConnected() && store.getName() != null && store.getName().length() > 0) {
                names.add(store.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private void removeSelectedPackItems() {
        List<MigrationPackItem> items = getSelectedPackItems();
        if (items.isEmpty()) {
            return;
        }
        for (MigrationPackItem item : items) {
            migrationPack.remove(item);
        }
        refreshMigrationPackView();
    }

    private void clearMigrationPack() {
        if (migrationPack.isEmpty()) {
            return;
        }
        if (MessageDialog.openQuestion(getSite().getShell(), "Migration Pack", "Clear the current Migration Pack?")) {
            migrationPack.clear();
            refreshMigrationPackView();
        }
    }

    private void exportMigrationPack() {
        if (migrationPack.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "The current Migration Pack is empty.");
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export Migration Pack");
        dialog.setFilterExtensions(new String[] { "*.zip", "*.ympack", "*.hlxpack", "*.*" });
        dialog.setFileName(safeFileName(migrationPack.getName()) + ".zip");
        String path = dialog.open();
        if (path == null) {
            return;
        }
        String lowerPath = path.toLowerCase(Locale.ENGLISH);
        if (!lowerPath.endsWith(".zip") && !lowerPath.endsWith(".ympack") && !lowerPath.endsWith(".hlxpack")) {
            path = path + ".zip";
        }
        try {
            migrationPackStorage.save(migrationPack, new File(path));
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "Migration Pack exported to:\n" + path);
            appendActivity("Migration Pack exported to " + path + ".");
        } catch (Throwable ex) {
            Activator.logError("Could not export Migration Pack.", ex);
            MessageDialog.openError(getSite().getShell(), "Migration Pack", "Could not export Migration Pack: " + safeMessage(ex));
        }
    }

    private void importMigrationPack() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Import Migration Pack");
        dialog.setFilterExtensions(new String[] { "*.zip", "*.ympack", "*.hlxpack", "*.*" });
        String path = dialog.open();
        if (path == null) {
            return;
        }
        try {
            migrationPack = migrationPackStorage.load(new File(path));
            lastMigrationPackRunReport = "";
            lastMigrationPackPreflightReport = "";
            refreshMigrationPackView();
            switchToMigrationPackTab();
            appendActivity("Migration Pack imported from " + path + ": " + migrationPack.size() + " item(s).");
        } catch (Throwable ex) {
            Activator.logError("Could not import Migration Pack.", ex);
            MessageDialog.openError(getSite().getShell(), "Migration Pack", "Could not import Migration Pack: " + safeMessage(ex));
        }
    }

    private File chooseMigrationBackupFile(String scopeName) {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Create Migration Backup");
        dialog.setFilterExtensions(new String[] { "*.ymbackup", "*.zip", "*.*" });
        dialog.setFileName(safeFileName("backup-" + (scopeName == null ? migrationPack.getName() : scopeName)) + ".ymbackup");
        String path = dialog.open();
        if (path == null || path.length() == 0) {
            return null;
        }
        String lower = path.toLowerCase(Locale.ENGLISH);
        if (!lower.endsWith(".ymbackup") && !lower.endsWith(".zip")) {
            path = path + ".ymbackup";
        }
        return new File(path);
    }

    private void restoreMigrationBackup() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Restore Migration Backup");
        dialog.setFilterExtensions(new String[] { "*.ymbackup", "*.zip", "*.*" });
        String path = dialog.open();
        if (path == null || path.length() == 0) {
            return;
        }
        if (!MessageDialog.openQuestion(getSite().getShell(), "Restore Migration Backup",
                "Restore this backup into the connected target environment(s)?\n\n"
                + "Existing before-state definitions/data will be imported back. Objects or rows that did not exist before the backed-up migration will be deleted where the backup contains a delete instruction.\n\n"
                + path)) {
            return;
        }
        final File file = new File(path);
        setBusy(true);
        updateOverviewStatus("Restoring migration backup...", ISharedImages.IMG_ELCL_SYNCED);
        Job job = new Job("Restore Yrell Migrator backup") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final String report;
                try {
                    MigrationBackupService.RestoreResult result = migrationBackupService.restore(file, stores, monitor);
                    report = result.report();
                } catch (Throwable ex) {
                    Activator.logError("Could not restore migration backup.", ex);
                    final String message = safeMessage(ex);
                    if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                        viewer.getControl().getDisplay().asyncExec(new Runnable() {
                            public void run() {
                                setBusy(false);
                                MessageDialog.openError(getSite().getShell(), "Restore Migration Backup", "Could not restore backup: " + message);
                            }
                        });
                    }
                    return Status.CANCEL_STATUS;
                }
                lastMigrationPackRunReport = report;
                if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                    viewer.getControl().getDisplay().asyncExec(new Runnable() {
                        public void run() {
                            setBusy(false);
                            new MigrationReportDialog(getSite().getShell(), "Restore Migration Backup", "Backup restore finished.", report, report.indexOf("FAILED") >= 0).open();
                            appendActivity("Migration backup restored from " + file.getAbsolutePath() + ".");
                            reloadOverviewFromCache(false);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void runMigrationPack() {
        if (migrationPack.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "The current Migration Pack is empty.");
            return;
        }
        runMigrationPackItems(new ArrayList<MigrationPackItem>(migrationPack.getItems()), "full Migration Pack");
    }

    private void runMigrationPackItems(List<MigrationPackItem> items, String scopeLabel) {
        if (items == null || items.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Migration Pack", "No Migration Pack rows were selected.");
            return;
        }
        final List<MigrationPackItem> orderedItems = migrationPack.orderedForRun(items);
        final MigrationPack runPack = packForItems(orderedItems, scopeLabel);
        MigrationPackPreflight.Result preflight = new MigrationPackPreflight(runPack, stores).run();
        lastMigrationPackPreflightReport = preflight.getReport();
        if (!preflight.canRun()) {
            new MigrationReportDialog(getSite().getShell(), "Migration Pack Preflight",
                    "Migration Pack has errors and cannot be run safely.", preflight.getReport(), true).open();
            return;
        }
        String message = "Run " + scopeLabel + " from " + migrationPack.getName() + "?\n\n"
                + runPack.getDefinitionCount() + " definition item(s) and "
                + runPack.getFormDataCount() + " form-data scope(s) will be processed.\n"
                + "Embedded rows: " + preflight.getEmbeddedRows() + "\n"
                + "Preflight: " + preflight.summary() + "\n\n"
                + "The pack contains embedded definitions/data. Only each row's target environment must be connected.\n"
                + "Rows are processed in the recommended execution order, regardless of visual order.";
        if (!MessageDialog.openQuestion(getSite().getShell(), "Run Migration Pack", message + "\n\nA before-state backup will be created before any target changes are written.")) {
            return;
        }
        final File backupFile = chooseMigrationBackupFile(runPack.getName());
        if (backupFile == null) {
            appendActivity("Migration Pack run cancelled because no backup file was selected.");
            return;
        }
        runMigrationPackJob(new ArrayList<MigrationPackItem>(orderedItems), runPack.getName(), backupFile);
    }

    private void runMigrationPackJob(final List<MigrationPackItem> packItems, final String runName, final File backupFile) {
        final long started = System.currentTimeMillis();
        setBusy(true);
        updateOverviewStatus("Running Migration Pack...", ISharedImages.IMG_ELCL_SYNCED);
        appendActivity("Migration Pack run started: " + packItems.size() + " item(s).");
        Job job = new Job("Run Yrell Migrator Migration Pack") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final List<MigrationResult> objectResults = new ArrayList<MigrationResult>();
                final StringBuilder dataReport = new StringBuilder();
                final StringBuilder unresolved = new StringBuilder();
                monitor.beginTask("Running Migration Pack", Math.max(1, packItems.size() * 4));
                String backupReport = "";
                try {
                    monitor.subTask("Creating before-state backup");
                    MigrationBackupService.BackupResult backup = migrationBackupService.createForPack(runName, packItems, stores, backupFile, monitor);
                    backupReport = "Before-state backup created: " + backupFile.getAbsolutePath() + "\n" + backup.summary() + "\n";
                    appendActivity("Before-state backup created: " + backupFile.getAbsolutePath() + ". " + backup.summary());
                    runMigrationPackDefinitions(packItems, MigrationDirection.SOURCE_TO_TARGET, objectResults, unresolved, monitor);
                    runMigrationPackDefinitions(packItems, MigrationDirection.TARGET_TO_SOURCE, objectResults, unresolved, monitor);
                    runMigrationPackData(packItems, dataReport, unresolved, monitor);
                } catch (Throwable ex) {
                    appendDataFailure(unresolved, "Migration Pack failed: " + safeMessage(ex));
                    Activator.logError("Migration Pack run failed.", ex);
                } finally {
                    monitor.done();
                }
                final long finished = System.currentTimeMillis();
                final String finalReport = backupReport + "\n" + buildMigrationPackRunReport(packItems, runName, objectResults, dataReport.toString(), unresolved.toString(), started, finished);
                lastMigrationPackRunReport = finalReport;
                if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                    viewer.getControl().getDisplay().asyncExec(new Runnable() {
                        public void run() {
                            setBusy(false);
                            boolean hasProblems = objectResultsProblemCount(objectResults) > 0 || unresolved.length() > 0;
                            new MigrationReportDialog(getSite().getShell(), "Migration Pack Report",
                                    hasProblems ? "Migration Pack completed with warnings." : "Migration Pack completed.",
                                    finalReport, hasProblems).open();
                            appendActivity("Migration Pack run finished. " + objectResults.size() + " object result row(s). Refreshing overview from local cache.");
                            refreshMigrationPackView();
                            reloadOverviewFromCache(false);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void runMigrationPackDefinitions(List<MigrationPackItem> packItems, MigrationDirection direction,
            List<MigrationResult> objectResults, StringBuilder unresolved, IProgressMonitor monitor) {
        for (MigrationPackItem item : packItems) {
            if (monitor.isCanceled()) return;
            if (item == null || !item.isDefinition() || item.getDirection() != direction) {
                continue;
            }
            IStore target = findStoreByName(item.getTargetServer());
            if (target == null || !target.isConnected()) {
                String message = "Target environment is not connected for embedded definition: "
                        + item.getObjectType() + " " + item.getObjectName() + " → " + item.getTargetServer();
                appendDataFailure(unresolved, message);
                item.markLastRun("Failed", message);
                continue;
            }
            monitor.subTask("Importing embedded definition " + item.getObjectType() + " " + item.getObjectName());
            MigrationResult result = migrationPackPayloadService.importDefinition(item, target, monitor);
            objectResults.add(result);
            item.markLastRun(result == null ? "Unknown" : result.getOutcomeLabel(), result == null ? "No result returned" : result.getDetail());
            monitor.worked(1);
        }
    }

    private void runMigrationPackData(List<MigrationPackItem> packItems, StringBuilder dataReport,
            StringBuilder unresolved, IProgressMonitor monitor) {
        int index = 0;
        for (MigrationPackItem item : packItems) {
            if (monitor.isCanceled()) {
                appendDataFailure(unresolved, "Cancelled before all form-data scopes were processed.");
                return;
            }
            if (item == null || !item.isFormData()) {
                continue;
            }
            index++;
            try {
                IStore target = findStoreByName(item.getTargetServer());
                if (target == null || !target.isConnected()) {
                    String message = "Target environment is not connected for embedded form data: " + item.getFormName()
                            + " → " + item.getTargetServer();
                    appendDataFailure(unresolved, message);
                    item.markLastRun("Failed", message);
                    continue;
                }
                if (!item.hasEmbeddedPayload()) {
                    String message = "Form data " + item.getFormName() + " has no embedded payload. Re-add it to the pack with v0.80.0 or later.";
                    appendDataFailure(unresolved, message);
                    item.markLastRun("Failed", message);
                    continue;
                }
                monitor.subTask("Importing embedded pack form data " + item.getFormName());
                MigrationPackPayloadService.EmbeddedDataResult result = migrationPackPayloadService.importEntries(item, target, monitor);
                if (dataReport.length() == 0) {
                    dataReport.append("Form data\n");
                }
                dataReport.append(index).append(". ").append(item.isDryRun() ? "Dry-run embedded " : "Imported embedded ")
                        .append(item.getFormName()).append(" → ").append(item.getTargetServer())
                        .append(" — read ").append(result.getRead())
                        .append(item.isDryRun() ? ", matched " : ", migrated ").append(result.getCreatedOrUpdated())
                        .append(", failed ").append(result.getFailed()).append('\n');
                if (result.getFailures().length() > 0) {
                    dataReport.append("   Failures: ").append(result.getFailures().replace("\n", "\n   ")).append('\n');
                }
                item.markLastRun(result.getFailed() > 0 ? "Warning" : (item.isDryRun() ? "Dry run" : "Succeeded"), result.summary());
            } catch (Throwable ex) {
                String message = "Form data " + item.getFormName() + ": " + safeMessage(ex);
                appendDataFailure(unresolved, message);
                item.markLastRun("Failed", message);
            }
            monitor.worked(1);
        }
    }

    private BmcDataMigrator.Options optionsFromPackItem(MigrationPackItem item) {
        IStore source = findStoreByName(item.getSourceServer());
        IStore target = findStoreByName(item.getTargetServer());
        if (source == null || target == null) {
            return null;
        }
        BmcDataMigrator.Options opts = new BmcDataMigrator.Options();
        opts.setSourceStore(source);
        opts.setTargetStore(target);
        opts.setFormName(item.getFormName());
        opts.setQualification(item.getQualification());
        opts.setMaxRows(item.getMaxRows());
        opts.setConflictMode(item.getConflictMode());
        opts.setAttachmentPolicy(item.getAttachmentPolicy());
        opts.setFilterToTargetWritableFields(item.isFilterToTargetWritableFields());
        opts.setRunWorkflow(item.isRunWorkflow());
        opts.setDryRun(item.isDryRun());
        return opts;
    }

    private IStore findStoreByName(String name) {
        if (name == null) return null;
        for (IStore store : stores) {
            if (store != null && store.getName() != null && store.getName().equalsIgnoreCase(name)) {
                return store;
            }
        }
        return null;
    }

    private CompareResult findCurrentCompareResult(MigrationPackItem item) {
        if (item == null || session == null || session.getResults() == null) {
            return null;
        }
        for (CompareResult row : session.getResults()) {
            if (row == null) continue;
            if (!safeText(row.getObjectName()).equalsIgnoreCase(item.getObjectName())) continue;
            if (!safeText(row.getObjectType()).equalsIgnoreCase(item.getObjectType())) continue;
            if (item.getSourceServer().length() > 0 && !safeText(row.getSourceServer()).equalsIgnoreCase(item.getSourceServer())) continue;
            if (item.getTargetServer().length() > 0 && !safeText(row.getTargetServer()).equalsIgnoreCase(item.getTargetServer())) continue;
            return row;
        }
        return null;
    }

    private String buildMigrationPackRunReport(List<MigrationPackItem> packItems, String runName, List<MigrationResult> objectResults, String dataReport,
            String unresolved, long startedAtMillis, long finishedAtMillis) {
        StringBuilder report = new StringBuilder();
        List<MigrationPackItem> reportItems = packItems == null ? new ArrayList<MigrationPackItem>() : packItems;
        int definitions = 0;
        int formData = 0;
        for (MigrationPackItem item : reportItems) {
            if (item == null) continue;
            if (item.isDefinition()) definitions++;
            if (item.isFormData()) formData++;
        }
        report.append("Migration Pack: ").append(runName == null || runName.length() == 0 ? migrationPack.getName() : runName).append('\n');
        report.append("Started: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startedAtMillis))).append('\n');
        report.append("Finished: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(finishedAtMillis))).append('\n');
        report.append("Items: ").append(reportItems.size()).append(" (definitions ").append(definitions)
                .append(", form data ").append(formData).append(")\n");
        report.append("Run order: recommended execution phases (security/catalog, forms, menus, workflow, data).\n");
        int orderIndex = 0;
        for (MigrationPackItem item : reportItems) {
            if (item == null) continue;
            orderIndex++;
            report.append("  ").append(orderIndex).append(". ").append(item.executionPhaseLabel()).append(" — ")
                    .append(item.isFormData() ? "Form data " + item.getFormName() : item.getObjectType() + " " + item.getObjectName())
                    .append(" → ").append(item.getTargetServer()).append('\n');
        }
        report.append('\n');
        if (objectResults != null && !objectResults.isEmpty()) {
            report.append(MigrationReportFormatter.formatObjectMigrationReport(MigrationDirection.SOURCE_TO_TARGET,
                    objectResults, startedAtMillis, finishedAtMillis, "Migration Pack definitions"));
            report.append('\n');
        }
        if (dataReport != null && dataReport.length() > 0) {
            report.append(dataReport).append('\n');
        }
        if (unresolved != null && unresolved.length() > 0) {
            report.append("Warnings / not run\n").append(unresolved).append('\n');
        }
        if ((objectResults == null || objectResults.isEmpty()) && (dataReport == null || dataReport.length() == 0)
                && (unresolved == null || unresolved.length() == 0)) {
            report.append("No executable pack rows were found.\n");
        }
        return report.toString();
    }

    private int objectResultsProblemCount(List<MigrationResult> objectResults) {
        return MigrationReportFormatter.countFailures(objectResults) + MigrationReportFormatter.countWarnings(objectResults);
    }

    private void createSettingsTab(org.eclipse.swt.widgets.TabFolder tabs) {
        Composite settingsRoot = new Composite(tabs, SWT.NONE);
        settingsRoot.setLayout(new GridLayout(1, false));
        org.eclipse.swt.widgets.TabItem settingsTab = new org.eclipse.swt.widgets.TabItem(tabs, SWT.NONE);
        settingsTab.setText("Settings");
        settingsTab.setControl(settingsRoot);

        org.eclipse.jface.preference.IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        CompareSettings.initializeDefaults(store);

        Group syncGroup = new Group(settingsRoot, SWT.NONE);
        syncGroup.setText("Sync and cache");
        syncGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        syncGroup.setLayout(new GridLayout(2, false));

        final Text include = settingText(syncGroup, "Include patterns:", store.getString(CompareSettings.KEY_SYNC_INCLUDE_NAME_PATTERNS), "Object name patterns included by Sync. Empty means all objects.");
        final Text exclude = settingText(syncGroup, "Exclude patterns:", store.getString(CompareSettings.KEY_SYNC_EXCLUDE_NAME_PATTERNS), "Object name patterns excluded by Sync. Exclude wins over include.");
        final Text cacheCustomizationTypes = settingText(syncGroup, "Cache customization types:", store.getString(CompareSettings.KEY_SYNC_CACHE_CUSTOMIZATION_TYPES), "Comma-separated customization types read into Sync cache: Base, Custom, Overlay. Default is Custom,Overlay. Group/Role/Report/Template are always included.");
        final Button incremental = settingCheck(syncGroup, "Incremental sync", store.getBoolean(CompareSettings.KEY_SYNC_INCREMENTAL_DEFINITIONS));
        final Button retryErrors = settingCheck(syncGroup, "Retry failed snapshots", store.getBoolean(CompareSettings.KEY_SYNC_RETRY_ERROR_SNAPSHOTS));
        final Button rebuild = settingCheck(syncGroup, "Rebuild snapshots next Sync", store.getBoolean(CompareSettings.KEY_SYNC_FORCE_REFRESH_DEFINITIONS));
        final Button aggressive = settingCheck(syncGroup, "Aggressive metadata fallback", store.getBoolean(CompareSettings.KEY_METADATA_AGGRESSIVE_ENUMERATION));

        Group limitsGroup = new Group(settingsRoot, SWT.NONE);
        limitsGroup.setText("Limits");
        limitsGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        limitsGroup.setLayout(new GridLayout(2, false));
        final Text objectTimeout = settingText(limitsGroup, "Object timeout seconds:", String.valueOf(store.getInt(CompareSettings.KEY_COMPARE_OBJECT_TIMEOUT_SECONDS)), "Timeout for loading a single object or snapshot.");
        final Text metadataTimeout = settingText(limitsGroup, "Metadata timeout seconds:", String.valueOf(store.getInt(CompareSettings.KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS)), "Timeout per metadata enumeration strategy.");
        final Text maxRows = settingText(limitsGroup, "Max overview rows:", String.valueOf(store.getInt(CompareSettings.KEY_SEARCH_MAX_RESULTS)), "Maximum number of cached rows loaded into the overview.");
        final Button showEqual = settingCheck(limitsGroup, "Show equal objects by default", store.getBoolean(CompareSettings.KEY_SHOW_EQUAL_BY_DEFAULT));

        Group ignoreGroup = new Group(settingsRoot, SWT.NONE);
        ignoreGroup.setText("Ignore rules");
        ignoreGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ignoreGroup.setLayout(new GridLayout(2, false));
        final Text ignoreProps = settingText(ignoreGroup, "Ignore properties:", store.getString(CompareSettings.KEY_IGNORE_DIFFERENCE_NAME_CONTAINS), "Comma-separated property names or name fragments ignored in displayed differences.");
        final Text ignoreMasks = settingText(ignoreGroup, "Ignore mask ids:", store.getString(CompareSettings.KEY_IGNORE_MASK_IDS), "Comma-separated BMC compare mask ids ignored by live compare.");
        final Text ignoreInternals = settingText(ignoreGroup, "Ignore internals:", store.getString(CompareSettings.KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS), "Comma-separated internal member names ignored when building fingerprints.");

        Composite buttons = new Composite(settingsRoot, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        buttons.setLayout(new GridLayout(5, false));
        Button save = new Button(buttons, SWT.PUSH);
        save.setText("Save settings");
        save.setImage(sharedImage(ISharedImages.IMG_ETOOL_SAVE_EDIT));
        save.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                try {
                    store.setValue(CompareSettings.KEY_SYNC_INCLUDE_NAME_PATTERNS, include.getText());
                    store.setValue(CompareSettings.KEY_SYNC_EXCLUDE_NAME_PATTERNS, exclude.getText());
                    store.setValue(CompareSettings.KEY_SYNC_CACHE_CUSTOMIZATION_TYPES, cacheCustomizationTypes.getText());
                    store.setValue(CompareSettings.KEY_SYNC_INCREMENTAL_DEFINITIONS, incremental.getSelection());
                    store.setValue(CompareSettings.KEY_SYNC_RETRY_ERROR_SNAPSHOTS, retryErrors.getSelection());
                    store.setValue(CompareSettings.KEY_SYNC_FORCE_REFRESH_DEFINITIONS, rebuild.getSelection());
                    store.setValue(CompareSettings.KEY_METADATA_AGGRESSIVE_ENUMERATION, aggressive.getSelection());
                    store.setValue(CompareSettings.KEY_COMPARE_OBJECT_TIMEOUT_SECONDS, parseIntSetting(objectTimeout.getText(), 180));
                    store.setValue(CompareSettings.KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS, parseIntSetting(metadataTimeout.getText(), 60));
                    store.setValue(CompareSettings.KEY_SEARCH_MAX_RESULTS, parseIntSetting(maxRows.getText(), 1000));
                    store.setValue(CompareSettings.KEY_SHOW_EQUAL_BY_DEFAULT, showEqual.getSelection());
                    store.setValue(CompareSettings.KEY_IGNORE_DIFFERENCE_NAME_CONTAINS, ignoreProps.getText());
                    store.setValue(CompareSettings.KEY_IGNORE_MASK_IDS, ignoreMasks.getText());
                    store.setValue(CompareSettings.KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS, ignoreInternals.getText());
                    modelAdapter.reloadSettings();
                    MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "Settings saved. Run Sync or Refresh overview for cache/search changes to take effect.");
                } catch (RuntimeException ex) {
                    MessageDialog.openError(getSite().getShell(), "Yrell Migrator", "Could not save settings: " + ex.getLocalizedMessage());
                }
            }
        });
        Button defaults = new Button(buttons, SWT.PUSH);
        defaults.setText("Restore defaults");
        defaults.setImage(sharedImage(ISharedImages.IMG_ELCL_SYNCED));
        defaults.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                store.setToDefault(CompareSettings.KEY_SYNC_INCLUDE_NAME_PATTERNS);
                store.setToDefault(CompareSettings.KEY_SYNC_EXCLUDE_NAME_PATTERNS);
                store.setToDefault(CompareSettings.KEY_SYNC_CACHE_CUSTOMIZATION_TYPES);
                store.setToDefault(CompareSettings.KEY_SYNC_INCREMENTAL_DEFINITIONS);
                store.setToDefault(CompareSettings.KEY_SYNC_RETRY_ERROR_SNAPSHOTS);
                store.setToDefault(CompareSettings.KEY_SYNC_FORCE_REFRESH_DEFINITIONS);
                store.setToDefault(CompareSettings.KEY_METADATA_AGGRESSIVE_ENUMERATION);
                store.setToDefault(CompareSettings.KEY_COMPARE_OBJECT_TIMEOUT_SECONDS);
                store.setToDefault(CompareSettings.KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS);
                store.setToDefault(CompareSettings.KEY_SEARCH_MAX_RESULTS);
                store.setToDefault(CompareSettings.KEY_SHOW_EQUAL_BY_DEFAULT);
                store.setToDefault(CompareSettings.KEY_IGNORE_DIFFERENCE_NAME_CONTAINS);
                store.setToDefault(CompareSettings.KEY_IGNORE_MASK_IDS);
                store.setToDefault(CompareSettings.KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS);
                include.setText(store.getString(CompareSettings.KEY_SYNC_INCLUDE_NAME_PATTERNS));
                exclude.setText(store.getString(CompareSettings.KEY_SYNC_EXCLUDE_NAME_PATTERNS));
                cacheCustomizationTypes.setText(store.getString(CompareSettings.KEY_SYNC_CACHE_CUSTOMIZATION_TYPES));
                incremental.setSelection(store.getBoolean(CompareSettings.KEY_SYNC_INCREMENTAL_DEFINITIONS));
                retryErrors.setSelection(store.getBoolean(CompareSettings.KEY_SYNC_RETRY_ERROR_SNAPSHOTS));
                rebuild.setSelection(store.getBoolean(CompareSettings.KEY_SYNC_FORCE_REFRESH_DEFINITIONS));
                aggressive.setSelection(store.getBoolean(CompareSettings.KEY_METADATA_AGGRESSIVE_ENUMERATION));
                objectTimeout.setText(String.valueOf(store.getInt(CompareSettings.KEY_COMPARE_OBJECT_TIMEOUT_SECONDS)));
                metadataTimeout.setText(String.valueOf(store.getInt(CompareSettings.KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS)));
                maxRows.setText(String.valueOf(store.getInt(CompareSettings.KEY_SEARCH_MAX_RESULTS)));
                showEqual.setSelection(store.getBoolean(CompareSettings.KEY_SHOW_EQUAL_BY_DEFAULT));
                ignoreProps.setText(store.getString(CompareSettings.KEY_IGNORE_DIFFERENCE_NAME_CONTAINS));
                ignoreMasks.setText(store.getString(CompareSettings.KEY_IGNORE_MASK_IDS));
                ignoreInternals.setText(store.getString(CompareSettings.KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS));
            }
        });
        Button exportSettings = new Button(buttons, SWT.PUSH);
        exportSettings.setText("Export settings...");
        exportSettings.setImage(sharedImage(ISharedImages.IMG_ETOOL_SAVEAS_EDIT));
        exportSettings.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                exportSettingsToFile(include, exclude, cacheCustomizationTypes, incremental, retryErrors, rebuild, aggressive, objectTimeout, metadataTimeout, maxRows, showEqual, ignoreProps, ignoreMasks, ignoreInternals);
            }
        });

        Button importSettings = new Button(buttons, SWT.PUSH);
        importSettings.setText("Import settings...");
        importSettings.setImage(sharedImage(ISharedImages.IMG_OBJ_FOLDER));
        importSettings.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                importSettingsFromFile(include, exclude, cacheCustomizationTypes, incremental, retryErrors, rebuild, aggressive, objectTimeout, metadataTimeout, maxRows, showEqual, ignoreProps, ignoreMasks, ignoreInternals);
            }
        });
    }

    private Text settingText(Composite parent, String label, String value, String tooltip) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(label);
        Text text = new Text(parent, SWT.BORDER);
        text.setText(value == null ? "" : value);
        text.setToolTipText(tooltip == null ? "" : tooltip);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 520;
        text.setLayoutData(gd);
        return text;
    }

    private Button settingCheck(Composite parent, String label, boolean selected) {
        new Label(parent, SWT.NONE).setText("");
        Button button = new Button(parent, SWT.CHECK);
        button.setText(label);
        button.setSelection(selected);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return button;
    }

    private int parseIntSetting(String text, int fallback) {
        try {
            return Integer.parseInt((text == null ? "" : text).trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private void exportSettingsToFile(Text include, Text exclude, Text cacheCustomizationTypes, Button incremental, Button retryErrors, Button rebuild, Button aggressive,
            Text objectTimeout, Text metadataTimeout, Text maxRows, Button showEqual, Text ignoreProps, Text ignoreMasks, Text ignoreInternals) {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export Yrell Migrator settings");
        dialog.setFilterExtensions(new String[] { "*.properties", "*.*" });
        dialog.setFileName("yrell-migrator-settings.properties");
        String path = dialog.open();
        if (path == null) {
            return;
        }
        try {
            Properties props = new Properties();
            props.setProperty(CompareSettings.KEY_SYNC_INCLUDE_NAME_PATTERNS, include.getText());
            props.setProperty(CompareSettings.KEY_SYNC_EXCLUDE_NAME_PATTERNS, exclude.getText());
            props.setProperty(CompareSettings.KEY_SYNC_CACHE_CUSTOMIZATION_TYPES, cacheCustomizationTypes.getText());
            props.setProperty(CompareSettings.KEY_SYNC_INCREMENTAL_DEFINITIONS, String.valueOf(incremental.getSelection()));
            props.setProperty(CompareSettings.KEY_SYNC_RETRY_ERROR_SNAPSHOTS, String.valueOf(retryErrors.getSelection()));
            props.setProperty(CompareSettings.KEY_SYNC_FORCE_REFRESH_DEFINITIONS, String.valueOf(rebuild.getSelection()));
            props.setProperty(CompareSettings.KEY_METADATA_AGGRESSIVE_ENUMERATION, String.valueOf(aggressive.getSelection()));
            props.setProperty(CompareSettings.KEY_COMPARE_OBJECT_TIMEOUT_SECONDS, objectTimeout.getText());
            props.setProperty(CompareSettings.KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS, metadataTimeout.getText());
            props.setProperty(CompareSettings.KEY_SEARCH_MAX_RESULTS, maxRows.getText());
            props.setProperty(CompareSettings.KEY_SHOW_EQUAL_BY_DEFAULT, String.valueOf(showEqual.getSelection()));
            props.setProperty(CompareSettings.KEY_IGNORE_DIFFERENCE_NAME_CONTAINS, ignoreProps.getText());
            props.setProperty(CompareSettings.KEY_IGNORE_MASK_IDS, ignoreMasks.getText());
            props.setProperty(CompareSettings.KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS, ignoreInternals.getText());
            FileOutputStream out = new FileOutputStream(path);
            try {
                props.store(out, "Yrell Migrator settings");
            } finally {
                out.close();
            }
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "Settings exported to:\n" + path);
        } catch (Throwable ex) {
            MessageDialog.openError(getSite().getShell(), "Yrell Migrator", "Could not export settings: " + safeMessage(ex));
        }
    }

    private void importSettingsFromFile(Text include, Text exclude, Text cacheCustomizationTypes, Button incremental, Button retryErrors, Button rebuild, Button aggressive,
            Text objectTimeout, Text metadataTimeout, Text maxRows, Button showEqual, Text ignoreProps, Text ignoreMasks, Text ignoreInternals) {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Import Yrell Migrator settings");
        dialog.setFilterExtensions(new String[] { "*.properties", "*.*" });
        String path = dialog.open();
        if (path == null) {
            return;
        }
        try {
            Properties props = new Properties();
            FileInputStream in = new FileInputStream(path);
            try {
                props.load(in);
            } finally {
                in.close();
            }
            include.setText(props.getProperty(CompareSettings.KEY_SYNC_INCLUDE_NAME_PATTERNS, include.getText()));
            exclude.setText(props.getProperty(CompareSettings.KEY_SYNC_EXCLUDE_NAME_PATTERNS, exclude.getText()));
            cacheCustomizationTypes.setText(props.getProperty(CompareSettings.KEY_SYNC_CACHE_CUSTOMIZATION_TYPES, cacheCustomizationTypes.getText()));
            incremental.setSelection(Boolean.valueOf(props.getProperty(CompareSettings.KEY_SYNC_INCREMENTAL_DEFINITIONS, String.valueOf(incremental.getSelection()))).booleanValue());
            retryErrors.setSelection(Boolean.valueOf(props.getProperty(CompareSettings.KEY_SYNC_RETRY_ERROR_SNAPSHOTS, String.valueOf(retryErrors.getSelection()))).booleanValue());
            rebuild.setSelection(Boolean.valueOf(props.getProperty(CompareSettings.KEY_SYNC_FORCE_REFRESH_DEFINITIONS, String.valueOf(rebuild.getSelection()))).booleanValue());
            aggressive.setSelection(Boolean.valueOf(props.getProperty(CompareSettings.KEY_METADATA_AGGRESSIVE_ENUMERATION, String.valueOf(aggressive.getSelection()))).booleanValue());
            objectTimeout.setText(props.getProperty(CompareSettings.KEY_COMPARE_OBJECT_TIMEOUT_SECONDS, objectTimeout.getText()));
            metadataTimeout.setText(props.getProperty(CompareSettings.KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS, metadataTimeout.getText()));
            maxRows.setText(props.getProperty(CompareSettings.KEY_SEARCH_MAX_RESULTS, maxRows.getText()));
            showEqual.setSelection(Boolean.valueOf(props.getProperty(CompareSettings.KEY_SHOW_EQUAL_BY_DEFAULT, String.valueOf(showEqual.getSelection()))).booleanValue());
            ignoreProps.setText(props.getProperty(CompareSettings.KEY_IGNORE_DIFFERENCE_NAME_CONTAINS, ignoreProps.getText()));
            ignoreMasks.setText(props.getProperty(CompareSettings.KEY_IGNORE_MASK_IDS, ignoreMasks.getText()));
            ignoreInternals.setText(props.getProperty(CompareSettings.KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS, ignoreInternals.getText()));
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "Settings imported. Click Save settings to apply them.");
        } catch (Throwable ex) {
            MessageDialog.openError(getSite().getShell(), "Yrell Migrator", "Could not import settings: " + safeMessage(ex));
        }
    }

    private void createActivityLog(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Activity");
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        group.setLayout(new GridLayout(1, false));

        activityLog = new Text(group, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, false);
        data.heightHint = 70;
        activityLog.setLayoutData(data);
        activityLog.setText("Ready. Sync and overview activity will be shown here.\n");
    }

    private void loadStoresAndTypesAsync() {
        final Display display = getViewSite() == null || getViewSite().getShell() == null
                ? Display.getDefault()
                : getViewSite().getShell().getDisplay();
        environmentAndTypeLoadComplete = false;
        lastInitialLoadMillis = 0L;
        appendActivity("Loading connected environments and object type registry in the background...");
        Job job = new Job("Yrell Migrator startup") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final long started = System.currentTimeMillis();
                final List<IStore> loadedStores;
                final List<BmcTypeGroup> loadedGroups;
                try {
                    loadedStores = modelAdapter.getConnectedStores();
                    loadedGroups = modelAdapter.getSearchTypeGroups();
                } catch (final Throwable ex) {
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(new Runnable() {
                            public void run() {
                                setBusy(false);
                                updateOverviewStatus("Startup discovery failed: " + safeMessage(ex), ISharedImages.IMG_OBJS_ERROR_TSK);
                                appendActivity("Startup discovery failed: " + safeMessage(ex));
                            }
                        });
                    }
                    return Status.OK_STATUS;
                }
                final long elapsed = System.currentTimeMillis() - started;
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            applyLoadedStoresAndTypes(loadedStores, loadedGroups, elapsed);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.setPriority(Job.SHORT);
        job.schedule();
    }

    private void applyLoadedStoresAndTypes(List<IStore> loadedStores, List<BmcTypeGroup> loadedGroups, long elapsedMillis) {
        stores = loadedStores == null ? new ArrayList<IStore>() : loadedStores;
        groups = loadedGroups == null ? new ArrayList<BmcTypeGroup>() : loadedGroups;
        environmentAndTypeLoadComplete = true;
        lastInitialLoadMillis = elapsedMillis;

        if (sourceCombo != null && !sourceCombo.isDisposed()) {
            sourceCombo.removeAll();
        }
        if (targetCombo != null && !targetCombo.isDisposed()) {
            targetCombo.removeAll();
        }
        for (IStore store : stores) {
            if (sourceCombo != null && !sourceCombo.isDisposed()) {
                sourceCombo.add(store.getName());
            }
            if (targetCombo != null && !targetCombo.isDisposed()) {
                targetCombo.add(store.getName());
            }
        }
        if (stores.size() > 0 && sourceCombo != null && !sourceCombo.isDisposed()) {
            sourceCombo.select(0);
        }
        if (targetCombo != null && !targetCombo.isDisposed()) {
            if (stores.size() > 1) {
                targetCombo.select(1);
            } else if (stores.size() > 0) {
                targetCombo.select(0);
            }
        }

        appendActivity("Startup discovery finished in " + elapsedMillis + " ms. Environments=" + stores.size() + ", object type groups=" + groups.size() + ".");
        updateOverviewStatus("Ready. Loading real cache overview...", ISharedImages.IMG_OBJS_INFO_TSK);
        if (stores.size() > 1) {
            reloadOverviewFromCache(false);
        } else {
            updateTypeNavigator();
            setBusy(false);
        }
        updateActionEnablement();
    }

    private void reloadOverviewFromCache(boolean explicit) {
        if (!environmentAndTypeLoadComplete) {
            if (explicit) {
                MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "Developer Studio environments and object types are still loading. Try again in a moment.");
            }
            return;
        }
        final IStore source = selectedStore(sourceCombo);
        final IStore target = selectedStore(targetCombo);
        final BmcTypeGroup allTypes = syncAllTypeGroup();
        if (source == null || target == null || source == target || source.getName().equalsIgnoreCase(target.getName())) {
            if (explicit) {
                MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "Select two different connected environments.");
            }
            updateTypeNavigator();
            return;
        }
        if (allTypes == null || allTypes.getTypes().isEmpty()) {
            if (explicit) {
                MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "No object types are available.");
            }
            return;
        }
        final Display display = getViewSite().getShell().getDisplay();
        setBusy(true);
        clearActivityLog();
        appendActivity("Refreshing overview from local cache. Source=" + source.getName() + ", Target=" + target.getName() + ".");
        updateOverviewStatus("Loading overview from local cache...", ISharedImages.IMG_OBJS_INFO_TSK);
        Job job = new Job("Yrell Migrator cache overview") {
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
                                updateOverviewStatus(message, ISharedImages.IMG_OBJS_INFO_TSK);
                            }
                        });
                    }
                };
                monitor.beginTask("Loading Yrell Migrator cache overview", IProgressMonitor.UNKNOWN);
                final long cacheStarted = System.currentTimeMillis();
                final List<CompareResult> rows;
                try {
                    rows = modelAdapter.cachedCompareSearch(source, target, allTypes.getTypes(), "",
                            true, true, true, Integer.MAX_VALUE, monitor, progress);
                } catch (final Throwable ex) {
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(new Runnable() {
                            public void run() {
                                setBusy(false);
                                appendActivity("Overview cache load failed: " + safeMessage(ex));
                                updateOverviewStatus("Cache load failed: " + safeMessage(ex), ISharedImages.IMG_OBJS_ERROR_TSK);
                            }
                        });
                    }
                    return Status.OK_STATUS;
                }
                final long cacheElapsed = System.currentTimeMillis() - cacheStarted;
                monitor.done();
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            setBusy(false);
                            String label = "Yrell Migrator overview: " + source.getName() + " → " + target.getName();
                            setSession(new ComparisonSession(label, rows));
                            CountSummary count = createCountSummary(rows);
                            appendActivity("Overview loaded from disk cache in " + cacheElapsed + " ms. " + rows.size() + " object(s): " + count.changed + " different, "
                                    + count.missing + " missing, " + count.errors + " errors, " + count.equal + " equal.");
                            updateOverviewStatus("Overview loaded from cache in " + cacheElapsed + " ms. " + rows.size() + " object(s).", ISharedImages.IMG_OBJS_INFO_TSK);
                        }
                    });
                }
                return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.setUser(false);
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
        CompareSettings settings = CompareSettings.load();
        appendActivity("Sync started. All known object types are included. Overview filters do not limit Sync.");
        appendActivity("Sync name filter: " + settings.describeSyncFilters() + ". Groups and Roles are always included.");
        updateOverviewStatus("Sync for " + syncStores.size() + " environment(s)...", ISharedImages.IMG_ELCL_SYNCED);

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
                                updateOverviewStatus(message, ISharedImages.IMG_ELCL_SYNCED);
                            }
                        });
                    }
                };
                final CacheStats stats = modelAdapter.syncDefinitionCache(syncStores, typeGroup.getTypes(), monitor, progress);
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            setBusy(false);
                            appendActivity("Sync complete. Metadata summary: " + stats.toSummary() + ". Reloading overview from cache.");
                            updateOverviewStatus("Sync complete. Reloading overview...", ISharedImages.IMG_OBJS_INFO_TSK);
                            reloadOverviewFromCache(false);
                        }
                    });
                }
                return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
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

    private IStore selectedStore(Combo combo) {
        int index = combo == null ? -1 : combo.getSelectionIndex();
        if (index < 0 || index >= stores.size()) {
            return null;
        }
        return stores.get(index);
    }

    private String selectedNavigatorTypeFromList() {
        if (typeNavigator == null || typeNavigator.isDisposed()) {
            return null;
        }
        String value = typeNavigatorIndex.get(Integer.valueOf(typeNavigator.getSelectionIndex()));
        return value == null || value.length() == 0 ? null : value;
    }

    private List<IModelType> modelTypesForNavigatorType(String typeLabel) {
        if (typeLabel == null || typeLabel.length() == 0) {
            return Collections.emptyList();
        }
        List<IModelType> result = new ArrayList<IModelType>();
        for (BmcTypeGroup group : groups) {
            if (group == null || group.getTypes() == null) {
                continue;
            }
            if (group.getLabel() != null && group.getLabel().equalsIgnoreCase(typeLabel)) {
                result.addAll(group.getTypes());
            } else if (group.getTypes().size() == 1) {
                IModelType type = group.getTypes().get(0);
                if (type != null && type.getTypeName() != null && type.getTypeName().equalsIgnoreCase(typeLabel)) {
                    result.add(type);
                }
            }
        }
        if (result.isEmpty()) {
            BmcTypeGroup all = syncAllTypeGroup();
            if (all != null) {
                for (IModelType type : all.getTypes()) {
                    if (type != null && type.getTypeName() != null && type.getTypeName().equalsIgnoreCase(typeLabel)) {
                        result.add(type);
                    }
                }
            }
        }
        return result;
    }

    private void updateTypeNavigator() {
        if (typeNavigator == null || typeNavigator.isDisposed() || session == null) {
            return;
        }
        String previous = selectedNavigatorType;
        FilterSnapshot filter = captureFilterSnapshot();
        java.util.Map<String, CountSummary> counts = new java.util.TreeMap<String, CountSummary>(String.CASE_INSENSITIVE_ORDER);
        CountSummary allSummary = new CountSummary();
        for (CompareResult result : session.getResults()) {
            if (!matchesNavigatorContext(result, filter)) {
                continue;
            }
            addToCount(allSummary, result);
            String type = result.getObjectType();
            if (type == null || type.length() == 0) {
                type = "<unknown>";
            }
            CountSummary summary = counts.get(type);
            if (summary == null) {
                summary = new CountSummary();
                counts.put(type, summary);
            }
            addToCount(summary, result);
        }

        java.util.List<java.util.Map.Entry<String, CountSummary>> entries = new java.util.ArrayList<java.util.Map.Entry<String, CountSummary>>(counts.entrySet());
        java.util.Collections.sort(entries, new java.util.Comparator<java.util.Map.Entry<String, CountSummary>>() {
            public int compare(java.util.Map.Entry<String, CountSummary> left, java.util.Map.Entry<String, CountSummary> right) {
                int leftWeight = typeSortWeight(left.getKey());
                int rightWeight = typeSortWeight(right.getKey());
                if (leftWeight != rightWeight) {
                    return leftWeight - rightWeight;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(left.getKey(), right.getKey());
            }
        });

        boolean showClean = true;
        typeNavigator.removeAll();
        typeNavigatorIndex.clear();
        int selectIndex = -1;
        int listIndex = 0;
        addTypeNavigatorItem("All", allSummary, null);
        typeNavigatorIndex.put(Integer.valueOf(listIndex), "");
        if (previous == null || previous.length() == 0) {
            selectIndex = listIndex;
        }
        listIndex++;
        for (java.util.Map.Entry<String, CountSummary> entry : entries) {
            CountSummary summary = entry.getValue();
            if (!showClean && !summary.hasIssues()) {
                continue;
            }
            addTypeNavigatorItem(entry.getKey(), summary, entry.getKey());
            typeNavigatorIndex.put(Integer.valueOf(listIndex), entry.getKey());
            if (previous != null && previous.equals(entry.getKey())) {
                selectIndex = listIndex;
            }
            listIndex++;
        }
        if (typeNavigator.getItemCount() == 0) {
            selectedNavigatorType = null;
            return;
        }
        if (selectIndex < 0) {
            selectIndex = 0;
        }
        typeNavigator.select(selectIndex);
        selectedNavigatorType = typeNavigatorIndex.get(Integer.valueOf(selectIndex));
    }

    private int typeSortWeight(String type) {
        if (type == null || groups == null) {
            return 100000;
        }
        for (int i = 0; i < groups.size(); i++) {
            BmcTypeGroup group = groups.get(i);
            if (group == null || group.getLabel() == null) {
                continue;
            }
            // Only use Developer Studio's real object-type rows for ordering. Convenience groups
            // such as "Workflow" are intentionally ignored here.
            if (group.getTypes() != null && group.getTypes().size() == 1 && group.getLabel().equalsIgnoreCase(type)) {
                return i;
            }
        }
        return 100000;
    }

    private void addTypeNavigatorItem(String label, CountSummary count, String type) {
        TableItem item = new TableItem(typeNavigator, SWT.NONE);
        item.setText(formatTypeNavigatorLabel(label, count));
        item.setImage(typeNavigatorImage(type == null ? label : type));
        item.setForeground(typeNavigatorForeground(count));
        item.setData(type);
    }

    private String formatTypeNavigatorLabel(String type, CountSummary count) {
        int total = count == null ? 0 : count.total();
        return type + " (" + total + ")";
    }

    private Color typeNavigatorForeground(CountSummary count) {
        if (typeNavigator == null || typeNavigator.isDisposed()) {
            return null;
        }
        Display display = typeNavigator.getDisplay();
        if (count == null || count.total() == 0) {
            return display.getSystemColor(SWT.COLOR_DARK_GRAY);
        }
        if (count.errors > 0) {
            return display.getSystemColor(SWT.COLOR_RED);
        }
        if (count.missing > 0) {
            return display.getSystemColor(SWT.COLOR_DARK_YELLOW);
        }
        if (count.changed > 0 || count.unknown > 0) {
            return display.getSystemColor(SWT.COLOR_DARK_BLUE);
        }
        return display.getSystemColor(SWT.COLOR_DARK_GREEN);
    }


    private Image typeNavigatorImage(String type) {
        String visibleType = type == null ? "" : type.trim();
        if (visibleType.equalsIgnoreCase("All")) {
            Image folder = sharedImage(ISharedImages.IMG_OBJ_FOLDER);
            if (folder != null) {
                return folder;
            }
        }
        String key = visibleType.toLowerCase(Locale.ENGLISH);
        Image existing = objectTypeImages.get(key);
        if (existing != null && !existing.isDisposed()) {
            return existing;
        }
        org.eclipse.jface.resource.ImageDescriptor descriptor = bmcObjectTypeDescriptor(type);
        if (descriptor != null) {
            try {
                Image image = descriptor.createImage(false);
                if (image != null) {
                    objectTypeImages.put(key, image);
                    return image;
                }
            } catch (Throwable ignored) {
            }
        }
        // Do not cache or dispose workbench shared images; Eclipse owns their lifecycle.
        return fallbackObjectTypeImage(type);
    }

    private org.eclipse.jface.resource.ImageDescriptor bmcObjectTypeDescriptor(String type) {
        String path = bmcObjectTypeIconPath(type);
        if (path == null || path.length() == 0) {
            return null;
        }
        org.eclipse.jface.resource.ImageDescriptor descriptor = null;
        try {
            descriptor = org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin("com.bmc.arsys.studio.commonui", path);
        } catch (Throwable ignored) {
            descriptor = null;
        }
        if (descriptor == null) {
            try {
                descriptor = org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin("com.bmc.arsys.studio.ui", path);
            } catch (Throwable ignored) {
                descriptor = null;
            }
        }
        return descriptor;
    }

    private String bmcObjectTypeIconPath(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ENGLISH).replace(" ", "");
        if (normalized.equals("form") || normalized.endsWith("form")) return "icons/Forms.gif";
        if (normalized.equals("activelink")) return "icons/ActiveLinks.gif";
        if (normalized.equals("activelinkguide")) return "icons/ActiveLinkGuides.gif";
        if (normalized.equals("filter")) return "icons/Filters.gif";
        if (normalized.equals("filterguide")) return "icons/FilterGuides.gif";
        if (normalized.equals("escalation")) return "icons/Escalations.gif";
        if (normalized.equals("menu")) return "icons/Menus.gif";
        if (normalized.equals("group")) return "icons/Groups.gif";
        if (normalized.equals("role")) return "icons/Roles.gif";
        if (normalized.equals("application")) return "icons/Application.gif";
        if (normalized.equals("packinglist")) return "icons/PackingLists.gif";
        if (normalized.equals("association")) return "icons/Associations.gif";
        if (normalized.equals("image")) return "icons/Image.gif";
        if (normalized.equals("supportfile")) return "icons/FileMenuType.gif";
        if (normalized.equals("webservice")) return "icons/WebServices1.gif";
        if (normalized.equals("report") || normalized.equals("reporttype")) return "icons/FormData.gif";
        if (normalized.equals("template")) return "icons/richtextformat.gif";
        if (normalized.equals("message")) return "icons/DefaultToolbarImage.gif";
        if (normalized.indexOf("flashboard") >= 0) return "icons/DefaultToolbarImage.gif";
        if (normalized.indexOf("datavisualization") >= 0) return "icons/ApplicationListField.gif";
        if (normalized.indexOf("distributed") >= 0) return "icons/DefaultToolbarImage.gif";
        return "icons/DefaultToolbarImage.gif";
    }

    private Image fallbackObjectTypeImage(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ENGLISH);
        if (normalized.indexOf("image") >= 0) return sharedImage(ISharedImages.IMG_OBJ_FILE);
        if (normalized.indexOf("group") >= 0 || normalized.indexOf("role") >= 0) return sharedImage(ISharedImages.IMG_OBJ_ELEMENT);
        if (normalized.indexOf("form") >= 0) return sharedImage(ISharedImages.IMG_OBJ_FOLDER);
        return sharedImage(ISharedImages.IMG_OBJ_FILE);
    }

    @Override
    public void dispose() {
        for (Image image : objectTypeImages.values()) {
            if (image != null && !image.isDisposed()) {
                image.dispose();
            }
        }
        objectTypeImages.clear();
        super.dispose();
    }

    private void clearActivityLog() {
        if (activityLog != null && !activityLog.isDisposed()) {
            activityLog.setText("");
        }
    }

    private void appendActivity(final String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (activityLog != null && !activityLog.isDisposed()) {
            Display display = activityLog.getDisplay();
            if (display != null && display.getThread() != Thread.currentThread()) {
                display.asyncExec(new Runnable() {
                    public void run() { appendActivity(text); }
                });
                return;
            }
        }
        if (activityLog == null || activityLog.isDisposed()) {
            return;
        }
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        activityLog.append("[" + timestamp + "] " + text + "\n");
        activityLog.setSelection(activityLog.getCharCount());
    }

    private void setBusy(final boolean busy) {
        Display display = Display.getDefault();
        if (display != null && display.getThread() != Thread.currentThread()) {
            display.asyncExec(new Runnable() {
                public void run() { setBusy(busy); }
            });
            return;
        }
        if (syncButton != null && !syncButton.isDisposed()) {
            syncButton.setEnabled(!busy);
        }
        if (reloadButton != null && !reloadButton.isDisposed()) {
            reloadButton.setEnabled(!busy);
        }
        if (cacheButton != null && !cacheButton.isDisposed()) {
            cacheButton.setEnabled(!busy);
        }
        if (operationProgress != null && !operationProgress.isDisposed()) {
            GridData data = (GridData) operationProgress.getLayoutData();
            data.exclude = !busy;
            operationProgress.setVisible(busy);
            operationProgress.getParent().layout(true, true);
        }
    }

    private void updateOverviewStatus(final String text, final String imageKey) {
        Display display = Display.getDefault();
        if (display != null && display.getThread() != Thread.currentThread()) {
            display.asyncExec(new Runnable() {
                public void run() { updateOverviewStatus(text, imageKey); }
            });
            return;
        }
        if (overviewStatusLabel != null && !overviewStatusLabel.isDisposed()) {
            overviewStatusLabel.setText(text == null ? "" : text);
            overviewStatusLabel.setImage(sharedImage(imageKey));
        }
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        }
    }

    public void setSession(ComparisonSession session) {
        this.session = session == null ? new ComparisonSession("No comparison", new ArrayList<CompareResult>()) : session;
        resultUiIndex.clear();
        scopeCountSummaryValid = false;
        automaticSortSkipped = false;
        userSortRequested = false;
        updateLargeRepositoryMode();
        refresh(true);
    }

    private void createFilterBar(Composite parent) {
        Composite filtersRoot = new Composite(parent, SWT.NONE);
        filtersRoot.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 0;
        rootLayout.marginHeight = 0;
        rootLayout.verticalSpacing = 3;
        filtersRoot.setLayout(rootLayout);

        Composite primaryFilters = new Composite(filtersRoot, SWT.NONE);
        primaryFilters.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout primaryLayout = new GridLayout(10, false);
        primaryLayout.marginWidth = 0;
        primaryLayout.marginHeight = 0;
        primaryFilters.setLayout(primaryLayout);

        Label searchLabel = new Label(primaryFilters, SWT.NONE);
        searchLabel.setText("Search:");

        searchText = new Text(primaryFilters, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        searchText.setMessage("name, type, user, date, server or detail");
        GridData searchData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        searchData.widthHint = 260;
        searchText.setLayoutData(searchData);
        searchText.addModifyListener(new ModifyListener() {
            public void modifyText(ModifyEvent event) {
                scheduleFilterRefresh(false);
            }
        });

        Label customizationLabel = new Label(primaryFilters, SWT.NONE);
        customizationLabel.setText("Customization:");

        customizationBaseButton = createFilterButton(primaryFilters, "Base", false);
        customizationBaseButton.setToolTipText("Show Base/unknown customization objects. Default is off to hide BMC base objects.");
        customizationCustomButton = createFilterButton(primaryFilters, "Custom", true);
        customizationCustomButton.setToolTipText("Show custom objects.");
        customizationOverlayButton = createFilterButton(primaryFilters, "Overlay", true);
        customizationOverlayButton.setToolTipText("Show overlay objects.");

        Label changedByLabel = new Label(primaryFilters, SWT.NONE);
        changedByLabel.setText("Changed by:");

        changedByText = new Text(primaryFilters, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        changedByText.setMessage("user contains...");
        GridData changedByData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        changedByData.widthHint = 145;
        changedByText.setLayoutData(changedByData);
        changedByText.setToolTipText("Type part of a source or target Changed by value. Empty means all users.");
        changedByText.addModifyListener(new ModifyListener() {
            public void modifyText(ModifyEvent event) {
                if (!rebuildingFilterOptions) {
                    scheduleFilterRefresh(false);
                }
            }
        });

        modifiedAfterEnabledButton = new Button(primaryFilters, SWT.CHECK);
        modifiedAfterEnabledButton.setText("Modified after:");
        modifiedAfterEnabledButton.setToolTipText("Filter rows where source or target Modified Date is after the selected date.");
        modifiedAfterEnabledButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                if (modifiedAfterDate != null && !modifiedAfterDate.isDisposed()) {
                    modifiedAfterDate.setEnabled(modifiedAfterEnabledButton.getSelection());
                }
                scheduleFilterRefresh(false);
            }
        });

        modifiedAfterDate = new DateTime(primaryFilters, SWT.DATE | SWT.DROP_DOWN);
        GridData modifiedData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        modifiedData.widthHint = 118;
        modifiedAfterDate.setLayoutData(modifiedData);
        modifiedAfterDate.setEnabled(false);
        modifiedAfterDate.setToolTipText("Modified Date must be after this date on either source or target.");
        modifiedAfterDate.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                scheduleFilterRefresh(false);
            }
        });

        Composite statusFilters = new Composite(filtersRoot, SWT.NONE);
        statusFilters.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout statusLayout = new GridLayout(10, false);
        statusLayout.marginWidth = 0;
        statusLayout.marginHeight = 0;
        statusFilters.setLayout(statusLayout);

        changedButton = createFilterButton(statusFilters, "Different", true);
        missingButton = createFilterButton(statusFilters, "Missing", true);
        errorButton = createFilterButton(statusFilters, "Errors", true);
        equalButton = createFilterButton(statusFilters, "Equal", false);
        stillDifferentButton = createFilterButton(statusFilters, "Still different", false);
        stillDifferentButton.setToolTipText("Show only rows that were classified as still different after post-migration verification.");
        permissionsDiffButton = createFilterButton(statusFilters, "Permissions", false);
        permissionsDiffButton.setToolTipText("Show only rows with permission/group related differences.");
        auditDiffButton = createFilterButton(statusFilters, "Audit", false);
        auditDiffButton.setToolTipText("Show only rows with audit-related differences. The status filters still apply.");

        Button allButton = new Button(statusFilters, SWT.PUSH);
        allButton.setText("All statuses");
        allButton.setToolTipText("Show all status groups");
        allButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                changedButton.setSelection(true);
                missingButton.setSelection(true);
                equalButton.setSelection(true);
                errorButton.setSelection(true);
                scheduleFilterRefresh(false);
            }
        });

        Button issuesButton = new Button(statusFilters, SWT.PUSH);
        issuesButton.setText("Issues preset");
        issuesButton.setToolTipText("Show the normal large-repository working set: Different, Missing and Errors; hide Equal/Base rows.");
        issuesButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                applyIssuesPreset();
            }
        });

        Button resetButton = new Button(statusFilters, SWT.PUSH);
        resetButton.setText("Reset filters");
        resetButton.setToolTipText("Clear search and restore the default useful filters");
        resetButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                resetFilters();
            }
        });
    }

    private Button createFilterButton(Composite parent, String text, boolean selected) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(text);
        button.setSelection(selected);
        button.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                scheduleFilterRefresh(false);
            }
        });
        return button;
    }

    private void createViewer(Composite parent) {
        viewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.VIRTUAL);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.setUseHashlookup(true);
        viewer.setContentProvider(new ILazyContentProvider() {
            public void updateElement(int index) {
                if (index >= 0 && index < visibleResults.size()) {
                    viewer.replace(visibleResults.get(index), index);
                }
            }
            public void dispose() {
            }
            public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            }
        });
        ColumnViewerToolTipSupport.enableFor(viewer);

        comparator = new ResultComparator();

        createColumns();
        hookDoubleClick();
        hookSelectionChanged();
        hookMouseLinks();
    }

    private void createColumns() {
        addColumn("Status", 135, COL_STATUS, new StatusLabelProvider());
        addColumn("Object name", 330, COL_NAME, new LinkLabelProvider() {
            @Override
            protected String getLinkText(CompareResult result) {
                return result.getObjectName();
            }

            @Override
            protected boolean isLink(CompareResult result) {
                return canOpenObject(result);
            }

            @Override
            protected String getTooltip(CompareResult result) {
                return canOpenObject(result)
                        ? "Open the object itself. Source is preferred when both sides exist; use the context menu to open a specific side."
                        : "Use Show Details to inspect cached differences.";
            }
        });
        addColumn("Object type", 125, COL_TYPE, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((CompareResult) element).getObjectType();
            }

            @Override
            public String getToolTipText(Object element) {
                return "Object type";
            }
        });
        addColumn("Customization", 105, COL_CUSTOMIZATION_TYPE, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((CompareResult) element).getCustomizationTypeSummary();
            }

            @Override
            public String getToolTipText(Object element) {
                CompareResult result = (CompareResult) element;
                return "Source customization: " + result.getSourceCustomizationType()
                        + " | Target customization: " + result.getTargetCustomizationType();
            }
        });
        addColumn("Form", 160, COL_FORM, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return primaryFormColumnText((CompareResult) element);
            }

            @Override
            public String getToolTipText(Object element) {
                String value = primaryFormColumnText((CompareResult) element);
                return value.length() == 0 ? "Primary form, when the object type exposes one." : value;
            }
        });
        addColumn("Form Type", 105, COL_FORM_TYPE, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return formTypeColumnText((CompareResult) element);
            }

            @Override
            public String getToolTipText(Object element) {
                String value = formTypeColumnText((CompareResult) element);
                return value.length() == 0 ? "Form schema type for Form objects: Regular, Display only, Join, View or Vendor." : value;
            }
        });
        addColumn("Key / ID", 115, COL_CONTEXT, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return contextColumnText((CompareResult) element);
            }

            @Override
            public String getToolTipText(Object element) {
                String value = contextColumnText((CompareResult) element);
                return value.length() == 0 ? "Optional key information for the current object type. For workflow this shows Enabled/Disabled when available." : value;
            }
        });
        addColumn("Open", 80, COL_OPEN, new LinkLabelProvider() {
            @Override
            protected String getLinkText(CompareResult result) {
                boolean source = canOpenSource(result);
                boolean target = canOpenTarget(result);
                if (source && target) {
                    return "Object";
                }
                if (source) {
                    return "Source";
                }
                if (target) {
                    return "Target";
                }
                return "";
            }

            @Override
            protected boolean isLink(CompareResult result) {
                return canOpenObject(result);
            }

            @Override
            protected String getTooltip(CompareResult result) {
                boolean source = canOpenSource(result);
                boolean target = canOpenTarget(result);
                if (source && target) {
                    return "Open object. Source is preferred; use the context menu to open a specific side.";
                }
                if (source) {
                    return result.getSourceItem() == null ? "Resolve and open source object" : "Open source object";
                }
                if (target) {
                    return result.getTargetItem() == null ? "Resolve and open target object" : "Open target object";
                }
                return null;
            }
        });
        addColumn("Source modified", 150, COL_SOURCE_MODIFIED, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return UiStrings.timestamp(((CompareResult) element).getSourceModified());
            }

            @Override
            public String getToolTipText(Object element) {
                return UiStrings.timestamp(((CompareResult) element).getSourceModified());
            }
        });
        addColumn("Target modified", 150, COL_TARGET_MODIFIED, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return UiStrings.timestamp(((CompareResult) element).getTargetModified());
            }

            @Override
            public String getToolTipText(Object element) {
                return UiStrings.timestamp(((CompareResult) element).getTargetModified());
            }
        });
        addColumn("Source changed by", 135, COL_SOURCE_CHANGED_BY, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((CompareResult) element).getSourceChangedBy();
            }

            @Override
            public String getToolTipText(Object element) {
                return "Source last changed by: " + ((CompareResult) element).getSourceChangedBy();
            }
        });
        addColumn("Target changed by", 135, COL_TARGET_CHANGED_BY, new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((CompareResult) element).getTargetChangedBy();
            }

            @Override
            public String getToolTipText(Object element) {
                return "Target last changed by: " + ((CompareResult) element).getTargetChangedBy();
            }
        });
    }

    private void addColumn(String title, int width, final int sortColumn, ColumnLabelProvider provider) {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setMoveable(true);
        column.getColumn().addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                userSortRequested = true;
                comparator.setColumn(sortColumn);
                Table table = viewer.getTable();
                table.setSortColumn((org.eclipse.swt.widgets.TableColumn) event.widget);
                table.setSortDirection(comparator.isAscending() ? SWT.UP : SWT.DOWN);
                applySortAndRefreshTable();
            }
        });
        column.setLabelProvider(provider);
    }

    private void addColumn(String title, int width, final int sortColumn, StyledCellLabelProvider provider) {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setMoveable(true);
        column.getColumn().addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                userSortRequested = true;
                comparator.setColumn(sortColumn);
                Table table = viewer.getTable();
                table.setSortColumn((org.eclipse.swt.widgets.TableColumn) event.widget);
                table.setSortDirection(comparator.isAscending() ? SWT.UP : SWT.DOWN);
                applySortAndRefreshTable();
            }
        });
        column.setLabelProvider(provider);
    }

    private void applySortAndRefreshTable() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        if (comparator != null) {
            long sortStarted = System.currentTimeMillis();
            Collections.sort(visibleResults, new Comparator<CompareResult>() {
                public int compare(CompareResult left, CompareResult right) {
                    return comparator.compare(viewer, left, right);
                }
            });
            lastSortMillis = System.currentTimeMillis() - sortStarted;
            automaticSortSkipped = false;
        }
        viewer.setItemCount(visibleResults.size());
        viewer.refresh();
    }

    private void hookDoubleClick() {
        viewer.addDoubleClickListener(new IDoubleClickListener() {
            public void doubleClick(DoubleClickEvent event) {
                IStructuredSelection selection = (IStructuredSelection) event.getSelection();
                Object first = selection.getFirstElement();
                if (first instanceof CompareResult) {
                    openBestAction((CompareResult) first);
                }
            }
        });
    }

    private void hookSelectionChanged() {
        viewer.addSelectionChangedListener(new ISelectionChangedListener() {
            public void selectionChanged(SelectionChangedEvent event) {
                updateActionEnablement();
            }
        });
    }

    private void hookMouseLinks() {
        final Table table = viewer.getTable();
        table.addMouseMoveListener(new MouseMoveListener() {
            public void mouseMove(MouseEvent event) {
                TableItem item = table.getItem(new Point(event.x, event.y));
                int column = item == null ? -1 : getColumnAt(item, new Point(event.x, event.y));
                CompareResult result = item == null ? null : (CompareResult) item.getData();
                table.setCursor(isClickableCell(result, column) ? table.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
            }
        });
        table.addListener(SWT.MouseExit, event -> table.setCursor(null));
        table.addListener(SWT.MouseUp, event -> {
            if (event.button != 1) {
                return;
            }
            Point point = new Point(event.x, event.y);
            TableItem item = table.getItem(point);
            if (item == null) {
                return;
            }
            int column = getColumnAt(item, point);
            Object data = item.getData();
            if (data instanceof CompareResult && isClickableCell((CompareResult) data, column)) {
                openFromColumn((CompareResult) data, column);
            }
        });
    }

    private int getColumnAt(TableItem item, Point point) {
        Table table = viewer.getTable();
        int columnCount = table.getColumnCount();
        for (int i = 0; i < columnCount; i++) {
            if (item.getBounds(i).contains(point)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isClickableCell(CompareResult result, int column) {
        if (result == null) {
            return false;
        }
        switch (column) {
        case COL_NAME:
        case COL_OPEN:
            return canOpenObject(result);
        default:
            return false;
        }
    }

    private void openFromColumn(CompareResult result, int column) {
        switch (column) {
        case COL_NAME:
        case COL_OPEN:
            openPrimaryObject(result);
            break;
        default:
            break;
        }
    }

    private void createToolbarActions() {
        deepCompareSelectedAction = new Action("Refresh Selected from Server...") {
            @Override
            public void run() {
                deepCompareSelected();
            }
        };
        deepCompareSelectedAction.setToolTipText("Manually reload selected objects from the live servers and refresh their cached compare result. Normal details come from Sync cache.");
        deepCompareSelectedAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_ELCL_SYNCED));

        openDetailedAction = new Action("Show Details") {
            @Override
            public void run() {
                CompareResult result = getSelectedResult();
                if (result != null) {
                    openDetailedCompare(result);
                }
            }
        };
        openDetailedAction.setToolTipText("Show structured Yrell Migrator details for the selected row");
        openDetailedAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_ELCL_SYNCED));

        openBmcCompareAction = new Action("Open BMC Compare") {
            @Override
            public void run() {
                CompareResult result = getSelectedResult();
                if (result != null) {
                    openBmcCompare(result);
                }
            }
        };
        openBmcCompareAction.setToolTipText("Open Developer Studio/BMC native compare editor");
        openBmcCompareAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_OBJ_FILE));

        technicalDiagnosticsAction = new Action("Show Technical Diagnostics...") {
            @Override
            public void run() {
                showTechnicalDiagnostics();
            }
        };
        technicalDiagnosticsAction.setToolTipText("Show cache, fingerprint, selected row and diff diagnostics for troubleshooting");
        technicalDiagnosticsAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_OBJS_INFO_TSK));

        openSourceAction = new Action("Open Source") {
            @Override
            public void run() {
                CompareResult result = getSelectedResult();
                if (result != null) {
                    openSourceObject(result);
                }
            }
        };
        openSourceAction.setToolTipText("Open the source object in Developer Studio");
        openSourceAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_TOOL_BACK));

        openTargetAction = new Action("Open Target") {
            @Override
            public void run() {
                CompareResult result = getSelectedResult();
                if (result != null) {
                    openTargetObject(result);
                }
            }
        };
        openTargetAction.setToolTipText("Open the target object in Developer Studio");
        openTargetAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_TOOL_FORWARD));

        migrateSourceToTargetAction = new Action("Migrate Source → Target...") {
            @Override
            public void run() {
                migrateSelected(MigrationDirection.SOURCE_TO_TARGET);
            }
        };
        migrateSourceToTargetAction.setToolTipText("Create or overwrite selected definitions/catalog objects in the target environment using the source side");
        migrateSourceToTargetAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_TOOL_FORWARD));

        migrateTargetToSourceAction = new Action("Migrate Target → Source...") {
            @Override
            public void run() {
                migrateSelected(MigrationDirection.TARGET_TO_SOURCE);
            }
        };
        migrateTargetToSourceAction.setToolTipText("Create or overwrite selected definitions/catalog objects in the source environment using the target side");
        migrateTargetToSourceAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_TOOL_BACK));

        migrateSourceDataToTargetAction = new Action("Migrate Source Data → Target...") {
            @Override
            public void run() {
                migrateSelectedFormData(MigrationDirection.SOURCE_TO_TARGET);
            }
        };
        migrateSourceDataToTargetAction.setToolTipText("Migrate form entries from source to target for selected form rows");
        migrateSourceDataToTargetAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_TOOL_FORWARD));

        migrateTargetDataToSourceAction = new Action("Migrate Target Data → Source...") {
            @Override
            public void run() {
                migrateSelectedFormData(MigrationDirection.TARGET_TO_SOURCE);
            }
        };
        migrateTargetDataToSourceAction.setToolTipText("Migrate form entries from target to source for selected form rows");
        migrateTargetDataToSourceAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_TOOL_BACK));

        exportFormDataCsvAction = new Action("Export Form Data to CSV...") {
            @Override
            public void run() {
                exportSelectedFormDataCsv();
            }
        };
        exportFormDataCsvAction.setToolTipText("Export entries from the selected form to CSV with qualification, row limit and delimiter options");
        exportFormDataCsvAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_ETOOL_SAVEAS_EDIT));

        addDefinitionsToPackSourceToTargetAction = new Action("Add Definitions to Migration Pack Source → Target") {
            @Override
            public void run() {
                addDefinitionsToMigrationPack(MigrationDirection.SOURCE_TO_TARGET);
            }
        };
        addDefinitionsToPackSourceToTargetAction.setToolTipText("Add selected definition/catalog/workflow rows to the local Migration Pack");
        addDefinitionsToPackSourceToTargetAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_OBJ_FOLDER));

        addDefinitionsToPackTargetToSourceAction = new Action("Add Definitions to Migration Pack Target → Source") {
            @Override
            public void run() {
                addDefinitionsToMigrationPack(MigrationDirection.TARGET_TO_SOURCE);
            }
        };
        addDefinitionsToPackTargetToSourceAction.setToolTipText("Add selected definition/catalog/workflow rows to the local Migration Pack");
        addDefinitionsToPackTargetToSourceAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_OBJ_FOLDER));

        addFormDataToPackSourceToTargetAction = new Action("Add Form Data to Migration Pack Source → Target...") {
            @Override
            public void run() {
                addFormDataToMigrationPack(MigrationDirection.SOURCE_TO_TARGET);
            }
        };
        addFormDataToPackSourceToTargetAction.setToolTipText("Add selected Form rows as form-data scopes with qualification/row options");
        addFormDataToPackSourceToTargetAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_OBJ_FOLDER));

        addFormDataToPackTargetToSourceAction = new Action("Add Form Data to Migration Pack Target → Source...") {
            @Override
            public void run() {
                addFormDataToMigrationPack(MigrationDirection.TARGET_TO_SOURCE);
            }
        };
        addFormDataToPackTargetToSourceAction.setToolTipText("Add selected Form rows as form-data scopes with qualification/row options");
        addFormDataToPackTargetToSourceAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_OBJ_FOLDER));

        resetFiltersAction = new Action("Reset Filters") {
            @Override
            public void run() {
                resetFilters();
            }
        };
        resetFiltersAction.setToolTipText("Show changed, missing and errors; hide equal objects");
        resetFiltersAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_ETOOL_CLEAR));

        exportCsvAction = new Action("Export Visible CSV...") {
            @Override
            public void run() {
                exportCsv();
            }
        };
        exportCsvAction.setToolTipText("Export the currently visible rows to CSV");
        exportCsvAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_ETOOL_SAVEAS_EDIT));

        exportDetailsCsvAction = new Action("Export Details CSV...") {
            @Override
            public void run() {
                exportDetailsCsv();
            }
        };
        exportDetailsCsvAction.setToolTipText("Export property-level difference details for selected rows, or all visible rows when nothing is selected");
        exportDetailsCsvAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_ETOOL_SAVEAS_EDIT));

        copyNamesAction = new Action("Copy name") {
            @Override
            public void run() {
                copySelectedObjectNames();
            }
        };
        copyNamesAction.setToolTipText("Copy selected object names to the clipboard, one name per line");
        copyNamesAction.setImageDescriptor(sharedDescriptor(ISharedImages.IMG_TOOL_COPY));

        IToolBarManager manager = getViewSite().getActionBars().getToolBarManager();
        manager.add(deepCompareSelectedAction);
        manager.add(openDetailedAction);
        manager.add(openBmcCompareAction);
        manager.add(technicalDiagnosticsAction);
        manager.add(openSourceAction);
        manager.add(openTargetAction);
        manager.add(new Separator());
        manager.add(migrateSourceToTargetAction);
        manager.add(migrateTargetToSourceAction);
        manager.add(migrateSourceDataToTargetAction);
        manager.add(migrateTargetDataToSourceAction);
        manager.add(new Separator());
        manager.add(addDefinitionsToPackSourceToTargetAction);
        manager.add(addFormDataToPackSourceToTargetAction);
        manager.add(new Separator());
        manager.add(resetFiltersAction);
        manager.add(exportCsvAction);
        manager.add(exportDetailsCsvAction);
        updateActionEnablement();
    }

    private void hookContextMenu() {
        MenuManager menuManager = new MenuManager("#YrellMigratorDifferencesPopup");
        menuManager.setRemoveAllWhenShown(true);
        menuManager.addMenuListener(new IMenuListener() {
            public void menuAboutToShow(IMenuManager manager) {
                CompareResult result = getSelectedResult();
                Action openBest = new Action("Open") {
                    @Override
                    public void run() {
                        CompareResult selected = getSelectedResult();
                        if (selected != null) {
                            openBestAction(selected);
                        }
                    }
                };
                openBest.setText(result != null && canShowDetails(result) ? "Show Details" : "Open Object");
                openBest.setEnabled(result != null && (canShowDetails(result) || canOpenAny(result)));
                manager.add(openBest);
                manager.add(deepCompareSelectedAction);
                manager.add(openDetailedAction);
                manager.add(openBmcCompareAction);
                manager.add(technicalDiagnosticsAction);
                manager.add(openSourceAction);
                manager.add(openTargetAction);
                manager.add(copyNamesAction);
                manager.add(new Separator());
                manager.add(migrateSourceToTargetAction);
                manager.add(migrateTargetToSourceAction);
                manager.add(migrateSourceDataToTargetAction);
                manager.add(migrateTargetDataToSourceAction);
                manager.add(exportFormDataCsvAction);
                manager.add(new Separator("pack"));
                manager.add(addDefinitionsToPackSourceToTargetAction);
                manager.add(addDefinitionsToPackTargetToSourceAction);
                manager.add(addFormDataToPackSourceToTargetAction);
                manager.add(addFormDataToPackTargetToSourceAction);
                manager.add(new Separator());
                manager.add(resetFiltersAction);
                manager.add(exportCsvAction);
                manager.add(exportDetailsCsvAction);
            }
        });
        Menu menu = menuManager.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
        getSite().registerContextMenu(menuManager, viewer);
    }

    private void copySelectedObjectNames() {
        List<CompareResult> selected = getSelectedResults();
        if (selected.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (CompareResult row : selected) {
            if (row == null) {
                continue;
            }
            String name = safeText(row.getObjectName());
            if (name.length() == 0) {
                continue;
            }
            if (text.length() > 0) {
                text.append(System.getProperty("line.separator"));
            }
            text.append(name);
        }
        if (text.length() == 0) {
            return;
        }
        Clipboard clipboard = new Clipboard(Display.getDefault());
        try {
            clipboard.setContents(new Object[] { text.toString() }, new Transfer[] { TextTransfer.getInstance() });
            appendActivity("Copied " + selected.size() + " object name(s) to clipboard.");
        } finally {
            clipboard.dispose();
        }
    }

    private void resetFilters() {
        if (searchText != null && !searchText.isDisposed()) {
            searchText.setText("");
        }
        selectedNavigatorType = typeNavigatorIndex.get(Integer.valueOf(0));
        if (typeNavigator != null && !typeNavigator.isDisposed() && typeNavigator.getItemCount() > 0) {
            typeNavigator.select(0);
            selectedNavigatorType = selectedNavigatorTypeFromList();
        }
        if (changedByText != null && !changedByText.isDisposed()) {
            changedByText.setText("");
        }
        if (modifiedAfterEnabledButton != null && !modifiedAfterEnabledButton.isDisposed()) {
            modifiedAfterEnabledButton.setSelection(false);
        }
        if (modifiedAfterDate != null && !modifiedAfterDate.isDisposed()) {
            modifiedAfterDate.setEnabled(false);
        }
        changedButton.setSelection(true);
        missingButton.setSelection(true);
        equalButton.setSelection(CompareSettings.load().isShowEqualByDefault());
        errorButton.setSelection(true);
        if (stillDifferentButton != null && !stillDifferentButton.isDisposed()) {
            stillDifferentButton.setSelection(false);
        }
        if (permissionsDiffButton != null && !permissionsDiffButton.isDisposed()) {
            permissionsDiffButton.setSelection(false);
        }
        if (auditDiffButton != null && !auditDiffButton.isDisposed()) {
            auditDiffButton.setSelection(false);
        }
        if (customizationBaseButton != null && !customizationBaseButton.isDisposed()) {
            customizationBaseButton.setSelection(false);
        }
        if (customizationCustomButton != null && !customizationCustomButton.isDisposed()) {
            customizationCustomButton.setSelection(true);
        }
        if (customizationOverlayButton != null && !customizationOverlayButton.isDisposed()) {
            customizationOverlayButton.setSelection(true);
        }
        refresh(false);
    }

    private void applyIssuesPreset() {
        if (searchText != null && !searchText.isDisposed()) {
            searchText.setText("");
        }
        if (changedByText != null && !changedByText.isDisposed()) {
            changedByText.setText("");
        }
        if (modifiedAfterEnabledButton != null && !modifiedAfterEnabledButton.isDisposed()) {
            modifiedAfterEnabledButton.setSelection(false);
        }
        if (modifiedAfterDate != null && !modifiedAfterDate.isDisposed()) {
            modifiedAfterDate.setEnabled(false);
        }
        if (changedButton != null && !changedButton.isDisposed()) {
            changedButton.setSelection(true);
        }
        if (missingButton != null && !missingButton.isDisposed()) {
            missingButton.setSelection(true);
        }
        if (errorButton != null && !errorButton.isDisposed()) {
            errorButton.setSelection(true);
        }
        if (equalButton != null && !equalButton.isDisposed()) {
            equalButton.setSelection(false);
        }
        if (stillDifferentButton != null && !stillDifferentButton.isDisposed()) {
            stillDifferentButton.setSelection(false);
        }
        if (permissionsDiffButton != null && !permissionsDiffButton.isDisposed()) {
            permissionsDiffButton.setSelection(false);
        }
        if (auditDiffButton != null && !auditDiffButton.isDisposed()) {
            auditDiffButton.setSelection(false);
        }
        if (customizationBaseButton != null && !customizationBaseButton.isDisposed()) {
            customizationBaseButton.setSelection(false);
        }
        if (customizationCustomButton != null && !customizationCustomButton.isDisposed()) {
            customizationCustomButton.setSelection(true);
        }
        if (customizationOverlayButton != null && !customizationOverlayButton.isDisposed()) {
            customizationOverlayButton.setSelection(true);
        }
        selectedNavigatorType = null;
        if (typeNavigator != null && !typeNavigator.isDisposed() && typeNavigator.getItemCount() > 0) {
            typeNavigator.select(0);
        }
        refresh(false);
    }

    private void openBestAction(CompareResult result) {
        if (result == null) {
            return;
        }
        if (canShowDetails(result)) {
            openDetailedCompare(result);
        } else {
            openPrimaryObject(result);
        }
    }

    private void openPrimaryObject(CompareResult result) {
        if (result == null) {
            return;
        }
        if (canOpenSource(result)) {
            openSourceObject(result);
        } else if (canOpenTarget(result)) {
            openTargetObject(result);
        } else {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "No object is available to open for this row.");
        }
    }

    private void openDetailedCompare(CompareResult result) {
        if (result == null) {
            return;
        }
        DifferenceDetailsDialog dialog = new DifferenceDetailsDialog(getSite().getShell(), result);
        dialog.open();
    }

    private void openBmcCompare(CompareResult result) {
        if (result == null || !result.canOpenDetailedCompare()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "BMC native compare is only available when the object exists in both environments.");
            return;
        }
        boolean opened = BmcDiffLauncher.openCompare(result.getSourceStore(), result.getTargetStore(), result.getModelType(), result.getObjectName());
        if (!opened) {
            MessageDialog.openWarning(getSite().getShell(), "Yrell Migrator", "Could not open BMC's detailed compare editor. Use Show Details or see the Error Log for details.");
        }
    }

    private void openSourceObject(CompareResult result) {
        if (!canOpenSource(result)) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "This row has no source object to open.");
            return;
        }
        IModelItem item = resolveSideItem(result, true);
        if (item == null) {
            MessageDialog.openWarning(getSite().getShell(), "Yrell Migrator", "Could not resolve the source object from Developer Studio. The cache may be stale; try Sync or Refresh Selected from Server.");
            return;
        }
        openObject(item, "source");
        refreshViewerRow(result);
    }

    private void openTargetObject(CompareResult result) {
        if (!canOpenTarget(result)) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "This row has no target object to open.");
            return;
        }
        IModelItem item = resolveSideItem(result, false);
        if (item == null) {
            MessageDialog.openWarning(getSite().getShell(), "Yrell Migrator", "Could not resolve the target object from Developer Studio. The cache may be stale; try Sync or Refresh Selected from Server.");
            return;
        }
        openObject(item, "target");
        refreshViewerRow(result);
    }

    private IModelItem resolveSideItem(CompareResult result, boolean source) {
        if (result == null) {
            return null;
        }
        IModelItem current = source ? result.getSourceItem() : result.getTargetItem();
        if (current != null) {
            return current;
        }
        IStore store = source ? result.getSourceStore() : result.getTargetStore();
        if (store == null || !store.isConnected() || result.getModelType() == null
                || result.getObjectName() == null || result.getObjectName().length() == 0) {
            return null;
        }
        try {
            IModelItem resolved = BmcItemEnumerator.findItem(store, result.getModelType(), result.getObjectName());
            if (source) {
                result.setSourceItem(resolved);
            } else {
                result.setTargetItem(resolved);
            }
            return resolved;
        } catch (Throwable ex) {
            Activator.logWarning("Could not resolve " + (source ? "source" : "target") + " object "
                    + result.getObjectType() + " " + result.getObjectName(), ex);
            return null;
        }
    }

    private void refreshViewerRow(CompareResult result) {
        if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
            viewer.refresh(result, true);
            updateActionEnablement();
        }
    }

    private void openObject(IModelItem item, String side) {
        boolean opened = BmcObjectOpener.open(item);
        if (!opened) {
            MessageDialog.openWarning(getSite().getShell(), "Yrell Migrator", "Could not open the " + side + " object in Developer Studio. See the Error Log for details.");
        }
    }

    private boolean canOpenAny(CompareResult result) {
        return canOpenObject(result) || canShowDetails(result);
    }

    private boolean canOpenObject(CompareResult result) {
        return canOpenSource(result) || canOpenTarget(result);
    }

    private boolean canOpenSource(CompareResult result) {
        return canOpenSide(result, true);
    }

    private boolean canOpenTarget(CompareResult result) {
        return canOpenSide(result, false);
    }

    private boolean canOpenSide(CompareResult result, boolean source) {
        if (result == null || result.getModelType() == null || result.getObjectName() == null
                || result.getObjectName().length() == 0) {
            return false;
        }
        IStore store = source ? result.getSourceStore() : result.getTargetStore();
        if (store == null || !store.isConnected()) {
            return false;
        }
        CompareStatus status = result.getStatus();
        if (source) {
            return status != CompareStatus.MISSING_IN_SOURCE;
        }
        return status != CompareStatus.MISSING_IN_TARGET;
    }

    private boolean canShowDetails(CompareResult result) {
        return result != null && (result.canOpenDetailedCompare() || result.hasStructuredDetails()
                || (result.getDifferenceCount() > 0 && result.getStatus() != CompareStatus.EQUAL));
    }


    private void deepCompareSelected() {
        final List<CompareResult> selected = getSelectedResults();
        final List<CompareResult> candidates = getDeepComparableResults(selected);
        if (candidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator",
                    "No refreshable rows are selected. Select rows that have source, target, type and name metadata.");
            return;
        }
        if (selected.size() > 25) {
            boolean confirmed = MessageDialog.openQuestion(getSite().getShell(), "Refresh Selected from Server",
                    "You selected " + selected.size() + " rows. " + candidates.size()
                            + " row(s) can be refreshed from the live servers. This is optional when the Sync cache is current and can take time.\n\nContinue?");
            if (!confirmed) {
                return;
            }
        }
        final org.eclipse.swt.widgets.Display display = viewer.getControl().getDisplay();
        Job job = new Job("Yrell Migrator selected rows") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final List<CompareResult> refreshed = new ArrayList<CompareResult>();
                monitor.beginTask("Refreshing selected AR objects from server", candidates.size());
                int index = 0;
                for (CompareResult row : candidates) {
                    if (monitor.isCanceled()) {
                        ProgressMessages.markCancelRequested(monitor, "the current refresh row");
                        break;
                    }
                    index++;
                    monitor.subTask("Refreshing " + row.getObjectType() + " " + row.getObjectName() + " (" + index + "/" + candidates.size() + ")");
                    refreshed.add(modelAdapter.refreshCompareWithTimeout(row.getSourceStore(), row.getTargetStore(), row.getModelType(), row.getObjectName(), monitor, null));
                    monitor.worked(1);
                }
                monitor.done();
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            replaceResults(refreshed);
                        }
                    });
                }
                return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void refreshRowsFromServer(final List<CompareResult> rows, final String label) {
        final List<CompareResult> candidates = getDeepComparableResults(rows);
        if (candidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator",
                    "No refreshable rows were available for " + label + ".");
            return;
        }
        final org.eclipse.swt.widgets.Display display = viewer.getControl().getDisplay();
        appendActivity("Refreshing " + candidates.size() + " " + label + " from server.");
        Job job = new Job("Yrell Migrator refresh " + label) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final List<CompareResult> refreshed = new ArrayList<CompareResult>();
                monitor.beginTask("Refreshing " + label + " from server", candidates.size());
                int index = 0;
                for (CompareResult row : candidates) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    index++;
                    monitor.subTask("Refreshing " + row.getObjectType() + " " + row.getObjectName() + " (" + index + "/" + candidates.size() + ")");
                    refreshed.add(modelAdapter.refreshCompareWithTimeout(row.getSourceStore(), row.getTargetStore(), row.getModelType(), row.getObjectName(), monitor, null));
                    monitor.worked(1);
                }
                monitor.done();
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            replaceResults(refreshed);
                            appendActivity("Refreshed " + refreshed.size() + " " + label + " from server.");
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private List<CompareResult> refreshableRowsFromMigrationResults(List<MigrationResult> results) {
        List<CompareResult> rows = new ArrayList<CompareResult>();
        if (results == null) {
            return rows;
        }
        for (MigrationResult result : results) {
            if (result == null || result.getCompareResult() == null) {
                continue;
            }
            CompareResult row = result.getCompareResult();
            if (row.getSourceStore() != null && row.getTargetStore() != null && row.getModelType() != null
                    && row.getObjectName() != null && row.getObjectName().length() > 0
                    && !containsSameResultKey(rows, row)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private boolean containsSameResultKey(List<CompareResult> rows, CompareResult candidate) {
        if (rows == null || candidate == null) {
            return false;
        }
        for (CompareResult row : rows) {
            if (sameResultKey(row, candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<CompareResult> getDeepComparableResults(List<CompareResult> rows) {
        List<CompareResult> candidates = new ArrayList<CompareResult>();
        for (CompareResult row : rows) {
            if (row != null && row.getSourceStore() != null && row.getTargetStore() != null
                    && row.getModelType() != null && row.getObjectName() != null && row.getObjectName().length() > 0) {
                candidates.add(row);
            }
        }
        return candidates;
    }

    private void replaceResults(List<CompareResult> refreshed) {
        if (refreshed == null || refreshed.isEmpty()) {
            return;
        }
        List<CompareResult> current = new ArrayList<CompareResult>(session.getResults());
        for (CompareResult row : refreshed) {
            replaceOne(current, row);
        }
        session = new ComparisonSession(session.getLabel() + " / selected refreshed", current);
        refresh(true);
    }

    private void replaceOne(List<CompareResult> rows, CompareResult replacement) {
        for (int i = 0; i < rows.size(); i++) {
            CompareResult existing = rows.get(i);
            if (sameResultKey(existing, replacement)) {
                rows.set(i, replacement);
                return;
            }
        }
        rows.add(replacement);
    }

    private boolean sameResultKey(CompareResult left, CompareResult right) {
        if (left == null || right == null) {
            return false;
        }
        return equalsIgnoreCase(left.getSourceServer(), right.getSourceServer())
                && equalsIgnoreCase(left.getTargetServer(), right.getTargetServer())
                && equalsIgnoreCase(left.getObjectType(), right.getObjectType())
                && equalsIgnoreCase(left.getObjectName(), right.getObjectName());
    }

    private void showTechnicalDiagnostics() {
        CompareResult result = getSelectedResult();
        if (result == null) {
            return;
        }
        String report = buildTechnicalDiagnostics(result);
        new MigrationReportDialog(getSite().getShell(), "Technical Diagnostics",
                "Technical diagnostics for the selected Yrell Migrator row.", report, false).open();
    }

    private String buildTechnicalDiagnostics(CompareResult result) {
        StringBuilder b = new StringBuilder();
        b.append("Object\n");
        b.append("  Status: ").append(result.getStatus().getLabel()).append('\n');
        b.append("  Type: ").append(result.getObjectType()).append('\n');
        b.append("  Name: ").append(result.getObjectName()).append('\n');
        b.append("  Form: ").append(primaryFormColumnText(result)).append('\n');
        b.append("  Form type: ").append(formTypeColumnText(result)).append('\n');
        b.append("  Key / ID: ").append(contextColumnText(result)).append('\n');
        b.append("  Customization: ").append(result.getCustomizationTypeSummary()).append("\n\n");
        b.append("Source\n");
        b.append("  Server: ").append(result.getSourceServer()).append('\n');
        b.append("  Modified: ").append(UiStrings.timestamp(result.getSourceModified())).append('\n');
        b.append("  Changed by: ").append(result.getSourceChangedBy()).append('\n');
        b.append("  Context key: ").append(result.getSourceContextKey()).append('\n');
        b.append("  Has item reference: ").append(result.getSourceItem() != null).append("\n\n");
        b.append("Target\n");
        b.append("  Server: ").append(result.getTargetServer()).append('\n');
        b.append("  Modified: ").append(UiStrings.timestamp(result.getTargetModified())).append('\n');
        b.append("  Changed by: ").append(result.getTargetChangedBy()).append('\n');
        b.append("  Context key: ").append(result.getTargetContextKey()).append('\n');
        b.append("  Has item reference: ").append(result.getTargetItem() != null).append("\n\n");
        CompareSettings settings = CompareSettings.load();
        b.append("Active ignore settings\n");
        b.append("  Ignore properties: ").append(settings.getIgnoreDifferenceNameContainsRaw()).append('\n');
        b.append("  Ignore mask ids: ").append(settings.getIgnoreMaskIds()).append('\n');
        b.append("  Ignore internals: ").append(settings.getIgnoreFingerprintMemberNameContainsRaw()).append("\n\n");
        b.append("Difference details (first 200)\n");
        int i = 0;
        for (DiffDetail d : result.getDifferenceDetails()) {
            i++;
            if (i > 200) {
                b.append("  ... truncated ...\n");
                break;
            }
            b.append("  ").append(i).append(". ").append(d.getArea()).append(" / ").append(d.getProperty())
                    .append(" [").append(d.getKind()).append("]\n")
                    .append("     Source: ").append(oneLine(d.getSourceValue())).append('\n')
                    .append("     Target: ").append(oneLine(d.getTargetValue())).append('\n');
        }
        b.append("\nTroubleshooting\n");
        b.append("  Use Refresh Selected from Server after manual changes or migration.\n");
        b.append("  If a harmless property is listed as a diff, add a stable token from the Area/Property line to Ignore properties, not the whole explanatory sentence.\n");
        return b.toString();
    }

    private String oneLine(String value) {
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        while (text.indexOf("  ") >= 0) {
            text = text.replace("  ", " ");
        }
        return text.trim();
    }

    private String buildDefinitionMigrationPreview(List<CompareResult> candidates, MigrationDirection direction) {
        StringBuilder b = new StringBuilder();
        b.append("Direction: ").append(direction.getLabel()).append('\n');
        b.append("Objects: ").append(candidates == null ? 0 : candidates.size()).append("\n\n");
        b.append("Planned order\n");
        int i = 0;
        Set<String> selectedGroupIds = new LinkedHashSet<String>();
        if (candidates != null) {
            for (CompareResult row : candidates) {
                if (row != null && isGroupType(row.getObjectType())) {
                    String key = direction == MigrationDirection.SOURCE_TO_TARGET ? row.getSourceContextKey() : row.getTargetContextKey();
                    if (key != null && key.trim().length() > 0) {
                        selectedGroupIds.add(key.trim());
                    }
                }
            }
            for (CompareResult row : candidates) {
                i++;
                b.append("  ").append(i).append(". ").append(row.getObjectType()).append(" — ").append(row.getObjectName());
                String form = primaryFormColumnText(row);
                if (form.length() > 0) {
                    b.append("  [Form: ").append(form).append(']');
                }
                String key = contextColumnText(row);
                if (key.length() > 0) {
                    b.append("  [Key / ID: ").append(key).append(']');
                }
                b.append('\n');
            }
        }
        b.append("\nDependency hints\n");
        b.append("  Execution order uses Group/Role first, then Menus, Forms by form type, other catalog data, workflow, Guides and other objects.\n");
        b.append("  Applications and Packing Lists can optionally migrate discoverable content after the container object.\n");
        b.append("  Computed Groups are retried once after other selected Groups.\n");
        List<String> groupWarnings = findGroupDependencyHints(candidates, direction, selectedGroupIds);
        if (groupWarnings.isEmpty()) {
            b.append("  No obvious missing Group dependency was found in the cached diff details.\n");
        } else {
            b.append("\nWarnings\n");
            for (String warning : groupWarnings) {
                b.append("  - ").append(warning).append('\n');
            }
        }
        b.append("\nRecommended checks\n");
        b.append("  - If workflow references permission groups, migrate missing Group rows first.\n");
        b.append("  - For Join/View/Vendor forms, include the referenced Regular forms in the same selection where possible.\n");
        b.append("  - After overlays, targeted refresh/schema warmup is run automatically, but Refresh Selected remains useful for verification.\n");
        return b.toString();
    }


    private Set<String> selectedGroupIds(List<CompareResult> candidates, MigrationDirection direction) {
        Set<String> selectedGroupIds = new LinkedHashSet<String>();
        if (candidates == null || direction == null) {
            return selectedGroupIds;
        }
        for (CompareResult row : candidates) {
            if (row != null && isGroupType(row.getObjectType())) {
                String key = direction == MigrationDirection.SOURCE_TO_TARGET ? row.getSourceContextKey() : row.getTargetContextKey();
                if (key != null && key.trim().length() > 0) {
                    selectedGroupIds.add(key.trim());
                }
            }
        }
        return selectedGroupIds;
    }

    private List<String> findGroupDependencyHints(List<CompareResult> candidates, MigrationDirection direction, Set<String> selectedGroupIds) {
        List<String> warnings = new ArrayList<String>();
        if (candidates == null) {
            return warnings;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (CompareResult row : candidates) {
            if (row == null || row.getDifferenceDetails() == null) {
                continue;
            }
            for (DiffDetail d : row.getDifferenceDetails()) {
                String text = (d.getArea() + " " + d.getProperty() + " " + d.getSourceValue() + " " + d.getTargetValue()).toLowerCase(Locale.ENGLISH);
                if (text.indexOf("group") < 0 && text.indexOf("permission") < 0) {
                    continue;
                }
                for (String id : extractIntegerTokens(d.getSourceValue() + " " + d.getTargetValue())) {
                    if (id.length() < 2) {
                        continue;
                    }
                    if (!selectedGroupIds.contains(id) && seen.add(row.getObjectName() + ":" + id)) {
                        warnings.add(row.getObjectType() + " " + row.getObjectName() + " may reference Group ID " + id + ". Select/migrate the Group row first if it is missing in target.");
                    }
                }
            }
        }
        return warnings;
    }

    private List<String> extractIntegerTokens(String text) {
        List<String> values = new ArrayList<String>();
        if (text == null) {
            return values;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b\\d{2,}\\b").matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private boolean isGroupType(String type) {
        String value = safeText(type).toLowerCase(Locale.ENGLISH).replace(" ", "");
        return value.equals("group") || value.equals("grouptype");
    }

    private String shortPreview(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n... full preview was shown in the previous dialog ...\n";
    }

    private void migrateSelected(final MigrationDirection direction) {
        final List<CompareResult> selected = getSelectedResults();
        final List<CompareResult> rawCandidates = getMigratableResults(selected, direction);
        if (rawCandidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator",
                    "No migratable rows are selected for " + direction.getLabel() + ".\n\n"
                            + "Select different or missing definitions, workflow objects, Menus or supported catalog/data-backed objects such as Group, Role, Message, Report or Template.");
            return;
        }

        int skippedCount = Math.max(0, selected.size() - rawCandidates.size());
        final MigrationPlan plan = migrationPlanner.buildPlan(selected, rawCandidates, direction, skippedCount);
        MigrationPlanDialog dialog = new MigrationPlanDialog(getSite().getShell(), plan);
        if (dialog.open() != MigrationPlanDialog.OK) {
            return;
        }

        runMigrationJob(plan, dialog.isIncludeContainerContent());
    }

    private boolean hasContainerSelection(List<CompareResult> candidates) {
        if (candidates == null) {
            return false;
        }
        for (CompareResult row : candidates) {
            if (containerContentMigrator.isContainerType(row)) {
                return true;
            }
        }
        return false;
    }

    private void runMigrationJob(final MigrationPlan plan, final boolean includeContainerContent) {
        final File backupFile = chooseMigrationBackupFile("object-migration");
        if (backupFile == null) {
            appendActivity("Object migration cancelled because no backup file was selected.");
            return;
        }
        final List<CompareResult> candidates = plan == null ? new ArrayList<CompareResult>() : plan.getOrderedRows();
        final MigrationDirection direction = plan == null ? MigrationDirection.SOURCE_TO_TARGET : plan.getDirection();
        final ComparisonSession baseSession = session;
        final long startedAtMillis = System.currentTimeMillis();
        setBusy(true);
        updateOverviewStatus("Migrating " + candidates.size() + " object(s) " + direction.getLabel() + "...", ISharedImages.IMG_ELCL_SYNCED);
        appendActivity("Migration started: " + candidates.size() + " object(s) " + direction.getLabel()
                + " using shared ObjectMigrationExecutor.");

        Job job = new Job("Migrate AR objects " + direction.getLabel()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    MigrationBackupService.BackupResult backup = migrationBackupService.createForMigrationPlan(plan, includeContainerContent, backupFile, monitor);
                    final String backupMessage = "Before-state backup created: " + backupFile.getAbsolutePath() + ". " + backup.summary();
                    if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                        viewer.getControl().getDisplay().asyncExec(new Runnable() { public void run() { appendActivity(backupMessage); } });
                    }
                } catch (final Throwable backupError) {
                    Activator.logError("Could not create before-state backup; migration aborted.", backupError);
                    if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                        viewer.getControl().getDisplay().asyncExec(new Runnable() {
                            public void run() {
                                setBusy(false);
                                MessageDialog.openError(getSite().getShell(), "Migration Backup", "Could not create backup. Migration was not run:\n" + safeMessage(backupError));
                            }
                        });
                    }
                    return Status.CANCEL_STATUS;
                }

                ObjectMigrationExecutor executor = new ObjectMigrationExecutor(modelAdapter, workflowMigrator,
                        catalogDataMigrator, supportFileMigrator, containerContentMigrator);
                ObjectMigrationExecutor.Execution execution = executor.execute(plan, includeContainerContent, monitor,
                        new ObjectMigrationExecutor.Listener() {
                            public void onProgress(final String message) {
                                if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                                    viewer.getControl().getDisplay().asyncExec(new Runnable() {
                                        public void run() {
                                            appendActivity(message);
                                            updateOverviewStatus(message, ISharedImages.IMG_ELCL_SYNCED);
                                        }
                                    });
                                }
                            }

                            public void onInfo(final String message) {
                                if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                                    viewer.getControl().getDisplay().asyncExec(new Runnable() {
                                        public void run() {
                                            appendActivity(message);
                                        }
                                    });
                                }
                            }
                        });

                if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                    viewer.getControl().getDisplay().asyncExec(new Runnable() {
                        public void run() {
                            finishMigration(baseSession, execution.getRefreshed(), execution.getResults(), direction, includeContainerContent, startedAtMillis, System.currentTimeMillis());
                        }
                    });
                }
                return execution.isCancelled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private MigrationResult verifyMigrationResult(CompareResult candidate, MigrationResult migrationResult,
            Map<CompareResult, CompareResult> refreshed, IProgressMonitor monitor) {
        if (migrationResult == null || !migrationResult.isSuccess()) {
            if (monitor != null) {
                monitor.worked(2);
            }
            return migrationResult == null ? MigrationResult.failure(candidate, "Migration did not return a result.") : migrationResult;
        }
        try {
            if (monitor != null && monitor.isCanceled()) {
                return MigrationResult.reclassified(migrationResult, candidate,
                        se.yrell.migrator.core.MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Post-migration verification skipped because cancel was requested.").getDetail());
            }
            modelAdapter.refreshDefinitionCacheForObject(candidate.getSourceStore(), candidate.getModelType(), candidate.getObjectName(), monitor, null);
            if (monitor != null) {
                monitor.worked(1);
            }
            if (monitor != null && monitor.isCanceled()) {
                return MigrationResult.reclassified(migrationResult, candidate,
                        se.yrell.migrator.core.MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Post-migration verification stopped after source refresh because cancel was requested.").getDetail());
            }
            modelAdapter.refreshDefinitionCacheForObject(candidate.getTargetStore(), candidate.getModelType(), candidate.getObjectName(), monitor, null);
            if (monitor != null) {
                monitor.worked(1);
            }
            if (monitor != null && monitor.isCanceled()) {
                return MigrationResult.reclassified(migrationResult, candidate,
                        se.yrell.migrator.core.MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Post-migration compare skipped because cancel was requested.").getDetail());
            }
            CompareResult refreshedResult = modelAdapter.refreshCompareWithTimeout(candidate.getSourceStore(), candidate.getTargetStore(), candidate.getModelType(), candidate.getObjectName(), monitor, null);
            refreshed.put(candidate, refreshedResult);
            return MigrationVerifier.classify(migrationResult, refreshedResult);
        } catch (Throwable refreshError) {
            if (monitor != null) {
                monitor.worked(2);
            }
            String message = refreshError.getLocalizedMessage() == null ? refreshError.getClass().getName() : refreshError.getLocalizedMessage();
            return MigrationResult.reclassified(migrationResult, candidate,
                    se.yrell.migrator.core.MigrationOutcome.WARNING,
                    MigrationResult.appendDetail(migrationResult, "Post-migration verification failed: " + message).getDetail());
        }
    }

    private boolean isOverlayMigration(MigrationResult result) {
        if (result == null || !result.isSuccess()) {
            return false;
        }
        String text = result.getDetail() == null ? "" : result.getDetail().toLowerCase(Locale.ENGLISH);
        return text.indexOf("overlay") >= 0;
    }

    private void pauseAfterOverlayMigration(IProgressMonitor monitor) {
        // AR/Developer Studio can expose a newly stored overlay definition before the entry/schema
        // layer has fully refreshed. A short pause before targeted cache/schema refresh makes the
        // following data-object migration more reliable without requiring a full Developer Studio restart.
        try {
            if (monitor != null) {
                monitor.subTask("Waiting briefly for overlay schema cache to settle");
            }
            long waited = 0L;
            while (waited < 650L) {
                if (monitor != null && monitor.isCanceled()) {
                    ProgressMessages.markCancelRequested(monitor, "overlay settle wait");
                    return;
                }
                Thread.sleep(50L);
                waited += 50L;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void finishMigration(ComparisonSession baseSession, Map<CompareResult, CompareResult> refreshed,
            List<MigrationResult> migrationResults, final MigrationDirection direction, final boolean includeContainerContent,
            long startedAtMillis, long finishedAtMillis) {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        setBusy(false);

        int failed = MigrationReportFormatter.countFailures(migrationResults);
        int warnings = MigrationReportFormatter.countWarnings(migrationResults);

        if (!refreshed.isEmpty()) {
            List<CompareResult> newResults = new ArrayList<CompareResult>();
            for (CompareResult row : baseSession.getResults()) {
                CompareResult replacement = refreshed.get(row);
                newResults.add(replacement == null ? row : replacement);
            }
            setSession(new ComparisonSession(baseSession.getLabel(), newResults));
        }

        String report = MigrationReportFormatter.formatObjectMigrationReport(direction, migrationResults,
                startedAtMillis, finishedAtMillis, "Differences view selection");
        MigrationReportFormatter.Summary summary = MigrationReportFormatter.summarize(direction, migrationResults,
                startedAtMillis, finishedAtMillis, "Differences view selection");
        final List<CompareResult> compareAgainRows = refreshableRowsFromMigrationResults(migrationResults);
        Runnable compareAgainAction = compareAgainRows.isEmpty() ? null : new Runnable() {
            public void run() {
                refreshRowsFromServer(compareAgainRows, "migrated objects");
            }
        };
        final List<CompareResult> retryRows = retryableRowsFromMigrationResults(migrationResults, direction);
        Runnable retryAction = retryRows.isEmpty() ? null : new Runnable() {
            public void run() {
                retryMigrationRows(retryRows, direction, includeContainerContent);
            }
        };
        new MigrationReportDialog(getSite().getShell(), "Object Migration Report",
                failed + warnings > 0 ? "Object migration completed with warnings." : "Object migration completed.",
                report, failed + warnings > 0, migrationResults, summary,
                compareAgainAction, "Compare Migrated Again", retryAction, "Retry Problems").open();
        appendActivity((failed > 0 ? "Migration finished with errors: " : (warnings > 0 ? "Migration finished with warnings: " : "Migration finished successfully: "))
                + MigrationReportFormatter.countSuccesses(migrationResults) + " succeeded, "
                + warnings + " warning(s), " + failed + " failed. Refreshing overview from local cache.");
        reloadOverviewFromCache(false);
    }

    private List<CompareResult> retryableRowsFromMigrationResults(List<MigrationResult> migrationResults, MigrationDirection direction) {
        List<CompareResult> rows = new ArrayList<CompareResult>();
        if (migrationResults == null) {
            return rows;
        }
        Set<CompareResult> seen = Collections.newSetFromMap(new IdentityHashMap<CompareResult, Boolean>());
        for (MigrationResult result : migrationResults) {
            if (result == null || !isRetryableMigrationResult(result)) {
                continue;
            }
            CompareResult row = result.getCompareResult();
            if (row != null && seen.add(row) && getMigratableResults(Collections.singletonList(row), direction).size() > 0) {
                rows.add(row);
            }
        }
        return rows;
    }

    private boolean isRetryableMigrationResult(MigrationResult result) {
        if (result == null) {
            return false;
        }
        se.yrell.migrator.core.MigrationOutcome outcome = result.getOutcome();
        return outcome == se.yrell.migrator.core.MigrationOutcome.FAILED
                || outcome == se.yrell.migrator.core.MigrationOutcome.UNKNOWN
                || outcome == se.yrell.migrator.core.MigrationOutcome.WARNING
                || outcome == se.yrell.migrator.core.MigrationOutcome.STILL_DIFFERENT
                || outcome == se.yrell.migrator.core.MigrationOutcome.CANCELLED;
    }

    private void retryMigrationRows(List<CompareResult> rows, MigrationDirection direction, boolean includeContainerContent) {
        List<CompareResult> candidates = getMigratableResults(rows, direction);
        if (candidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Retry Migration",
                    "There are no retryable migratable rows left in this report.");
            return;
        }
        int skippedCount = Math.max(0, rows.size() - candidates.size());
        MigrationPlan retryPlan = migrationPlanner.buildPlan(candidates, candidates, direction, skippedCount);
        appendActivity("Retrying " + candidates.size() + " object(s) from the migration report.");
        runMigrationJob(retryPlan, includeContainerContent);
    }

    private List<CompareResult> getMigratableResults(List<CompareResult> rows, MigrationDirection direction) {
        List<CompareResult> candidates = new ArrayList<CompareResult>();
        for (CompareResult row : rows) {
            if (workflowMigrator.canMigrate(row, direction)
                    || catalogDataMigrator.canMigrate(row, direction)
                    || supportFileMigrator.canMigrate(row, direction)) {
                candidates.add(row);
            }
        }
        return candidates;
    }

    private boolean hasMigratableSelection(MigrationDirection direction) {
        return !getMigratableResults(getSelectedResults(), direction).isEmpty();
    }

    private List<CompareResult> orderDefinitionMigration(List<CompareResult> rows) {
        List<CompareResult> ordered = new ArrayList<CompareResult>();
        if (rows != null) {
            ordered.addAll(rows);
        }
        Collections.sort(ordered, new Comparator<CompareResult>() {
            public int compare(CompareResult left, CompareResult right) {
                int weight = migrationTypeWeight(left) - migrationTypeWeight(right);
                if (weight != 0) {
                    return weight;
                }
                int type = safeText(left == null ? null : left.getObjectType()).compareToIgnoreCase(safeText(right == null ? null : right.getObjectType()));
                if (type != 0) {
                    return type;
                }
                return safeText(left == null ? null : left.getObjectName()).compareToIgnoreCase(safeText(right == null ? null : right.getObjectName()));
            }
        });
        return ordered;
    }

    private int formMigrationTypeWeight(CompareResult result) {
        String formType = formTypeColumnText(result).toLowerCase(Locale.ENGLISH);
        int base;
        if (formType.indexOf("regular") >= 0) {
            base = 0;
        } else if (formType.indexOf("display") >= 0) {
            base = 1;
        } else if (formType.indexOf("join") >= 0) {
            base = 2;
        } else if (formType.indexOf("view") >= 0) {
            base = 3;
        } else if (formType.indexOf("vendor") >= 0) {
            base = 4;
        } else {
            base = 5;
        }
        return (base * 10) + 5;
    }

    private boolean isCatalogDataTypeName(String normalizedType) {
        if (normalizedType == null) {
            return false;
        }
        return isGroupOrRoleCatalogType(normalizedType)
                || normalizedType.equals("message")
                || normalizedType.equals("report")
                || normalizedType.equals("reporttype")
                || normalizedType.equals("template");
    }

    private boolean isGroupOrRoleCatalogType(String normalizedType) {
        if (normalizedType == null) {
            return false;
        }
        return normalizedType.equals("group")
                || normalizedType.equals("grouptype")
                || normalizedType.equals("role")
                || normalizedType.equals("roletype");
    }

    private int groupRoleMigrationWeight(CompareResult result) {
        String type = safeText(result == null ? null : result.getObjectType()).toLowerCase(Locale.ENGLISH).replace(" ", "");
        if (type.equals("group") || type.equals("grouptype")) {
            return 0;
        }
        if (type.equals("role") || type.equals("roletype")) {
            return 1;
        }
        return 2;
    }

    private int migrationTypeWeight(CompareResult result) {
        String type = safeText(result == null ? null : result.getObjectType()).toLowerCase(Locale.ENGLISH).replace(" ", "");
        // Groups/Roles must be available before Forms and Workflow, because permissions and
        // computed group expressions frequently reference Group IDs. This matches the safer
        // import order used by the standard tools better than migrating Forms first.
        if (isGroupOrRoleCatalogType(type)) {
            return 10 + groupRoleMigrationWeight(result);
        }
        if (type.indexOf("menu") >= 0) {
            return 20;
        }
        if (type.indexOf("form") >= 0) {
            return 30 + formMigrationTypeWeight(result);
        }
        if (isCatalogDataTypeName(type)) {
            return 40;
        }
        if (type.indexOf("activelink") >= 0) {
            return 50;
        }
        if (type.indexOf("filter") >= 0) {
            return 60;
        }
        if (type.indexOf("escalation") >= 0) {
            return 70;
        }
        if (type.indexOf("guide") >= 0) {
            return 80;
        }
        return 100;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private void migrateSelectedFormData(final MigrationDirection direction) {
        final List<CompareResult> selected = getSelectedResults();
        final List<CompareResult> candidates = getFormDataMigratableResults(selected, direction);
        if (candidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator",
                    "No form rows are selected for data migration " + direction.getLabel() + ".");
            return;
        }
        final CompareResult first = candidates.get(0);
        final com.bmc.arsys.studio.model.store.IStore sourceStore = direction == MigrationDirection.SOURCE_TO_TARGET ? first.getSourceStore() : first.getTargetStore();
        final com.bmc.arsys.studio.model.store.IStore targetStore = direction == MigrationDirection.SOURCE_TO_TARGET ? first.getTargetStore() : first.getSourceStore();
        for (CompareResult row : candidates) {
            com.bmc.arsys.studio.model.store.IStore rowSource = direction == MigrationDirection.SOURCE_TO_TARGET ? row.getSourceStore() : row.getTargetStore();
            com.bmc.arsys.studio.model.store.IStore rowTarget = direction == MigrationDirection.SOURCE_TO_TARGET ? row.getTargetStore() : row.getSourceStore();
            if (!sameStore(sourceStore, rowSource) || !sameStore(targetStore, rowTarget)) {
                MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator",
                        "Please migrate form data for one source/target environment pair at a time.");
                return;
            }
        }

        DataMigrationOptionsDialog dialog = new DataMigrationOptionsDialog(getSite().getShell(),
                candidates.size() == 1 ? candidates.get(0).getObjectName() : candidates.size() + " selected forms",
                sourceStore, targetStore);
        if (dialog.open() != DataMigrationOptionsDialog.OK) {
            return;
        }
        final BmcDataMigrator.Options template = dialog.getOptions();
        if (template == null) {
            return;
        }

        previewAndConfirmDataMigration(candidates, direction, template, sourceStore, targetStore);
    }

    private void previewAndConfirmDataMigration(final List<CompareResult> candidates, final MigrationDirection direction,
            final BmcDataMigrator.Options template, final IStore sourceStore, final IStore targetStore) {
        final Display display = viewer.getControl().getDisplay();
        appendActivity("Data migration preview started for " + candidates.size() + " form(s). No target rows are touched during preview.");
        Job previewJob = new Job("Preview form data migration " + direction.getLabel()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final List<BmcDataMigrator.Preview> previews = new ArrayList<BmcDataMigrator.Preview>();
                final StringBuilder failures = new StringBuilder();
                monitor.beginTask("Previewing form data migration", candidates.size());
                boolean cancelled = false;
                for (CompareResult row : candidates) {
                    if (monitor.isCanceled()) {
                        cancelled = true;
                        appendDataFailure(failures, "Cancelled before previewing " + row.getObjectName() + ". Partial preview only.");
                        break;
                    }
                    try {
                        BmcDataMigrator.Options opts = copyDataOptions(template);
                        opts.setFormName(row.getObjectName());
                        previews.add(dataMigrator.preview(opts, monitor));
                    } catch (Throwable ex) {
                        appendDataFailure(failures, row.getObjectName() + ": " + safeMessage(ex));
                    }
                    monitor.worked(1);
                }
                monitor.done();
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(new Runnable() {
                        public void run() {
                            if (viewer == null || viewer.getControl().isDisposed()) {
                                return;
                            }
                            boolean warnings = failures.length() > 0 || hasPreviewWarnings(previews) || !template.isDryRun();
                            String report = buildDataMigrationPreviewReport(candidates, direction, template, sourceStore, targetStore, previews, failures.toString());
                            new MigrationReportDialog(getSite().getShell(), "Data Migration Preview",
                                    template.isDryRun() ? "Dry run preview is ready. No target rows will be written." : "Write-mode preview is ready. Review warnings before continuing.",
                                    report, warnings).open();
                            if (failures.length() > 0) {
                                MessageDialog.openWarning(getSite().getShell(), "Data Migration Preview", "Preview failed for one or more forms. Fix the failures before running migration.");
                                appendActivity("Data migration preview failed for one or more form(s). See preview report.");
                                return;
                            }
                            if (template.isDryRun()) {
                                if (MessageDialog.openQuestion(getSite().getShell(), "Run Data Dry Run " + direction.getLabel(),
                                        "Run the dry run now?\n\nThis will read matching source entries and produce a migration report, but it will not write target rows.")) {
                                    runFormDataMigrationJob(candidates, direction, template);
                                }
                            } else if (MessageDialog.openQuestion(getSite().getShell(), "Write Form Data " + direction.getLabel(),
                                    "Write mode is enabled. The target environment may be changed.\n\nContinue with the data migration now?")) {
                                runFormDataMigrationJob(candidates, direction, template);
                            }
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        previewJob.setUser(true);
        previewJob.schedule();
    }

    private void runFormDataMigrationJob(final List<CompareResult> candidates, final MigrationDirection direction,
            final BmcDataMigrator.Options template) {
        Job job = new Job("Migrate form data " + direction.getLabel()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Migrating form data", candidates.size() * 2);
                int read = 0;
                int migrated = 0;
                int failed = 0;
                StringBuilder failures = new StringBuilder();
                StringBuilder perForm = new StringBuilder();
                boolean cancelled = false;
                for (CompareResult row : candidates) {
                    if (monitor.isCanceled()) {
                        cancelled = true;
                        appendDataFailure(failures, "Cancelled before migrating " + row.getObjectName() + ". Partial report only.");
                        break;
                    }
                    try {
                        BmcDataMigrator.Options opts = copyDataOptions(template);
                        opts.setFormName(row.getObjectName());
                        BmcDataMigrator.Result result = dataMigrator.migrate(opts, monitor);
                        read += result.getRead();
                        migrated += result.getMigrated();
                        failed += result.getFailed();
                        appendPerFormDataSummary(perForm, row.getObjectName(), result, opts.isDryRun());
                        appendDataFailures(failures, row.getObjectName(), result.getFailures());
                        if (!opts.isDryRun()) {
                            if (!monitor.isCanceled()) {
                                modelAdapter.refreshDefinitionCacheForObject(row.getSourceStore(), row.getModelType(), row.getObjectName(), monitor, null);
                            }
                            if (!monitor.isCanceled()) {
                                modelAdapter.refreshDefinitionCacheForObject(row.getTargetStore(), row.getModelType(), row.getObjectName(), monitor, null);
                            }
                            if (monitor.isCanceled()) {
                                cancelled = true;
                                appendDataFailure(failures, "Cancelled after writing/refreshing " + row.getObjectName() + ". Later forms were not processed.");
                                break;
                            }
                        }
                    } catch (Throwable ex) {
                        failed++;
                        appendDataFailure(failures, row.getObjectName() + ": " + safeMessage(ex));
                    }
                    monitor.worked(1);
                }
                final int readCount = read;
                final int ok = migrated;
                final int bad = failed;
                final boolean dryRun = template.isDryRun();
                final boolean wasCancelled = cancelled;
                final String formText = perForm.toString();
                final String failText = failures.toString();
                viewer.getControl().getDisplay().asyncExec(new Runnable() {
                    public void run() {
                        StringBuilder message = new StringBuilder();
                        message.append("Mode: ").append(dryRun ? "dry run - no target writes" : "write to target").append("\n");
                        message.append("Read entries: ").append(readCount).append("\n");
                        message.append(dryRun ? "Dry-run matched entries: " : "Migrated entries: ").append(ok).append("\n");
                        message.append("Failed entries/forms: ").append(bad);
                        if (formText.length() > 0) {
                            message.append("\n\nPer form:\n").append(formText);
                        }
                        if (failText.length() > 0) {
                            message.append("\n\nFailures:\n").append(failText);
                        }
                        new MigrationReportDialog(getSite().getShell(), dryRun ? "Data Dry Run Report" : "Data Migration Report",
                                wasCancelled ? "Data migration cancelled. Partial report is shown." : (bad > 0 ? "Data migration completed with warnings." : "Data migration completed."),
                                message.toString(), wasCancelled || bad > 0).open();
                    }
                });
                monitor.done();
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private boolean hasPreviewWarnings(List<BmcDataMigrator.Preview> previews) {
        if (previews == null) {
            return false;
        }
        for (BmcDataMigrator.Preview preview : previews) {
            if (preview != null && preview.hasWarnings()) {
                return true;
            }
        }
        return false;
    }

    private String buildDataMigrationPreviewReport(List<CompareResult> candidates, MigrationDirection direction,
            BmcDataMigrator.Options template, IStore sourceStore, IStore targetStore,
            List<BmcDataMigrator.Preview> previews, String failures) {
        StringBuilder report = new StringBuilder();
        report.append("Direction: ").append(direction.getLabel()).append('\n');
        report.append("From: ").append(sourceStore == null ? "" : sourceStore.getName()).append('\n');
        report.append("To:   ").append(targetStore == null ? "" : targetStore.getName()).append('\n');
        report.append("Mode: ").append(template.isDryRun() ? "dry run - no target writes" : "write to target").append('\n');
        report.append("Forms selected: ").append(candidates == null ? 0 : candidates.size()).append('\n');
        report.append("Rows per form: ").append(template.getMaxRows() <= 0 ? "all matching rows" : String.valueOf(template.getMaxRows())).append('\n');
        if (template.getQualification() != null && template.getQualification().trim().length() > 0) {
            report.append("Qualification: ").append(template.getQualification().trim()).append('\n');
        }
        report.append("Conflict handling: ").append(template.getConflictMode().getLabel()).append('\n');
        report.append("Workflow: ").append(template.isRunWorkflow() ? "run" : "suppressed").append('\n');
        report.append("Page size: ").append(template.getPageSize()).append("\n\n");

        int totalPlanned = 0;
        int totalKnown = 0;
        boolean anyUnknown = false;
        if (previews != null && !previews.isEmpty()) {
            report.append("Preflight rows:\n");
            for (BmcDataMigrator.Preview preview : previews) {
                if (preview == null) {
                    continue;
                }
                totalPlanned += Math.max(0, preview.getPlannedRows());
                if (preview.isCountKnown()) {
                    totalKnown += Math.max(0, preview.getSourceRows());
                } else {
                    anyUnknown = true;
                }
                report.append("- ").append(preview.getFormName()).append(": source rows ");
                report.append(preview.isCountKnown() ? String.valueOf(preview.getSourceRows()) : "unknown/first-page " + preview.getSourceRows());
                report.append(", planned ").append(template.isDryRun() ? "read " : "write ").append(preview.getPlannedRows());
                if (preview.getSampleEntryIds() != null && !preview.getSampleEntryIds().isEmpty()) {
                    report.append(", sample Request IDs ").append(preview.getSampleEntryIds());
                }
                report.append("\n  Field preview: sampled ").append(preview.getSampledFieldValues());
                report.append(", send ").append(preview.getIncludedFieldValues());
                report.append(", skip ").append(preview.getSkippedFieldValues());
                if (preview.getSkippedAttachmentFieldValues() > 0) {
                    report.append(" (attachments skipped ").append(preview.getSkippedAttachmentFieldValues()).append(')');
                }
                report.append("\n  ").append(preview.getFieldPolicySummary());
                if (preview.hasWarnings()) {
                    report.append("\n  Warnings:\n  ").append(preview.getWarnings().replace("\n", "\n  "));
                }
                report.append('\n');
            }
            report.append("\nTotal planned ").append(template.isDryRun() ? "read" : "write").append(" entries: ").append(totalPlanned).append('\n');
            if (!anyUnknown) {
                report.append("Total matching source entries: ").append(totalKnown).append('\n');
            }
        }
        if (!template.isDryRun()) {
            report.append("\nSafety notes:\n");
            report.append("- This is write mode. Target data can be created, replaced or merged depending on conflict handling.\n");
            report.append("- Run a dry run first if the row count, qualification or conflict mode is uncertain.\n");
        }
        if (failures != null && failures.length() > 0) {
            report.append("\nPreview failures:\n").append(failures).append('\n');
        }
        return report.toString();
    }

    private void appendPerFormDataSummary(StringBuilder builder, String formName, BmcDataMigrator.Result result, boolean dryRun) {
        if (builder == null || result == null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(formName).append(": read ").append(result.getRead());
        builder.append(dryRun ? ", matched " : ", migrated ").append(result.getMigrated());
        builder.append(", failed ").append(result.getFailed());
        if (result.getSkippedFieldValues() > 0 || result.getFieldValuesSent() > 0) {
            builder.append(", sent field values ").append(result.getFieldValuesSent());
            builder.append(", skipped field values ").append(result.getSkippedFieldValues());
            if (result.getSkippedAttachmentFieldValues() > 0) {
                builder.append(" (attachments ").append(result.getSkippedAttachmentFieldValues()).append(')');
            }
        }
    }

    private BmcDataMigrator.Options copyDataOptions(BmcDataMigrator.Options source) {
        BmcDataMigrator.Options copy = new BmcDataMigrator.Options();
        copy.setSourceStore(source.getSourceStore());
        copy.setTargetStore(source.getTargetStore());
        copy.setFormName(source.getFormName());
        copy.setQualification(source.getQualification());
        copy.setMaxRows(source.getMaxRows());
        copy.setConflictMode(source.getConflictMode());
        copy.setAttachmentPolicy(source.getAttachmentPolicy());
        copy.setEntryKeyStrategy(source.getEntryKeyStrategy());
        copy.setFilterToTargetWritableFields(source.isFilterToTargetWritableFields());
        copy.setRunWorkflow(source.isRunWorkflow());
        copy.setDryRun(source.isDryRun());
        copy.setPageSize(source.getPageSize());
        return copy;
    }

    private List<CompareResult> getFormDataMigratableResults(List<CompareResult> rows, MigrationDirection direction) {
        List<CompareResult> candidates = new ArrayList<CompareResult>();
        for (CompareResult row : rows) {
            if (row == null || !dataMigrator.isFormType(row.getObjectType(), row.getModelType() == null ? null : row.getModelType().getClass().getName())) {
                continue;
            }
            if (direction == MigrationDirection.SOURCE_TO_TARGET) {
                if (row.getSourceStore() != null && row.getTargetStore() != null && row.getStatus() != CompareStatus.MISSING_IN_SOURCE) {
                    candidates.add(row);
                }
            } else if (row.getSourceStore() != null && row.getTargetStore() != null && row.getStatus() != CompareStatus.MISSING_IN_TARGET) {
                candidates.add(row);
            }
        }
        return candidates;
    }

    private boolean hasFormDataMigratableSelection(MigrationDirection direction) {
        return !getFormDataMigratableResults(getSelectedResults(), direction).isEmpty();
    }

    private CompareResult getFormDataExportableResult() {
        List<CompareResult> selected = getSelectedResults();
        if (selected.size() != 1) {
            return null;
        }
        CompareResult row = selected.get(0);
        if (row == null || !dataMigrator.isFormType(row.getObjectType(), row.getModelType() == null ? null : row.getModelType().getClass().getName())) {
            return null;
        }
        return hasAnyExportableStore(row) ? row : null;
    }

    private boolean hasFormDataExportableSelection() {
        return getFormDataExportableResult() != null;
    }

    private boolean hasAnyExportableStore(CompareResult row) {
        return row != null && ((row.getSourceStore() != null && row.getStatus() != CompareStatus.MISSING_IN_SOURCE)
                || (row.getTargetStore() != null && row.getStatus() != CompareStatus.MISSING_IN_TARGET));
    }

    private void exportSelectedFormDataCsv() {
        final CompareResult row = getFormDataExportableResult();
        if (row == null) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator",
                    "Select exactly one Form row to export its data to CSV.");
            return;
        }
        List<FormDataExportOptionsDialog.StoreChoice> choices = new ArrayList<FormDataExportOptionsDialog.StoreChoice>();
        if (row.getSourceStore() != null && row.getStatus() != CompareStatus.MISSING_IN_SOURCE) {
            choices.add(new FormDataExportOptionsDialog.StoreChoice("Source", row.getSourceStore()));
        }
        if (row.getTargetStore() != null && row.getStatus() != CompareStatus.MISSING_IN_TARGET) {
            choices.add(new FormDataExportOptionsDialog.StoreChoice("Target", row.getTargetStore()));
        }
        if (choices.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator",
                    "The selected form is not available on either side for data export.");
            return;
        }
        FormDataExportOptionsDialog dialog = new FormDataExportOptionsDialog(getSite().getShell(), row.getObjectName(), choices);
        if (dialog.open() != FormDataExportOptionsDialog.OK) {
            return;
        }
        final BmcFormDataCsvExporter.Options options = dialog.getOptions();
        if (options == null) {
            return;
        }
        FileDialog fileDialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        fileDialog.setText("Export Form Data to CSV");
        fileDialog.setFilterExtensions(new String[] { "*.csv", "*.*" });
        fileDialog.setFileName(safeFileName(row.getObjectName()) + "-data.csv");
        final String path = fileDialog.open();
        if (path == null) {
            return;
        }
        runFormDataCsvExportJob(options, path);
    }

    private void runFormDataCsvExportJob(final BmcFormDataCsvExporter.Options options, final String path) {
        setBusy(true);
        updateOverviewStatus("Exporting form data from " + options.getFormName() + "...", ISharedImages.IMG_ETOOL_SAVEAS_EDIT);
        appendActivity("CSV export started for form " + options.getFormName() + ".");
        Job job = new Job("Export form data CSV") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    final BmcFormDataCsvExporter.Result result = formDataCsvExporter.export(options, path, monitor);
                    if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                        viewer.getControl().getDisplay().asyncExec(new Runnable() {
                            public void run() {
                                setBusy(false);
                                updateOverviewStatus("CSV export complete: " + result.getExportedRows() + " row(s).", ISharedImages.IMG_ETOOL_SAVEAS_EDIT);
                                appendActivity("CSV export complete for " + result.getFormName() + ": " + result.getExportedRows() + " row(s) written to " + result.getFilePath() + ".");
                                MessageDialog.openInformation(getSite().getShell(), "Form Data CSV Export",
                                        "Export completed.\n\nForm: " + result.getFormName()
                                        + "\nEnvironment: " + result.getServerName()
                                        + "\nRows: " + result.getExportedRows()
                                        + "\nColumns: " + result.getColumns()
                                        + "\nFile: " + result.getFilePath());
                            }
                        });
                    }
                    return Status.OK_STATUS;
                } catch (final Throwable ex) {
                    Activator.logError("Could not export form data CSV.", ex);
                    if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                        viewer.getControl().getDisplay().asyncExec(new Runnable() {
                            public void run() {
                                setBusy(false);
                                updateOverviewStatus("CSV export failed.", ISharedImages.IMG_OBJS_ERROR_TSK);
                                appendActivity("CSV export failed: " + safeMessage(ex));
                                MessageDialog.openError(getSite().getShell(), "Form Data CSV Export", "Could not export CSV: " + safeMessage(ex));
                            }
                        });
                    }
                    return Status.CANCEL_STATUS;
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private String safeFileName(String text) {
        String value = text == null ? "form" : text.trim();
        if (value.length() == 0) {
            value = "form";
        }
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private void appendDataFailures(StringBuilder builder, String formName, String failures) {
        if (failures == null || failures.length() == 0) return;
        appendDataFailure(builder, formName + ":\n" + failures);
    }

    private void appendDataFailure(StringBuilder builder, String text) {
        if (builder.length() > 2500) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(text);
    }

    private boolean sameStore(com.bmc.arsys.studio.model.store.IStore left, com.bmc.arsys.studio.model.store.IStore right) {
        if (left == null || right == null) return false;
        return left.getName() != null && left.getName().equalsIgnoreCase(right.getName());
    }

    private String safeMessage(Throwable ex) {
        String message = ex == null ? null : ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? String.valueOf(ex) : message;
    }

    private String describeSource(List<CompareResult> rows) {
        return describeEnvironment(rows, true);
    }

    private String describeTarget(List<CompareResult> rows) {
        return describeEnvironment(rows, false);
    }

    private String describeEnvironment(List<CompareResult> rows, boolean source) {
        Set<String> names = new LinkedHashSet<String>();
        for (CompareResult row : rows) {
            String name = source ? row.getSourceServer() : row.getTargetServer();
            if (name != null && name.length() > 0) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return "Unknown";
        }
        if (names.size() == 1) {
            return names.iterator().next();
        }
        return "Multiple environments";
    }

    private CompareResult getSelectedResult() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return null;
        }
        IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
        Object first = selection.getFirstElement();
        return first instanceof CompareResult ? (CompareResult) first : null;
    }

    private List<CompareResult> getSelectedResults() {
        List<CompareResult> results = new ArrayList<CompareResult>();
        if (viewer == null || viewer.getControl().isDisposed()) {
            return results;
        }
        IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
        for (Object element : selection.toArray()) {
            if (element instanceof CompareResult) {
                results.add((CompareResult) element);
            }
        }
        return results;
    }

    private void updateActionEnablement() {
        CompareResult result = getSelectedResult();
        if (deepCompareSelectedAction != null) {
            deepCompareSelectedAction.setEnabled(!getDeepComparableResults(getSelectedResults()).isEmpty());
        }
        if (openDetailedAction != null) {
            openDetailedAction.setEnabled(result != null && canShowDetails(result));
        }
        if (openBmcCompareAction != null) {
            openBmcCompareAction.setEnabled(result != null && result.canOpenDetailedCompare());
        }
        if (technicalDiagnosticsAction != null) {
            technicalDiagnosticsAction.setEnabled(result != null);
        }
        if (openSourceAction != null) {
            openSourceAction.setEnabled(canOpenSource(result));
        }
        if (openTargetAction != null) {
            openTargetAction.setEnabled(canOpenTarget(result));
        }
        if (migrateSourceToTargetAction != null) {
            migrateSourceToTargetAction.setEnabled(hasMigratableSelection(MigrationDirection.SOURCE_TO_TARGET));
        }
        if (migrateTargetToSourceAction != null) {
            migrateTargetToSourceAction.setEnabled(hasMigratableSelection(MigrationDirection.TARGET_TO_SOURCE));
        }
        if (migrateSourceDataToTargetAction != null) {
            migrateSourceDataToTargetAction.setEnabled(hasFormDataMigratableSelection(MigrationDirection.SOURCE_TO_TARGET));
        }
        if (migrateTargetDataToSourceAction != null) {
            migrateTargetDataToSourceAction.setEnabled(hasFormDataMigratableSelection(MigrationDirection.TARGET_TO_SOURCE));
        }
        if (exportFormDataCsvAction != null) {
            exportFormDataCsvAction.setEnabled(hasFormDataExportableSelection());
        }
        if (addDefinitionsToPackSourceToTargetAction != null) {
            addDefinitionsToPackSourceToTargetAction.setEnabled(hasMigratableSelection(MigrationDirection.SOURCE_TO_TARGET));
        }
        if (addDefinitionsToPackTargetToSourceAction != null) {
            addDefinitionsToPackTargetToSourceAction.setEnabled(hasMigratableSelection(MigrationDirection.TARGET_TO_SOURCE));
        }
        if (addFormDataToPackSourceToTargetAction != null) {
            addFormDataToPackSourceToTargetAction.setEnabled(hasFormDataMigratableSelection(MigrationDirection.SOURCE_TO_TARGET));
        }
        if (addFormDataToPackTargetToSourceAction != null) {
            addFormDataToPackTargetToSourceAction.setEnabled(hasFormDataMigratableSelection(MigrationDirection.TARGET_TO_SOURCE));
        }
        if (exportCsvAction != null) {
            exportCsvAction.setEnabled(session != null && !session.getResults().isEmpty());
        }
        if (exportDetailsCsvAction != null) {
            exportDetailsCsvAction.setEnabled(session != null && !session.getResults().isEmpty());
        }
        if (copyNamesAction != null) {
            copyNamesAction.setEnabled(!getSelectedResults().isEmpty());
        }
    }

    private void exportCsv() {
        List<CompareResult> results = getVisibleResults();
        if (results.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "There are no visible comparison results to export.");
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export Yrell Migrator - Differences");
        dialog.setFilterExtensions(new String[] { "*.csv", "*.*" });
        dialog.setFileName("ar-differences-visible.csv");
        String path = dialog.open();
        if (path == null) {
            return;
        }
        try {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(path), "UTF-8"));
            try {
                writer.println("Status,Evidence,Evidence detail,Object type,Customization type,Object name,Form,Form type,Key / ID,Source modified,Target modified,Source changed by,Target changed by,Detail");
                for (CompareResult result : results) {
                    writer.println(
                            UiStrings.csv(result.getStatus().getLabel()) + ','
                                    + UiStrings.csv(result.getEvidenceLabel()) + ','
                                    + UiStrings.csv(result.getEvidenceDetail()) + ','
                                    + UiStrings.csv(result.getObjectType()) + ','
                                    + UiStrings.csv(result.getCustomizationTypeSummary()) + ','
                                    + UiStrings.csv(result.getObjectName()) + ','
                                    + UiStrings.csv(primaryFormColumnText(result)) + ','
                                    + UiStrings.csv(formTypeColumnText(result)) + ','
                                    + UiStrings.csv(contextColumnText(result)) + ','
                                    + UiStrings.csv(UiStrings.timestamp(result.getSourceModified())) + ','
                                    + UiStrings.csv(UiStrings.timestamp(result.getTargetModified())) + ','
                                    + UiStrings.csv(result.getSourceChangedBy()) + ','
                                    + UiStrings.csv(result.getTargetChangedBy()) + ','
                                    + UiStrings.csv(result.getDetail()));
                }
            } finally {
                writer.close();
            }
        } catch (Exception ex) {
            Activator.logError("Could not export Yrell Migrator - Differences CSV.", ex);
            MessageDialog.openError(getSite().getShell(), "Yrell Migrator", "Could not export CSV: " + ex.getLocalizedMessage());
        }
    }

    private void exportDetailsCsv() {
        List<CompareResult> results = getSelectedResults();
        if (results.isEmpty()) {
            results = getVisibleResults();
        }
        if (results.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "Yrell Migrator", "There are no comparison results to export.");
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Export AR Difference Details");
        dialog.setFilterExtensions(new String[] { "*.csv", "*.*" });
        dialog.setFileName("ar-difference-details.csv");
        String path = dialog.open();
        if (path == null) {
            return;
        }
        try {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(path), "UTF-8"));
            try {
                writer.println("Object status,Evidence,Evidence detail,Object type,Customization type,Object name,Source,Target,Area,Property,Kind,Source value,Target value,Difference,Summary detail");
                for (CompareResult result : results) {
                    List<DiffDetail> details = result.getDifferenceDetails();
                    if (details == null || details.isEmpty()) {
                        writer.println(UiStrings.csv(result.getStatus().getLabel()) + ','
                                + UiStrings.csv(result.getEvidenceLabel()) + ','
                                + UiStrings.csv(result.getEvidenceDetail()) + ','
                                + UiStrings.csv(result.getObjectType()) + ','
                                + UiStrings.csv(result.getCustomizationTypeSummary()) + ','
                                + UiStrings.csv(result.getObjectName()) + ','
                                + UiStrings.csv(result.getSourceServer()) + ','
                                + UiStrings.csv(result.getTargetServer()) + ",,,,,,,"
                                + UiStrings.csv(result.getDetail()));
                        continue;
                    }
                    for (DiffDetail detail : details) {
                        writer.println(UiStrings.csv(result.getStatus().getLabel()) + ','
                                + UiStrings.csv(result.getEvidenceLabel()) + ','
                                + UiStrings.csv(result.getEvidenceDetail()) + ','
                                + UiStrings.csv(result.getObjectType()) + ','
                                + UiStrings.csv(result.getCustomizationTypeSummary()) + ','
                                + UiStrings.csv(result.getObjectName()) + ','
                                + UiStrings.csv(result.getSourceServer()) + ','
                                + UiStrings.csv(result.getTargetServer()) + ','
                                + UiStrings.csv(detail.getArea()) + ','
                                + UiStrings.csv(detail.getProperty()) + ','
                                + UiStrings.csv(detail.getKind()) + ','
                                + UiStrings.csv(detail.getSourceValue()) + ','
                                + UiStrings.csv(detail.getTargetValue()) + ','
                                + UiStrings.csv(differenceText(detail)) + ','
                                + UiStrings.csv(result.getDetail()));
                    }
                }
            } finally {
                writer.close();
            }
        } catch (Exception ex) {
            Activator.logError("Could not export AR Difference Details CSV.", ex);
            MessageDialog.openError(getSite().getShell(), "Yrell Migrator", "Could not export details CSV: " + ex.getLocalizedMessage());
        }
    }

    private String differenceText(DiffDetail detail) {
        if (detail == null) {
            return "";
        }
        String kind = detail.getKind();
        String source = detail.getSourceValue();
        String target = detail.getTargetValue();
        if (source.length() == 0 && target.length() > 0) {
            return "Only in target: " + target;
        }
        if (source.length() > 0 && target.length() == 0) {
            return "Only in source: " + source;
        }
        if (source.equals(target)) {
            return kind == null ? "" : kind;
        }
        return "Source differs from target.";
    }


    private String primaryFormColumnText(CompareResult result) {
        if (result == null) {
            return "";
        }
        String direct = safeText(result.getPrimaryFormSummary());
        if (direct.length() > 0) {
            return compactFormValue(direct);
        }
        String type = safeText(result.getObjectType()).toLowerCase(Locale.ENGLISH);
        if (type.equals("form") || type.endsWith(" form")) {
            return result.getObjectName();
        }
        String value = firstBusinessValue(result, new String[] {
                "Primary form", "Primary Form", "Primary form name", "Primary Form Name",
                "Form name", "Form Name", "formName", "Form", "Forms", "Associated forms", "Associated Forms" });
        if (value.length() > 0) {
            return compactFormValue(value);
        }
        return "";
    }

    private String compactFormValue(String value) {
        String text = safeText(value);
        if (text.length() == 0) {
            return "";
        }
        text = text.replace('[', ' ').replace(']', ' ').replace('{', ' ').replace('}', ' ');
        text = text.replace("\r", " ").replace("\n", " ");
        while (text.indexOf("  ") >= 0) {
            text = text.replace("  ", " ");
        }
        text = text.trim();
        int arrow = text.indexOf(" → ");
        if (arrow > 0) {
            return text;
        }
        int comma = text.indexOf(',');
        if (comma > 0) {
            return text.substring(0, comma).trim() + " …";
        }
        return text;
    }

    private String formTypeColumnText(CompareResult result) {
        if (result == null) {
            return "";
        }
        return safeText(result.getFormTypeSummary());
    }

    private String contextColumnText(CompareResult result) {
        if (result == null) {
            return "";
        }
        if (isWorkflowType(result.getObjectType())) {
            String workflowState = safeText(result.getWorkflowStateSummary());
            if (workflowState.length() > 0) {
                return workflowState;
            }
        }
        String direct = safeText(result.getContextKeySummary());
        if (direct.length() > 0) {
            return direct;
        }
        String type = safeText(result.getObjectType()).toLowerCase(Locale.ENGLISH);
        if (type.indexOf("group") >= 0 || type.indexOf("role") >= 0 || type.indexOf("report") >= 0 || type.indexOf("template") >= 0) {
            String value = firstBusinessValue(result, new String[] { "Group ID", "GroupId", "Role ID", "RoleId", "Key / ID", "ID", "Id", "Request ID", "RequestId" });
            if (value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    private boolean isWorkflowType(String objectType) {
        String type = safeText(objectType).toLowerCase(Locale.ENGLISH).replace(" ", "");
        return type.equals("activelink") || type.equals("filter") || type.equals("escalation")
                || type.equals("activelinkguide") || type.equals("filterguide");
    }

    private String firstBusinessValue(CompareResult result, String[] properties) {
        if (result == null || result.getDifferenceDetails() == null || properties == null) {
            return "";
        }
        for (DiffDetail detail : result.getDifferenceDetails()) {
            String property = detail == null ? "" : safeText(detail.getProperty());
            for (int i = 0; i < properties.length; i++) {
                if (property.equalsIgnoreCase(properties[i])) {
                    String value = safeText(detail.getSourceValue());
                    if (value.length() == 0) {
                        value = safeText(detail.getTargetValue());
                    }
                    return value;
                }
            }
        }
        return "";
    }

    private List<CompareResult> getVisibleResults() {
        return new ArrayList<CompareResult>(visibleResults);
    }

    private void refresh(boolean rebuildFilterOptions) {
        long refreshStarted = System.currentTimeMillis();
        if (viewer != null && !viewer.getControl().isDisposed()) {
            if (rebuildFilterOptions) {
                rebuildDynamicFilterOptions();
            } else {
                updateTypeNavigator();
            }
            scopeCountSummaryValid = false;
            FilterSnapshot filter = captureFilterSnapshot();
            rebuildVisibleResults(filter);
            currentScopeCountSummary = createCurrentScopeCountSummaryInternal(filter);
            scopeCountSummaryValid = true;
            viewer.setInput(visibleResults);
            viewer.setItemCount(visibleResults.size());
            viewer.refresh();
        } else {
            scopeCountSummaryValid = false;
        }
        lastRefreshMillis = System.currentTimeMillis() - refreshStarted;
        updateFilterButtonLabels();
        updateSummaryText();
        updateActionEnablement();
        logLargeRepositoryPerformanceIfNeeded();
    }

    private void scheduleFilterRefresh(final boolean rebuildFilterOptions) {
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed()) {
            refresh(rebuildFilterOptions);
            return;
        }
        pendingFilterRefreshRebuildOptions = pendingFilterRefreshRebuildOptions || rebuildFilterOptions;
        final int generation = ++filterRefreshGeneration;
        Display display = viewer.getControl().getDisplay();
        display.timerExec(FILTER_DEBOUNCE_MS, new Runnable() {
            public void run() {
                if (generation != filterRefreshGeneration) {
                    return;
                }
                if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed()) {
                    return;
                }
                boolean rebuild = pendingFilterRefreshRebuildOptions;
                pendingFilterRefreshRebuildOptions = false;
                refresh(rebuild);
            }
        });
    }

    private void rebuildVisibleResults(FilterSnapshot filter) {
        long filterStarted = System.currentTimeMillis();
        visibleResults.clear();
        automaticSortSkipped = false;
        lastFilterInputRows = 0;
        lastFilterMatchedRows = 0;
        lastSortMillis = 0L;
        lastIndexMillis = 0L;
        lastIndexedRows = 0;
        if (session == null || session.getResults() == null) {
            lastFilterMillis = System.currentTimeMillis() - filterStarted;
            return;
        }
        lastFilterInputRows = session.getResults().size();
        for (CompareResult result : session.getResults()) {
            if (resultFilter.select(result, filter)) {
                visibleResults.add(result);
            }
        }
        lastFilterMatchedRows = visibleResults.size();
        lastFilterMillis = System.currentTimeMillis() - filterStarted;
        if (shouldSortVisibleResults()) {
            long sortStarted = System.currentTimeMillis();
            Collections.sort(visibleResults, new Comparator<CompareResult>() {
                public int compare(CompareResult left, CompareResult right) {
                    return comparator.compare(viewer, left, right);
                }
            });
            lastSortMillis = System.currentTimeMillis() - sortStarted;
        } else {
            automaticSortSkipped = comparator != null && visibleResults.size() > LARGE_REPOSITORY_AUTO_SORT_LIMIT;
        }
    }

    private boolean shouldSortVisibleResults() {
        if (comparator == null) {
            return false;
        }
        if (!largeRepositoryMode) {
            return true;
        }
        if (userSortRequested) {
            return true;
        }
        return visibleResults.size() <= LARGE_REPOSITORY_AUTO_SORT_LIMIT;
    }

    private void updateLargeRepositoryMode() {
        int size = session == null || session.getResults() == null ? 0 : session.getResults().size();
        largeRepositoryMode = size >= LARGE_REPOSITORY_THRESHOLD;
    }

    private ResultUiIndex indexFor(CompareResult result) {
        if (result == null) {
            return emptyResultUiIndex;
        }
        ResultUiIndex index = resultUiIndex.get(result);
        if (index == null) {
            long started = System.currentTimeMillis();
            index = new ResultUiIndex(result);
            resultUiIndex.put(result, index);
            lastIndexMillis += System.currentTimeMillis() - started;
            lastIndexedRows++;
        }
        return index;
    }

    private void rebuildDynamicFilterOptions() {
        rebuildingFilterOptions = true;
        try {
            updateTypeNavigator();
        } finally {
            rebuildingFilterOptions = false;
        }
    }

    private void rebuildCombo(Combo combo, String allLabel, List<String> values, String previous) {
        if (combo == null || combo.isDisposed()) {
            return;
        }
        combo.removeAll();
        combo.add(allLabel);
        for (String value : values) {
            combo.add(value);
        }
        int index = 0;
        if (previous != null && previous.length() > 0) {
            String[] items = combo.getItems();
            for (int i = 0; i < items.length; i++) {
                if (previous.equals(items[i])) {
                    index = i;
                    break;
                }
            }
        }
        combo.select(index);
    }

    private void updateFilterButtonLabels() {
        if (changedButton == null || changedButton.isDisposed()) {
            return;
        }
        CountSummary count = createCurrentScopeCountSummary();
        changedButton.setText("Different (" + count.changed + ")");
        missingButton.setText("Missing (" + count.missing + ")");
        errorButton.setText("Errors (" + count.errors + ")");
        equalButton.setText("Equal (" + count.equal + ")");
        if (stillDifferentButton != null && !stillDifferentButton.isDisposed()) {
            stillDifferentButton.setText("Still different (" + count.stillDifferent + ")");
        }
        if (permissionsDiffButton != null && !permissionsDiffButton.isDisposed()) {
            permissionsDiffButton.setText("Permissions (" + count.permissions + ")");
        }
        if (auditDiffButton != null && !auditDiffButton.isDisposed()) {
            auditDiffButton.setText("Audit (" + count.audit + ")");
        }
        changedButton.getParent().layout(true, true);
    }

    private void updateSummaryText() {
        if (summaryLabel == null || summaryLabel.isDisposed()) {
            return;
        }
        List<CompareResult> results = session.getResults();
        CountSummary count = createCurrentScopeCountSummary();
        int visible = viewer == null || viewer.getTable().isDisposed() ? count.total() : visibleResults.size();
        StringBuilder builder = new StringBuilder();
        builder.append(session.getLabel());
        builder.append(" — showing ").append(visible).append(" of ").append(count.total()).append(" filtered objects");
        if (results.size() != count.total()) {
            builder.append(" (").append(results.size()).append(" in cache overview)");
        }
        if (selectedNavigatorType != null && selectedNavigatorType.length() > 0) {
            builder.append("  |  type: ").append(selectedNavigatorType);
        }
        String customization = activeCustomizationFiltersText();
        if (customization.length() > 0 && !isDefaultCustomizationFilter()) {
            builder.append("  |  customization: ").append(customization);
        } else if (isDefaultCustomizationFilter()) {
            builder.append("  |  customization: Custom, Overlay");
        }
        builder.append("  |  ").append(count.changed).append(" different");
        builder.append(", ").append(count.missing).append(" missing");
        builder.append(", ").append(count.errors).append(" errors");
        builder.append(", ").append(count.equal).append(" equal");
        if (equalButton != null && !equalButton.isDisposed() && !equalButton.getSelection() && count.equal > 0) {
            builder.append("  |  equal rows hidden");
        }
        if (largeRepositoryMode) {
            builder.append("  |  large repository mode");
            if (automaticSortSkipped) {
                builder.append("; auto-sort paused");
            }
            builder.append("  |  filter ").append(lastFilterMillis).append(" ms");
            if (lastSortMillis > 0L) {
                builder.append(", sort ").append(lastSortMillis).append(" ms");
            }
            if (lastIndexMillis > 0L) {
                builder.append(", index ").append(lastIndexMillis).append(" ms/").append(lastIndexedRows).append(" new rows");
            }
        }
        String quick = activeQuickFiltersText();
        if (quick.length() > 0) {
            builder.append("  |  quick filter: ").append(quick);
        }
        summaryLabel.setText(builder.toString());
        summaryLabel.getParent().layout(true, true);
    }

    private CountSummary createCurrentScopeCountSummary() {
        if (scopeCountSummaryValid) {
            return currentScopeCountSummary;
        }
        return createCurrentScopeCountSummaryInternal();
    }

    private CountSummary createCurrentScopeCountSummaryInternal() {
        return createCurrentScopeCountSummaryInternal(captureFilterSnapshot());
    }

    private CountSummary createCurrentScopeCountSummaryInternal(FilterSnapshot filter) {
        CountSummary count = new CountSummary();
        if (session == null || session.getResults() == null) {
            return count;
        }
        for (CompareResult result : session.getResults()) {
            if (matchesNonStatusFilters(result, filter)) {
                addToCount(count, result);
            }
        }
        return count;
    }

    private CountSummary createCountSummary(List<CompareResult> results) {
        CountSummary count = new CountSummary();
        for (CompareResult result : results) {
            addToCount(count, result);
        }
        return count;
    }

    private void addToCount(CountSummary count, CompareResult result) {
        if (count == null || result == null || result.getStatus() == null) {
            if (count != null) {
                count.unknown++;
            }
            return;
        }
        switch (result.getStatus()) {
        case CHANGED:
            count.changed++;
            break;
        case MISSING_IN_TARGET:
        case MISSING_IN_SOURCE:
            count.missing++;
            break;
        case ERROR:
            count.errors++;
            break;
        case EQUAL:
            count.equal++;
            break;
        default:
            count.unknown++;
            break;
        }
        if (isStillDifferentRow(result)) {
            count.stillDifferent++;
        }
        if (hasLogicalDiffSection(result, "Permissions")) {
            count.permissions++;
        }
        if (hasLogicalDiffSection(result, "Audit Settings")) {
            count.audit++;
        }
    }

    private boolean matchesNonStatusFilters(CompareResult result) {
        return matchesNonStatusFilters(result, captureFilterSnapshot());
    }

    private boolean matchesNonStatusFilters(CompareResult result, FilterSnapshot filter) {
        return filter == null || filter.matchesNonStatus(result);
    }

    private boolean matchesNavigatorContext(CompareResult result) {
        return matchesNavigatorContext(result, captureFilterSnapshot());
    }

    private boolean matchesNavigatorContext(CompareResult result, FilterSnapshot filter) {
        return filter == null || filter.matchesNavigatorContext(result);
    }

    private boolean matchesCustomizationTypeFilter(CompareResult result) {
        boolean hasBaseControl = customizationBaseButton != null && !customizationBaseButton.isDisposed();
        boolean hasCustomControl = customizationCustomButton != null && !customizationCustomButton.isDisposed();
        boolean hasOverlayControl = customizationOverlayButton != null && !customizationOverlayButton.isDisposed();
        if (!hasBaseControl && !hasCustomControl && !hasOverlayControl) {
            return true;
        }
        boolean base = hasBaseControl && customizationBaseButton.getSelection();
        boolean custom = hasCustomControl && customizationCustomButton.getSelection();
        boolean overlay = hasOverlayControl && customizationOverlayButton.getSelection();
        if (base && custom && overlay) {
            return true;
        }
        ResultUiIndex index = indexFor(result);
        return (base && index.customizationBase)
                || (custom && index.customizationCustom)
                || (overlay && index.customizationOverlay);
    }

    private boolean isDefaultCustomizationFilter() {
        return customizationBaseButton != null && !customizationBaseButton.isDisposed()
                && customizationCustomButton != null && !customizationCustomButton.isDisposed()
                && customizationOverlayButton != null && !customizationOverlayButton.isDisposed()
                && !customizationBaseButton.getSelection()
                && customizationCustomButton.getSelection()
                && customizationOverlayButton.getSelection();
    }

    private String activeCustomizationFiltersText() {
        if (customizationBaseButton == null || customizationBaseButton.isDisposed()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        appendQuickFilter(out, customizationBaseButton, "Base");
        appendQuickFilter(out, customizationCustomButton, "Custom");
        appendQuickFilter(out, customizationOverlayButton, "Overlay");
        return out.toString();
    }

    private boolean matchesQuickDiffFilters(CompareResult result) {
        boolean still = stillDifferentButton != null && !stillDifferentButton.isDisposed() && stillDifferentButton.getSelection();
        boolean permissions = permissionsDiffButton != null && !permissionsDiffButton.isDisposed() && permissionsDiffButton.getSelection();
        boolean audit = auditDiffButton != null && !auditDiffButton.isDisposed() && auditDiffButton.getSelection();
        if (!still && !permissions && !audit) {
            return true;
        }
        return (still && isStillDifferentRow(result))
                || (permissions && hasLogicalDiffSection(result, "Permissions"))
                || (audit && hasLogicalDiffSection(result, "Audit Settings"));
    }

    private String activeQuickFiltersText() {
        StringBuilder out = new StringBuilder();
        appendQuickFilter(out, stillDifferentButton, "still different");
        appendQuickFilter(out, permissionsDiffButton, "permissions");
        appendQuickFilter(out, auditDiffButton, "audit");
        return out.toString();
    }

    private void appendQuickFilter(StringBuilder out, Button button, String label) {
        if (button == null || button.isDisposed() || !button.getSelection()) {
            return;
        }
        if (out.length() > 0) {
            out.append(", ");
        }
        out.append(label);
    }

    private boolean isStillDifferentRow(CompareResult result) {
        return indexFor(result).stillDifferent;
    }

    private boolean hasLogicalDiffSection(CompareResult result, String section) {
        ResultUiIndex index = indexFor(result);
        if ("Permissions".equals(section)) {
            return index.hasPermissions;
        }
        if ("Audit Settings".equals(section)) {
            return index.hasAudit;
        }
        if (result == null || result.getDifferenceDetails() == null) {
            return false;
        }
        for (DiffDetail detail : result.getDifferenceDetails()) {
            if (section.equals(DiffSummaryAnalyzer.logicalSection(detail))) {
                return true;
            }
        }
        return false;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ENGLISH);
    }

    private boolean matchesStatusFilter(CompareResult result) {
        CompareStatus status = result.getStatus();
        if (status == CompareStatus.CHANGED) {
            return changedButton == null || changedButton.getSelection();
        }
        if (status == CompareStatus.MISSING_IN_TARGET || status == CompareStatus.MISSING_IN_SOURCE) {
            return missingButton == null || missingButton.getSelection();
        }
        if (status == CompareStatus.ERROR) {
            return errorButton == null || errorButton.getSelection();
        }
        if (status == CompareStatus.EQUAL) {
            return equalButton != null && equalButton.getSelection();
        }
        return true;
    }

    private boolean matchesTypeFilter(CompareResult result) {
        if (selectedNavigatorType == null || selectedNavigatorType.length() == 0) {
            return true;
        }
        return selectedNavigatorType.equals(result.getObjectType());
    }

    private boolean matchesChangedByFilter(CompareResult result) {
        if (changedByText == null || changedByText.isDisposed()) {
            return true;
        }
        String selected = changedByText.getText();
        if (selected == null || selected.trim().length() == 0) {
            return true;
        }
        String q = selected.trim().toLowerCase(Locale.ENGLISH);
        return indexFor(result).changedByText.indexOf(q) >= 0;
    }

    private boolean matchesModifiedFilter(CompareResult result) {
        if (modifiedAfterEnabledButton == null || modifiedAfterEnabledButton.isDisposed()
                || !modifiedAfterEnabledButton.getSelection()
                || modifiedAfterDate == null || modifiedAfterDate.isDisposed()) {
            return true;
        }
        long threshold = selectedModifiedAfterSeconds();
        ResultUiIndex index = indexFor(result);
        return isOnOrAfter(index.sourceModifiedSeconds, threshold) || isOnOrAfter(index.targetModifiedSeconds, threshold);
    }

    private boolean matchesTextFilter(CompareResult result) {
        if (searchText == null || searchText.isDisposed()) {
            return true;
        }
        String query = searchText.getText();
        if (query == null || query.trim().length() == 0) {
            return true;
        }
        String q = query.toLowerCase(Locale.ENGLISH).trim();
        if (q.length() == 0) {
            return true;
        }
        return indexFor(result).searchText.indexOf(q) >= 0;
    }

    private long startOfTodaySeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    private boolean isOnOrAfter(long timestamp, long threshold) {
        return timestamp != Long.MIN_VALUE && timestamp >= threshold;
    }

    private boolean isBefore(long timestamp, long threshold) {
        return timestamp != Long.MIN_VALUE && timestamp < threshold;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private boolean containsDetails(CompareResult result, String query) {
        if (result == null || result.getDifferenceDetails() == null) {
            return false;
        }
        for (se.yrell.migrator.core.DiffDetail detail : result.getDifferenceDetails()) {
            if (contains(detail.getArea(), query) || contains(detail.getProperty(), query)
                    || contains(detail.getSourceValue(), query) || contains(detail.getTargetValue(), query)
                    || contains(detail.getKind(), query)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ENGLISH).contains(query);
    }

    private Image sharedImage(String key) {
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

    private Color statusForeground(CompareStatus status) {
        if (viewer == null || viewer.getTable().isDisposed()) {
            return null;
        }
        switch (status) {
        case CHANGED:
            return viewer.getTable().getDisplay().getSystemColor(SWT.COLOR_DARK_BLUE);
        case MISSING_IN_SOURCE:
        case MISSING_IN_TARGET:
            return viewer.getTable().getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW);
        case ERROR:
            return viewer.getTable().getDisplay().getSystemColor(SWT.COLOR_RED);
        case EQUAL:
            return viewer.getTable().getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN);
        default:
            return viewer.getTable().getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
        }
    }


    private int statusRank(CompareStatus status) {
        switch (status) {
        case CHANGED:
            return 0;
        case MISSING_IN_TARGET:
        case MISSING_IN_SOURCE:
            return 1;
        case ERROR:
            return 2;
        case EQUAL:
            return 3;
        default:
            return 4;
        }
    }

    private FilterSnapshot captureFilterSnapshot() {
        return new FilterSnapshot();
    }

    private String[] splitSearchTokens(String query) {
        if (query == null) {
            return new String[0];
        }
        String normalized = query.toLowerCase(Locale.ENGLISH).trim();
        if (normalized.length() == 0) {
            return new String[0];
        }
        java.util.List<String> tokens = new java.util.ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && Character.isWhitespace(ch)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[tokens.size()]);
    }

    private void logLargeRepositoryPerformanceIfNeeded() {
        if (!largeRepositoryMode) {
            return;
        }
        if (lastRefreshMillis < LARGE_REPOSITORY_PERF_LOG_THRESHOLD_MS
                && lastFilterMillis < LARGE_REPOSITORY_PERF_LOG_THRESHOLD_MS
                && lastSortMillis < LARGE_REPOSITORY_PERF_LOG_THRESHOLD_MS
                && lastIndexMillis < LARGE_REPOSITORY_PERF_LOG_THRESHOLD_MS) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPerformanceLogAtMillis < 5000L) {
            return;
        }
        lastPerformanceLogAtMillis = now;
        appendActivity("UI performance: " + lastFilterInputRows + " rows scanned, "
                + lastFilterMatchedRows + " shown; filter=" + lastFilterMillis + " ms, sort="
                + lastSortMillis + " ms, index=" + lastIndexMillis + " ms/" + lastIndexedRows
                + " new rows, refresh=" + lastRefreshMillis + " ms.");
    }

    private boolean isAlwaysShownRegardlessOfCustomization(CompareResult result) {
        return result != null && ObjectTypeRegistry.isAlwaysShownRegardlessOfCustomizationFilter(result.getObjectType());
    }

    private final class FilterSnapshot {
        final String selectedType;
        final boolean showChanged;
        final boolean showMissing;
        final boolean showEqual;
        final boolean showError;
        final boolean showBase;
        final boolean showCustom;
        final boolean showOverlay;
        final boolean allCustomization;
        final String changedByQuery;
        final String[] searchTokens;
        final boolean modifiedAfterEnabled;
        final long modifiedAfterSeconds;
        final boolean quickStillDifferent;
        final boolean quickPermissions;
        final boolean quickAudit;

        FilterSnapshot() {
            this.selectedType = selectedNavigatorType == null ? "" : selectedNavigatorType;
            this.showChanged = changedButton == null || changedButton.isDisposed() || changedButton.getSelection();
            this.showMissing = missingButton == null || missingButton.isDisposed() || missingButton.getSelection();
            this.showEqual = equalButton != null && !equalButton.isDisposed() && equalButton.getSelection();
            this.showError = errorButton == null || errorButton.isDisposed() || errorButton.getSelection();
            this.showBase = customizationBaseButton != null && !customizationBaseButton.isDisposed() && customizationBaseButton.getSelection();
            this.showCustom = customizationCustomButton == null || customizationCustomButton.isDisposed() || customizationCustomButton.getSelection();
            this.showOverlay = customizationOverlayButton == null || customizationOverlayButton.isDisposed() || customizationOverlayButton.getSelection();
            this.allCustomization = showBase && showCustom && showOverlay;
            this.changedByQuery = changedByText == null || changedByText.isDisposed()
                    ? "" : safeLower(changedByText.getText()).trim();
            this.searchTokens = splitSearchTokens(searchText == null || searchText.isDisposed() ? "" : searchText.getText());
            this.modifiedAfterEnabled = modifiedAfterEnabledButton != null && !modifiedAfterEnabledButton.isDisposed()
                    && modifiedAfterEnabledButton.getSelection()
                    && modifiedAfterDate != null && !modifiedAfterDate.isDisposed();
            this.modifiedAfterSeconds = modifiedAfterEnabled ? selectedModifiedAfterSeconds() : Long.MIN_VALUE;
            this.quickStillDifferent = stillDifferentButton != null && !stillDifferentButton.isDisposed() && stillDifferentButton.getSelection();
            this.quickPermissions = permissionsDiffButton != null && !permissionsDiffButton.isDisposed() && permissionsDiffButton.getSelection();
            this.quickAudit = auditDiffButton != null && !auditDiffButton.isDisposed() && auditDiffButton.getSelection();
        }

        boolean matches(CompareResult result) {
            return matchesStatus(result) && matchesNonStatus(result);
        }

        boolean matchesNonStatus(CompareResult result) {
            return matchesType(result)
                    && matchesCustomization(result)
                    && matchesChangedBy(result)
                    && matchesModified(result)
                    && matchesSearchText(result)
                    && matchesQuick(result);
        }

        boolean matchesNavigatorContext(CompareResult result) {
            return matchesCustomization(result)
                    && matchesChangedBy(result)
                    && matchesModified(result)
                    && matchesSearchText(result);
        }

        private boolean matchesStatus(CompareResult result) {
            if (result == null || result.getStatus() == null) {
                return true;
            }
            CompareStatus status = result.getStatus();
            if (status == CompareStatus.CHANGED) {
                return showChanged;
            }
            if (status == CompareStatus.MISSING_IN_TARGET || status == CompareStatus.MISSING_IN_SOURCE) {
                return showMissing;
            }
            if (status == CompareStatus.ERROR) {
                return showError;
            }
            if (status == CompareStatus.EQUAL) {
                return showEqual;
            }
            return true;
        }

        private boolean matchesType(CompareResult result) {
            return selectedType.length() == 0 || (result != null && selectedType.equals(result.getObjectType()));
        }

        private boolean matchesCustomization(CompareResult result) {
            if (allCustomization || isAlwaysShownRegardlessOfCustomization(result)) {
                return true;
            }
            ResultUiIndex index = indexFor(result);
            return (showBase && index.customizationBase)
                    || (showCustom && index.customizationCustom)
                    || (showOverlay && index.customizationOverlay);
        }

        private boolean matchesChangedBy(CompareResult result) {
            return changedByQuery.length() == 0 || indexFor(result).changedByText.indexOf(changedByQuery) >= 0;
        }

        private boolean matchesModified(CompareResult result) {
            if (!modifiedAfterEnabled) {
                return true;
            }
            ResultUiIndex index = indexFor(result);
            return isOnOrAfter(index.sourceModifiedSeconds, modifiedAfterSeconds)
                    || isOnOrAfter(index.targetModifiedSeconds, modifiedAfterSeconds);
        }

        private boolean matchesSearchText(CompareResult result) {
            if (searchTokens.length == 0) {
                return true;
            }
            String haystack = indexFor(result).searchText;
            for (int i = 0; i < searchTokens.length; i++) {
                if (haystack.indexOf(searchTokens[i]) < 0) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesQuick(CompareResult result) {
            if (!quickStillDifferent && !quickPermissions && !quickAudit) {
                return true;
            }
            ResultUiIndex index = indexFor(result);
            return (quickStillDifferent && index.stillDifferent)
                    || (quickPermissions && index.hasPermissions)
                    || (quickAudit && index.hasAudit);
        }
    }

    private long selectedModifiedAfterSeconds() {
        if (modifiedAfterDate == null || modifiedAfterDate.isDisposed()) {
            return Long.MIN_VALUE;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, modifiedAfterDate.getYear());
        calendar.set(Calendar.MONTH, modifiedAfterDate.getMonth());
        calendar.set(Calendar.DAY_OF_MONTH, modifiedAfterDate.getDay());
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    private final class ResultFilter extends ViewerFilter {
        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element) {
            if (!(element instanceof CompareResult)) {
                return false;
            }
            return select((CompareResult) element, captureFilterSnapshot());
        }

        public boolean select(CompareResult result, FilterSnapshot filter) {
            return result != null && (filter == null || filter.matches(result));
        }
    }

    private final class ResultComparator extends ViewerComparator {
        private int column = COL_STATUS;
        private boolean ascending = true;

        public void setColumn(int column) {
            if (this.column == column) {
                ascending = !ascending;
            } else {
                this.column = column;
                ascending = true;
            }
        }

        public boolean isAscending() {
            return ascending;
        }

        @Override
        public int compare(Viewer viewer, Object e1, Object e2) {
            CompareResult left = (CompareResult) e1;
            CompareResult right = (CompareResult) e2;
            int result;
            switch (column) {
            case COL_STATUS:
                result = statusRank(left.getStatus()) - statusRank(right.getStatus());
                break;
            case COL_TYPE:
                result = compareString(left.getObjectType(), right.getObjectType());
                break;
            case COL_CUSTOMIZATION_TYPE:
                result = compareString(left.getCustomizationTypeSummary(), right.getCustomizationTypeSummary());
                break;
            case COL_NAME:
                result = compareString(left.getObjectName(), right.getObjectName());
                break;
            case COL_FORM:
                result = compareString(primaryFormColumnText(left), primaryFormColumnText(right));
                break;
            case COL_FORM_TYPE:
                result = compareString(formTypeColumnText(left), formTypeColumnText(right));
                break;
            case COL_CONTEXT:
                result = compareContextKey(contextColumnText(left), contextColumnText(right));
                break;
            case COL_SOURCE_MODIFIED:
                result = compareLong(UiStrings.timestampValue(left.getSourceModified()), UiStrings.timestampValue(right.getSourceModified()));
                break;
            case COL_TARGET_MODIFIED:
                result = compareLong(UiStrings.timestampValue(left.getTargetModified()), UiStrings.timestampValue(right.getTargetModified()));
                break;
            case COL_SOURCE_CHANGED_BY:
                result = compareString(left.getSourceChangedBy(), right.getSourceChangedBy());
                break;
            case COL_TARGET_CHANGED_BY:
                result = compareString(left.getTargetChangedBy(), right.getTargetChangedBy());
                break;
            default:
                result = compareString(left.getObjectName(), right.getObjectName());
                break;
            }
            if (result == 0) {
                result = compareString(left.getObjectName(), right.getObjectName());
            }
            return ascending ? result : -result;
        }

        private int compareString(String left, String right) {
            if (left == null) {
                left = "";
            }
            if (right == null) {
                right = "";
            }
            return String.CASE_INSENSITIVE_ORDER.compare(left, right);
        }

        private int compareContextKey(String left, String right) {
            String l = left == null ? "" : left.trim();
            String r = right == null ? "" : right.trim();
            Long leftNumber = parseWholeNumber(l);
            Long rightNumber = parseWholeNumber(r);
            if (leftNumber != null && rightNumber != null) {
                int numeric = compareLong(leftNumber.longValue(), rightNumber.longValue());
                if (numeric != 0) {
                    return numeric;
                }
                return compareString(l, r);
            }
            if (leftNumber != null) {
                return -1;
            }
            if (rightNumber != null) {
                return 1;
            }
            return compareString(l, r);
        }

        private Long parseWholeNumber(String value) {
            if (value == null) {
                return null;
            }
            String text = value.trim();
            if (text.length() == 0) {
                return null;
            }
            int start = 0;
            if (text.charAt(0) == '-' || text.charAt(0) == '+') {
                if (text.length() == 1) {
                    return null;
                }
                start = 1;
            }
            for (int i = start; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return null;
                }
            }
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private int compareLong(long left, long right) {
            if (left == right) {
                return 0;
            }
            return left < right ? -1 : 1;
        }
    }

    private boolean isNormalSnapshotEvidence(CompareResult result) {
        return result != null && result.getEvidence() == CompareEvidence.CACHED_SNAPSHOT;
    }

    private final class StatusLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            CompareResult result = (CompareResult) element;
            String label = result.getStatus().getLabel();
            return isNormalSnapshotEvidence(result) ? label : label + " ⚠";
        }

        @Override
        public Image getImage(Object element) {
            CompareResult result = (CompareResult) element;
            if (result.getEvidence() == CompareEvidence.CACHE_ERROR) {
                return sharedImage(ISharedImages.IMG_OBJS_ERROR_TSK);
            }
            if (!isNormalSnapshotEvidence(result) && result.getStatus() == CompareStatus.EQUAL) {
                return sharedImage(ISharedImages.IMG_OBJS_WARN_TSK);
            }
            CompareStatus status = result.getStatus();
            switch (status) {
            case ERROR:
                return sharedImage(ISharedImages.IMG_OBJS_ERROR_TSK);
            case CHANGED:
            case MISSING_IN_TARGET:
            case MISSING_IN_SOURCE:
                return sharedImage(ISharedImages.IMG_OBJS_WARN_TSK);
            case EQUAL:
                return sharedImage(ISharedImages.IMG_OBJS_INFO_TSK);
            default:
                return sharedImage(ISharedImages.IMG_OBJ_ELEMENT);
            }
        }

        @Override
        public Color getForeground(Object element) {
            return statusForeground(((CompareResult) element).getStatus());
        }

        @Override
        public String getToolTipText(Object element) {
            CompareResult result = (CompareResult) element;
            return result.getStatus().getLabel() + " — " + result.getDetail() + "\nEvidence: " + result.getEvidenceTooltip();
        }
    }

    private abstract class LinkLabelProvider extends StyledCellLabelProvider {
        private final Styler linkStyler = new Styler() {
            @Override
            public void applyStyles(TextStyle textStyle) {
                textStyle.foreground = viewer.getTable().getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND);
                textStyle.underline = true;
            }
        };

        @Override
        public void update(ViewerCell cell) {
            CompareResult result = (CompareResult) cell.getElement();
            String text = getLinkText(result);
            StyledString styled = new StyledString();
            if (text != null) {
                if (isLink(result)) {
                    styled.append(text, linkStyler);
                } else {
                    styled.append(text);
                }
            }
            cell.setText(styled.getString());
            cell.setStyleRanges(styled.getStyleRanges());
            super.update(cell);
        }

        @Override
        public String getToolTipText(Object element) {
            return getTooltip((CompareResult) element);
        }

        protected abstract String getLinkText(CompareResult result);

        protected abstract boolean isLink(CompareResult result);

        protected String getTooltip(CompareResult result) {
            return null;
        }
    }

    private final class StoreLinkLabelProvider extends LinkLabelProvider {
        private final boolean source;

        StoreLinkLabelProvider(boolean source) {
            this.source = source;
        }

        @Override
        protected String getLinkText(CompareResult result) {
            return source ? result.getSourceServer() : result.getTargetServer();
        }

        @Override
        protected boolean isLink(CompareResult result) {
            return source ? canOpenSource(result) : canOpenTarget(result);
        }

        @Override
        protected String getTooltip(CompareResult result) {
            if (source && canOpenSource(result)) {
                return result.getSourceItem() == null ? "Resolve and open source object" : "Open source object";
            }
            if (!source && canOpenTarget(result)) {
                return result.getTargetItem() == null ? "Resolve and open target object" : "Open target object";
            }
            return source ? "Source object is not available" : "Target object is not available";
        }
    }


    private final class ResultUiIndex {
        final String searchText;
        final String changedByText;
        final long sourceModifiedSeconds;
        final long targetModifiedSeconds;
        final boolean stillDifferent;
        final boolean hasPermissions;
        final boolean hasAudit;
        final boolean customizationBase;
        final boolean customizationCustom;
        final boolean customizationOverlay;

        ResultUiIndex(CompareResult result) {
            if (result == null) {
                this.searchText = "";
                this.changedByText = "";
                this.sourceModifiedSeconds = Long.MIN_VALUE;
                this.targetModifiedSeconds = Long.MIN_VALUE;
                this.stillDifferent = false;
                this.hasPermissions = false;
                this.hasAudit = false;
                this.customizationBase = true;
                this.customizationCustom = false;
                this.customizationOverlay = false;
                return;
            }
            this.sourceModifiedSeconds = UiStrings.timestampValue(result.getSourceModified());
            this.targetModifiedSeconds = UiStrings.timestampValue(result.getTargetModified());
            this.changedByText = lowerJoin(result.getSourceChangedBy(), result.getTargetChangedBy());
            boolean permission = false;
            boolean audit = false;
            StringBuilder details = new StringBuilder(256);
            if (result.getDifferenceDetails() != null) {
                for (DiffDetail detail : result.getDifferenceDetails()) {
                    if (detail == null) {
                        continue;
                    }
                    String section = DiffSummaryAnalyzer.logicalSection(detail);
                    if ("Permissions".equals(section)) {
                        permission = true;
                    } else if ("Audit Settings".equals(section)) {
                        audit = true;
                    }
                    appendSearch(details, detail.getArea());
                    appendSearch(details, detail.getProperty());
                    appendSearch(details, detail.getSourceValue());
                    appendSearch(details, detail.getTargetValue());
                    appendSearch(details, detail.getKind());
                }
            }
            this.hasPermissions = permission;
            this.hasAudit = audit;
            String customizationText = lowerJoin(result.getSourceCustomizationType(), result.getTargetCustomizationType(), result.getCustomizationTypeSummary());
            boolean custom = customizationText.indexOf("custom") >= 0;
            boolean overlay = customizationText.indexOf("overlay") >= 0 || customizationText.indexOf("overlaid") >= 0;
            boolean base = customizationText.indexOf("base") >= 0 || customizationText.length() == 0;
            this.customizationCustom = custom;
            this.customizationOverlay = overlay;
            this.customizationBase = base || (!custom && !overlay);
            String statusLabel = result.getStatus() == null ? "" : result.getStatus().getLabel();
            this.stillDifferent = lowerJoin(result.getDetail(), result.getEvidenceDetail(), statusLabel)
                    .indexOf("still different") >= 0
                    || lowerJoin(result.getDetail(), result.getEvidenceDetail(), statusLabel)
                    .indexOf("source and target are still different") >= 0;

            StringBuilder search = new StringBuilder(512 + details.length());
            appendSearch(search, result.getObjectName());
            appendSearch(search, result.getObjectType());
            appendSearch(search, statusLabel);
            appendSearch(search, result.getEvidenceLabel());
            appendSearch(search, result.getEvidenceDetail());
            appendSearch(search, result.getCustomizationTypeSummary());
            appendSearch(search, result.getSourceCustomizationType());
            appendSearch(search, result.getTargetCustomizationType());
            appendSearch(search, result.getSourceServer());
            appendSearch(search, result.getTargetServer());
            appendSearch(search, result.getSourceChangedBy());
            appendSearch(search, result.getTargetChangedBy());
            appendSearch(search, UiStrings.timestamp(result.getSourceModified()));
            appendSearch(search, UiStrings.timestamp(result.getTargetModified()));
            appendSearch(search, result.getDetail());
            appendSearch(search, primaryFormColumnText(result));
            appendSearch(search, formTypeColumnText(result));
            appendSearch(search, contextColumnText(result));
            appendSearch(search, result.getWorkflowStateSummary());
            search.append(details);
            this.searchText = search.toString().toLowerCase(Locale.ENGLISH);
        }

        private String lowerJoin(String one, String two) {
            return lowerJoin(new String[] { one, two });
        }

        private String lowerJoin(String one, String two, String three) {
            return lowerJoin(new String[] { one, two, three });
        }

        private String lowerJoin(String[] values) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                appendSearch(out, values[i]);
            }
            return out.toString().toLowerCase(Locale.ENGLISH);
        }

        private void appendSearch(StringBuilder out, String value) {
            if (value == null || value.length() == 0) {
                return;
            }
            out.append(' ').append(value);
        }
    }

    private static final class CountSummary {
        int changed;
        int missing;
        int equal;
        int errors;
        int unknown;
        int stillDifferent;
        int permissions;
        int audit;

        int total() {
            return changed + missing + equal + errors + unknown;
        }

        boolean hasIssues() {
            return changed > 0 || missing > 0 || errors > 0 || unknown > 0;
        }
    }
}
