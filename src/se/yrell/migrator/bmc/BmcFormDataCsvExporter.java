package se.yrell.migrator.bmc;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.Locale;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.api.Constants;
import com.bmc.arsys.api.Entry;
import com.bmc.arsys.api.Field;
import com.bmc.arsys.api.OutputInteger;
import com.bmc.arsys.api.QualifierInfo;
import com.bmc.arsys.api.Timestamp;
import com.bmc.arsys.api.Value;
import com.bmc.arsys.studio.model.ModelException;
import com.bmc.arsys.studio.model.store.ARServerStore;
import com.bmc.arsys.studio.model.store.IEntryStore;
import com.bmc.arsys.studio.model.store.IStore;

import se.yrell.migrator.Activator;

/** Exports AR form entries to a delimited CSV file through the Developer Studio entry API. */
public final class BmcFormDataCsvExporter {
    public static final int DEFAULT_PAGE_SIZE = 250;
    private static final Locale SWEDISH = new Locale("sv", "SE");
    private static final String SWEDISH_DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    public static final class Options {
        private IStore store;
        private String formName;
        private String qualification;
        private int maxRows;
        private String delimiter = ";";
        private int pageSize = DEFAULT_PAGE_SIZE;

        public IStore getStore() { return store; }
        public void setStore(IStore store) { this.store = store; }
        public String getFormName() { return formName; }
        public void setFormName(String formName) { this.formName = formName; }
        public String getQualification() { return qualification; }
        public void setQualification(String qualification) { this.qualification = qualification; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
        public String getDelimiter() { return delimiter == null || delimiter.length() == 0 ? ";" : delimiter; }
        public void setDelimiter(String delimiter) { this.delimiter = normalizeDelimiter(delimiter); }
        public int getPageSize() { return pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    }

    public static final class Result {
        private int exportedRows;
        private int columns;
        private String filePath;
        private String formName;
        private String serverName;

        public int getExportedRows() { return exportedRows; }
        public int getColumns() { return columns; }
        public String getFilePath() { return filePath; }
        public String getFormName() { return formName; }
        public String getServerName() { return serverName; }
    }

    private static final class ColumnInfo {
        private final int fieldId;
        private final String name;

        private ColumnInfo(int fieldId, String name) {
            this.fieldId = fieldId;
            this.name = name == null || name.length() == 0 ? String.valueOf(fieldId) : name;
        }
    }

    public Result export(Options options, String filePath, IProgressMonitor monitor) throws ModelException {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        validate(options, filePath);
        IEntryStore entryStore = (IEntryStore) options.getStore();
        String form = options.getFormName();
        String delimiter = options.getDelimiter();
        QualifierInfo qualifier = parseQualification(options.getStore(), form, options.getQualification());
        int maxRows = Math.max(0, options.getMaxRows());
        int pageSize = Math.max(1, options.getPageSize());
        List<ColumnInfo> columns = readColumns(options.getStore(), form);
        int[] fieldIds = columns.isEmpty() ? null : toFieldIds(columns);

        Result result = new Result();
        result.formName = form;
        result.serverName = options.getStore() == null ? "" : options.getStore().getName();
        result.filePath = filePath;
        result.columns = columns.size();

        safeMonitor.beginTask("Exporting form data for " + form, maxRows == 0 ? IProgressMonitor.UNKNOWN : maxRows);
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8"));
            writeHeader(writer, columns, delimiter);
            int offset = 0;
            int remaining = maxRows == 0 ? Integer.MAX_VALUE : maxRows;
            while (!safeMonitor.isCanceled() && remaining > 0) {
                int batchSize = Math.min(pageSize, remaining);
                safeMonitor.subTask("Reading " + form + " entries " + (offset + 1) + "-" + (offset + batchSize));
                OutputInteger total = new OutputInteger();
                List<Entry> rows = entryStore.getListEntryObjects(form, qualifier, offset, batchSize, null, null, false, total);
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                for (Entry row : rows) {
                    if (safeMonitor.isCanceled() || remaining <= 0) {
                        break;
                    }
                    Entry full = fullEntry(entryStore, form, row, fieldIds);
                    if (columns.isEmpty()) {
                        columns = columnsFromEntry(full);
                        fieldIds = toFieldIds(columns);
                        result.columns = columns.size();
                        // Empty metadata fallback means the first written header was empty. This is very rare
                        // for ARServerStore; fail clearly instead of producing an unusable CSV.
                        throw new ModelException("Could not read the field list for form '" + form + "'. CSV header could not be created.");
                    }
                    writeRow(writer, full, columns, delimiter);
                    result.exportedRows++;
                    remaining--;
                    safeMonitor.worked(1);
                }
                offset += rows.size();
                if (rows.size() < batchSize) {
                    break;
                }
                rows.clear();
            }
        } catch (ModelException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new ModelException("Could not export form data CSV: " + safeMessage(ex));
        } finally {
            if (writer != null) {
                writer.close();
            }
            safeMonitor.done();
        }
        return result;
    }

    private void validate(Options options, String filePath) throws ModelException {
        if (options == null) {
            throw new ModelException("No export options were supplied.");
        }
        if (!(options.getStore() instanceof IEntryStore) || !options.getStore().isConnected()) {
            throw new ModelException("Selected environment does not support form entry access or is not connected.");
        }
        if (options.getFormName() == null || options.getFormName().trim().length() == 0) {
            throw new ModelException("No form name was supplied.");
        }
        if (filePath == null || filePath.trim().length() == 0) {
            throw new ModelException("No output CSV file was selected.");
        }
    }

    private QualifierInfo parseQualification(IStore store, String formName, String qualification) throws ModelException {
        if (qualification == null || qualification.trim().length() == 0) {
            return null;
        }
        try {
            if (store instanceof ARServerStore) {
                return ((ARServerStore) store).getContext().parseQualification(formName, qualification);
            }
        } catch (Throwable ex) {
            throw new ModelException("Could not parse qualification: " + safeMessage(ex));
        }
        try {
            return store.decodeQualification(qualification);
        } catch (Throwable ex) {
            throw new ModelException("Could not parse qualification. Use AR qualification syntax, for example 'Status' = \"Enabled\". " + safeMessage(ex));
        }
    }

    private List<ColumnInfo> readColumns(IStore store, String formName) {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        if (!(store instanceof ARServerStore)) {
            return columns;
        }
        try {
            List<Field> fields = ((ARServerStore) store).getContext().getListFieldObjects(formName);
            if (fields == null) {
                return columns;
            }
            Collections.sort(fields, new Comparator<Field>() {
                public int compare(Field left, Field right) {
                    return Integer.valueOf(left.getFieldID()).compareTo(Integer.valueOf(right.getFieldID()));
                }
            });
            boolean hasRequestId = false;
            for (Field field : fields) {
                if (!isExportableEntryField(field)) {
                    continue;
                }
                int id = field.getFieldID();
                if (id == 1) {
                    hasRequestId = true;
                }
                columns.add(new ColumnInfo(id, field.getName()));
            }
            if (!hasRequestId) {
                columns.add(0, new ColumnInfo(1, "Request ID"));
            }
        } catch (Throwable ex) {
            Activator.logWarning("Could not read field list for CSV export from " + formName + ".", ex);
        }
        return columns;
    }

    private boolean isExportableEntryField(Field field) {
        if (field == null || field.getFieldID() <= 0) {
            return false;
        }
        int fieldType = field.getFieldType();
        if (fieldType != Constants.AR_FIELD_TYPE_DATA && fieldType != Constants.AR_FIELD_TYPE_ATTACH) {
            return false;
        }
        int dataType = field.getDataType();
        switch (dataType) {
            case Constants.AR_DATA_TYPE_CONTROL:
            case Constants.AR_DATA_TYPE_TABLE:
            case Constants.AR_DATA_TYPE_COLUMN:
            case Constants.AR_DATA_TYPE_PAGE:
            case Constants.AR_DATA_TYPE_PAGE_HOLDER:
            case Constants.AR_DATA_TYPE_ATTACH_POOL:
            case Constants.AR_DATA_TYPE_VIEW:
            case Constants.AR_DATA_TYPE_DISPLAY:
            case Constants.AR_DATA_TYPE_TRIM:
                return false;
            default:
                return true;
        }
    }

    private int[] toFieldIds(List<ColumnInfo> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        int[] fieldIds = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            fieldIds[i] = columns.get(i).fieldId;
        }
        return fieldIds;
    }

    private Entry fullEntry(IEntryStore store, String formName, Entry row, int[] fieldIds) {
        String id = safeEntryId(row);
        if (id == null || id.length() == 0 || "<null>".equals(id)) {
            return row;
        }
        try {
            Entry full = store.getEntry(formName, id, fieldIds);
            if (full != null) {
                if (full.getEntryId() == null || full.getEntryId().length() == 0) {
                    full.setEntryId(id);
                }
                return full;
            }
        } catch (Throwable ignored) {
        }
        return row;
    }

    private List<ColumnInfo> columnsFromEntry(Entry entry) {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        if (entry == null) {
            return columns;
        }
        Map<Integer, Value> values = new LinkedHashMap<Integer, Value>(entry);
        List<Integer> ids = new ArrayList<Integer>(values.keySet());
        Collections.sort(ids);
        if (!ids.contains(Integer.valueOf(1))) {
            columns.add(new ColumnInfo(1, "Request ID"));
        }
        for (Integer id : ids) {
            columns.add(new ColumnInfo(id.intValue(), String.valueOf(id)));
        }
        return columns;
    }

    private void writeHeader(PrintWriter writer, List<ColumnInfo> columns, String delimiter) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                line.append(delimiter);
            }
            line.append(csv(columns.get(i).name, delimiter));
        }
        writer.println(line.toString());
    }

    private void writeRow(PrintWriter writer, Entry entry, List<ColumnInfo> columns, String delimiter) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                line.append(delimiter);
            }
            ColumnInfo column = columns.get(i);
            String text;
            if (column.fieldId == 1) {
                Value value = entry == null ? null : entry.get(Integer.valueOf(column.fieldId));
                text = valueToText(value);
                if (text.length() == 0) {
                    text = safeEntryId(entry);
                }
            } else {
                text = valueToText(entry == null ? null : entry.get(Integer.valueOf(column.fieldId)));
            }
            line.append(csv(text, delimiter));
        }
        writer.println(line.toString());
    }

    private String valueToText(Value value) {
        if (value == null) {
            return "";
        }
        try {
            String alias = value.getAlias();
            if (alias != null && alias.length() > 0 && !looksLikeTechnicalTimestamp(alias)) {
                return alias;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object raw = value.getValue();
            String formatted = formatPossibleTimestamp(raw);
            if (formatted.length() > 0) {
                return formatted;
            }
            return raw == null ? "" : String.valueOf(raw);
        } catch (Throwable ignored) {
            String text = String.valueOf(value);
            String formatted = formatPossibleTimestamp(text);
            return formatted.length() > 0 ? formatted : text;
        }
    }

    private boolean looksLikeTechnicalTimestamp(String text) {
        return text != null && text.matches(".*Timestamp\\s*=\\s*\\d+.*");
    }

    private String formatPossibleTimestamp(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof Timestamp) {
            return formatTimestamp((Timestamp) raw);
        }
        String text = String.valueOf(raw);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(".*Timestamp\\s*=\\s*(\\d+).*", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.matches()) {
            try {
                long seconds = Long.parseLong(matcher.group(1));
                return new SimpleDateFormat(SWEDISH_DATE_TIME_PATTERN, SWEDISH).format(new Date(seconds * 1000L));
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    private String formatTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        try {
            return new SimpleDateFormat(SWEDISH_DATE_TIME_PATTERN, SWEDISH).format(timestamp.toDate());
        } catch (RuntimeException ex) {
            try {
                return new SimpleDateFormat(SWEDISH_DATE_TIME_PATTERN, SWEDISH).format(new Date(timestamp.getValue() * 1000L));
            } catch (RuntimeException ignored) {
                return "";
            }
        }
    }

    private String csv(String value, String delimiter) {
        String text = value == null ? "" : value;
        boolean quote = text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || (delimiter != null && delimiter.length() > 0 && text.indexOf(delimiter) >= 0);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.indexOf('"') >= 0) {
            text = text.replace("\"", "\"\"");
            quote = true;
        }
        return quote ? "\"" + text + "\"" : text;
    }

    private static String normalizeDelimiter(String delimiter) {
        if (delimiter == null || delimiter.length() == 0) {
            return ";";
        }
        String trimmed = delimiter.trim();
        if ("\\t".equals(trimmed) || "tab".equalsIgnoreCase(trimmed)) {
            return "\t";
        }
        if (trimmed.length() == 0) {
            return ";";
        }
        return trimmed.substring(0, 1);
    }

    private String safeEntryId(Entry entry) {
        if (entry == null) {
            return "<null>";
        }
        String id = entry.getEntryId();
        return id == null || id.length() == 0 ? entry.getKey() : id;
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }
}
