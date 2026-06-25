package se.yrell.migrator;

import org.eclipse.ui.IStartup;

import se.yrell.migrator.config.CompareSettings;

/**
 * Early startup hook used only to make plugin loading easy to diagnose in the
 * Eclipse/Developer Studio workspace log.
 */
public final class StartupDiagnostics implements IStartup {
    @Override
    public void earlyStartup() {
        CompareSettings settings = CompareSettings.load();
        Activator.logInfo("Yrell Migrator plugin loaded. Version=" + getVersion() + ", Java="
                + System.getProperty("java.version") + ", Vendor=" + System.getProperty("java.vendor")
                + ", CompareConfig=" + settings.describeLocation());
    }

    private String getVersion() {
        try {
            return Activator.getDefault().getBundle().getVersion().toString();
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
