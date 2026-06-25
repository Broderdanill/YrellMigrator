package se.yrell.migrator.core;

import java.util.Locale;

/**
 * Classifies archive-related form differences into operator-facing guidance.
 *
 * Archive settings are stored as form metadata in AR System but have special
 * runtime consequences. Keeping them separate from generic "Main Settings"
 * makes post-migration "Still different" rows easier to understand.
 */
public final class ArchiveDiffAnalyzer {
    private ArchiveDiffAnalyzer() {
    }

    public static Summary analyze(java.util.List<DiffDetail> details) {
        Summary summary = new Summary();
        if (details == null) {
            return summary;
        }
        for (DiffDetail detail : details) {
            if (detail == null || !isLikelyArchive(detail)) {
                continue;
            }
            summary.archiveRows++;
            String area = lower(detail.getArea());
            String prop = lower(detail.getProperty());
            String kind = lower(detail.getKind());
            String source = lower(detail.getSourceValue());
            String target = lower(detail.getTargetValue());
            String combined = area + " " + prop + " " + kind + " " + source + " " + target;

            if (containsAny(combined, "enable", "enabled", "disabled")) summary.enabledRows++;
            if (containsAny(combined, "archive type", "archivetype", "type")) summary.typeRows++;
            if (containsAny(combined, "archive dest", "destination", "archive form", "archivefrom", "archive from")) summary.destinationRows++;
            if (containsAny(combined, "qualifier", "qualification", "age qualifier", "field id", "fieldid")) summary.qualificationRows++;
            if (containsAny(combined, "policy", "appear in archive policy")) summary.policyRows++;
            if (containsAny(combined, "description")) summary.descriptionRows++;
            if (source.length() == 0 && target.length() > 0) summary.targetOnlyRows++;
            if (source.length() > 0 && target.length() == 0) summary.sourceOnlyRows++;
        }
        return summary;
    }

    public static boolean isLikelyArchive(DiffDetail detail) {
        if (detail == null) {
            return false;
        }
        String area = lower(detail.getArea());
        String prop = lower(detail.getProperty());
        String kind = lower(detail.getKind());
        String combined = area + " " + prop + " " + kind;
        return combined.indexOf("archive") >= 0 || combined.indexOf("archiving") >= 0;
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

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ENGLISH);
    }

    public static final class Summary {
        private int archiveRows;
        private int enabledRows;
        private int typeRows;
        private int destinationRows;
        private int qualificationRows;
        private int policyRows;
        private int descriptionRows;
        private int sourceOnlyRows;
        private int targetOnlyRows;

        public int getArchiveRows() { return archiveRows; }
        public int getEnabledRows() { return enabledRows; }
        public int getTypeRows() { return typeRows; }
        public int getDestinationRows() { return destinationRows; }
        public int getQualificationRows() { return qualificationRows; }
        public int getPolicyRows() { return policyRows; }
        public int getDescriptionRows() { return descriptionRows; }
        public int getSourceOnlyRows() { return sourceOnlyRows; }
        public int getTargetOnlyRows() { return targetOnlyRows; }

        public boolean hasRows() {
            return archiveRows > 0;
        }

        public boolean hasFunctionalSignals() {
            return enabledRows > 0 || typeRows > 0 || destinationRows > 0 || qualificationRows > 0 || policyRows > 0
                    || sourceOnlyRows > 0 || targetOnlyRows > 0;
        }

        public String oneLineRisk() {
            if (!hasRows()) {
                return "";
            }
            if (enabledRows > 0 || typeRows > 0 || destinationRows > 0 || qualificationRows > 0) {
                return "Archive risk: high - archive enablement, type, destination or qualification differs.";
            }
            if (policyRows > 0) {
                return "Archive risk: medium - archive policy visibility differs.";
            }
            if (descriptionRows > 0 && archiveRows == descriptionRows) {
                return "Archive risk: low - only archive description differs.";
            }
            return "Archive risk: review required.";
        }

        public String toOperatorText() {
            if (!hasRows()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            out.append("- ").append(archiveRows).append(" archive-related row(s) differ. ").append(oneLineRisk());
            if (enabledRows > 0) out.append(" Enabled/disabled state: ").append(enabledRows).append('.');
            if (typeRows > 0) out.append(" Archive type: ").append(typeRows).append('.');
            if (destinationRows > 0) out.append(" Destination/form reference: ").append(destinationRows).append('.');
            if (qualificationRows > 0) out.append(" Qualification/age field: ").append(qualificationRows).append('.');
            if (policyRows > 0) out.append(" Archive policy: ").append(policyRows).append('.');
            if (descriptionRows > 0) out.append(" Description: ").append(descriptionRows).append('.');
            return out.toString();
        }
    }
}
