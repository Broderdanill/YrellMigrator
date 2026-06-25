package se.yrell.migrator.bmc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;

import com.bmc.arsys.studio.model.store.IStore;
import com.bmc.arsys.studio.model.type.IModelType;

import se.yrell.migrator.Activator;

/**
 * Launches BMC's existing detailed compare editor without a hard compile-time dependency on the
 * com.bmc.arsys.studio.ui.diff bundle.
 */
public final class BmcDiffLauncher {
    private static final String DIFF_BUNDLE = "com.bmc.arsys.studio.ui.diff";
    private static final String COMPARABLE_OBJECT = "com.bmc.arsys.studio.ui.diff.actions.model.ComparableObject";
    private static final String COMPARE_ACTION = "com.bmc.arsys.studio.ui.diff.actions.CompareAction";

    private BmcDiffLauncher() {
    }

    public static boolean isDetailedCompareAvailable() {
        Bundle bundle = Platform.getBundle(DIFF_BUNDLE);
        return bundle != null;
    }

    public static boolean openCompare(final IStore sourceStore, final IStore targetStore, final IModelType type, final String name) {
        if (Display.getCurrent() == null) {
            final boolean[] result = new boolean[] { false };
            Display.getDefault().syncExec(new Runnable() {
                public void run() {
                    result[0] = openCompare(sourceStore, targetStore, type, name);
                }
            });
            return result[0];
        }

        if (sourceStore == null || targetStore == null || type == null || name == null || name.length() == 0) {
            return false;
        }

        try {
            Bundle bundle = Platform.getBundle(DIFF_BUNDLE);
            if (bundle == null) {
                Activator.logWarning("BMC diff bundle is not available; detailed compare cannot be opened.", null);
                return false;
            }
            Class<?> comparableObjectClass = bundle.loadClass(COMPARABLE_OBJECT);
            Constructor<?> comparableConstructor = comparableObjectClass.getConstructor(IStore.class, String.class, IModelType.class);
            Object sourceComparable = comparableConstructor.newInstance(sourceStore, name, type);
            Object targetComparable = comparableConstructor.newInstance(targetStore, name, type);

            Class<?> compareActionClass = bundle.loadClass(COMPARE_ACTION);
            Method getAction = compareActionClass.getMethod("getAction", comparableObjectClass, comparableObjectClass, comparableObjectClass);
            Object action = getAction.invoke(null, sourceComparable, targetComparable, null);
            if (action instanceof IAction) {
                ((IAction) action).run();
                return true;
            }
            Activator.logWarning("BMC compare action was created but does not implement IAction.", null);
        } catch (Throwable throwable) {
            Activator.logWarning("Could not open BMC detailed compare editor.", throwable);
        }
        return false;
    }
}
