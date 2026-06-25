package se.yrell.migrator.bmc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.Activator;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.bmc.BmcMetadataCache.CacheEntry;
import se.yrell.migrator.bmc.BmcMetadataCache.SearchPattern;

/**
 * Persistent definition snapshot cache.
 *
 * The metadata cache is used to find object names quickly. This cache is one step deeper: it stores
 * a stable fingerprint and a canonical text snapshot of the actual AR definition after the object
 * has been opened by Developer Studio. The snapshot is deliberately plain text and not serialized
 * Java model objects, so it stays version-tolerant and safe to keep between Developer Studio runs.
 */
public final class BmcDefinitionCache {
    /** Increment only when the canonical snapshot format changes enough that old cache rows cannot show useful details. */
    public static final int CURRENT_SNAPSHOT_SCHEMA_VERSION = 3;
    public static final String CURRENT_SNAPSHOT_KIND = "semantic-snapshot-schema-" + CURRENT_SNAPSHOT_SCHEMA_VERSION;

    private final Map<String, DefinitionEntry> entries = new LinkedHashMap<String, DefinitionEntry>();
    private final Map<String, List<DefinitionEntry>> entriesByServerType = new LinkedHashMap<String, List<DefinitionEntry>>();
    private boolean loaded;
    private long lastLoadMillis;
    private int lastLoadEntries;
    private String lastLoadMode = "not loaded";
    private BmcDefinitionCacheStorageV2 storageV2;

    public synchronized List<DefinitionEntry> find(IStore store, List<IModelType> types, String query) {
        loadIfNeeded();
        if (store == null || types == null) {
            return Collections.emptyList();
        }
        String server = store.getName();
        SearchPattern pattern = SearchPattern.compile(query);
        List<DefinitionEntry> result = new ArrayList<DefinitionEntry>();
        HashSet<String> seen = new HashSet<String>();
        for (IModelType type : types) {
            if (type == null) {
                continue;
            }
            List<DefinitionEntry> bucket = entriesByServerType.get(serverTypeKey(server, type.getTypeName()));
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (DefinitionEntry entry : bucket) {
                if (entry != null && seen.add(entry.key()) && pattern.matchesEntry(entry.name, entry.type, entry.changedBy)) {
                    result.add(entry);
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    public synchronized DefinitionEntry get(String server, String type, String name) {
        loadIfNeeded();
        return entries.get(key(server, type, name));
    }

    public synchronized boolean needsRefresh(CacheEntry metadata) {
        return getRefreshReason(metadata).shouldRefresh();
    }

    public synchronized RefreshReason getRefreshReason(CacheEntry metadata) {
        loadIfNeeded();
        if (metadata == null) {
            return RefreshReason.reuse("no metadata");
        }
        CompareSettings settings = CompareSettings.load();
        if (settings.isSyncForceRefreshDefinitions()) {
            return RefreshReason.refresh("forced rebuild enabled");
        }
        DefinitionEntry existing = entries.get(metadata.key());
        if (existing == null) {
            return RefreshReason.refresh("not cached");
        }
        if (existing.error.length() > 0) {
            if (settings.isSyncRetryErrorSnapshots()) {
                return RefreshReason.refresh("previous error; retry enabled");
            }
            return RefreshReason.reuse("previous error; retry disabled");
        }
        if (existing.fingerprint.length() == 0) {
            return RefreshReason.refresh("missing fingerprint");
        }
        if (!hasStoredSnapshot(existing)) {
            return RefreshReason.refresh("missing property snapshot");
        }
        if (!isCompatibleSnapshotKind(existing.sourceKind)) {
            return RefreshReason.refresh("old snapshot format " + existing.sourceKind);
        }
        if (!settings.isSyncIncrementalDefinitions()) {
            return RefreshReason.refresh("incremental definition sync disabled");
        }
        if (metadata.modified != Long.MIN_VALUE) {
            if (existing.modified == Long.MIN_VALUE) {
                // Earlier syncs, or fallback name-only enumeration, may have cached a valid snapshot
                // without a reliable last-update value. Do not throw that snapshot away just because
                // the next metadata pass managed to read a timestamp; record the better metadata and
                // use it as the baseline for future incremental syncs.
                return RefreshReason.reuse("last update now known; cached snapshot kept");
            }
            if (existing.modified != metadata.modified) {
                return RefreshReason.refresh("last update changed");
            }
            // Do not deep-refresh solely because Changed By differs. In some Developer Studio/BMC
            // builds the lightweight item enumeration can return unstable or normalized user values,
            // which made every Sync look like it needed a deep-cache rebuild even when the object
            // timestamp had not changed. The timestamp is the authoritative incremental signal.
            return RefreshReason.reuse("last update unchanged");
        }
        if (existing.modified != Long.MIN_VALUE) {
            // Current enumeration could not read a timestamp even though the cached row has one.
            // Treat that as missing metadata rather than a definition change; otherwise the sync may
            // alternate between deep-caching all rows and reusing them depending on which BMC
            // enumeration strategy happened to succeed.
            return RefreshReason.reuse("last update unavailable this run; cached snapshot kept");
        }
        if (metadata.hasReliableMetadata()) {
            // Only Changed By is available. That is useful display metadata, but not reliable enough
            // to force a full object hydration. Keep the existing snapshot and use Force rebuild when
            // a one-off reread is needed.
            return RefreshReason.reuse("no reliable last update; cached snapshot kept");
        }
        // We cannot reliably detect incremental changes without server metadata. Keep the existing
        // snapshot to avoid turning every broad sync into a full deep read. Use Force rebuild if a
        // name-only object must be re-read from the server.
        return RefreshReason.reuse("no reliable metadata; cached snapshot kept");
    }

    private boolean isCompatibleSnapshotKind(String kind) {
        if (kind == null || kind.length() == 0) {
            return false;
        }
        if (CURRENT_SNAPSHOT_KIND.equals(kind)) {
            return true;
        }
        if (kind.startsWith("semantic-snapshot-schema-")) {
            return CURRENT_SNAPSHOT_KIND.equals(kind);
        }
        // v0.9.5+ uses the external gzip semantic snapshot model. Treat all compatible v0.*
        // snapshots up to v0.45 as schema 3. From v0.46 onward the cache compatibility decision is
        // intentionally tied to CURRENT_SNAPSHOT_SCHEMA_VERSION instead of the plugin version, so a
        // maintenance release does not force unnecessary deep-cache rebuilds.
        if (kind.startsWith("semantic-snapshot-v1.")) {
            return true;
        }
        if (!kind.startsWith("semantic-snapshot-v0.")) {
            return false;
        }
        int minor = parseLegacySnapshotMinor(kind);
        return minor >= 9 && minor <= 45;
    }

    private static int parseLegacySnapshotMinor(String kind) {
        if (kind == null) {
            return -1;
        }
        String prefix = "semantic-snapshot-v0.";
        if (!kind.startsWith(prefix)) {
            return -1;
        }
        int start = prefix.length();
        int end = start;
        while (end < kind.length() && Character.isDigit(kind.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(kind.substring(start, end));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public synchronized void putSnapshot(CacheEntry metadata, String fingerprint, String snapshot, String sourceKind) {
        loadIfNeeded();
        if (metadata == null) {
            return;
        }
        DefinitionEntry entry = new DefinitionEntry(metadata.server, metadata.type, metadata.name, metadata.modified,
                metadata.changedBy, metadata.customizationType, metadata.contextKey, fingerprint, System.currentTimeMillis(), sourceKind, "", "");
        writeSnapshot(entry, snapshot);
        putEntry(entry);
    }


    public synchronized void updateMetadataOnly(CacheEntry metadata) {
        loadIfNeeded();
        if (metadata == null) {
            return;
        }
        DefinitionEntry existing = entries.get(metadata.key());
        if (existing == null) {
            return;
        }
        DefinitionEntry updated = new DefinitionEntry(metadata.server, metadata.type, metadata.name, metadata.modified,
                metadata.changedBy, metadata.customizationType.length() == 0 ? existing.customizationType : metadata.customizationType, metadata.contextKey.length() == 0 ? existing.contextKey : metadata.contextKey, existing.fingerprint, existing.cachedAt, existing.sourceKind, existing.error, existing.snapshot);
        putEntry(updated);
    }

    public synchronized void putError(CacheEntry metadata, String error) {
        loadIfNeeded();
        if (metadata == null) {
            return;
        }
        DefinitionEntry entry = new DefinitionEntry(metadata.server, metadata.type, metadata.name, metadata.modified,
                metadata.changedBy, metadata.customizationType, metadata.contextKey, "", System.currentTimeMillis(), "error", error == null ? "" : error, "");
        deleteSnapshot(entry);
        putEntry(entry);
    }

    /** Removes one definition-cache row and its external snapshot. */
    public synchronized boolean remove(String server, String typeName, String name) {
        loadIfNeeded();
        DefinitionEntry removed = removeEntry(key(server, typeName, name));
        if (removed != null) {
            deleteSnapshot(removed);
            save();
            return true;
        }
        return false;
    }

    public synchronized int removeMissing(String server, String typeName, Collection<String> currentNames) {
        loadIfNeeded();
        List<String> remove = new ArrayList<String>();
        for (DefinitionEntry entry : entries.values()) {
            if (entry.server.equalsIgnoreCase(server) && entry.type.equalsIgnoreCase(typeName)
                    && !containsIgnoreCase(currentNames, entry.name)) {
                remove.add(entry.key());
            }
        }
        for (String key : remove) {
            DefinitionEntry removed = removeEntry(key);
            deleteSnapshot(removed);
        }
        return remove.size();
    }



    /** Returns a support-friendly quality breakdown of the local definition snapshot cache. */
    public synchronized CacheQualityStats qualityStats() {
        loadIfNeeded();
        int errors = 0;
        int fullSnapshots = 0;
        int legacySnapshots = 0;
        int missingSnapshots = 0;
        int missingFingerprints = 0;
        int noReliableTimestamp = 0;
        int staleSchema = 0;
        for (DefinitionEntry entry : entries.values()) {
            if (entry == null) {
                continue;
            }
            if (entry.error.length() > 0) {
                errors++;
                continue;
            }
            if (entry.modified == Long.MIN_VALUE) {
                noReliableTimestamp++;
            }
            if (entry.fingerprint.length() == 0) {
                missingFingerprints++;
            }
            boolean hasSnapshot = hasStoredSnapshot(entry);
            if (!hasSnapshot) {
                missingSnapshots++;
            } else if (isCompatibleSnapshotKind(entry.sourceKind)) {
                fullSnapshots++;
            } else {
                staleSchema++;
            }
            if (entry.sourceKind.startsWith("semantic-snapshot-v0.") || entry.sourceKind.startsWith("semantic-snapshot-v1.")) {
                legacySnapshots++;
            }
        }
        return new CacheQualityStats(entries.size(), fullSnapshots, legacySnapshots, missingSnapshots,
                missingFingerprints, errors, noReliableTimestamp, staleSchema, CURRENT_SNAPSHOT_KIND);
    }

    public static final class CacheQualityStats {
        public final int entries;
        public final int fullSnapshots;
        public final int legacySnapshots;
        public final int missingSnapshots;
        public final int missingFingerprints;
        public final int errors;
        public final int noReliableTimestamp;
        public final int staleSchema;
        public final String currentSnapshotKind;

        CacheQualityStats(int entries, int fullSnapshots, int legacySnapshots, int missingSnapshots,
                int missingFingerprints, int errors, int noReliableTimestamp, int staleSchema, String currentSnapshotKind) {
            this.entries = entries;
            this.fullSnapshots = fullSnapshots;
            this.legacySnapshots = legacySnapshots;
            this.missingSnapshots = missingSnapshots;
            this.missingFingerprints = missingFingerprints;
            this.errors = errors;
            this.noReliableTimestamp = noReliableTimestamp;
            this.staleSchema = staleSchema;
            this.currentSnapshotKind = currentSnapshotKind == null ? "" : currentSnapshotKind;
        }

        public int metadataOnlyEntries() {
            int value = missingFingerprints + missingSnapshots + staleSchema;
            return value < 0 ? 0 : value;
        }
    }

    /** Returns cache size details and optionally removes orphan snapshot files. */
    public synchronized CacheMaintenanceStats maintenance(boolean cleanOrphans) {
        loadIfNeeded();
        int snapshotFiles = 0;
        int orphanFiles = 0;
        long snapshotBytes = 0L;
        long indexBytes = 0L;
        File index = getCacheFile();
        if (index != null && index.isFile()) {
            indexBytes = index.length();
        }
        java.util.HashSet<String> expected = new java.util.HashSet<String>();
        for (DefinitionEntry entry : entries.values()) {
            File file = getSnapshotFile(entry);
            if (file != null) {
                expected.add(file.getName());
            }
        }
        File dir = index == null ? null : new File(index.getParentFile(), "definition-cache-snapshots");
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File file = files[i];
                    if (file == null || !file.isFile()) {
                        continue;
                    }
                    snapshotFiles++;
                    snapshotBytes += file.length();
                    if (!expected.contains(file.getName())) {
                        orphanFiles++;
                        if (cleanOrphans) {
                            try {
                                if (file.delete()) {
                                    snapshotFiles--;
                                    snapshotBytes -= file.length();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }
        BmcDefinitionCacheStorageV2.StorageStats v2 = storageV2().stats(cleanOrphans);
        File cacheRoot = index == null ? null : index.getParentFile();
        String permissionWarning = describeCachePermissionWarning(cacheRoot);
        return new CacheMaintenanceStats(entries.size(), snapshotFiles, orphanFiles, indexBytes, snapshotBytes,
                index == null ? "" : index.getAbsolutePath(), dir == null ? "" : dir.getAbsolutePath(), v2, permissionWarning);
    }

    /** Rebuilds packed cache storage v2 from all currently available snapshots. Legacy snapshot files are kept. */
    public synchronized BmcDefinitionCacheStorageV2.RebuildResult rebuildStorageV2() {
        loadIfNeeded();
        BmcDefinitionCacheStorageV2.RebuildResult result = storageV2().rebuild(entries.values(), new BmcDefinitionCacheStorageV2.SnapshotReader() {
            public String readSnapshot(DefinitionEntry entry) {
                return readSnapshotLegacyOnly(entry);
            }
        }, false);
        storageV2().saveIndexIfDirty();
        return result;
    }

    /** Rebuilds packed cache storage v2 and removes legacy one-file-per-object snapshots that are safely represented in v2. */
    public synchronized BmcDefinitionCacheStorageV2.RebuildResult compactStorageV2AndRemoveLegacySnapshots() {
        loadIfNeeded();
        BmcDefinitionCacheStorageV2.RebuildResult result = rebuildStorageV2();
        int removed = 0;
        for (DefinitionEntry entry : entries.values()) {
            if (entry != null && storageV2().hasSnapshot(entry)) {
                File legacy = getSnapshotFile(entry);
                if (legacy != null && legacy.isFile()) {
                    try {
                        if (legacy.delete()) {
                            removed++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return new BmcDefinitionCacheStorageV2.RebuildResult(result.writtenSnapshots, result.skippedEntries, result.packFiles,
                result.elapsedMillis, result.message + "; removed legacy snapshot files: " + removed);
    }


    /** Deletes local definition cache files. This does not contact any AR server. */
    public synchronized int secureClearLocalCache() {
        int removed = 0;
        File cache = getCacheFile();
        File parent = cache == null ? null : cache.getParentFile();
        if (cache != null && cache.isFile() && safeDelete(cache)) {
            removed++;
        }
        File startup = getStartupIndexFile();
        if (startup != null && startup.isFile() && safeDelete(startup)) {
            removed++;
        }
        if (parent != null) {
            removed += deleteDirectoryContents(new File(parent, "definition-cache-snapshots"), true);
            removed += deleteDirectoryContents(new File(parent, "definition-cache-v2"), true);
            removed += deleteDirectoryContents(new File(parent, "definition-cache-v2.old"), true);
            removed += deleteDirectoryContents(new File(parent, "definition-cache-v2.rebuild.tmp"), true);
        }
        entries.clear();
        entriesByServerType.clear();
        loaded = false;
        lastLoadMillis = 0L;
        lastLoadEntries = 0;
        lastLoadMode = "cleared";
        storageV2 = null;
        return removed;
    }

    public static final class CacheMaintenanceStats {
        public final int entries;
        public final int snapshotFiles;
        public final int orphanSnapshotFiles;
        public final long indexBytes;
        public final long snapshotBytes;
        public final String indexPath;
        public final String snapshotDirectory;
        public final BmcDefinitionCacheStorageV2.StorageStats storageV2;
        public final String permissionWarning;

        CacheMaintenanceStats(int entries, int snapshotFiles, int orphanSnapshotFiles, long indexBytes,
                long snapshotBytes, String indexPath, String snapshotDirectory, BmcDefinitionCacheStorageV2.StorageStats storageV2, String permissionWarning) {
            this.entries = entries;
            this.snapshotFiles = snapshotFiles;
            this.orphanSnapshotFiles = orphanSnapshotFiles;
            this.indexBytes = indexBytes;
            this.snapshotBytes = snapshotBytes;
            this.indexPath = indexPath == null ? "" : indexPath;
            this.snapshotDirectory = snapshotDirectory == null ? "" : snapshotDirectory;
            this.storageV2 = storageV2;
            this.permissionWarning = permissionWarning == null ? "" : permissionWarning;
        }

        public long totalBytes() {
            return indexBytes + snapshotBytes + (storageV2 == null ? 0L : storageV2.totalBytes());
        }
    }


    /**
     * Reads a tiny startup manifest without loading the full definition-cache TSV.
     * Used by the UI to open quickly in large environments and by diagnostics to show
     * whether a fast cache index is available. If the manifest is missing/corrupt the
     * caller can still fall back to the full cache scan by using find()/maintenance().
     */
    public synchronized CacheStartupStats startupStatsFast() {
        File cacheFile = getCacheFile();
        File index = getStartupIndexFile();
        if (index == null || !index.isFile()) {
            return new CacheStartupStats(false, false, 0, 0, 0, Collections.<CacheStartupBucket>emptyList(), 0L, 0L, 0L,
                    cacheFile == null ? "" : cacheFile.getAbsolutePath(), index == null ? "" : index.getAbsolutePath(),
                    "startup index missing; full cache scan is available on demand");
        }
        Properties props = new Properties();
        FileInputStream in = null;
        long started = System.currentTimeMillis();
        try {
            in = new FileInputStream(index);
            props.load(in);
            int entries = parseInt(props.getProperty("entries"));
            int servers = parseInt(props.getProperty("servers"));
            int types = parseInt(props.getProperty("serverTypes"));
            List<CacheStartupBucket> buckets = readStartupBuckets(props);
            long generated = parseLong(props.getProperty("generatedAt"));
            long cacheModified = parseLong(props.getProperty("cacheModified"));
            long currentCacheModified = cacheFile != null && cacheFile.isFile() ? cacheFile.lastModified() : 0L;
            boolean current = currentCacheModified == 0L || cacheModified == currentCacheModified;
            long elapsed = System.currentTimeMillis() - started;
            return new CacheStartupStats(true, current, entries, servers, types, buckets, generated, cacheModified, elapsed,
                    cacheFile == null ? "" : cacheFile.getAbsolutePath(), index.getAbsolutePath(),
                    current ? "startup index current" : "startup index does not match cache timestamp; full cache scan will rebuild it");
        } catch (Throwable ex) {
            return new CacheStartupStats(true, false, 0, 0, 0, Collections.<CacheStartupBucket>emptyList(), 0L, 0L, System.currentTimeMillis() - started,
                    cacheFile == null ? "" : cacheFile.getAbsolutePath(), index.getAbsolutePath(),
                    "startup index read failed: " + safeMessage(ex));
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static List<CacheStartupBucket> readStartupBuckets(Properties props) {
        if (props == null) {
            return Collections.emptyList();
        }
        int count = parseInt(props.getProperty("bucketCount"));
        if (count <= 0) {
            return Collections.emptyList();
        }
        List<CacheStartupBucket> buckets = new ArrayList<CacheStartupBucket>();
        for (int i = 0; i < count; i++) {
            String prefix = "bucket." + i + ".";
            String server = props.getProperty(prefix + "server", "");
            String type = props.getProperty(prefix + "type", "");
            int entries = parseInt(props.getProperty(prefix + "entries"));
            if (server.length() == 0 && type.length() == 0 && entries == 0) {
                continue;
            }
            buckets.add(new CacheStartupBucket(server, type, entries,
                    parseInt(props.getProperty(prefix + "base")),
                    parseInt(props.getProperty(prefix + "custom")),
                    parseInt(props.getProperty(prefix + "overlay")),
                    parseInt(props.getProperty(prefix + "unknown")),
                    parseInt(props.getProperty(prefix + "errors"))));
        }
        Collections.sort(buckets);
        return Collections.unmodifiableList(buckets);
    }

    /** Returns the last full TSV load metrics for support/performance diagnostics. */
    public synchronized CacheLoadStats lastLoadStats() {
        return new CacheLoadStats(lastLoadEntries, lastLoadMillis, lastLoadMode);
    }

    public synchronized void save() {
        File file = getCacheFile();
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
            writer.write("# Yrell Migrator definition snapshot cache. Safe to delete.\n");
            for (DefinitionEntry entry : entries.values()) {
                writer.write(escape(entry.server)); writer.write('\t');
                writer.write(escape(entry.type)); writer.write('\t');
                writer.write(escape(entry.name)); writer.write('\t');
                writer.write(String.valueOf(entry.modified)); writer.write('\t');
                writer.write(escape(entry.changedBy)); writer.write('\t');
                writer.write(escape(entry.customizationType)); writer.write('\t');
                writer.write(escape(entry.contextKey)); writer.write('\t');
                writer.write(escape(entry.fingerprint)); writer.write('\t');
                writer.write(String.valueOf(entry.cachedAt)); writer.write('\t');
                writer.write(escape(entry.sourceKind)); writer.write('\t');
                writer.write(escape(entry.error)); writer.write('\t');
                // Snapshots are stored externally in gzip files to keep Developer Studio heap usage low.
                writer.write(""); writer.write('\n');
            }
        } catch (Exception ex) {
            Activator.logWarning("Could not write Yrell Migrator definition cache.", ex);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
            storageV2().saveIndexIfDirty();
            writeStartupIndex(file);
        }
    }

    private synchronized void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        long loadStarted = System.currentTimeMillis();
        int loadedEntries = 0;
        File file = getCacheFile();
        if (file == null || !file.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().length() == 0) {
                    continue;
                }
                String[] parts = splitTabs(line);
                if (parts.length < 9) {
                    continue;
                }
                String customizationType = parts.length >= 11 ? unescape(parts[5]) : "";
                boolean hasContextKey = parts.length >= 12;
                String contextKey = hasContextKey ? unescape(parts[6]) : "";
                int offset = parts.length >= 11 ? (hasContextKey ? 2 : 1) : 0;
                String snapshot = parts.length >= 10 + offset ? unescape(parts[9 + offset]) : "";
                DefinitionEntry entry = new DefinitionEntry(unescape(parts[0]), unescape(parts[1]), unescape(parts[2]),
                        parseLong(parts[3]), unescape(parts[4]), customizationType, contextKey, unescape(parts[5 + offset]), parseLong(parts[6 + offset]),
                        unescape(parts[7 + offset]), unescape(parts[8 + offset]), "");
                if (snapshot != null && snapshot.length() > 0) {
                    // Migrate old TSV-embedded snapshots to external gzip files and drop them from heap.
                    writeSnapshot(entry, snapshot);
                }
                putEntry(entry);
                loadedEntries++;
            }
        } catch (Exception ex) {
            Activator.logWarning("Could not read Yrell Migrator definition cache.", ex);
        } finally {
            lastLoadMillis = System.currentTimeMillis() - loadStarted;
            lastLoadEntries = loadedEntries;
            lastLoadMode = "full TSV metadata scan";
            writeStartupIndex(file);
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }



    public synchronized String getSnapshot(DefinitionEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.snapshot != null && entry.snapshot.length() > 0) {
            return entry.snapshot;
        }
        return readSnapshot(entry);
    }

    private boolean hasStoredSnapshot(DefinitionEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.snapshot != null && entry.snapshot.trim().length() > 0) {
            return true;
        }
        if (storageV2().hasSnapshot(entry)) {
            return true;
        }
        File file = getSnapshotFile(entry);
        return file != null && file.isFile() && file.length() > 0;
    }

    private void deleteSnapshot(DefinitionEntry entry) {
        storageV2().remove(entry);
        File file = getSnapshotFile(entry);
        if (file != null && file.isFile()) {
            try {
                file.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    private void writeSnapshot(DefinitionEntry entry, String snapshot) {
        if (entry == null) {
            return;
        }
        if (storageV2().isAvailable()) {
            storageV2().appendSnapshot(entry, snapshot);
            return;
        }
        writeLegacySnapshot(entry, snapshot);
    }

    private void writeLegacySnapshot(DefinitionEntry entry, String snapshot) {
        if (entry == null) {
            return;
        }
        File file = getSnapshotFile(entry);
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        GZIPOutputStream gzip = null;
        try {
            gzip = new GZIPOutputStream(new FileOutputStream(file));
            byte[] bytes = (snapshot == null ? "" : snapshot).getBytes("UTF-8");
            gzip.write(bytes);
        } catch (Exception ex) {
            Activator.logWarning("Could not write Yrell Migrator snapshot file.", ex);
        } finally {
            if (gzip != null) {
                try { gzip.close(); } catch (Exception ignored) {}
            }
        }
    }

    private String readSnapshot(DefinitionEntry entry) {
        String packed = storageV2().readSnapshot(entry);
        if (packed != null && packed.length() > 0) {
            return packed;
        }
        return readSnapshotLegacyOnly(entry);
    }

    private String readSnapshotLegacyOnly(DefinitionEntry entry) {
        File file = getSnapshotFile(entry);
        if (file == null || !file.isFile()) {
            return "";
        }
        GZIPInputStream gzip = null;
        InputStreamReader reader = null;
        StringBuilder builder = new StringBuilder(4096);
        char[] buffer = new char[8192];
        try {
            gzip = new GZIPInputStream(new FileInputStream(file));
            reader = new InputStreamReader(gzip, "UTF-8");
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } catch (Exception ex) {
            Activator.logWarning("Could not read Yrell Migrator snapshot file.", ex);
            return "";
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            } else if (gzip != null) {
                try { gzip.close(); } catch (Exception ignored) {}
            }
        }
    }

    private File getSnapshotFile(DefinitionEntry entry) {
        File cache = getCacheFile();
        if (cache == null || entry == null) {
            return null;
        }
        File dir = new File(cache.getParentFile(), "definition-cache-snapshots");
        return new File(dir, sha256(entry.key()) + ".txt.gz");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception ex) {
            return String.valueOf((value == null ? "" : value).hashCode());
        }
    }

    private File getCacheFile() {
        Activator plugin = Activator.getDefault();
        if (plugin != null) {
            try {
                File state = plugin.getStateLocation().toFile();
                return new File(state, "definition-cache.tsv");
            } catch (Throwable ignored) {
            }
        }
        return new File(new File(System.getProperty("user.home", "."), ".yrell-migrator"), "definition-cache.tsv");
    }

    private static String describeCachePermissionWarning(File cacheRoot) {
        if (cacheRoot == null || !cacheRoot.exists()) {
            return "";
        }
        try {
            Path path = cacheRoot.toPath();
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                return "Cache directory is readable/writable by group or others: " + cacheRoot.getAbsolutePath();
            }
        } catch (UnsupportedOperationException ex) {
            return "Cache permission check not available on this filesystem; ensure this directory is user-only: " + cacheRoot.getAbsolutePath();
        } catch (Throwable ex) {
            return "Cache permission check failed: " + safeMessage(ex);
        }
        return "";
    }

    private static boolean safeDelete(File file) {
        try {
            return file != null && file.exists() && file.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int deleteDirectoryContents(File dir, boolean deleteRoot) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        int removed = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                if (file == null) {
                    continue;
                }
                if (file.isDirectory()) {
                    removed += deleteDirectoryContents(file, true);
                } else if (safeDelete(file)) {
                    removed++;
                }
            }
        }
        if (deleteRoot && safeDelete(dir)) {
            removed++;
        }
        return removed;
    }

    private static boolean containsIgnoreCase(Collection<String> values, String wanted) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    private void putEntry(DefinitionEntry entry) {
        if (entry == null) {
            return;
        }
        DefinitionEntry old = entries.put(entry.key(), entry);
        if (old != null) {
            removeFromIndex(old);
        }
        List<DefinitionEntry> bucket = entriesByServerType.get(serverTypeKey(entry.server, entry.type));
        if (bucket == null) {
            bucket = new ArrayList<DefinitionEntry>();
            entriesByServerType.put(serverTypeKey(entry.server, entry.type), bucket);
        }
        bucket.add(entry);
    }

    private DefinitionEntry removeEntry(String key) {
        DefinitionEntry old = entries.remove(key);
        if (old != null) {
            removeFromIndex(old);
        }
        return old;
    }

    private void removeFromIndex(DefinitionEntry entry) {
        List<DefinitionEntry> bucket = entriesByServerType.get(serverTypeKey(entry.server, entry.type));
        if (bucket != null) {
            bucket.remove(entry);
            if (bucket.isEmpty()) {
                entriesByServerType.remove(serverTypeKey(entry.server, entry.type));
            }
        }
    }

    private static String serverTypeKey(String server, String type) {
        return (server == null ? "" : server).toLowerCase(Locale.ENGLISH) + '\u0000'
                + (type == null ? "" : type).toLowerCase(Locale.ENGLISH);
    }

    static String key(String server, String type, String name) {
        return (server == null ? "" : server).toLowerCase(Locale.ENGLISH) + '\u0000'
                + (type == null ? "" : type).toLowerCase(Locale.ENGLISH) + '\u0000'
                + (name == null ? "" : name).toLowerCase(Locale.ENGLISH);
    }

    private BmcDefinitionCacheStorageV2 storageV2() {
        if (storageV2 == null) {
            storageV2 = new BmcDefinitionCacheStorageV2(getCacheFile());
        }
        return storageV2;
    }


    private File getStartupIndexFile() {
        File cache = getCacheFile();
        if (cache == null) {
            return null;
        }
        return new File(cache.getParentFile(), "definition-cache-startup.properties");
    }

    private void writeStartupIndex(File cacheFile) {
        File index = getStartupIndexFile();
        if (index == null || cacheFile == null) {
            return;
        }
        File parent = index.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        java.util.HashSet<String> servers = new java.util.HashSet<String>();
        java.util.HashSet<String> serverTypes = new java.util.HashSet<String>();
        Map<String, CacheStartupBucketBuilder> buckets = new java.util.TreeMap<String, CacheStartupBucketBuilder>(String.CASE_INSENSITIVE_ORDER);
        for (DefinitionEntry entry : entries.values()) {
            if (entry == null) {
                continue;
            }
            servers.add(entry.server.toLowerCase(Locale.ENGLISH));
            String bucketKey = serverTypeKey(entry.server, entry.type);
            serverTypes.add(bucketKey);
            CacheStartupBucketBuilder bucket = buckets.get(bucketKey);
            if (bucket == null) {
                bucket = new CacheStartupBucketBuilder(entry.server, entry.type);
                buckets.put(bucketKey, bucket);
            }
            bucket.add(entry);
        }
        Properties props = new Properties();
        props.setProperty("format", "yrell-migrator-definition-cache-startup-v1");
        props.setProperty("snapshotKind", CURRENT_SNAPSHOT_KIND);
        props.setProperty("generatedAt", String.valueOf(System.currentTimeMillis()));
        props.setProperty("cacheModified", String.valueOf(cacheFile.isFile() ? cacheFile.lastModified() : 0L));
        props.setProperty("entries", String.valueOf(entries.size()));
        props.setProperty("servers", String.valueOf(servers.size()));
        props.setProperty("serverTypes", String.valueOf(serverTypes.size()));
        props.setProperty("bucketCount", String.valueOf(buckets.size()));
        int bucketIndex = 0;
        for (CacheStartupBucketBuilder bucket : buckets.values()) {
            String prefix = "bucket." + bucketIndex + ".";
            props.setProperty(prefix + "server", bucket.server);
            props.setProperty(prefix + "type", bucket.type);
            props.setProperty(prefix + "entries", String.valueOf(bucket.entries));
            props.setProperty(prefix + "base", String.valueOf(bucket.base));
            props.setProperty(prefix + "custom", String.valueOf(bucket.custom));
            props.setProperty(prefix + "overlay", String.valueOf(bucket.overlay));
            props.setProperty(prefix + "unknown", String.valueOf(bucket.unknown));
            props.setProperty(prefix + "errors", String.valueOf(bucket.errors));
            bucketIndex++;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(index);
            props.store(out, "Yrell Migrator definition-cache summary index. Safe to delete.");
        } catch (Throwable ex) {
            Activator.logWarning("Could not write Yrell Migrator cache startup index.", ex);
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String[] splitTabs(String line) {
        if (line == null) {
            return new String[0];
        }
        ArrayList<String> parts = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '\t') {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[parts.size()]);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return Long.MIN_VALUE;
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value == null ? "" : value;
        }
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                if (ch == 't') builder.append('\t');
                else if (ch == 'n') builder.append('\n');
                else if (ch == 'r') builder.append('\r');
                else builder.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else {
                builder.append(ch);
            }
        }
        if (escaped) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private static final class CacheStartupBucketBuilder {
        final String server;
        final String type;
        int entries;
        int base;
        int custom;
        int overlay;
        int unknown;
        int errors;

        CacheStartupBucketBuilder(String server, String type) {
            this.server = server == null ? "" : server;
            this.type = type == null ? "" : type;
        }

        void add(DefinitionEntry entry) {
            entries++;
            if (entry != null && entry.error != null && entry.error.length() > 0) {
                errors++;
            }
            String customization = entry == null || entry.customizationType == null ? "" : entry.customizationType.trim().toLowerCase(Locale.ENGLISH);
            if (customization.indexOf("custom") >= 0) {
                custom++;
            } else if (customization.indexOf("overlay") >= 0 || customization.indexOf("overlaid") >= 0) {
                overlay++;
            } else if (customization.indexOf("base") >= 0) {
                base++;
            } else {
                unknown++;
            }
        }
    }

    public static final class RefreshReason {
        private final boolean refresh;
        private final String reason;

        private RefreshReason(boolean refresh, String reason) {
            this.refresh = refresh;
            this.reason = reason == null ? "" : reason;
        }

        public static RefreshReason refresh(String reason) {
            return new RefreshReason(true, reason);
        }

        public static RefreshReason reuse(String reason) {
            return new RefreshReason(false, reason);
        }

        public boolean shouldRefresh() {
            return refresh;
        }

        public String getReason() {
            return reason;
        }
    }


    public static final class CacheStartupStats {
        public final boolean indexAvailable;
        public final boolean current;
        public final int entries;
        public final int servers;
        public final int serverTypes;
        public final List<CacheStartupBucket> buckets;
        public final long generatedAt;
        public final long cacheModified;
        public final long indexLoadMillis;
        public final String cachePath;
        public final String indexPath;
        public final String note;

        CacheStartupStats(boolean indexAvailable, boolean current, int entries, int servers, int serverTypes,
                List<CacheStartupBucket> buckets, long generatedAt, long cacheModified, long indexLoadMillis, String cachePath, String indexPath, String note) {
            this.indexAvailable = indexAvailable;
            this.current = current;
            this.entries = entries;
            this.servers = servers;
            this.serverTypes = serverTypes;
            this.buckets = buckets == null ? Collections.<CacheStartupBucket>emptyList() : buckets;
            this.generatedAt = generatedAt;
            this.cacheModified = cacheModified;
            this.indexLoadMillis = indexLoadMillis;
            this.cachePath = cachePath == null ? "" : cachePath;
            this.indexPath = indexPath == null ? "" : indexPath;
            this.note = note == null ? "" : note;
        }
    }

    public static final class CacheStartupBucket implements Comparable<CacheStartupBucket> {
        public final String server;
        public final String type;
        public final int entries;
        public final int base;
        public final int custom;
        public final int overlay;
        public final int unknown;
        public final int errors;

        CacheStartupBucket(String server, String type, int entries, int base, int custom, int overlay, int unknown, int errors) {
            this.server = server == null ? "" : server;
            this.type = type == null ? "" : type;
            this.entries = entries;
            this.base = base;
            this.custom = custom;
            this.overlay = overlay;
            this.unknown = unknown;
            this.errors = errors;
        }

        public int compareTo(CacheStartupBucket other) {
            int serverCompare = String.CASE_INSENSITIVE_ORDER.compare(server, other == null ? "" : other.server);
            if (serverCompare != 0) {
                return serverCompare;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(type, other == null ? "" : other.type);
        }

        public String summary() {
            StringBuilder text = new StringBuilder();
            text.append(server).append(" / ").append(type).append(": ").append(entries);
            if (custom > 0) text.append(", custom ").append(custom);
            if (overlay > 0) text.append(", overlay ").append(overlay);
            if (base > 0) text.append(", base ").append(base);
            if (unknown > 0) text.append(", unknown ").append(unknown);
            if (errors > 0) text.append(", errors ").append(errors);
            return text.toString();
        }
    }

    public static final class CacheLoadStats {
        public final int entries;
        public final long loadMillis;
        public final String mode;

        CacheLoadStats(int entries, long loadMillis, String mode) {
            this.entries = entries;
            this.loadMillis = loadMillis;
            this.mode = mode == null ? "" : mode;
        }
    }

    public static final class DefinitionEntry implements Comparable<DefinitionEntry> {
        public final String server;
        public final String type;
        public final String name;
        public final long modified;
        public final String changedBy;
        public final String customizationType;
        public final String contextKey;
        public final String fingerprint;
        public final long cachedAt;
        public final String sourceKind;
        public final String error;
        public final String snapshot;

        DefinitionEntry(String server, String type, String name, long modified, String changedBy, String customizationType, String contextKey,
                String fingerprint, long cachedAt, String sourceKind, String error, String snapshot) {
            this.server = server == null ? "" : server;
            this.type = type == null ? "" : type;
            this.name = name == null ? "" : name;
            this.modified = modified;
            this.changedBy = changedBy == null ? "" : changedBy;
            this.customizationType = customizationType == null ? "" : customizationType;
            this.contextKey = contextKey == null ? "" : contextKey;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.cachedAt = cachedAt;
            this.sourceKind = sourceKind == null ? "" : sourceKind;
            this.error = error == null ? "" : error;
            this.snapshot = snapshot == null ? "" : snapshot;
        }

        public String key() {
            return BmcDefinitionCache.key(server, type, name);
        }

        public int compareTo(DefinitionEntry other) {
            int result = String.CASE_INSENSITIVE_ORDER.compare(type, other.type);
            if (result == 0) {
                result = String.CASE_INSENSITIVE_ORDER.compare(name, other.name);
            }
            return result;
        }
    }
}
