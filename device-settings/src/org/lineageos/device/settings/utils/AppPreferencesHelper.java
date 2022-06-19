/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Helper class for managing app list preferences across multiple fragments
 */

package org.lineageos.device.settings.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import java.util.Map;

import org.lineageos.device.settings.Constants;

public class AppPreferencesHelper {
    private static final String TAG = "AppPreferencesHelper";

    /**
     * Callback interface for app preference clicks
     */
    public interface OnAppPreferenceClickListener {
        void onAppClicked(String packageName);
    }

    /**
     * Refresh the app list preferences in a category.
     * Removes only app preferences, keeps the static "Add" button.
     *
     * @param prefGroup The preference category
     * @param addButtonPref The static "Add app" button preference
     * @param appList Map of package names to app data
     * @param context Context for loading app info
     * @param clickListener Callback when an app is clicked
     */
    public static void refreshAppPreferences(
            PreferenceGroup prefGroup,
            Preference addButtonPref,
            Map<String, ?> appList,
            Context context,
            OnAppPreferenceClickListener clickListener) {

        if (prefGroup == null) {
            return;
        }

        // Remove only app preferences, keep the static button
        int initialCount = prefGroup.getPreferenceCount();
        for (int i = initialCount - 1; i >= 0; i--) {
            Preference pref = prefGroup.getPreference(i);
            if (!pref.equals(addButtonPref)) {
                prefGroup.removePreference(pref);
            }
        }

        // Add app preferences from list
        PackageManager pm = context.getPackageManager();
        for (String packageName : appList.keySet()) {
            try {
                Preference appPref = new Preference(context);
                appPref.setKey(packageName);
                appPref.setTitle(pm.getApplicationInfo(packageName, 0).loadLabel(pm));
                appPref.setIcon(pm.getApplicationInfo(packageName, 0).loadIcon(pm));
                appPref.setPersistent(false);

                if (clickListener != null) {
                    appPref.setOnPreferenceClickListener(pref -> {
                        clickListener.onAppClicked(packageName);
                        return true;
                    });
                }

                prefGroup.addPreference(appPref);
            } catch (PackageManager.NameNotFoundException e) {
                if (Constants.DEBUG) {
                    Log.w(TAG, "App not found: " + packageName, e);
                }
            }
        }
    }

    /**
     * Setup the package list adapter for app selection dialog.
     * Handles common setup tasks to avoid code duplication.
     *
     * @param list The ListView for the dialog
     * @param excludedPackages Packages to exclude from the list
     * @param context Context
     */
    public static void setupPackageListAdapter(
            android.widget.ListView list,
            java.util.HashSet<String> excludedPackages,
            Context context) {

        PackageListAdapter adapter = new PackageListAdapter(context);
        adapter.setExcludedPackages(excludedPackages);
        list.setAdapter(adapter);
        list.setDivider(null);
    }
}
