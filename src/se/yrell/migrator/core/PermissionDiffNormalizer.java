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
 * Normalizes permission/group related diff rows into operator-facing signals.
 *
 * AR/Developer Studio snapshots can serialize permissions in slightly different
 * orders or display formats even when the same group IDs are present. This class
 * does not hide those differences; it classifies them so the UI can tell the
 * operator whether the row looks like a real missing group or more like ordering
 * / formatting noise.
 */
public final class PermissionDiffNormalizer {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?<![A-Za-z0-9])-?\\d{2,12}(?![A-Za-z0-9])");

    private PermissionDiffNormalizer() {
    }

    public static Summary analyze(List<DiffDetail> details) {
        Summary summary = new Summary();
        if (details == null) {
            return summary;
        }
        for (DiffDetail detail : details) {
            if (detail == null || !isLikelyPermission(detail)) {
                continue;
            }
            summary.permissionRows++;

            String source = safe(detail.getSourceValue()).trim();
            String target = safe(detail.getTargetValue()).trim();
            String kind = lower(detail.getKind());
            String property = lower(detail.getProperty());
            String area = lower(detail.getArea());
            boolean sourceEmpty = source.length() == 0;
            boolean targetEmpty = target.length() == 0;
            boolean missingInTarget = kind.indexOf("missing in target") >= 0 || (!sourceEmpty && targetEmpty);
            boolean missingInSource = kind.indexOf("missing in source") >= 0 || (sourceEmpty && !targetEmpty);

            Set<String> sourceValueIds = extractPermissionIds(detail.getSourceValue());
            Set<String> targetValueIds = extractPermissionIds(detail.getTargetValue());
            Set<String> contextIds = extractContextPermissionIds(detail);
            summary.contextIds.addAll(contextIds);

            summary.sourceIds.addAll(sourceValueIds);
            summary.targetIds.addAll(targetValueIds);
            if (sourceValueIds.isEmpty() && targetValueIds.isEmpty()) {
                if (missingInTarget) {
                    summary.sourceIds.addAll(contextIds);
                } else if (missingInSource) {
                    summary.targetIds.addAll(contextIds);
                } else {
                    summary.sourceIds.addAll(contextIds);
                    summary.targetIds.addAll(contextIds);
                }
            } else {
                if (sourceValueIds.isEmpty() && !targetValueIds.isEmpty() && !missingInTarget) {
                    summary.sourceIds.addAll(contextIds);
                }
                if (targetValueIds.isEmpty() && !sourceValueIds.isEmpty() && !missingInSource) {
                    summary.targetIds.addAll(contextIds);
                }
            }

            if (missingInTarget) {
                summary.sourceOnlyRows++;
            } else if (missingInSource) {
                summary.targetOnlyRows++;
            } else {
                summary.changedRows++;
                if (valuesMatchIgnoringOrderAndFormatting(source, target)) {
                    summary.formattingOnlyRows++;
                } else if (sameNumberSet(source, target)) {
                    summary.sameIdDifferentDisplayRows++;
                }
            }
            if (property.indexOf("order") >= 0 || property.indexOf("sort") >= 0 || property.indexOf("index") >= 0
                    || kind.indexOf("order") >= 0 || kind.indexOf("format") >= 0
                    || area.indexOf("index") >= 0) {
                summary.orderRows++;
            }
        }
        summary.finish();
        return summary;
    }

    public static boolean isLikelyPermission(DiffDetail detail) {
        if (detail == null) {
            return false;
        }
        String area = lower(detail.getArea());
        String prop = lower(detail.getProperty());
        String kind = lower(detail.getKind());
        String combined = area + " " + prop + " " + kind;
        return area.startsWith("permission") || area.startsWith("permissions")
                || prop.indexOf("permission") >= 0 || prop.indexOf("group") >= 0
                || combined.indexOf("group id") >= 0 || combined.indexOf("groupid") >= 0;
    }

    /**
     * Extracts IDs that are likely to be permission/group identifiers from a value.
     * Field IDs are deliberately not inferred from generic member names here; that
     * prevents rows such as "Field 536870913 / permissionList" from looking like a
     * common group reference simply because the field id appears on both sides.
     */
    static Set<String> extractPermissionIds(String text) {
        Set<String> ids = new LinkedHashSet<String>();
        collectNumbers(ids, text);
        return ids;
    }

    private static Set<String> extractContextPermissionIds(DiffDetail detail) {
        Set<String> ids = new LinkedHashSet<String>();
        if (detail == null) {
            return ids;
        }
        String area = safe(detail.getArea());
        String prop = safe(detail.getProperty());
        String combined = (area + " " + prop).toLowerCase(Locale.ENGLISH);
        // Only use context numbers when the context itself looks like a group/
        // permission path. Avoid field/view/object ids that are not group ids.
        if ((combined.indexOf("group") >= 0 || combined.indexOf("permission") >= 0)
                && combined.indexOf("field ") < 0 && combined.indexOf("fieldid") < 0 && combined.indexOf("field id") < 0) {
            collectNumbers(ids, area);
            collectNumbers(ids, prop);
        }
        return ids;
    }

    private static boolean valuesMatchIgnoringOrderAndFormatting(String source, String target) {
        if (source == null || target == null || source.length() == 0 || target.length() == 0) {
            return false;
        }
        List<String> left = normalizedTokens(source);
        List<String> right = normalizedTokens(target);
        return !left.isEmpty() && left.equals(right);
    }

    private static boolean sameNumberSet(String source, String target) {
        Set<String> left = new LinkedHashSet<String>();
        Set<String> right = new LinkedHashSet<String>();
        collectNumbers(left, source);
        collectNumbers(right, target);
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
        private int permissionRows;
        private int sourceOnlyRows;
        private int targetOnlyRows;
        private int changedRows;
        private int formattingOnlyRows;
        private int sameIdDifferentDisplayRows;
        private int orderRows;
        private final Set<String> sourceIds = new LinkedHashSet<String>();
        private final Set<String> targetIds = new LinkedHashSet<String>();
        private final Set<String> commonIds = new LinkedHashSet<String>();
        private final Set<String> sourceOnlyIds = new LinkedHashSet<String>();
        private final Set<String> targetOnlyIds = new LinkedHashSet<String>();
        private final Set<String> contextIds = new LinkedHashSet<String>();

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

        public int getPermissionRows() { return permissionRows; }
        public int getSourceOnlyRows() { return sourceOnlyRows; }
        public int getTargetOnlyRows() { return targetOnlyRows; }
        public int getChangedRows() { return changedRows; }
        public int getFormattingOnlyRows() { return formattingOnlyRows; }
        public int getSameIdDifferentDisplayRows() { return sameIdDifferentDisplayRows; }
        public int getOrderRows() { return orderRows; }
        public Set<String> getCommonIds() { return Collections.unmodifiableSet(commonIds); }
        public Set<String> getSourceOnlyIds() { return Collections.unmodifiableSet(sourceOnlyIds); }
        public Set<String> getTargetOnlyIds() { return Collections.unmodifiableSet(targetOnlyIds); }
        public Set<String> getContextIds() { return Collections.unmodifiableSet(contextIds); }

        public boolean hasRows() {
            return permissionRows > 0;
        }

        public boolean hasBlockingSignals() {
            return sourceOnlyRows > 0 || targetOnlyRows > 0 || !sourceOnlyIds.isEmpty() || !targetOnlyIds.isEmpty();
        }

        public boolean looksLikeFormattingOnly() {
            return permissionRows > 0 && !hasBlockingSignals()
                    && (formattingOnlyRows > 0 || sameIdDifferentDisplayRows > 0 || orderRows > 0);
        }

        public String oneLineRisk() {
            if (!hasRows()) {
                return "";
            }
            if (hasBlockingSignals()) {
                return "Permission risk: missing source/target group references detected.";
            }
            if (looksLikeFormattingOnly()) {
                return "Permission risk: low - same IDs or order/formatting differences dominate.";
            }
            if (!commonIds.isEmpty()) {
                return "Permission risk: medium - common group IDs exist, but permission values still differ.";
            }
            return "Permission risk: review required.";
        }

        public String toOperatorText() {
            if (!hasRows()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            out.append("- ").append(permissionRows).append(" permission/group related row(s) differ.");
            out.append(' ').append(oneLineRisk());
            if (changedRows > 0) {
                out.append(" Changed values: ").append(changedRows).append('.');
            }
            if (sourceOnlyRows > 0 || targetOnlyRows > 0) {
                out.append(" Source-only rows: ").append(sourceOnlyRows)
                        .append(", target-only rows: ").append(targetOnlyRows).append('.');
            }
            if (formattingOnlyRows > 0 || sameIdDifferentDisplayRows > 0 || orderRows > 0) {
                out.append(" Possible non-functional differences: ")
                        .append(formattingOnlyRows).append(" normalized value match, ")
                        .append(sameIdDifferentDisplayRows).append(" same-ID display change, ")
                        .append(orderRows).append(" order/index row(s).");
            }
            if (!commonIds.isEmpty()) {
                out.append("\n- Group IDs found on both sides: ").append(sample(commonIds, 8)).append('.');
            }
            if (!sourceOnlyIds.isEmpty()) {
                out.append("\n- Source-only group/permission IDs: ").append(sample(sourceOnlyIds, 10)).append('.');
            }
            if (!targetOnlyIds.isEmpty()) {
                out.append("\n- Target-only group/permission IDs: ").append(sample(targetOnlyIds, 10)).append('.');
            }
            if (!contextIds.isEmpty() && commonIds.isEmpty() && sourceOnlyIds.isEmpty() && targetOnlyIds.isEmpty()) {
                out.append("\n- Permission context IDs found in member/property names: ").append(sample(contextIds, 8)).append('.');
            }
            if (looksLikeFormattingOnly()) {
                out.append("\n- Interpretation: this is more likely ordering/display serialization than a missing group, but verify effective permission values before adding ignore rules.");
            } else {
                out.append("\n- Recommended check: compare Group ID first, then group/display name. Names can differ while the AR permission identity is effectively the same.");
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
