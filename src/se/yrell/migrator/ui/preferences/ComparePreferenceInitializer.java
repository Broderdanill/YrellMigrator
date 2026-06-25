package se.yrell.migrator.ui.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

import se.yrell.migrator.Activator;
import se.yrell.migrator.config.CompareSettings;

/** Registers default values for the Eclipse preference store. */
public final class ComparePreferenceInitializer extends AbstractPreferenceInitializer {
    @Override
    public void initializeDefaultPreferences() {
        if (Activator.getDefault() != null) {
            CompareSettings.initializeDefaults(Activator.getDefault().getPreferenceStore());
        }
    }
}
