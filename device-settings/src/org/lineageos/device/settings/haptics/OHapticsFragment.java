/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.haptics;

import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SegmentedButtonPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SliderPreference;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;

import java.util.Arrays;
import java.util.List;

/** The O-Haptics page: touch haptics, touch style and vibration intensity. */
public class OHapticsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final int[] STYLE_ICONS = {
        R.drawable.ic_haptic_style_crisp,
        R.drawable.ic_haptic_style_gentle,
        R.drawable.ic_haptic_style_richtap,
    };

    private OHapticsController mOHaptics;
    private List<String> mStyleValues;

    private SwitchPreferenceCompat mSystemHapticsPref;
    private SliderPreference mHapticIntensityPref;
    private SegmentedButtonPreference mHapticStylePref;
    private SwitchPreferenceCompat mKeyboardHapticsPref;
    private SliderPreference mVibrationIntensityPref;

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

        mStyleValues = Arrays.asList(getResources().getStringArray(R.array.haptic_style_values));
        if (mHapticStylePref != null) {
            String[] entries = getResources().getStringArray(R.array.haptic_style_entries);
            for (int i = 0; i < entries.length; i++) {
                mHapticStylePref.setUpButton(i, entries[i], STYLE_ICONS[i]);
                mHapticStylePref.setButtonVisibility(i, true);
            }
            mHapticStylePref.setOnButtonClickListener((group, checkedId, isChecked) -> {
                if (!isChecked) {
                    return;
                }
                int index = group.indexOfChild(group.findViewById(checkedId));
                if (index >= 0 && index < mStyleValues.size()) {
                    onStyleSelected(index);
                }
            });
        }

        for (Preference pref : new Preference[] {mSystemHapticsPref, mHapticIntensityPref,
                mKeyboardHapticsPref, mVibrationIntensityPref}) {
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
            mHapticStylePref.setCheckedIndex(
                    Math.max(mStyleValues.indexOf(OHapticsController.getStyle()), 0));
            for (int i = 0; i < mStyleValues.size(); i++) {
                mHapticStylePref.setButtonEnabled(i, systemHaptics);
            }
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

    private void onStyleSelected(int index) {
        String style = mStyleValues.get(index);
        if (style.equals(OHapticsController.getStyle())) {
            return;
        }
        OHapticsController.setStyle(style);
        mHapticStylePref.setCheckedIndex(index);
        mOHaptics.previewStyle();
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
