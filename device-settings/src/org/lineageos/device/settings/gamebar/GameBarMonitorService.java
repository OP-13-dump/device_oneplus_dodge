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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.ForegroundAppDetector;

import java.util.HashSet;
import java.util.Set;

public class GameBarMonitorService extends Service {
    private static final String TAG = "GameBarMonitor";

    private static volatile GameBarMonitorService sInstance;

    private Handler mHandler;
    private ForegroundAppDetector mForegroundDetector;
    private boolean mAppMonitoringActive = false;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mHandler = new Handler();
        mForegroundDetector = ForegroundAppDetector.getInstance(this);
        if (Constants.DEBUG) Log.i(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mHandler.post(this::handleStateChanged);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterPrefListener();
        stopAppMonitoring();
        GameBar.getInstance(this).hide();
        sInstance = null;
        if (Constants.DEBUG) Log.i(TAG, "Service destroyed");
    }

    // ===== Public API =====

    public static void notifyStateChanged(Context context) {
        GameBarMonitorService instance = sInstance;
        if (instance == null) {
            // Start service
            Intent serviceIntent = new Intent(context, GameBarMonitorService.class);
            try {
                context.startService(serviceIntent);
                if (Constants.DEBUG) Log.i(TAG, "Service started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service", e);
            }
            return;
        }

        if (Constants.DEBUG) Log.i(TAG, "State changed notification received");
        instance.mHandler.post(instance::handleStateChanged);
    }

    // ===== State handling =====

    private void handleStateChanged() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean masterEnabled = prefs.getBoolean("game_bar_enable", false);
        boolean hasAutoEnableApps = hasAutoEnableApps();

        if (Constants.DEBUG) Log.i(TAG, "State: masterEnabled=" + masterEnabled + ", hasAutoApps=" + hasAutoEnableApps);

        // Determine if we need to do anything
        if (!masterEnabled && !hasAutoEnableApps) {
            if (Constants.DEBUG) Log.i(TAG, "Nothing to monitor - stopping service");
            unregisterPrefListener();
            stopAppMonitoring();
            GameBar.getInstance(this).hide();
            stopSelf();
            return;
        }

        // Register preference listener if not already
        registerPrefListenerIfNeeded();

        // Determine monitoring strategy
        if (masterEnabled) {
            // Master switch ON - always show, no need to monitor
            if (Constants.DEBUG) Log.i(TAG, "Master enabled - showing GameBar, stopping app monitoring");
            stopAppMonitoring();
            GameBar.getInstance(this).applyPreferences();
            GameBar.getInstance(this).show();
        } else if (hasAutoEnableApps) {
            // Master switch OFF, auto-enable list exists - monitor apps
            if (!mAppMonitoringActive) {
                if (Constants.DEBUG) Log.i(TAG, "Starting app monitoring for auto-enable");
                startAppMonitoring();
            }
        }
    }

    // ===== App monitoring =====

    private void startAppMonitoring() {
        if (mAppMonitoringActive) {
            if (Constants.DEBUG) Log.w(TAG, "App monitoring already active");
            return;
        }

        mAppMonitoringActive = true;
        GameBarMonitorService self = this;
        mForegroundDetector.startMonitoring("GameBar", packageName -> {
            if (Constants.DEBUG) Log.i(TAG, "Listener callback - Foreground app: " + packageName);

            Set<String> autoApps = self.getAutoEnableApps();

            if (Constants.DEBUG) Log.i(TAG, "GameBar auto apps list size: " + autoApps.size() + ", searching for: " + packageName);

            if (autoApps.contains(packageName)) {
                if (Constants.DEBUG) Log.i(TAG, "App " + packageName + " in auto list - showing");
                GameBar.getInstance(self).applyPreferences();
                GameBar.getInstance(self).show();
            } else {
                if (Constants.DEBUG) Log.i(TAG, "App " + packageName + " not in auto list - hiding");
                GameBar.getInstance(self).hide();
            }
        });
        if (Constants.DEBUG) Log.i(TAG, "App monitoring listener registered");
    }

    private void stopAppMonitoring() {
        if (!mAppMonitoringActive) {
            return;
        }

        mAppMonitoringActive = false;
        mForegroundDetector.stopMonitoring("GameBar");
    }

    // ===== Preference management =====

    private void registerPrefListenerIfNeeded() {
        if (mPrefListener != null) {
            return;
        }

        mPrefListener = (prefs, key) -> {
            if ("game_bar_enable".equals(key) || Constants.KEY_GAMEBAR_AUTO_APPS.equals(key)) {
                if (Constants.DEBUG) Log.i(TAG, "Preference changed: " + key);
                mHandler.post(this::handleStateChanged);
            }
        };

        try {
            PreferenceManager.getDefaultSharedPreferences(this)
                    .registerOnSharedPreferenceChangeListener(mPrefListener);
            if (Constants.DEBUG) Log.i(TAG, "Preference listener registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register preference listener", e);
            mPrefListener = null;
        }
    }

    private void unregisterPrefListener() {
        if (mPrefListener != null) {
            try {
                PreferenceManager.getDefaultSharedPreferences(this)
                        .unregisterOnSharedPreferenceChangeListener(mPrefListener);
                if (Constants.DEBUG) Log.i(TAG, "Preference listener unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister preference listener", e);
            }
            mPrefListener = null;
        }
    }

    private Set<String> getAutoEnableApps() {
        String raw = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(Constants.KEY_GAMEBAR_AUTO_APPS, "");
        Set<String> apps = new HashSet<>();
        if (!TextUtils.isEmpty(raw)) {
            String[] packages = raw.split("\\|");
            for (String pkg : packages) {
                if (!TextUtils.isEmpty(pkg)) {
                    apps.add(pkg.trim());
                }
            }
        }
        return apps;
    }

    private boolean hasAutoEnableApps() {
        String raw = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(Constants.KEY_GAMEBAR_AUTO_APPS, "");
        return !TextUtils.isEmpty(raw);
    }
}
