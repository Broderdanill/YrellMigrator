package se.yrell.migrator.ui.dialogs;

import java.io.File;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import se.yrell.migrator.bmc.BmcDefinitionCache;
import se.yrell.migrator.bmc.BmcDefinitionCache.CacheMaintenanceStats;
import se.yrell.migrator.bmc.BmcDefinitionCache.CacheQualityStats;
import se.yrell.migrator.bmc.BmcDefinitionCacheStorageV2;

/** Small user-facing cache maintenance dialog. */
public final class CacheMaintenanceDialog extends TitleAreaDialog {
    private final BmcDefinitionCache cache;
    private Text summaryText;
    private String lastSummary = "";
    private CacheMaintenanceStats lastStats;

    public CacheMaintenanceDialog(Shell parentShell, BmcDefinitionCache cache) {
        super(parentShell);
        this.cache = cache == null ? new BmcDefinitionCache() : cache;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Cache Maintenance");
        setMessage("Review cache health, copy diagnostics and clean orphan snapshot files.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        container.setLayout(layout);

        summaryText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.widthHint = 680;
        data.heightHint = 260;
        summaryText.setLayoutData(data);

        Composite buttons = new Composite(container, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout bl = new GridLayout(7, false);
        bl.marginWidth = 0;
        buttons.setLayout(bl);

        Button refresh = new Button(buttons, SWT.PUSH);
        refresh.setText("Refresh");
        refresh.addListener(SWT.Selection, new org.eclipse.swt.widgets.Listener() { public void handleEvent(org.eclipse.swt.widgets.Event event) { refresh(false); } });

        Button clean = new Button(buttons, SWT.PUSH);
        clean.setText("Clean orphan snapshots");
        clean.addListener(SWT.Selection, new org.eclipse.swt.widgets.Listener() { public void handleEvent(org.eclipse.swt.widgets.Event event) {
            if (MessageDialog.openQuestion(getShell(), "Clean orphan snapshots",
                    "Remove snapshot files that are no longer referenced by the cache index?")) {
                refresh(true);
            }
        } });

        Button rebuildV2 = new Button(buttons, SWT.PUSH);
        rebuildV2.setText("Rebuild storage v2");
        rebuildV2.addListener(SWT.Selection, new org.eclipse.swt.widgets.Listener() { public void handleEvent(org.eclipse.swt.widgets.Event event) { rebuildStorageV2(false); } });

        Button compactV2 = new Button(buttons, SWT.PUSH);
        compactV2.setText("Compact v2");
        compactV2.addListener(SWT.Selection, new org.eclipse.swt.widgets.Listener() { public void handleEvent(org.eclipse.swt.widgets.Event event) {
            if (MessageDialog.openQuestion(getShell(), "Compact cache storage v2",
                    "Rebuild packed cache storage v2 and remove legacy one-file-per-object snapshots that are safely represented in v2?")) {
                rebuildStorageV2(true);
            }
        } });

        Button secureClear = new Button(buttons, SWT.PUSH);
        secureClear.setText("Secure clear cache");
        secureClear.addListener(SWT.Selection, new org.eclipse.swt.widgets.Listener() { public void handleEvent(org.eclipse.swt.widgets.Event event) { secureClearCache(); } });

        Button copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy summary");
        copy.addListener(SWT.Selection, new org.eclipse.swt.widgets.Listener() { public void handleEvent(org.eclipse.swt.widgets.Event event) { copySummary(); } });

        Button open = new Button(buttons, SWT.PUSH);
        open.setText("Open cache folder");
        open.addListener(SWT.Selection, new org.eclipse.swt.widgets.Listener() { public void handleEvent(org.eclipse.swt.widgets.Event event) { openCacheFolder(); } });

        Label note = new Label(container, SWT.WRAP);
        note.setText("This does not contact any AR server. Storage v2 packs snapshots per server/type to reduce many small files. Secure clear deletes local cache files only.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        refresh(false);
        return area;
    }

    private void refresh(boolean clean) {
        CacheMaintenanceStats stats = cache.maintenance(clean);
        CacheQualityStats quality = cache.qualityStats();
        lastStats = stats;
        StringBuilder text = new StringBuilder();
        if (clean) {
            text.append("Cleanup completed.\n\n");
        }
        text.append("Health: ").append(healthLabel(stats, quality)).append("\n");
        if (stats != null && stats.permissionWarning.length() > 0) {
            text.append("Security: WARNING - ").append(stats.permissionWarning).append("\n");
        }
        if (quality != null) {
            text.append("Current snapshot schema: ").append(quality.currentSnapshotKind).append("\n\n");
            text.append("Definition cache quality:\n");
            text.append("  Full snapshots: ").append(quality.fullSnapshots).append('\n');
            text.append("  Legacy snapshots: ").append(quality.legacySnapshots).append('\n');
            text.append("  Metadata/stale entries: ").append(quality.metadataOnlyEntries()).append('\n');
            text.append("  Cache errors: ").append(quality.errors).append('\n');
            text.append("  Missing snapshots: ").append(quality.missingSnapshots).append('\n');
            text.append("  Missing fingerprints: ").append(quality.missingFingerprints).append('\n');
            text.append("  No reliable timestamp: ").append(quality.noReliableTimestamp).append("\n\n");
        }
        text.append("Storage:\n");
        text.append("  Cache entries: ").append(stats.entries).append('\n');
        text.append("  Snapshot files: ").append(stats.snapshotFiles).append('\n');
        text.append("  Orphan snapshot files: ").append(stats.orphanSnapshotFiles).append('\n');
        text.append("  Index size: ").append(formatBytes(stats.indexBytes)).append('\n');
        text.append("  Snapshot size: ").append(formatBytes(stats.snapshotBytes)).append('\n');
        if (stats.storageV2 != null) {
            text.append("\nStorage v2 packed snapshots:\n");
            text.append("  Available: ").append(stats.storageV2.available).append('\n');
            text.append("  Indexed snapshots: ").append(stats.storageV2.indexedSnapshots).append('\n');
            text.append("  Pack files: ").append(stats.storageV2.packFiles).append('\n');
            text.append("  Orphan pack files: ").append(stats.storageV2.orphanPackFiles).append('\n');
            text.append("  v2 index size: ").append(formatBytes(stats.storageV2.indexBytes)).append('\n');
            text.append("  v2 pack size: ").append(formatBytes(stats.storageV2.packBytes)).append('\n');
        }
        text.append("  Total size: ").append(formatBytes(stats.totalBytes())).append("\n\n");
        text.append("Index:\n").append(stats.indexPath).append("\n\n");
        text.append("Snapshot directory:\n").append(stats.snapshotDirectory).append("\n\n");
        if (stats.storageV2 != null) {
            text.append("Storage v2 directory:\n").append(stats.storageV2.rootPath).append('\n');
        }
        lastSummary = text.toString();
        if (summaryText != null && !summaryText.isDisposed()) {
            summaryText.setText(lastSummary);
        }
    }

    private String healthLabel(CacheMaintenanceStats stats, CacheQualityStats quality) {
        if (quality != null && quality.errors > 0) {
            return "ERROR - " + quality.errors + " cache error(s). Run Sync/Rebuild for affected objects.";
        }
        if (quality != null && quality.metadataOnlyEntries() > 0) {
            return "WARNING - " + quality.metadataOnlyEntries() + " metadata-only/stale entry/entries. Full diff may require Sync/Rebuild.";
        }
        if (stats != null && stats.orphanSnapshotFiles > 0) {
            return "WARNING - " + stats.orphanSnapshotFiles + " orphan snapshot file(s) can be cleaned.";
        }
        if (stats != null && stats.storageV2 != null && stats.storageV2.orphanPackFiles > 0) {
            return "WARNING - " + stats.storageV2.orphanPackFiles + " orphan v2 pack file(s) can be cleaned.";
        }
        return "OK - cache looks healthy.";
    }


    private void secureClearCache() {
        if (!MessageDialog.openConfirm(getShell(), "Secure clear local cache",
                "Delete the local Yrell Migrator definition cache, legacy snapshots and packed storage v2 files?\n\n"
                + "This does not contact any AR server. You will need to run Sync again before cached comparisons are available.")) {
            return;
        }
        int removed = cache.secureClearLocalCache();
        refresh(false);
        MessageDialog.openInformation(getShell(), "Cache cleared", "Local cache files removed: " + removed);
    }

    private void rebuildStorageV2(boolean compact) {
        BmcDefinitionCacheStorageV2.RebuildResult result = compact ? cache.compactStorageV2AndRemoveLegacySnapshots() : cache.rebuildStorageV2();
        refresh(false);
        String message = "Storage v2 rebuild completed.\n\n"
                + "Snapshots written: " + result.writtenSnapshots + "\n"
                + "Entries skipped: " + result.skippedEntries + "\n"
                + "Pack files: " + result.packFiles + "\n"
                + "Elapsed: " + result.elapsedMillis + " ms\n"
                + result.message;
        lastSummary = message + "\n\n" + (lastSummary == null ? "" : lastSummary);
        if (summaryText != null && !summaryText.isDisposed()) {
            summaryText.setText(lastSummary);
        }
    }

    private void copySummary() {
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] { lastSummary == null ? "" : lastSummary }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private void openCacheFolder() {
        String path = null;
        if (lastStats != null && lastStats.snapshotDirectory.length() > 0) {
            path = lastStats.snapshotDirectory;
        } else if (lastStats != null && lastStats.indexPath.length() > 0) {
            File index = new File(lastStats.indexPath);
            File parent = index.getParentFile();
            path = parent == null ? null : parent.getAbsolutePath();
        }
        if ((path == null || path.length() == 0) && lastStats != null && lastStats.storageV2 != null && lastStats.storageV2.rootPath.length() > 0) {
            path = lastStats.storageV2.rootPath;
        }
        if (path == null || path.length() == 0) {
            MessageDialog.openInformation(getShell(), "Open cache folder", "No cache folder is available yet.");
            return;
        }
        File folder = new File(path);
        if (!folder.isDirectory()) {
            folder.mkdirs();
        }
        if (!Program.launch(folder.getAbsolutePath())) {
            MessageDialog.openInformation(getShell(), "Open cache folder", folder.getAbsolutePath());
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(java.util.Locale.ENGLISH, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(java.util.Locale.ENGLISH, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.ENGLISH, "%.1f GB", mb / 1024.0);
    }
}
