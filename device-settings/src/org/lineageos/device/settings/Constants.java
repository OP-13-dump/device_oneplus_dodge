/*
 * Copyright (C) 2018-2024 crDroid Android Project
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

import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.provider.Settings;
import androidx.preference.PreferenceManager;

import android.media.AudioManager;

public class Constants {

    /* Debug flag */
    public static final boolean DEBUG = true;

    // Device name prop
    public static final String PROP_DEVICE_CODENAME = "ro.alpha.device";

    /* Alert Slider */
    public static final String NODE_SLIDER_STATE = "/proc/tristatekey/tri_state";

    public static final String KEY_NOTIF_SLIDER_PANEL = "notification_slider";
    public static final String KEY_NOTIF_SLIDER_USAGE = "slider_usage";
    public static final String KEY_NOTIF_SLIDER_ACTION_TOP = "action_top_position";
    public static final String KEY_NOTIF_SLIDER_ACTION_MIDDLE = "action_middle_position";
    public static final String KEY_NOTIF_SLIDER_ACTION_BOTTOM = "action_bottom_position";

    /* Per-position apps for the app-launch slider usage */
    public static final String KEY_NOTIF_SLIDER_APP_TOP = "slider_app_top_position";
    public static final String KEY_NOTIF_SLIDER_APP_MIDDLE = "slider_app_middle_position";
    public static final String KEY_NOTIF_SLIDER_APP_BOTTOM = "slider_app_bottom_position";

    public static final String EXTRA_SLIDER_USAGE = "usage";
    public static final String EXTRA_SLIDER_ACTIONS = "actions";
    public static final String EXTRA_SLIDER_APPS = "slider_apps";

    public static final String NOTIF_SLIDER_FOR_NOTIFICATION = "1";
    public static final String NOTIF_SLIDER_FOR_FLASHLIGHT = "2";
    public static final String NOTIF_SLIDER_FOR_BRIGHTNESS = "3";
    public static final String NOTIF_SLIDER_FOR_ROTATION = "4";
    public static final String NOTIF_SLIDER_FOR_RINGER = "5";
    public static final String NOTIF_SLIDER_FOR_NOTIFICATION_RINGER = "6";
    public static final String NOTIF_SLIDER_FOR_APPLAUNCH = "7";

    public static final String ACTION_UPDATE_SLIDER_POSITION
            = "org.lineageos.device.settings.UPDATE_SLIDER_POSITION";
    public static final String ACTION_UPDATE_SLIDER_SETTINGS
            = "org.lineageos.device.settings.UPDATE_SLIDER_SETTINGS";
    public static final String EXTRA_SLIDER_POSITION = "position";
    public static final String EXTRA_SLIDER_POSITION_VALUE = "position_value";
    public static final String EXTRA_SLIDER_PACKAGE = "package";

    public static final int MODE_TOTAL_SILENCE = 600;
    public static final int MODE_ALARMS_ONLY = 601;
    public static final int MODE_PRIORITY_ONLY = 602;
    public static final int MODE_NONE = 603;
    public static final int MODE_VIBRATE = 604;
    public static final int MODE_RING = 605;
    public static final int MODE_SILENT = 620;
    public static final int MODE_FLASHLIGHT_ON = 621;
    public static final int MODE_FLASHLIGHT_OFF = 622;
    public static final int MODE_FLASHLIGHT_BLINK = 623;
    public static final int MODE_BRIGHTNESS_BRIGHT = 630;
    public static final int MODE_BRIGHTNESS_DARK = 631;
    public static final int MODE_BRIGHTNESS_AUTO = 632;
    public static final int MODE_ROTATION_AUTO = 640;
    public static final int MODE_ROTATION_0 = 641;
    public static final int MODE_ROTATION_90 = 642;
    public static final int MODE_ROTATION_270 = 643;
    // Must match MODE_APP_LAUNCH in SystemUI TriStateUiControllerImpl
    public static final int MODE_APP_LAUNCH = 650;

    // Holds <preference_key> -> <proc_node> mapping
    public static final Map<String, String> sBooleanNodePreferenceMap = new HashMap<>();
    public static final Map<String, String> sStringNodePreferenceMap = new HashMap<>();

    /* GameBar */
    public static final String KEY_GAMEBAR_AUTO_APPS = "game_bar_auto_apps";

    /* OnePulse PWM */
    public static final String NODE_ONEPULSE_PWM = "/sys/kernel/oplus_display/pwm_onepulse";
    public static final String KEY_ONEPULSE_PWM = "onepulse_pwm";

    /* Bypass Charging */
    public static final String NODE_BYPASS_CHARGING = "/sys/class/oplus_chg/battery/mmi_charging_enable";
    public static final String KEY_BYPASS_CHARGING = "bypass_charging";
    public static final String KEY_BYPASS_CHARGING_TARGET = "bypass_charging_target";
    public static final String KEY_BYPASS_CHARGING_APPS = "bypass_charging_apps";

    public static final int BYPASS_OFF = 0;
    public static final int BYPASS_WAITING = 1;
    public static final int BYPASS_ON = 2;

    public static final int BYPASS_TARGET_MIN = 1;
    public static final int BYPASS_TARGET_MAX = 99;
    public static final int BYPASS_TARGET_DEFAULT = BYPASS_TARGET_MIN;

    /* HBM */
    public static final String NODE_HBM = "/sys/kernel/oplus_display/hbm_max";
    public static final String KEY_HBM = "hbm_max";

    /** Automatic sunlight boost: drives hbm_max from the light sensor with the
     *  stock hbm_lux_table thresholds (enter 40000 lux, exit 20000 lux). */
    public static final String KEY_SUNLIGHT_BOOST = "sunlight_boost";

    /** Panel test-TE counter: real DDIC self-refresh rate (LTPO). Write "1" to
     *  enable the irq (done at boot by DeviceSettingsService); reads return the
     *  measured rate, or 0 until two TE pulses have been observed. Consumed by the
     *  SurfaceFlinger "Show refresh rate" overlay via
     *  ro.surface_flinger.panel_refresh_rate_node, not by device-settings. */
    public static final String NODE_TEST_TE = "/sys/kernel/oplus_display/test_te";

    /** CRTC frame-done counter = SurfaceFlinger composition rate (NOT the panel
     *  self-refresh rate). This is what the crDroid FPS Info tile shows
     *  (config_fpsInfoSysNode); GameBar reads the same node for a matching number.
     *  The sm8750 (sun) kernel names the CRTC sysfs device "card0-sde-crtc-0"
     *  (device_create "card%d-sde-crtc-%d"), unlike sm8550/sm8650's "sde-crtc-0". */
    public static final String NODE_MEASURED_FPS = "/sys/class/drm/card0-sde-crtc-0/measured_fps";

    /** ADFR/LTPO min fps request: 0 = auto (panel self-refresh drops to the
     *  timing's lowest table entry: 20Hz active floor, 1Hz idle), N = fixed
     *  (kernel clamps into the current timing's table, so writing the tile
     *  rate pins the DDIC at the mode rate). Applied by the kernel
     *  immediately and re-applied on every panel enable/timing switch. */
    public static final String NODE_ADFR_MIN_FPS = "/sys/kernel/oplus_display/adfr_min_fps";

    /** Refresh rate */
    public static final String KEY_REFRESH_RATE_MODE = "refresh_rate_mode";
    /** SharedPreferences key for per-app refresh rate overrides (pipe-separated "pkg:fps|pkg:fps") */
    public static final String KEY_REFRESH_RATE_APPS = "refresh_rate_apps";
    /** 0 = auto: leave the system refresh rate settings untouched */
    public static final int REFRESH_RATE_DEFAULT = 0;

    /* Haptic profile */
    public static final String KEY_HAPTIC_PROFILE = "haptic_profile";
    public static final String PROP_HAPTIC_PROFILE = "persist.sys.haptic_profile";
    public static final String HAPTIC_PROFILE_DEFAULT = "op13gentle";
}
