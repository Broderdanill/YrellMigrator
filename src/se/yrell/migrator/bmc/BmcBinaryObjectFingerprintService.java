package se.yrell.migrator.bmc;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

import se.yrell.migrator.core.DiffDetail;

/**
 * Conservative fingerprinting for Developer Studio binary-like objects such as Images and Support Files.
 *
 * The service only invokes no-argument public getters and intentionally avoids reading InputStreams.
 * Some Developer Studio builds expose binary content as byte[]/ByteBuffer while others only expose
 * metadata until the editor opens the object. The resulting profile therefore records both byte-level
 * evidence and the available metadata/hints so comparisons remain transparent rather than silently
 * pretending to be complete.
 */
public final class BmcBinaryObjectFingerprintService {
    private static final int MAX_DEPTH = 2;
    private static final int MAX_ITEMS = 80;

    public boolean isBinaryLikeType(String typeName) {
        String lower = normalize(typeName);
        return lower.indexOf("image") >= 0
                || lower.indexOf("supportfile") >= 0
                || lower.indexOf("support file") >= 0
                || lower.indexOf("binary") >= 0
                || lower.indexOf("attachment") >= 0;
    }

    public List<DiffDetail> compare(String objectType, Object sourceObject, Object targetObject) {
        List<DiffDetail> details = new ArrayList<DiffDetail>();
        if (!isBinaryLikeType(objectType)) {
            return details;
        }
        Profile source = inspect(sourceObject);
        Profile target = inspect(targetObject);
        if (!safe(source.fingerprint).equals(safe(target.fingerprint))) {
            details.add(new DiffDetail("Binary", "Fingerprint", source.fingerprintSummary(), target.fingerprintSummary(), "binary differs"));
        }
        if (source.byteCount != target.byteCount) {
            details.add(new DiffDetail("Binary", "Discovered bytes", String.valueOf(source.byteCount), String.valueOf(target.byteCount), "binary size differs"));
        }
        if (source.binaryMembers != target.binaryMembers) {
            details.add(new DiffDetail("Binary", "Discovered binary members", String.valueOf(source.binaryMembers), String.valueOf(target.binaryMembers), "binary member count differs"));
        }
        if (!safe(source.metadataSummary()).equals(safe(target.metadataSummary()))) {
            details.add(new DiffDetail("Binary", "Metadata profile", source.metadataSummary(), target.metadataSummary(), "binary metadata differs"));
        }
        if (!source.isComplete() || !target.isComplete()) {
            details.add(new DiffDetail("Binary", "Profile quality", source.qualitySummary(), target.qualitySummary(), "binary profile quality"));
        }
        return details;
    }

    public Profile inspect(Object object) {
        Collector collector = new Collector();
        if (object == null) {
            collector.notes.add("object not loaded");
            return collector.toProfile();
        }
        collector.metadata.add("class=" + object.getClass().getName());
        collectObject(object, "Object", 0, new IdentityHashMap<Object, Boolean>(), collector);
        return collector.toProfile();
    }

    private void collectObject(Object object, String path, int depth, IdentityHashMap<Object, Boolean> seen, Collector collector) {
        if (object == null || depth > MAX_DEPTH || collector.itemCount >= MAX_ITEMS) {
            return;
        }
        if (seen.containsKey(object)) {
            return;
        }
        seen.put(object, Boolean.TRUE);
        try {
            Method[] methods = BmcReflectionSupport.safePublicMethods(object);
            for (int i = 0; i < methods.length && collector.itemCount < MAX_ITEMS; i++) {
                Method method = methods[i];
                if (!BmcReflectionSupport.isSafeNoArgGetter(method)) {
                    continue;
                }
                String property = BmcReflectionSupport.propertyName(method);
                String propLower = normalize(property);
                Class<?> returnType = method.getReturnType();
                boolean binaryHint = hasBinaryHint(propLower) || isBinaryReturnType(returnType);
                boolean metadataHint = isMetadataProperty(propLower);
                if (!binaryHint && !metadataHint && depth > 0) {
                    continue;
                }
                Object value;
                try {
                    value = BmcReflectionSupport.invokeNoArg(object, method);
                } catch (Throwable ex) {
                    if (binaryHint || metadataHint) {
                        collector.notes.add(path + "." + property + " threw " + BmcReflectionSupport.safeMessage(ex));
                    }
                    continue;
                }
                if (value == null) {
                    continue;
                }
                String memberPath = path + "." + property;
                if (value instanceof byte[]) {
                    collector.addBytes(memberPath, (byte[]) value);
                } else if (value instanceof ByteBuffer) {
                    collector.addByteBuffer(memberPath, (ByteBuffer) value);
                } else if (value instanceof File) {
                    File file = (File) value;
                    collector.metadata.add(memberPath + "=" + file.getName() + "(" + file.length() + " bytes)");
                    collector.itemCount++;
                } else if (isSimpleValue(value)) {
                    if (metadataHint || binaryHint) {
                        collector.metadata.add(memberPath + "=" + truncate(String.valueOf(value), 180));
                        collector.itemCount++;
                    }
                } else if (binaryHint) {
                    collector.metadata.add(memberPath + ".class=" + value.getClass().getName());
                    collector.itemCount++;
                    collectObject(value, memberPath, depth + 1, seen, collector);
                }
            }
        } finally {
            seen.remove(object);
        }
    }

    private boolean hasBinaryHint(String property) {
        return property.indexOf("image") >= 0
                || property.indexOf("file") >= 0
                || property.indexOf("content") >= 0
                || property.indexOf("data") >= 0
                || property.indexOf("byte") >= 0
                || property.indexOf("binary") >= 0
                || property.indexOf("attachment") >= 0
                || property.indexOf("stream") >= 0
                || property.indexOf("blob") >= 0;
    }

    private boolean isMetadataProperty(String property) {
        return property.equals("name")
                || property.equals("filename")
                || property.equals("file")
                || property.equals("size")
                || property.equals("length")
                || property.equals("contenttype")
                || property.equals("mimetype")
                || property.equals("type")
                || property.equals("checksum")
                || property.equals("encoding")
                || property.equals("extension")
                || property.equals("format")
                || property.equals("id");
    }

    private boolean isBinaryReturnType(Class<?> returnType) {
        return byte[].class.equals(returnType) || ByteBuffer.class.isAssignableFrom(returnType);
    }

    private boolean isSimpleValue(Object value) {
        Class<?> type = value.getClass();
        return type.isPrimitive()
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum
                || value instanceof java.util.Date
                || type.getName().startsWith("java.time.");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ENGLISH).replace(" ", "").replace("_", "").replace("-", "");
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data == null ? new byte[0] : data);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Throwable ex) {
            return String.valueOf(data == null ? 0 : Arrays.hashCode(data));
        }
    }

    private static final class Collector {
        final List<String> binary = new ArrayList<String>();
        final List<String> metadata = new ArrayList<String>();
        final List<String> notes = new ArrayList<String>();
        long byteCount;
        int binaryMembers;
        int itemCount;

        void addBytes(String path, byte[] data) {
            byte[] bytes = data == null ? new byte[0] : data;
            binary.add(path + ":bytes=" + bytes.length + ":sha256=" + sha256(bytes));
            byteCount += bytes.length;
            binaryMembers++;
            itemCount++;
        }

        void addByteBuffer(String path, ByteBuffer buffer) {
            ByteBuffer copy = buffer == null ? ByteBuffer.allocate(0) : buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            addBytes(path, bytes);
        }

        Profile toProfile() {
            Collections.sort(binary);
            Collections.sort(metadata);
            Collections.sort(notes);
            String canonical = "binary=" + binary.toString() + "\nmetadata=" + metadata.toString() + "\nnotes=" + notes.toString();
            return new Profile(sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)), byteCount, binaryMembers,
                    new ArrayList<String>(binary), new ArrayList<String>(metadata), new ArrayList<String>(notes));
        }
    }

    public static final class Profile {
        private final String fingerprint;
        private final long byteCount;
        private final int binaryMembers;
        private final List<String> binary;
        private final List<String> metadata;
        private final List<String> notes;

        private Profile(String fingerprint, long byteCount, int binaryMembers, List<String> binary, List<String> metadata, List<String> notes) {
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.byteCount = byteCount;
            this.binaryMembers = binaryMembers;
            this.binary = binary == null ? Collections.<String>emptyList() : Collections.unmodifiableList(binary);
            this.metadata = metadata == null ? Collections.<String>emptyList() : Collections.unmodifiableList(metadata);
            this.notes = notes == null ? Collections.<String>emptyList() : Collections.unmodifiableList(notes);
        }

        public boolean isComplete() {
            return binaryMembers > 0 && notes.isEmpty();
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public long getByteCount() {
            return byteCount;
        }

        public int getBinaryMembers() {
            return binaryMembers;
        }

        public String fingerprintSummary() {
            String shortHash = fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
            return shortHash + " (bytes=" + byteCount + ", members=" + binaryMembers + ")";
        }

        public String metadataSummary() {
            if (metadata.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            int max = Math.min(metadata.size(), 8);
            for (int i = 0; i < max; i++) {
                if (i > 0) {
                    builder.append("; ");
                }
                builder.append(metadata.get(i));
            }
            if (metadata.size() > max) {
                builder.append("; ... +").append(metadata.size() - max).append(" more");
            }
            return builder.toString();
        }

        public String qualitySummary() {
            if (isComplete()) {
                return "byte-level profile";
            }
            StringBuilder builder = new StringBuilder();
            if (binaryMembers == 0) {
                builder.append("metadata-only/no byte[] exposed");
            } else {
                builder.append("byte-level profile with notes");
            }
            if (!notes.isEmpty()) {
                builder.append("; ").append(notes.get(0));
                if (notes.size() > 1) {
                    builder.append("; ... +").append(notes.size() - 1).append(" more");
                }
            }
            return builder.toString();
        }
    }
}
