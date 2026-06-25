package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import se.yrell.migrator.bmc.BmcCatalogDataMigrator;
import se.yrell.migrator.bmc.BmcContainerContentMigrator;
import se.yrell.migrator.bmc.BmcModelAdapter;
import se.yrell.migrator.bmc.BmcSupportFileMigrator;
import se.yrell.migrator.bmc.BmcWorkflowMigrator;
import se.yrell.migrator.ui.util.ProgressMessages;

/**
 * Shared object migration execution pipeline used by both the Yrell Migrator
 * Differences view and the Developer Studio context-menu action.
 *
 * The UI entry points still choose the selected rows, target environment and
 * report scope, but save, container-content migration, dependency retry,
 * cancellation handling and post-migration verification live here so behavior
 * stays consistent regardless of where migration starts.
 */
public final class ObjectMigrationExecutor {
    private final BmcModelAdapter modelAdapter;
    private final BmcWorkflowMigrator workflowMigrator;
    private final BmcCatalogDataMigrator catalogDataMigrator;
    private final BmcSupportFileMigrator supportFileMigrator;
    private final BmcContainerContentMigrator containerContentMigrator;

    public ObjectMigrationExecutor(BmcModelAdapter modelAdapter, BmcWorkflowMigrator workflowMigrator,
            BmcCatalogDataMigrator catalogDataMigrator, BmcSupportFileMigrator supportFileMigrator,
            BmcContainerContentMigrator containerContentMigrator) {
        this.modelAdapter = modelAdapter;
        this.workflowMigrator = workflowMigrator;
        this.catalogDataMigrator = catalogDataMigrator;
        this.supportFileMigrator = supportFileMigrator;
        this.containerContentMigrator = containerContentMigrator;
    }

    public Execution execute(MigrationPlan plan, boolean includeContainerContent, IProgressMonitor monitor,
            Listener listener) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        Listener safeListener = listener == null ? Listener.NOOP : listener;
        List<CompareResult> candidates = plan == null ? Collections.<CompareResult>emptyList() : plan.getOrderedRows();
        MigrationDirection direction = plan == null ? MigrationDirection.SOURCE_TO_TARGET : plan.getDirection();
        Map<CompareResult, CompareResult> refreshed = new IdentityHashMap<CompareResult, CompareResult>();
        List<MigrationResult> results = new ArrayList<MigrationResult>();
        List<CompareResult> deferredDependencyRetries = new ArrayList<CompareResult>();
        boolean cancelled = false;

        safeMonitor.beginTask("Migrating objects", Math.max(1, candidates.size() * 4));
        if (modelAdapter != null) {
            modelAdapter.clearCache();
        }

        ProgressMessages progressMessages = new ProgressMessages("Migrating", candidates.size());
        int migrationIndex = 0;
        for (CompareResult candidate : candidates) {
            if (safeMonitor.isCanceled()) {
                cancelled = true;
                ProgressMessages.markCancelRequested(safeMonitor, "the current object boundary");
                results.add(MigrationResult.cancelled(candidate,
                        "Cancel requested before this object was migrated. The report is partial and includes completed objects above."));
                break;
            }
            migrationIndex++;
            String progressMessage = progressMessages.step(migrationIndex, candidate.getObjectType(), candidate.getObjectName());
            safeMonitor.subTask(progressMessage);
            safeListener.onProgress(progressMessage);

            boolean supportFileMigration = supportFileMigrator != null && supportFileMigrator.canMigrate(candidate, direction);
            boolean catalogMigration = !supportFileMigration && catalogDataMigrator != null && catalogDataMigrator.canMigrate(candidate, direction);
            MigrationResult migrationResult = supportFileMigration
                    ? supportFileMigrator.migrate(candidate, direction, safeMonitor)
                    : (catalogMigration
                            ? catalogDataMigrator.migrate(candidate, direction, safeMonitor)
                            : workflowMigrator.migrate(candidate, direction, safeMonitor));
            safeMonitor.worked(1);

            if (safeMonitor.isCanceled()) {
                cancelled = true;
                results.add(MigrationResult.warning(candidate,
                        "Cancel requested after save attempt. Post-migration verification was skipped at the next safe point."));
                break;
            }

            if (catalogMigration && migrationResult != null && !migrationResult.isSuccess()
                    && catalogDataMigrator.shouldRetryAfterDependencies(migrationResult)) {
                deferredDependencyRetries.add(candidate);
                safeListener.onInfo("Deferring " + candidate.getObjectType() + " " + candidate.getObjectName()
                        + " for a second pass because it appears to depend on another Group.");
                safeMonitor.worked(3);
                continue;
            }

            if (migrationResult != null && migrationResult.isSuccess() && isOverlayMigration(migrationResult)) {
                pauseAfterOverlayMigration(safeMonitor);
            }

            if (safeMonitor.isCanceled()) {
                cancelled = true;
                results.add(MigrationResult.reclassified(migrationResult, candidate, MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Cancel requested after object save. Container-content migration and verification were skipped.").getDetail()));
                break;
            }

            if (migrationResult != null && migrationResult.isSuccess() && includeContainerContent
                    && containerContentMigrator != null && containerContentMigrator.isContainerType(candidate)) {
                MigrationResult contentResult = containerContentMigrator.migrateContent(candidate, direction, safeMonitor);
                results.add(contentResult);
                safeMonitor.worked(1);
            }

            if (safeMonitor.isCanceled()) {
                cancelled = true;
                results.add(MigrationResult.reclassified(migrationResult, candidate, MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Cancel requested after migration. Post-migration verification was skipped.").getDetail()));
                break;
            }

            results.add(verifyMigrationResult(candidate, migrationResult, refreshed, safeMonitor));
            safeMonitor.worked(1);
        }

        if (cancelled) {
            safeListener.onInfo(ProgressMessages.cancelRequested("the current object"));
        }

        if (!cancelled && !deferredDependencyRetries.isEmpty() && catalogDataMigrator != null) {
            cancelled = retryDeferredCatalogDependencies(deferredDependencyRetries, direction, refreshed, results, safeMonitor, safeListener);
        }
        safeMonitor.done();
        return new Execution(results, refreshed, cancelled);
    }

    private boolean retryDeferredCatalogDependencies(List<CompareResult> deferredDependencyRetries,
            MigrationDirection direction, Map<CompareResult, CompareResult> refreshed, List<MigrationResult> results,
            IProgressMonitor monitor, Listener listener) {
        List<CompareResult> pendingDependencyRetries = new ArrayList<CompareResult>(deferredDependencyRetries);
        Map<CompareResult, String> lastDependencyErrors = new IdentityHashMap<CompareResult, String>();
        int maxDependencyPasses = Math.max(2, pendingDependencyRetries.size() + 1);
        int dependencyPass = 0;
        boolean cancelled = false;
        while (!pendingDependencyRetries.isEmpty() && dependencyPass < maxDependencyPasses && !monitor.isCanceled()) {
            dependencyPass++;
            listener.onInfo("Retrying deferred Group dependencies, pass " + dependencyPass + " ("
                    + pendingDependencyRetries.size() + " row(s)).");
            List<CompareResult> stillPending = new ArrayList<CompareResult>();
            int successfulInPass = 0;
            int retryIndex = 0;
            ProgressMessages retryProgressMessages = new ProgressMessages("Retrying dependency pass " + dependencyPass,
                    pendingDependencyRetries.size());
            for (CompareResult candidate : pendingDependencyRetries) {
                if (monitor.isCanceled()) {
                    cancelled = true;
                    ProgressMessages.markCancelRequested(monitor, "the current retry object");
                    results.add(MigrationResult.cancelled(candidate,
                            "Cancel requested before this deferred dependency retry was migrated."));
                    break;
                }
                retryIndex++;
                String progressMessage = retryProgressMessages.step(retryIndex, candidate.getObjectType(), candidate.getObjectName());
                monitor.subTask(progressMessage);
                listener.onProgress(progressMessage);
                MigrationResult retryResult = catalogDataMigrator.migrate(candidate, direction, monitor);
                monitor.worked(1);
                if (monitor.isCanceled()) {
                    cancelled = true;
                    results.add(MigrationResult.warning(candidate,
                            "Cancel requested after deferred dependency retry save attempt. Verification was skipped."));
                    break;
                }
                if (retryResult.isSuccess()) {
                    successfulInPass++;
                    results.add(verifyMigrationResult(candidate, retryResult, refreshed, monitor));
                } else if (catalogDataMigrator.shouldRetryAfterDependencies(retryResult)) {
                    lastDependencyErrors.put(candidate, retryResult.getDetail());
                    stillPending.add(candidate);
                    monitor.worked(2);
                } else {
                    results.add(retryResult);
                    monitor.worked(2);
                }
                monitor.worked(1);
            }
            if (cancelled || stillPending.isEmpty()) {
                break;
            }
            if (successfulInPass == 0) {
                pendingDependencyRetries = stillPending;
                break;
            }
            pendingDependencyRetries = stillPending;
        }
        if (!cancelled) {
            for (CompareResult candidate : pendingDependencyRetries) {
                String lastError = lastDependencyErrors.get(candidate);
                results.add(MigrationResult.failure(candidate,
                        "Retried deferred Group dependency migration in multiple passes, but the dependency is still not satisfied. "
                        + "This computed group may reference one or more Group ID(s) or another computed group that was not selected, "
                        + "or one of the referenced groups failed to migrate. Select and migrate all referenced Group ID(s) first, then retry."
                        + (lastError == null || lastError.length() == 0 ? "" : " Last error: " + lastError)));
            }
        }
        return cancelled;
    }

    private MigrationResult verifyMigrationResult(CompareResult candidate, MigrationResult migrationResult,
            Map<CompareResult, CompareResult> refreshed, IProgressMonitor monitor) {
        if (migrationResult == null || !migrationResult.isSuccess()) {
            monitor.worked(2);
            return migrationResult == null ? MigrationResult.failure(candidate, "Migration did not return a result.") : migrationResult;
        }
        try {
            if (monitor.isCanceled()) {
                return MigrationResult.reclassified(migrationResult, candidate, MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Post-migration verification skipped because cancel was requested.").getDetail());
            }
            modelAdapter.refreshDefinitionCacheForObject(candidate.getSourceStore(), candidate.getModelType(), candidate.getObjectName(), monitor, null);
            monitor.worked(1);
            if (monitor.isCanceled()) {
                return MigrationResult.reclassified(migrationResult, candidate, MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Post-migration verification stopped after source refresh because cancel was requested.").getDetail());
            }
            modelAdapter.refreshDefinitionCacheForObject(candidate.getTargetStore(), candidate.getModelType(), candidate.getObjectName(), monitor, null);
            monitor.worked(1);
            if (monitor.isCanceled()) {
                return MigrationResult.reclassified(migrationResult, candidate, MigrationOutcome.WARNING,
                        MigrationResult.appendDetail(migrationResult,
                                "Post-migration compare skipped because cancel was requested.").getDetail());
            }
            CompareResult refreshedResult = modelAdapter.refreshCompareWithTimeout(candidate.getSourceStore(), candidate.getTargetStore(),
                    candidate.getModelType(), candidate.getObjectName(), monitor, null);
            refreshed.put(candidate, refreshedResult);
            return MigrationVerifier.classify(migrationResult, refreshedResult);
        } catch (Throwable refreshError) {
            monitor.worked(2);
            String message = refreshError.getLocalizedMessage() == null ? refreshError.getClass().getName() : refreshError.getLocalizedMessage();
            return MigrationResult.reclassified(migrationResult, candidate, MigrationOutcome.WARNING,
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

    public static final class Execution {
        private final List<MigrationResult> results;
        private final Map<CompareResult, CompareResult> refreshed;
        private final boolean cancelled;

        private Execution(List<MigrationResult> results, Map<CompareResult, CompareResult> refreshed, boolean cancelled) {
            this.results = results == null ? Collections.<MigrationResult>emptyList() : results;
            this.refreshed = refreshed == null ? Collections.<CompareResult, CompareResult>emptyMap() : refreshed;
            this.cancelled = cancelled;
        }

        public List<MigrationResult> getResults() {
            return results;
        }

        public Map<CompareResult, CompareResult> getRefreshed() {
            return refreshed;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    public interface Listener {
        Listener NOOP = new Listener() {
            public void onProgress(String message) {
            }
            public void onInfo(String message) {
            }
        };

        void onProgress(String message);
        void onInfo(String message);
    }
}
