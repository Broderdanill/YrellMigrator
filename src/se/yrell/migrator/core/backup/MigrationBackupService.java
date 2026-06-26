package se.yrell.migrator.core.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.bmc.arsys.api.ARServerUser;
import com.bmc.arsys.api.Constants;
import com.bmc.arsys.api.Entry;
import com.bmc.arsys.api.StructItemInfo;
import com.bmc.arsys.studio.model.store.ARServerStore;
import com.bmc.arsys.studio.model.store.IEntryStore;
import com.bmc.arsys.studio.model.store.IStore;

import se.yrell.migrator.Activator;
import se.yrell.migrator.bmc.BmcDataMigrator;
import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationPlan;
import se.yrell.migrator.core.MigrationResult;
import se.yrell.migrator.core.pack.MigrationPackItem;
import se.yrell.migrator.core.pack.MigrationPackPayloadService;

/**
 * Creates and restores before-state backups for migrations into an AR Server.
 *
 * Backups are portable ZIP files. Existing target definitions are saved as .def files, entry data is
 * saved as CSV, and objects/rows that did not exist before migration are recorded as delete-on-restore.
 */
public final class MigrationBackupService {
    public static final String FILE_EXTENSION = "*.ymbackup";

    private static final String MANIFEST = "manifest.xml";
    private static final String PREVIEW = "preview.txt";
    private static final String CHECKSUMS = "checksums.sha256";

    private final MigrationPackPayloadService payloadService = new MigrationPackPayloadService();

    public BackupResult createForPack(String runName, List<MigrationPackItem> packItems, List<IStore> stores, File file,
            IProgressMonitor monitor) throws Exception {
        if (file == null) throw new IllegalArgumentException("No backup file supplied.");
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        List<BackupItem> items = new ArrayList<BackupItem>();
        List<MigrationPackItem> source = packItems == null ? Collections.<MigrationPackItem>emptyList() : packItems;
        safeMonitor.beginTask("Creating Yrell Migrator backup", Math.max(1, source.size()));
        int index = 0;
        for (MigrationPackItem packItem : source) {
            index++;
            if (packItem == null) continue;
            safeMonitor.subTask("Backing up before-state " + displayName(packItem));
            IStore target = findStore(packItem.getTargetServer(), stores);
            BackupItem backup = BackupItem.fromPackItem(packItem);
            backup.index = index;
            if (target == null || !target.isConnected()) {
                backup.warning = "Target environment was not connected during backup: " + packItem.getTargetServer();
            } else if (MigrationPackPayloadService.PAYLOAD_AR_DEFINITION.equals(packItem.getPayloadType())) {
                captureTargetDefinition(backup, target);
            } else if (payloadService.isEntryPayloadType(packItem.getPayloadType())) {
                captureTargetEntriesForPackItem(backup, packItem, target);
            } else {
                backup.warning = "Unsupported payload type for backup: " + packItem.getPayloadType();
            }
            items.add(backup);
            safeMonitor.worked(1);
        }
        safeMonitor.done();
        writeBackup(runName, items, file);
        return new BackupResult(items.size(), countExisting(items), countDeleteInstructions(items), buildPreview(runName, items));
    }

    public BackupResult createForMigrationPlan(MigrationPlan plan, boolean includeContainerContent, File file,
            IProgressMonitor monitor) throws Exception {
        if (file == null) throw new IllegalArgumentException("No backup file supplied.");
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        List<BackupItem> items = new ArrayList<BackupItem>();
        List<CompareResult> rows = plan == null ? Collections.<CompareResult>emptyList() : plan.getOrderedRows();
        MigrationDirection direction = plan == null ? MigrationDirection.SOURCE_TO_TARGET : plan.getDirection();
        safeMonitor.beginTask("Creating Yrell Migrator backup", Math.max(1, rows.size()));
        int index = 0;
        for (CompareResult row : rows) {
            index++;
            if (row == null) continue;
            safeMonitor.subTask("Backing up target before-state " + row.getObjectType() + " " + row.getObjectName());
            BackupItem backup = BackupItem.fromCompareRow(row, direction);
            backup.index = index;
            IStore target = direction == MigrationDirection.TARGET_TO_SOURCE ? row.getSourceStore() : row.getTargetStore();
            if (target == null || !target.isConnected()) {
                backup.warning = "Target environment was not connected during backup: " + backup.targetServer;
            } else {
                captureTargetDefinition(backup, target);
            }
            items.add(backup);
            safeMonitor.worked(1);
        }
        safeMonitor.done();
        writeBackup("Object migration " + (plan == null ? "" : plan.getDirection().getLabel()), items, file);
        return new BackupResult(items.size(), countExisting(items), countDeleteInstructions(items), buildPreview("Object migration", items));
    }

    public RestoreResult restore(File file, List<IStore> stores, IProgressMonitor monitor) throws Exception {
        if (file == null) throw new IllegalArgumentException("No backup file supplied.");
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        LoadedBackup backup = loadBackup(file);
        List<BackupItem> items = new ArrayList<BackupItem>(backup.items);
        Collections.reverse(items);
        RestoreResult result = new RestoreResult();
        safeMonitor.beginTask("Restoring Yrell Migrator backup", Math.max(1, items.size()));
        for (BackupItem item : items) {
            if (safeMonitor.isCanceled()) {
                result.cancelled = true;
                break;
            }
            IStore target = findStore(item.targetServer, stores);
            if (target == null || !target.isConnected()) {
                result.failed++;
                result.add("FAILED " + item.displayName() + ": target is not connected: " + item.targetServer);
                continue;
            }
            safeMonitor.subTask("Restoring " + item.displayName());
            try {
                if ("definition".equals(item.kind) && MigrationPackPayloadService.PAYLOAD_AR_DEFINITION.equals(item.payloadType)) {
                    restoreDefinition(item, target, result);
                } else if (payloadService.isEntryPayloadType(item.payloadType)) {
                    restoreEntries(item, target, result);
                } else if (!item.existedBefore && "definition".equals(item.kind)) {
                    deleteDefinition(item, target);
                    result.restored++;
                    result.add("Deleted object that did not exist before migration: " + item.displayName());
                } else {
                    result.warning++;
                    result.add("WARNING unsupported backup item: " + item.displayName() + " payload " + item.payloadType);
                }
            } catch (Throwable ex) {
                result.failed++;
                result.add("FAILED " + item.displayName() + ": " + safeMessage(ex));
            }
            safeMonitor.worked(1);
        }
        safeMonitor.done();
        return result;
    }

    private void captureTargetDefinition(BackupItem backup, IStore target) {
        if (!(target instanceof ARServerStore)) {
            backup.warning = "Target does not expose AR definition export APIs.";
            return;
        }
        int structType = structTypeFor(backup.objectType);
        if (structType < 0) {
            backup.warning = "Unsupported definition type for before-state export: " + backup.objectType;
            return;
        }
        try {
            ARServerUser context = ((ARServerStore) target).getContext();
            List<StructItemInfo> structItems = new ArrayList<StructItemInfo>();
            structItems.add(new StructItemInfo(structType, backup.objectName));
            String definition = context.exportDef(structItems);
            if (definition == null || definition.length() == 0) {
                backup.existedBefore = false;
                backup.deleteOnRestore = true;
                return;
            }
            backup.existedBefore = true;
            backup.payloadType = MigrationPackPayloadService.PAYLOAD_AR_DEFINITION;
            backup.payloadBytes = definition.getBytes("UTF-8");
            backup.payloadExtension = ".def";
        } catch (Throwable ex) {
            backup.existedBefore = false;
            backup.deleteOnRestore = true;
            backup.warning = "Target object was treated as new/not present during backup. Export message: " + safeMessage(ex);
        }
    }

    private void captureTargetEntriesForPackItem(BackupItem backup, MigrationPackItem packItem, IStore target) {
        if (!(target instanceof IEntryStore)) {
            backup.warning = "Target does not support entry backup.";
            return;
        }
        try {
            IEntryStore entryStore = (IEntryStore) target;
            List<Entry> sourceRows = payloadService.getEmbeddedEntries(packItem);
            List<Entry> beforeRows = new ArrayList<Entry>();
            List<String> deleteIds = new ArrayList<String>();
            for (Entry row : sourceRows) {
                String id = safeEntryId(row);
                if (id.length() == 0) continue;
                try {
                    Entry existing = entryStore.getEntry(packItem.getFormName(), id, null);
                    if (existing != null) {
                        beforeRows.add((Entry) existing.clone());
                    } else {
                        deleteIds.add(id);
                    }
                } catch (Throwable missing) {
                    deleteIds.add(id);
                }
            }
            backup.existedBefore = !beforeRows.isEmpty();
            backup.deleteEntryIds.addAll(deleteIds);
            backup.payloadType = MigrationPackPayloadService.PAYLOAD_ENTRY_DATA_CSV;
            backup.payloadBytes = payloadService.toOpenPayloadBytes(entryItem(packItem, beforeRows));
            backup.payloadExtension = ".csv";
            backup.entryRows = beforeRows.size();
            backup.deleteOnRestore = !deleteIds.isEmpty();
        } catch (Throwable ex) {
            backup.warning = "Could not capture entry before-state: " + safeMessage(ex);
        }
    }

    private MigrationPackItem entryItem(MigrationPackItem source, List<Entry> entries) throws Exception {
        MigrationPackItem copy = new MigrationPackItem();
        copy.setKind(source == null ? MigrationPackItem.KIND_FORM_DATA : source.getKind());
        copy.setObjectType(source == null ? "Form data" : source.getObjectType());
        copy.setObjectName(source == null ? "" : source.getObjectName());
        copy.setFormName(source == null ? "" : source.getFormName());
        copy.setTargetServer(source == null ? "" : source.getTargetServer());
        copy.setConflictMode(BmcDataMigrator.ConflictMode.PRESERVE_ID_OVERWRITE);
        copy.setAttachmentPolicy(BmcDataMigrator.AttachmentPolicy.INCLUDE_ATTACHMENTS);
        copy.setFilterToTargetWritableFields(true);
        copy.setPayloadType(MigrationPackPayloadService.PAYLOAD_ENTRY_DATA_CSV);
        copy.setPayloadBase64(payloadService.serializeEntriesCsvBase64(entries));
        copy.setEmbeddedRowCount(entries == null ? 0 : entries.size());
        return copy;
    }

    private void restoreDefinition(BackupItem item, IStore target, RestoreResult result) throws Exception {
        if (item.existedBefore && item.payloadBytes != null && item.payloadBytes.length > 0) {
            MigrationPackItem packItem = new MigrationPackItem();
            packItem.setKind(MigrationPackItem.KIND_DEFINITION);
            packItem.setObjectType(item.objectType);
            packItem.setObjectName(item.objectName);
            packItem.setTargetServer(item.targetServer);
            packItem.setPayloadType(MigrationPackPayloadService.PAYLOAD_AR_DEFINITION);
            packItem.setPayloadBase64(gzipToBase64(item.payloadBytes));
            MigrationResult mr = payloadService.importDefinition(packItem, target, new NullProgressMonitor());
            if (mr != null && mr.isSuccess()) {
                result.restored++;
                result.add("Restored definition: " + item.displayName());
            } else {
                result.failed++;
                result.add("FAILED restore definition " + item.displayName() + ": " + (mr == null ? "no result" : mr.getDetail()));
            }
        } else if (item.deleteOnRestore) {
            deleteDefinition(item, target);
            result.restored++;
            result.add("Deleted definition that did not exist before migration: " + item.displayName());
        }
    }

    private void restoreEntries(BackupItem item, IStore target, RestoreResult result) throws Exception {
        if (item.deleteEntryIds != null && !item.deleteEntryIds.isEmpty() && target instanceof IEntryStore) {
            IEntryStore entryStore = (IEntryStore) target;
            for (String id : item.deleteEntryIds) {
                try {
                    entryStore.deleteEntry(item.formName, id, 0);
                    result.restored++;
                    result.add("Deleted row that did not exist before migration: " + item.formName + " " + id);
                } catch (Throwable ex) {
                    result.warning++;
                    result.add("WARNING could not delete row " + item.formName + " " + id + ": " + safeMessage(ex));
                }
            }
        }
        if (item.existedBefore && item.payloadBytes != null && item.payloadBytes.length > 0) {
            MigrationPackItem packItem = new MigrationPackItem();
            packItem.setKind(MigrationPackItem.KIND_FORM_DATA);
            packItem.setObjectType(item.objectType);
            packItem.setObjectName(item.objectName);
            packItem.setFormName(item.formName);
            packItem.setTargetServer(item.targetServer);
            packItem.setPayloadType(MigrationPackPayloadService.PAYLOAD_ENTRY_DATA_CSV);
            packItem.setPayloadBase64(gzipToBase64(item.payloadBytes));
            packItem.setEmbeddedRowCount(item.entryRows);
            packItem.setConflictMode(BmcDataMigrator.ConflictMode.PRESERVE_ID_OVERWRITE);
            packItem.setAttachmentPolicy(BmcDataMigrator.AttachmentPolicy.INCLUDE_ATTACHMENTS);
            MigrationPackPayloadService.EmbeddedDataResult dr = payloadService.importEntries(packItem, target, new NullProgressMonitor());
            if (dr.getFailed() > 0) {
                result.warning++;
                result.add("WARNING restored entry data with failures: " + dr.summary());
            } else {
                result.restored++;
                result.add("Restored entry data: " + item.displayName() + " rows " + dr.getCreatedOrUpdated());
            }
        }
    }

    private void deleteDefinition(BackupItem item, IStore target) throws Exception {
        if (!(target instanceof ARServerStore)) {
            throw new IllegalStateException("Target does not expose AR delete APIs.");
        }
        ARServerUser context = ((ARServerStore) target).getContext();
        int type = structTypeFor(item.objectType);
        String name = item.objectName;
        if (type == StructItemInfo.SCHEMA) context.deleteForm(name, 0);
        else if (type == StructItemInfo.ACTIVE_LINK) context.deleteActiveLink(name, 0);
        else if (type == StructItemInfo.FILTER) context.deleteFilter(name, 0);
        else if (type == StructItemInfo.ESCALATION) context.deleteEscalation(name, 0);
        else if (type == StructItemInfo.CHAR_MENU) context.deleteMenu(name, 0);
        else if (type == StructItemInfo.IMAGE) context.deleteImage(name, false);
        else if (type == StructItemInfo.APPLICATION) context.deleteContainer(name, 0);
        else if (type == StructItemInfo.CONTAINER) context.deleteContainer(name, 0);
        else if (type == StructItemInfo.ASSOCIATION) context.deleteAssociation(name, 0);
        else throw new IllegalStateException("Delete is not implemented for definition type " + item.objectType + ".");
    }

    private void writeBackup(String runName, List<BackupItem> items, File file) throws Exception {
        Document doc = newDocument();
        Element root = doc.createElement("yrellMigrationBackup");
        root.setAttribute("version", "1");
        root.setAttribute("createdAt", String.valueOf(System.currentTimeMillis()));
        root.setAttribute("runName", runName == null ? "" : runName);
        root.setAttribute("itemCount", String.valueOf(items == null ? 0 : items.size()));
        doc.appendChild(root);
        List<PayloadFile> payloads = new ArrayList<PayloadFile>();
        int index = 0;
        if (items != null) {
            for (BackupItem item : items) {
                index++;
                Element e = doc.createElement("item");
                e.setAttribute("index", String.valueOf(index));
                e.setAttribute("kind", item.kind);
                e.setAttribute("objectType", item.objectType);
                e.setAttribute("objectName", item.objectName);
                e.setAttribute("formName", item.formName);
                e.setAttribute("targetServer", item.targetServer);
                e.setAttribute("payloadType", item.payloadType);
                e.setAttribute("existedBefore", String.valueOf(item.existedBefore));
                e.setAttribute("deleteOnRestore", String.valueOf(item.deleteOnRestore));
                e.setAttribute("deleteEntryIds", join(item.deleteEntryIds));
                e.setAttribute("entryRows", String.valueOf(item.entryRows));
                e.setAttribute("warning", item.warning);
                if (item.payloadBytes != null && item.payloadBytes.length > 0) {
                    String folder = item.payloadExtension.equals(".def") ? "definitions" : "data";
                    String ref = folder + "/backup-" + zero(index) + "-" + safeFileToken(item.displayName()) + item.payloadExtension;
                    String sha = sha256(item.payloadBytes);
                    e.setAttribute("payloadRef", ref);
                    e.setAttribute("payloadSha256", sha);
                    e.setAttribute("payloadSize", String.valueOf(item.payloadBytes.length));
                    payloads.add(new PayloadFile(ref, item.payloadBytes, sha));
                }
                root.appendChild(e);
            }
        }
        ByteArrayOutputStream manifest = new ByteArrayOutputStream();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(doc), new StreamResult(manifest));
        ZipOutputStream zip = new ZipOutputStream(new java.io.FileOutputStream(file));
        try {
            put(zip, MANIFEST, manifest.toByteArray());
            put(zip, PREVIEW, buildPreview(runName, items).getBytes("UTF-8"));
            StringBuilder sums = new StringBuilder();
            sums.append(sha256(manifest.toByteArray())).append("  ").append(MANIFEST).append('\n');
            for (PayloadFile payload : payloads) {
                put(zip, payload.path, payload.bytes);
                sums.append(payload.sha256).append("  ").append(payload.path).append('\n');
            }
            put(zip, CHECKSUMS, sums.toString().getBytes("UTF-8"));
        } finally {
            zip.close();
        }
    }

    private LoadedBackup loadBackup(File file) throws Exception {
        ZipFile zip = new ZipFile(file);
        try {
            ZipEntry manifest = zip.getEntry(MANIFEST);
            if (manifest == null) throw new IllegalArgumentException("Backup is missing manifest.xml.");
            DocumentBuilderFactory factory = secureFactory();
            Document doc = factory.newDocumentBuilder().parse(zip.getInputStream(manifest));
            Element root = doc.getDocumentElement();
            if (root == null || !"yrellMigrationBackup".equals(root.getNodeName())) {
                throw new IllegalArgumentException("This is not a Yrell Migrator backup file.");
            }
            LoadedBackup backup = new LoadedBackup();
            backup.runName = root.getAttribute("runName");
            NodeList nodes = root.getElementsByTagName("item");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element)) continue;
                Element e = (Element) nodes.item(i);
                BackupItem item = new BackupItem();
                item.kind = e.getAttribute("kind");
                item.objectType = e.getAttribute("objectType");
                item.objectName = e.getAttribute("objectName");
                item.formName = e.getAttribute("formName");
                item.targetServer = e.getAttribute("targetServer");
                item.payloadType = e.getAttribute("payloadType");
                item.existedBefore = Boolean.parseBoolean(e.getAttribute("existedBefore"));
                item.deleteOnRestore = Boolean.parseBoolean(e.getAttribute("deleteOnRestore"));
                item.deleteEntryIds.addAll(split(e.getAttribute("deleteEntryIds")));
                item.entryRows = parseInt(e.getAttribute("entryRows"), 0);
                item.warning = e.getAttribute("warning");
                String ref = e.getAttribute("payloadRef");
                if (ref != null && ref.length() > 0) {
                    ZipEntry payload = zip.getEntry(ref);
                    if (payload == null) throw new IllegalArgumentException("Backup is missing payload " + ref);
                    item.payloadBytes = readAll(zip.getInputStream(payload));
                    String expected = e.getAttribute("payloadSha256");
                    if (expected != null && expected.length() > 0 && !expected.equalsIgnoreCase(sha256(item.payloadBytes))) {
                        throw new IllegalArgumentException("Checksum mismatch for " + ref);
                    }
                }
                backup.items.add(item);
            }
            return backup;
        } finally {
            zip.close();
        }
    }

    private String buildPreview(String runName, List<BackupItem> items) {
        StringBuilder b = new StringBuilder();
        b.append("Yrell Migrator Backup\n");
        b.append("Run: ").append(runName == null ? "" : runName).append('\n');
        b.append("Created: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        b.append("Items: ").append(items == null ? 0 : items.size()).append('\n');
        if (items != null) {
            int i = 0;
            for (BackupItem item : items) {
                i++;
                b.append('\n').append(i).append(". ").append(item.displayName()).append('\n')
                        .append("   Target: ").append(item.targetServer).append('\n')
                        .append("   Existed before: ").append(item.existedBefore).append('\n')
                        .append("   Payload: ").append(item.payloadType == null || item.payloadType.length() == 0 ? "none" : item.payloadType).append('\n');
                if (item.deleteOnRestore || !item.deleteEntryIds.isEmpty()) {
                    b.append("   Restore delete instruction: yes").append(item.deleteEntryIds.isEmpty() ? "" : " rows " + item.deleteEntryIds).append('\n');
                }
                if (item.warning != null && item.warning.length() > 0) b.append("   Warning: ").append(item.warning).append('\n');
            }
        }
        return b.toString();
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
        return -1;
    }

    private IStore findStore(String name, List<IStore> stores) {
        if (name == null || stores == null) return null;
        for (IStore store : stores) {
            if (store != null && store.getName() != null && store.getName().equalsIgnoreCase(name)) return store;
        }
        return null;
    }

    private String safeEntryId(Entry entry) {
        if (entry == null) return "";
        String id = entry.getEntryId();
        return id == null || id.length() == 0 ? entry.getKey() : id;
    }

    private String displayName(MigrationPackItem item) {
        if (item == null) return "<empty>";
        return item.isFormData() ? "Form data " + item.getFormName() : item.getObjectType() + " " + item.getObjectName();
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ENGLISH).replace(" ", "");
    }

    private int countExisting(List<BackupItem> items) {
        int count = 0;
        for (BackupItem item : items) if (item != null && item.existedBefore) count++;
        return count;
    }

    private int countDeleteInstructions(List<BackupItem> items) {
        int count = 0;
        for (BackupItem item : items) if (item != null && (item.deleteOnRestore || !item.deleteEntryIds.isEmpty())) count++;
        return count;
    }

    private Document newDocument() throws Exception {
        return secureFactory().newDocumentBuilder().newDocument();
    }

    private DocumentBuilderFactory secureFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Throwable ignored) {}
        return factory;
    }

    private void put(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(bytes == null ? new byte[0] : bytes);
        zip.closeEntry();
    }

    private byte[] readAll(java.io.InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        in.close();
        return out.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hash.length; i++) sb.append(String.format("%02x", hash[i] & 0xff));
        return sb.toString();
    }

    private String gzipToBase64(byte[] bytes) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(raw);
        gzip.write(bytes == null ? new byte[0] : bytes);
        gzip.close();
        return java.util.Base64.getEncoder().encodeToString(raw.toByteArray());
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String value : values) {
            if (value == null || value.length() == 0) continue;
            if (b.length() > 0) b.append('|');
            b.append(value.replace("|", ""));
        }
        return b.toString();
    }

    private List<String> split(String text) {
        List<String> values = new ArrayList<String>();
        if (text == null || text.length() == 0) return values;
        String[] parts = text.split("\\|");
        for (int i = 0; i < parts.length; i++) if (parts[i].length() > 0) values.add(parts[i]);
        return values;
    }

    private int parseInt(String text, int fallback) {
        try { return Integer.parseInt(text); } catch (Throwable ex) { return fallback; }
    }

    private String safeFileToken(String text) {
        String value = text == null ? "item" : text.trim();
        if (value.length() == 0) value = "item";
        value = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (value.length() > 80) value = value.substring(0, 80);
        return value;
    }

    private String zero(int index) {
        return String.format("%04d", Integer.valueOf(index));
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) return "unknown";
        String msg = ex.getLocalizedMessage();
        return msg == null || msg.length() == 0 ? ex.getClass().getName() : msg;
    }

    private static final class BackupItem {
        int index;
        String kind = "definition";
        String objectType = "";
        String objectName = "";
        String formName = "";
        String targetServer = "";
        String payloadType = "";
        String payloadExtension = ".bin";
        byte[] payloadBytes;
        boolean existedBefore;
        boolean deleteOnRestore;
        int entryRows;
        String warning = "";
        final List<String> deleteEntryIds = new ArrayList<String>();

        static BackupItem fromPackItem(MigrationPackItem item) {
            BackupItem b = new BackupItem();
            b.kind = item == null ? "definition" : item.getKind();
            b.objectType = item == null ? "" : item.getObjectType();
            b.objectName = item == null ? "" : item.getObjectName();
            b.formName = item == null ? "" : item.getFormName();
            b.targetServer = item == null ? "" : item.getTargetServer();
            return b;
        }

        static BackupItem fromCompareRow(CompareResult row, MigrationDirection direction) {
            BackupItem b = new BackupItem();
            b.kind = "definition";
            b.objectType = row == null ? "" : row.getObjectType();
            b.objectName = row == null ? "" : row.getObjectName();
            b.formName = row == null ? "" : row.getPrimaryFormSummary();
            b.targetServer = row == null ? "" : (direction == MigrationDirection.TARGET_TO_SOURCE ? row.getSourceServer() : row.getTargetServer());
            return b;
        }

        String displayName() {
            return ("formData".equals(kind) ? "Form data " + formName : objectType + " " + objectName).trim();
        }
    }

    private static final class PayloadFile {
        final String path;
        final byte[] bytes;
        final String sha256;
        PayloadFile(String path, byte[] bytes, String sha256) {
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class LoadedBackup {
        String runName = "";
        final List<BackupItem> items = new ArrayList<BackupItem>();
    }

    public static final class BackupResult {
        private final int items;
        private final int existingItems;
        private final int deleteInstructions;
        private final String report;
        BackupResult(int items, int existingItems, int deleteInstructions, String report) {
            this.items = items;
            this.existingItems = existingItems;
            this.deleteInstructions = deleteInstructions;
            this.report = report == null ? "" : report;
        }
        public int getItems() { return items; }
        public int getExistingItems() { return existingItems; }
        public int getDeleteInstructions() { return deleteInstructions; }
        public String getReport() { return report; }
        public String summary() {
            return "Backup items " + items + ", existing before-state " + existingItems
                    + ", delete-on-restore instructions " + deleteInstructions;
        }
    }

    public static final class RestoreResult {
        private int restored;
        private int warning;
        private int failed;
        private boolean cancelled;
        private final StringBuilder details = new StringBuilder();
        void add(String line) {
            if (details.length() > 0) details.append('\n');
            details.append(line);
        }
        public int getRestored() { return restored; }
        public int getWarnings() { return warning; }
        public int getFailed() { return failed; }
        public boolean isCancelled() { return cancelled; }
        public String getDetails() { return details.toString(); }
        public String report() {
            return "Yrell Migrator Backup Restore\nRestored/deleted: " + restored + "\nWarnings: " + warning
                    + "\nFailed: " + failed + (cancelled ? "\nCancelled: yes" : "") + "\n\n" + details.toString();
        }
    }
}
