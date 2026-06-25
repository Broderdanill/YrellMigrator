package se.yrell.migrator.core;

/** A structured, UI-friendly difference row for one changed AR object. */
public final class DiffDetail {
    private final String area;
    private final String property;
    private final String sourceValue;
    private final String targetValue;
    private final String kind;

    public DiffDetail(String area, String property, String sourceValue, String targetValue, String kind) {
        this.area = nullToEmpty(area);
        this.property = nullToEmpty(property);
        this.sourceValue = nullToEmpty(sourceValue);
        this.targetValue = nullToEmpty(targetValue);
        this.kind = nullToEmpty(kind);
    }

    public String getArea() {
        return area;
    }

    public String getProperty() {
        return property;
    }

    public String getSourceValue() {
        return sourceValue;
    }

    public String getTargetValue() {
        return targetValue;
    }

    public String getKind() {
        return kind;
    }

    public String toSummary() {
        StringBuilder builder = new StringBuilder();
        if (area.length() > 0) {
            builder.append(area);
        }
        if (property.length() > 0) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(property);
        }
        if (builder.length() == 0) {
            builder.append(kind.length() == 0 ? "Difference" : kind);
        }
        return builder.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
