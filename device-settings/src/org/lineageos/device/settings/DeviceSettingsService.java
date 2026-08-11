/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Central service for DeviceSettings.
 * - Always running after boot
 * - Handles screen on/off events
 * - Handles power connect/disconnect events
 * - Initializes and manages other services/controllers
 */

package org.lineageos.device.settings;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.bypasschrg.BypassChargingController;
import org.lineageos.device.settings.bypasschrg.BypassChargingManager;
import org.lineageos.device.settings.display.DisplayModeController;
import org.lineageos.device.settings.display.HbmController;
import org.lineageos.device.settings.display.PwmController;
import org.lineageos.device.settings.display.SunlightBoostController;
import org.lineageos.device.settings.gamebar.GameBar;
import org.lineageos.device.settings.gamebar.GameBarMonitorService;
import org.lineageos.device.settings.refreshrate.RefreshRateController;
import org.lineageos.device.settings.refreshrate.RefreshRateMonitorService;
import org.lineageos.device.settings.utils.FileUtils;

public class DeviceSettingsService extends Service {
    private static final String TAG = "DeviceSettingsService";

    private BroadcastReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Constants.DEBUG) Log.i(TAG, "Service created");

        // Initialize all subsystems
        initializeSubsystems();

        // Register receivers for screen and power events
        registerReceivers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Constants.DEBUG) Log.i(TAG, "Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (Constants.DEBUG) Log.i(TAG, "Service destroyed");
        unregisterReceivers();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===== Initialization =====

    private void initializeSubsystems() {
        initializeBypassCharging();
        initializePwm();
        initializeTestTe();
        initializeSunlightBoost();
        initializeGameBar();
        initializeRefreshRate();
    }

    private void initializeSunlightBoost() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing SunlightBoost");
        try {
            SunlightBoostController.getInstance(this).init();
            if (Constants.DEBUG) Log.i(TAG, "SunlightBoost initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SunlightBoost", e);
        }
    }

    private void initializeBypassCharging() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing BypassCharging");
        try {
            BypassChargingController controller = BypassChargingController.getInstance(this);
            // The kernel charging vote does not survive a reboot (and a stale vote can
            // survive an app crash), so sync the hardware with the persisted state first
            controller.reconcileHardwareState();
            BypassChargingManager.notifyStateChanged(this, controller.getState());
            if (Constants.DEBUG) Log.i(TAG, "BypassCharging initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BypassCharging", e);
        }
    }

    private void initializePwm() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing PWM");
        try {
            PwmController.getInstance(this).restorePwmSetting();
            if (Constants.DEBUG) Log.i(TAG, "PWM initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize PWM", e);
        }
    }

    private void initializeTestTe() {
        // Enable the panel test-TE irq so the node reports the real DDIC
        // self-refresh rate, consumed by the SurfaceFlinger "Show refresh rate"
        // overlay via ro.surface_flinger.panel_refresh_rate_node
        try {
            if (FileUtils.isFileWritable(Constants.NODE_TEST_TE)) {
                FileUtils.writeLine(Constants.NODE_TEST_TE, "1");
                if (Constants.DEBUG) Log.i(TAG, "Test-TE counter enabled");
            } else {
                Log.w(TAG, "Test-TE node is not writable");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable test-TE counter", e);
        }
    }

    private void initializeGameBar() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing GameBar");
        try {
            var prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean masterEnabled = prefs.getBoolean("game_bar_enable", false);
            boolean hasAutoApps = ! TextUtils.isEmpty(
                    prefs.getString(Constants.KEY_GAMEBAR_AUTO_APPS, ""));

            if (Constants.DEBUG) Log.i(TAG, "GameBar: master=" + masterEnabled + ", autoApps=" + hasAutoApps);

            if (masterEnabled) {
                GameBar.getInstance(this).applyPreferences();
                GameBar.getInstance(this).show();
            }

            if (masterEnabled || hasAutoApps) {
                GameBarMonitorService.notifyStateChanged(this);
            }

            if (Constants.DEBUG) Log.i(TAG, "GameBar initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize GameBar", e);
        }
    }

    private void initializeRefreshRate() {
        if (Constants.DEBUG) Log.i(TAG, "Initializing RefreshRate");
        try {
            RefreshRateController.getInstance(this);
            RefreshRateMonitorService.notifyStateChanged(this);
            if (Constants.DEBUG) Log.i(TAG, "RefreshRate initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize RefreshRate", e);
        }
    }

    // ===== Receivers =====

    private void registerReceivers() {
        if (mReceiver != null) return;

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                final String action = intent.getAction();
                if (Constants.DEBUG) Log.i(TAG, "Received: " + action);

                switch (action) {
                    case Intent.ACTION_SCREEN_OFF:
                        handleScreenOff();
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        handleScreenOn();
                        break;
                    case DisplayModeController.ACTION_DISPLAY_MODE_CHANGED:
                        handleDisplayModeChanged();
                        break;
                    case Intent.ACTION_POWER_CONNECTED:
                        handlePowerConnected();
                        break;
                    case Intent.ACTION_POWER_DISCONNECTED:
                        handlePowerDisconnected();
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(DisplayModeController.ACTION_DISPLAY_MODE_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

        try {
            registerReceiver(mReceiver, filter);
            if (Constants.DEBUG) Log.i(TAG, "Receivers registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receivers", e);
            mReceiver = null;
        }
    }

    private void unregisterReceivers() {
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
            mReceiver = null;
        }
    }

    // ===== Screen Event Handlers =====

    private void handleScreenOff() {
        if (Constants.DEBUG) Log.i(TAG, "Screen OFF");

        // Disable HBM (business rule #2)
        try {
            HbmController hbmController = HbmController.getInstance(this);
            if (hbmController.isHbmEnabled()) {
                hbmController.disableHbm();
                DisplayModeController.getInstance(this).broadcastStateChange();
                if (Constants.DEBUG) Log.i(TAG, "HBM disabled on screen off");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable HBM on screen off", e);
        }

        // Stop GameBar
        try {
            GameBar.getInstance(this).hide();
            Intent svc = new Intent(this, GameBarMonitorService.class);
            stopService(svc);
            if (Constants.DEBUG) Log.i(TAG, "GameBar stopped on screen off");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop GameBar on screen off", e);
        }
    }

    private void handleScreenOn() {
        if (Constants.DEBUG) Log.i(TAG, "Screen ON");

        // The kernel ADFR status_reset() re-arms sa_min_fps=1 on every panel
        // enable/timing switch, which would silently re-enable LTPO after a
        // screen-off/on. Re-apply the persisted refresh-rate state (including
        // the LTPO master switch) so the user's choice survives.
        try {
            RefreshRateMonitorService.notifyStateChanged(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to re-apply refresh rate on screen on", e);
        }

        // Restart GameBar if needed
        try {
            var prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean masterEnabled = prefs.getBoolean("game_bar_enable", false);
            boolean hasAutoApps = ! TextUtils.isEmpty(
                    prefs.getString(Constants.KEY_GAMEBAR_AUTO_APPS, ""));

            if (masterEnabled || hasAutoApps) {
                GameBarMonitorService.notifyStateChanged(this);
                if (Constants.DEBUG) Log.i(TAG, "GameBar restarted on screen on");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart GameBar on screen on", e);
        }
    }

    // ===== Display Mode Handlers =====

    private void handleDisplayModeChanged() {
        if (Constants.DEBUG) Log.i(TAG, "Display mode changed");

        // The kernel ADFR status_reset() re-arms sa_min_fps=1 on every timing
        // switch too, which would silently re-enable LTPO when the user has it
        // off and SF auto-switches modes (e.g. 120->60 for video). Re-apply.
        try {
            RefreshRateMonitorService.notifyStateChanged(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to re-apply refresh rate on mode change", e);
        }
    }

    // ===== Power Event Handlers =====

    private void handlePowerConnected() {
        if (Constants.DEBUG) Log.i(TAG, "Power CONNECTED");

        // Notify BypassChargingController
        try {
            BypassChargingController.getInstance(this).handlePowerConnected();
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle power connected", e);
        }
    }

    private void handlePowerDisconnected() {
        if (Constants.DEBUG) Log.i(TAG, "Power DISCONNECTED");

        // Re-enable charging in the kernel: the vote survives replug, so a stale
        // "charging disabled" would silently prevent charging on the next connect
        // if this app were not running then. Global prefs are kept for next connect.
        try {
            BypassChargingController.getInstance(this).handlePowerDisconnected();
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle power disconnected", e);
        }
    }
}