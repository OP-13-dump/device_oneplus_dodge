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

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;

public class GameBarController {

    private static final String TAG = "GameBarController";

    private final Context mContext;
    private final Object mLock = new Object();

    private static GameBarController sInstance;

    public static final class GameBarState {
        public final boolean masterEnabled;
        public final boolean hasAutoApps;

        public GameBarState(boolean masterEnabled, boolean hasAutoApps) {
            this.masterEnabled = masterEnabled;
            this.hasAutoApps = hasAutoApps;
        }

        @Override
        public String toString() {
            return "GameBarState{" +
                    "master=" + masterEnabled +
                    ", autoApps=" + hasAutoApps +
                    "}";
        }
    }

    public static synchronized GameBarController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GameBarController(context.getApplicationContext());
        }
        return sInstance;
    }

    private GameBarController(Context context) {
        mContext = context.getApplicationContext();
    }

    // ===== UI trigger: enable master switch =====

    public void enableGameBar() {
        synchronized (mLock) {
            saveMasterEnabled(true);
            if (Constants.DEBUG) Log.i(TAG, "GameBar enabled");
            notifyStateChanged();
        }
    }

    // ===== UI trigger: disable master switch =====

    public void disableGameBar() {
        synchronized (mLock) {
            saveMasterEnabled(false);
            if (Constants.DEBUG) Log.i(TAG, "GameBar disabled");
            notifyStateChanged();
        }
    }

    // ===== State snapshot =====

    public GameBarState getState() {
        synchronized (mLock) {
            return new GameBarState(
                    isMasterEnabled(),
                    hasAutoAppsInPrefs()
            );
        }
    }

    // ===== Preferences =====

    public boolean isMasterEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean("game_bar_enable", false);
    }

    private void saveMasterEnabled(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .putBoolean("game_bar_enable", enabled)
                .apply();
    }

    public boolean hasAutoAppsInPrefs() {
        String raw = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(Constants.KEY_GAMEBAR_AUTO_APPS, "");
        return !TextUtils.isEmpty(raw);
    }

    // ===== Notification =====

    private void notifyStateChanged() {
        GameBarState state = getState();
        GameBarMonitorService.notifyStateChanged(mContext);
    }
}
