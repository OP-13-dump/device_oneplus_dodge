/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.bypasschrg;

import android.app.Dialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.TwoStatePreference;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;
import org.lineageos.device.settings.preferences.CustomSeekBarPreference;
import org.lineageos.device.settings.utils.AppPreferencesHelper;
import org.lineageos.device.settings.utils.PackageListAdapter;
import org.lineageos.device.settings.utils.PackageListAdapter.PackageItem;
import org.lineageos.device.settings.utils.AppListManager;

import java.util.HashSet;

public class BypassChargingFragment extends PreferenceFragmentCompat {

    private static final String BYPASS_CHARGING_ADD_PACKAGES = "bypass_charging_add_packages";
    private static final String BYPASS_CHARGING_APPLICATIONS = "bypass_charging_applications";

    private PackageManager mPackageManager;
    private PreferenceGroup mPackagesPreList;
    private Preference mPackagesPref;

    private AppListManager mAppListManager;
    private TwoStatePreference mBypassPreference;
    private BypassChargingController mBypassController;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.bypass_charging_settings, rootKey);

        mBypassController = BypassChargingController.getInstance(getContext());
        mPackageManager = requireContext().getPackageManager();

        mAppListManager = new AppListManager(
                getContext(),
                Constants.KEY_BYPASS_CHARGING_APPS,
                this::onAppListChanged
        );

        boolean bypassSupported = mBypassController.isBypassChargingSupported();

        mBypassPreference = findPreference(Constants.KEY_BYPASS_CHARGING);
        if (mBypassPreference != null) {
            mBypassPreference.setEnabled(bypassSupported);
            if (bypassSupported) {
                updateBypassToggleState();
                mBypassPreference.setOnPreferenceChangeListener((pref, newValue) -> {
                    boolean enable = (boolean) newValue;
                    if (enable) {
                        mBypassController.enableBypassCharging();
                    } else {
                        mBypassController.disableBypassCharging();
                    }
                    return true;
                });
            } else {
                mBypassPreference.setSummary(R.string.bypass_charging_unavailable);
            }
        }

        CustomSeekBarPreference targetPreference = findPreference(Constants.KEY_BYPASS_CHARGING_TARGET);
        if (targetPreference != null) {
            targetPreference.setValue(mBypassController.getBypassChargingTarget());
            targetPreference.setMin(Constants.BYPASS_TARGET_MIN);
            targetPreference.setMax(Constants.BYPASS_TARGET_MAX);
            targetPreference.setDefaultValue(Constants.BYPASS_TARGET_DEFAULT, true);
            targetPreference.setOnPreferenceChangeListener((pref, newValue) -> {
                int target = (int) newValue;
                if (target < Constants.BYPASS_TARGET_MIN || target > Constants.BYPASS_TARGET_MAX) {
                    return false;
                }
                mBypassController.setBypassChargingTarget(target);
                return true;
            });
        }

        mPackagesPreList = findPreference(BYPASS_CHARGING_APPLICATIONS);
        if (mPackagesPreList != null) {
            mPackagesPreList.setOrderingAsAdded(false);
        }

        mPackagesPref = findPreference(BYPASS_CHARGING_ADD_PACKAGES);
        if (mPackagesPref != null) {
            mPackagesPref.setOnPreferenceClickListener(pref -> {
                showAppSelectionDialog();
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAppListManager.refreshAppList()) {
            refreshUI();
        }
        updateBypassToggleState();
    }

    private void updateBypassToggleState() {
        if (mBypassPreference != null && mBypassController != null) {
            boolean enabled = mBypassController.getBypassChargingStatus() != Constants.BYPASS_OFF;
            mBypassPreference.setChecked(enabled);
        }
    }

    private void refreshUI() {
        AppPreferencesHelper.refreshAppPreferences(
                mPackagesPreList,
                mPackagesPref,
                mAppListManager.getAppList(),
                getContext(),
                packageName -> showDeleteConfirmation(packageName)
        );
    }

    private void showDeleteConfirmation(String packageName) {
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.delete)
                .setMessage(R.string.delete_message)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mAppListManager.removeApp(packageName);
                    refreshUI();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAppSelectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        final Dialog dialog;
        final ListView list = new ListView(requireActivity());

        HashSet<String> excludedPackages = new HashSet<>(mAppListManager.getAppList().keySet());
        excludedPackages.add(getContext().getPackageName());

        AppPreferencesHelper.setupPackageListAdapter(list, excludedPackages, getContext());

        builder.setTitle(R.string.add_app);
        builder.setView(list);
        dialog = builder.create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            PackageItem info = (PackageItem) parent.getItemAtPosition(position);
            mAppListManager.addApp(info.packageName);
            refreshUI();
            dialog.dismiss();
        });

        if (!requireActivity().isFinishing()) {
            dialog.show();
        }
    }

    private void onAppListChanged() {
        BypassChargingManager.notifyStateChanged(getContext(), mBypassController.getState());
    }
}
