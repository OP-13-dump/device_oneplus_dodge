/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Shared app list manager for BypassCharging and GameBar.
 * Handles app list persistence, parsing, and UI integration.
 */

package org.lineageos.device.settings.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppListManager {
    private static final String TAG = "AppListManager";

    private final Context mContext;
    private final String mPreferenceKey;
    private final PackageManager mPackageManager;
    private final Callback mCallback;

    private String mCachedPackageList;
    private Map<String, AppItem> mAppMap;

    /**
     * Callback for app list changes
     */
    public interface Callback {
        void onAppListChanged();
    }

    /**
     * Represents an app item
     */
    public static class AppItem {
        public final String packageName;

        public AppItem(String packageName) {
            this.packageName = packageName;
        }

        @Override
        public String toString() {
            return packageName;
        }

        public static AppItem fromString(String value) {
            if (TextUtils.isEmpty(value)) return null;
            return new AppItem(value);
        }
    }

    public AppListManager(Context context, String preferenceKey, Callback callback) {
        this.mContext = context.getApplicationContext();
        this.mPreferenceKey = preferenceKey;
        this.mCallback = callback;
        this.mPackageManager = mContext.getPackageManager();
        this.mAppMap = new HashMap<>();
    }

    /**
     * Parse and refresh app list from preferences
     * Returns true if list changed
     */
    public boolean refreshAppList() {
        String appListString = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(mPreferenceKey, "");

        if (!TextUtils.equals(mCachedPackageList, appListString)) {
            mCachedPackageList = appListString;
            mAppMap.clear();
            parseAndAddToMap(appListString, mAppMap);
            if (Constants.DEBUG) Log.i(TAG, "App list refreshed: " + mAppMap.size() + " apps");
            return true;
        }
        return false;
    }

    /**
     * Get all apps in list
     */
    public Map<String, AppItem> getAppList() {
        return new HashMap<>(mAppMap);
    }

    /**
     * Add app to list
     */
    public void addApp(String packageName) {
        AppItem app = mAppMap.get(packageName);
        if (app == null) {
            app = new AppItem(packageName);
            mAppMap.put(packageName, app);
            saveAppList();
            if (Constants.DEBUG) Log.i(TAG, "App added: " + packageName);
            notifyCallback();
        }
    }

    /**
     * Remove app from list
     */
    public void removeApp(String packageName) {
        if (mAppMap.remove(packageName) != null) {
            saveAppList();
            if (Constants.DEBUG) Log.i(TAG, "App removed: " + packageName);
            notifyCallback();
        }
    }

    /**
     * Check if app is in list
     */
    public boolean containsApp(String packageName) {
        return mAppMap.containsKey(packageName);
    }

    /**
     * Refresh UI preferences from app list
     */
    public void refreshPreferences(PreferenceGroup prefGroup, Preference addAppPref,
                                   OnAppClickListener listener) {
        if (prefGroup != null) {
            prefGroup.removeAll();

            for (AppItem app : mAppMap.values()) {
                try {
                    Preference pref = createPreferenceFromInfo(app, listener);
                    prefGroup.addPreference(pref);
                } catch (PackageManager.NameNotFoundException e) {
                    if (Constants.DEBUG) Log.w(TAG, "App not found: " + app.packageName, e);
                }
            }

            // Add button preference
            if (addAppPref != null) {
                addAppPref.setOrder(0);
                prefGroup.addPreference(addAppPref);
            }
        }
    }

    /**
     * Create preference from app info
     */
    private Preference createPreferenceFromInfo(AppItem app, OnAppClickListener listener)
            throws PackageManager.NameNotFoundException {
        PackageInfo info = mPackageManager.getPackageInfo(app.packageName,
                PackageManager.GET_META_DATA);

        Preference pref = new Preference(mContext);
        pref.setKey(app.packageName);
        pref.setTitle(info.applicationInfo.loadLabel(mPackageManager));
        pref.setIcon(info.applicationInfo.loadIcon(mPackageManager));
        pref.setPersistent(false);

        if (listener != null) {
            pref.setOnPreferenceClickListener(p -> {
                listener.onAppClicked(app.packageName);
                return true;
            });
        }

        return pref;
    }

    /**
     * Get list of all non-system apps excluding current app
     */
    public List<AppItem> getAvailableApps(String currentPackageName) {
        List<AppItem> available = new ArrayList<>();
        List<PackageInfo> packages = mPackageManager.getInstalledPackages(
                PackageManager.GET_META_DATA);

        for (PackageInfo info : packages) {
            boolean isSystem = (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isSelf = info.packageName.equals(currentPackageName);
            boolean alreadyInList = mAppMap.containsKey(info.packageName);

            if (!isSystem && !isSelf && !alreadyInList) {
                available.add(new AppItem(info.packageName));
            }
        }

        if (Constants.DEBUG) Log.i(TAG, "Available apps: " + available.size());
        return available;
    }

    /**
     * Callback for app click (add/remove)
     */
    public interface OnAppClickListener {
        void onAppClicked(String packageName);
    }

    // ===== Private helpers =====

    private void parseAndAddToMap(String baseString, Map<String, AppItem> map) {
        if (baseString == null) return;

        final String[] array = TextUtils.split(baseString, "\\|");
        for (String item : array) {
            if (TextUtils.isEmpty(item)) continue;
            AppItem app = AppItem.fromString(item);
            if (app != null) {
                map.put(app.packageName, app);
            }
        }
    }

    private void saveAppList() {
        List<String> settings = new ArrayList<>();
        for (AppItem app : mAppMap.values()) {
            settings.add(app.toString());
        }
        final String value = TextUtils.join("|", settings);

        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .putString(mPreferenceKey, value)
                .apply();

        if (Constants.DEBUG) Log.i(TAG, "App list saved: " + mAppMap.size() + " apps");
    }

    private void notifyCallback() {
        if (mCallback != null) {
            mCallback.onAppListChanged();
        }
    }
}
