package se.yrell.migrator.bmc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bmc.arsys.studio.model.type.IModelType;

/** User-facing group of AR object types for broad search/compare. */
public final class BmcTypeGroup {
    private final String label;
    private final List<IModelType> types;

    public BmcTypeGroup(String label, List<IModelType> types) {
        this.label = label == null ? "" : label;
        this.types = Collections.unmodifiableList(new ArrayList<IModelType>(types));
    }

    public String getLabel() {
        return label;
    }

    public List<IModelType> getTypes() {
        return types;
    }

    @Override
    public String toString() {
        return label;
    }
}
