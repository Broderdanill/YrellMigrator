package se.yrell.migrator.bmc;

import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.api.StatusInfo;
import com.bmc.arsys.api.ArchiveInfo;
import com.bmc.arsys.api.AssociationsToFollow;
import com.bmc.arsys.studio.model.ModelException;
import com.bmc.arsys.studio.model.ModelState;
import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.item.ItemList;
import com.bmc.arsys.studio.model.ar.IARForm;
import com.bmc.arsys.studio.model.internal.ICustomizableObject;
import com.bmc.arsys.studio.model.store.IModelObject;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationResult;

/**
 * Performs object migration through Developer Studio's model API.
 *
 * Supported by default: Forms, workflow objects, Menus and Images when Developer Studio exposes
 * them as normal model objects. Support Files are handled by {@link BmcSupportFileMigrator}
 * because their binary backing differs between Developer Studio versions.
 */
public final class BmcWorkflowMigrator {

    public MigrationResult migrate(CompareResult result, MigrationDirection direction, IProgressMonitor monitor) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        if (result == null) {
            return MigrationResult.failure(null, "No row was selected.");
        }
        if (direction == null) {
            return MigrationResult.failure(result, "No migration direction was supplied.");
        }
        if (!isMigratableObject(result)) {
            return MigrationResult.failure(result, "Only definitions and Images are migratable through the generic model migrator. Support Files use the dedicated Support File migrator.");
        }
        if (result.getStatus() == CompareStatus.ERROR) {
            return MigrationResult.failure(result, "Rows in Error status cannot be migrated.");
        }

        IStore fromStore = direction == MigrationDirection.SOURCE_TO_TARGET ? result.getSourceStore() : result.getTargetStore();
        IStore toStore = direction == MigrationDirection.SOURCE_TO_TARGET ? result.getTargetStore() : result.getSourceStore();
        IModelType type = result.getModelType();
        String name = result.getObjectName();

        if (fromStore == null || !fromStore.isConnected()) {
            return MigrationResult.failure(result, "The source environment is not connected.");
        }
        if (toStore == null || !toStore.isConnected()) {
            return MigrationResult.failure(result, "The target environment is not connected.");
        }
        if (type == null || name == null || name.length() == 0) {
            return MigrationResult.failure(result, "The row has no usable object type or name.");
        }

        IModelObject sourceObject = null;
        try {
            safeMonitor.subTask("Loading " + type.getTypeName() + " " + name + " from " + fromStore.getName());
            IModelItem sourceItem = findItem(fromStore, type, name);
            if (sourceItem == null) {
                return MigrationResult.failure(result, "The selected direction has no source object to migrate.");
            }
            sourceObject = fromStore.getObject(sourceItem);
            if (sourceObject == null) {
                sourceObject = fromStore.getObject(type, name);
            }
            if (sourceObject == null) {
                return MigrationResult.failure(result, "Could not load the source object from " + fromStore.getName() + ".");
            }
        } catch (Throwable throwable) {
            return MigrationResult.failure(result, migrationErrorMessage(throwable));
        }

        try {
            String auditNote = migrateAuditDependencyIfPresent(result, fromStore, toStore, type, name, sourceObject, safeMonitor);
            String archiveNote = migrateArchiveDependencyIfPresent(result, fromStore, toStore, type, name, sourceObject, safeMonitor);
            safeMonitor.subTask("Migrating " + type.getTypeName() + " " + name + " " + direction.getLabel());
            MigrationResult migrated = migrateLoadedObject(result, direction, toStore, type, name, sourceObject, safeMonitor);
            if (migrated.isSuccess() && (auditNote.length() > 0 || archiveNote.length() > 0)) {
                StringBuilder detail = new StringBuilder(migrated.getDetail());
                if (auditNote.length() > 0) {
                    detail.append("\n\n").append(auditNote);
                }
                if (archiveNote.length() > 0) {
                    detail.append("\n\n").append(archiveNote);
                }
                return MigrationResult.success(result, migrated.isCreated(), detail.toString());
            }
            return migrated;
        } catch (Throwable throwable) {
            MigrationResult recovered = tryRecoverMissingAuditForm(result, direction, fromStore, toStore, type, name, sourceObject, safeMonitor, throwable);
            if (recovered != null) {
                return recovered;
            }
            return MigrationResult.failure(result, migrationErrorMessage(throwable));
        }
    }

    private MigrationResult migrateLoadedObject(CompareResult result, MigrationDirection direction, IStore toStore,
            IModelType type, String name, IModelObject sourceObject, IProgressMonitor monitor) throws Throwable {
        IModelItem destinationItem = findItem(toStore, type, name);
        boolean create = destinationItem == null;
        boolean createOverlay = shouldCreateOverlayInsteadOfUpdatingBase(result, direction) && destinationItem != null;
        List<StatusInfo> statuses = storeClonedObject(toStore, type, name, sourceObject, create, createOverlay);
        String action = createOverlay ? "Created overlay for" : (create ? "Created" : "Updated");
        return MigrationResult.success(result, create || createOverlay, action + " " + result.getObjectType() + " " + name + " in " + toStore.getName() + statusSummary(statuses));
    }

    private List<StatusInfo> storeClonedObject(IStore toStore, IModelType type, String name, IModelObject sourceObject,
            boolean create, boolean createOverlay) throws Throwable {
        Object cloned = sourceObject.clone();
        if (!(cloned instanceof IModelObject)) {
            throw new IllegalStateException("The selected object type could not be cloned by Developer Studio.");
        }
        IModelObject destinationObject = (IModelObject) cloned;
        destinationObject.setStore(toStore);
        mirrorFormArchiveSettings(sourceObject, destinationObject);
        if (createOverlay) {
            prepareNewOverlay(destinationObject);
            destinationObject.setState(ModelState.EXISTING);
            return toStore.storeObject(type, destinationObject, true, true);
        }
        if (create) {
            destinationObject.setState(ModelState.NEW);
            return toStore.createObject(type, destinationObject, true);
        }
        destinationObject.setState(ModelState.EXISTING);
        return toStore.storeObject(type, destinationObject, true, true);
    }


    private String migrateArchiveDependencyIfPresent(CompareResult result, IStore fromStore, IStore toStore,
            IModelType type, String name, IModelObject sourceObject, IProgressMonitor monitor) throws Throwable {
        if (!isFormType(result == null ? null : result.getObjectType())) {
            return "";
        }
        ArchiveInfo archiveInfo = archiveInfoFromObject(sourceObject);
        if (archiveInfo == null || !archiveInfo.isEnable()) {
            return "";
        }
        String destinationForm = stripQuotes(archiveInfo.getArchiveDest());
        StringBuilder note = new StringBuilder();
        note.append("Archive settings detected: enabled");
        note.append(", type=").append(archiveInfo.getArchiveType());
        if (destinationForm.length() > 0) {
            note.append(", destination=").append(destinationForm);
        }
        note.append(". The form archive metadata was copied explicitly before save.");

        if (destinationForm.length() == 0 || destinationForm.equalsIgnoreCase(name)) {
            return note.toString();
        }
        IModelItem archiveDestinationItem = findItem(fromStore, type, destinationForm);
        if (archiveDestinationItem == null) {
            note.append(" Archive destination form '").append(destinationForm).append("' was not found in source.");
            return note.toString();
        }
        IModelObject archiveDestinationObject = fromStore.getObject(archiveDestinationItem);
        if (archiveDestinationObject == null) {
            archiveDestinationObject = fromStore.getObject(type, destinationForm);
        }
        if (archiveDestinationObject == null) {
            note.append(" Archive destination form '").append(destinationForm).append("' could not be loaded from source.");
            return note.toString();
        }
        if (monitor != null) {
            monitor.subTask("Migrating archive destination form " + destinationForm);
        }
        IModelItem targetArchiveDestinationItem = findItem(toStore, type, destinationForm);
        boolean createArchiveDestination = targetArchiveDestinationItem == null;
        List<StatusInfo> archiveStatuses = storeClonedObject(toStore, type, destinationForm, archiveDestinationObject, createArchiveDestination, false);
        if (monitor != null) {
            monitor.worked(1);
        }
        note.append(" Archive destination handled: ")
                .append(createArchiveDestination ? "created" : "updated")
                .append(" form ").append(destinationForm)
                .append(statusSummary(archiveStatuses));
        return note.toString();
    }

    private ArchiveInfo archiveInfoFromObject(Object sourceObject) {
        if (sourceObject instanceof IARForm) {
            try {
                return ((IARForm) sourceObject).getArchiveInfo();
            } catch (Throwable ignored) {
                // Fall back to reflection below for mixed Developer Studio versions.
            }
        }
        Object archiveInfo = invokeNoArg(sourceObject, "getArchiveInfo");
        return archiveInfo instanceof ArchiveInfo ? (ArchiveInfo) archiveInfo : null;
    }

    private void mirrorFormArchiveSettings(IModelObject sourceObject, IModelObject destinationObject) {
        if (!(sourceObject instanceof IARForm) || !(destinationObject instanceof IARForm)) {
            return;
        }
        try {
            IARForm sourceForm = (IARForm) sourceObject;
            IARForm targetForm = (IARForm) destinationObject;
            ArchiveInfo archiveInfo = sourceForm.getArchiveInfo();
            targetForm.setArchiveInfo(cloneArchiveInfo(archiveInfo));
            AssociationsToFollow associations = sourceForm.getAssociationToFollowForArchive();
            targetForm.setAssociationToFollowForArchive(cloneAssociationsToFollow(associations));
        } catch (Throwable ignored) {
            // StoreObject/createObject will still return the real AR/BMC error if archive metadata cannot be persisted.
        }
    }

    private ArchiveInfo cloneArchiveInfo(ArchiveInfo archiveInfo) {
        if (archiveInfo == null) {
            return null;
        }
        try {
            return (ArchiveInfo) archiveInfo.clone();
        } catch (Throwable ignored) {
            return archiveInfo;
        }
    }

    private AssociationsToFollow cloneAssociationsToFollow(AssociationsToFollow associations) {
        if (associations == null) {
            return null;
        }
        try {
            return (AssociationsToFollow) associations.clone();
        } catch (Throwable ignored) {
            return associations;
        }
    }

    private String migrateAuditDependencyIfPresent(CompareResult result, IStore fromStore, IStore toStore,
            IModelType type, String name, IModelObject sourceObject, IProgressMonitor monitor) throws Throwable {
        if (!isFormType(result == null ? null : result.getObjectType())) {
            return "";
        }
        String auditFormName = auditFormNameFromObject(sourceObject);
        if (auditFormName.length() == 0 || auditFormName.equalsIgnoreCase(name)) {
            return "";
        }
        IModelItem auditItem = findItem(fromStore, type, auditFormName);
        if (auditItem == null) {
            return "Audit form dependency was declared as '" + auditFormName + "', but it was not found in source.";
        }
        IModelObject auditObject = fromStore.getObject(auditItem);
        if (auditObject == null) {
            auditObject = fromStore.getObject(type, auditFormName);
        }
        if (auditObject == null) {
            return "Audit form dependency was declared as '" + auditFormName + "', but it could not be loaded from source.";
        }
        if (monitor != null) {
            monitor.subTask("Migrating audit form dependency " + auditFormName);
        }
        IModelItem targetAuditItem = findItem(toStore, type, auditFormName);
        boolean createAudit = targetAuditItem == null;
        List<StatusInfo> auditStatuses = storeClonedObject(toStore, type, auditFormName, auditObject, createAudit, false);
        if (monitor != null) {
            monitor.worked(1);
        }
        return "Audit dependency handled: " + (createAudit ? "created" : "updated") + " audit form " + auditFormName + statusSummary(auditStatuses);
    }

    private MigrationResult tryRecoverMissingAuditForm(CompareResult result, MigrationDirection direction,
            IStore fromStore, IStore toStore, IModelType type, String name, IModelObject sourceObject,
            IProgressMonitor monitor, Throwable originalError) {
        if (!isFormType(result == null ? null : result.getObjectType())) {
            return null;
        }
        String auditFormName = detectMissingAuditFormName(originalError, sourceObject, name);
        if (auditFormName.length() == 0 || auditFormName.equalsIgnoreCase(name)) {
            return null;
        }
        try {
            IModelItem auditItem = findItem(fromStore, type, auditFormName);
            if (auditItem == null) {
                return null;
            }
            IModelObject auditObject = fromStore.getObject(auditItem);
            if (auditObject == null) {
                auditObject = fromStore.getObject(type, auditFormName);
            }
            if (auditObject == null) {
                return null;
            }
            if (monitor != null) {
                monitor.subTask("Migrating audit form dependency " + auditFormName + " before " + name);
            }
            IModelItem targetAuditItem = findItem(toStore, type, auditFormName);
            boolean createAudit = targetAuditItem == null;
            List<StatusInfo> auditStatuses = storeClonedObject(toStore, type, auditFormName, auditObject, createAudit, false);
            if (monitor != null) {
                monitor.worked(1);
                monitor.subTask("Retrying audited form " + name);
            }
            MigrationResult retried = migrateLoadedObject(result, direction, toStore, type, name, sourceObject, monitor);
            if (!retried.isSuccess()) {
                return retried;
            }
            String detail = retried.getDetail() + "\n\nAudit dependency handled: "
                    + (createAudit ? "created" : "updated") + " audit form " + auditFormName + " before retrying the audited form"
                    + statusSummary(auditStatuses);
            return MigrationResult.success(result, retried.isCreated(), detail);
        } catch (Throwable retryError) {
            return MigrationResult.failure(result,
                    migrationErrorMessage(originalError)
                    + "\n\nYrell Migrator detected that this audited form references audit form '" + auditFormName
                    + "' and tried to migrate that audit form first, but recovery failed: "
                    + migrationErrorMessage(retryError));
        }
    }

    private boolean isFormType(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ENGLISH).replace(" ", "");
        return value.equals("form") || value.equals("formtype") || value.contains("form");
    }

    private String detectMissingAuditFormName(Throwable error, Object sourceObject, String sourceName) {
        String fromError = missingFormNameFromError(error);
        if (fromError.length() > 0 && !fromError.equalsIgnoreCase(sourceName)) {
            String fromAuditInfo = auditFormNameFromObject(sourceObject);
            if (fromAuditInfo.length() == 0 || sameSimpleName(fromError, fromAuditInfo)) {
                return fromError;
            }
            // In some Developer Studio builds the error contains the exact AR form name while auditInfo
            // renders a decorated AuditInfo object. Prefer the form name from the AR error.
            return fromError;
        }
        return "";
    }

    private String missingFormNameFromError(Throwable throwable) {
        String message = throwable == null ? "" : String.valueOf(throwable.getLocalizedMessage());
        if (message == null || message.length() == 0) {
            message = throwable == null ? "" : String.valueOf(throwable);
        }
        String lower = message.toLowerCase(Locale.ENGLISH);
        if (message.indexOf("ERROR (303)") < 0 || lower.indexOf("form does not exist") < 0) {
            return "";
        }
        int semi = message.lastIndexOf(';');
        if (semi < 0 || semi + 1 >= message.length()) {
            return "";
        }
        String text = message.substring(semi + 1).trim();
        int nl = text.indexOf('\n');
        if (nl >= 0) {
            text = text.substring(0, nl).trim();
        }
        text = stripQuotes(text);
        if (text.length() == 0 || text.indexOf(' ') >= 0 && text.toLowerCase(Locale.ENGLISH).startsWith("specified object")) {
            return "";
        }
        return text;
    }

    private String auditFormNameFromObject(Object sourceObject) {
        Object auditInfo = invokeNoArg(sourceObject, "getAuditInfo");
        if (auditInfo == null) {
            auditInfo = invokeNoArg(sourceObject, "getAudit");
        }
        if (auditInfo == null) {
            return "";
        }
        String[] getters = new String[] {
                "getAuditFormName", "getAuditSchemaName", "getAuditForm", "getFormName", "getSchemaName", "getName", "getForm" };
        for (int i = 0; i < getters.length; i++) {
            Object value = invokeNoArg(auditInfo, getters[i]);
            String text = stripQuotes(simpleDisplay(value));
            if (looksLikeFormName(text)) {
                return text;
            }
        }
        String text = simpleDisplay(auditInfo);
        String parsed = parseNamedValue(text, new String[] { "auditFormName", "auditSchemaName", "auditForm", "formName", "schemaName" });
        return looksLikeFormName(parsed) ? parsed : "";
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName, new Class[0]);
            method.setAccessible(true);
            return method.invoke(target, new Object[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String parseNamedValue(String text, String[] names) {
        if (text == null || names == null) {
            return "";
        }
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)" + java.util.regex.Pattern.quote(name) + "\\s*[=:]\\s*([^,}\\]\\s]+)");
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                return stripQuotes(m.group(1));
            }
        }
        return "";
    }

    private boolean looksLikeFormName(String text) {
        if (text == null) {
            return false;
        }
        String value = stripQuotes(text);
        if (value.length() == 0 || "null".equalsIgnoreCase(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ENGLISH);
        return lower.indexOf("com.bmc.") < 0 && lower.indexOf("@") < 0 && lower.indexOf("auditinfo") < 0;
    }

    private boolean sameSimpleName(String a, String b) {
        return stripQuotes(a).equalsIgnoreCase(stripQuotes(b));
    }

    private String stripQuotes(String text) {
        String value = text == null ? "" : text.trim();
        while ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("<") && value.endsWith(">"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private String simpleDisplay(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value);
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    private String migrationErrorMessage(Throwable throwable) {
        String message = throwable == null ? "unknown" : (throwable.getLocalizedMessage() == null ? throwable.getClass().getName() : throwable.getLocalizedMessage());
        String lower = message.toLowerCase(Locale.ENGLISH);
        if (message.indexOf("ERROR (318)") >= 0 && lower.indexOf("group does not exist") >= 0) {
            return message + "\n\nMissing permission group on target. Migrate the Group row(s) first, then retry this workflow migration. Group ID is shown in the Key / ID column for Group rows after Sync.";
        }
        if (message.indexOf("ERROR (317)") >= 0 && lower.indexOf("duplicate") >= 0 && lower.indexOf("form") >= 0) {
            return message + "\n\nThe base object already exists in target. For overlays the migrator should update/create only the overlay layer, not recreate the base form. Refresh/sync the row and retry with v0.31.0 or later.";
        }
        if (message.indexOf("ERROR (303)") >= 0 && lower.indexOf("overlay mode") >= 0) {
            return message + "\n\nThe source object appears to be an overlay. v0.31.0 stores it as a new overlay layer when target has only the base object, instead of creating a duplicate base object.";
        }
        if (message.indexOf("ERROR (1587)") >= 0 && lower.indexOf("unknown field referenced in query") >= 0) {
            return message + "\n\nThis usually means the form definition contains a table/join/qualification that references a field on another form that is not available in target yet. "
                    + "If this form contains table/join fields that depend on other forms, migrate the related detail forms first, then the root/header form, and finally any join forms.";
        }
        if (message.indexOf("WARNING (8981)") >= 0 && lower.indexOf("application owner") >= 0) {
            return message + "\n\nThis is normally a non-fatal AR warning from the import/store operation. If it appears together with an ERROR line, fix the ERROR first; the owner warning can usually be ignored.";
        }
        return message;
    }


    private void prepareNewOverlay(IModelObject object) {
        if (object instanceof ICustomizableObject) {
            try {
                ((ICustomizableObject) object).setObjectOverlayState(ICustomizableObject.ObjectOverlayState.NEW);
            } catch (Throwable ignored) {
                // Older Developer Studio builds can expose customization differently.
                // In that case the normal storeObject path will still return a clear AR error.
            }
        }
    }

    private boolean shouldCreateOverlayInsteadOfUpdatingBase(CompareResult result, MigrationDirection direction) {
        if (result == null || direction == null) {
            return false;
        }
        String sourceCustomization = direction == MigrationDirection.SOURCE_TO_TARGET
                ? result.getSourceCustomizationType() : result.getTargetCustomizationType();
        String targetCustomization = direction == MigrationDirection.SOURCE_TO_TARGET
                ? result.getTargetCustomizationType() : result.getSourceCustomizationType();
        if (!"overlay".equalsIgnoreCase(sourceCustomization)) {
            return false;
        }
        return !"overlay".equalsIgnoreCase(targetCustomization);
    }

    public boolean canMigrate(CompareResult result, MigrationDirection direction) {
        if (result == null || direction == null || !isMigratableObject(result)) {
            return false;
        }
        CompareStatus status = result.getStatus();
        if (status == CompareStatus.ERROR) {
            return false;
        }
        if (direction == MigrationDirection.SOURCE_TO_TARGET) {
            return result.getSourceStore() != null && result.getTargetStore() != null
                    && status != CompareStatus.MISSING_IN_SOURCE;
        }
        return result.getSourceStore() != null && result.getTargetStore() != null
                && status != CompareStatus.MISSING_IN_TARGET;
    }

    public boolean isMigratableObject(CompareResult result) {
        if (result == null || result.getModelType() == null) {
            return false;
        }
        return isMigratableType(result.getObjectType(), result.getModelType().getClass().getName());
    }

    public boolean isMigratableType(IModelType type) {
        if (type == null) {
            return false;
        }
        return isMigratableType(type.getTypeName(), type.getClass().getName());
    }

    private boolean isMigratableType(String typeName, String className) {
        // Use the visible model type name for expensive/container exclusions. Some BMC model
        // classes live in packages containing words such as "application", which previously made
        // ordinary workflow rows non-migratable from cached Difference rows.
        String normalizedType = (typeName == null ? "" : typeName).toLowerCase(Locale.ENGLISH).replace(" ", "");
        String combined = (normalizedType + " " + (className == null ? "" : className).toLowerCase(Locale.ENGLISH).replace(" ", ""));
        if (normalizedType.contains("supportfile") || normalizedType.contains("binary")) {
            return false;
        }
        return isDefinitionType(normalizedType, combined);
    }

    private boolean isDefinitionType(String normalizedType, String combined) {
        return normalizedType.equals("form")
                || normalizedType.contains("form")
                || normalizedType.contains("activelink")
                || normalizedType.contains("filter")
                || normalizedType.contains("escalation")
                || normalizedType.contains("guide")
                || normalizedType.contains("menu")
                || normalizedType.contains("image")
                || normalizedType.contains("application")
                || normalizedType.contains("packinglist")
                || normalizedType.contains("webservice")
                || normalizedType.contains("flashboard")
                || normalizedType.contains("datavisualizationdefinition")
                || normalizedType.contains("association")
                || combined.contains("activelink")
                || combined.contains("filter")
                || combined.contains("escalation")
                || combined.contains("guide")
                || combined.contains("menu")
                || combined.contains("image")
                || combined.contains("application")
                || combined.contains("packinglist")
                || combined.contains("webservice")
                || combined.contains("flashboard")
                || combined.contains("datavisualizationdefinition")
                || combined.contains("association");
    }

    private boolean isDataBackedCatalogType(String normalizedType, String combined) {
        return normalizedType.equals("group")
                || normalizedType.equals("role")
                || normalizedType.equals("message")
                || normalizedType.equals("report")
                || normalizedType.equals("reporttype")
                || normalizedType.equals("template")
                || combined.contains("groupitem")
                || combined.contains("roleitem")
                || combined.contains("messageitem")
                || combined.contains("reportitem")
                || combined.contains("templateitem");
    }

    private IModelItem findItem(IStore store, IModelType type, String name) throws ModelException {
        IModelItem item = store.getItem(type, name);
        if (item != null) {
            return item;
        }
        ItemList<IModelItem> itemList = store.getItemList(type);
        return itemList == null ? null : itemList.get(type, name);
    }

    private String statusSummary(List<StatusInfo> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return ".";
        }
        StringBuilder builder = new StringBuilder(". AR status: ");
        int added = 0;
        for (StatusInfo status : statuses) {
            if (status == null) {
                continue;
            }
            if (added > 0) {
                builder.append(" | ");
            }
            builder.append(status.getMessageNum());
            String message = status.getMessageText();
            if (message != null && message.length() > 0) {
                builder.append(' ').append(message);
            }
            String appended = status.getAppendedText();
            if (appended != null && appended.length() > 0) {
                builder.append(' ').append(appended);
            }
            added++;
            if (added >= 3) {
                break;
            }
        }
        if (added == 0) {
            return ".";
        }
        if (statuses.size() > added) {
            builder.append(" | ...");
        }
        return builder.toString();
    }
}
