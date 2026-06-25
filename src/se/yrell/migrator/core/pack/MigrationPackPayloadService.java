package se.yrell.migrator.core.pack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.bmc.arsys.api.ARServerUser;
import com.bmc.arsys.api.AttachmentValue;
import com.bmc.arsys.api.Constants;
import com.bmc.arsys.api.DataType;
import com.bmc.arsys.api.DateInfo;
import com.bmc.arsys.api.Entry;
import com.bmc.arsys.api.Field;
import com.bmc.arsys.api.OutputInteger;
import com.bmc.arsys.api.QualifierInfo;
import com.bmc.arsys.api.StructItemInfo;
import com.bmc.arsys.api.Time;
import com.bmc.arsys.api.Timestamp;
import com.bmc.arsys.api.Value;
import com.bmc.arsys.studio.model.ModelException;
import com.bmc.arsys.studio.model.store.ARServerStore;
import com.bmc.arsys.studio.model.store.IEntryStore;
import com.bmc.arsys.studio.model.store.IStore;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcDataMigrator;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationResult;

/**
 * Captures and replays the embedded payloads in .ympack files.
 *
 * A Migration Pack is intended to be portable: definitions and entry data are stored in the file,
 * so only the destination environment has to be connected when the pack is run later.
 */
public final class MigrationPackPayloadService {
    public static final String PAYLOAD_AR_DEFINITION = "arDefinition";
    public static final String PAYLOAD_ENTRY_DATA = "entryData";
    public static final String PAYLOAD_ENTRY_DATA_XML = "entryDataXml";

    private static final int PAGE_SIZE = 100;

    private final Map<String, CatalogMapping> catalogMappings = new LinkedHashMap<String, CatalogMapping>();

    public MigrationPackPayloadService() {
        addCatalog(new CatalogMapping("Group", "Group", new String[] { "Group Name", "Name" }));
        addCatalog(new CatalogMapping("GroupType", "Group", new String[] { "Group Name", "Name" }));
        addCatalog(new CatalogMapping("Role", "Roles", new String[] { "Role Name", "Name" }));
        addCatalog(new CatalogMapping("RoleType", "Roles", new String[] { "Role Name", "Name" }));
        addCatalog(new CatalogMapping("Message", "AR System Message Catalog", new String[] { "Message Number" }));
        addCatalog(new CatalogMapping("Report", "Report", new String[] { "Report Name", "Name", "Report" }));
        addCatalog(new CatalogMapping("Template", "AR System Email Templates", new String[] { "Template Name", "Name", "Template" }));
    }

    public boolean hasEmbeddedPayload(MigrationPackItem item) {
        return item != null && item.getPayloadBase64().length() > 0 && item.getPayloadType().length() > 0;
    }

    public void captureDefinition(MigrationPackItem item, CompareResult row, MigrationDirection direction,
            IProgressMonitor monitor) throws Exception {
        if (item == null) {
            return;
        }
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        if (row == null) {
            throw new IllegalArgumentException("No selected row was supplied for pack capture.");
        }
        IStore sourceStore = direction == MigrationDirection.TARGET_TO_SOURCE ? row.getTargetStore() : row.getSourceStore();
        if (sourceStore == null || !sourceStore.isConnected()) {
            throw new ModelException("Source environment is not connected, so the item cannot be embedded in the pack.");
        }
        CatalogMapping catalog = catalogMapping(row.getObjectType());
        if (catalog != null) {
            captureCatalogDefinitionAsEntries(item, row, sourceStore, catalog, safeMonitor);
            return;
        }
        if (!(sourceStore instanceof ARServerStore)) {
            throw new ModelException("Source environment does not expose AR export APIs for definition capture.");
        }
        int structType = structTypeFor(row.getObjectType());
        if (structType < 0) {
            throw new ModelException("This object type cannot currently be embedded as an AR definition: " + row.getObjectType());
        }
        safeMonitor.subTask("Embedding definition " + row.getObjectType() + " " + row.getObjectName());
        ARServerUser context = ((ARServerStore) sourceStore).getContext();
        List<StructItemInfo> items = new ArrayList<StructItemInfo>();
        items.add(new StructItemInfo(structType, row.getObjectName()));
        String definition = context.exportDef(items);
        if (definition == null || definition.length() == 0) {
            throw new ModelException("AR export returned an empty definition for " + row.getObjectType() + " " + row.getObjectName() + ".");
        }
        item.setPayloadType(PAYLOAD_AR_DEFINITION);
        item.setPayloadBase64(compressToBase64(definition.getBytes("UTF-8")));
        item.setEmbeddedRowCount(0);
        item.setEmbeddedObjectCount(1);
        item.setCapturedAtMillis(System.currentTimeMillis());
        item.setCaptureSummary("Embedded AR definition (" + definition.length() + " chars).");
    }

    public void captureFormData(MigrationPackItem item, BmcDataMigrator.Options options,
            IProgressMonitor monitor) throws Exception {
        if (item == null || options == null) {
            return;
        }
        if (!(options.getSourceStore() instanceof IEntryStore) || !options.getSourceStore().isConnected()) {
            throw new ModelException("Source environment does not support entry access or is not connected.");
        }
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        IEntryStore source = (IEntryStore) options.getSourceStore();
        List<Entry> entries = readEntries(source, options.getSourceStore(), options.getFormName(), options.getQualification(), options.getMaxRows(), safeMonitor);
        item.setPayloadType(PAYLOAD_ENTRY_DATA_XML);
        item.setPayloadBase64(serializeEntriesXml(entries));
        item.setEmbeddedRowCount(entries.size());
        item.setEmbeddedObjectCount(0);
        item.setCapturedAtMillis(System.currentTimeMillis());
        item.setCaptureSummary("Embedded " + entries.size() + " form-data row(s).");
    }

    public MigrationResult importDefinition(MigrationPackItem item, IStore targetStore, IProgressMonitor monitor) {
        CompareResult identity = identity(item, targetStore);
        if (item == null) {
            return MigrationResult.failure(identity, "No Migration Pack item was supplied.");
        }
        if (targetStore == null || !targetStore.isConnected()) {
            return MigrationResult.failure(identity, "Target environment is not connected.");
        }
        if (!hasEmbeddedPayload(item)) {
            return MigrationResult.failure(identity, "The Migration Pack item has no embedded payload. Re-add or re-export it with v0.80.0 or later.");
        }
        try {
            if (PAYLOAD_AR_DEFINITION.equals(item.getPayloadType())) {
                if (!(targetStore instanceof ARServerStore)) {
                    return MigrationResult.failure(identity, "Target environment does not expose AR definition import APIs.");
                }
                String definition = new String(decompressFromBase64(item.getPayloadBase64()), "UTF-8");
                int importOption = Constants.AR_IMPORT_OPT_CREATE | Constants.AR_IMPORT_OPT_OVERWRITE
                        | Constants.AR_IMPORT_OPT_HANDLE_CONFLICT_OVERWRITE
                        | Constants.AR_IMPORT_OPT_NOT_DELETE_FIELD
                        | Constants.AR_IMPORT_OPT_NOT_DELETE_VUI
                        | Constants.AR_IMPORT_OPT_PRESERVE_INDEX
                        | Constants.AR_IMPORT_OPT_PRESERVE_VUI_NAMESPACE
                        | Constants.AR_IMPORT_OPT_OVERWRITE_DISP_PROPS;
                ((ARServerStore) targetStore).getContext().importDefFromBuffer(definition, importOption);
                return MigrationResult.success(identity, false, "Imported embedded AR definition for "
                        + item.getObjectType() + " " + item.getObjectName() + " into " + targetStore.getName() + ".");
            }
            if (PAYLOAD_ENTRY_DATA.equals(item.getPayloadType()) || PAYLOAD_ENTRY_DATA_XML.equals(item.getPayloadType())) {
                EmbeddedDataResult dataResult = importEntries(item, targetStore, monitor);
                if (dataResult.failed > 0) {
                    return MigrationResult.warning(identity, dataResult.summary());
                }
                return MigrationResult.success(identity, dataResult.createdOrUpdated > 0, dataResult.summary());
            }
            return MigrationResult.failure(identity, "Unsupported Migration Pack payload type: " + item.getPayloadType());
        } catch (Throwable ex) {
            return MigrationResult.failure(identity, safeMessage(ex));
        }
    }

    public EmbeddedDataResult importEntries(MigrationPackItem item, IStore targetStore, IProgressMonitor monitor) throws Exception {
        if (item == null) {
            throw new ModelException("No Migration Pack item was supplied.");
        }
        if (!(targetStore instanceof IEntryStore) || !targetStore.isConnected()) {
            throw new ModelException("Target environment does not support entry access or is not connected.");
        }
        if (!PAYLOAD_ENTRY_DATA.equals(item.getPayloadType()) && !PAYLOAD_ENTRY_DATA_XML.equals(item.getPayloadType())) {
            throw new ModelException("The pack item does not contain embedded entry data.");
        }
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        IEntryStore target = (IEntryStore) targetStore;
        List<Entry> entries = deserializeEntries(item);
        FieldPolicy policy = buildTargetFieldPolicy(targetStore, item.getFormName(), item.getAttachmentPolicy());
        int mergeOption = BmcDataMigrator.safeDataMergeOptions(item.getConflictMode().getMergeOption(), item.isRunWorkflow());
        EmbeddedDataResult result = new EmbeddedDataResult(item.getFormName());
        safeMonitor.beginTask("Importing embedded form data for " + item.getFormName(), entries.size());
        int index = 0;
        for (Entry entry : entries) {
            if (safeMonitor.isCanceled()) {
                result.cancelled = true;
                break;
            }
            index++;
            String entryId = safeEntryId(entry);
            safeMonitor.subTask("Importing " + item.getFormName() + " " + entryId + " (" + index + "/" + entries.size() + ")");
            try {
                if (item.isDryRun()) {
                    result.read++;
                    result.createdOrUpdated++;
                } else {
                    Entry outbound = filterEntry(entry, policy, item, result);
                    target.mergeEntry(item.getFormName(), outbound, mergeOption);
                    result.read++;
                    result.createdOrUpdated++;
                }
            } catch (Throwable ex) {
                result.read++;
                result.addFailure(entryId + ": " + safeMessage(ex));
            }
            safeMonitor.worked(1);
        }
        safeMonitor.done();
        return result;
    }

    private void captureCatalogDefinitionAsEntries(MigrationPackItem item, CompareResult row, IStore sourceStore,
            CatalogMapping mapping, IProgressMonitor monitor) throws Exception {
        if (!(sourceStore instanceof IEntryStore)) {
            throw new ModelException("Source environment does not support entry access for " + row.getObjectType() + ".");
        }
        IEntryStore source = (IEntryStore) sourceStore;
        List<Entry> entries = findCatalogRows(source, sourceStore, mapping, row.getObjectName(), monitor);
        if (entries.isEmpty()) {
            throw new ModelException("No source row was found in " + mapping.formName + " for " + row.getObjectType() + " " + row.getObjectName() + ".");
        }
        item.setFormName(mapping.formName);
        item.setPayloadType(PAYLOAD_ENTRY_DATA_XML);
        item.setPayloadBase64(serializeEntriesXml(entries));
        item.setEmbeddedRowCount(entries.size());
        item.setEmbeddedObjectCount(0);
        item.setCapturedAtMillis(System.currentTimeMillis());
        item.setCaptureSummary("Embedded " + entries.size() + " data-backed definition row(s) from " + mapping.formName + ".");
    }

    private List<Entry> readEntries(IEntryStore source, IStore sourceStore, String formName, String qualification,
            int maxRows, IProgressMonitor monitor) throws Exception {
        List<Entry> all = new ArrayList<Entry>();
        QualifierInfo qualifier = parseQualification(sourceStore, formName, qualification);
        int remaining = maxRows <= 0 ? Integer.MAX_VALUE : maxRows;
        int offset = 0;
        while (remaining > 0) {
            int batchSize = Math.min(PAGE_SIZE, remaining);
            if (monitor != null) {
                monitor.subTask("Embedding form data " + formName + " rows " + (offset + 1) + "-" + (offset + batchSize));
            }
            OutputInteger total = new OutputInteger();
            List<Entry> batch = source.getListEntryObjects(formName, qualifier, offset, batchSize, null, null, false, total);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (Entry e : batch) {
                all.add(e == null ? null : (Entry) e.clone());
            }
            remaining -= batch.size();
            offset += batch.size();
            if (batch.size() < batchSize) {
                break;
            }
        }
        return all;
    }

    private List<Entry> findCatalogRows(IEntryStore source, IStore sourceStore, CatalogMapping mapping, String keyValue,
            IProgressMonitor monitor) throws Exception {
        ModelException lastUnknownField = null;
        for (int i = 0; i < mapping.keyFields.length; i++) {
            String field = mapping.keyFields[i];
            try {
                QualifierInfo q = parseQualification(sourceStore, mapping.formName, "'" + field + "' = \"" + escape(keyValue) + "\"");
                OutputInteger total = new OutputInteger();
                List<Entry> rows = source.getListEntryObjects(mapping.formName, q, 0, 25, null, null, false, total);
                if (rows != null && !rows.isEmpty()) {
                    List<Entry> full = new ArrayList<Entry>();
                    for (Entry row : rows) {
                        String id = safeEntryId(row);
                        try {
                            Entry loaded = id == null || id.length() == 0 ? row : source.getEntry(mapping.formName, id, null);
                            full.add(loaded == null ? row : (Entry) loaded.clone());
                        } catch (Throwable ignored) {
                            full.add(row == null ? null : (Entry) row.clone());
                        }
                    }
                    return full;
                }
            } catch (ModelException ex) {
                if (!looksLikeUnknownFieldError(ex)) {
                    throw ex;
                }
                lastUnknownField = ex;
            }
        }
        if (lastUnknownField != null) {
            throw lastUnknownField;
        }
        return new ArrayList<Entry>();
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
            throw new ModelException("Could not parse qualification: " + safeMessage(ex));
        }
    }

    private FieldPolicy buildTargetFieldPolicy(IStore targetStore, String formName, BmcDataMigrator.AttachmentPolicy attachmentPolicy) {
        FieldPolicy policy = new FieldPolicy();
        policy.attachmentPolicy = attachmentPolicy == null ? BmcDataMigrator.AttachmentPolicy.SKIP_ATTACHMENTS : attachmentPolicy;
        if (!(targetStore instanceof ARServerStore)) {
            policy.metadataKnown = false;
            return policy;
        }
        try {
            List<Field> fields = ((ARServerStore) targetStore).getContext().getListFieldObjects(formName);
            policy.metadataKnown = true;
            if (fields != null) {
                for (Field field : fields) {
                    if (field == null || field.getFieldID() <= 0) continue;
                    Integer id = Integer.valueOf(field.getFieldID());
                    if (isAttachmentField(field)) policy.attachmentFieldIds.add(id);
                    if (isWritableEntryField(field, policy.attachmentPolicy == BmcDataMigrator.AttachmentPolicy.INCLUDE_ATTACHMENTS)) {
                        policy.writableFieldIds.add(id);
                    }
                }
            }
        } catch (Throwable ex) {
            policy.metadataKnown = false;
            Activator.logWarning("Could not read target field metadata for embedded pack data " + formName + ".", ex);
        }
        return policy;
    }

    private Entry filterEntry(Entry entry, FieldPolicy policy, MigrationPackItem item, EmbeddedDataResult result) {
        Entry outbound = new Entry();
        outbound.setEntryId(entry == null ? null : entry.getEntryId());
        if (entry == null) {
            return outbound;
        }
        for (Map.Entry<Integer, Value> pair : entry.entrySet()) {
            Integer fieldId = pair.getKey();
            if (fieldId == null) continue;
            if (fieldId.intValue() == 1 || fieldId.intValue() == 3 || fieldId.intValue() == 5 || fieldId.intValue() == 6 || fieldId.intValue() == 15) {
                result.skippedFieldValues++;
                continue;
            }
            if (policy != null && item.getAttachmentPolicy() == BmcDataMigrator.AttachmentPolicy.SKIP_ATTACHMENTS
                    && policy.attachmentFieldIds.contains(fieldId)) {
                result.skippedAttachmentFieldValues++;
                result.skippedFieldValues++;
                continue;
            }
            if (item.isFilterToTargetWritableFields() && policy != null && policy.metadataKnown
                    && !policy.writableFieldIds.contains(fieldId)) {
                result.skippedFieldValues++;
                continue;
            }
            outbound.put(fieldId, pair.getValue());
            result.fieldValuesSent++;
        }
        return outbound;
    }

    private int structTypeFor(String objectType) {
        String n = normalize(objectType);
        if (n.equals("form") || n.contains("form")) return StructItemInfo.SCHEMA;
        if (n.contains("activelink") && n.contains("guide")) return StructItemInfo.CONTAINER;
        if (n.contains("filter") && n.contains("guide")) return StructItemInfo.CONTAINER;
        if (n.contains("activelink")) return StructItemInfo.ACTIVE_LINK;
        if (n.equals("filter") || n.contains("filter")) return StructItemInfo.FILTER;
        if (n.contains("escalation")) return StructItemInfo.ESCALATION;
        if (n.contains("menu")) return StructItemInfo.CHAR_MENU;
        if (n.contains("image")) return StructItemInfo.IMAGE;
        if (n.contains("application")) return StructItemInfo.APPLICATION;
        if (n.contains("packinglist") || n.contains("webservice")) return StructItemInfo.CONTAINER;
        if (n.contains("association")) return StructItemInfo.ASSOCIATION;
        if (n.contains("distributedmap")) return StructItemInfo.DIST_MAP;
        if (n.contains("distributedpool")) return StructItemInfo.DIST_POOL;
        return -1;
    }

    private boolean isWritableEntryField(Field field, boolean includeAttachments) {
        if (field == null || field.getFieldID() <= 0) return false;
        int fieldType = field.getFieldType();
        if (fieldType != Constants.AR_FIELD_TYPE_DATA && fieldType != Constants.AR_FIELD_TYPE_ATTACH) return false;
        if (!includeAttachments && fieldType == Constants.AR_FIELD_TYPE_ATTACH) return false;
        if (field.getFieldOption() == Constants.AR_FIELD_OPTION_DISPLAY) return false;
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

    private boolean isAttachmentField(Field field) {
        return field != null && field.getFieldType() == Constants.AR_FIELD_TYPE_ATTACH;
    }

    private String serializeEntries(List<Entry> entries) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(raw);
        out.writeObject(entries == null ? new ArrayList<Entry>() : entries);
        out.close();
        return compressToBase64(raw.toByteArray());
    }

    private String serializeEntriesXml(List<Entry> entries) throws Exception {
        DocumentBuilderFactory factory = secureFactory();
        Document doc = factory.newDocumentBuilder().newDocument();
        Element root = doc.createElement("entries");
        root.setAttribute("version", "2");
        root.setAttribute("format", "neutral-field-values");
        root.setAttribute("count", String.valueOf(entries == null ? 0 : entries.size()));
        doc.appendChild(root);
        if (entries != null) {
            for (Entry entry : entries) {
                Element e = doc.createElement("entry");
                e.setAttribute("entryId", safeEntryId(entry));
                root.appendChild(e);
                if (entry == null) continue;
                for (Map.Entry<Integer, Value> pair : entry.entrySet()) {
                    if (pair == null || pair.getKey() == null) continue;
                    Element f = doc.createElement("field");
                    f.setAttribute("id", String.valueOf(pair.getKey().intValue()));
                    writeValue(doc, f, pair.getValue());
                    e.appendChild(f);
                }
            }
        }
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.transform(new DOMSource(doc), new StreamResult(xml));
        return compressToBase64(xml.toByteArray());
    }

    private void writeValue(Document doc, Element field, Value value) {
        if (value == null) {
            field.setAttribute("type", String.valueOf(DataType.NULL.toInt()));
            field.setAttribute("typeName", DataType.NULL.toString());
            return;
        }
        DataType type = value.getDataType();
        if (type == null) type = DataType.NULL;
        field.setAttribute("type", String.valueOf(type.toInt()));
        field.setAttribute("typeName", type.toString());
        if (value.getAlias() != null && value.getAlias().length() > 0) {
            field.setAttribute("alias", value.getAlias());
        }
        Object raw = value.getValue();
        if (raw instanceof AttachmentValue) {
            AttachmentValue attachment = (AttachmentValue) raw;
            field.setAttribute("encoding", "attachment-base64");
            field.setAttribute("fileName", attachment.getName() == null ? "" : attachment.getName());
            byte[] content = attachment.getContent();
            field.setTextContent(java.util.Base64.getEncoder().encodeToString(content == null ? new byte[0] : content));
        } else if (raw instanceof byte[]) {
            field.setAttribute("encoding", "base64");
            field.setTextContent(java.util.Base64.getEncoder().encodeToString((byte[]) raw));
        } else {
            field.setAttribute("encoding", "string");
            field.setTextContent(raw == null ? "" : String.valueOf(raw));
        }
    }

    private List<Entry> deserializeEntries(MigrationPackItem item) throws Exception {
        if (item != null && PAYLOAD_ENTRY_DATA_XML.equals(item.getPayloadType())) {
            return deserializeEntriesXml(item.getPayloadBase64());
        }
        return deserializeEntriesJava(item == null ? null : item.getPayloadBase64());
    }

    private List<Entry> deserializeEntriesJava(String base64) throws Exception {
        byte[] bytes = decompressFromBase64(base64);
        ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes));
        Object obj = in.readObject();
        in.close();
        if (!(obj instanceof List)) {
            throw new ModelException("Embedded entry payload is not a row list.");
        }
        List<Entry> entries = new ArrayList<Entry>();
        for (Object element : (List<?>) obj) {
            if (element instanceof Entry) {
                entries.add((Entry) element);
            }
        }
        return entries;
    }

    private List<Entry> deserializeEntriesXml(String base64) throws Exception {
        byte[] bytes = decompressFromBase64(base64);
        DocumentBuilderFactory factory = secureFactory();
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        Element root = doc.getDocumentElement();
        if (root == null || !"entries".equals(root.getNodeName())) {
            throw new ModelException("Embedded entry XML payload is not a row list.");
        }
        List<Entry> entries = new ArrayList<Entry>();
        NodeList entryNodes = root.getElementsByTagName("entry");
        for (int i = 0; i < entryNodes.getLength(); i++) {
            if (!(entryNodes.item(i) instanceof Element)) continue;
            Element entryElement = (Element) entryNodes.item(i);
            Entry entry = new Entry();
            String entryId = entryElement.getAttribute("entryId");
            if (entryId != null && entryId.length() > 0) entry.setEntryId(entryId);
            NodeList fields = entryElement.getElementsByTagName("field");
            for (int j = 0; j < fields.getLength(); j++) {
                if (!(fields.item(j) instanceof Element)) continue;
                Element fieldElement = (Element) fields.item(j);
                int fieldId = parseInt(fieldElement.getAttribute("id"), -1);
                if (fieldId <= 0) continue;
                entry.put(Integer.valueOf(fieldId), readValue(fieldElement));
            }
            entries.add(entry);
        }
        return entries;
    }

    private Value readValue(Element field) {
        int typeNumber = parseInt(field.getAttribute("type"), DataType.CHAR.toInt());
        DataType type = DataType.toDataType(typeNumber);
        String encoding = field.getAttribute("encoding");
        String text = field.getTextContent();
        try {
            if (DataType.NULL.equals(type)) return new Value();
            if (DataType.ATTACHMENT.equals(type) || "attachment-base64".equals(encoding)) {
                byte[] content = java.util.Base64.getDecoder().decode(text == null ? "" : text);
                AttachmentValue attachment = new AttachmentValue(field.getAttribute("fileName"), content);
                return alias(new Value(attachment), field.getAttribute("alias"));
            }
            if ("base64".equals(encoding)) {
                byte[] content = java.util.Base64.getDecoder().decode(text == null ? "" : text);
                return alias(new Value(new com.bmc.arsys.api.ByteListValue(0, content)), field.getAttribute("alias"));
            }
            if (DataType.INTEGER.equals(type) || DataType.ENUM.equals(type) || DataType.BITMASK.equals(type)) {
                return alias(new Value(Integer.parseInt(emptyToZero(text))), field.getAttribute("alias"));
            }
            if (DataType.ULONG.equals(type)) {
                return alias(new Value(Long.parseLong(emptyToZero(text))), field.getAttribute("alias"));
            }
            if (DataType.REAL.equals(type)) {
                return alias(new Value(Double.parseDouble(emptyToZero(text))), field.getAttribute("alias"));
            }
            if (DataType.DECIMAL.equals(type)) {
                return alias(new Value(new BigDecimal(text == null || text.length() == 0 ? "0" : text)), field.getAttribute("alias"));
            }
            if (DataType.TIME.equals(type)) {
                return alias(new Value(new Timestamp(Long.parseLong(emptyToZero(text)))), field.getAttribute("alias"));
            }
            if (DataType.TIME_OF_DAY.equals(type)) {
                return alias(new Value(new Time(Long.parseLong(emptyToZero(text)))), field.getAttribute("alias"));
            }
            if (DataType.DATE.equals(type)) {
                return alias(new Value(new DateInfo(Integer.parseInt(emptyToZero(text)))), field.getAttribute("alias"));
            }
            return alias(new Value(text == null ? "" : text, type), field.getAttribute("alias"));
        } catch (Throwable ex) {
            Value fallback = new Value(text == null ? "" : text);
            return alias(fallback, field.getAttribute("alias"));
        }
    }

    private Value alias(Value value, String alias) {
        if (value != null && alias != null && alias.length() > 0) {
            value.setAlias(alias);
        }
        return value;
    }

    private DocumentBuilderFactory secureFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Throwable ignored) {
        }
        return factory;
    }

    private int parseInt(String text, int fallback) {
        try { return Integer.parseInt(text); } catch (Throwable ex) { return fallback; }
    }

    private String emptyToZero(String text) {
        return text == null || text.length() == 0 ? "0" : text;
    }

    private String compressToBase64(byte[] bytes) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(raw);
        gzip.write(bytes == null ? new byte[0] : bytes);
        gzip.close();
        return java.util.Base64.getEncoder().encodeToString(raw.toByteArray());
    }

    private byte[] decompressFromBase64(String base64) throws Exception {
        byte[] gz = java.util.Base64.getDecoder().decode(base64 == null ? "" : base64);
        GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }

    private CompareResult identity(MigrationPackItem item, IStore targetStore) {
        CompareResult row = new CompareResult();
        row.setStatus(CompareStatus.CHANGED);
        row.setObjectType(item == null ? "Migration Pack item" : item.getObjectType());
        row.setObjectName(item == null ? "<unknown>" : item.getObjectName());
        row.setSourceServer(item == null ? "" : item.getSourceServer());
        row.setTargetServer(targetStore == null ? (item == null ? "" : item.getTargetServer()) : targetStore.getName());
        row.setSourceContextKey(item == null ? "" : item.getContextKey());
        row.setTargetContextKey(item == null ? "" : item.getContextKey());
        return row;
    }

    public IStore targetStoreForItem(MigrationPackItem item, List<IStore> stores) {
        if (item == null || stores == null) return null;
        String target = item.getTargetServer();
        if (target == null || target.trim().length() == 0) return null;
        for (IStore store : stores) {
            if (store != null && store.getName() != null && store.getName().equalsIgnoreCase(target)) {
                return store;
            }
        }
        return null;
    }

    private CatalogMapping catalogMapping(String objectType) {
        return catalogMappings.get(normalize(objectType));
    }

    private void addCatalog(CatalogMapping mapping) {
        catalogMappings.put(normalize(mapping.objectType), mapping);
    }

    private boolean looksLikeUnknownFieldError(Throwable error) {
        String msg = safeMessage(error).toLowerCase(Locale.ENGLISH);
        return msg.indexOf("unknown field") >= 0 || msg.indexOf("field referenced") >= 0;
    }

    private String safeEntryId(Entry entry) {
        if (entry == null) return "";
        String id = entry.getEntryId();
        return id == null || id.length() == 0 ? entry.getKey() : id;
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ENGLISH).replace(" ", "");
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) return "unknown";
        String msg = ex.getLocalizedMessage();
        return msg == null || msg.length() == 0 ? ex.getClass().getName() : msg;
    }

    private static final class CatalogMapping {
        final String objectType;
        final String formName;
        final String[] keyFields;
        CatalogMapping(String objectType, String formName, String[] keyFields) {
            this.objectType = objectType;
            this.formName = formName;
            this.keyFields = keyFields == null ? new String[0] : keyFields;
        }
    }

    private static final class FieldPolicy {
        boolean metadataKnown;
        BmcDataMigrator.AttachmentPolicy attachmentPolicy = BmcDataMigrator.AttachmentPolicy.SKIP_ATTACHMENTS;
        final List<Integer> writableFieldIds = new ArrayList<Integer>();
        final List<Integer> attachmentFieldIds = new ArrayList<Integer>();
    }

    public static final class EmbeddedDataResult {
        private final String formName;
        int read;
        int createdOrUpdated;
        int failed;
        int skippedFieldValues;
        int skippedAttachmentFieldValues;
        int fieldValuesSent;
        boolean cancelled;
        private final StringBuilder failures = new StringBuilder();

        EmbeddedDataResult(String formName) {
            this.formName = formName == null ? "" : formName;
        }
        void addFailure(String text) {
            failed++;
            if (failures.length() < 3000) {
                if (failures.length() > 0) failures.append('\n');
                failures.append(text);
            }
        }
        public int getRead() { return read; }
        public int getCreatedOrUpdated() { return createdOrUpdated; }
        public int getFailed() { return failed; }
        public String getFailures() { return failures.toString(); }
        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append((cancelled ? "Cancelled embedded import for " : "Imported embedded data for ")).append(formName)
                    .append(" — read ").append(read)
                    .append(", migrated ").append(createdOrUpdated)
                    .append(", failed ").append(failed)
                    .append(", sent ").append(fieldValuesSent).append(" field value(s)")
                    .append(", skipped ").append(skippedFieldValues).append(" field value(s)")
                    .append(", validation bypass: required + pattern/menu checks");
            if (skippedAttachmentFieldValues > 0) {
                b.append(" including ").append(skippedAttachmentFieldValues).append(" attachment field value(s)");
            }
            b.append('.');
            if (failures.length() > 0) {
                b.append(" Failures: ").append(failures.toString().replace('\n', ' '));
            }
            return b.toString();
        }
    }
}
