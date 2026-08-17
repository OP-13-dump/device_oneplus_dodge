/*
 * Copyright (C) 2018-2023 crDroid Android Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Keep;

import com.android.internal.os.DeviceKeyHandler;

import java.util.Arrays;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.SliderControllerBase;
import org.lineageos.device.settings.slider.AppLaunchController;
import org.lineageos.device.settings.slider.BrightnessController;
import org.lineageos.device.settings.slider.FlashlightController;
import org.lineageos.device.settings.slider.NotificationController;
import org.lineageos.device.settings.slider.NotificationRingerController;
import org.lineageos.device.settings.slider.RingerController;
import org.lineageos.device.settings.slider.RotationController;

@Keep
public class KeyHandler implements DeviceKeyHandler {
    private static final String TAG = KeyHandler.class.getSimpleName();

    private final Context mContext;
    private final NotificationController mNotificationController;
    private final FlashlightController mFlashlightController;
    private final BrightnessController mBrightnessController;
    private final RotationController mRotationController;
    private final RingerController mRingerController;
    private final NotificationRingerController mNotificationRingerController;
    private final AppLaunchController mAppLaunchController;

    private SliderControllerBase mSliderController;

    private final InputManager mInputManager;

    private final BroadcastReceiver mSliderUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int usage = intent.getIntExtra(Constants.EXTRA_SLIDER_USAGE, 0);
            int[] actions = intent.getIntArrayExtra(Constants.EXTRA_SLIDER_ACTIONS);

            Log.i(TAG, "update usage " + usage + " with actions " +
                    Arrays.toString(actions));

            SliderControllerBase oldController = mSliderController;
            SliderControllerBase newController;

            switch (usage) {
                case NotificationController.ID:
                    newController = mNotificationController;
                    break;
                case FlashlightController.ID:
                    newController = mFlashlightController;
                    break;
                case BrightnessController.ID:
                    newController = mBrightnessController;
                    break;
                case RotationController.ID:
                    newController = mRotationController;
                    break;
                case RingerController.ID:
                    newController = mRingerController;
                    break;
                case NotificationRingerController.ID:
                    newController = mNotificationRingerController;
                    break;
                case AppLaunchController.ID:
                    mAppLaunchController.updatePackages(
                            intent.getStringArrayExtra(Constants.EXTRA_SLIDER_APPS));
                    newController = mAppLaunchController;
                    break;
                default:
                    Log.w(TAG, "Unknown slider usage: " + usage);
                    return;
            }

            if (oldController != null && oldController != newController) {
                oldController.reset();
            }

            mSliderController = newController;
            mSliderController.update(actions);

            // Don't "restore" the app-launch usage: that would open an app
            // on every settings change and on boot instead of only on a
            // real slider move.
            if (!(mSliderController instanceof AppLaunchController)) {
                mSliderController.restoreState(context, false);
            }
        }
    };

    public KeyHandler(Context context) {
        mContext = context;

        mNotificationController = new NotificationController(mContext);
        mFlashlightController = new FlashlightController(mContext);
        mBrightnessController = new BrightnessController(mContext);
        mRotationController = new RotationController(mContext);
        mRingerController = new RingerController(mContext);
        mNotificationRingerController = new NotificationRingerController(mContext);
        mAppLaunchController = new AppLaunchController(mContext);

        mContext.registerReceiver(mSliderUpdateReceiver,
                new IntentFilter(Constants.ACTION_UPDATE_SLIDER_SETTINGS),
                Context.RECEIVER_EXPORTED);

        mInputManager = mContext.getSystemService(InputManager.class);

        // Prefs live in the DeviceSettings package, not system_server's context.
        try {
            Context packageContext = mContext.createPackageContext(
                    Constants.class.getPackage().getName(), 0);
            DeviceSettings.restoreSliderStates(packageContext);
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore slider states", e);
        }
    }

    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return event;
        }

        if (!mInputManager.getInputDevice(event.getDeviceId()).getName().equals("oplus,hall_tri_state_key")) {
            return event;
        }

        if (mSliderController == null) {
            Log.w(TAG, "Slider controller not initialized yet");
            return null;
        }

        mSliderController.processEvent(mContext);

        return null;
    }
}
