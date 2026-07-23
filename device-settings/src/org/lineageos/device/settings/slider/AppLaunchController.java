/*
 * Copyright (C) 2026 AlphaDroid
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

package org.lineageos.device.settings.slider;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.SliderControllerBase;
import org.lineageos.device.settings.utils.FileUtils;

public final class AppLaunchController extends SliderControllerBase {

    public static final int ID = 7;

    private static final String TAG = "AppLaunchController";

    private static final int APP_LAUNCH = 70;

    private final PowerManager mPowerManager;

    // Per-position package names (top, middle, bottom), delivered via
    // EXTRA_SLIDER_APPS since this runs in system_server and cannot read
    // the settings app's SharedPreferences.
    private String[] mPackages = new String[3];

    public AppLaunchController(Context context) {
        super(context);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    public void updatePackages(String[] packages) {
        if (packages != null && packages.length == 3) {
            mPackages = packages;
        }
    }

    @Override
    protected int processAction(int action) {
        Log.i(TAG, "slider action: " + action);
        if (action != APP_LAUNCH) {
            return 0;
        }

        int state;
        try {
            state = Integer.parseInt(FileUtils.readLine(Constants.NODE_SLIDER_STATE).trim());
        } catch (Exception e) {
            Log.e(TAG, "Failed to read slider state", e);
            return 0;
        }
        if (state < 1 || state > 3) {
            return 0;
        }

        String pkg = mPackages[state - 1];
        if (TextUtils.isEmpty(pkg)) {
            return 0;
        }

        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.w(TAG, "No launch intent for " + pkg);
            return 0;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        try {
            if (!mPowerManager.isInteractive()) {
                mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_GESTURE, TAG + ":slider");
            }
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            Log.i(TAG, "Launched " + pkg);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + pkg, e);
        }

        // Return 0: the app opening is the feedback, don't trigger the
        // SystemUI tri-state dialog (it has no icon for this mode).
        return 0;
    }

    @Override
    public void reset() {
    }
}
