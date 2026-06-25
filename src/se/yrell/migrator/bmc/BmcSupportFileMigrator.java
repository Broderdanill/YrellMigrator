package se.yrell.migrator.bmc;

import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.bmc.arsys.api.StatusInfo;
import com.bmc.arsys.studio.model.ModelException;
import com.bmc.arsys.studio.model.ModelState;
import com.bmc.arsys.studio.model.item.IModelItem;
import com.bmc.arsys.studio.model.item.ItemList;
import com.bmc.arsys.studio.model.store.IModelObject;
import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.core.CompareResult;
import se.yrell.migrator.core.CompareStatus;
import se.yrell.migrator.core.MigrationDirection;
import se.yrell.migrator.core.MigrationResult;

/**
 * Best-effort Support File migrator.
 *
 * Support files are binary/container-adjacent in Developer Studio and are intentionally kept out of
 * the generic workflow migrator. When the installed Developer Studio build exposes Support Files as
 * cloneable IModelObject instances, this class migrates them through the same model store API. If a
 * build exposes them through a different internal API the failure message remains explicit and does
 * not affect normal Forms/workflow/Image migration.
 */
public final class BmcSupportFileMigrator {

    public boolean canMigrate(CompareResult result, MigrationDirection direction) {
        if (result == null || direction == null || !isSupportFileType(result)) {
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

    public boolean isSupportFileType(CompareResult result) {
        if (result == null) {
            return false;
        }
        return isSupportFileType(result.getObjectType())
                || isSupportFileType(result.getModelType() == null ? "" : result.getModelType().getClass().getName());
    }

    public boolean isSupportFileType(IModelType type) {
        return type != null && (isSupportFileType(type.getTypeName()) || isSupportFileType(type.getClass().getName()));
    }

    private boolean isSupportFileType(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ENGLISH).replace(" ", "");
        return value.indexOf("supportfile") >= 0 || value.indexOf("support_file") >= 0;
    }

    public MigrationResult migrate(CompareResult result, MigrationDirection direction, IProgressMonitor monitor) {
        IProgressMonitor safeMonitor = monitor == null ? new NullProgressMonitor() : monitor;
        if (!canMigrate(result, direction)) {
            return MigrationResult.failure(result, "This row is not a migratable Support File in the selected direction.");
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
            return MigrationResult.failure(result, "The Support File row has no usable type or name.");
        }
        try {
            safeMonitor.subTask("Loading Support File " + name + " from " + fromStore.getName());
            IModelItem sourceItem = findItem(fromStore, type, name);
            if (sourceItem == null) {
                return MigrationResult.failure(result, "The selected direction has no source Support File to migrate.");
            }
            IModelObject sourceObject = fromStore.getObject(sourceItem);
            if (sourceObject == null) {
                sourceObject = fromStore.getObject(type, name);
            }
            if (sourceObject == null) {
                return MigrationResult.failure(result, "Could not load the source Support File from " + fromStore.getName() + ".");
            }
            Object cloned = sourceObject.clone();
            if (!(cloned instanceof IModelObject)) {
                return MigrationResult.failure(result, "Developer Studio did not expose Support File " + name + " as a cloneable model object.");
            }
            IModelItem targetItem = findItem(toStore, type, name);
            boolean create = targetItem == null;
            IModelObject targetObject = (IModelObject) cloned;
            targetObject.setStore(toStore);
            targetObject.setState(create ? ModelState.NEW : ModelState.EXISTING);
            safeMonitor.subTask((create ? "Creating" : "Updating") + " Support File " + name + " in " + toStore.getName());
            List<StatusInfo> statuses = create ? toStore.createObject(type, targetObject, true) : toStore.storeObject(type, targetObject, true, true);
            return MigrationResult.success(result, create, (create ? "Created" : "Updated") + " Support File " + name + " in " + toStore.getName() + statusSummary(statuses));
        } catch (Throwable ex) {
            return MigrationResult.failure(result, supportFileErrorMessage(ex));
        }
    }

    private IModelItem findItem(IStore store, IModelType type, String name) throws ModelException {
        IModelItem item = store.getItem(type, name);
        if (item != null) {
            return item;
        }
        ItemList<IModelItem> itemList = store.getItemList(type);
        return itemList == null ? null : itemList.get(type, name);
    }

    private String supportFileErrorMessage(Throwable throwable) {
        String message = throwable == null ? "unknown" : (throwable.getLocalizedMessage() == null ? throwable.getClass().getName() : throwable.getLocalizedMessage());
        return message + "\n\nSupport File migration is best-effort because BMC exposes binary support files differently between Developer Studio versions. If this fails in your build, export/import the Support File manually and use Yrell Migrator for verification.";
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
        if (statuses.size() > added) {
            builder.append(" | ...");
        }
        return builder.toString();
    }
}
