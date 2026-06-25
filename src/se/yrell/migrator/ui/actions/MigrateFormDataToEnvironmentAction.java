package se.yrell.migrator.ui.actions;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.item.ItemList;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.ui.views.objectlist.actions.BaseObjectListAction;

import se.yrell.migrator.bmc.BmcDataMigrator;
import se.yrell.migrator.bmc.BmcModelAdapter;
import se.yrell.migrator.ui.dialogs.DataMigrationOptionsDialog;
import se.yrell.migrator.ui.dialogs.MigrationReportDialog;
import se.yrell.migrator.ui.util.ProgressMessages;

/** Migrates entries/data from selected forms to another connected environment. */
public final class MigrateFormDataToEnvironmentAction extends BaseObjectListAction {
    private final BmcModelAdapter adapter = new BmcModelAdapter();
    private final BmcDataMigrator dataMigrator = new BmcDataMigrator();

    public MigrateFormDataToEnvironmentAction(ItemList<IModelItem> items) {
        setText("Migrate Form Data to Environment...");
        setToolTipText("Migrate entries/data for selected forms to another connected server");
        setItems(items);
    }

    @Override
    public void run() {
        ItemList<IModelItem> selected = getItems();
        if (selected == null || selected.isEmpty()) {
            showInfo("No forms are selected.");
            return;
        }
        IModelItem firstItem = selected.getFirstItem();
        final IStore sourceStore = firstItem == null ? null : firstItem.getStore();
        if (sourceStore == null || !sourceStore.isConnected()) {
            showInfo("The selected source environment is not connected.");
            return;
        }
        final List<IModelItem> forms = toFormList(selected);
        if (forms.isEmpty()) {
            showInfo("No forms are selected. Data migration is only available for forms.");
            return;
        }
        if (forms.size() != selected.size()) {
            boolean keepGoing = MessageDialog.openQuestion(getShell(), "Migrate Form Data",
                    (selected.size() - forms.size()) + " selected object(s) will be skipped because they are not forms.\n\nContinue with " + forms.size() + " form(s)?");
            if (!keepGoing) {
                return;
            }
        }
        for (IModelItem item : forms) {
            if (item.getStore() == null || !sourceStore.getName().equalsIgnoreCase(item.getStore().getName())) {
                showInfo("Please migrate data from one source environment at a time.");
                return;
            }
        }
        List<IStore> targetStores = adapter.getConnectedStoresExcluding(sourceStore);
        if (targetStores.isEmpty()) {
            showInfo("Connect to at least one additional AR System server before migrating form data.");
            return;
        }

        DataMigrationOptionsDialog dialog = new DataMigrationOptionsDialog(getShell(), forms.size() == 1 ? forms.get(0).getName() : forms.size() + " selected forms", sourceStore, targetStores);
        if (dialog.open() != DataMigrationOptionsDialog.OK) {
            return;
        }
        final BmcDataMigrator.Options template = dialog.getOptions();
        if (template == null) {
            return;
        }

        previewAndConfirm(forms, sourceStore, template);
    }

    private void previewAndConfirm(final List<IModelItem> forms, final IStore sourceStore, final BmcDataMigrator.Options template) {
        final Display display = Display.getDefault();
        Job previewJob = new Job("Preview form data migration to " + template.getTargetStore().getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final List<BmcDataMigrator.Preview> previews = new ArrayList<BmcDataMigrator.Preview>();
                final StringBuilder failures = new StringBuilder();
                monitor.beginTask("Previewing form data migration", forms.size());
                final ProgressMessages previewProgress = new ProgressMessages("Previewing", forms.size());
                int previewIndex = 0;
                boolean cancelled = false;
                for (IModelItem form : forms) {
                    if (monitor.isCanceled()) {
                        cancelled = true;
                        appendFailure(failures, "Cancelled before previewing " + form.getName() + ". Partial preview only.");
                        break;
                    }
                    previewIndex++;
                    try {
                        monitor.subTask(previewProgress.step(previewIndex, "Form", form.getName()));
                        BmcDataMigrator.Options opts = copyOptions(template);
                        opts.setFormName(form.getName());
                        previews.add(dataMigrator.preview(opts, monitor));
                    } catch (Throwable ex) {
                        appendFailure(failures, form.getName() + ": " + safeMessage(ex));
                    }
                    monitor.worked(1);
                }
                monitor.done();
                final boolean previewCancelled = cancelled;
                display.asyncExec(new Runnable() {
                    public void run() {
                        if (getShell() == null || getShell().isDisposed()) {
                            return;
                        }
                        boolean warnings = failures.length() > 0 || hasPreviewWarnings(previews) || !template.isDryRun();
                        String report = buildPreviewReport(forms, sourceStore, template, previews, failures.toString());
                        new MigrationReportDialog(getShell(), "Data Migration Preview",
                                previewCancelled ? "Data migration preview cancelled. Partial preview only." : (template.isDryRun() ? "Dry run preview is ready. No target rows will be written." : "Write-mode preview is ready. Review warnings before continuing."),
                                report, warnings || previewCancelled).open();
                        if (previewCancelled) {
                            return;
                        }
                        if (failures.length() > 0) {
                            MessageDialog.openWarning(getShell(), "Data Migration Preview", "Preview failed for one or more forms. Fix the failures before running migration.");
                            return;
                        }
                        if (template.isDryRun()) {
                            if (MessageDialog.openQuestion(getShell(), "Run Data Dry Run",
                                    "Run the dry run now?\n\nThis reads matching source entries and produces a report, but does not write target rows.")) {
                                runMigrationJob(forms, sourceStore, template);
                            }
                        } else if (MessageDialog.openQuestion(getShell(), "Write Form Data",
                                "Write mode is enabled. Target data may be changed.\n\nContinue with the data migration now?")) {
                            runMigrationJob(forms, sourceStore, template);
                        }
                    }
                });
                return cancelled ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        previewJob.setUser(true);
        previewJob.schedule();
    }

    private void runMigrationJob(final List<IModelItem> forms, final IStore sourceStore, final BmcDataMigrator.Options template) {
        Job job = new Job("Migrate form data to " + template.getTargetStore().getName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Migrating form data", forms.size() * 2);
                int read = 0;
                int migrated = 0;
                int failed = 0;
                StringBuilder failures = new StringBuilder();
                StringBuilder perForm = new StringBuilder();
                final ProgressMessages progressMessages = new ProgressMessages(template.isDryRun() ? "Dry-run" : "Migrating", forms.size());
                int formIndex = 0;
                boolean cancelled = false;
                for (IModelItem form : forms) {
                    if (monitor.isCanceled()) {
                        cancelled = true;
                        appendFailure(failures, "Cancelled before migrating " + form.getName() + ". Partial report only.");
                        break;
                    }
                    formIndex++;
                    try {
                        monitor.subTask(progressMessages.step(formIndex, "Form", form.getName()));
                        BmcDataMigrator.Options opts = copyOptions(template);
                        opts.setFormName(form.getName());
                        BmcDataMigrator.Result result = dataMigrator.migrate(opts, monitor);
                        read += result.getRead();
                        migrated += result.getMigrated();
                        failed += result.getFailed();
                        appendPerFormSummary(perForm, form.getName(), result, opts.isDryRun());
                        appendFailures(failures, form.getName(), result.getFailures());
                        if (!opts.isDryRun()) {
                            if (!monitor.isCanceled()) {
                                adapter.refreshDefinitionCacheForObject(sourceStore, form.getItemType(), form.getName(), monitor, null);
                            }
                            if (!monitor.isCanceled()) {
                                adapter.refreshDefinitionCacheForObject(template.getTargetStore(), form.getItemType(), form.getName(), monitor, null);
                            }
                            if (monitor.isCanceled()) {
                                cancelled = true;
                                appendFailure(failures, "Cancelled after writing/refreshing " + form.getName() + ". Later forms were not processed.");
                                break;
                            }
                        }
                    } catch (Throwable ex) {
                        failed++;
                        appendFailure(failures, form.getName() + ": " + safeMessage(ex));
                    }
                    monitor.worked(1);
                }
                final int readCount = read;
                final int ok = migrated;
                final int bad = failed;
                final String formText = perForm.toString();
                final String failText = failures.toString();
                final boolean dryRun = template.isDryRun();
                final boolean wasCancelled = cancelled;
                Display.getDefault().asyncExec(new Runnable() {
                    public void run() {
                        showCompleted(readCount, ok, bad, dryRun, formText, failText, wasCancelled);
                    }
                });
                monitor.done();
                return wasCancelled ? Status.CANCEL_STATUS : Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private BmcDataMigrator.Options copyOptions(BmcDataMigrator.Options source) {
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

    private List<IModelItem> toFormList(ItemList<IModelItem> selected) {
        List<IModelItem> list = new ArrayList<IModelItem>();
        for (IModelItem item : selected) {
            if (dataMigrator.isFormItem(item)) {
                list.add(item);
            }
        }
        return list;
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

    private String buildPreviewReport(List<IModelItem> forms, IStore sourceStore, BmcDataMigrator.Options template,
            List<BmcDataMigrator.Preview> previews, String failures) {
        StringBuilder report = new StringBuilder();
        report.append("Direction: Source to target\n");
        report.append("From: ").append(sourceStore == null ? "" : sourceStore.getName()).append('\n');
        report.append("To:   ").append(template.getTargetStore() == null ? "" : template.getTargetStore().getName()).append('\n');
        report.append("Mode: ").append(template.isDryRun() ? "dry run - no target writes" : "write to target").append('\n');
        report.append("Forms selected: ").append(forms == null ? 0 : forms.size()).append('\n');
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

    private void appendPerFormSummary(StringBuilder builder, String formName, BmcDataMigrator.Result result, boolean dryRun) {
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

    private void appendFailures(StringBuilder builder, String formName, String failures) {
        if (failures == null || failures.length() == 0) {
            return;
        }
        appendFailure(builder, formName + ":\n" + failures);
    }

    private void appendFailure(StringBuilder builder, String text) {
        if (builder.length() > 2500) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private void showCompleted(int read, int migrated, int failed, boolean dryRun, String perForm, String failures, boolean cancelled) {
        StringBuilder message = new StringBuilder();
        message.append("Mode: ").append(dryRun ? "dry run - no target writes" : "write to target").append('\n');
        message.append("Read entries: ").append(read).append('\n');
        message.append(dryRun ? "Dry-run matched entries: " : "Migrated entries: ").append(migrated).append('\n');
        message.append("Failed entries/forms: ").append(failed);
        if (perForm != null && perForm.length() > 0) {
            message.append("\n\nPer form:\n").append(perForm);
        }
        if (failures != null && failures.length() > 0) {
            message.append("\n\nFailures:\n").append(failures);
        }
        new MigrationReportDialog(getShell(), dryRun ? "Data Dry Run Report" : "Data Migration Report",
                cancelled ? "Data migration cancelled. Partial report is shown." : (failed > 0 ? "Data migration completed with warnings." : "Data migration completed."),
                message.toString(), cancelled || failed > 0).open();
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

    private String safeMessage(Throwable ex) {
        String message = ex == null ? null : ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? String.valueOf(ex) : message;
    }
}
