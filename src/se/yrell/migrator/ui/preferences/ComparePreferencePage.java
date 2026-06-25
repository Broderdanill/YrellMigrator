package se.yrell.migrator.ui.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import se.yrell.migrator.Activator;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.config.SettingsHealthReport;

/** Preference page shown under Window -> Preferences -> Yrell Migrator. */
public final class ComparePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
    public ComparePreferencePage() {
        super(GRID);
        setDescription("Configure sync, search and migration behaviour.");
    }

    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        CompareSettings.initializeDefaults(getPreferenceStore());
    }

    @Override
    protected void createFieldEditors() {
        addOverviewButton(getFieldEditorParent());

        addSection("Sync and local cache",
                "Sync refreshes metadata from the selected environments, then reuses unchanged definition snapshots when incremental sync is enabled. "
                        + "Include keeps matching object names. Exclude removes matching object names and wins over include. "
                        + "Cache customization types accepts Base, Custom and Overlay; default is Custom,Overlay. Non-customizable catalog/data objects such as Group, Role, Report and Template are always included. "
                        + "Use rebuild only when you want to regenerate every snapshot, for example after changing ignore rules. "
                        + "Binary/support and expensive reference/container deep-cache are off by default because Images, Web Services, Applications, Flashboards, Data Visualization definitions, Packing Lists and Reports can be slow or memory-heavy in Developer Studio.");
        addField(compactStringField(CompareSettings.KEY_SYNC_INCLUDE_NAME_PATTERNS, "Include patterns:"));
        addField(compactStringField(CompareSettings.KEY_SYNC_EXCLUDE_NAME_PATTERNS, "Exclude patterns:"));
        addField(compactStringField(CompareSettings.KEY_SYNC_CACHE_CUSTOMIZATION_TYPES, "Cache customization types:"));
        addField(new BooleanFieldEditor(CompareSettings.KEY_SYNC_INCREMENTAL_DEFINITIONS, "Incremental sync", getFieldEditorParent()));
        addField(new BooleanFieldEditor(CompareSettings.KEY_SYNC_RETRY_ERROR_SNAPSHOTS, "Retry failed snapshots", getFieldEditorParent()));
        addField(new BooleanFieldEditor(CompareSettings.KEY_SYNC_FORCE_REFRESH_DEFINITIONS, "Rebuild snapshots next Sync", getFieldEditorParent()));
        IntegerFieldEditor objectTimeout = new IntegerFieldEditor(CompareSettings.KEY_COMPARE_OBJECT_TIMEOUT_SECONDS, "Object timeout (seconds):", getFieldEditorParent());
        objectTimeout.setValidRange(10, 3600);
        addField(objectTimeout);

        IntegerFieldEditor metadataTimeout = new IntegerFieldEditor(CompareSettings.KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS, "Metadata timeout (seconds):", getFieldEditorParent());
        metadataTimeout.setValidRange(5, 3600);
        addField(metadataTimeout);

        addField(new BooleanFieldEditor(CompareSettings.KEY_METADATA_AGGRESSIVE_ENUMERATION, "Aggressive metadata fallback", getFieldEditorParent()));

        addSection("Cached search and Yrell Migrator - Differences",
                "Search reads the local definition cache and opens Yrell Migrator - Differences automatically. Max rows limits how many cached comparison rows are returned. Max details controls the summary text per row; full details are still available in the details dialog and CSV export.");
        IntegerFieldEditor searchLimit = new IntegerFieldEditor(CompareSettings.KEY_SEARCH_MAX_RESULTS, "Max search rows:", getFieldEditorParent());
        searchLimit.setValidRange(1, 50000);
        addField(searchLimit);
        IntegerFieldEditor summary = new IntegerFieldEditor(CompareSettings.KEY_MAX_SUMMARY_ITEMS, "Max details per row:", getFieldEditorParent());
        summary.setValidRange(1, 100);
        addField(summary);
        addField(new BooleanFieldEditor(CompareSettings.KEY_SHOW_EQUAL_BY_DEFAULT, "Show equal objects", getFieldEditorParent()));

        addSection("Ignore rules",
                "Ignore rules are applied while snapshots are generated. Rebuild snapshots after changing ignore rules if you want old cached snapshots to reflect the new rules. Typical ignores are lastChangedBy, lastUpdateTime, lastModifiedBy and lastModifiedTime.");
        addField(compactStringField(CompareSettings.KEY_IGNORE_DIFFERENCE_NAME_CONTAINS, "Ignore properties:"));
        addField(compactStringField(CompareSettings.KEY_IGNORE_MASK_IDS, "Ignore mask ids:"));
        addField(compactStringField(CompareSettings.KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS, "Ignore internals:"));

        addSection("Advanced live refresh / legacy compare",
                "These settings only affect manual live refresh and older direct compare paths. Normal Search and Yrell Migrator - Differences details use the Sync cache.");
        addField(new BooleanFieldEditor(CompareSettings.KEY_FORCE_RELOAD_OBJECTS, "Reload object before refresh", getFieldEditorParent()));
        addField(new BooleanFieldEditor(CompareSettings.KEY_DEEP_COMPARE_FORMS, "Deep live form compare", getFieldEditorParent()));
        addField(new BooleanFieldEditor(CompareSettings.KEY_FINGERPRINT_FALLBACK, "Strict fallback scan", getFieldEditorParent()));
        addField(new BooleanFieldEditor(CompareSettings.KEY_USE_BMC_DEFAULT_IGNORES, "Use BMC ignore list", getFieldEditorParent()));
        addField(new BooleanFieldEditor(CompareSettings.KEY_CACHE_OBJECTS, "Cache live objects", getFieldEditorParent()));
    }


    private StringFieldEditor compactStringField(String key, String labelText) {
        return new CompactStringFieldEditor(key, labelText, getFieldEditorParent());
    }

    private static final class CompactStringFieldEditor extends StringFieldEditor {
        private static final int TEXT_WIDTH_HINT = 520;

        CompactStringFieldEditor(String name, String labelText, Composite parent) {
            super(name, labelText, parent);
        }

        @Override
        protected void doFillIntoGrid(Composite parent, int numColumns) {
            super.doFillIntoGrid(parent, numColumns);
            Text text = getTextControl(parent);
            Object data = text.getLayoutData();
            GridData gd = data instanceof GridData ? (GridData) data : new GridData();
            gd.widthHint = TEXT_WIDTH_HINT;
            gd.minimumWidth = 200;
            gd.grabExcessHorizontalSpace = true;
            gd.horizontalAlignment = SWT.FILL;
            text.setLayoutData(gd);
        }
    }

    private void addOverviewButton(Composite parent) {
        CompareSettings settings = CompareSettings.load();
        StringBuilder text = new StringBuilder();
        text.append("Active settings source:\n").append(settings.describeLocation()).append("\n\n");
        if (CompareSettings.getInstallAreaConfigFile() != null) {
            text.append("Optional shared properties file:\n").append(CompareSettings.getInstallAreaConfigFile().getAbsolutePath()).append("\n\n");
        } else {
            text.append("Optional shared properties file:\nyrell-migrator.properties next to devstudio.ini\n\n");
        }
        text.append("Effective Sync filter:\n").append(settings.describeSyncFilters()).append("\n");
        text.append(SettingsHealthReport.format(settings)).append("\n");
        text.append("Pattern rules:\n")
                .append("Include keeps matching object names. Exclude removes matching object names and wins over include.\n")
                .append("Wildcards: * or % = any text, ? or _ = one character.\n")
                .append("Empty include means include everything.");

        Composite row = createFullWidthRow(parent, 2);
        Label label = new Label(row, SWT.NONE);
        label.setText("Settings");
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        addInfoButton(row, "Settings information", text.toString());
    }

    private void addSection(String text, final String helpText) {
        Composite row = createFullWidthRow(getFieldEditorParent(), 2);
        GridData rowData = (GridData) row.getLayoutData();
        rowData.verticalIndent = 8;

        Label label = new Label(row, SWT.NONE);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        addInfoButton(row, text, helpText);
    }

    private Composite createFullWidthRow(Composite parent, int columns) {
        Composite row = new Composite(parent, SWT.NONE);
        GridData data = new GridData(SWT.LEFT, SWT.TOP, false, false);
        data.horizontalSpan = 2;
        row.setLayoutData(data);
        GridLayout layout = new GridLayout(columns, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 6;
        row.setLayout(layout);
        return row;
    }

    private void addInfoButton(Composite parent, final String title, final String text) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText("i");
        button.setToolTipText("Show help for this section");
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.widthHint = 26;
        button.setLayoutData(gd);
        button.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                MessageDialog.openInformation(getShell(), title, text == null ? "" : text);
            }
        });
    }
}
