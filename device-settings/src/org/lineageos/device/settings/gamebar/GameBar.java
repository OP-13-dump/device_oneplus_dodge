/*
 * Copyright (C) 2025 kenway214
 * Copyright (C) 2025 AlphaDroid
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
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;
import org.lineageos.device.settings.utils.ForegroundAppDetector;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * GameBar overlay — single-file, cleaned up.
 * Key changes compared to previous iteration:
 *  - Side-by-side mode no longer imposes internal width packing/ellipsis logic.
 *    When in side_by_side mode the overlay is allowed to occupy the full
 *    available screen width (MATCH_PARENT) and all enabled groups are shown
 *    concatenated. The overlay reacts to orientation changes and updates the
 *    view accordingly.
 *  - Orientation change detection forces a re-layout of the overlay and
 *    recalculates measurements.
 */
public class GameBar {

    private static GameBar sInstance;
    public static synchronized GameBar getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GameBar(context.getApplicationContext());
        }
        return sInstance;
    }

    // sysfs paths
    private static final String BATTERY_TEMP_PATH = "/sys/class/power_supply/battery/temp";

    // position prefs
    private static final String PREF_KEY_DRAGGED_X = "game_bar_dragged_x";
    private static final String PREF_KEY_DRAGGED_Y = "game_bar_dragged_y";

    // layout dims (dp)
    private static final int DRAG_BOUNDARY_MARGIN_DP = 8;

    private final Context mContext;
    private final WeakReference<Context> mContextRef;
    private final WindowManager mWindowManager;
    private final Handler mHandler;

    private View mOverlayView;
    private LinearLayout mRootLayout;
    private WindowManager.LayoutParams mLayoutParams;
    private boolean mIsShowing = false;

    // Layout references
    private View mStackedLayout;
    private View mSbsLayout;

    // Side-by-side view
    private TextView mSbsTextView;

    // Stacked mode view references
    private View mFpsRow;
    private TextView mFpsValue;

    private View mCpuUsageRow;
    private TextView mCpuUsageValue;

    private LinearLayout mCpuFreqContainer;
    private TextView[] mCpuFreqValues = new TextView[8]; // OnePlus 12R has 8 cores

    private View mCpuTempRow;
    private TextView mCpuTempValue;

    private View mGpuUsageRow;
    private TextView mGpuUsageValue;

    private View mGpuClockRow;
    private TextView mGpuClockValue;

    private View mGpuTempRow;
    private TextView mGpuTempValue;

    private View mRamRow;
    private TextView mRamValue;

    private View mBatteryTempRow;
    private TextView mBatteryTempValue;

    // customization state
    private int mTextSizeSp       = 16;
    private int mBackgroundAlpha  = 128;
    private int mCornerRadius     = 16;
    private int mPaddingDp        = 12;
    private String mTitleColorHex = "#FFFFFF";
    private String mValueColorHex = "#4CAF50";
    private String mPosition      = "top_left";
    private String mSplitMode     = "stacked"; // "stacked" | "side_by_side"
    private int mUpdateIntervalMs = 1000;
    private boolean mDraggable    = false;
    private int mItemSpacingDp    = 8;

    // feature toggles
    private boolean mShowBatteryTemp = false;
    private boolean mShowCpuUsage    = false;
    private boolean mShowCpuClock    = false;
    private boolean mShowCpuTemp     = false;
    private boolean mShowRam         = false;
    private boolean mShowFps         = false;
    private boolean mShowGpuUsage    = false;
    private boolean mShowGpuClock    = false;
    private boolean mShowGpuTemp     = false;

    // gestures
    private boolean mLongPressEnabled      = false;
    private long mLongPressThresholdMs     = 1000;
    private boolean mDoubleTapCaptureEnabled = false;
    private boolean mSingleTapToggleEnabled  = false;

    private GestureDetector mGestureDetector;

    // screen + drag
    private int mScreenWidth = 0;
    private int mScreenHeight = 0;
    private int mLastScreenWidth = 0;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    private int mLastAppliedSbsWidth = -1;

    private TextView mCpuSectionHeader;
    private TextView mGpuSectionHeader;
    private View mSeparatorRamCpu;
    private View mSeparatorCpuGpu;

    // updater
    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            // Guard against stale context and ensure overlay is still showing
            Context ctx = mContextRef.get();
            if (!mIsShowing || ctx == null || mOverlayView == null) {
                // Stop updating if context is gone or overlay hidden
                mHandler.removeCallbacksAndMessages(null);
                return;
            }

            checkOrientationChange();
            updateStats();
            mHandler.postDelayed(this, mUpdateIntervalMs);
        }
    };

    private GameBar(Context context) {
        mContext = context;
        mContextRef = new WeakReference<>(context.getApplicationContext());
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());

        mGestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mDoubleTapCaptureEnabled) {
                    if (GameDataExport.getInstance().isCapturing()) {
                        GameDataExport.getInstance().stopCapture();
                    } else {
                        GameDataExport.getInstance().startCapture();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mSingleTapToggleEnabled) {
                    Context ctx = mContextRef.get();
                    if (ctx == null) return false;

                    // Toggle persisted split mode (fragment listener updates UI)
                    String newMode = "stacked".equals(mSplitMode) ? "side_by_side" : "stacked";
                    PreferenceManager.getDefaultSharedPreferences(ctx)
                            .edit()
                            .putString("game_bar_split_mode", newMode)
                            .apply();
                    updateSplitMode(newMode);
                    updateStats();
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mLongPressEnabled) {
                    openOverlaySettings();
                }
            }
        });
        mGestureDetector.setIsLongpressEnabled(true);
    }

    // ===== lifecycle =====

    public void applyPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        // features
        mShowFps         = prefs.getBoolean("game_bar_fps_enable", false);
        mShowBatteryTemp = prefs.getBoolean("game_bar_temp_enable", false);
        mShowCpuUsage    = prefs.getBoolean("game_bar_cpu_usage_enable", false);
        mShowCpuClock    = prefs.getBoolean("game_bar_cpu_clock_enable", false);
        mShowCpuTemp     = prefs.getBoolean("game_bar_cpu_temp_enable", false);
        mShowRam         = prefs.getBoolean("game_bar_ram_enable", false);
        mShowGpuUsage    = prefs.getBoolean("game_bar_gpu_usage_enable", false);
        mShowGpuClock    = prefs.getBoolean("game_bar_gpu_clock_enable", false);
        mShowGpuTemp     = prefs.getBoolean("game_bar_gpu_temp_enable", false);

        // gestures
        mDoubleTapCaptureEnabled = prefs.getBoolean("game_bar_doubletap_capture", false);
        mSingleTapToggleEnabled  = prefs.getBoolean("game_bar_single_tap_toggle", false);
        mLongPressEnabled        = prefs.getBoolean("game_bar_longpress_enable", false);
        try {
            mLongPressThresholdMs = Long.parseLong(prefs.getString("game_bar_longpress_timeout", "1000"));
        } catch (NumberFormatException ignored) {}

        // look & feel
        updateSplitMode(prefs.getString("game_bar_split_mode", "stacked"));
        updateTextSize(prefs.getInt("game_bar_text_size", 16));
        updateBackgroundAlpha(prefs.getInt("game_bar_background_alpha", 128));
        updateCornerRadius(prefs.getInt("game_bar_corner_radius", 16));
        updatePadding(prefs.getInt("game_bar_padding", 12));
        updateTitleColor(prefs.getString("game_bar_title_color", "#FFFFFF"));
        updateValueColor(prefs.getString("game_bar_value_color", "#4CAF50"));
        updateUpdateInterval(prefs.getString("game_bar_update_interval", "1000"));
        updatePosition(prefs.getString("game_bar_position", "top_left"));
        updateItemSpacing(prefs.getInt("game_bar_item_spacing", 8));
    }

    public void show() {
        if (mIsShowing) return;

        applyPreferences();

        // screen size
        Point size = new Point();
        mWindowManager.getDefaultDisplay().getSize(size);
        mScreenWidth = size.x;
        mScreenHeight = size.y;
        mLastScreenWidth = mScreenWidth;

        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        // If side_by_side: allow full width (the user requested NO internal width restrictions)
        if ("side_by_side".equals(mSplitMode)) {
            mLayoutParams.width = WindowManager.LayoutParams.MATCH_PARENT;
            // keep height wrap content
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            mLayoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        if ("draggable".equals(mPosition)) {
            mDraggable = true;
            loadSavedPosition(mLayoutParams);
            if (mLayoutParams.x == 0 && mLayoutParams.y == 0) {
                mLayoutParams.gravity = Gravity.TOP | Gravity.START;
                mLayoutParams.x = 0;
                mLayoutParams.y = 100;
            }
        } else {
            mDraggable = false;
            applyPosition(mLayoutParams, mPosition);
        }

        // Inflate appropriate layout based on split mode
        LayoutInflater inflater = LayoutInflater.from(mContext);

        if ("side_by_side".equals(mSplitMode)) {
            mSbsLayout = inflater.inflate(R.layout.game_bar_side_by_side, null);
            mOverlayView = mSbsLayout;
            mRootLayout = mSbsLayout.findViewById(R.id.game_bar_sbs_root);
            mSbsTextView = mSbsLayout.findViewById(R.id.sbs_text);

            // Make the text view use all available width and avoid internal truncation logic.
            mSbsTextView.setSingleLine(true);
            mSbsTextView.setHorizontallyScrolling(true);
            mSbsTextView.setEllipsize(null);
            mSbsTextView.setMaxLines(1);

            // Ensure layout params for the textview are match_parent so it uses full overlay width
            try {
                View lpOwner = mSbsTextView;
                android.view.ViewGroup.LayoutParams tvLp = lpOwner.getLayoutParams();
                if (tvLp != null) {
                    tvLp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                    tvLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    lpOwner.setLayoutParams(tvLp);
                }
            } catch (Exception ignored) {}

        } else {
            mStackedLayout = inflater.inflate(R.layout.game_bar_stacked, null);
            mOverlayView = mStackedLayout;
            mRootLayout = (LinearLayout) mStackedLayout;
            cacheStackedViewReferences();
        }

        // Apply styling
        applyBackgroundStyleToLayout();
        applyPaddingToLayout();
        applyTextStyling();

        // Setup touch handling
        setupTouchListener();

        mWindowManager.addView(mOverlayView, mLayoutParams);
        mIsShowing = true;
        updateStats();
        startUpdates();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            GameBarFpsMeter.getInstance(mContext).start();
        }
    }

    public void hide() {
        if (!mIsShowing) return;

        // Stop all pending callbacks immediately
        mHandler.removeCallbacksAndMessages(null);

        // Remove view from window manager
        if (mOverlayView != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mOverlayView);
            } catch (IllegalArgumentException e) {
                // View was already removed - safe to ignore
                android.util.Log.w("GameBar", "View already removed from window", e);
            }
            mOverlayView = null;
            mRootLayout = null;
            mStackedLayout = null;
            mSbsLayout = null;
            mSbsTextView = null;
        }

        mIsShowing = false;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            GameBarFpsMeter.getInstance(mContext).stop();
        }
    }

    private void cacheStackedViewReferences() {
        // FPS
        mFpsRow = mStackedLayout.findViewById(R.id.fps_row);
        mFpsValue = mStackedLayout.findViewById(R.id.fps_value);

        // Section headers
        mCpuSectionHeader = mStackedLayout.findViewById(R.id.cpu_section_header);
        mGpuSectionHeader = mStackedLayout.findViewById(R.id.gpu_section_header);

        // CPU Usage
        mCpuUsageRow = mStackedLayout.findViewById(R.id.cpu_usage_row);
        mCpuUsageValue = mStackedLayout.findViewById(R.id.cpu_usage_value);

        // CPU Frequencies (8 cores)
        mCpuFreqContainer = mStackedLayout.findViewById(R.id.cpu_freq_container);
        mCpuFreqValues[0] = mStackedLayout.findViewById(R.id.cpu0_value);
        mCpuFreqValues[1] = mStackedLayout.findViewById(R.id.cpu1_value);
        mCpuFreqValues[2] = mStackedLayout.findViewById(R.id.cpu2_value);
        mCpuFreqValues[3] = mStackedLayout.findViewById(R.id.cpu3_value);
        mCpuFreqValues[4] = mStackedLayout.findViewById(R.id.cpu4_value);
        mCpuFreqValues[5] = mStackedLayout.findViewById(R.id.cpu5_value);
        mCpuFreqValues[6] = mStackedLayout.findViewById(R.id.cpu6_value);
        mCpuFreqValues[7] = mStackedLayout.findViewById(R.id.cpu7_value);

        // CPU Temp
        mCpuTempRow = mStackedLayout.findViewById(R.id.cpu_temp_row);
        mCpuTempValue = mStackedLayout.findViewById(R.id.cpu_temp_value);

        // GPU Usage
        mGpuUsageRow = mStackedLayout.findViewById(R.id.gpu_usage_row);
        mGpuUsageValue = mStackedLayout.findViewById(R.id.gpu_usage_value);

        // GPU Clock
        mGpuClockRow = mStackedLayout.findViewById(R.id.gpu_clock_row);
        mGpuClockValue = mStackedLayout.findViewById(R.id.gpu_clock_value);

        // GPU Temp
        mGpuTempRow = mStackedLayout.findViewById(R.id.gpu_temp_row);
        mGpuTempValue = mStackedLayout.findViewById(R.id.gpu_temp_value);

        // RAM
        mRamRow = mStackedLayout.findViewById(R.id.ram_row);
        mRamValue = mStackedLayout.findViewById(R.id.ram_value);

        // Battery Temp
        mBatteryTempRow = mStackedLayout.findViewById(R.id.battery_temp_row);
        mBatteryTempValue = mStackedLayout.findViewById(R.id.battery_temp_value);


        // Separators
        mSeparatorRamCpu = mStackedLayout.findViewById(R.id.sep_ram_cpu);
        mSeparatorCpuGpu = mStackedLayout.findViewById(R.id.sep_cpu_gpu);
    }

    private void setupTouchListener() {
        mOverlayView.setOnTouchListener((v, event) -> {
            if (mGestureDetector.onTouchEvent(event)) {
                return true;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mDraggable) {
                        initialX = mLayoutParams.x;
                        initialY = mLayoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (mDraggable) {
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);

                        mLayoutParams.x = initialX + deltaX;
                        mLayoutParams.y = initialY + deltaY;

                        // Post bounds check to next frame to allow view measurement
                        mHandler.post(() -> {
                            if (mOverlayView != null && mLayoutParams != null) {
                                enforceOverlayBounds();
                                try {
                                    mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
                                } catch (IllegalArgumentException e) {
                                    android.util.Log.w("GameBar", "Failed to update layout during drag", e);
                                }
                            }
                        });
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mDraggable) {
                        // Final bounds enforcement
                        enforceOverlayBounds();

                        // Save position
                        Context ctx = mContextRef.get();
                        if (ctx != null) {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
                            prefs.edit()
                                    .putInt(PREF_KEY_DRAGGED_X, mLayoutParams.x)
                                    .putInt(PREF_KEY_DRAGGED_Y, mLayoutParams.y)
                                    .apply();
                        }

                        // Apply final position
                        try {
                            mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
                        } catch (IllegalArgumentException e) {
                            android.util.Log.w("GameBar", "Failed to update layout on touch release", e);
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    private void enforceOverlayBounds() {
        if (mOverlayView == null || mLayoutParams == null || mWindowManager == null) return;

        int overlayWidth = mOverlayView.getWidth();
        int overlayHeight = mOverlayView.getHeight();

        // If view hasn't been measured yet (width/height == 0), force measurement
        if (overlayWidth == 0 || overlayHeight == 0) {
            mOverlayView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            overlayWidth = Math.max(overlayWidth, mOverlayView.getMeasuredWidth());
            overlayHeight = Math.max(overlayHeight, mOverlayView.getMeasuredHeight());
        }

        // Still zero? Use minimum safe dimensions to prevent clamping to edge
        if (overlayWidth == 0) overlayWidth = dpToPx(mContext, 100); // ~100dp minimum
        if (overlayHeight == 0) overlayHeight = dpToPx(mContext, 50); // ~50dp minimum

        int margin = dpToPx(mContext, DRAG_BOUNDARY_MARGIN_DP);

        // Clamp X coordinate
        int minX = -overlayWidth + margin;
        int maxX = mScreenWidth - margin;
        mLayoutParams.x = Math.max(minX, Math.min(maxX, mLayoutParams.x));

        // Clamp Y coordinate
        int minY = -overlayHeight + margin;
        int maxY = mScreenHeight - margin;
        mLayoutParams.y = Math.max(minY, Math.min(maxY, mLayoutParams.y));
    }

    // ===== orientation =====

    private void checkOrientationChange() {
        Point size = new Point();
        try {
            mWindowManager.getDefaultDisplay().getSize(size);
            if (size.x != mLastScreenWidth) {
                mLastScreenWidth = size.x;
                mScreenWidth = size.x;
                mScreenHeight = size.y;
                if (mIsShowing) {
                    // If we're in side-by-side mode allow the overlay to re-layout using MATCH_PARENT
                    if ("side_by_side".equals(mSplitMode) && mLayoutParams != null) {
                        mLayoutParams.width = WindowManager.LayoutParams.MATCH_PARENT;
                        try {
                            mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
                        } catch (Exception e) {
                            android.util.Log.w("GameBar", "Failed to update layout on orientation change", e);
                        }
                    }
                    updateStats();
                }
            }
        } catch (Exception ignored) { }
    }

    /**
     * Return available width in pixels. For side_by_side we return the full safe display width
     * (no internal packing restriction) — the overlay will be MATCH_PARENT in that mode.
     */
    private int getAvailableWidth() {
        Point size = new Point();
        mWindowManager.getDefaultDisplay().getSize(size);
        int screenWidth = size.x;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                DisplayCutout cutout = mWindowManager.getDefaultDisplay().getCutout();
                if (cutout != null) {
                    screenWidth -= (cutout.getSafeInsetLeft() + cutout.getSafeInsetRight());
                }
            } catch (Exception e) {
                android.util.Log.w("GameBar", "Failed to get display cutout", e);
            }
        }

        // Subtract padding only for stacked mode — side_by_side is free to use the full safe width.
        if (!"side_by_side".equals(mSplitMode)) {
            int paddingPx = dpToPx(mContext, mPaddingDp);
            screenWidth -= (paddingPx * 2);
            screenWidth -= dpToPx(mContext, 8);  // guard
        }
        return screenWidth;
    }

    private TextPaint makeMonoPaint() {
        TextPaint p = new TextPaint();
        p.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, mTextSizeSp, mContext.getResources().getDisplayMetrics()));
        p.setTypeface(Typeface.MONOSPACE);
        p.setAntiAlias(true);
        return p;
    }

    private void updateStats() {
        if (!mIsShowing || mRootLayout == null) return;

        if ("side_by_side".equals(mSplitMode)) {
            updateSideBySideStats();
        } else {
            updateStackedStats();
        }

        // Handle data capture
        if (GameDataExport.getInstance().isCapturing()) {
            captureCurrentData();
        }

        // Update layout if needed
        if (mLayoutParams != null && mOverlayView != null) {
            try {
                mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
            } catch (IllegalArgumentException e) {
                android.util.Log.w("GameBar", "Failed to update layout", e);
            }
        }
    }

    private void updateSideBySideStats() {
        if (mSbsTextView == null) return;

        // Build the single-line string using existing packing/ellipsis logic
        final String finalLine = buildOneLineSbsText();
        if (finalLine == null || finalLine.isEmpty()) {
            mSbsTextView.setText("");
            return;
        }

        // Parse colors with fallbacks
        int valueColor;
        int titleColor;
        try { valueColor = Color.parseColor(mValueColorHex); }
        catch (Exception e) { valueColor = Color.parseColor("#4CAF50"); }
        try { titleColor = Color.parseColor(mTitleColorHex); }
        catch (Exception e) { titleColor = Color.WHITE; }

        // Use value color as the base color for the entire string
        mSbsTextView.setTextColor(valueColor);

        // Build spannable and color each group's label (text before first space) with titleColor
        SpannableStringBuilder ssb = new SpannableStringBuilder(finalLine);
        String[] groups = finalLine.split("\\s\\|\\s");
        int idx = 0;
        for (int gi = 0; gi < groups.length; gi++) {
            String g = groups[gi];
            if (g == null || g.isEmpty()) {
                idx += (gi < groups.length - 1) ? 3 : 0;
                continue;
            }

            int firstSpace = g.indexOf(' ');
            if (firstSpace > 0) {
                int spanStart = idx;
                int spanEnd = idx + firstSpace;
                try {
                    ssb.setSpan(new ForegroundColorSpan(titleColor),
                            spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } catch (Exception ignored) {}
            }

            idx += g.length();
            if (gi < groups.length - 1) idx += 3; // for " | "
        }

        // Set the text to the TextView (so fonts/spans are applied)
        mSbsTextView.setText(ssb);

        try {
            // Use TextPaint identical to rendering
            TextPaint paint = makeMonoPaint();

            // Upper bound for StaticLayout: use getAvailableWidth (accounts for cutout and padding guard)
            int upperBound = getAvailableWidth();
            if (upperBound <= 0) {
                // sane fallback if something is wrong with getAvailableWidth
                upperBound = dpToPx(mContext, 1000);
            }

            // Create StaticLayout (builder for API >= M, fallback to constructor otherwise)
            final android.text.StaticLayout layout;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                layout = android.text.StaticLayout.Builder.obtain(ssb, 0, ssb.length(), paint, upperBound)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                        .build();
            } else {
                layout = new android.text.StaticLayout(ssb, paint, upperBound,
                        android.text.Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
            }

            // Get intrinsic text width of the first line (exact renderer width)
            float textPx = (layout.getLineCount() > 0) ? layout.getLineWidth(0) : paint.measureText(finalLine);
            int textWidthPx = (int) Math.ceil(textPx);

            // Root padding (left + right). If root not present, use configured padding value.
            int paddingLeft = (mRootLayout != null) ? mRootLayout.getPaddingLeft() : dpToPx(mContext, mPaddingDp);
            int paddingRight = (mRootLayout != null) ? mRootLayout.getPaddingRight() : dpToPx(mContext, mPaddingDp);
            int paddingTotal = paddingLeft + paddingRight;

            // 4dp safe margin
            int safeMarginPx = dpToPx(mContext, 4);

            // Desired width = text + padding + safe margin
            int desiredWidth = textWidthPx + paddingTotal + safeMarginPx;

            // Cap to available usable width (can't exceed screen usable width)
            int usable = getAvailableWidth();
            if (usable > 0 && desiredWidth > usable) desiredWidth = usable;

            // Only update when changed (reduces churn and jank)
            if (desiredWidth != mLastAppliedSbsWidth) {
                // Apply width to window layout params
                if (mLayoutParams != null) {
                    mLayoutParams.width = desiredWidth;
                    try {
                        mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
                    } catch (IllegalArgumentException e) {
                        Log.w("GameBar", "Failed to update overlay width", e);
                    } catch (Exception e) {
                        Log.w("GameBar", "Unexpected error updating overlay width", e);
                    }
                }

                // Apply width to TextView (subtract padding so text area matches)
                int textViewTarget = Math.max(0, desiredWidth - paddingTotal);
                android.view.ViewGroup.LayoutParams tvLp = mSbsTextView.getLayoutParams();
                if (tvLp != null) {
                    tvLp.width = textViewTarget;
                    mSbsTextView.setLayoutParams(tvLp);
                } else {
                    mSbsTextView.setWidth(textViewTarget);
                }

                mLastAppliedSbsWidth = desiredWidth;
            }

        } catch (Exception e) {
            Log.w("GameBar", "Side-by-side exact width measurement failed", e);
            // fallback: nothing — keep previous width
        }
    }

    private void updateStackedStats() {
        // Track if any CPU items are visible
        boolean anyCpuVisible = false;

        // Track if any GPU items are visible
        boolean anyGpuVisible = false;

        // FPS
        if (mShowFps && mFpsRow != null) {
            String fps = fpsValue();
            mFpsValue.setText(fps != null ? fps : "N/A");
            mFpsRow.setVisibility(View.VISIBLE);
        } else if (mFpsRow != null) {
            mFpsRow.setVisibility(View.GONE);
        }

        // CPU Usage
        if (mShowCpuUsage && mCpuUsageRow != null) {
            String usage = safe(GameBarCpuInfo.getCpuUsage());
            mCpuUsageValue.setText(usage != null ? usage + "%" : "N/A");
            mCpuUsageRow.setVisibility(View.VISIBLE);
            anyCpuVisible = true;
        } else if (mCpuUsageRow != null) {
            mCpuUsageRow.setVisibility(View.GONE);
        }

        // CPU Frequencies (8 cores)
        if (mShowCpuClock && mCpuFreqContainer != null) {
            List<String> freqs = GameBarCpuInfo.getCpuFrequencies();
            for (int i = 0; i < 8; i++) {
                if (i < freqs.size() && mCpuFreqValues[i] != null) {
                    mCpuFreqValues[i].setText(formatFrequency(freqs.get(i)));
                } else if (mCpuFreqValues[i] != null) {
                    mCpuFreqValues[i].setText("N/A");
                }
            }
            mCpuFreqContainer.setVisibility(View.VISIBLE);
            anyCpuVisible = true;
        } else if (mCpuFreqContainer != null) {
            mCpuFreqContainer.setVisibility(View.GONE);
        }

        // CPU Temp
        if (mShowCpuTemp && mCpuTempRow != null) {
            String temp = safe(GameBarCpuInfo.getCpuTemp());
            mCpuTempValue.setText(temp != null ? temp + " °C" : "N/A");
            mCpuTempRow.setVisibility(View.VISIBLE);
            anyCpuVisible = true;
        } else if (mCpuTempRow != null) {
            mCpuTempRow.setVisibility(View.GONE);
        }

        // CPU Section Header - show only if any CPU item is visible
        if (mCpuSectionHeader != null) {
            mCpuSectionHeader.setVisibility(anyCpuVisible ? View.VISIBLE : View.GONE);
        }

        // GPU Usage
        if (mShowGpuUsage && mGpuUsageRow != null) {
            String usage = safe(GameBarGpuInfo.getGpuUsage());
            mGpuUsageValue.setText(usage != null ? usage + "%" : "N/A");
            mGpuUsageRow.setVisibility(View.VISIBLE);
            anyGpuVisible = true;
        } else if (mGpuUsageRow != null) {
            mGpuUsageRow.setVisibility(View.GONE);
        }

        // GPU Clock
        if (mShowGpuClock && mGpuClockRow != null) {
            String clock = safe(GameBarGpuInfo.getGpuClock());
            mGpuClockValue.setText(clock != null ? clock + " MHz" : "N/A");
            mGpuClockRow.setVisibility(View.VISIBLE);
            anyGpuVisible = true;
        } else if (mGpuClockRow != null) {
            mGpuClockRow.setVisibility(View.GONE);
        }

        // GPU Temp
        if (mShowGpuTemp && mGpuTempRow != null) {
            String temp = safe(GameBarGpuInfo.getGpuTemp());
            mGpuTempValue.setText(temp != null ? temp + " °C" : "N/A");
            mGpuTempRow.setVisibility(View.VISIBLE);
            anyGpuVisible = true;
        } else if (mGpuTempRow != null) {
            mGpuTempRow.setVisibility(View.GONE);
        }

        // GPU Section Header - show only if any GPU item is visible
        if (mGpuSectionHeader != null) {
            mGpuSectionHeader.setVisibility(anyGpuVisible ? View.VISIBLE : View.GONE);
        }

        // RAM
        if (mShowRam && mRamRow != null) {
            String ram = safe(GameBarMemInfo.getRamUsage());
            mRamValue.setText(ram != null ? ram + " MB" : "N/A");
            mRamRow.setVisibility(View.VISIBLE);
        } else if (mRamRow != null) {
            mRamRow.setVisibility(View.GONE);
        }

        // Battery Temp
        if (mShowBatteryTemp && mBatteryTempRow != null) {
            String temp = readBatteryTempValue();
            mBatteryTempValue.setText(temp != null ? temp + " °C" : "N/A");
            mBatteryTempRow.setVisibility(View.VISIBLE);
        } else if (mBatteryTempRow != null) {
            mBatteryTempRow.setVisibility(View.GONE);
        }

        // Upper group (above RAM<->CPU separator): FPS, BAT, RAM
        boolean upperGroupVisible = false;
        if ((mFpsRow != null && mFpsRow.getVisibility() == View.VISIBLE)
                || (mBatteryTempRow != null && mBatteryTempRow.getVisibility() == View.VISIBLE)
                || (mRamRow != null && mRamRow.getVisibility() == View.VISIBLE)) {
            upperGroupVisible = true;
        }

        // Lower group (below CPU<->GPU separator): GPU items (anyGpuVisible)
        boolean lowerGroupVisible = anyGpuVisible;

        // Separator between RAM and CPU: show only if something is above (FPS/BAT/RAM) AND something CPU-related is visible
        if (mSeparatorRamCpu != null) {
            boolean show = upperGroupVisible && anyCpuVisible;
            mSeparatorRamCpu.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        // Separator between CPU and GPU: show only if any CPU item visible AND any GPU item visible
        if (mSeparatorCpuGpu != null) {
            boolean show = anyCpuVisible && anyGpuVisible;
            mSeparatorCpuGpu.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private String formatFrequency(String freqValue) {
        if (freqValue == null) return "N/A";
        String s = freqValue.trim();
        if (s.isEmpty()) return "N/A";

        // Find the last contiguous run of digits in the string (right-most number)
        int i = s.length() - 1;
        // skip any trailing non-digits
        while (i >= 0 && !Character.isDigit(s.charAt(i))) i--;
        if (i < 0) return "N/A";

        int endIdx = i;
        // find start of this number
        while (i >= 0 && Character.isDigit(s.charAt(i))) i--;
        int startIdx = i + 1;

        String digits = s.substring(startIdx, endIdx + 1);
        if (digits.isEmpty()) return "N/A";

        try {
            int mhz = Integer.parseInt(digits);
            return String.format(Locale.getDefault(), "%d MHz", mhz);
        } catch (NumberFormatException e) {
            return "N/A";
        }
    }

    private void captureCurrentData() {
        String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String pkgName = ForegroundAppDetector.getInstance(mContext).getCurrentForegroundApp();

        String fpsStr = "N/A", batStr = "N/A", cpuUsage = "N/A", cpuTemp = "N/A",
               gpuUsage = "N/A", gpuClk = "N/A", gpuTemp = "N/A";

        float fpsVal = GameBarFpsMeter.getInstance(mContext).getFps();
        fpsStr = fpsVal >= 0 ? String.format(Locale.getDefault(), "%.0f", fpsVal) : "N/A";
        batStr = readBatteryTempValue();
        cpuUsage = safe(GameBarCpuInfo.getCpuUsage());
        cpuTemp  = safe(GameBarCpuInfo.getCpuTemp());
        gpuUsage = safe(GameBarGpuInfo.getGpuUsage());
        gpuClk   = safe(GameBarGpuInfo.getGpuClock());
        gpuTemp  = safe(GameBarGpuInfo.getGpuTemp());

        GameDataExport.getInstance().addOverlayData(
                dateTime, pkgName, fpsStr, batStr, cpuUsage,
                cpuTemp, gpuUsage, gpuClk, gpuTemp
        );
    }

    // ===== Side-by-side (single-line) =====

    private String buildOneLineSbsText() {
        final String groupSep = " | ";
        final String compSep  = " · ";

        // Build each group text if feature enabled and value exists
        List<String> groups = new ArrayList<>();

        // FPS
        if (mShowFps) {
            String fps = fpsValue();
            if (fps != null) groups.add("FPS " + fps);
        }

        // BAT (°C)
        if (mShowBatteryTemp) {
            String t = readBatteryTempValue();
            if (t != null) groups.add("BAT " + t + "°C");
        }

        // CPU merged (usage% · maxMHz · temp°C)
        if (mShowCpuUsage || mShowCpuClock || mShowCpuTemp) {
            String merged = buildCpuMerged(compSep);
            if (merged != null) groups.add("CPU " + merged);
        }

        // RAM MB
        if (mShowRam) {
            String ram = safe(GameBarMemInfo.getRamUsage());
            if (ram != null) groups.add("RAM " + ram + "MB");
        }

        // GPU merged (usage% · clkMHz · temp°C)
        if (mShowGpuUsage || mShowGpuClock || mShowGpuTemp) {
            String merged = buildGpuMerged(compSep);
            if (merged != null) groups.add("GPU " + merged);
        }

        if (groups.isEmpty()) return "";

        // IMPORTANT: in side_by_side mode we do NOT perform width-limited packing.
        // Instead we return the full concatenation and let the overlay use the
        // maximum available width (MATCH_PARENT). This removes the restrictive
        // measurement/ellipsis logic and gives the user full control over which
        // items they enable.
        return TextUtils.join(groupSep, groups);
    }

    private String buildCpuMerged(String compSep) {
        List<String> comps = new ArrayList<>(3);

        if (mShowCpuUsage) {
            String u = safe(GameBarCpuInfo.getCpuUsage());
            if (u != null) comps.add(u + "%");
        }
        if (mShowCpuClock) {
            String mhz = maxCpuMhz(); // representative = max core
            if (mhz != null) comps.add(mhz + "MHz");
        }
        if (mShowCpuTemp) {
            String t = safe(GameBarCpuInfo.getCpuTemp());
            if (t != null) comps.add(t + "°C");
        }

        if (comps.isEmpty()) return null;
        return TextUtils.join(compSep, comps);
    }

    private String buildGpuMerged(String compSep) {
        List<String> comps = new ArrayList<>(3);

        if (mShowGpuUsage) {
            String u = safe(GameBarGpuInfo.getGpuUsage());
            if (u != null) comps.add(u + "%");
        }
        if (mShowGpuClock) {
            String mhz = safe(GameBarGpuInfo.getGpuClock());
            if (mhz != null) comps.add(mhz + "MHz");
        }
        if (mShowGpuTemp) {
            String t = safe(GameBarGpuInfo.getGpuTemp());
            if (t != null) comps.add(t + "°C");
        }

        if (comps.isEmpty()) return null;
        return TextUtils.join(compSep, comps);
    }

    private String fpsValue() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return null; // FPS meter not supported
        }
        float v = GameBarFpsMeter.getInstance(mContext).getFps();
        return v >= 0 ? String.format(Locale.getDefault(), "%.0f", v) : null;
    }

    private String readBatteryTempValue() {
        String tmp = readLine(BATTERY_TEMP_PATH);
        if (tmp == null || tmp.isEmpty()) return null;
        try {
            int raw = Integer.parseInt(tmp.trim());
            return String.format(Locale.getDefault(), "%.1f", raw / 10f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String maxCpuMhz() {
        List<String> freqs = GameBarCpuInfo.getCpuFrequencies();
        int best = -1;
        for (String s : freqs) {
            int idx = s.lastIndexOf(':');
            if (idx >= 0) {
                String tail = s.substring(idx + 1).trim();
                String digits = "";
                for (int i = 0; i < tail.length(); i++) {
                    char c = tail.charAt(i);
                    if (Character.isDigit(c)) digits += c;
                    else if (!digits.isEmpty()) break;
                }
                if (!digits.isEmpty()) {
                    try {
                        int mhz = Integer.parseInt(digits);
                        if (mhz > best) best = mhz;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return best > 0 ? String.valueOf(best) : null;
    }

    private String safe(String v) {
        if (v == null) return null;
        if ("N/A".equals(v)) return null;
        return v.trim().isEmpty() ? null : v.trim();
    }

    private int measure(String s, TextPaint p) {
        return (int) Math.ceil(p.measureText(s));
    }

    // Apply styling to layout views
    private void applyTextStyling() {
        if ("side_by_side".equals(mSplitMode) && mSbsTextView != null) {
            mSbsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            try {
                mSbsTextView.setTextColor(Color.parseColor(mValueColorHex));
            } catch (Exception e) {
                mSbsTextView.setTextColor(Color.WHITE);
            }
        } else if (mStackedLayout != null) {
            applyStackedTextStyling();
        }
    }

    // Apply text styling to all stacked views
    private void applyStackedTextStyling() {
        int[] labelIds = {
            R.id.fps_label,
            R.id.cpu0_label, R.id.cpu1_label, R.id.cpu2_label, R.id.cpu3_label,
            R.id.cpu4_label, R.id.cpu5_label, R.id.cpu6_label, R.id.cpu7_label,
            R.id.ram_label, R.id.battery_temp_label, R.id.cpu_usage_label,
            R.id.cpu_temp_label, R.id.gpu_usage_label, R.id.gpu_temp_label,
            R.id.gpu_clock_label
        };

        int[] valueIds = {
            R.id.fps_value, R.id.cpu_usage_value,
            R.id.cpu0_value, R.id.cpu1_value, R.id.cpu2_value, R.id.cpu3_value,
            R.id.cpu4_value, R.id.cpu5_value, R.id.cpu6_value, R.id.cpu7_value,
            R.id.cpu_temp_value, R.id.gpu_usage_value, R.id.gpu_clock_value,
            R.id.gpu_temp_value, R.id.ram_value, R.id.battery_temp_value
        };

        int titleColor, valueColor;
        try {
            titleColor = Color.parseColor(mTitleColorHex);
        } catch (Exception e) {
            titleColor = Color.WHITE;
        }
        try {
            valueColor = Color.parseColor(mValueColorHex);
        } catch (Exception e) {
            valueColor = Color.parseColor("#4CAF50");
        }

        // Apply to section headers
        if (mCpuSectionHeader != null) {
            mCpuSectionHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            mCpuSectionHeader.setTextColor(titleColor);
        }
        if (mGpuSectionHeader != null) {
            mGpuSectionHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            mGpuSectionHeader.setTextColor(titleColor);
        }

        for (int id : labelIds) {
            TextView tv = mStackedLayout.findViewById(id);
            if (tv != null) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
                tv.setTextColor(titleColor);
            }
        }

        for (int id : valueIds) {
            TextView tv = mStackedLayout.findViewById(id);
            if (tv != null) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
                tv.setTextColor(valueColor);
            }
        }
    }

    private void applyBackgroundStyleToLayout() {
        if (mRootLayout == null) return;

        GradientDrawable drawable = new GradientDrawable();
        int color = Color.argb(mBackgroundAlpha, 0, 0, 0);
        drawable.setColor(color);
        drawable.setCornerRadius(dpToPx(mContext, mCornerRadius));
        mRootLayout.setBackground(drawable);
    }

    private void applyPaddingToLayout() {
        if (mRootLayout != null) {
            int px = dpToPx(mContext, mPaddingDp);
            mRootLayout.setPadding(px, px, px, px);
        }
    }

    // ===== public setters for Fragment =====

    private void persistBooleanPref(String key, boolean value) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        prefs.edit().putBoolean(key, value).apply();
    }

    public void setShowFps(boolean show) {
        mShowFps = show;
        persistBooleanPref("game_bar_fps_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowBatteryTemp(boolean show) {
        mShowBatteryTemp = show;
        persistBooleanPref("game_bar_temp_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowCpuUsage(boolean show) {
        mShowCpuUsage = show;
        persistBooleanPref("game_bar_cpu_usage_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowCpuClock(boolean show) {
        mShowCpuClock = show;
        persistBooleanPref("game_bar_cpu_clock_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowCpuTemp(boolean show) {
        mShowCpuTemp = show;
        persistBooleanPref("game_bar_cpu_temp_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowRam(boolean show) {
        mShowRam = show;
        persistBooleanPref("game_bar_ram_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowGpuUsage(boolean show) {
        mShowGpuUsage = show;
        persistBooleanPref("game_bar_gpu_usage_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowGpuClock(boolean show) {
        mShowGpuClock = show;
        persistBooleanPref("game_bar_gpu_clock_enable", show);
        if (mIsShowing) updateStats();
    }

    public void setShowGpuTemp(boolean show) {
        mShowGpuTemp = show;
        persistBooleanPref("game_bar_gpu_temp_enable", show);
        if (mIsShowing) updateStats();
    }

    public void updateTextSize(int sp) {
        mTextSizeSp = sp;
        applyTextStyling();
    }

    public void updateCornerRadius(int radius) {
        mCornerRadius = radius;
        applyBackgroundStyleToLayout();
    }

    public void updateBackgroundAlpha(int alpha) {
        mBackgroundAlpha = alpha;
        applyBackgroundStyleToLayout();
    }

    public void updatePadding(int dp) {
        mPaddingDp = dp;
        applyPaddingToLayout();
    }

    public void updateTitleColor(String hex) {
        mTitleColorHex = hex;
        applyTextStyling();
    }

    public void updateValueColor(String hex) {
        mValueColorHex = hex;
        applyTextStyling();
    }

    public void updateItemSpacing(int dp) {
        mItemSpacingDp = dp;
        if (mIsShowing) updateStats();
    }

    public void updatePosition(String pos) {
        mPosition = pos;
        if (mIsShowing && mOverlayView != null && mLayoutParams != null) {
            if ("draggable".equals(mPosition)) {
                mDraggable = true;
                loadSavedPosition(mLayoutParams);
                if (mLayoutParams.x == 0 && mLayoutParams.y == 0) {
                    mLayoutParams.gravity = Gravity.TOP | Gravity.START;
                    mLayoutParams.x = 0;
                    mLayoutParams.y = 100;
                }
            } else {
                mDraggable = false;
                applyPosition(mLayoutParams, mPosition);
            }
            try {
                mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
            } catch (IllegalArgumentException e) {
                android.util.Log.w("GameBar", "Failed to update position", e);
            }
        }
    }

    public void updateSplitMode(String mode) {
        if (mode == null) mode = "stacked";
        // Persist immediately so other components see the change
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        prefs.edit().putString("game_bar_split_mode", mode).apply();

        mSplitMode = mode;

        // If overlay is showing, recreate with new layout — ensures width policy is applied
        if (mIsShowing) {
            hide();
            show();
        }
    }

    public void updateUpdateInterval(String intervalStr) {
        try { mUpdateIntervalMs = Integer.parseInt(intervalStr); }
        catch (NumberFormatException e) { mUpdateIntervalMs = 1000; }
        if (mIsShowing) startUpdates();
    }

    public void setLongPressEnabled(boolean enabled) { mLongPressEnabled = enabled; }
    public void setLongPressThresholdMs(long ms)     { mLongPressThresholdMs = ms; }
    public void setDoubleTapCaptureEnabled(boolean enabled) { mDoubleTapCaptureEnabled = enabled; }
    public void setSingleTapToggleEnabled(boolean enabled)  { mSingleTapToggleEnabled  = enabled; }

    private void startUpdates() {
        mHandler.removeCallbacks(mUpdateRunnable);
        mHandler.post(mUpdateRunnable);
    }

    private void loadSavedPosition(WindowManager.LayoutParams lp) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        int savedX = prefs.getInt(PREF_KEY_DRAGGED_X, Integer.MIN_VALUE);
        int savedY = prefs.getInt(PREF_KEY_DRAGGED_Y, Integer.MIN_VALUE);

        if (savedX != Integer.MIN_VALUE && savedY != Integer.MIN_VALUE) {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = savedX;
            lp.y = savedY;

            int margin = dpToPx(mContext, DRAG_BOUNDARY_MARGIN_DP);
            lp.x = Math.max(-100 + margin, Math.min(mScreenWidth - margin, lp.x));
            lp.y = Math.max(-100 + margin, Math.min(mScreenHeight - margin, lp.y));
        }
    }

    private void applyPosition(WindowManager.LayoutParams lp, String pos) {
        switch (pos) {
            case "top_left":
                lp.gravity = Gravity.TOP | Gravity.START; lp.x = 0; lp.y = 100; break;
            case "top_center":
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = 100; break;
            case "top_right":
                lp.gravity = Gravity.TOP | Gravity.END; lp.x = 0; lp.y = 100; break;
            case "bottom_left":
                lp.gravity = Gravity.BOTTOM | Gravity.START; lp.x = 0; lp.y = 100; break;
            case "bottom_center":
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; lp.y = 100; break;
            case "bottom_right":
                lp.gravity = Gravity.BOTTOM | Gravity.END; lp.x = 0; lp.y = 100; break;
            default:
                lp.gravity = Gravity.TOP | Gravity.START; lp.x = 0; lp.y = 100; break;
        }
    }

    // ===== utils =====

    private String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private void openOverlaySettings() {
        try {
            Context ctx = mContextRef.get();
            if (ctx == null) {
                android.util.Log.w("GameBar", "Context is null, cannot open settings");
                return;
            }

            Intent intent = new Intent(ctx, GameBarSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            android.util.Log.e("GameBar", "Failed to open overlay settings", e);
        }
    }

    public boolean isValidHexColor(String hex) {
        if (hex == null || hex.isEmpty()) return false;
        if (!hex.startsWith("#")) return false;
        if (hex.length() != 7 && hex.length() != 9) return false;
        try {
            Color.parseColor(hex);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int dpToPx(Context context, int dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * scale);
    }
}
