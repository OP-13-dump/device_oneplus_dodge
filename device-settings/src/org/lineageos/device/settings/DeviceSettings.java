/*
 * Copyright (C) 2018-2024 crDroid Android Project
 * Copyright (C) 2025 AlphaDroid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.device.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.CheckBox;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.Arrays;
import java.util.HashSet;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.display.DisplayModeController;
import org.lineageos.device.settings.display.HbmController;
import org.lineageos.device.settings.display.PwmController;
import org.lineageos.device.settings.display.SunlightBoostController;
import org.lineageos.device.settings.utils.AppPreferencesHelper;
import org.lineageos.device.settings.utils.FileUtils;
import org.lineageos.device.settings.utils.PackageListAdapter.PackageItem;

public class DeviceSettings extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = DeviceSettings.class.getSimpleName();

    private static final String KEY_SHOW_HBM_WARNING = "hbm_warning";

    private ListPreference mTopKeyPref;
    private ListPreference mMiddleKeyPref;
    private ListPreference mBottomKeyPref;

    private static final String[] SLIDER_APP_KEYS = {
        Constants.KEY_NOTIF_SLIDER_APP_TOP,
        Constants.KEY_NOTIF_SLIDER_APP_MIDDLE,
        Constants.KEY_NOTIF_SLIDER_APP_BOTTOM
    };

    private SwitchPreferenceCompat mOnePulsePWMSwitch;
    private SwitchPreferenceCompat mHbmSwitch;
    private SwitchPreferenceCompat mSunlightBoostSwitch;
    private ListPreference mHapticProfilePref;

    private HbmController mHbmController;
    private PwmController mPwmController;
    private DisplayModeController mDisplayModeController;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.main);

        mHbmController = HbmController.getInstance(getContext());
        mPwmController = PwmController.getInstance(getContext());
        mDisplayModeController = DisplayModeController.getInstance(getContext());

        mOnePulsePWMSwitch = (SwitchPreferenceCompat) findPreference(Constants.KEY_ONEPULSE_PWM);
        if (FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
            mOnePulsePWMSwitch.setEnabled(true);
            mOnePulsePWMSwitch.setChecked(mPwmController.isPwmEnabled());
            mOnePulsePWMSwitch.setOnPreferenceChangeListener(this);
        } else {
            mOnePulsePWMSwitch.setEnabled(false);
        }

        mHbmSwitch = (SwitchPreferenceCompat) findPreference(Constants.KEY_HBM);
        if (FileUtils.isFileWritable(Constants.NODE_HBM)) {
            mHbmSwitch.setEnabled(true);
            mHbmSwitch.setChecked(mHbmController.isHbmEnabled());
            mHbmSwitch.setOnPreferenceChangeListener(this);
        } else {
            mHbmSwitch.setEnabled(false);
        }

        mSunlightBoostSwitch = (SwitchPreferenceCompat) findPreference(Constants.KEY_SUNLIGHT_BOOST);
        if (FileUtils.isFileWritable(Constants.NODE_HBM)) {
            mSunlightBoostSwitch.setOnPreferenceChangeListener(this);
        } else {
            mSunlightBoostSwitch.setEnabled(false);
        }

        // Sync UI state based on current HBM/PWM state
        syncHbmPwmState();

        initNotificationSliderPreference();

        mHapticProfilePref = (ListPreference) findPreference(Constants.KEY_HAPTIC_PROFILE);
        if (mHapticProfilePref != null) {
            String currentProfile = SystemProperties.get(Constants.PROP_HAPTIC_PROFILE, Constants.HAPTIC_PROFILE_DEFAULT);
            mHapticProfilePref.setValue(currentProfile);
            mHapticProfilePref.setSummary(mHapticProfilePref.getEntry());
            mHapticProfilePref.setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh state when returning to settings
        syncHbmPwmState();
    }

    /**
     * Sync HBM/PWM toggle states based on business logic:
     * - PWM has priority over HBM
     * - If PWM is enabled, HBM toggle is disabled
     * - PWM toggle is always enabled (it can override HBM)
     */
    private void syncHbmPwmState() {
        if (mOnePulsePWMSwitch == null || mHbmSwitch == null) {
            return;
        }

        boolean pwmEnabled = mPwmController.isPwmEnabled();
        boolean hbmEnabled = mHbmController.isHbmEnabled();

        // PWM has priority: if both are somehow enabled, disable HBM
        if (pwmEnabled && hbmEnabled) {
            Log.i(TAG, "PWM is enabled, disabling HBM (PWM has priority)");
            mHbmSwitch.setChecked(false);
            mHbmController.disableHbm();
            mDisplayModeController.broadcastStateChange();
            hbmEnabled = false;
        }

        // Update checkbox states
        mOnePulsePWMSwitch.setChecked(pwmEnabled);
        mHbmSwitch.setChecked(hbmEnabled);

        // HBM cannot be enabled if PWM is enabled
        mHbmSwitch.setEnabled(!pwmEnabled && FileUtils.isFileWritable(Constants.NODE_HBM));

        // PWM can ALWAYS be toggled (it has priority and will auto-disable HBM)
        mOnePulsePWMSwitch.setEnabled(FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM));
    }

    private void initNotificationSliderPreference() {
        registerPreferenceListener(Constants.KEY_NOTIF_SLIDER_USAGE);
        registerPreferenceListener(Constants.KEY_NOTIF_SLIDER_ACTION_TOP);
        registerPreferenceListener(Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE);
        registerPreferenceListener(Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM);

        for (String key : SLIDER_APP_KEYS) {
            Preference pref = findPreference(key);
            pref.setOnPreferenceClickListener(p -> {
                showSliderAppSelectionDialog(key);
                return true;
            });
            updateSliderAppSummary(key);
        }

        ListPreference usagePref = (ListPreference) findPreference(
                Constants.KEY_NOTIF_SLIDER_USAGE);
        handleSliderUsageChange(usagePref.getValue());
    }

    private void registerPreferenceListener(String key) {
        Preference p = findPreference(key);
        p.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        if (preference == mOnePulsePWMSwitch) {
            boolean enabled = (Boolean) newValue;

            if (enabled) {
                // PWM has priority - enablePwm() will auto-disable HBM if needed
                if (!mDisplayModeController.enablePwm()) {
                    return false;
                }
                Log.i(TAG, "PWM enabled");

                // Update HBM switch state (it was disabled by enablePwm if it was on)
                mHbmSwitch.setChecked(false);
                mHbmSwitch.setEnabled(false);
            } else {
                if (!mDisplayModeController.disablePwm()) {
                    return false;
                }
                Log.i(TAG, "PWM disabled");

                // Re-enable HBM toggle
                mHbmSwitch.setEnabled(FileUtils.isFileWritable(Constants.NODE_HBM));
            }
            return true;
        } else if (preference == mSunlightBoostSwitch) {
            boolean enabled = (Boolean) newValue;
            // Persist before the controller re-reads the preference
            sharedPrefs.edit().putBoolean(Constants.KEY_SUNLIGHT_BOOST, enabled).commit();
            SunlightBoostController.getInstance(getContext()).updateState();
            return true;
        } else if (preference == mHbmSwitch) {
            boolean enabled = (Boolean) newValue;

            // HBM cannot be enabled if PWM is active
            if (enabled && mPwmController.isPwmEnabled()) {
                Log.i(TAG, "Cannot enable HBM while PWM is active");
                mHbmSwitch.setChecked(false);
                return false;
            }

            if (enabled && sharedPrefs.getBoolean(KEY_SHOW_HBM_WARNING, true)) {
                showHbmWarningDialog();
                // Return false - dialog will handle the actual enable
                return false;
            } else {
                if (enabled) {
                    if (!mDisplayModeController.enableHbm()) {
                        mHbmSwitch.setChecked(false);
                        return false;
                    }
                    Log.i(TAG, "HBM enabled");
                } else {
                    if (!mDisplayModeController.disableHbm()) {
                        mHbmSwitch.setChecked(true);
                        return false;
                    }
                    Log.i(TAG, "HBM disabled");
                }
            }
            return true;
        }

        String key = preference.getKey();
        switch (key) {
            case Constants.KEY_NOTIF_SLIDER_USAGE:
                return handleSliderUsageChange((String) newValue) &&
                        handleSliderUsageDefaultsChange((String) newValue) &&
                        notifySliderUsageChange((String) newValue);
            case Constants.KEY_NOTIF_SLIDER_ACTION_TOP:
                return notifySliderActionChange(0, (String) newValue);
            case Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE:
                return notifySliderActionChange(1, (String) newValue);
            case Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM:
                return notifySliderActionChange(2, (String) newValue);
            case Constants.KEY_HAPTIC_PROFILE:
                String profileValue = (String) newValue;
                SystemProperties.set(Constants.PROP_HAPTIC_PROFILE, profileValue);
                ListPreference hapticPref = (ListPreference) preference;
                int index = hapticPref.findIndexOfValue(profileValue);
                if (index >= 0) {
                    hapticPref.setSummary(hapticPref.getEntries()[index]);
                }
                return true;
            default:
                break;
        }

        String node = Constants.sBooleanNodePreferenceMap.get(key);
        if (!TextUtils.isEmpty(node) && FileUtils.isFileWritable(node)) {
            Boolean value = (Boolean) newValue;
            FileUtils.writeLine(node, value ? "1" : "0");
            return true;
        }
        node = Constants.sStringNodePreferenceMap.get(key);
        if (!TextUtils.isEmpty(node) && FileUtils.isFileWritable(node)) {
            FileUtils.writeLine(node, (String) newValue);
            return true;
        }

        return false;
    }

    private void showHbmWarningDialog() {
        // Create a custom view with checkbox for "Don't show again"
        android.widget.LinearLayout container = new android.widget.LinearLayout(getActivity());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.CheckBox dontShowAgain = new android.widget.CheckBox(getActivity());
        dontShowAgain.setText(R.string.hbm_warning_dont_show_again);

        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(48, 12, 0, 12);
        dontShowAgain.setLayoutParams(params);

        container.addView(dontShowAgain);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.hbm_warning_title)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(R.string.hbm_warning_summary)
                .setView(container)
                .setPositiveButton(R.string.hbm_btn_enable, (dialog, which) -> {
                        if (dontShowAgain.isChecked()) {
                            SharedPreferences sharedPrefs =
                                    PreferenceManager.getDefaultSharedPreferences(getContext());
                            sharedPrefs.edit().putBoolean(KEY_SHOW_HBM_WARNING, false).apply();
                        }
                        if (mDisplayModeController.enableHbm()) {
                            mHbmSwitch.setChecked(true);
                            Log.i(TAG, "HBM enabled via dialog");
                        } else {
                            mHbmSwitch.setChecked(false);
                            Log.w(TAG, "Failed to enable HBM via dialog");
                        }
                    })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        mHbmSwitch.setChecked(false);
                    })
                .setCancelable(false)
                .show();
    }

    @Override
    public void addPreferencesFromResource(int preferencesResId) {
        super.addPreferencesFromResource(preferencesResId);
        // Initialize node preferences
        for (String pref : Constants.sBooleanNodePreferenceMap.keySet()) {
            SwitchPreferenceCompat b = (SwitchPreferenceCompat) findPreference(pref);
            if (b == null) continue;
            String node = Constants.sBooleanNodePreferenceMap.get(pref);
            if (FileUtils.isFileReadable(node)) {
                String curNodeValue = FileUtils.readLine(node);
                b.setChecked(curNodeValue.equals("1"));
                b.setOnPreferenceChangeListener(this);
            } else {
                removePref(b);
            }
        }
        for (String pref : Constants.sStringNodePreferenceMap.keySet()) {
            ListPreference l = (ListPreference) findPreference(pref);
            if (l == null) continue;
            String node = Constants.sStringNodePreferenceMap.get(pref);
            if (FileUtils.isFileReadable(node)) {
                l.setValue(FileUtils.readLine(node));
                l.setOnPreferenceChangeListener(this);
            } else {
                removePref(l);
            }
        }
    }

    private void removePref(Preference pref) {
        PreferenceGroup parent = pref.getParent();
        if (parent == null) {
            return;
        }
        parent.removePreference(pref);
        if (parent.getPreferenceCount() == 0) {
            removePref(parent);
        }
    }

    private boolean handleSliderUsageChange(String newValue) {
        updateSliderRowVisibility(newValue);
        switch (newValue) {
            case Constants.NOTIF_SLIDER_FOR_NOTIFICATION:
                return updateSliderActions(
                        R.array.notification_slider_mode_entries,
                        R.array.notification_slider_mode_entry_values);
            case Constants.NOTIF_SLIDER_FOR_FLASHLIGHT:
                return updateSliderActions(
                        R.array.notification_slider_flashlight_entries,
                        R.array.notification_slider_flashlight_entry_values);
            case Constants.NOTIF_SLIDER_FOR_BRIGHTNESS:
                return updateSliderActions(
                        R.array.notification_slider_brightness_entries,
                        R.array.notification_slider_brightness_entry_values);
            case Constants.NOTIF_SLIDER_FOR_ROTATION:
                return updateSliderActions(
                        R.array.notification_slider_rotation_entries,
                        R.array.notification_slider_rotation_entry_values);
            case Constants.NOTIF_SLIDER_FOR_RINGER:
                return updateSliderActions(
                        R.array.notification_slider_ringer_entries,
                        R.array.notification_slider_ringer_entry_values);
            case Constants.NOTIF_SLIDER_FOR_NOTIFICATION_RINGER:
                return updateSliderActions(
                        R.array.notification_ringer_slider_mode_entries,
                        R.array.notification_ringer_slider_mode_entry_values);
            case Constants.NOTIF_SLIDER_FOR_APPLAUNCH:
                return updateSliderActions(
                        R.array.notification_slider_applaunch_entries,
                        R.array.notification_slider_applaunch_entry_values);
            default:
                return false;
        }
    }

    /** In app-launch mode the three action rows are replaced by app pickers. */
    private void updateSliderRowVisibility(String usage) {
        boolean appMode = Constants.NOTIF_SLIDER_FOR_APPLAUNCH.equals(usage);

        setPrefVisible(Constants.KEY_NOTIF_SLIDER_ACTION_TOP, !appMode);
        setPrefVisible(Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE, !appMode);
        setPrefVisible(Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM, !appMode);

        for (String key : SLIDER_APP_KEYS) {
            setPrefVisible(key, appMode);
        }
    }

    private void setPrefVisible(String key, boolean visible) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setVisible(visible);
        }
    }

    private void updateSliderAppSummary(String key) {
        Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        String pkg = prefs.getString(key, "");
        if (TextUtils.isEmpty(pkg)) {
            pref.setSummary(R.string.notification_slider_app_none);
            return;
        }
        PackageManager pm = getContext().getPackageManager();
        try {
            pref.setSummary(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (PackageManager.NameNotFoundException e) {
            pref.setSummary(R.string.notification_slider_app_none);
        }
    }

    private void showSliderAppSelectionDialog(String key) {
        if (getActivity() == null || getActivity().isFinishing()) {
            return;
        }

        final ListView list = new ListView(getActivity());
        HashSet<String> excludedPackages = new HashSet<>();
        excludedPackages.add(getContext().getPackageName());
        AppPreferencesHelper.setupPackageListAdapter(list, excludedPackages, getContext());

        final Dialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.notification_slider_app_dialog_title)
                .setView(list)
                .setNeutralButton(R.string.notification_slider_app_clear,
                        (d, which) -> setSliderApp(key, ""))
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            PackageItem info = (PackageItem) parent.getItemAtPosition(position);
            setSliderApp(key, info.packageName);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void setSliderApp(String key, String packageName) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        prefs.edit().putString(key, packageName).commit();
        updateSliderAppSummary(key);

        // Push the new package set to the KeyHandler in system_server
        ListPreference usagePref = (ListPreference) findPreference(
                Constants.KEY_NOTIF_SLIDER_USAGE);
        sendUpdateBroadcast(getActivity().getApplicationContext(),
                Integer.parseInt(usagePref.getValue()), getCurrentSliderActions());
    }

    private boolean handleSliderUsageDefaultsChange(String newValue) {
        int defaultsResId = getDefaultResIdForUsage(newValue);
        if (defaultsResId == 0) {
            return false;
        }
        return updateSliderActionDefaults(defaultsResId);
    }

    private boolean updateSliderActions(int entriesResId, int entryValuesResId) {
        String[] entries = getResources().getStringArray(entriesResId);
        String[] entryValues = getResources().getStringArray(entryValuesResId);
        return updateSliderPreference(Constants.KEY_NOTIF_SLIDER_ACTION_TOP,
                entries, entryValues) &&
            updateSliderPreference(Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE,
                    entries, entryValues) &&
            updateSliderPreference(Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM,
                    entries, entryValues);
    }

    private boolean updateSliderActionDefaults(int defaultsResId) {
        String[] defaults = getResources().getStringArray(defaultsResId);
        if (defaults.length != 3) {
            return false;
        }

        return updateSliderPreferenceValue(Constants.KEY_NOTIF_SLIDER_ACTION_TOP,
                defaults[0]) &&
            updateSliderPreferenceValue(Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE,
                    defaults[1]) &&
            updateSliderPreferenceValue(Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM,
                    defaults[2]);
    }

    private boolean updateSliderPreference(CharSequence key,
            String[] entries, String[] entryValues) {
        ListPreference pref = (ListPreference) findPreference(key);
        if (pref == null) {
            return false;
        }
        pref.setEntries(entries);
        pref.setEntryValues(entryValues);
        return true;
    }

    private boolean updateSliderPreferenceValue(CharSequence key,
            String value) {
        ListPreference pref = (ListPreference) findPreference(key);
        if (pref == null) {
            return false;
        }
        pref.setValue(value);
        return true;
    }

    private int[] getCurrentSliderActions() {
        int[] actions = new int[3];
        ListPreference p;

        p = (ListPreference) findPreference(
                Constants.KEY_NOTIF_SLIDER_ACTION_TOP);
        actions[0] = Integer.parseInt(p.getValue());

        p = (ListPreference) findPreference(
                Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE);
        actions[1] = Integer.parseInt(p.getValue());

        p = (ListPreference) findPreference(
                Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM);
        actions[2] = Integer.parseInt(p.getValue());

        return actions;
    }

    private boolean notifySliderUsageChange(String usage) {
        sendUpdateBroadcast(getActivity().getApplicationContext(), Integer.parseInt(usage),
                getCurrentSliderActions());
        return true;
    }

    private boolean notifySliderActionChange(int index, String value) {
        ListPreference p = (ListPreference) findPreference(
                Constants.KEY_NOTIF_SLIDER_USAGE);
        int usage = Integer.parseInt(p.getValue());

        int[] actions = getCurrentSliderActions();
        actions[index] = Integer.parseInt(value);

        sendUpdateBroadcast(getActivity().getApplicationContext(), usage, actions);
        return true;
    }

    public static void sendUpdateBroadcast(Context context,
            int usage, int[] actions) {
        Intent intent = new Intent(Constants.ACTION_UPDATE_SLIDER_SETTINGS);
        intent.putExtra(Constants.EXTRA_SLIDER_USAGE, usage);
        intent.putExtra(Constants.EXTRA_SLIDER_ACTIONS, actions);
        intent.putExtra(Constants.EXTRA_SLIDER_APPS, getSliderApps(context));
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        context.sendBroadcastAsUser(intent, UserHandle.CURRENT);
        Log.i(TAG, "update slider usage " + usage + " with actions: " +
                Arrays.toString(actions));
    }

    private static String[] getSliderApps(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return new String[] {
            prefs.getString(Constants.KEY_NOTIF_SLIDER_APP_TOP, ""),
            prefs.getString(Constants.KEY_NOTIF_SLIDER_APP_MIDDLE, ""),
            prefs.getString(Constants.KEY_NOTIF_SLIDER_APP_BOTTOM, "")
        };
    }

    public static void restoreSliderStates(Context context) {
        Resources res = context.getResources();
        SharedPreferences prefs = context.getSharedPreferences(
                context.getPackageName() + "_preferences", Context.MODE_PRIVATE);

        String usage = prefs.getString(Constants.KEY_NOTIF_SLIDER_USAGE,
                res.getString(R.string.config_defaultNotificationSliderUsage));

        int defaultsResId = getDefaultResIdForUsage(usage);
        if (defaultsResId == 0) {
            return;
        }

        String[] defaults = res.getStringArray(defaultsResId);
        if (defaults.length != 3) {
            return;
        }

        String actionTop = prefs.getString(
                Constants.KEY_NOTIF_SLIDER_ACTION_TOP, defaults[0]);

        String actionMiddle = prefs.getString(
                Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE, defaults[1]);

        String actionBottom = prefs.getString(
                Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM, defaults[2]);

        prefs.edit()
            .putString(Constants.KEY_NOTIF_SLIDER_USAGE, usage)
            .putString(Constants.KEY_NOTIF_SLIDER_ACTION_TOP, actionTop)
            .putString(Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE, actionMiddle)
            .putString(Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM, actionBottom)
            .commit();

        sendUpdateBroadcast(context, Integer.parseInt(usage), new int[] {
            Integer.parseInt(actionTop),
            Integer.parseInt(actionMiddle),
            Integer.parseInt(actionBottom)
        });
    }

    public static void restoreOnePulsePwmSetting(Context context) {
        if (FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)) {
            PwmController pwmController = PwmController.getInstance(context);
            if (pwmController.isPwmEnabled()) {
                pwmController.enablePwm();
            }
        }
    }

    private static int getDefaultResIdForUsage(String usage) {
        switch (usage) {
            case Constants.NOTIF_SLIDER_FOR_NOTIFICATION:
                return R.array.config_defaultSliderActionsForNotification;
            case Constants.NOTIF_SLIDER_FOR_FLASHLIGHT:
                return R.array.config_defaultSliderActionsForFlashlight;
            case Constants.NOTIF_SLIDER_FOR_BRIGHTNESS:
                return R.array.config_defaultSliderActionsForBrightness;
            case Constants.NOTIF_SLIDER_FOR_ROTATION:
                return R.array.config_defaultSliderActionsForRotation;
            case Constants.NOTIF_SLIDER_FOR_RINGER:
                return R.array.config_defaultSliderActionsForRinger;
            case Constants.NOTIF_SLIDER_FOR_NOTIFICATION_RINGER:
                return R.array.config_defaultSliderActionsForNotificationRinger;
            case Constants.NOTIF_SLIDER_FOR_APPLAUNCH:
                return R.array.config_defaultSliderActionsForApplaunch;
            default:
                return 0;
        }
    }
}
