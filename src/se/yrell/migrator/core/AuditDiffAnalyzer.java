package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies audit-related diff rows into actionable operator guidance.
 *
 * Audit metadata often spans the main form, the generated audit form and field
 * mappings. This helper keeps those signals separate so a post-migration
 * "Still different" warning can tell the operator whether to inspect audit
 * enablement, audit form references, field mapping, style/mode or permissions.
 */
public final class AuditDiffAnalyzer {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?<![A-Za-z0-9])-?\\d{2,12}(?![A-Za-z0-9])");

    private AuditDiffAnalyzer() {
    }

    public static Summary analyze(List<DiffDetail> details) {
        Summary summary = new Summary();
        if (details == null) {
            return summary;
        }
        for (DiffDetail detail : details) {
            if (detail == null || !isLikelyAudit(detail)) {
                continue;
            }
            summary.auditRows++;
            String area = lower(detail.getArea());
            String prop = lower(detail.getProperty());
            String kind = lower(detail.getKind());
            String source = lower(detail.getSourceValue());
            String target = lower(detail.getTargetValue());
            String combined = area + " " + prop + " " + kind + " " + source + " " + target;

            boolean enabled = containsAny(combined, "enabled", "disabled", "enable audit", "audit enable", "audit state");
            boolean formReference = containsAny(combined, "audit form", "auditschema", "audit schema", "schema name", "form name", "auditform");
            boolean styleOrMode = containsAny(combined, "style", "mode", "type", "audit option", "audit trail");
            boolean fieldMapping = containsAny(combined, "field", "mapping", "map", "field id", "fieldid");
            boolean permissions = containsAny(combined, "permission", "group", "group id", "groupid");
            boolean orderOrFormat = containsAny(combined, "order", "index", "format", "sequence", "position");

            if (enabled) summary.enabledRows++;
            if (formReference) summary.auditFormRows++;
            if (styleOrMode) summary.styleRows++;
            if (fieldMapping) summary.fieldMappingRows++;
            if (permissions) summary.permissionRows++;
            if (orderOrFormat) summary.orderOrFormatRows++;

            if (source.length() == 0 && target.length() > 0) summary.targetOnlyRows++;
            if (source.length() > 0 && target.length() == 0) summary.sourceOnlyRows++;
            if (valuesMatchIgnoringOrderAndFormatting(detail.getSourceValue(), detail.getTargetValue())) {
                summary.formattingOnlyRows++;
            }
            collectNumbers(summary.sourceIds, detail.getSourceValue());
            collectNumbers(summary.targetIds, detail.getTargetValue());
            collectNumbers(summary.contextIds, detail.getArea());
            collectNumbers(summary.contextIds, detail.getProperty());
        }
        summary.finish();
        return summary;
    }

    public static boolean isLikelyAudit(DiffDetail detail) {
        if (detail == null) {
            return false;
        }
        String area = lower(detail.getArea());
        String prop = lower(detail.getProperty());
        String kind = lower(detail.getKind());
        String source = lower(detail.getSourceValue());
        String target = lower(detail.getTargetValue());
        String combined = area + " " + prop + " " + kind + " " + source + " " + target;
        return combined.indexOf("audit") >= 0 || combined.indexOf("auditschema") >= 0;
    }

    private static boolean valuesMatchIgnoringOrderAndFormatting(String source, String target) {
        if (source == null || target == null || source.length() == 0 || target.length() == 0) {
            return false;
        }
        List<String> left = normalizedTokens(source);
        List<String> right = normalizedTokens(target);
        return !left.isEmpty() && left.equals(right);
    }

    private static List<String> normalizedTokens(String value) {
        List<String> tokens = new ArrayList<String>();
        String[] parts = safe(value).toLowerCase(Locale.ENGLISH).split("[^a-z0-9_-]+");
        for (String part : parts) {
            if (part.length() == 0 || "null".equals(part)) {
                continue;
            }
            tokens.add(part);
        }
        Collections.sort(tokens);
        return tokens;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && needle.length() > 0 && text.indexOf(needle) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void collectNumbers(Set<String> out, String text) {
        if (out == null || text == null || text.length() == 0) {
            return;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            if ("0".equals(value) || "1".equals(value)) {
                continue;
            }
            out.add(value);
        }
    }

    private static String lower(String value) {
        return safe(value).toLowerCase(Locale.ENGLISH);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class Summary {
        private int auditRows;
        private int enabledRows;
        private int auditFormRows;
        private int styleRows;
        private int fieldMappingRows;
        private int permissionRows;
        private int orderOrFormatRows;
        private int formattingOnlyRows;
        private int sourceOnlyRows;
        private int targetOnlyRows;
        private final Set<String> sourceIds = new LinkedHashSet<String>();
        private final Set<String> targetIds = new LinkedHashSet<String>();
        private final Set<String> contextIds = new LinkedHashSet<String>();
        private final Set<String> commonIds = new LinkedHashSet<String>();
        private final Set<String> sourceOnlyIds = new LinkedHashSet<String>();
        private final Set<String> targetOnlyIds = new LinkedHashSet<String>();

        private void finish() {
            commonIds.clear();
            commonIds.addAll(sourceIds);
            commonIds.retainAll(targetIds);
            sourceOnlyIds.clear();
            sourceOnlyIds.addAll(sourceIds);
            sourceOnlyIds.removeAll(targetIds);
            targetOnlyIds.clear();
            targetOnlyIds.addAll(targetIds);
            targetOnlyIds.removeAll(sourceIds);
        }

        public int getAuditRows() { return auditRows; }
        public int getEnabledRows() { return enabledRows; }
        public int getAuditFormRows() { return auditFormRows; }
        public int getStyleRows() { return styleRows; }
        public int getFieldMappingRows() { return fieldMappingRows; }
        public int getPermissionRows() { return permissionRows; }
        public int getOrderOrFormatRows() { return orderOrFormatRows; }
        public int getFormattingOnlyRows() { return formattingOnlyRows; }
        public int getSourceOnlyRows() { return sourceOnlyRows; }
        public int getTargetOnlyRows() { return targetOnlyRows; }
        public Set<String> getContextIds() { return Collections.unmodifiableSet(contextIds); }
        public Set<String> getSourceOnlyIds() { return Collections.unmodifiableSet(sourceOnlyIds); }
        public Set<String> getTargetOnlyIds() { return Collections.unmodifiableSet(targetOnlyIds); }

        public boolean hasRows() {
            return auditRows > 0;
        }

        public boolean hasFunctionalSignals() {
            return enabledRows > 0 || auditFormRows > 0 || styleRows > 0 || fieldMappingRows > 0
                    || permissionRows > 0 || sourceOnlyRows > 0 || targetOnlyRows > 0
                    || !sourceOnlyIds.isEmpty() || !targetOnlyIds.isEmpty();
        }

        public String oneLineRisk() {
            if (!hasRows()) {
                return "";
            }
            if (enabledRows > 0 || auditFormRows > 0 || fieldMappingRows > 0) {
                return "Audit risk: high - enablement, audit form reference or field mapping differs.";
            }
            if (styleRows > 0 || permissionRows > 0) {
                return "Audit risk: medium - audit mode/style or permissions differ.";
            }
            if (orderOrFormatRows > 0 || formattingOnlyRows > 0) {
                return "Audit risk: low/medium - order or serialization differences dominate.";
            }
            return "Audit risk: review required.";
        }

        public String toOperatorText() {
            if (!hasRows()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            out.append("- ").append(auditRows).append(" audit-related row(s) differ. ").append(oneLineRisk());
            if (enabledRows > 0) out.append(" Audit enabled/disabled state: ").append(enabledRows).append('.');
            if (auditFormRows > 0) out.append(" Audit form/schema reference: ").append(auditFormRows).append('.');
            if (styleRows > 0) out.append(" Style/type/mode: ").append(styleRows).append('.');
            if (fieldMappingRows > 0) out.append(" Field mapping/field metadata: ").append(fieldMappingRows).append('.');
            if (permissionRows > 0) out.append(" Audit permissions/groups: ").append(permissionRows).append('.');
            if (orderOrFormatRows > 0 || formattingOnlyRows > 0) {
                out.append(" Possible serialization/order signals: ").append(orderOrFormatRows)
                        .append(" order/format row(s), ").append(formattingOnlyRows).append(" normalized value match(es).");
            }
            if (!sourceOnlyIds.isEmpty()) {
                out.append("\n- Source-only audit-related IDs: ").append(sample(sourceOnlyIds, 10)).append('.');
            }
            if (!targetOnlyIds.isEmpty()) {
                out.append("\n- Target-only audit-related IDs: ").append(sample(targetOnlyIds, 10)).append('.');
            }
            if (!contextIds.isEmpty()) {
                out.append("\n- Audit context IDs seen in member/property names: ").append(sample(contextIds, 8)).append('.');
            }
            out.append("\n- Recommended check: verify the main form and its audit form together; some AR environments keep audit metadata in related form members.");
            if (enabledRows > 0 || auditFormRows > 0 || styleRows > 0 || fieldMappingRows > 0) {
                out.append(" If migration says 'Still different', refresh Developer Studio caches and compare the audit form reference plus audit field mapping explicitly.");
            }
            return out.toString();
        }

        private static String sample(Set<String> values, int max) {
            StringBuilder out = new StringBuilder();
            int added = 0;
            for (String value : values) {
                if (added >= max) {
                    out.append(", ...");
                    break;
                }
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(value);
                added++;
            }
            return out.toString();
        }
    }
}
