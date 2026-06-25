package se.yrell.migrator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small, dependency-free risk classifier for structured diff rows.
 *
 * This is deliberately conservative: it does not hide or ignore differences.
 * It gives the UI and migration reports a stable way to explain whether a
 * remaining difference is probably functional, probably serialization/order
 * noise, or needs manual review.
 */
public final class DiffRiskClassifier {
    private DiffRiskClassifier() {
    }

    public enum RiskLevel {
        NONE(0, "none"),
        LOW(1, "low"),
        MEDIUM(2, "medium"),
        HIGH(3, "high"),
        REVIEW(2, "review");

        private final int weight;
        private final String label;

        RiskLevel(int weight, String label) {
            this.weight = weight;
            this.label = label;
        }

        public int getWeight() {
            return weight;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class Assessment {
        private final RiskLevel level;
        private final List<String> reasons;

        private Assessment(RiskLevel level, List<String> reasons) {
            this.level = level == null ? RiskLevel.REVIEW : level;
            this.reasons = Collections.unmodifiableList(new ArrayList<String>(reasons == null ? Collections.<String>emptyList() : reasons));
        }

        public RiskLevel getLevel() {
            return level;
        }

        public List<String> getReasons() {
            return reasons;
        }

        public boolean hasReasons() {
            return !reasons.isEmpty();
        }

        public String oneLine() {
            if (level == RiskLevel.NONE && reasons.isEmpty()) {
                return "Risk: none.";
            }
            StringBuilder out = new StringBuilder();
            out.append("Risk: ").append(level.getLabel());
            if (!reasons.isEmpty()) {
                out.append(" - ");
                appendSample(out, reasons, 3);
            }
            out.append('.');
            return out.toString();
        }

        public String toOperatorText() {
            StringBuilder out = new StringBuilder(oneLine());
            if (reasons.size() > 3) {
                out.append(" Additional signals: ");
                appendSample(out, reasons.subList(3, reasons.size()), 4);
                out.append('.');
            }
            return out.toString();
        }
    }

    public static Assessment assess(CompareResult result) {
        if (result == null) {
            return new Assessment(RiskLevel.REVIEW, Collections.singletonList("no compare row selected"));
        }
        List<String> reasons = new ArrayList<String>();
        RiskLevel level = RiskLevel.NONE;
        CompareStatus status = result.getStatus();
        if (status == CompareStatus.ERROR) {
            level = max(level, RiskLevel.HIGH);
            reasons.add("compare error");
        } else if (status == CompareStatus.MISSING_IN_SOURCE || status == CompareStatus.MISSING_IN_TARGET) {
            level = max(level, RiskLevel.HIGH);
            reasons.add("object is missing on one side");
        } else if (status == CompareStatus.UNKNOWN) {
            level = max(level, RiskLevel.REVIEW);
            reasons.add("comparison status is unknown");
        } else if (status == CompareStatus.EQUAL) {
            return new Assessment(RiskLevel.NONE, Collections.<String>emptyList());
        }

        List<DiffDetail> details = result.getDifferenceDetails();
        if (details == null || details.isEmpty()) {
            if (level == RiskLevel.NONE) {
                level = RiskLevel.REVIEW;
                reasons.add("no structured diff rows available");
            }
            return new Assessment(level, reasons);
        }

        PermissionDiffNormalizer.Summary permission = PermissionDiffNormalizer.analyze(details);
        if (permission.hasRows()) {
            if (permission.hasBlockingSignals()) {
                level = max(level, RiskLevel.HIGH);
                reasons.add("permission/group references are missing on one side");
            } else if (permission.looksLikeFormattingOnly()) {
                level = max(level, RiskLevel.LOW);
                reasons.add("permission rows look like order/display serialization");
            } else {
                level = max(level, RiskLevel.MEDIUM);
                reasons.add("permission/group values differ");
            }
        }

        AuditDiffAnalyzer.Summary audit = AuditDiffAnalyzer.analyze(details);
        if (audit.hasRows()) {
            if (audit.getEnabledRows() > 0 || audit.getAuditFormRows() > 0 || audit.getFieldMappingRows() > 0) {
                level = max(level, RiskLevel.HIGH);
                reasons.add("audit enablement/form reference/field mapping differs");
            } else if (audit.getStyleRows() > 0 || audit.getPermissionRows() > 0) {
                level = max(level, RiskLevel.MEDIUM);
                reasons.add("audit mode/style or audit permissions differ");
            } else if (audit.getOrderOrFormatRows() > 0 || audit.getFormattingOnlyRows() > 0) {
                level = max(level, RiskLevel.LOW);
                reasons.add("audit rows look like order/serialization");
            } else {
                level = max(level, RiskLevel.REVIEW);
                reasons.add("audit metadata differs");
            }
        }

        ArchiveDiffAnalyzer.Summary archive = ArchiveDiffAnalyzer.analyze(details);
        if (archive.hasRows()) {
            if (archive.getEnabledRows() > 0 || archive.getTypeRows() > 0 || archive.getDestinationRows() > 0 || archive.getQualificationRows() > 0) {
                level = max(level, RiskLevel.HIGH);
                reasons.add("archive enablement/type/destination/qualification differs");
            } else if (archive.getPolicyRows() > 0) {
                level = max(level, RiskLevel.MEDIUM);
                reasons.add("archive policy visibility differs");
            } else if (archive.getDescriptionRows() > 0) {
                level = max(level, RiskLevel.LOW);
                reasons.add("archive description differs");
            } else {
                level = max(level, RiskLevel.REVIEW);
                reasons.add("archive metadata differs");
            }
        }

        SectionSignals sections = sectionSignals(details);
        if (sections.hasBinary) {
            level = max(level, RiskLevel.MEDIUM);
            reasons.add("binary/image/support-file verification differs or is limited");
        }
        if (sections.hasFields) {
            level = max(level, RiskLevel.MEDIUM);
            reasons.add("field definitions differ");
        }
        if (sections.hasWorkflow) {
            level = max(level, RiskLevel.MEDIUM);
            reasons.add("workflow logic/actions differ");
        }
        if (sections.hasQualifications) {
            level = max(level, RiskLevel.MEDIUM);
            reasons.add("qualification/run-if logic differs");
        }
        if (sections.hasMainSettings && level == RiskLevel.NONE) {
            level = RiskLevel.REVIEW;
            reasons.add("main settings differ");
        }
        if (level == RiskLevel.NONE && !details.isEmpty()) {
            level = RiskLevel.REVIEW;
            reasons.add("structured differences require review");
        }
        return new Assessment(level, unique(reasons));
    }

    public static String oneLine(CompareResult result) {
        return assess(result).oneLine();
    }

    private static RiskLevel max(RiskLevel left, RiskLevel right) {
        if (left == null) return right == null ? RiskLevel.REVIEW : right;
        if (right == null) return left;
        return right.getWeight() > left.getWeight() ? right : left;
    }

    private static SectionSignals sectionSignals(List<DiffDetail> details) {
        SectionSignals signals = new SectionSignals();
        for (DiffDetail detail : details) {
            String section = DiffSummaryAnalyzer.logicalSection(detail);
            if ("Binary".equals(section)) signals.hasBinary = true;
            else if ("Fields".equals(section)) signals.hasFields = true;
            else if ("If Actions".equals(section) || "Else Actions".equals(section)
                    || "Execution".equals(section) || "Guide Membership".equals(section)
                    || "Schedule".equals(section)) signals.hasWorkflow = true;
            else if ("Qualifications".equals(section)) signals.hasQualifications = true;
            else if ("Archive Settings".equals(section)) signals.hasArchive = true;
            else if ("Main Settings".equals(section)) signals.hasMainSettings = true;
        }
        return signals;
    }

    private static List<String> unique(List<String> values) {
        Set<String> set = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                String text = value == null ? "" : value.trim();
                if (text.length() > 0) {
                    set.add(text.toLowerCase(Locale.ENGLISH));
                }
            }
        }
        List<String> out = new ArrayList<String>();
        for (String value : set) {
            out.add(value);
        }
        return out;
    }

    private static void appendSample(StringBuilder out, List<String> values, int max) {
        int added = 0;
        for (String value : values) {
            if (added >= max) {
                out.append(", ...");
                break;
            }
            if (added > 0) {
                out.append(", ");
            }
            out.append(value);
            added++;
        }
    }

    private static final class SectionSignals {
        boolean hasBinary;
        boolean hasFields;
        boolean hasWorkflow;
        boolean hasQualifications;
        boolean hasMainSettings;
        boolean hasArchive;
    }
}
