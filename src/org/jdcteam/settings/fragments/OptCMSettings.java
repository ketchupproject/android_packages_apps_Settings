package org.jdcteam.settings.fragments;

import android.os.Bundle;

public class OptCMSettings extends SettingsPreferenceFragment {
		
	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		addPreferencesFromResource(R.xml.optcm_settings);
	}
}
