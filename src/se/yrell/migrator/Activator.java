package se.yrell.migrator;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/** Bundle activator and central logging helper. */
public final class Activator extends AbstractUIPlugin {
    public static final String PLUGIN_ID = "se.yrell.migrator";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public static void logInfo(String message) {
        log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    public static void logWarning(String message, Throwable throwable) {
        log(new Status(IStatus.WARNING, PLUGIN_ID, message, throwable));
    }

    public static void logError(String message, Throwable throwable) {
        log(new Status(IStatus.ERROR, PLUGIN_ID, message, throwable));
    }

    public static void log(IStatus status) {
        Activator current = getDefault();
        if (current != null && current.getLog() != null) {
            current.getLog().log(status);
        }
    }
}
