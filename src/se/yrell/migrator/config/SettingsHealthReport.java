package se.yrell.migrator.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Builds a compact sanity report for effective Yrell Migrator settings.
 *
 * <p>The report is intentionally conservative: it does not reject settings and it does not
 * contact AR servers. It only points out combinations that commonly make compares slower,
 * noisier or less predictable.</p>
 */
public final class SettingsHealthReport {
    private SettingsHealthReport() {
    }

    public static Report analyze(CompareSettings settings) {
        List<String> warnings = new ArrayList<String>();
        List<String> notes = new ArrayList<String>();
        if (settings == null) {
            warnings.add("Settings could not be loaded.");
            return new Report("WARNING", warnings, notes);
        }

        if (settings.getSourceFile() != null) {
            String source = settings.describeLocation();
            notes.add("Settings are loaded from an external properties file: " + source);
            if (source != null && source.toLowerCase(Locale.ENGLISH).endsWith(CompareSettings.LEGACY_CONFIG_FILE_NAME)) {
                warnings.add("Legacy settings file name is in use. Prefer yrell-migrator.properties for secure deployments.");
            }
        }
        if (settings.isSyncForceRefreshDefinitions()) {
            warnings.add("Rebuild snapshots next Sync is enabled. The next Sync will regenerate cached definitions and may be slow.");
        }
        if (!settings.isSyncIncrementalDefinitions()) {
            warnings.add("Incremental sync is disabled. Every Sync will refresh more definition data than usual.");
        }
        if (settings.isSyncDeepCacheBinaryObjects()) {
            warnings.add("Deep-cache binary/support objects is enabled. Images and Support Files can be memory-heavy in Developer Studio.");
        }
        if (settings.isSyncDeepCacheExpensiveObjects()) {
            warnings.add("Deep-cache expensive objects is enabled. Applications, Packing Lists, Reports, Web Services and similar objects can be slow.");
        }
        if (settings.isMetadataAggressiveEnumeration()) {
            warnings.add("Aggressive metadata fallback is enabled. Keep it on only when normal metadata enumeration misses object types.");
        }
        if (settings.getCompareObjectTimeoutSeconds() < 30) {
            warnings.add("Object timeout is very low (" + settings.getCompareObjectTimeoutSeconds() + "s). Large forms/workflow may time out.");
        }
        if (settings.getMetadataEnumerationTimeoutSeconds() < 15) {
            warnings.add("Metadata timeout is very low (" + settings.getMetadataEnumerationTimeoutSeconds() + "s). Sync may skip slow object types.");
        }
        if (settings.getSearchMaxResults() > 10000) {
            warnings.add("Search max rows is high (" + settings.getSearchMaxResults() + "). Large searches can make the Differences view heavy.");
        }
        if (settings.isShowEqualByDefault()) {
            notes.add("Equal objects are shown by default. This is useful for audits but can make the list noisy in large environments.");
        }
        if (!settings.isSyncCacheBaseCustomization()) {
            notes.add("Base customization is not selected for Sync cache. BMC base definitions are skipped by default; non-customizable catalog/data objects remain included.");
        }
        if (!settings.isSyncCacheCustomCustomization() && !settings.isSyncCacheOverlayCustomization()) {
            warnings.add("Neither Custom nor Overlay is selected for Sync cache. Normal custom work may disappear from cached comparisons.");
        }

        String ignoreProperties = settings.getIgnoreDifferenceNameContainsRaw();
        if (ignoreProperties == null || ignoreProperties.trim().length() == 0) {
            warnings.add("Ignore properties is empty. Timestamp/last-changed fields may create avoidable differences.");
        } else {
            addRiskyIgnoreWarnings(ignoreProperties, warnings);
        }

        String syncFilters = settings.describeSyncFilters();
        if (syncFilters != null && syncFilters.indexOf("include=<all>") >= 0) {
            notes.add("Sync include is empty, so every object name is eligible unless excluded.");
        }
        if (settings.isSyncRetryErrorSnapshots()) {
            notes.add("Retry failed snapshots is enabled. This is useful after transient AR/Developer Studio errors.");
        } else {
            notes.add("Retry failed snapshots is disabled. Enable it when fixing transient cache errors.");
        }

        return new Report(warnings.isEmpty() ? "OK" : "WARNING", warnings, notes);
    }

    public static String format(CompareSettings settings) {
        return analyze(settings).toText();
    }

    private static void addRiskyIgnoreWarnings(String raw, List<String> warnings) {
        String lower = raw.toLowerCase(Locale.ENGLISH);
        String[] risky = new String[] { "permission", "permissionlist", "group", "audit", "qualification", "runif", "field", "view", "action", "workflow" };
        for (int i = 0; i < risky.length; i++) {
            if (containsTokenLike(lower, risky[i])) {
                warnings.add("Ignore properties contains a broad/high-impact token: " + risky[i]
                        + ". Prefer scoped ignores from Show Details for permissions, audit, fields, views and workflow logic.");
            }
        }
    }

    private static boolean containsTokenLike(String lowerRaw, String token) {
        if (lowerRaw == null || lowerRaw.length() == 0) {
            return false;
        }
        String[] parts = lowerRaw.split("[,;]");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim().toLowerCase(Locale.ENGLISH).replace(" ", "");
            if (part.equals(token) || part.endsWith("/" + token) || part.indexOf(token) >= 0 && token.length() >= 6) {
                return true;
            }
        }
        return false;
    }

    public static final class Report {
        private final String status;
        private final List<String> warnings;
        private final List<String> notes;

        private Report(String status, List<String> warnings, List<String> notes) {
            this.status = status == null ? "UNKNOWN" : status;
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings == null ? Collections.<String>emptyList() : warnings));
            this.notes = Collections.unmodifiableList(new ArrayList<String>(notes == null ? Collections.<String>emptyList() : notes));
        }

        public String getStatus() {
            return status;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public List<String> getNotes() {
            return notes;
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public String toText() {
            StringBuilder out = new StringBuilder();
            out.append("Settings health: ").append(status).append('\n');
            if (warnings.isEmpty()) {
                out.append("No high-risk setting combinations detected.\n");
            } else {
                out.append("Warnings:\n");
                for (int i = 0; i < warnings.size(); i++) {
                    out.append("  - ").append(warnings.get(i)).append('\n');
                }
            }
            if (!notes.isEmpty()) {
                out.append("Notes:\n");
                for (int i = 0; i < notes.size(); i++) {
                    out.append("  - ").append(notes.get(i)).append('\n');
                }
            }
            return out.toString();
        }
    }
}
