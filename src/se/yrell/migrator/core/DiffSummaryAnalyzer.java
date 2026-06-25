package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds short, operator-facing explanations from structured diff rows.
 *
 * The raw snapshot/member details are intentionally technical. This helper keeps
 * the UI focused on "why is it different?" and gives extra guidance for the
 * recurring problem areas: permissions/groups and audit settings.
 */
public final class DiffSummaryAnalyzer {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?<![A-Za-z0-9])-?\\d{1,12}(?![A-Za-z0-9])");

    private DiffSummaryAnalyzer() {
    }

    public static String shortCause(CompareResult result) {
        if (result == null) {
            return "";
        }
        List<DiffDetail> details = result.getDifferenceDetails();
        if (details == null || details.isEmpty()) {
            return fallbackStatus(result);
        }
        Map<String, Integer> sections = sectionCounts(details);
        StringBuilder out = new StringBuilder();
        int added = 0;
        for (Map.Entry<String, Integer> entry : sections.entrySet()) {
            if (added >= 4) {
                out.append(", ...");
                break;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(" (").append(entry.getValue()).append(')');
            added++;
        }
        return out.toString();
    }

    public static String verificationSummary(CompareResult result) {
        if (result == null) {
            return "";
        }
        String cause = shortCause(result);
        StringBuilder out = new StringBuilder();
        if (cause.length() > 0) {
            out.append(" Remaining differences: ").append(cause).append('.');
        }
        String risk = DiffRiskClassifier.oneLine(result);
        if (risk.length() > 0 && risk.indexOf("Risk: none") < 0) {
            out.append(' ').append(risk);
        }
        String permissions = permissionSummary(result);
        if (permissions.length() > 0) {
            out.append(' ').append(oneLine(permissions, 260));
        }
        String audit = auditSummary(result);
        if (audit.length() > 0) {
            out.append(' ').append(oneLine(audit, 180));
        }
        String archive = archiveSummary(result);
        if (archive.length() > 0) {
            out.append(' ').append(oneLine(archive, 180));
        }
        return out.toString();
    }

    public static String operatorSummary(CompareResult result, int maxLines) {
        if (result == null) {
            return "No compare row selected.";
        }
        List<DiffDetail> details = result.getDifferenceDetails();
        if (details == null || details.isEmpty()) {
            String detail = safe(result.getDetail()).trim();
            return detail.length() == 0 ? fallbackStatus(result) : detail;
        }
        Map<String, Integer> sections = sectionCounts(details);
        Map<String, Integer> kinds = kindCounts(details);
        StringBuilder out = new StringBuilder();
        out.append(result.getObjectType()).append(" differs because:");
        String risk = DiffRiskClassifier.assess(result).toOperatorText();
        if (risk.length() > 0 && risk.indexOf("Risk: none") < 0) {
            out.append("\n- ").append(risk);
        }
        int added = 0;
        for (Map.Entry<String, Integer> entry : sections.entrySet()) {
            if (added >= Math.max(1, maxLines)) {
                out.append("\n- ...");
                break;
            }
            out.append("\n- ").append(sectionExplanation(entry.getKey(), entry.getValue(), details));
            added++;
        }
        String permission = permissionSummary(result);
        if (permission.length() > 0) {
            out.append("\n\nPermissions check:\n").append(permission);
        }
        String audit = auditSummary(result);
        if (audit.length() > 0) {
            out.append("\n\nAudit check:\n").append(audit);
        }
        String archive = archiveSummary(result);
        if (archive.length() > 0) {
            out.append("\n\nArchive check:\n").append(archive);
        }
        if (!kinds.isEmpty()) {
            out.append("\n\nChange types: ").append(joinCounts(kinds, 5)).append('.');
        }
        return out.toString();
    }

    public static String permissionSummary(CompareResult result) {
        if (result == null || result.getDifferenceDetails() == null || result.getDifferenceDetails().isEmpty()) {
            return "";
        }
        PermissionDiffNormalizer.Summary summary = PermissionDiffNormalizer.analyze(result.getDifferenceDetails());
        return summary.hasRows() ? summary.toOperatorText() : "";
    }

    public static String auditSummary(CompareResult result) {
        if (result == null || result.getDifferenceDetails() == null || result.getDifferenceDetails().isEmpty()) {
            return "";
        }
        AuditDiffAnalyzer.Summary summary = AuditDiffAnalyzer.analyze(result.getDifferenceDetails());
        return summary.hasRows() ? summary.toOperatorText() : "";
    }

    public static String archiveSummary(CompareResult result) {
        if (result == null || result.getDifferenceDetails() == null || result.getDifferenceDetails().isEmpty()) {
            return "";
        }
        ArchiveDiffAnalyzer.Summary summary = ArchiveDiffAnalyzer.analyze(result.getDifferenceDetails());
        return summary.hasRows() ? summary.toOperatorText() : "";
    }

    public static String logicalSection(DiffDetail detail) {
        String area = detail == null ? "" : safe(detail.getArea());
        String property = detail == null ? "" : safe(detail.getProperty());
        String kind = detail == null ? "" : safe(detail.getKind());
        String lowerArea = area.toLowerCase(Locale.ENGLISH);
        String lowerProperty = property.toLowerCase(Locale.ENGLISH);
        String lowerKind = kind.toLowerCase(Locale.ENGLISH);
        String combined = lowerArea + " " + lowerProperty + " " + lowerKind;
        if (combined.indexOf("archive") >= 0 || combined.indexOf("archiving") >= 0) return "Archive Settings";
        if (combined.indexOf("audit") >= 0) return "Audit Settings";
        if (lowerArea.indexOf("binary") >= 0 || lowerProperty.indexOf("fingerprint") >= 0 || lowerProperty.indexOf("discovered bytes") >= 0) return "Binary";
        if (lowerArea.startsWith("field ") || lowerArea.startsWith("fields")) return "Fields";
        if (lowerArea.startsWith("view ") || lowerArea.startsWith("views")) return "Views";
        if (lowerArea.startsWith("if action") || lowerArea.startsWith("if actions")) return "If Actions";
        if (lowerArea.startsWith("else action") || lowerArea.startsWith("else actions")) return "Else Actions";
        if (lowerArea.indexOf("associated forms") >= 0) return "Associated Forms";
        if (lowerArea.indexOf("qualification") >= 0 || lowerProperty.indexOf("qualification") >= 0 || lowerProperty.indexOf("run if") >= 0) return "Qualifications";
        if (lowerArea.indexOf("execution") >= 0 || lowerProperty.indexOf("execute") >= 0 || lowerProperty.indexOf("order") >= 0) return "Execution";
        if (lowerArea.startsWith("menu item") || lowerArea.startsWith("menu items")) return "Menu Items";
        if (lowerArea.startsWith("index") || lowerArea.startsWith("indexes")) return "Indexes";
        if (lowerArea.startsWith("permission") || lowerArea.startsWith("permissions") || lowerProperty.indexOf("permission") >= 0 || lowerProperty.indexOf("group") >= 0) return "Permissions";
        if (lowerArea.indexOf("guide membership") >= 0) return "Guide Membership";
        if (lowerArea.indexOf("schedule") >= 0) return "Schedule";
        return "Main Settings";
    }

    public static int sectionSortWeight(String section) {
        if ("Main Settings".equals(section)) return 10;
        if ("Associated Forms".equals(section)) return 20;
        if ("Execution".equals(section)) return 30;
        if ("Qualifications".equals(section)) return 40;
        if ("Audit Settings".equals(section)) return 45;
        if ("Archive Settings".equals(section)) return 46;
        if ("Fields".equals(section)) return 50;
        if ("Views".equals(section)) return 60;
        if ("If Actions".equals(section)) return 70;
        if ("Else Actions".equals(section)) return 80;
        if ("Menu Items".equals(section)) return 90;
        if ("Indexes".equals(section)) return 100;
        if ("Permissions".equals(section)) return 110;
        if ("Guide Membership".equals(section)) return 120;
        if ("Schedule".equals(section)) return 130;
        if ("Binary".equals(section)) return 140;
        return 500;
    }

    private static String sectionExplanation(String section, int count, List<DiffDetail> details) {
        String suffix = count == 1 ? "1 row" : count + " rows";
        if ("Permissions".equals(section)) {
            return "Permissions/groups differ (" + suffix + ").";
        }
        if ("Audit Settings".equals(section)) {
            return "Audit settings differ (" + suffix + ").";
        }
        if ("Archive Settings".equals(section)) {
            return "Archive settings differ (" + suffix + ").";
        }
        if ("Fields".equals(section)) {
            return "Field definitions differ (" + suffix + ").";
        }
        if ("Views".equals(section)) {
            return "View/display metadata differs (" + suffix + ").";
        }
        if ("If Actions".equals(section) || "Else Actions".equals(section)) {
            return section + " differ (" + suffix + ").";
        }
        if ("Qualifications".equals(section)) {
            return "Qualification/run-if logic differs (" + suffix + ").";
        }
        if ("Binary".equals(section)) {
            return "Binary payload/fingerprint differs (" + suffix + ").";
        }
        if ("Execution".equals(section)) {
            return "Execution settings/order differ (" + suffix + ").";
        }
        return section + " differ (" + suffix + ").";
    }

    private static Map<String, Integer> sectionCounts(List<DiffDetail> details) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (details == null) {
            return counts;
        }
        List<String> ordered = new ArrayList<String>();
        for (DiffDetail detail : details) {
            String section = logicalSection(detail);
            if (!counts.containsKey(section)) {
                ordered.add(section);
            }
            increment(counts, section);
        }
        ordered.sort(new java.util.Comparator<String>() {
            public int compare(String left, String right) {
                int w = sectionSortWeight(left) - sectionSortWeight(right);
                return w != 0 ? w : String.CASE_INSENSITIVE_ORDER.compare(left, right);
            }
        });
        Map<String, Integer> sorted = new LinkedHashMap<String, Integer>();
        for (String section : ordered) {
            sorted.put(section, counts.get(section));
        }
        return sorted;
    }

    private static Map<String, Integer> kindCounts(List<DiffDetail> details) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (details == null) {
            return counts;
        }
        for (DiffDetail detail : details) {
            increment(counts, prettyKind(detail == null ? "" : detail.getKind()));
        }
        return counts;
    }

    private static void increment(Map<String, Integer> map, String key) {
        if (key == null || key.length() == 0) {
            key = "Other";
        }
        Integer old = map.get(key);
        map.put(key, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
    }

    private static String prettyKind(String kind) {
        String value = kind == null || kind.trim().length() == 0 ? "property" : kind.trim();
        String lower = value.toLowerCase(Locale.ENGLISH);
        if (lower.indexOf("missing") >= 0) {
            return lower.indexOf("source") >= 0 ? "Only in target" : lower.indexOf("target") >= 0 ? "Only in source" : "Missing";
        }
        if (lower.indexOf("error") >= 0) return "Error";
        if (lower.indexOf("audit") >= 0) return "Audit changed";
        if (lower.indexOf("qualification") >= 0) return "Qualification changed";
        if (lower.indexOf("action") >= 0) return "Action changed";
        if (lower.indexOf("permission") >= 0) return "Permission changed";
        if (lower.indexOf("field") >= 0) return "Field changed";
        if (lower.indexOf("view") >= 0) return "View changed";
        if (lower.indexOf("binary") >= 0 || lower.indexOf("fingerprint") >= 0) return "Binary changed";
        return "Changed";
    }

    private static String joinCounts(Map<String, Integer> map, int max) {
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (added >= max) {
                builder.append(", ...");
                break;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            added++;
        }
        return builder.toString();
    }

    private static void collectNumbers(Set<String> out, String text) {
        if (out == null || text == null || text.length() == 0) {
            return;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            // Skip tiny formatting numbers that are often boolean/order fragments.
            if ("0".equals(value) || "1".equals(value)) {
                continue;
            }
            out.add(value);
        }
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

    private static String fallbackStatus(CompareResult result) {
        if (result == null || result.getStatus() == null) {
            return "No structured diff details available.";
        }
        return "Status: " + result.getStatus().getLabel() + ". No structured diff details available.";
    }

    private static String oneLine(String text, int max) {
        String value = safe(text).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String lower(String value) {
        return safe(value).toLowerCase(Locale.ENGLISH);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
