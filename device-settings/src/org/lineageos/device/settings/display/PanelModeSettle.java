/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 *
 * Shared settle window for HBM (hbm_max) and one-pulse PWM mode switches.
 * HBM EXIT and PWM DC↔1P rewrites must not stack within a few ms (crazy colors /
 * peak brightness). Proximity-based: any mode change marks a timestamp; every
 * *enter* path waits out the remainder of SETTLE_MS before its first panel TX.
 */
package org.lineageos.device.settings.display;

import android.os.SystemClock;
import android.util.Log;

final class PanelModeSettle {
    private static final String TAG = "PanelModeSettle";

    /**
     * ~2 frames + ADFR kickoff. dmesg showed ~11 ms HBM EXIT → PWM on is too short.
     */
    static final long SETTLE_MS = 150L;

    private static final Object LOCK = new Object();
    private static long sLastModeChangeElapsedMs;

    private PanelModeSettle() {}

    /** Call after any HBM/PWM panel command path finishes (enable or disable). */
    static void mark() {
        synchronized (LOCK) {
            sLastModeChangeElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Block until SETTLE_MS has elapsed since the last mark, if needed.
     * No-op when nothing was marked yet or the window has already passed.
     * Must not run on the main/UI thread for a full SETTLE_MS (tiles post work
     * off-main); short remainder waits are fine anywhere.
     */
    static void awaitIfNeeded(String reason) {
        long remain;
        synchronized (LOCK) {
            if (sLastModeChangeElapsedMs == 0L) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - sLastModeChangeElapsedMs;
            remain = SETTLE_MS - elapsed;
        }
        if (remain <= 0L) {
            return;
        }
        try {
            Log.i(TAG, "settle " + remain + "ms remaining (" + reason + ")");
            Thread.sleep(remain);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "settle interrupted (" + reason + ")");
        }
    }
}
