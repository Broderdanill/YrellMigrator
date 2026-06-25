package se.yrell.migrator.core.pack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import se.yrell.migrator.bmc.BmcDataMigrator;
import se.yrell.migrator.core.MigrationDirection;

/** Import/export for portable .ympack files. Legacy .hlxpack files still load. */
public final class MigrationPackStorage {
    public static final String FILE_EXTENSION = "*.ympack";
    public static final String LEGACY_FILE_EXTENSION = "*.hlxpack";
    private static final String MANIFEST = "manifest.xml";
    private static final String PREVIEW = "preview.txt";
    private static final String README = "README.txt";
    private static final String CHECKSUMS = "checksums.sha256";

    public void save(MigrationPack pack, File file) throws Exception {
        if (pack == null) throw new IllegalArgumentException("No migration pack supplied.");
        if (file == null) throw new IllegalArgumentException("No file supplied.");
        saveZip(pack, file);
    }

    public MigrationPack load(File file) throws Exception {
        if (file == null) throw new IllegalArgumentException("No file supplied.");
        if (looksLikeZip(file)) {
            return loadZip(file);
        }
        FileInputStream in = new FileInputStream(file);
        try {
            return loadXml(in, null);
        } finally {
            in.close();
        }
    }

    private void saveZip(MigrationPack pack, File file) throws Exception {
        List<PayloadFile> payloads = new ArrayList<PayloadFile>();
        Document doc = newDocument();
        Element root = doc.createElement("yrellMigrationPack");
        root.setAttribute("version", "3");
        root.setAttribute("format", "zip");
        root.setAttribute("name", pack.getName());
        root.setAttribute("createdAt", String.valueOf(pack.getCreatedAtMillis()));
        root.setAttribute("modifiedAt", String.valueOf(System.currentTimeMillis()));
        root.setAttribute("definitionCount", String.valueOf(pack.getDefinitionCount()));
        root.setAttribute("formDataCount", String.valueOf(pack.getFormDataCount()));
        doc.appendChild(root);

        int index = 0;
        for (MigrationPackItem item : pack.getItems()) {
            if (item == null) continue;
            index++;
            Element e = itemElement(doc, item);
            if (item.getPayloadBase64() != null && item.getPayloadBase64().length() > 0) {
                byte[] payloadBytes = java.util.Base64.getDecoder().decode(item.getPayloadBase64());
                String ref = "payloads/item-" + zero(index) + ".bin";
                String sha = sha256(payloadBytes);
                payloads.add(new PayloadFile(ref, payloadBytes, sha));
                e.setAttribute("payloadRef", ref);
                e.setAttribute("payloadSha256", sha);
                e.setAttribute("payloadSize", String.valueOf(payloadBytes.length));
            }
            root.appendChild(e);
        }

        ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(doc), new StreamResult(manifestBytes));

        ZipOutputStream zip = new ZipOutputStream(new java.io.FileOutputStream(file));
        try {
            put(zip, MANIFEST, manifestBytes.toByteArray());
            put(zip, PREVIEW, buildPreview(pack).getBytes("UTF-8"));
            put(zip, README, buildReadme().getBytes("UTF-8"));
            StringBuilder sums = new StringBuilder();
            sums.append(sha256(manifestBytes.toByteArray())).append("  ").append(MANIFEST).append('\n');
            for (PayloadFile payload : payloads) {
                put(zip, payload.path, payload.bytes);
                sums.append(payload.sha256).append("  ").append(payload.path).append('\n');
            }
            put(zip, CHECKSUMS, sums.toString().getBytes("UTF-8"));
        } finally {
            zip.close();
        }
    }

    private MigrationPack loadZip(File file) throws Exception {
        ZipFile zip = new ZipFile(file);
        try {
            ZipEntry manifest = zip.getEntry(MANIFEST);
            if (manifest == null) {
                throw new IllegalArgumentException("The migration pack file does not contain " + MANIFEST + ".");
            }
            MigrationPack pack = loadXml(zip.getInputStream(manifest), zip);
            return pack;
        } finally {
            zip.close();
        }
    }

    private MigrationPack loadXml(InputStream stream, ZipFile zip) throws Exception {
        DocumentBuilderFactory factory = secureFactory();
        Document doc = factory.newDocumentBuilder().parse(stream);
        Element root = doc.getDocumentElement();
        if (root == null || !("yrellMigrationPack".equals(root.getNodeName()) || "helixMigrationPack".equals(root.getNodeName()))) {
            throw new IllegalArgumentException("The file is not a Yrell Migrator migration pack.");
        }
        MigrationPack pack = new MigrationPack();
        pack.setName(root.getAttribute("name"));
        pack.setCreatedAtMillis(parseLong(root.getAttribute("createdAt"), System.currentTimeMillis()));
        pack.setModifiedAtMillis(parseLong(root.getAttribute("modifiedAt"), System.currentTimeMillis()));
        pack.clear();
        NodeList nodes = root.getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element)) continue;
            Element e = (Element) nodes.item(i);
            MigrationPackItem item = new MigrationPackItem();
            item.setKind(e.getAttribute("kind"));
            item.setDirection(parseDirection(e.getAttribute("direction")));
            item.setObjectType(e.getAttribute("objectType"));
            item.setObjectName(e.getAttribute("objectName"));
            item.setFormName(e.getAttribute("formName"));
            item.setSourceServer(e.getAttribute("sourceServer"));
            item.setTargetServer(e.getAttribute("targetServer"));
            item.setContextKey(e.getAttribute("contextKey"));
            item.setQualification(e.getAttribute("qualification"));
            item.setMaxRows(parseInt(e.getAttribute("maxRows"), 0));
            item.setConflictMode(parseEnum(BmcDataMigrator.ConflictMode.class, e.getAttribute("conflictMode"), BmcDataMigrator.ConflictMode.PRESERVE_ID_OVERWRITE));
            item.setAttachmentPolicy(parseEnum(BmcDataMigrator.AttachmentPolicy.class, e.getAttribute("attachmentPolicy"), BmcDataMigrator.AttachmentPolicy.SKIP_ATTACHMENTS));
            item.setFilterToTargetWritableFields(parseBoolean(e.getAttribute("filterToTargetWritableFields"), true));
            item.setRunWorkflow(parseBoolean(e.getAttribute("runWorkflow"), false));
            item.setDryRun(parseBoolean(e.getAttribute("dryRun"), false));
            item.setPayloadType(e.getAttribute("payloadType"));
            item.setEmbeddedRowCount(parseInt(e.getAttribute("embeddedRows"), 0));
            item.setEmbeddedObjectCount(parseInt(e.getAttribute("embeddedObjects"), 0));
            item.setCapturedAtMillis(parseLong(e.getAttribute("capturedAt"), 0L));
            item.setCaptureSummary(e.getAttribute("captureSummary"));
            String payloadRef = e.getAttribute("payloadRef");
            if (payloadRef != null && payloadRef.length() > 0 && zip != null) {
                ZipEntry payload = zip.getEntry(payloadRef);
                if (payload == null) {
                    throw new IllegalArgumentException("Missing payload in migration pack: " + payloadRef);
                }
                byte[] payloadBytes = readAll(zip.getInputStream(payload));
                String expectedSha = e.getAttribute("payloadSha256");
                if (expectedSha != null && expectedSha.length() > 0) {
                    String actualSha = sha256(payloadBytes);
                    if (!expectedSha.equalsIgnoreCase(actualSha)) {
                        throw new IllegalArgumentException("Payload checksum mismatch for " + payloadRef + ".");
                    }
                }
                item.setPayloadBase64(java.util.Base64.getEncoder().encodeToString(payloadBytes));
            } else {
                NodeList payloads = e.getElementsByTagName("payload");
                if (payloads != null && payloads.getLength() > 0) {
                    item.setPayloadBase64(payloads.item(0).getTextContent());
                } else {
                    item.setPayloadBase64(e.getAttribute("payloadBase64"));
                }
            }
            item.setAddedAtMillis(parseLong(e.getAttribute("addedAt"), System.currentTimeMillis()));
            item.setLastRunOutcome(e.getAttribute("lastRunOutcome"));
            item.setLastRunMessage(e.getAttribute("lastRunMessage"));
            item.setLastRunAtMillis(parseLong(e.getAttribute("lastRunAt"), 0L));
            pack.add(item);
        }
        return pack;
    }

    private Element itemElement(Document doc, MigrationPackItem item) {
        Element e = doc.createElement("item");
        e.setAttribute("kind", item.getKind());
        e.setAttribute("direction", item.getDirection().name());
        e.setAttribute("objectType", item.getObjectType());
        e.setAttribute("objectName", item.getObjectName());
        e.setAttribute("formName", item.getFormName());
        e.setAttribute("sourceServer", item.getSourceServer());
        e.setAttribute("targetServer", item.getTargetServer());
        e.setAttribute("contextKey", item.getContextKey());
        e.setAttribute("qualification", item.getQualification());
        e.setAttribute("maxRows", String.valueOf(item.getMaxRows()));
        e.setAttribute("conflictMode", item.getConflictMode().name());
        e.setAttribute("attachmentPolicy", item.getAttachmentPolicy().name());
        e.setAttribute("filterToTargetWritableFields", String.valueOf(item.isFilterToTargetWritableFields()));
        e.setAttribute("runWorkflow", String.valueOf(item.isRunWorkflow()));
        e.setAttribute("dryRun", String.valueOf(item.isDryRun()));
        e.setAttribute("payloadType", item.getPayloadType());
        e.setAttribute("embeddedRows", String.valueOf(item.getEmbeddedRowCount()));
        e.setAttribute("embeddedObjects", String.valueOf(item.getEmbeddedObjectCount()));
        e.setAttribute("capturedAt", String.valueOf(item.getCapturedAtMillis()));
        e.setAttribute("captureSummary", item.getCaptureSummary());
        e.setAttribute("addedAt", String.valueOf(item.getAddedAtMillis()));
        e.setAttribute("lastRunOutcome", item.getLastRunOutcome());
        e.setAttribute("lastRunMessage", item.getLastRunMessage());
        e.setAttribute("lastRunAt", String.valueOf(item.getLastRunAtMillis()));
        return e;
    }

    public String buildPreview(MigrationPack pack) {
        StringBuilder b = new StringBuilder();
        b.append("Yrell Migrator Migration Pack\n");
        b.append("Name: ").append(pack == null ? "" : pack.getName()).append('\n');
        b.append("Items: ").append(pack == null ? 0 : pack.size()).append('\n');
        b.append("Definitions: ").append(pack == null ? 0 : pack.getDefinitionCount()).append('\n');
        b.append("Form data scopes: ").append(pack == null ? 0 : pack.getFormDataCount()).append('\n');
        if (pack != null) {
            int i = 0;
            for (MigrationPackItem item : pack.getItems()) {
                if (item == null) continue;
                i++;
                b.append('\n').append(i).append(". ")
                        .append(item.isFormData() ? "Form data" : "Definition")
                        .append(" — ").append(item.getObjectType()).append(' ')
                        .append(item.isFormData() ? item.getFormName() : item.getObjectName()).append('\n')
                        .append("   Phase: ").append(item.executionPhaseLabel()).append('\n')
                        .append("   Target: ").append(item.getTargetServer()).append('\n')
                        .append("   Payload: ").append(item.getPayloadType()).append(item.hasEmbeddedPayload() ? " embedded" : " missing").append('\n');
                if (item.getEmbeddedRowCount() > 0) b.append("   Rows: ").append(item.getEmbeddedRowCount()).append('\n');
                if (item.getCaptureSummary().length() > 0) b.append("   Capture: ").append(item.getCaptureSummary()).append('\n');
                if (item.getLastRunOutcome().length() > 0) b.append("   Last run: ").append(item.lastRunSummary()).append('\n');
            }
        }
        return b.toString();
    }

    private String buildReadme() {
        return "This is a Yrell Migrator .ympack ZIP package.\n"
                + "manifest.xml contains package metadata. payloads/ contains embedded AR definitions and form data.\n"
                + "preview.txt shows the planned item order and payload summary.\n"
                + "checksums.sha256 can be used for troubleshooting/corruption checks.\n";
    }

    private boolean looksLikeZip(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            int a = in.read();
            int b = in.read();
            return a == 'P' && b == 'K';
        } finally {
            in.close();
        }
    }

    private void put(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(bytes == null ? new byte[0] : bytes);
        zip.closeEntry();
    }

    private byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private Document newDocument() throws Exception {
        DocumentBuilder builder = secureFactory().newDocumentBuilder();
        return builder.newDocument();
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

    private String zero(int index) {
        String text = String.valueOf(index);
        while (text.length() < 5) text = "0" + text;
        return text;
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder b = new StringBuilder();
        for (byte value : hash) {
            String s = Integer.toHexString(value & 0xff);
            if (s.length() == 1) b.append('0');
            b.append(s);
        }
        return b.toString().toLowerCase(Locale.ENGLISH);
    }

    private MigrationDirection parseDirection(String text) {
        try { return MigrationDirection.valueOf(text); } catch (Throwable ex) { return MigrationDirection.SOURCE_TO_TARGET; }
    }

    private int parseInt(String text, int fallback) {
        try { return Integer.parseInt(text); } catch (Throwable ex) { return fallback; }
    }

    private long parseLong(String text, long fallback) {
        try { return Long.parseLong(text); } catch (Throwable ex) { return fallback; }
    }

    private boolean parseBoolean(String text, boolean fallback) {
        if (text == null || text.length() == 0) return fallback;
        return Boolean.valueOf(text).booleanValue();
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String text, T fallback) {
        try { return Enum.valueOf(type, text); } catch (Throwable ex) { return fallback; }
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
}
