/*
 * Copyright (C) 2016-2023 crDroid Android Project
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.device.settings.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.SliderPreference;

import org.lineageos.device.settings.R;

/**
 * A Material 3 Expressive slider row: title with the current value on the
 * right, summary, and the SettingsLib expressive slider flanked by -/+ steppers.
 * A reset button appears next to the value once it differs from the default.
 */
public class CustomSeekBarPreference extends SliderPreference {
    private static final String ANDROIDNS = "http://schemas.android.com/apk/res/android";

    private final boolean mShowSign;
    private final String mUnits;
    private final String mDefaultValueText;

    private boolean mDefaultValueExists;
    private int mDefaultValue;

    private TextView mValueView;
    private View mResetView;

    public CustomSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.custom_seekbar_preference);

        int interval = 1;
        boolean continuousUpdates = false;
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CustomSeekBarPreference);
        try {
            mShowSign = a.getBoolean(R.styleable.CustomSeekBarPreference_showSign, false);
            String units = a.getString(R.styleable.CustomSeekBarPreference_units);
            mUnits = units != null ? units : "";
            continuousUpdates = a.getBoolean(
                    R.styleable.CustomSeekBarPreference_continuousUpdates, false);
            mDefaultValueText = a.getString(R.styleable.CustomSeekBarPreference_defaultValueText);
            interval = Math.max(1, a.getInt(R.styleable.CustomSeekBarPreference_interval, 1));
        } finally {
            a.recycle();
        }

        if (attrs != null) {
            String defaultValue = attrs.getAttributeValue(ANDROIDNS, "defaultValue");
            if (defaultValue != null && !defaultValue.isEmpty()) {
                try {
                    mDefaultValue = Integer.parseInt(defaultValue);
                    mDefaultValueExists = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        setSliderIncrement(interval);
        setUpdatesContinuously(continuousUpdates);
        setHapticFeedbackMode(HAPTIC_FEEDBACK_MODE_ON_ENDS);
        setExtraChangeListener((slider, value, fromUser) -> updateValueViews((int) value));
    }

    public CustomSeekBarPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        mValueView = (TextView) holder.findViewById(R.id.value);
        mResetView = holder.findViewById(R.id.reset);
        mResetView.setOnClickListener(v -> changeValue(mDefaultValue));

        // SliderPreference's own steppers skip the change listener; route
        // them through it so callers see every change.
        bindStepper(holder, R.id.icon_start_frame, R.id.icon_start,
                R.drawable.ic_custom_seekbar_minus, -getSliderIncrement(),
                getValue() > getMin());
        bindStepper(holder, R.id.icon_end_frame, R.id.icon_end,
                R.drawable.ic_custom_seekbar_plus, getSliderIncrement(),
                getValue() < getMax());

        updateValueViews(getValue());
    }

    private void bindStepper(PreferenceViewHolder holder, int frameId, int iconId,
            int iconRes, int delta, boolean canStep) {
        View frame = holder.findViewById(frameId);
        ImageView icon = (ImageView) holder.findViewById(iconId);
        if (frame == null || icon == null) {
            return;
        }
        icon.setImageResource(iconRes);
        frame.setVisibility(View.VISIBLE);
        frame.setOnClickListener(v -> changeValue(getValue() + delta));
        boolean enabled = isEnabled() && canStep;
        frame.setEnabled(enabled);
        icon.setEnabled(enabled);
        icon.setAlpha(enabled ? 1f : 0.38f);
    }

    /** Applies a value the way a drag would: through the change listener. */
    private void changeValue(int value) {
        value = Math.max(getMin(), Math.min(getMax(), value));
        if (value != getValue() && callChangeListener(value)) {
            setValue(value);
        }
    }

    private void updateValueViews(int value) {
        if (mValueView != null) {
            mValueView.setText(getTextValue(value));
        }
        if (mResetView != null) {
            mResetView.setVisibility(mDefaultValueExists && value != mDefaultValue
                    ? View.VISIBLE : View.GONE);
        }
    }

    private String getTextValue(int value) {
        if (mDefaultValueText != null && mDefaultValueExists && value == mDefaultValue) {
            return mDefaultValueText;
        }
        return (mShowSign && value > 0 ? "+" : "") + value + mUnits;
    }

    public void setDefaultValue(int value, boolean update) {
        mDefaultValue = value;
        mDefaultValueExists = true;
        if (update) {
            notifyChanged();
        }
    }

    public void refresh(int value) {
        setValue(value);
    }
}
