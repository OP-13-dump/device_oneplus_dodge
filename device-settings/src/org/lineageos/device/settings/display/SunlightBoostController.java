/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Automatic sunlight brightness boost, mirroring stock OOS behavior: the stock
 * hbm_lux_table for this panel enters its HBM brightness bands at 40000 lux and
 * exits at 20000 lux. The backlight path tops out at ~798 nits (level 4094), so
 * the boost drives the oplus hbm_max interface (DSI_CMD_HBM_MAX = DBV 4333,
 * ~1118 nits full-white on AA569) through HbmController, which also pins the
 * refresh rate and parks auto-brightness while the panel is latched.
 */

package org.lineageos.device.settings.display;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.utils.FileUtils;

public class SunlightBoostController {

    private static final String TAG = "SunlightBoostController";

    /** Stock hbm_lux_table id 31 first band: enter 40000 lux, exit 20000 lux */
    private static final float ENTER_LUX = 40000f;
    private static final float EXIT_LUX = 20000f;
    /** Sustained-condition debounce so a camera flash or a shadow doesn't flap the panel */
    private static final long ENTER_DEBOUNCE_MS = 2000;
    private static final long EXIT_DEBOUNCE_MS = 5000;

    private static final String KEY_AUTO_ENGAGED = "sunlight_boost_auto_engaged";

    private static SunlightBoostController sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final PowerManager mPowerManager;

    private boolean mListening = false;
    private boolean mAutoEngaged = false;
    private long mEnterSince = 0;
    private long mExitSince = 0;
    private boolean mReceiverRegistered = false;

    public static synchronized SunlightBoostController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SunlightBoostController(context.getApplicationContext());
        }
        return sInstance;
    }

    private SunlightBoostController(Context context) {
        mContext = context;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        mSensorManager = context.getSystemService(SensorManager.class);
        mLightSensor = mSensorManager != null
                ? mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            evaluate(event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // The panel resets on power cycle, so a latched boost must not
                // leave stale settings backups behind
                if (mAutoEngaged) {
                    disengage("screen off");
                }
                stopListening();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                updateState();
            }
        }
    };

    /** Called once from DeviceSettingsService at boot / service start */
    public void init() {
        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenReceiver, filter);
            mReceiverRegistered = true;
        }

        // Reconcile a boost left latched by a crash or service restart: if the
        // node no longer reads enabled the user (or a reboot) already cleared it
        mAutoEngaged = mPrefs.getBoolean(KEY_AUTO_ENGAGED, false);
        if (mAutoEngaged && !isHbmNodeOn()) {
            mAutoEngaged = false;
            mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, false).apply();
        }

        updateState();
        if (Constants.DEBUG) Log.i(TAG, "Initialized, autoEngaged=" + mAutoEngaged);
    }

    /** Re-evaluate after the preference toggles or the screen comes on */
    public void updateState() {
        boolean featureEnabled = mPrefs.getBoolean(Constants.KEY_SUNLIGHT_BOOST, true);

        if (!featureEnabled) {
            if (mAutoEngaged) {
                disengage("feature disabled");
            }
            stopListening();
            return;
        }

        boolean screenOn = mPowerManager == null || mPowerManager.isInteractive();
        if (screenOn && FileUtils.isFileWritable(Constants.NODE_HBM)) {
            startListening();
        } else {
            stopListening();
        }
    }

    private void startListening() {
        if (mListening || mLightSensor == null) {
            return;
        }
        // The ALS is already powered for auto-brightness; batching keeps this a
        // passenger on the existing sensor traffic
        mSensorManager.registerListener(mLightListener, mLightSensor,
                SensorManager.SENSOR_DELAY_NORMAL, 2_000_000 /* maxReportLatencyUs */);
        mListening = true;
        mEnterSince = 0;
        mExitSince = 0;
        if (Constants.DEBUG) Log.i(TAG, "Light sensor listening");
    }

    private void stopListening() {
        if (!mListening) {
            return;
        }
        mSensorManager.unregisterListener(mLightListener);
        mListening = false;
        mEnterSince = 0;
        mExitSince = 0;
        if (Constants.DEBUG) Log.i(TAG, "Light sensor stopped");
    }

    private void evaluate(float lux) {
        final long now = SystemClock.elapsedRealtime();

        if (mAutoEngaged) {
            if (!isHbmNodeOn()) {
                // The user turned HBM off underneath us (tile/settings): they win
                mAutoEngaged = false;
                mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, false).apply();
                mExitSince = 0;
                return;
            }
            if (lux <= EXIT_LUX) {
                if (mExitSince == 0) {
                    mExitSince = now;
                } else if (now - mExitSince >= EXIT_DEBOUNCE_MS) {
                    disengage("lux " + lux + " below exit threshold");
                }
            } else {
                mExitSince = 0;
            }
            return;
        }

        if (lux >= ENTER_LUX) {
            if (mEnterSince == 0) {
                mEnterSince = now;
            } else if (now - mEnterSince >= ENTER_DEBOUNCE_MS) {
                mEnterSince = 0;
                tryEngage(lux);
            }
        } else {
            mEnterSince = 0;
        }
    }

    private void tryEngage(float lux) {
        // Only take over from auto brightness; a manual slider user chose their level
        if (!isAutoBrightnessEnabled()) {
            if (Constants.DEBUG) Log.i(TAG, "Not engaging: auto brightness off");
            return;
        }
        // Never stack on top of a manually enabled HBM (we would restore over
        // the user's own backups on exit)
        if (isHbmNodeOn()) {
            if (Constants.DEBUG) Log.i(TAG, "Not engaging: HBM already on");
            return;
        }

        // enableHbm() refuses while PWM one-pulse is active
        if (DisplayModeController.getInstance(mContext).enableHbm()) {
            mAutoEngaged = true;
            mExitSince = 0;
            mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, true).apply();
            Log.i(TAG, "Sunlight boost engaged at " + lux + " lux");
        }
    }

    private void disengage(String reason) {
        DisplayModeController.getInstance(mContext).disableHbm();
        mAutoEngaged = false;
        mExitSince = 0;
        mPrefs.edit().putBoolean(KEY_AUTO_ENGAGED, false).apply();
        Log.i(TAG, "Sunlight boost disengaged: " + reason);
    }

    private boolean isHbmNodeOn() {
        String value = FileUtils.readLineTrimmed(Constants.NODE_HBM);
        return "1".equals(value);
    }

    private boolean isAutoBrightnessEnabled() {
        try {
            int mode = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT);
            return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            return false;
        }
    }
}
