package se.yrell.migrator.ui.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.bmc.arsys.api.Timestamp;

/** Small formatting helpers to keep UI classes compact. */
public final class UiStrings {
    private static final Locale SWEDISH = new Locale("sv", "SE");
    private static final String SWEDISH_DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private UiStrings() {
    }

    public static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Formats AR System timestamps as a readable Swedish date/time. Developer Studio exposes
     * timestamps as AR epoch seconds; the old toString() form is too technical for the view.
     */
    public static String timestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        try {
            Date date = timestamp.toDate();
            if (date == null) {
                return "";
            }
            return new SimpleDateFormat(SWEDISH_DATE_TIME_PATTERN, SWEDISH).format(date);
        } catch (RuntimeException ex) {
            return timestamp.toString();
        }
    }

    public static long timestampValue(Timestamp timestamp) {
        if (timestamp == null) {
            return Long.MIN_VALUE;
        }
        try {
            return timestamp.getValue();
        } catch (RuntimeException ex) {
            return Long.MIN_VALUE;
        }
    }

    public static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }
}
