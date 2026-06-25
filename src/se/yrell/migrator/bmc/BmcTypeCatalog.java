package se.yrell.migrator.bmc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.bmc.arsys.studio.model.type.IModelType;

/** Resolves common Developer Studio object types through a central version-tolerant registry. */
public final class BmcTypeCatalog {
    private BmcTypeCatalog() {
    }

    public static List<BmcTypeGroup> getGroups() {
        List<BmcTypeGroup> groups = new ArrayList<BmcTypeGroup>();

        Set<IModelType> all = new LinkedHashSet<IModelType>();
        for (ObjectTypeRegistry.ObjectTypeInfo info : ObjectTypeRegistry.all()) {
            all.addAll(ObjectTypeRegistry.resolveTypes(info));
        }
        groups.add(new BmcTypeGroup("All object types", new ArrayList<IModelType>(all)));

        for (ObjectTypeRegistry.ObjectTypeInfo info : ObjectTypeRegistry.all()) {
            List<IModelType> resolved = ObjectTypeRegistry.resolveTypes(info);
            if (!resolved.isEmpty()) {
                groups.add(new BmcTypeGroup(info.getLabel(), resolved));
            }
        }

        groups.add(group("Definitions - common", "FormType", "ActiveLinkType", "FilterType", "EscalationType", "MenuType", "ActiveLinkGuideType", "FilterGuideType", "WebServiceType", "ApplicationType", "PackingListType", "AssociationType", "com.bmc.arsys.studio.model.type.providers.AssociationTypeProvider"));
        groups.add(group("Workflow", "ActiveLinkType", "FilterType", "EscalationType", "ActiveLinkGuideType", "FilterGuideType"));
        groups.add(group("Applications / Packing lists", "ApplicationType", "PackingListType"));
        groups.add(group("Associations", "AssociationType", "com.bmc.arsys.studio.model.type.providers.AssociationTypeProvider"));
        groups.add(group("Configuration / data", "DataType", "DefinitionFormDataType"));
        groups.add(group("Images / Support files", "ImageType", "SupportFileType"));
        return groups;
    }

    public static BmcTypeGroup group(String label, String... typeClassNames) {
        Set<IModelType> types = new LinkedHashSet<IModelType>();
        if (typeClassNames != null) {
            for (int i = 0; i < typeClassNames.length; i++) {
                IModelType type = ObjectTypeRegistry.resolve(typeClassNames[i]);
                if (type != null) {
                    types.add(type);
                }
            }
        }
        return new BmcTypeGroup(label, new ArrayList<IModelType>(types));
    }
}
