/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.haptics;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;

/** The O-Haptics page: touch haptics, touch style and vibration intensity. */
public class OHapticsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private OHapticsController mOHaptics;

    private SwitchPreferenceCompat mSystemHapticsPref;
    private SeekBarPreference mHapticIntensityPref;
    private ListPreference mHapticStylePref;
    private SwitchPreferenceCompat mKeyboardHapticsPref;
    private SeekBarPreference mVibrationIntensityPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.ohaptics_settings, rootKey);

        mOHaptics = new OHapticsController(getContext());

        mSystemHapticsPref = findPreference(Constants.KEY_SYSTEM_HAPTICS);
        mHapticIntensityPref = findPreference(Constants.KEY_HAPTIC_INTENSITY);
        mHapticStylePref = findPreference(Constants.KEY_HAPTIC_PROFILE);
        mKeyboardHapticsPref = findPreference(Constants.KEY_KEYBOARD_HAPTICS);
        mVibrationIntensityPref = findPreference(Constants.KEY_VIBRATION_INTENSITY);

        if (mHapticIntensityPref != null) {
            mHapticIntensityPref.setMin(0);
            mHapticIntensityPref.setMax(
                    OHapticsController.maxLevel(OHapticsController.TOUCH_STRENGTH_MIN));
        }
        if (mVibrationIntensityPref != null) {
            mVibrationIntensityPref.setMin(0);
            mVibrationIntensityPref.setMax(
                    OHapticsController.maxLevel(OHapticsController.VIB_STRENGTH_MIN));
        }

        for (Preference pref : new Preference[] {mSystemHapticsPref, mHapticIntensityPref,
                mHapticStylePref, mKeyboardHapticsPref, mVibrationIntensityPref}) {
            if (pref != null) {
                pref.setOnPreferenceChangeListener(this);
            }
        }

        updatePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePreferences();
    }

    @Override
    public void onPause() {
        mOHaptics.cancelPreview();
        super.onPause();
    }

    private void updatePreferences() {
        boolean systemHaptics = mOHaptics.isSystemHapticsEnabled();
        if (mSystemHapticsPref != null) {
            mSystemHapticsPref.setChecked(systemHaptics);
        }
        if (mHapticIntensityPref != null) {
            mHapticIntensityPref.setValue(OHapticsController.strengthToLevel(
                    OHapticsController.getTouchStrength(),
                    OHapticsController.TOUCH_STRENGTH_MIN));
            mHapticIntensityPref.setEnabled(systemHaptics);
        }
        if (mHapticStylePref != null) {
            mHapticStylePref.setValue(OHapticsController.getStyle());
            mHapticStylePref.setSummary(mHapticStylePref.getEntry());
            mHapticStylePref.setEnabled(systemHaptics);
        }
        if (mKeyboardHapticsPref != null) {
            mKeyboardHapticsPref.setChecked(mOHaptics.isKeyboardHapticsEnabled());
            mKeyboardHapticsPref.setEnabled(systemHaptics);
        }
        if (mVibrationIntensityPref != null) {
            mVibrationIntensityPref.setValue(OHapticsController.strengthToLevel(
                    OHapticsController.getVibrationStrength(),
                    OHapticsController.VIB_STRENGTH_MIN));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mSystemHapticsPref) {
            mOHaptics.setSystemHapticsEnabled((Boolean) newValue);
            updatePreferences();
        } else if (preference == mHapticIntensityPref) {
            OHapticsController.setTouchStrength(OHapticsController.levelToStrength(
                    (Integer) newValue, OHapticsController.TOUCH_STRENGTH_MIN));
            mOHaptics.previewTouch();
        } else if (preference == mHapticStylePref) {
            String style = OHapticsController.normalizeStyle((String) newValue);
            OHapticsController.setStyle(style);
            int index = mHapticStylePref.findIndexOfValue(style);
            if (index >= 0) {
                mHapticStylePref.setSummary(mHapticStylePref.getEntries()[index]);
            }
            mOHaptics.previewStyle();
        } else if (preference == mKeyboardHapticsPref) {
            mOHaptics.setKeyboardHapticsEnabled((Boolean) newValue);
        } else if (preference == mVibrationIntensityPref) {
            OHapticsController.setVibrationStrength(OHapticsController.levelToStrength(
                    (Integer) newValue, OHapticsController.VIB_STRENGTH_MIN));
            mOHaptics.previewVibration();
        } else {
            return false;
        }
        return true;
    }
}
