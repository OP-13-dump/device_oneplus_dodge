/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.fastcharge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.lineageos.device.settings.Constants;

/** SystemUI long-press inside the charging ring. Guarded by STATUS_BAR_SERVICE. */
public class FastChargeBoostReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Constants.ACTION_BOOST_CHARGING.equals(intent.getAction())) {
            return;
        }
        FastChargeController.getInstance(context).boostSession();
    }
}
