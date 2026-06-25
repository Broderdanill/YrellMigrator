package se.yrell.migrator.bmc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcDefinitionCache.DefinitionEntry;

/**
 * Packed snapshot storage for large definition caches.
 *
 * v1 of the cache used one gzip file per object. That is robust, but Windows/Developer Studio
 * environments with hundreds of thousands of objects can spend a lot of time opening and scanning
 * tiny files. Storage v2 keeps the regular metadata TSV, but stores snapshot payloads in append-only
 * per-server/type pack files with a lightweight offset index.
 *
 * The implementation is deliberately conservative:
 * - legacy small files remain readable as fallback,
 * - pack files are append-only and compacted/rebuilt explicitly,
 * - index writes are atomic,
 * - corruption in v2 falls back to legacy snapshots instead of breaking compare.
 */
public final class BmcDefinitionCacheStorageV2 {
    private static final String INDEX_FORMAT = "yrell-migrator-definition-cache-storage-v2-index-1";
    private static final int DEFAULT_CHUNK_TARGET_ENTRIES = 5000;
    /**
     * Hard limits for local cache-v2 reads. These protect Developer Studio from a manipulated
     * local index/pack pair that points at a very large compressed payload or gzip bomb.
     * They can be raised with system properties if a customer has exceptionally large definitions.
     */
    private static final long MAX_COMPRESSED_SNAPSHOT_BYTES = Long.getLong("yrell.migrator.cache.v2.maxCompressedBytes", 32L * 1024L * 1024L).longValue();
    private static final int MAX_EXPANDED_SNAPSHOT_CHARS = Integer.getInteger("yrell.migrator.cache.v2.maxExpandedChars", 64 * 1024 * 1024).intValue();

    public interface SnapshotReader {
        String readSnapshot(DefinitionEntry entry);
    }

    private final File root;
    private final File indexFile;
    private final Map<String, Location> locations = new HashMap<String, Location>();
    private final Map<String, Integer> appendCounts = new HashMap<String, Integer>();
    private boolean loaded;
    private boolean dirty;

    public BmcDefinitionCacheStorageV2(File cacheFile) {
        File parent = cacheFile == null ? null : cacheFile.getParentFile();
        this.root = parent == null ? null : new File(parent, "definition-cache-v2");
        this.indexFile = root == null ? null : new File(root, "index.tsv");
    }

    public synchronized boolean isAvailable() {
        loadIndexIfNeeded();
        return indexFile != null && indexFile.isFile() && !locations.isEmpty();
    }

    public synchronized boolean hasSnapshot(DefinitionEntry entry) {
        loadIndexIfNeeded();
        Location location = entry == null ? null : locations.get(entry.key());
        if (location == null) {
            return false;
        }
        File pack = resolvePackFileQuietly(location.packName);
        return pack != null && pack.isFile() && location.offset >= 0 && location.length > 0
                && location.length <= MAX_COMPRESSED_SNAPSHOT_BYTES
                && pack.length() >= location.offset + location.length;
    }

    public synchronized String readSnapshot(DefinitionEntry entry) {
        loadIndexIfNeeded();
        Location location = entry == null ? null : locations.get(entry.key());
        if (location == null || root == null) {
            return "";
        }
        File pack = resolvePackFileQuietly(location.packName);
        if (pack == null || !pack.isFile() || location.offset < 0 || location.length <= 0
                || location.length > Integer.MAX_VALUE || location.length > MAX_COMPRESSED_SNAPSHOT_BYTES) {
            Activator.logWarning("Ignored Yrell Migrator cache storage v2 snapshot with invalid path or size limit; legacy fallback may be used.", null);
            return "";
        }
        RandomAccessFile random = null;
        GZIPInputStream gzip = null;
        InputStreamReader reader = null;
        try {
            random = new RandomAccessFile(pack, "r");
            if (random.length() < location.offset + location.length) {
                return "";
            }
            byte[] compressed = new byte[(int) location.length];
            random.seek(location.offset);
            random.readFully(compressed);
            gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
            reader = new InputStreamReader(gzip, "UTF-8");
            StringBuilder builder = new StringBuilder(4096);
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (builder.length() + read > MAX_EXPANDED_SNAPSHOT_CHARS) {
                    Activator.logWarning("Ignored Yrell Migrator cache storage v2 snapshot because expanded size exceeds the configured safety limit; legacy fallback may be used.", null);
                    return "";
                }
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } catch (Exception ex) {
            Activator.logWarning("Could not read Yrell Migrator cache storage v2 snapshot; legacy fallback may be used.", ex);
            return "";
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            } else if (gzip != null) {
                try { gzip.close(); } catch (Exception ignored) {}
            }
            if (random != null) {
                try { random.close(); } catch (Exception ignored) {}
            }
        }
    }

    public synchronized void appendSnapshot(DefinitionEntry entry, String snapshot) {
        if (entry == null || root == null) {
            return;
        }
        loadIndexIfNeeded();
        if (!root.isDirectory()) {
            root.mkdirs();
        }
        String bucket = bucketName(entry.server, entry.type);
        int count = appendCounts.containsKey(bucket) ? appendCounts.get(bucket).intValue() : countExistingForBucket(bucket);
        int chunk = Math.max(0, count / DEFAULT_CHUNK_TARGET_ENTRIES);
        String packName = bucket + "-" + pad4(chunk) + ".pack";
        File pack = resolvePackFileQuietly(packName);
        if (pack == null) {
            Activator.logWarning("Could not append Yrell Migrator cache storage v2 snapshot because the generated pack path was rejected by security validation.", null);
            return;
        }
        try {
            byte[] compressed = gzip(snapshot == null ? "" : snapshot);
            long offset = pack.isFile() ? pack.length() : 0L;
            FileOutputStream out = new FileOutputStream(pack, true);
            try {
                out.write(compressed);
            } finally {
                out.close();
            }
            locations.put(entry.key(), new Location(packName, offset, compressed.length, System.currentTimeMillis()));
            appendCounts.put(bucket, Integer.valueOf(count + 1));
            dirty = true;
        } catch (Exception ex) {
            Activator.logWarning("Could not append Yrell Migrator cache storage v2 snapshot; legacy storage can still be used.", ex);
        }
    }

    public synchronized void remove(DefinitionEntry entry) {
        loadIndexIfNeeded();
        if (entry != null && locations.remove(entry.key()) != null) {
            dirty = true;
        }
    }

    public synchronized void saveIndexIfDirty() {
        loadIndexIfNeeded();
        if (!dirty) {
            return;
        }
        writeIndex();
        dirty = false;
    }

    public synchronized StorageStats stats(boolean cleanOrphans) {
        loadIndexIfNeeded();
        int packFiles = 0;
        int orphanPackFiles = 0;
        long packBytes = 0L;
        long indexBytes = indexFile != null && indexFile.isFile() ? indexFile.length() : 0L;
        java.util.HashSet<String> expected = new java.util.HashSet<String>();
        for (Location location : locations.values()) {
            if (location != null && location.packName.length() > 0) {
                expected.add(location.packName);
            }
        }
        if (root != null && root.isDirectory()) {
            File[] files = root.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File file = files[i];
                    if (file == null || !file.isFile() || !file.getName().endsWith(".pack")) {
                        continue;
                    }
                    packFiles++;
                    packBytes += file.length();
                    if (!expected.contains(file.getName())) {
                        orphanPackFiles++;
                        if (cleanOrphans) {
                            try {
                                if (file.delete()) {
                                    packFiles--;
                                    packBytes -= file.length();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }
        return new StorageStats(indexFile != null && indexFile.isFile(), locations.size(), packFiles, orphanPackFiles,
                indexBytes, packBytes, root == null ? "" : root.getAbsolutePath(), indexFile == null ? "" : indexFile.getAbsolutePath());
    }

    public synchronized RebuildResult rebuild(Collection<DefinitionEntry> entries, SnapshotReader reader, boolean deleteLegacyFiles) {
        if (root == null) {
            return new RebuildResult(0, 0, 0, 0L, "storage v2 root unavailable");
        }
        long started = System.currentTimeMillis();
        File tmpRoot = new File(root.getParentFile(), root.getName() + ".rebuild.tmp");
        deleteDirectory(tmpRoot);
        tmpRoot.mkdirs();
        Map<String, Location> rebuilt = new HashMap<String, Location>();
        Map<String, Integer> counts = new HashMap<String, Integer>();
        int written = 0;
        int skipped = 0;
        int packFiles = 0;
        if (entries != null) {
            for (DefinitionEntry entry : entries) {
                if (entry == null || entry.error.length() > 0) {
                    skipped++;
                    continue;
                }
                String snapshot = reader == null ? "" : reader.readSnapshot(entry);
                if (snapshot == null || snapshot.length() == 0) {
                    skipped++;
                    continue;
                }
                String bucket = bucketName(entry.server, entry.type);
                int count = counts.containsKey(bucket) ? counts.get(bucket).intValue() : 0;
                int chunk = Math.max(0, count / DEFAULT_CHUNK_TARGET_ENTRIES);
                String packName = bucket + "-" + pad4(chunk) + ".pack";
                File pack = new File(tmpRoot, packName);
                if (!isSafePackName(packName)) {
                    skipped++;
                    continue;
                }
                try {
                    byte[] compressed = gzip(snapshot);
                    long offset = pack.isFile() ? pack.length() : 0L;
                    FileOutputStream out = new FileOutputStream(pack, true);
                    try {
                        out.write(compressed);
                    } finally {
                        out.close();
                    }
                    if (offset == 0L) {
                        packFiles++;
                    }
                    rebuilt.put(entry.key(), new Location(packName, offset, compressed.length, System.currentTimeMillis()));
                    counts.put(bucket, Integer.valueOf(count + 1));
                    written++;
                } catch (Exception ex) {
                    skipped++;
                    Activator.logWarning("Could not rebuild one Yrell Migrator storage v2 snapshot.", ex);
                }
            }
        }
        if (root.isDirectory()) {
            File backup = new File(root.getParentFile(), root.getName() + ".old");
            deleteDirectory(backup);
            if (!root.renameTo(backup)) {
                deleteDirectory(root);
            }
        }
        if (!tmpRoot.renameTo(root)) {
            deleteDirectory(root);
            tmpRoot.renameTo(root);
        }
        locations.clear();
        locations.putAll(rebuilt);
        appendCounts.clear();
        appendCounts.putAll(counts);
        loaded = true;
        dirty = true;
        writeIndex();
        dirty = false;
        if (deleteLegacyFiles && entries != null) {
            for (DefinitionEntry entry : entries) {
                // Caller owns legacy file deletion because legacy file paths are private there.
            }
        }
        long elapsed = System.currentTimeMillis() - started;
        return new RebuildResult(written, skipped, packFiles, elapsed, "storage v2 rebuilt");
    }

    private void loadIndexIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        locations.clear();
        appendCounts.clear();
        if (indexFile == null || !indexFile.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(indexFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = splitTabs(line);
                if (parts.length < 4) {
                    continue;
                }
                if ("format".equals(parts[0])) {
                    continue;
                }
                String key = unescape(parts[0]);
                String pack = unescape(parts[1]);
                long offset = parseLong(parts[2]);
                long length = parseLong(parts[3]);
                long written = parts.length > 4 ? parseLong(parts[4]) : 0L;
                if (key.length() > 0 && isSafePackName(pack) && length > 0 && length <= MAX_COMPRESSED_SNAPSHOT_BYTES && offset >= 0) {
                    locations.put(key, new Location(pack, offset, length, written));
                    String bucket = pack;
                    int dash = pack.lastIndexOf('-');
                    if (dash > 0) {
                        bucket = pack.substring(0, dash);
                    }
                    Integer old = appendCounts.get(bucket);
                    appendCounts.put(bucket, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
                }
            }
        } catch (Exception ex) {
            Activator.logWarning("Could not read Yrell Migrator cache storage v2 index; legacy cache will be used.", ex);
            locations.clear();
            appendCounts.clear();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void writeIndex() {
        if (indexFile == null) {
            return;
        }
        if (root != null && !root.isDirectory()) {
            root.mkdirs();
        }
        File tmp = new File(indexFile.getAbsolutePath() + ".tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            writer.write("# Yrell Migrator definition cache storage v2 index. Safe to delete.\n");
            writer.write("format\t"); writer.write(INDEX_FORMAT); writer.write('\n');
            List<String> keys = new ArrayList<String>(locations.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                Location loc = locations.get(key);
                if (loc == null) {
                    continue;
                }
                writer.write(escape(key)); writer.write('\t');
                writer.write(escape(loc.packName)); writer.write('\t');
                writer.write(String.valueOf(loc.offset)); writer.write('\t');
                writer.write(String.valueOf(loc.length)); writer.write('\t');
                writer.write(String.valueOf(loc.writtenAt)); writer.write('\n');
            }
        } catch (Exception ex) {
            Activator.logWarning("Could not write Yrell Migrator cache storage v2 index.", ex);
            return;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
        if (indexFile.isFile() && !indexFile.delete()) {
            // renameTo below may still replace on some platforms; keep fallback safe.
        }
        if (!tmp.renameTo(indexFile)) {
            try {
                FileInputStream in = new FileInputStream(tmp);
                FileOutputStream out = new FileOutputStream(indexFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                in.close();
                out.close();
                tmp.delete();
            } catch (Exception ex) {
                Activator.logWarning("Could not replace Yrell Migrator cache storage v2 index atomically.", ex);
            }
        }
    }

    private File resolvePackFileQuietly(String packName) {
        try {
            return resolvePackFile(packName);
        } catch (Exception ex) {
            Activator.logWarning("Rejected Yrell Migrator cache storage v2 pack path: " + safePackNameForLog(packName), ex);
            return null;
        }
    }

    private File resolvePackFile(String packName) throws IOException {
        if (root == null || !isSafePackName(packName)) {
            return null;
        }
        File canonicalRoot = root.getCanonicalFile();
        File pack = new File(canonicalRoot, packName).getCanonicalFile();
        String rootPath = canonicalRoot.getPath();
        String packPath = pack.getPath();
        if (!packPath.equals(rootPath) && !packPath.startsWith(rootPath + File.separator)) {
            throw new IOException("Pack file is outside cache storage v2 root");
        }
        return pack;
    }

    private static boolean isSafePackName(String packName) {
        if (packName == null || packName.length() == 0 || packName.length() > 255) {
            return false;
        }
        if (packName.indexOf('/') >= 0 || packName.indexOf('\\') >= 0 || packName.indexOf("..") >= 0 || !packName.endsWith(".pack")) {
            return false;
        }
        for (int i = 0; i < packName.length(); i++) {
            char c = packName.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static String safePackNameForLog(String packName) {
        if (packName == null) {
            return "<null>";
        }
        String value = packName.replace('\n', '_').replace('\r', '_').replace('\t', '_');
        return value.length() > 120 ? value.substring(0, 120) + "..." : value;
    }

    private int countExistingForBucket(String bucket) {
        int count = 0;
        for (Location location : locations.values()) {
            if (location != null && location.packName.startsWith(bucket + "-")) {
                count++;
            }
        }
        return count;
    }

    private static String bucketName(String server, String type) {
        return sanitize(server) + "__" + sanitize(type);
    }

    private static String sanitize(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ENGLISH);
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        if (out.length() == 0) {
            out.append("unknown");
        }
        out.append('_').append(shortHash(value));
        return out.toString();
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4 && i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xff);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception ex) {
            return String.valueOf(Math.abs((value == null ? "" : value).hashCode()));
        }
    }

    private static String pad4(int value) {
        String s = String.valueOf(value);
        while (s.length() < 4) {
            s = "0" + s;
        }
        return s;
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, text == null ? 128 : text.length() / 2));
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        try {
            gzip.write((text == null ? "" : text).getBytes("UTF-8"));
        } finally {
            gzip.close();
        }
        return out.toByteArray();
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                if (file != null && file.isDirectory()) {
                    deleteDirectory(file);
                } else if (file != null) {
                    try { file.delete(); } catch (Throwable ignored) {}
                }
            }
        }
        try { dir.delete(); } catch (Throwable ignored) {}
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

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return 0L;
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
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                if (c == 't') out.append('\t');
                else if (c == 'n') out.append('\n');
                else if (c == 'r') out.append('\r');
                else out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static final class Location {
        final String packName;
        final long offset;
        final long length;
        final long writtenAt;
        Location(String packName, long offset, long length, long writtenAt) {
            this.packName = packName == null ? "" : packName;
            this.offset = offset;
            this.length = length;
            this.writtenAt = writtenAt;
        }
    }

    public static final class StorageStats {
        public final boolean available;
        public final int indexedSnapshots;
        public final int packFiles;
        public final int orphanPackFiles;
        public final long indexBytes;
        public final long packBytes;
        public final String rootPath;
        public final String indexPath;

        StorageStats(boolean available, int indexedSnapshots, int packFiles, int orphanPackFiles, long indexBytes, long packBytes, String rootPath, String indexPath) {
            this.available = available;
            this.indexedSnapshots = indexedSnapshots;
            this.packFiles = packFiles;
            this.orphanPackFiles = orphanPackFiles;
            this.indexBytes = indexBytes;
            this.packBytes = packBytes;
            this.rootPath = rootPath == null ? "" : rootPath;
            this.indexPath = indexPath == null ? "" : indexPath;
        }

        public long totalBytes() {
            return indexBytes + packBytes;
        }
    }

    public static final class RebuildResult {
        public final int writtenSnapshots;
        public final int skippedEntries;
        public final int packFiles;
        public final long elapsedMillis;
        public final String message;

        RebuildResult(int writtenSnapshots, int skippedEntries, int packFiles, long elapsedMillis, String message) {
            this.writtenSnapshots = writtenSnapshots;
            this.skippedEntries = skippedEntries;
            this.packFiles = packFiles;
            this.elapsedMillis = elapsedMillis;
            this.message = message == null ? "" : message;
        }
    }
}
