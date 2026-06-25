package se.yrell.migrator.diagnostics;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcDefinitionCache;
import se.yrell.migrator.bmc.ObjectTypeRegistry;
import se.yrell.migrator.bmc.BmcDefinitionCache.CacheMaintenanceStats;
import se.yrell.migrator.bmc.BmcDefinitionCache.CacheQualityStats;
import se.yrell.migrator.config.CompareSettings;
import se.yrell.migrator.config.SettingsHealthReport;

/**
 * Builds a compact, support-friendly diagnostics report for the installed plugin.
 *
 * The report intentionally avoids contacting AR servers. It only reads local runtime state,
 * OSGi bundle metadata, active settings and local cache statistics.
 */
public final class DiagnosticsReport {
    private DiagnosticsReport() {
    }

    public static String create() {
        CompareSettings settings = CompareSettings.load();
        BmcDefinitionCache cache = new BmcDefinitionCache();
        CacheMaintenanceStats cacheStats = cache.maintenance(false);
        CacheQualityStats qualityStats = cache.qualityStats();
        StringBuilder text = new StringBuilder(4096);
        appendHeader(text);
        appendRuntime(text);
        appendBundles(text);
        appendSettings(text, settings);
        appendObjectTypeRegistry(text);
        appendCachePolicy(text, settings);
        appendSettingsHealth(text, settings);
        appendBinaryHandling(text, settings);
        appendCache(text, cacheStats);
        appendCacheQuality(text, qualityStats);
        appendSystemPaths(text);
        return text.toString();
    }

    public static String createSanitized() {
        return sanitize(create());
    }

    public static String sanitize(String report) {
        if (report == null || report.length() == 0) {
            return "";
        }
        String sanitized = report;
        sanitized = replaceSystemPath(sanitized, "user.home", "<user.home>");
        sanitized = replaceSystemPath(sanitized, "java.home", "<java.home>");
        sanitized = replaceSystemPath(sanitized, "osgi.install.area", "<osgi.install.area>");
        sanitized = replaceSystemPath(sanitized, "eclipse.home.location", "<eclipse.home.location>");
        String home = System.getProperty("user.home", "");
        if (home != null && home.length() > 0) {
            sanitized = sanitized.replace(home.replace('\\', '/'), "<user.home>");
            sanitized = sanitized.replace(home, "<user.home>");
        }
        sanitized = redactPathLine(sanitized, "Java home");
        sanitized = redactPathLine(sanitized, "Shared config path");
        sanitized = redactPathLine(sanitized, "Settings source");
        sanitized = redactPathLine(sanitized, "Storage v2 root");
        sanitized = redactPathLine(sanitized, "Storage v2 index");
        sanitized = redactPathLine(sanitized, "Index path");
        sanitized = redactPathLine(sanitized, "Snapshot directory");
        sanitized = redactPathLine(sanitized, "Plugin state");
        return sanitized;
    }

    private static void appendHeader(StringBuilder text) {
        line(text, "Yrell Migrator diagnostics");
        line(text, "Generated", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ENGLISH).format(new Date()));
        line(text, "Plugin", Activator.PLUGIN_ID);
        line(text, "Plugin version", bundleVersion(Activator.PLUGIN_ID));
        line(text, "Snapshot schema", BmcDefinitionCache.CURRENT_SNAPSHOT_KIND);
        text.append('\n');
    }

    private static void appendRuntime(StringBuilder text) {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory == null ? null : memory.getHeapMemoryUsage();
        line(text, "Runtime");
        line(text, "Java version", System.getProperty("java.version", ""));
        line(text, "Java vendor", System.getProperty("java.vendor", ""));
        line(text, "Java home", System.getProperty("java.home", ""));
        line(text, "OS", System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", ""));
        line(text, "Available processors", String.valueOf(runtime.availableProcessors()));
        line(text, "Heap used", heap == null ? "unknown" : formatBytes(heap.getUsed()));
        line(text, "Heap committed", heap == null ? "unknown" : formatBytes(heap.getCommitted()));
        line(text, "Heap max", heap == null ? "unknown" : formatBytes(heap.getMax()));
        text.append('\n');
    }

    private static void appendBundles(StringBuilder text) {
        line(text, "Developer Studio / OSGi bundles");
        Map<String, String> bundles = new LinkedHashMap<String, String>();
        bundles.put("org.eclipse.platform", "Eclipse Platform");
        bundles.put("org.eclipse.ui", "Eclipse UI");
        bundles.put("org.eclipse.core.runtime", "Eclipse Runtime");
        bundles.put("com.bmc.arsys.studio.api", "BMC Studio API");
        bundles.put("com.bmc.arsys.studio.model", "BMC Studio Model");
        bundles.put("com.bmc.arsys.studio.commonui", "BMC Studio Common UI");
        bundles.put("com.bmc.arsys.studio.ui", "BMC Studio UI");
        for (Map.Entry<String, String> entry : bundles.entrySet()) {
            line(text, entry.getValue(), bundleVersion(entry.getKey()));
        }
        text.append('\n');
    }

    private static void appendSettings(StringBuilder text, CompareSettings settings) {
        line(text, "Effective settings");
        line(text, "Settings source", settings.describeLocation());
        File sharedConfig = CompareSettings.getInstallAreaConfigFile();
        line(text, "Shared config path", sharedConfig == null ? "unknown" : sharedConfig.getAbsolutePath());
        line(text, "Sync filters", settings.describeSyncFilters());
        line(text, "Incremental definitions", String.valueOf(settings.isSyncIncrementalDefinitions()));
        line(text, "Retry error snapshots", String.valueOf(settings.isSyncRetryErrorSnapshots()));
        line(text, "Force snapshot rebuild", String.valueOf(settings.isSyncForceRefreshDefinitions()));
        line(text, "Deep-cache binary/support", String.valueOf(settings.isSyncDeepCacheBinaryObjects()));
        line(text, "Deep-cache expensive", String.valueOf(settings.isSyncDeepCacheExpensiveObjects()));
        line(text, "Metadata timeout seconds", String.valueOf(settings.getMetadataEnumerationTimeoutSeconds()));
        line(text, "Object timeout seconds", String.valueOf(settings.getCompareObjectTimeoutSeconds()));
        line(text, "Aggressive metadata fallback", String.valueOf(settings.isMetadataAggressiveEnumeration()));
        line(text, "Search max rows", String.valueOf(settings.getSearchMaxResults()));
        line(text, "Show equal by default", String.valueOf(settings.isShowEqualByDefault()));
        line(text, "Cache overview startup", "full local cache overview loads when the view opens");
        line(text, "Ignore properties", settings.getIgnoreDifferenceNameContainsRaw());
        line(text, "Ignore internals", settings.getIgnoreFingerprintMemberNameContainsRaw());
        line(text, "Ignore mask ids", settings.getIgnoreMaskIds().toString());
        text.append('\n');
    }



    private static void appendObjectTypeRegistry(StringBuilder text) {
        ObjectTypeRegistry.RegistryStatus status = ObjectTypeRegistry.status();
        line(text, "Object type registry");
        line(text, "Resolved object types", String.valueOf(status.resolved));
        line(text, "Unresolved optional object types", String.valueOf(status.unresolved));
        line(text, "Associations", status.associationStatus);
        line(text, "Registry usage", "central source for labels, migration phases, customization/cache capability hints");
        if (status.unresolvedLabels.length() > 0) {
            line(text, "Unresolved labels", status.unresolvedLabels);
        }
        text.append('\n');
    }

    private static void appendCachePolicy(StringBuilder text, CompareSettings settings) {
        line(text, "Cache customization policy");
        line(text, "Base", settings.isSyncCacheBaseCustomization() ? "enabled" : "disabled");
        line(text, "Custom", settings.isSyncCacheCustomCustomization() ? "enabled" : "disabled");
        line(text, "Overlay", settings.isSyncCacheOverlayCustomization() ? "enabled" : "disabled");
        line(text, "Policy", settings.describeSyncFilters());
        line(text, "Unknown customization on normal definitions", settings.isSyncCacheBaseCustomization()
                ? "included because Base is enabled"
                : "treated as Base/unknown and skipped before deep snapshot");
        line(text, "Always included without customization type", "Image, Group, Role, Message, Report, Template");
        line(text, "Definition snapshot impact", "Base rows skipped by metadata sync are not deep-read during full Sync");
        text.append('\n');
    }


    private static void appendSettingsHealth(StringBuilder text, CompareSettings settings) {
        line(text, "Settings health");
        String report = SettingsHealthReport.format(settings);
        String[] lines = report.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].length() > 0) {
                text.append(lines[i]).append('\n');
            }
        }
        text.append('\n');
    }

    private static void appendBinaryHandling(StringBuilder text, CompareSettings settings) {
        line(text, "Binary object handling");
        line(text, "Image migration", "generic model migrator when Developer Studio exposes Image as a cloneable model object");
        line(text, "Support File migration", "dedicated best-effort Support File migrator");
        line(text, "Live binary fingerprinting", "enabled for Image/Support File rows when byte[]/ByteBuffer content is exposed by getters");
        line(text, "Sync binary deep-cache", settings.isSyncDeepCacheBinaryObjects()
                ? "enabled: Sync may open binary objects for snapshots"
                : "disabled: Sync uses metadata-only snapshots for binary/support objects");
        text.append('\n');
    }

    private static void appendCache(StringBuilder text, CacheMaintenanceStats stats) {
        line(text, "Local definition cache");
        if (stats.permissionWarning.length() > 0) {
            line(text, "Cache permissions", "WARNING - " + stats.permissionWarning);
        } else {
            line(text, "Cache permissions", "OK or not applicable");
        }
        line(text, "Cache entries", String.valueOf(stats.entries));
        line(text, "Snapshot files", String.valueOf(stats.snapshotFiles));
        line(text, "Orphan snapshot files", String.valueOf(stats.orphanSnapshotFiles));
        line(text, "Index size", formatBytes(stats.indexBytes));
        line(text, "Snapshot size", formatBytes(stats.snapshotBytes));
        if (stats.storageV2 != null) {
            line(text, "Storage v2 available", String.valueOf(stats.storageV2.available));
            line(text, "Storage v2 indexed snapshots", String.valueOf(stats.storageV2.indexedSnapshots));
            line(text, "Storage v2 pack files", String.valueOf(stats.storageV2.packFiles));
            line(text, "Storage v2 orphan pack files", String.valueOf(stats.storageV2.orphanPackFiles));
            line(text, "Storage v2 index size", formatBytes(stats.storageV2.indexBytes));
            line(text, "Storage v2 pack size", formatBytes(stats.storageV2.packBytes));
            line(text, "Storage v2 root", stats.storageV2.rootPath);
            line(text, "Storage v2 index", stats.storageV2.indexPath);
        }
        line(text, "Total size", formatBytes(stats.totalBytes()));
        line(text, "Index path", stats.indexPath);
        line(text, "Snapshot directory", stats.snapshotDirectory);
        text.append('\n');
    }


    private static void appendCacheQuality(StringBuilder text, CacheQualityStats stats) {
        line(text, "Definition cache quality");
        line(text, "Current snapshot kind", stats.currentSnapshotKind);
        line(text, "Entries", String.valueOf(stats.entries));
        line(text, "Full compatible snapshots", String.valueOf(stats.fullSnapshots));
        line(text, "Legacy compatible snapshots", String.valueOf(stats.legacySnapshots));
        line(text, "Entries without snapshot file", String.valueOf(stats.missingSnapshots));
        line(text, "Entries without fingerprint", String.valueOf(stats.missingFingerprints));
        line(text, "Entries with cache errors", String.valueOf(stats.errors));
        line(text, "Entries without reliable timestamp", String.valueOf(stats.noReliableTimestamp));
        line(text, "Entries with stale schema", String.valueOf(stats.staleSchema));
        line(text, "Potential metadata-only rows", String.valueOf(stats.metadataOnlyEntries()));
        text.append('\n');
    }

    private static void appendSystemPaths(StringBuilder text) {
        line(text, "System paths");
        line(text, "osgi.install.area", System.getProperty("osgi.install.area", ""));
        line(text, "eclipse.home.location", System.getProperty("eclipse.home.location", ""));
        line(text, "user.home", System.getProperty("user.home", ""));
        try {
            Activator plugin = Activator.getDefault();
            line(text, "Plugin state", plugin == null ? "not started" : String.valueOf(plugin.getStateLocation()));
        } catch (Throwable ex) {
            line(text, "Plugin state", safeMessage(ex));
        }
        text.append('\n');
    }

    private static String bundleVersion(String bundleId) {
        try {
            Bundle bundle = Platform.getBundle(bundleId);
            if (bundle == null) {
                return "not loaded";
            }
            return bundle.getVersion() + " (state=" + bundleState(bundle.getState()) + ")";
        } catch (Throwable ex) {
            return safeMessage(ex);
        }
    }

    private static String bundleState(int state) {
        switch (state) {
        case Bundle.ACTIVE:
            return "ACTIVE";
        case Bundle.INSTALLED:
            return "INSTALLED";
        case Bundle.RESOLVED:
            return "RESOLVED";
        case Bundle.STARTING:
            return "STARTING";
        case Bundle.STOPPING:
            return "STOPPING";
        case Bundle.UNINSTALLED:
            return "UNINSTALLED";
        default:
            return String.valueOf(state);
        }
    }

    private static String replaceSystemPath(String text, String property, String replacement) {
        String value = System.getProperty(property, "");
        if (value == null || value.length() == 0) {
            return text;
        }
        return text.replace(value, replacement).replace(value.replace('\\', '/'), replacement);
    }

    private static String redactPathLine(String text, String label) {
        String prefix = label + ": ";
        String[] lines = text.split("\r?\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(prefix) && looksLikePath(line.substring(prefix.length()))) {
                out.append(prefix).append("<redacted path>");
            } else {
                out.append(line);
            }
            if (i + 1 < lines.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static boolean looksLikePath(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.indexOf('/') >= 0 || v.indexOf('\\') >= 0 || v.startsWith("file:") || (v.length() > 2 && v.charAt(1) == ':');
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "unknown";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.ENGLISH, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.ENGLISH, "%.1f MB", mb);
        }
        return String.format(Locale.ENGLISH, "%.1f GB", mb / 1024.0);
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }

    private static void line(StringBuilder text, String heading) {
        text.append(heading == null ? "" : heading).append('\n');
        text.append(repeat('-', heading == null ? 0 : Math.max(heading.length(), 8))).append('\n');
    }

    private static void line(StringBuilder text, String name, String value) {
        text.append(name == null ? "" : name).append(": ").append(value == null ? "" : value).append('\n');
    }

    private static String repeat(char ch, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(ch);
        }
        return builder.toString();
    }
}
