/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Background service that monitors and applies refresh rate changes.
 * Runs only when app override list is not empty.
 * Uses Settings.System MIN/PEAK_REFRESH_RATE for framework integration.
 */

package org.lineageos.device.settings.refreshrate;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.display.HbmController;
import org.lineageos.device.settings.utils.FileUtils;
import org.lineageos.device.settings.utils.ForegroundAppDetector;

public class RefreshRateMonitorService extends Service {

    private static final String TAG = "RefreshRateMonitorService";

    private static volatile RefreshRateMonitorService sInstance;

    private RefreshRateController mRefreshRateController;
    private Handler mHandler;
    private ForegroundAppDetector mForegroundDetector;
    private boolean mAppMonitoringActive = false;

    // "auto" = full dynamic range: let SurfaceFlinger range across 60/90/120 by
    // content while the kernel ADFR self-refresh drops the DDIC to 20/1 beneath.
    // Panel max is 120 on dodge (1440p and 1080p timings). Defining auto explicitly (instead of restoring the
    // user's Android "Smooth display" baseline, which is often 60) is what keeps
    // auto from welding the ceiling to 60Hz.
    private static final float AUTO_MIN_REFRESH_RATE = 60f;
    private static final float AUTO_PEAK_REFRESH_RATE = 120f;

    // ===== Lifecycle =====

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mRefreshRateController = RefreshRateController.getInstance(this);
        mHandler = new Handler();
        mForegroundDetector = ForegroundAppDetector.getInstance(this);
        if (Constants.DEBUG) Log.i(TAG, "Service created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAppMonitoring();
        // Leave the last applied refresh-rate state in place: START_STICKY restarts
        // re-apply it from the controller, and nothing here is ever "stuck low"
        // (fixed pins the mode rate; auto is already the full dynamic range).
        sInstance = null;
        if (Constants.DEBUG) Log.i(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mHandler.post(this::handleStateChanged);
        return START_STICKY;
    }

    // ===== Public API =====

    public static void notifyStateChanged(Context context) {
        RefreshRateMonitorService instance = sInstance;
        if (instance == null) {
            // Start service
            Intent serviceIntent = new Intent(context, RefreshRateMonitorService.class);
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
        RefreshRateController.RefreshRateState state = mRefreshRateController.getState();

        if (Constants.DEBUG) Log.i(TAG, "State: " + state.toString());

        // Determine if we need to monitor
        boolean hasAppOverrides = mRefreshRateController.hasAppOverrides();

        if (!hasAppOverrides) {
            if (Constants.DEBUG) Log.i(TAG, "No app overrides - stopping app monitoring");
            stopAppMonitoring();
            // Still apply global rate even without app overrides
            applyRefreshRate(state.globalRefreshRate);
            return;
        }

        // App override list exists - start monitoring
        if (!mAppMonitoringActive) {
            if (Constants.DEBUG) Log.i(TAG, "Starting app monitoring");
            startAppMonitoring();
            // startAppMonitoring delivers an initial report for the current app
        } else {
            // Monitoring was already active, so no app-change report is coming:
            // apply the (possibly changed) effective rate for the current app now,
            // otherwise a tile/settings change only takes effect on the next app switch
            String pkg = mForegroundDetector.getCurrentForegroundApp();
            int targetFps = mRefreshRateController.getEffectiveRefreshRate(pkg);
            if (Constants.DEBUG) Log.i(TAG, "Applying effective rate " + targetFps
                    + " for current app " + pkg);
            applyRefreshRate(targetFps);
        }
    }

    // ===== App monitoring =====

    private void startAppMonitoring() {
        if (mAppMonitoringActive) {
            if (Constants.DEBUG) Log.w(TAG, "App monitoring already active");
            return;
        }

        mAppMonitoringActive = true;

        mForegroundDetector.startMonitoring("RefreshRate", packageName -> {
            if (Constants.DEBUG) Log.i(TAG, "Foreground app: " + packageName);

            int targetFps = mRefreshRateController.getEffectiveRefreshRate(packageName);

            if (Constants.DEBUG) {
                String desc = targetFps == 0 ? "auto" : targetFps + " fps";
                Log.i(TAG, "Applying refresh rate: " + desc + " for " + packageName);
            }
            applyRefreshRate(targetFps);
        });

        if (Constants.DEBUG) Log.i(TAG, "App monitoring listener registered");
    }

    private void stopAppMonitoring() {
        if (!mAppMonitoringActive) {
            return;
        }

        mAppMonitoringActive = false;
        mForegroundDetector.stopMonitoring("RefreshRate");
        if (Constants.DEBUG) Log.i(TAG, "App monitoring stopped");
    }

    // ===== Refresh rate application via Settings =====

    private void applyRefreshRate(int fps) {
        // HBM pins the refresh rate to 120Hz to avoid timing-switch flashes; don't
        // fight that pin - overrides re-apply on the next app change after HBM ends
        if (HbmController.getInstance(this).isHbmEnabled()) {
            if (Constants.DEBUG) Log.i(TAG, "HBM active, skipping refresh rate change");
            return;
        }

        // LTPO master switch (see KEY_LTPO_ENABLED): when enabled the panel
        // self-refresh floor is dynamic (write 0 -> kernel maps to 1: 20Hz active
        // floor, 1Hz idle); when disabled the panel is pinned to the panel max
        // (120) so it never drops below the mode rate - the kernel clamps 120
        // into whichever timing's mapping table is active, giving a fixed display.
        boolean ltpo = mRefreshRateController.isLtpoEnabled();
        int minFps = ltpo ? 0 : 120;
        FileUtils.writeLine(Constants.NODE_ADFR_MIN_FPS, String.valueOf(minFps));

        // fps == 0 (auto): open SF to the full 60..120 range so it can pick the mode
        // by content. fps > 0 (fixed): lock SF to that single mode (MIN == PEAK).
        final float minRate = (fps == 0) ? AUTO_MIN_REFRESH_RATE : (float) fps;
        final float peakRate = (fps == 0) ? AUTO_PEAK_REFRESH_RATE : (float) fps;

        if (Constants.DEBUG) Log.i(TAG, "Applying MIN/PEAK refresh rate: "
                + minRate + "/" + peakRate + " Hz (fps=" + fps + ")");

        Settings.System.putFloatForUser(
                getContentResolver(),
                Settings.System.MIN_REFRESH_RATE,
                minRate,
                UserHandle.USER_CURRENT
        );

        Settings.System.putFloatForUser(
                getContentResolver(),
                Settings.System.PEAK_REFRESH_RATE,
                peakRate,
                UserHandle.USER_CURRENT
        );
    }
}
