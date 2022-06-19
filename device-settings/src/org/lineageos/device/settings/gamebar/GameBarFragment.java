/*
 * Copyright (C) 2025 kenway214
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
package org.lineageos.device.settings.gamebar;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;
import org.lineageos.device.settings.preferences.CustomSeekBarPreference;
import org.lineageos.device.settings.utils.AppPreferencesHelper;
import org.lineageos.device.settings.utils.PackageListAdapter.PackageItem;
import org.lineageos.device.settings.utils.AppListManager;

import java.util.HashSet;

public class GameBarFragment extends PreferenceFragmentCompat {

    private GameBar mGameBar;
    private SwitchPreferenceCompat mFpsSwitch;
    private SwitchPreferenceCompat mBatteryTempSwitch;
    private SwitchPreferenceCompat mCpuUsageSwitch;
    private SwitchPreferenceCompat mCpuClockSwitch;
    private SwitchPreferenceCompat mCpuTempSwitch;
    private SwitchPreferenceCompat mRamSwitch;
    private SwitchPreferenceCompat mGpuUsageSwitch;
    private SwitchPreferenceCompat mGpuClockSwitch;
    private SwitchPreferenceCompat mGpuTempSwitch;
    private Preference mCaptureStartPref;
    private Preference mCaptureStopPref;
    private Preference mCaptureExportPref;
    private SwitchPreferenceCompat mDoubleTapCapturePref;
    private SwitchPreferenceCompat mSingleTapTogglePref;
    private SwitchPreferenceCompat mLongPressEnablePref;
    private ListPreference mLongPressTimeoutPref;
    private CustomSeekBarPreference mTextSizePref;
    private CustomSeekBarPreference mBgAlphaPref;
    private CustomSeekBarPreference mCornerRadiusPref;
    private CustomSeekBarPreference mPaddingPref;
    private CustomSeekBarPreference mItemSpacingPref;
    private ListPreference mUpdateIntervalPref;
    private ListPreference mTitleColorPref;
    private ListPreference mValueColorPref;
    private ListPreference mPositionPref;
    private ListPreference mSplitModePref;
    private Preference mResetPositionPref;

    private PreferenceGroup mPackagesPreList;
    private Preference mAddPackagesPref;
    private AppListManager mAppListManager;

    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener;

    private static final String GAME_BAR_ADD_PACKAGES = "game_bar_add_packages";
    private static final String GAME_BAR_APPLICATIONS = "game_bar_applications";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.game_bar_preferences, rootKey);

        mGameBar = GameBar.getInstance(getContext());

        mAppListManager = new AppListManager(
                getContext(),
                Constants.KEY_GAMEBAR_AUTO_APPS,
                this::onAppListChanged
        );

        mPackagesPreList = findPreference(GAME_BAR_APPLICATIONS);
        if (mPackagesPreList != null) {
            mPackagesPreList.setOrderingAsAdded(false);
        }

        mAddPackagesPref = findPreference(GAME_BAR_ADD_PACKAGES);
        if (mAddPackagesPref != null) {
            mAddPackagesPref.setOnPreferenceClickListener(pref -> {
                showAppSelectionDialog();
                return true;
            });
        }

        // Master enable
        SwitchPreferenceCompat enableSwitch = findPreference("game_bar_enable");
        if (enableSwitch != null) {
            enableSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                boolean enabled = (boolean) newValue;
                if (enabled) {
                    if (Settings.canDrawOverlays(getContext())) {
                        mGameBar.applyPreferences();
                        mGameBar.show();
                    } else {
                        // Block enable if overlay permission is missing
                        return false;
                    }
                } else {
                    mGameBar.hide();
                }
                return true;
            });
        }

        // Stats toggles
        mFpsSwitch = findPreference("game_bar_fps_enable");
        if (mFpsSwitch != null) {
            mFpsSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowFps((boolean) newValue);
                return true;
            });
        }

        mBatteryTempSwitch = findPreference("game_bar_temp_enable");
        if (mBatteryTempSwitch != null) {
            mBatteryTempSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowBatteryTemp((boolean) newValue);
                return true;
            });
        }

        mCpuUsageSwitch = findPreference("game_bar_cpu_usage_enable");
        if (mCpuUsageSwitch != null) {
            mCpuUsageSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowCpuUsage((boolean) newValue);
                return true;
            });
        }

        mCpuClockSwitch = findPreference("game_bar_cpu_clock_enable");
        if (mCpuClockSwitch != null) {
            mCpuClockSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowCpuClock((boolean) newValue);
                return true;
            });
        }

        mCpuTempSwitch = findPreference("game_bar_cpu_temp_enable");
        if (mCpuTempSwitch != null) {
            mCpuTempSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowCpuTemp((boolean) newValue);
                return true;
            });
        }

        mRamSwitch = findPreference("game_bar_ram_enable");
        if (mRamSwitch != null) {
            mRamSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowRam((boolean) newValue);
                return true;
            });
        }

        mGpuUsageSwitch = findPreference("game_bar_gpu_usage_enable");
        if (mGpuUsageSwitch != null) {
            mGpuUsageSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowGpuUsage((boolean) newValue);
                return true;
            });
        }

        mGpuClockSwitch = findPreference("game_bar_gpu_clock_enable");
        if (mGpuClockSwitch != null) {
            mGpuClockSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowGpuClock((boolean) newValue);
                return true;
            });
        }

        mGpuTempSwitch = findPreference("game_bar_gpu_temp_enable");
        if (mGpuTempSwitch != null) {
            mGpuTempSwitch.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setShowGpuTemp((boolean) newValue);
                return true;
            });
        }

        // Data capture
        mCaptureStartPref = findPreference("game_bar_capture_start");
        if (mCaptureStartPref != null) {
            mCaptureStartPref.setOnPreferenceClickListener(pref -> {
                GameDataExport.getInstance().startCapture();
                return true;
            });
        }

        mCaptureStopPref = findPreference("game_bar_capture_stop");
        if (mCaptureStopPref != null) {
            mCaptureStopPref.setOnPreferenceClickListener(pref -> {
                GameDataExport.getInstance().stopCapture();
                return true;
            });
        }

        mCaptureExportPref = findPreference("game_bar_capture_export");
        if (mCaptureExportPref != null) {
            mCaptureExportPref.setOnPreferenceClickListener(pref -> {
                GameDataExport.getInstance().exportDataToCsv();
                return true;
            });
        }

        // Gestures
        mDoubleTapCapturePref = findPreference("game_bar_doubletap_capture");
        if (mDoubleTapCapturePref != null) {
            mDoubleTapCapturePref.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setDoubleTapCaptureEnabled((boolean) newValue);
                return true;
            });
        }

        mSingleTapTogglePref = findPreference("game_bar_single_tap_toggle");
        if (mSingleTapTogglePref != null) {
            mSingleTapTogglePref.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setSingleTapToggleEnabled((boolean) newValue);
                return true;
            });
        }

        mLongPressEnablePref = findPreference("game_bar_longpress_enable");
        if (mLongPressEnablePref != null) {
            mLongPressEnablePref.setOnPreferenceChangeListener((pref, newValue) -> {
                mGameBar.setLongPressEnabled((boolean) newValue);
                return true;
            });
        }

        mLongPressTimeoutPref = findPreference("game_bar_longpress_timeout");
        if (mLongPressTimeoutPref != null) {
            mLongPressTimeoutPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof String) {
                    long ms = Long.parseLong((String) newValue);
                    mGameBar.setLongPressThresholdMs(ms);
                }
                return true;
            });
        }

        // UI customization
        mTextSizePref = findPreference("game_bar_text_size");
        if (mTextSizePref != null) {
            mTextSizePref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof Integer) {
                    mGameBar.updateTextSize((Integer) newValue);
                }
                return true;
            });
        }

        mBgAlphaPref = findPreference("game_bar_background_alpha");
        if (mBgAlphaPref != null) {
            mBgAlphaPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof Integer) {
                    mGameBar.updateBackgroundAlpha((Integer) newValue);
                }
                return true;
            });
        }

        mCornerRadiusPref = findPreference("game_bar_corner_radius");
        if (mCornerRadiusPref != null) {
            mCornerRadiusPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof Integer) {
                    mGameBar.updateCornerRadius((Integer) newValue);
                }
                return true;
            });
        }

        mPaddingPref = findPreference("game_bar_padding");
        if (mPaddingPref != null) {
            mPaddingPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof Integer) {
                    mGameBar.updatePadding((Integer) newValue);
                }
                return true;
            });
        }

        mItemSpacingPref = findPreference("game_bar_item_spacing");
        if (mItemSpacingPref != null) {
            mItemSpacingPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof Integer) {
                    mGameBar.updateItemSpacing((Integer) newValue);
                }
                return true;
            });
        }

        mUpdateIntervalPref = findPreference("game_bar_update_interval");
        if (mUpdateIntervalPref != null) {
            mUpdateIntervalPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof String) {
                    mGameBar.updateUpdateInterval((String) newValue);
                }
                return true;
            });
        }

        mTitleColorPref = findPreference("game_bar_title_color");
        if (mTitleColorPref != null) {
            mTitleColorPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof String) {
                    String hex = (String) newValue;
                    if (mGameBar.isValidHexColor(hex)) {
                        mGameBar.updateTitleColor(hex);
                        return true;
                    }
                    return false;
                }
                return false;
            });
        }

        mValueColorPref = findPreference("game_bar_value_color");
        if (mValueColorPref != null) {
            mValueColorPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof String) {
                    String hex = (String) newValue;
                    if (mGameBar.isValidHexColor(hex)) {
                        mGameBar.updateValueColor(hex);
                        return true;
                    }
                    return false;
                }
                return false;
            });
        }

        mPositionPref = findPreference("game_bar_position");
        if (mPositionPref != null) {
            mPositionPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof String) {
                    mGameBar.updatePosition((String) newValue);
                }
                return true;
            });
        }

        mResetPositionPref = findPreference("game_bar_reset_position");
        if (mResetPositionPref != null) {
            mResetPositionPref.setOnPreferenceClickListener(pref -> {
                Context ctx = getContext();
                if (ctx != null) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
                    prefs.edit()
                            .remove("game_bar_dragged_x")
                            .remove("game_bar_dragged_y")
                            .apply();
                    mGameBar.updatePosition("top_left");
                }
                return true;
            });
        }

        mSplitModePref = findPreference("game_bar_split_mode");
        if (mSplitModePref != null) {
            mSplitModePref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue instanceof String) {
                    mGameBar.updateSplitMode((String) newValue);
                }
                return true;
            });
        }
    }

    // FIX #8: Fragment Lifecycle - Complete Methods with Null Safety
    @Override
    public void onResume() {
        super.onResume();

        Context ctx = getContext();
        if (ctx == null) {
            android.util.Log.w("GameBarFragment", "Context is null in onResume");
            return;
        }

        if (!hasUsageStatsPermission(ctx)) {
            requestUsageStatsPermission();
        }

        if (mAppListManager != null && mAppListManager.refreshAppList()) {
            refreshAppListUI();
        }

        // Sync split mode ListPreference value + summary from persisted value
        refreshSplitModeSummaryFromPrefs();

        // Register a prefs listener so single-tap changes (from overlay) update UI live
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        mPrefListener = (sp, key) -> {
            if ("game_bar_split_mode".equals(key)) {
                refreshSplitModeSummaryFromPrefs();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    @Override
    public void onPause() {
        super.onPause();

        // FIX #8: Unregister listener with null safety
        Context ctx = getContext();
        if (ctx != null && mPrefListener != null) {
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
                prefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
            } catch (Exception e) {
                android.util.Log.w("GameBarFragment", "Failed to unregister preference listener", e);
            }
            mPrefListener = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // FIX #8: Additional cleanup to prevent leaks
        if (mPrefListener != null) {
            Context ctx = getContext();
            if (ctx != null) {
                try {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
                    prefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
                } catch (Exception e) {
                    android.util.Log.w("GameBarFragment", "Failed to unregister listener in onDestroyView", e);
                }
            }
            mPrefListener = null;
        }

        // Clear references to prevent memory leaks
        mGameBar = null;
        mAppListManager = null;
    }

    private void refreshSplitModeSummaryFromPrefs() {
        if (mSplitModePref == null) return;

        Context ctx = getContext();
        if (ctx == null) {
            android.util.Log.w("GameBarFragment", "Context is null, cannot refresh split mode");
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String newMode = prefs.getString("game_bar_split_mode", "stacked");

        mSplitModePref.setValue(newMode);

        String[] entries = getResources().getStringArray(R.array.game_bar_split_mode_entries);
        String[] values  = getResources().getStringArray(R.array.game_bar_split_mode_values);

        // default to first entry if not found
        String summary = entries.length > 0 ? entries[0] : newMode;
        for (int i = 0; i < values.length && i < entries.length; i++) {
            if (values[i].equals(newMode)) {
                summary = entries[i];
                break;
            }
        }
        mSplitModePref.setSummary(summary);
    }

    private void refreshAppListUI() {
        Context ctx = getContext();
        if (ctx == null || mPackagesPreList == null || mAddPackagesPref == null) {
            android.util.Log.w("GameBarFragment", "Cannot refresh app list UI - missing context or preferences");
            return;
        }

        if (mAppListManager == null) {
            android.util.Log.w("GameBarFragment", "AppListManager is null");
            return;
        }

        AppPreferencesHelper.refreshAppPreferences(
                mPackagesPreList,
                mAddPackagesPref,
                mAppListManager.getAppList(),
                ctx,
                packageName -> showDeleteConfirmation(packageName)
        );
    }

    private void showDeleteConfirmation(String packageName) {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            android.util.Log.w("GameBarFragment", "Cannot show delete dialog - fragment not attached");
            return;
        }

        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.delete)
                .setMessage(R.string.delete_message)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (mAppListManager != null) {
                        mAppListManager.removeApp(packageName);
                        refreshAppListUI();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAppSelectionDialog() {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            android.util.Log.w("GameBarFragment", "Cannot show app selection - fragment not attached");
            return;
        }

        Context ctx = getContext();
        if (ctx == null || mAppListManager == null) {
            android.util.Log.w("GameBarFragment", "Cannot show app selection - missing context or manager");
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        final Dialog dialog;
        final ListView list = new ListView(requireActivity());

        HashSet<String> excludedPackages = new HashSet<>(mAppListManager.getAppList().keySet());
        excludedPackages.add(ctx.getPackageName());

        AppPreferencesHelper.setupPackageListAdapter(list, excludedPackages, ctx);

        builder.setTitle(R.string.add_app);
        builder.setView(list);
        dialog = builder.create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            PackageItem info = (PackageItem) parent.getItemAtPosition(position);
            if (mAppListManager != null) {
                mAppListManager.addApp(info.packageName);
                refreshAppListUI();
            }
            dialog.dismiss();
        });

        if (!requireActivity().isFinishing()) {
            dialog.show();
        }
    }

    private void onAppListChanged() {
        Context ctx = getContext();
        if (ctx != null) {
            GameBarMonitorService.notifyStateChanged(ctx);
        }
    }

    private boolean hasUsageStatsPermission(Context context) {
        android.app.AppOpsManager appOps = (android.app.AppOpsManager)
                context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.getPackageName()
        );
        return (mode == android.app.AppOpsManager.MODE_ALLOWED);
    }

    private void requestUsageStatsPermission() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
    }
}
