package se.yrell.migrator.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.Preferences;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.ObjectTypeRegistry;

/**
 * Compare configuration.
 *
 * Normal settings are stored in Eclipse preferences (Window -> Preferences -> Yrell Migrator).
 * For scripted/portable installations an optional yrell-migrator.properties file can be placed next
 * to devstudio.ini; explicit system-property/environment paths still win. Legacy Helix Migrator
 * config locations are still accepted as fallbacks so existing installations can upgrade safely.
 * Working-directory config discovery is disabled by default for safer secure deployments.
 */
public final class CompareSettings {
    public static final String SYSTEM_PROPERTY = "yrell.migrator.config";
    public static final String LEGACY_SYSTEM_PROPERTY = "helix.migrator.config";
    public static final String OLDEST_LEGACY_SYSTEM_PROPERTY = "helix.compare.config";
    public static final String ENV_PROPERTY = "YRELL_MIGRATOR_CONFIG";
    public static final String LEGACY_ENV_PROPERTY = "HELIX_MIGRATOR_CONFIG";
    public static final String OLDEST_LEGACY_ENV_PROPERTY = "HELIX_COMPARE_CONFIG";
    public static final String ALLOW_WORKING_DIRECTORY_CONFIG_PROPERTY = "yrell.migrator.allowWorkingDirectoryConfig";
    public static final String LEGACY_ALLOW_WORKING_DIRECTORY_CONFIG_PROPERTY = "helix.migrator.allowWorkingDirectoryConfig";
    public static final String CONFIG_FILE_NAME = "yrell-migrator.properties";
    public static final String LEGACY_CONFIG_FILE_NAME = "helix-migrator.properties";
    public static final String OLDEST_LEGACY_CONFIG_FILE_NAME = "helix-compare.properties";
    private static final String LEGACY_PLUGIN_ID = "se.yrell.helix.migrator";

    public static final String KEY_USE_BMC_DEFAULT_IGNORES = "compare.useBmcDefaultIgnores";
    public static final String KEY_FORCE_RELOAD_OBJECTS = "compare.forceReloadObjects";
    public static final String KEY_DEEP_COMPARE_FORMS = "compare.deepCompareForms";
    public static final String KEY_CACHE_OBJECTS = "compare.cacheObjects";
    public static final String KEY_FINGERPRINT_FALLBACK = "compare.fingerprintFallback";
    public static final String KEY_COMPARE_OBJECT_TIMEOUT_SECONDS = "compare.objectTimeoutSeconds";
    public static final String KEY_MAX_SUMMARY_ITEMS = "compare.maxSummaryItems";
    public static final String KEY_SEARCH_MAX_RESULTS = "search.maxResults";
    public static final String KEY_SHOW_EQUAL_BY_DEFAULT = "view.showEqualByDefault";
    public static final String KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS = "metadata.enumerationTimeoutSeconds";
    public static final String KEY_METADATA_AGGRESSIVE_ENUMERATION = "metadata.aggressiveEnumeration";
    public static final String KEY_SYNC_INCLUDE_NAME_PATTERNS = "sync.include.name.patterns";
    public static final String KEY_SYNC_EXCLUDE_NAME_PATTERNS = "sync.exclude.name.patterns";
    public static final String KEY_SYNC_CACHE_CUSTOMIZATION_TYPES = "sync.cache.customization.types";
    public static final String KEY_SYNC_FORCE_REFRESH_DEFINITIONS = "sync.forceRefreshDefinitions";
    public static final String KEY_SYNC_DEEP_CACHE_BINARY_OBJECTS = "sync.deepCacheBinaryObjects";
    public static final String KEY_SYNC_DEEP_CACHE_EXPENSIVE_OBJECTS = "sync.deepCacheExpensiveObjects";
    public static final String KEY_SYNC_INCREMENTAL_DEFINITIONS = "sync.incrementalDefinitions";
    public static final String KEY_SYNC_RETRY_ERROR_SNAPSHOTS = "sync.retryErrorSnapshots";
    public static final String KEY_IGNORE_MASK_IDS = "ignore.mask.ids";
    public static final String KEY_IGNORE_DIFFERENCE_NAME_CONTAINS = "ignore.difference.name.contains";
    public static final String KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS = "ignore.fingerprint.member.name.contains";

    private final File sourceFile;
    private final String sourceDescription;
    private final boolean useBmcDefaultIgnores;
    private final boolean forceReloadObjects;
    private final boolean deepCompareForms;
    private final boolean cacheObjects;
    private final boolean fingerprintFallback;
    private final boolean showEqualByDefault;
    private final int compareObjectTimeoutSeconds;
    private final int maxSummaryItems;
    private final int searchMaxResults;
    private final int metadataEnumerationTimeoutSeconds;
    private final boolean metadataAggressiveEnumeration;
    private final List<NamePattern> syncIncludeNamePatterns;
    private final List<NamePattern> syncExcludeNamePatterns;
    private final String syncIncludeNamePatternsRaw;
    private final String syncExcludeNamePatternsRaw;
    private final String syncCacheCustomizationTypesRaw;
    private final Set<String> syncCacheCustomizationTypes;
    private final boolean syncForceRefreshDefinitions;
    private final boolean syncDeepCacheBinaryObjects;
    private final boolean syncDeepCacheExpensiveObjects;
    private final boolean syncIncrementalDefinitions;
    private final boolean syncRetryErrorSnapshots;
    private final Set<Integer> ignoreMaskIds;
    private final List<String> ignoreDifferenceNameContains;
    private final List<String> ignoreFingerprintMemberNameContains;

    private CompareSettings(File sourceFile, String sourceDescription, Properties props) {
        this.sourceFile = sourceFile;
        this.sourceDescription = sourceDescription == null ? "preferences/defaults" : sourceDescription;
        this.useBmcDefaultIgnores = getBoolean(props, KEY_USE_BMC_DEFAULT_IGNORES, false);
        this.forceReloadObjects = getBoolean(props, KEY_FORCE_RELOAD_OBJECTS, true);
        this.deepCompareForms = getBoolean(props, KEY_DEEP_COMPARE_FORMS, true);
        this.cacheObjects = getBoolean(props, KEY_CACHE_OBJECTS, true);
        this.fingerprintFallback = getBoolean(props, KEY_FINGERPRINT_FALLBACK, true);
        this.showEqualByDefault = getBoolean(props, KEY_SHOW_EQUAL_BY_DEFAULT, false);
        this.compareObjectTimeoutSeconds = getInt(props, KEY_COMPARE_OBJECT_TIMEOUT_SECONDS, 180);
        this.maxSummaryItems = getInt(props, KEY_MAX_SUMMARY_ITEMS, 10);
        this.searchMaxResults = getInt(props, KEY_SEARCH_MAX_RESULTS, 1000);
        this.metadataEnumerationTimeoutSeconds = getInt(props, KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS, 60);
        this.metadataAggressiveEnumeration = getBoolean(props, KEY_METADATA_AGGRESSIVE_ENUMERATION, false);
        this.syncIncludeNamePatternsRaw = props.getProperty(KEY_SYNC_INCLUDE_NAME_PATTERNS, "ARS:*");
        this.syncExcludeNamePatternsRaw = props.getProperty(KEY_SYNC_EXCLUDE_NAME_PATTERNS, "");
        this.syncCacheCustomizationTypesRaw = props.getProperty(KEY_SYNC_CACHE_CUSTOMIZATION_TYPES, "custom,overlay");
        this.syncForceRefreshDefinitions = getBoolean(props, KEY_SYNC_FORCE_REFRESH_DEFINITIONS, false);
        this.syncDeepCacheBinaryObjects = getBoolean(props, KEY_SYNC_DEEP_CACHE_BINARY_OBJECTS, false);
        this.syncDeepCacheExpensiveObjects = getBoolean(props, KEY_SYNC_DEEP_CACHE_EXPENSIVE_OBJECTS, false);
        this.syncIncrementalDefinitions = getBoolean(props, KEY_SYNC_INCREMENTAL_DEFINITIONS, true);
        this.syncRetryErrorSnapshots = getBoolean(props, KEY_SYNC_RETRY_ERROR_SNAPSHOTS, false);
        this.syncIncludeNamePatterns = parseNamePatterns(syncIncludeNamePatternsRaw);
        this.syncExcludeNamePatterns = parseNamePatterns(syncExcludeNamePatternsRaw);
        this.syncCacheCustomizationTypes = parseCustomizationTypeSet(syncCacheCustomizationTypesRaw);
        this.ignoreMaskIds = parseIntegerSet(props.getProperty(KEY_IGNORE_MASK_IDS, ""));
        this.ignoreDifferenceNameContains = parseStringList(props.getProperty(KEY_IGNORE_DIFFERENCE_NAME_CONTAINS,
                "Last Update,Last Changed,Modified Date,Timestamp"));
        this.ignoreFingerprintMemberNameContains = parseStringList(props.getProperty(KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS,
                "store,changeListener,loadedActiveLinks,deletedFields,deletedViews,dirty,state"));
    }

    public static void initializeDefaults(IPreferenceStore store) {
        if (store == null) {
            return;
        }
        store.setDefault(KEY_USE_BMC_DEFAULT_IGNORES, false);
        store.setDefault(KEY_FORCE_RELOAD_OBJECTS, true);
        store.setDefault(KEY_DEEP_COMPARE_FORMS, true);
        store.setDefault(KEY_CACHE_OBJECTS, true);
        store.setDefault(KEY_FINGERPRINT_FALLBACK, true);
        store.setDefault(KEY_COMPARE_OBJECT_TIMEOUT_SECONDS, 180);
        store.setDefault(KEY_SHOW_EQUAL_BY_DEFAULT, false);
        store.setDefault(KEY_MAX_SUMMARY_ITEMS, 10);
        store.setDefault(KEY_SEARCH_MAX_RESULTS, 1000);
        store.setDefault(KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS, 60);
        store.setDefault(KEY_METADATA_AGGRESSIVE_ENUMERATION, false);
        store.setDefault(KEY_SYNC_INCLUDE_NAME_PATTERNS, "ARS:*");
        store.setDefault(KEY_SYNC_EXCLUDE_NAME_PATTERNS, "");
        store.setDefault(KEY_SYNC_CACHE_CUSTOMIZATION_TYPES, "custom,overlay");
        store.setDefault(KEY_SYNC_FORCE_REFRESH_DEFINITIONS, false);
        store.setDefault(KEY_SYNC_DEEP_CACHE_BINARY_OBJECTS, false);
        store.setDefault(KEY_SYNC_DEEP_CACHE_EXPENSIVE_OBJECTS, false);
        store.setDefault(KEY_SYNC_INCREMENTAL_DEFINITIONS, true);
        store.setDefault(KEY_SYNC_RETRY_ERROR_SNAPSHOTS, false);
        store.setDefault(KEY_IGNORE_MASK_IDS, "");
        store.setDefault(KEY_IGNORE_DIFFERENCE_NAME_CONTAINS, "Last Update,Last Changed,Modified Date,Timestamp");
        store.setDefault(KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS, "store,changeListener,loadedActiveLinks,deletedFields,deletedViews,dirty,state");
    }

    public static CompareSettings load() {
        Properties props = defaultProperties();
        loadPreferenceStore(props);

        File file = findConfigFile();
        if (file != null && file.isFile()) {
            loadFile(file, props);
            return new CompareSettings(file, file.getAbsolutePath(), props);
        }
        return new CompareSettings(null, "Window > Preferences > Yrell Migrator", props);
    }

    public static File getInstallAreaConfigFile() {
        File installArea = getInstallAreaDirectory();
        return installArea == null ? null : new File(installArea, CONFIG_FILE_NAME);
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public boolean isUseBmcDefaultIgnores() {
        return useBmcDefaultIgnores;
    }

    public boolean isForceReloadObjects() {
        return forceReloadObjects;
    }

    public boolean isDeepCompareForms() {
        return deepCompareForms;
    }

    public boolean isCacheObjects() {
        return cacheObjects;
    }

    public boolean isFingerprintFallback() {
        return fingerprintFallback;
    }

    public boolean isShowEqualByDefault() {
        return showEqualByDefault;
    }

    public int getCompareObjectTimeoutSeconds() {
        return compareObjectTimeoutSeconds;
    }

    public int getMaxSummaryItems() {
        return maxSummaryItems;
    }

    public int getSearchMaxResults() {
        return searchMaxResults;
    }

    public int getMetadataEnumerationTimeoutSeconds() {
        return metadataEnumerationTimeoutSeconds;
    }

    public boolean isMetadataAggressiveEnumeration() {
        return metadataAggressiveEnumeration;
    }

    public String getSyncIncludeNamePatternsRaw() {
        return syncIncludeNamePatternsRaw == null ? "" : syncIncludeNamePatternsRaw;
    }

    public String getSyncExcludeNamePatternsRaw() {
        return syncExcludeNamePatternsRaw == null ? "" : syncExcludeNamePatternsRaw;
    }

    public String getSyncCacheCustomizationTypesRaw() {
        return syncCacheCustomizationTypesRaw == null ? "" : syncCacheCustomizationTypesRaw;
    }

    public boolean isSyncCacheBaseCustomization() {
        return syncCacheCustomizationTypes.contains("base");
    }

    public boolean isSyncCacheCustomCustomization() {
        return syncCacheCustomizationTypes.contains("custom");
    }

    public boolean isSyncCacheOverlayCustomization() {
        return syncCacheCustomizationTypes.contains("overlay");
    }

    public boolean isSyncForceRefreshDefinitions() {
        return syncForceRefreshDefinitions;
    }

    public boolean isSyncDeepCacheBinaryObjects() {
        return syncDeepCacheBinaryObjects;
    }

    public boolean isSyncDeepCacheExpensiveObjects() {
        return syncDeepCacheExpensiveObjects;
    }

    public boolean isSyncIncrementalDefinitions() {
        return syncIncrementalDefinitions;
    }

    public boolean isSyncRetryErrorSnapshots() {
        return syncRetryErrorSnapshots;
    }

    public boolean isCacheFastStartup() {
        return false;
    }

    /**
     * Returns true when an object name should be included in full sync. Patterns are matched against
     * the AR object name, case-insensitively. Empty include list means include all. Exclude wins.
     */
    public boolean shouldSyncObject(String typeName, String objectName) {
        return getSyncDecision(typeName, objectName).isIncluded();
    }

    /** Returns an explicit include/exclude decision used by Sync logging and diagnostics. */
    public SyncDecision getSyncDecision(String typeName, String objectName) {
        String name = objectName == null ? "" : objectName;
        if (isAlwaysSyncedType(typeName)) {
            return SyncDecision.included(typeName, name, "<always: groups/roles>");
        }
        if (!syncIncludeNamePatterns.isEmpty()) {
            for (NamePattern pattern : syncIncludeNamePatterns) {
                if (pattern.matches(name)) {
                    return excludeDecision(typeName, name, pattern.getOriginal());
                }
            }
            return SyncDecision.skippedByInclude(typeName, name, getSyncIncludeNamePatternsRaw());
        }
        return excludeDecision(typeName, name, "<all>");
    }

    /**
     * Returns the customization-type cache decision used after name include/exclude rules.
     *
     * <p>Blank customization metadata is still included for non-customizable/catalog objects such
     * as Group, Role, Report and Template. For normal customizable definitions,
     * blank metadata is treated as unknown/base-like and follows the Base checkbox. This keeps BMC
     * base definitions out of the cache by default while not losing data-backed catalog rows.</p>
     */
    public SyncDecision getCustomizationSyncDecision(String typeName, String objectName, String customizationType) {
        String name = objectName == null ? "" : objectName;
        String normalized = normalizeCustomizationType(customizationType);
        if (normalized.length() == 0) {
            if (isNonCustomizableCatalogType(typeName)) {
                return SyncDecision.included(typeName, name, "<always: non-customizable catalog/data object>");
            }
            normalized = "base";
        }
        if (syncCacheCustomizationTypes.contains(normalized)) {
            return SyncDecision.included(typeName, name, "customization=" + normalized);
        }
        return SyncDecision.skippedByCustomization(typeName, name, normalized, getSyncCacheCustomizationTypesRaw());
    }

    private boolean isAlwaysSyncedType(String typeName) {
        return isNonCustomizableCatalogType(typeName);
    }

    public static boolean isNonCustomizableCatalogType(String typeName) {
        return ObjectTypeRegistry.isAlwaysCachedWithoutCustomization(typeName);
    }

    private SyncDecision excludeDecision(String typeName, String objectName, String includePattern) {
        String name = objectName == null ? "" : objectName;
        for (NamePattern pattern : syncExcludeNamePatterns) {
            if (pattern.matches(name)) {
                return SyncDecision.skippedByExclude(typeName, name, includePattern, pattern.getOriginal());
            }
        }
        return SyncDecision.included(typeName, name, includePattern);
    }

    public String describeSyncFilters() {
        String include = getSyncIncludeNamePatternsRaw().trim();
        String exclude = getSyncExcludeNamePatternsRaw().trim();
        return "include=" + (include.length() == 0 ? "<all>" : include)
                + ", exclude=" + (exclude.length() == 0 ? "<none>" : exclude)
                + ", cache customization=" + describeSyncCacheCustomizationTypes();
    }

    public String describeSyncCacheCustomizationTypes() {
        if (syncCacheCustomizationTypes.isEmpty()) {
            return "<none>";
        }
        ArrayList<String> labels = new ArrayList<String>();
        if (syncCacheCustomizationTypes.contains("base")) labels.add("Base");
        if (syncCacheCustomizationTypes.contains("custom")) labels.add("Custom");
        if (syncCacheCustomizationTypes.contains("overlay")) labels.add("Overlay");
        return join(labels, ", ");
    }

    public Set<Integer> getIgnoreMaskIds() {
        return ignoreMaskIds;
    }

    public boolean shouldIgnoreDifferenceName(String name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        String normalized = normalize(name);
        for (String token : ignoreDifferenceNameContains) {
            if (normalized.indexOf(token) >= 0) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldIgnoreFingerprintMember(String name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        String normalized = normalize(name);
        for (String token : ignoreFingerprintMemberNameContains) {
            if (normalized.indexOf(token) >= 0) {
                return true;
            }
        }
        return false;
    }


    public String getIgnoreDifferenceNameContainsRaw() {
        StringBuilder builder = new StringBuilder();
        for (String value : ignoreDifferenceNameContains) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    public String getIgnoreFingerprintMemberNameContainsRaw() {
        StringBuilder builder = new StringBuilder();
        for (String value : ignoreFingerprintMemberNameContains) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    public String describeLocation() {
        return sourceDescription;
    }

    private static Properties defaultProperties() {
        Properties props = new Properties();
        props.setProperty(KEY_USE_BMC_DEFAULT_IGNORES, "false");
        props.setProperty(KEY_FORCE_RELOAD_OBJECTS, "true");
        props.setProperty(KEY_DEEP_COMPARE_FORMS, "true");
        props.setProperty(KEY_CACHE_OBJECTS, "true");
        props.setProperty(KEY_FINGERPRINT_FALLBACK, "true");
        props.setProperty(KEY_COMPARE_OBJECT_TIMEOUT_SECONDS, "180");
        props.setProperty(KEY_SHOW_EQUAL_BY_DEFAULT, "false");
        props.setProperty(KEY_MAX_SUMMARY_ITEMS, "10");
        props.setProperty(KEY_SEARCH_MAX_RESULTS, "1000");
        props.setProperty(KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS, "60");
        props.setProperty(KEY_METADATA_AGGRESSIVE_ENUMERATION, "false");
        props.setProperty(KEY_SYNC_INCLUDE_NAME_PATTERNS, "ARS:*");
        props.setProperty(KEY_SYNC_EXCLUDE_NAME_PATTERNS, "");
        props.setProperty(KEY_SYNC_CACHE_CUSTOMIZATION_TYPES, "custom,overlay");
        props.setProperty(KEY_SYNC_FORCE_REFRESH_DEFINITIONS, "false");
        props.setProperty(KEY_SYNC_DEEP_CACHE_BINARY_OBJECTS, "false");
        props.setProperty(KEY_SYNC_DEEP_CACHE_EXPENSIVE_OBJECTS, "false");
        props.setProperty(KEY_SYNC_INCREMENTAL_DEFINITIONS, "true");
        props.setProperty(KEY_SYNC_RETRY_ERROR_SNAPSHOTS, "false");
        props.setProperty(KEY_IGNORE_MASK_IDS, "");
        props.setProperty(KEY_IGNORE_DIFFERENCE_NAME_CONTAINS, "Last Update,Last Changed,Modified Date,Timestamp");
        props.setProperty(KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS, "store,changeListener,loadedActiveLinks,deletedFields,deletedViews,dirty,state");
        return props;
    }

    private static void loadPreferenceStore(Properties props) {
        Activator plugin = Activator.getDefault();
        if (plugin == null) {
            return;
        }
        IPreferenceStore store = plugin.getPreferenceStore();
        initializeDefaults(store);
        loadLegacyPreferenceStore(props);
        copyPreference(store, props, KEY_USE_BMC_DEFAULT_IGNORES);
        copyPreference(store, props, KEY_FORCE_RELOAD_OBJECTS);
        copyPreference(store, props, KEY_DEEP_COMPARE_FORMS);
        copyPreference(store, props, KEY_CACHE_OBJECTS);
        copyPreference(store, props, KEY_FINGERPRINT_FALLBACK);
        copyPreference(store, props, KEY_COMPARE_OBJECT_TIMEOUT_SECONDS);
        copyPreference(store, props, KEY_SHOW_EQUAL_BY_DEFAULT);
        copyPreference(store, props, KEY_MAX_SUMMARY_ITEMS);
        copyPreference(store, props, KEY_SEARCH_MAX_RESULTS);
        copyPreference(store, props, KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS);
        copyPreference(store, props, KEY_METADATA_AGGRESSIVE_ENUMERATION);
        copyPreference(store, props, KEY_SYNC_INCLUDE_NAME_PATTERNS);
        copyPreference(store, props, KEY_SYNC_EXCLUDE_NAME_PATTERNS);
        copyPreference(store, props, KEY_SYNC_CACHE_CUSTOMIZATION_TYPES);
        copyPreference(store, props, KEY_SYNC_FORCE_REFRESH_DEFINITIONS);
        copyPreference(store, props, KEY_SYNC_DEEP_CACHE_BINARY_OBJECTS);
        copyPreference(store, props, KEY_SYNC_DEEP_CACHE_EXPENSIVE_OBJECTS);
        copyPreference(store, props, KEY_SYNC_INCREMENTAL_DEFINITIONS);
        copyPreference(store, props, KEY_SYNC_RETRY_ERROR_SNAPSHOTS);
        copyPreference(store, props, KEY_IGNORE_MASK_IDS);
        copyPreference(store, props, KEY_IGNORE_DIFFERENCE_NAME_CONTAINS);
        copyPreference(store, props, KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS);
    }

    private static void copyPreference(IPreferenceStore store, Properties props, String key) {
        if (store.isDefault(key)) {
            // Keep the default or a value imported from the previous se.yrell.helix.migrator preference node.
            return;
        }
        String value = store.getString(key);
        if (value != null) {
            props.setProperty(key, value);
        }
    }

    private static void loadLegacyPreferenceStore(Properties props) {
        try {
            Preferences legacy = InstanceScope.INSTANCE.getNode(LEGACY_PLUGIN_ID);
            if (legacy == null) {
                return;
            }
            String[] keys = preferenceKeys();
            for (int i = 0; i < keys.length; i++) {
                String value = legacy.get(keys[i], null);
                if (value != null) {
                    props.setProperty(keys[i], value);
                }
            }
        } catch (Throwable ignored) {
            // Preferences migration must never block startup.
        }
    }

    private static String[] preferenceKeys() {
        return new String[] {
                KEY_USE_BMC_DEFAULT_IGNORES,
                KEY_FORCE_RELOAD_OBJECTS,
                KEY_DEEP_COMPARE_FORMS,
                KEY_CACHE_OBJECTS,
                KEY_FINGERPRINT_FALLBACK,
                KEY_COMPARE_OBJECT_TIMEOUT_SECONDS,
                KEY_SHOW_EQUAL_BY_DEFAULT,
                KEY_MAX_SUMMARY_ITEMS,
                KEY_SEARCH_MAX_RESULTS,
                KEY_METADATA_ENUMERATION_TIMEOUT_SECONDS,
                KEY_METADATA_AGGRESSIVE_ENUMERATION,
                KEY_SYNC_INCLUDE_NAME_PATTERNS,
                KEY_SYNC_EXCLUDE_NAME_PATTERNS,
                KEY_SYNC_CACHE_CUSTOMIZATION_TYPES,
                KEY_SYNC_FORCE_REFRESH_DEFINITIONS,
                KEY_SYNC_DEEP_CACHE_BINARY_OBJECTS,
                KEY_SYNC_DEEP_CACHE_EXPENSIVE_OBJECTS,
                KEY_SYNC_INCREMENTAL_DEFINITIONS,
                KEY_SYNC_RETRY_ERROR_SNAPSHOTS,
                KEY_IGNORE_MASK_IDS,
                KEY_IGNORE_DIFFERENCE_NAME_CONTAINS,
                KEY_IGNORE_FINGERPRINT_MEMBER_NAME_CONTAINS
        };
    }

    private static File findConfigFile() {
        String explicit = System.getProperty(SYSTEM_PROPERTY);
        if (explicit == null || explicit.trim().length() == 0) {
            explicit = System.getProperty(LEGACY_SYSTEM_PROPERTY);
        }
        if (explicit == null || explicit.trim().length() == 0) {
            explicit = System.getProperty(OLDEST_LEGACY_SYSTEM_PROPERTY);
        }
        if (explicit != null && explicit.trim().length() > 0) {
            return new File(explicit.trim());
        }

        String env = System.getenv(ENV_PROPERTY);
        if (env == null || env.trim().length() == 0) {
            env = System.getenv(LEGACY_ENV_PROPERTY);
        }
        if (env == null || env.trim().length() == 0) {
            env = System.getenv(OLDEST_LEGACY_ENV_PROPERTY);
        }
        if (env != null && env.trim().length() > 0) {
            return new File(env.trim());
        }

        File installAreaFile = getInstallAreaConfigFile();
        if (installAreaFile != null && installAreaFile.isFile()) {
            return installAreaFile;
        }
        File installArea = getInstallAreaDirectory();
        if (installArea != null) {
            File legacyInstallAreaFile = new File(installArea, LEGACY_CONFIG_FILE_NAME);
            if (legacyInstallAreaFile.isFile()) {
                return legacyInstallAreaFile;
            }
            File oldestLegacyInstallAreaFile = new File(installArea, OLDEST_LEGACY_CONFIG_FILE_NAME);
            if (oldestLegacyInstallAreaFile.isFile()) {
                return oldestLegacyInstallAreaFile;
            }
        }

        String home = System.getProperty("user.home");
        if (home != null && home.trim().length() > 0) {
            File homeFile = new File(new File(home, ".yrell-migrator"), CONFIG_FILE_NAME);
            if (homeFile.isFile()) {
                return homeFile;
            }
            File legacyHomeFile = new File(new File(home, ".helix-migrator"), LEGACY_CONFIG_FILE_NAME);
            if (legacyHomeFile.isFile()) {
                return legacyHomeFile;
            }
            File oldestLegacyHomeFile = new File(new File(home, ".helix-compare"), OLDEST_LEGACY_CONFIG_FILE_NAME);
            if (oldestLegacyHomeFile.isFile()) {
                return oldestLegacyHomeFile;
            }
        }

        if (Boolean.getBoolean(ALLOW_WORKING_DIRECTORY_CONFIG_PROPERTY) || Boolean.getBoolean(LEGACY_ALLOW_WORKING_DIRECTORY_CONFIG_PROPERTY)) {
            File workingDirFile = new File(CONFIG_FILE_NAME);
            if (workingDirFile.isFile()) {
                return workingDirFile;
            }
            File legacyWorkingDirFile = new File(LEGACY_CONFIG_FILE_NAME);
            if (legacyWorkingDirFile.isFile()) {
                return legacyWorkingDirFile;
            }
            File oldestLegacyWorkingDirFile = new File(OLDEST_LEGACY_CONFIG_FILE_NAME);
            if (oldestLegacyWorkingDirFile.isFile()) {
                return oldestLegacyWorkingDirFile;
            }
        }
        return null;
    }

    private static File getInstallAreaDirectory() {
        String installArea = System.getProperty("osgi.install.area");
        if (installArea == null || installArea.trim().length() == 0) {
            installArea = System.getProperty("eclipse.home.location");
        }
        return toFileLocation(installArea);
    }

    /**
     * Converts Eclipse/OSGi file locations to real file-system paths.
     *
     * <p>Developer Studio on Windows may expose the install area as normal paths,
     * {@code file:/C:/...}, {@code file:///C:/...} or the less URI-friendly
     * {@code file:\C:\...}. Passing the last form directly to {@link File} turns
     * it into a relative path such as
     * {@code C:\DeveloperStudio\file:\C:\DeveloperStudio\...}. This helper
     * normalises all those variants before callers append config file names.</p>
     */
    public static File toFileLocation(String location) {
        if (location == null) {
            return null;
        }
        String value = location.trim();
        if (value.length() == 0) {
            return null;
        }

        if (value.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                URI uri = URI.create(value.replace('\\', '/'));
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    return new File(uri).getAbsoluteFile();
                }
            } catch (Throwable ignored) {
                // Fall through to the Windows-friendly cleanup below.
            }

            String path = value.substring(5);
            while (path.startsWith("///")) {
                path = path.substring(2);
            }
            if (path.startsWith("//") && path.length() > 3 && path.charAt(3) == ':') {
                path = path.substring(2);
            } else if (path.startsWith("/") && path.length() > 2 && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            path = path.replace('/', File.separatorChar);
            return new File(path).getAbsoluteFile();
        }

        try {
            URI uri = URI.create(value);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return new File(uri).getAbsoluteFile();
            }
        } catch (Throwable ignored) {
            // Plain path handling below.
        }
        return new File(value).getAbsoluteFile();
    }

    private static void loadFile(File file, Properties props) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
        } catch (IOException ex) {
            Activator.logWarning("Could not read Yrell Migrator config file: " + file.getAbsolutePath(), ex);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean getBoolean(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        value = value.trim();
        if (value.length() == 0) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static int getInt(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().length() == 0) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static Set<Integer> parseIntegerSet(String value) {
        LinkedHashSet<Integer> result = new LinkedHashSet<Integer>();
        if (value == null || value.trim().length() == 0) {
            return Collections.unmodifiableSet(result);
        }
        String[] parts = value.split("[,;]");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.length() == 0) {
                continue;
            }
            try {
                result.add(Integer.valueOf(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static List<String> parseStringList(String value) {
        ArrayList<String> result = new ArrayList<String>();
        if (value == null || value.trim().length() == 0) {
            return Collections.unmodifiableList(result);
        }
        String[] parts = value.split("[,;]");
        for (int i = 0; i < parts.length; i++) {
            String normalized = normalize(parts[i]);
            if (normalized.length() > 0) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static Set<String> parseCustomizationTypeSet(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        String raw = value == null ? "" : value;
        String[] parts = raw.split("[,;\n\r]");
        for (int i = 0; i < parts.length; i++) {
            String normalized = normalizeCustomizationType(parts[i]);
            if (normalized.length() > 0) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static String normalizeCustomizationType(Object value) {
        if (value == null) {
            return "";
        }
        String lower = String.valueOf(value).trim().toLowerCase(Locale.ENGLISH);
        if (lower.length() == 0 || "unknown".equals(lower) || "null".equals(lower)) {
            return "";
        }
        if (lower.indexOf("custom") >= 0 || "4".equals(lower)) {
            return "custom";
        }
        if (lower.indexOf("overlay") >= 0 || lower.indexOf("overlaid") >= 0 || "2".equals(lower)) {
            return "overlay";
        }
        if (lower.indexOf("base") >= 0 || lower.indexOf("origin") >= 0 || "none".equals(lower) || "0".equals(lower)) {
            return "base";
        }
        return "";
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.length() == 0) continue;
                if (builder.length() > 0) builder.append(separator);
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static List<NamePattern> parseNamePatterns(String value) {
        ArrayList<NamePattern> result = new ArrayList<NamePattern>();
        if (value == null || value.trim().length() == 0) {
            return Collections.unmodifiableList(result);
        }
        String[] parts = value.split("[,;\n\r]");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (part.length() > 0) {
                result.add(new NamePattern(part));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }

    public static final class SyncDecision {
        private final boolean included;
        private final String typeName;
        private final String objectName;
        private final String reason;
        private final String includePattern;
        private final String excludePattern;
        private final String customizationType;

        private SyncDecision(boolean included, String typeName, String objectName, String reason, String includePattern, String excludePattern) {
            this(included, typeName, objectName, reason, includePattern, excludePattern, "");
        }

        private SyncDecision(boolean included, String typeName, String objectName, String reason, String includePattern, String excludePattern, String customizationType) {
            this.included = included;
            this.typeName = typeName == null ? "" : typeName;
            this.objectName = objectName == null ? "" : objectName;
            this.reason = reason == null ? "" : reason;
            this.includePattern = includePattern == null ? "" : includePattern;
            this.excludePattern = excludePattern == null ? "" : excludePattern;
            this.customizationType = customizationType == null ? "" : customizationType;
        }

        public static SyncDecision included(String typeName, String objectName, String includePattern) {
            return new SyncDecision(true, typeName, objectName, "included", includePattern, "");
        }

        public static SyncDecision skippedByInclude(String typeName, String objectName, String includePatterns) {
            return new SyncDecision(false, typeName, objectName, "not matched by include filter", includePatterns, "");
        }

        public static SyncDecision skippedByExclude(String typeName, String objectName, String includePattern, String excludePattern) {
            return new SyncDecision(false, typeName, objectName, "matched by exclude filter", includePattern, excludePattern);
        }

        public static SyncDecision skippedByCustomization(String typeName, String objectName, String customizationType, String enabledTypes) {
            return new SyncDecision(false, typeName, objectName,
                    "customization type not selected for cache", enabledTypes, "", customizationType);
        }

        public boolean isIncluded() { return included; }
        public String getTypeName() { return typeName; }
        public String getObjectName() { return objectName; }
        public String getReason() { return reason; }
        public String getIncludePattern() { return includePattern; }
        public String getExcludePattern() { return excludePattern; }
        public String getCustomizationType() { return customizationType; }
    }

    private static final class NamePattern {
        private final String original;
        private final String prefix;
        private final String regex;

        NamePattern(String pattern) {
            this.original = pattern == null ? "" : pattern.trim();
            boolean hasWildcard = original.indexOf('*') >= 0 || original.indexOf('%') >= 0
                    || original.indexOf('?') >= 0 || original.indexOf('_') >= 0;
            if (hasWildcard) {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < original.length(); i++) {
                    char c = original.charAt(i);
                    if (c == '*' || c == '%') {
                        builder.append(".*");
                    } else if (c == '?' || c == '_') {
                        builder.append('.');
                    } else {
                        if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                            builder.append('\\');
                        }
                        builder.append(c);
                    }
                }
                this.regex = "(?i)^" + builder.toString() + "$";
                this.prefix = null;
            } else {
                this.regex = null;
                this.prefix = normalize(original);
            }
        }

        String getOriginal() {
            return original;
        }

        boolean matches(String value) {
            if (original.length() == 0 || "*".equals(original) || "%".equals(original)) {
                return true;
            }
            String text = value == null ? "" : value;
            if (regex != null) {
                return text.matches(regex);
            }
            return text.toLowerCase(Locale.ENGLISH).startsWith(prefix);
        }
    }
}
