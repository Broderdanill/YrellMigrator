package se.yrell.migrator.core;

/**
 * Builds safe, copyable ignore-rule suggestions for one structured diff row.
 *
 * The details dialog intentionally shows only small tokens that can be pasted
 * into the Settings page or property file. This avoids the previous UX trap
 * where operators could accidentally copy a whole explanatory paragraph.
 */
public final class DiffIgnoreAdvisor {
    private DiffIgnoreAdvisor() {
    }

    public static IgnoreSuggestion suggest(DiffDetail detail) {
        String area = detail == null ? "" : clean(detail.getArea());
        String property = detail == null ? "" : clean(detail.getProperty());
        String token = property.length() > 0 ? property : area;
        String shortToken = simplifyIgnoreToken(token);
        String scopedToken = area.length() == 0 || shortToken.length() == 0 ? shortToken : area + "/" + shortToken;
        String propertyLine = shortToken.length() == 0 ? "" : "ignore.difference.name.contains=" + shortToken;
        String scopedPropertyLine = scopedToken.length() == 0 ? "" : "ignore.difference.name.contains=" + scopedToken;
        String guidance;
        if (shortToken.length() == 0) {
            guidance = "No stable property token was available for this difference.";
        } else {
            guidance = "Use the short token to ignore this property everywhere, or the scoped token to ignore it only within this section.";
        }
        return new IgnoreSuggestion(shortToken, scopedToken, propertyLine, scopedPropertyLine, guidance);
    }

    public static String longHelp(DiffDetail detail) {
        IgnoreSuggestion suggestion = suggest(detail);
        if (!suggestion.hasToken()) {
            return "Ignore help: " + suggestion.getGuidance();
        }
        StringBuilder out = new StringBuilder();
        out.append("Ignore help:\n");
        out.append("1. Open the Settings tab in Yrell Migrator.\n");
        out.append("2. Add one of these values to 'Ignore properties':\n");
        out.append("   ").append(suggestion.getShortToken()).append('\n');
        if (suggestion.getScopedToken().length() > 0 && !suggestion.getScopedToken().equals(suggestion.getShortToken())) {
            out.append("   ").append(suggestion.getScopedToken()).append('\n');
        }
        out.append("3. Click Save settings and refresh the compare row.\n\n");
        if (suggestion.getPropertyLine().length() > 0) {
            out.append("Equivalent property-file example:\n");
            out.append(suggestion.getPropertyLine()).append('\n');
            if (suggestion.getScopedPropertyLine().length() > 0 && !suggestion.getScopedPropertyLine().equals(suggestion.getPropertyLine())) {
                out.append(suggestion.getScopedPropertyLine()).append('\n');
            }
            out.append('\n');
        }
        out.append(suggestion.getGuidance());
        return out.toString();
    }

    private static String simplifyIgnoreToken(String token) {
        String text = clean(token);
        if (text.length() == 0) {
            return text;
        }
        int slash = text.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < text.length()) {
            text = text.substring(slash + 1).trim();
        }
        String lower = text.toLowerCase(java.util.Locale.ENGLISH);
        if (lower.startsWith("definition.")) {
            text = text.substring("definition.".length()).trim();
        } else if (lower.startsWith("definition /")) {
            text = text.substring("definition /".length()).trim();
        }
        return text.length() == 0 ? clean(token) : text;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }

    public static final class IgnoreSuggestion {
        private final String shortToken;
        private final String scopedToken;
        private final String propertyLine;
        private final String scopedPropertyLine;
        private final String guidance;

        private IgnoreSuggestion(String shortToken, String scopedToken, String propertyLine, String scopedPropertyLine, String guidance) {
            this.shortToken = shortToken == null ? "" : shortToken;
            this.scopedToken = scopedToken == null ? "" : scopedToken;
            this.propertyLine = propertyLine == null ? "" : propertyLine;
            this.scopedPropertyLine = scopedPropertyLine == null ? "" : scopedPropertyLine;
            this.guidance = guidance == null ? "" : guidance;
        }

        public String getShortToken() {
            return shortToken;
        }

        public String getScopedToken() {
            return scopedToken;
        }

        public String getPropertyLine() {
            return propertyLine;
        }

        public String getScopedPropertyLine() {
            return scopedPropertyLine;
        }

        public String getGuidance() {
            return guidance;
        }

        public boolean hasToken() {
            return shortToken.length() > 0;
        }
    }
}
