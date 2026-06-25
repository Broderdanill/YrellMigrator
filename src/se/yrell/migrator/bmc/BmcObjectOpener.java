package se.yrell.migrator.bmc;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;

import com.bmc.arsys.studio.model.item.IModelItem;

import se.yrell.migrator.Activator;

/**
 * Opens AR System objects in Developer Studio's default editor.
 *
 * The implementation intentionally uses reflection against Developer Studio's UI action. This keeps
 * the rest of the plug-in independent from BMC UI internals while still reusing the native editor
 * selection/opening behavior, including special cases such as fields/forms and hidden objects.
 */
public final class BmcObjectOpener {
    private static final String UI_BUNDLE = "com.bmc.arsys.studio.ui";
    private static final String OPEN_DEFAULT_EDITOR_ACTION = "com.bmc.arsys.studio.ui.views.objectlist.actions.OpenDefaultEditorAction";

    private BmcObjectOpener() {
    }

    public static boolean open(final IModelItem item) {
        if (Display.getCurrent() == null) {
            final boolean[] result = new boolean[] { false };
            Display.getDefault().syncExec(new Runnable() {
                public void run() {
                    result[0] = open(item);
                }
            });
            return result[0];
        }

        if (item == null) {
            return false;
        }

        try {
            Bundle bundle = Platform.getBundle(UI_BUNDLE);
            Class<?> actionClass = bundle == null ? Class.forName(OPEN_DEFAULT_EDITOR_ACTION) : bundle.loadClass(OPEN_DEFAULT_EDITOR_ACTION);
            Object action = actionClass.getConstructor().newInstance();

            ArrayList<IModelItem> items = new ArrayList<IModelItem>();
            items.add(item);

            Method setItems = actionClass.getMethod("setItems", ArrayList.class);
            setItems.invoke(action, items);

            Method run = actionClass.getMethod("run");
            run.invoke(action);
            return true;
        } catch (Throwable throwable) {
            Activator.logWarning("Could not open AR System object in the default Developer Studio editor.", throwable);
            return false;
        }
    }
}
