package se.yrell.migrator.bmc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.bmc.arsys.studio.model.type.IModelType;

/**
 * Central registry for Developer Studio object-type capabilities used by Yrell Migrator.
 *
 * <p>The registry keeps all type labels, BMC type classes, migration phase hints and cache-policy
 * hints in one place. Developer Studio internals move between releases, so all type resolution is
 * best-effort and reflection based.</p>
 */
public final class ObjectTypeRegistry {
    private static final List<ObjectTypeInfo> TYPES;

    static {
        List<ObjectTypeInfo> list = new ArrayList<ObjectTypeInfo>();
        add(list, "Form", phase("Forms"), true, true, true, false, "FormType");
        add(list, "Active Link", phase("Workflow"), true, true, true, false, "ActiveLinkType");
        add(list, "Filter", phase("Workflow"), true, true, true, false, "FilterType");
        add(list, "Escalation", phase("Workflow"), true, true, true, false, "EscalationType");
        add(list, "Menu", phase("Menus"), true, true, true, false, "MenuType");
        add(list, "Active Link Guide", phase("Guides"), true, true, true, false, "ActiveLinkGuideType");
        add(list, "Filter Guide", phase("Guides"), true, true, true, false, "FilterGuideType");
        add(list, "Web Service", phase("Other definitions"), true, true, true, false, "WebServiceType");
        add(list, "Application", phase("Containers"), true, true, true, false, "ApplicationType");
        add(list, "Packing List", phase("Containers"), true, true, true, false, "PackingListType");
        add(list, "Association", phase("Associations"), true, true, true, false,
                "AssociationType", "com.bmc.arsys.studio.model.type.providers.AssociationTypeProvider");
        add(list, "Image", phase("Images / Support files"), false, true, true, true, "ImageType");
        add(list, "Support File", phase("Images / Support files"), false, true, false, false, "SupportFileType");
        add(list, "Flashboard", phase("Other definitions"), true, true, true, false, "FlashboardType");
        add(list, "Flashboard Variable", phase("Other definitions"), true, true, true, false, "FlashboardVariableType");
        add(list, "Flashboard Data Source", phase("Other definitions"), true, true, true, false, "FlashboardDataSourceType");
        add(list, "Flashboard Alarm", phase("Other definitions"), true, true, true, false, "FlashboardAlarmType");
        add(list, "Data Visualization Definition", phase("Other definitions"), true, true, true, false, "DataVisualizationDefinitionType");
        add(list, "Data Visualization Module", phase("Other definitions"), true, true, true, false, "DataVisualizationModuleType");
        add(list, "Distributed Map", phase("Other definitions"), true, true, true, false, "DistributedMapType");
        add(list, "Distributed Pool", phase("Other definitions"), true, true, true, false, "DistributedPoolType");
        add(list, "Group", phase("Groups / Roles"), false, true, true, true, "GroupType");
        add(list, "Role", phase("Groups / Roles"), false, true, true, true, "RoleType");
        add(list, "Message", phase("Catalog data"), false, true, true, true, "MessageType");
        add(list, "Report", phase("Catalog data"), false, true, true, true, "ReportType");
        add(list, "Template", phase("Catalog data"), false, true, true, true, "TemplateType");
        TYPES = Collections.unmodifiableList(list);
    }

    private ObjectTypeRegistry() {
    }

    private static String phase(String phase) {
        return phase == null ? "Other definitions" : phase;
    }

    private static void add(List<ObjectTypeInfo> list, String label, String phase, boolean customizationAware,
            boolean migratable, boolean deepSnapshot, boolean alwaysCacheWithoutCustomization, String... typeClassNames) {
        list.add(new ObjectTypeInfo(label, phase, customizationAware, migratable, deepSnapshot,
                alwaysCacheWithoutCustomization, typeClassNames));
    }

    public static List<ObjectTypeInfo> all() {
        return TYPES;
    }

    public static ObjectTypeInfo byLabel(String label) {
        String normalized = normalize(label);
        for (ObjectTypeInfo info : TYPES) {
            if (normalize(info.getLabel()).equals(normalized)) {
                return info;
            }
        }
        return null;
    }

    public static ObjectTypeInfo byTypeName(String typeName) {
        String normalized = normalize(typeName);
        for (ObjectTypeInfo info : TYPES) {
            if (normalize(info.getLabel()).equals(normalized)) {
                return info;
            }
            for (String className : info.getTypeClassNames()) {
                String simple = className;
                int dot = simple.lastIndexOf('.');
                if (dot >= 0) {
                    simple = simple.substring(dot + 1);
                }
                String withoutType = simple.endsWith("Type") ? simple.substring(0, simple.length() - 4) : simple;
                if (normalize(withoutType).equals(normalized) || normalize(simple).equals(normalized)) {
                    return info;
                }
            }
        }
        return null;
    }

    public static boolean isCustomizationAware(String typeName) {
        ObjectTypeInfo info = byTypeName(typeName);
        if (info != null) {
            return info.isCustomizationAware();
        }
        String lower = normalize(typeName);
        return lower.indexOf("form") >= 0
                || lower.indexOf("activelink") >= 0
                || lower.indexOf("filter") >= 0
                || lower.indexOf("escalation") >= 0
                || lower.indexOf("menu") >= 0
                || lower.indexOf("guide") >= 0
                || lower.indexOf("webservice") >= 0
                || lower.indexOf("application") >= 0
                || lower.indexOf("packinglist") >= 0
                || lower.indexOf("datavisualization") >= 0
                || lower.indexOf("association") >= 0;
    }

    public static boolean isAlwaysCachedWithoutCustomization(String typeName) {
        ObjectTypeInfo info = byTypeName(typeName);
        if (info != null) {
            return info.isAlwaysCacheWithoutCustomization();
        }
        String lower = normalize(typeName);
        return lower.equals("image") || lower.equals("imagetype")
                || lower.equals("group") || lower.equals("grouptype")
                || lower.equals("role") || lower.equals("roletype")
                || lower.equals("message") || lower.equals("messagetype")
                || lower.equals("report") || lower.equals("reporttype")
                || lower.equals("template") || lower.equals("templatetype");
    }

    public static boolean isAlwaysShownRegardlessOfCustomizationFilter(String typeName) {
        String lower = normalize(typeName);
        return lower.equals("image") || lower.equals("imagetype")
                || lower.equals("group") || lower.equals("grouptype")
                || lower.equals("role") || lower.equals("roletype")
                || lower.equals("report") || lower.equals("reporttype")
                || lower.equals("template") || lower.equals("templatetype");
    }

    public static String phaseFor(String typeName) {
        ObjectTypeInfo info = byTypeName(typeName);
        return info == null ? "Other definitions" : info.getMigrationPhase();
    }

    public static List<IModelType> resolveTypes(ObjectTypeInfo info) {
        if (info == null) {
            return Collections.emptyList();
        }
        Set<IModelType> types = new LinkedHashSet<IModelType>();
        for (String name : info.getTypeClassNames()) {
            IModelType type = resolve(name);
            if (type != null) {
                types.add(type);
            }
        }
        return new ArrayList<IModelType>(types);
    }

    public static IModelType resolve(String simpleClassName) {
        String className = simpleClassName.indexOf('.') >= 0 ? simpleClassName : "com.bmc.arsys.studio.model.type." + simpleClassName;
        try {
            Class<?> clazz = Class.forName(className);
            try {
                Method method = clazz.getMethod("getInstance", new Class[0]);
                Object value = method.invoke(null, new Object[0]);
                if (value instanceof IModelType) {
                    return (IModelType) value;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object provider = clazz.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
                Method method = clazz.getMethod("getType", new Class[0]);
                Object value = method.invoke(provider, new Object[0]);
                if (value instanceof IModelType) {
                    return (IModelType) value;
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static RegistryStatus status() {
        int resolved = 0;
        int unresolved = 0;
        StringBuilder missing = new StringBuilder();
        String associationStatus = "not resolved";
        for (ObjectTypeInfo info : TYPES) {
            boolean hasType = !resolveTypes(info).isEmpty();
            if (hasType) {
                resolved++;
            } else {
                unresolved++;
                if (missing.length() < 600) {
                    if (missing.length() > 0) missing.append(", ");
                    missing.append(info.getLabel());
                }
            }
            if ("Association".equals(info.getLabel())) {
                associationStatus = hasType ? "resolved as own object type" : "not exposed by this Developer Studio build";
            }
        }
        return new RegistryStatus(resolved, unresolved, missing.toString(), associationStatus);
    }

    public static String cachePolicyFor(String typeName, String customizationSummary) {
        String normalizedType = normalize(typeName);
        String customization = customizationSummary == null ? "" : customizationSummary.trim().toLowerCase(Locale.ENGLISH);
        if (isAlwaysCachedWithoutCustomization(typeName)) {
            return "Included because " + (typeName == null || typeName.length() == 0 ? "this object type" : typeName)
                    + " has no customization type.";
        }
        if (customization.indexOf("custom") >= 0) {
            return "Included when cache policy includes Custom.";
        }
        if (customization.indexOf("overlay") >= 0 || customization.indexOf("overlaid") >= 0) {
            return "Included when cache policy includes Overlay.";
        }
        if (customization.indexOf("base") >= 0 || customization.length() == 0) {
            return "Base/unknown customization; included only when cache policy includes Base.";
        }
        return "Customization policy depends on resolved type metadata." + (normalizedType.length() == 0 ? "" : " Type=" + typeName + ".");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH).replace(" ", "").replace("_", "");
    }

    public static final class ObjectTypeInfo {
        private final String label;
        private final String migrationPhase;
        private final boolean customizationAware;
        private final boolean migratable;
        private final boolean deepSnapshot;
        private final boolean alwaysCacheWithoutCustomization;
        private final List<String> typeClassNames;

        ObjectTypeInfo(String label, String migrationPhase, boolean customizationAware, boolean migratable,
                boolean deepSnapshot, boolean alwaysCacheWithoutCustomization, String[] typeClassNames) {
            this.label = label == null ? "" : label;
            this.migrationPhase = migrationPhase == null ? "Other definitions" : migrationPhase;
            this.customizationAware = customizationAware;
            this.migratable = migratable;
            this.deepSnapshot = deepSnapshot;
            this.alwaysCacheWithoutCustomization = alwaysCacheWithoutCustomization;
            List<String> names = new ArrayList<String>();
            if (typeClassNames != null) {
                for (String typeClassName : typeClassNames) {
                    if (typeClassName != null && typeClassName.trim().length() > 0) {
                        names.add(typeClassName.trim());
                    }
                }
            }
            this.typeClassNames = Collections.unmodifiableList(names);
        }

        public String getLabel() { return label; }
        public String getMigrationPhase() { return migrationPhase; }
        public boolean isCustomizationAware() { return customizationAware; }
        public boolean isMigratable() { return migratable; }
        public boolean isDeepSnapshot() { return deepSnapshot; }
        public boolean isAlwaysCacheWithoutCustomization() { return alwaysCacheWithoutCustomization; }
        public List<String> getTypeClassNames() { return typeClassNames; }
    }

    public static final class RegistryStatus {
        public final int resolved;
        public final int unresolved;
        public final String unresolvedLabels;
        public final String associationStatus;

        RegistryStatus(int resolved, int unresolved, String unresolvedLabels, String associationStatus) {
            this.resolved = resolved;
            this.unresolved = unresolved;
            this.unresolvedLabels = unresolvedLabels == null ? "" : unresolvedLabels;
            this.associationStatus = associationStatus == null ? "" : associationStatus;
        }
    }
}
