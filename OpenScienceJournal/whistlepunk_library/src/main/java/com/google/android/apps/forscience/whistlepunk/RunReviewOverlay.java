/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.support.v4.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.SeekBar;

import com.google.android.apps.forscience.whistlepunk.review.CoordinatedSeekbarViewGroup;
import com.google.android.apps.forscience.whistlepunk.review.GraphExploringSeekBar;
import com.google.android.apps.forscience.whistlepunk.scalarchart.ChartController;
import com.google.android.apps.forscience.whistlepunk.scalarchart.ChartData;

/**
 * Draws the value over a RunReview chart.
 */
public class RunReviewOverlay extends View implements ChartController.ChartDataLoadedCallback {

    public interface OnTimestampChangeListener {
        void onTimestampChanged(long timestamp);
    }
    private OnTimestampChangeListener mTimestampChangeListener;

    public interface OnSeekbarTouchListener {
        void onTouchStart();
        void onTouchStop();
    }
    private OnSeekbarTouchListener mOnSeekbarTouchListener;

    // Class to track the measurements of a RunReview overlay flag, which is bounded by
    // boxStart/End/Top/Bottom, and has a notch below it down to a certain height.
    private static class FlagMeasurements {
        public float boxStart;
        public float boxEnd;
        public float boxTop;
        public float boxBottom;
        public float notchBottom;
    }
    // Save allocations by just keeping one of these measurements around.
    private FlagMeasurements mFlagMeasurements = new FlagMeasurements();

    private static double SQRT_2_OVER_2 = Math.sqrt(2) / 2;

    private int mHeight;
    private int mWidth;
    private int mPaddingBottom;
    private int mChartPaddingTop;
    private int mChartHeight;
    private int mChartMarginLeft;
    private int mChartMarginRight;

    // Flag text padding.
    private int mLabelPadding;

    // Space between the time label and value label in a flag.
    private int mIntraLabelPadding;

    // Height of the triangle notch at the bottom of a flag.
    private int mNotchHeight;

    // Radius of the corner of the flag.
    private int mCornerRadius;

    // Amount of buffer a flag must keep between itself and the body of the flag after it.
    private int mCropFlagBufferX;

    private Paint mPaint;
    private Paint mDotPaint;
    private Paint mDotBackgroundPaint;
    private Paint mTextPaint;
    private Paint mTimePaint;
    private Paint mLinePaint;
    private Paint mCenterLinePaint;
    private Paint mCropBackgroundPaint;
    private Paint mCropVerticalLinePaint;

    public static final long NO_TIMESTAMP_SELECTED = -1;

    private long mPreviouslySelectedTimestamp;
    private long mPreviousCropStartTimestamp;
    private long mPreviousCropEndTimestamp;

    // Represents the data associated with a seekbar point tracked in RunReviewOverlay.
    private static class OverlayPointData {
        // The currently selected timestamp for this seekbar.
        public long timestamp = NO_TIMESTAMP_SELECTED;

        // The value for the currently selected timestamp.
        public double value;

        // The screen point of the currently selected value.
        public PointF screenPoint;

        // The string representing the current chart value for the standard overlay label.
        public String label;
    }

    private OverlayPointData mPointData = new OverlayPointData();
    private GraphExploringSeekBar mSeekbar;

    // TODO: Consider moving crop fields and logic into a CropController class.
    private OverlayPointData mCropStartData = new OverlayPointData();
    private OverlayPointData mCropEndData = new OverlayPointData();
    private CoordinatedSeekbarViewGroup mCropSeekbarGroup;

    // When one of the crop seekbars' progress is changed we sometimes need to update the progress
    // bars again in order to match the closest point to the location on the seekbar.
    // Because the crop seekbars' positions may interact with each other (they cannot be closer
    // than 1 second or 5% of their length), both seekbars' updated values are calculated at
    // the same time.
    // This variable tracks whether we are still waiting for a progress update from the second
    // crop seekbar, and is used to decide whether to refresh the data or wait until the second
    // seekbar's update comes in.
    // This prevents refreshing from happening too frequently or before the second seekbar has
    // a chance to have its progress updated.
    // TODO: Is there a cleaner way to do this?
    private boolean mIgnoreNextSeekbarProgressUpdate;

    private ChartController mChartController;
    private ExternalAxisController mExternalAxis;
    private String mTextFormat;
    private ElapsedTimeAxisFormatter mTimeFormat;
    private RectF mBoxRect;
    private Path mPath;
    private float mDotRadius;
    private float mDotBackgroundRadius;
    private Drawable mThumb;
    private ViewTreeObserver.OnDrawListener mOnDrawListener;

    private boolean mIsCropping;

    public RunReviewOverlay(Context context) {
        super(context);
        init();
    }

    public RunReviewOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RunReviewOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @TargetApi(21)
    public RunReviewOverlay(Context context, AttributeSet attrs, int defStyleAttr,
                            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        Resources res = getResources();

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.FILL);

        mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDotPaint.setStyle(Paint.Style.FILL);

        mDotBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDotBackgroundPaint.setColor(res.getColor(R.color.chart_margins_color));
        mDotBackgroundPaint.setStyle(Paint.Style.FILL);

        Typeface valueTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface timeTimeface = Typeface.create("sans-serif", Typeface.NORMAL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTypeface(valueTypeface);
        mTextPaint.setTextSize(res.getDimension(R.dimen.run_review_overlay_label_text_size));
        mTextPaint.setColor(res.getColor(R.color.text_color_white));

        mTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTimePaint.setTypeface(timeTimeface);
        mTimePaint.setTextSize(res.getDimension(R.dimen.run_review_overlay_label_text_size));
        mTimePaint.setColor(res.getColor(R.color.text_color_white));

        mCenterLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCenterLinePaint.setStrokeWidth(res.getDimensionPixelSize(R.dimen.chart_grid_line_width));
        mCenterLinePaint.setStyle(Paint.Style.STROKE);
        mCenterLinePaint.setColor(res.getColor(R.color.text_color_white));

        mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLinePaint.setStrokeWidth(res.getDimensionPixelSize(R.dimen.recording_overlay_bar_width));
        int dashSize = res.getDimensionPixelSize(R.dimen.run_review_overlay_dash_size);
        mLinePaint.setPathEffect(new DashPathEffect(new float[]{dashSize, dashSize}, dashSize));
        mLinePaint.setColor(res.getColor(R.color.note_overlay_line_color));
        mLinePaint.setStyle(Paint.Style.STROKE);

        mPath = new Path();
        mBoxRect = new RectF();

        // TODO: Need to make sure this is at least as detailed as the SensorAppearance number
        // format!
        mTextFormat = res.getString(R.string.run_review_chart_label_format);
        mTimeFormat = ElapsedTimeAxisFormatter.getInstance(getContext());

        mCropBackgroundPaint = new Paint();
        mCropBackgroundPaint.setStyle(Paint.Style.FILL);
        mCropBackgroundPaint.setColor(res.getColor(R.color.text_color_black));
        mCropBackgroundPaint.setAlpha(40);

        mCropVerticalLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCropVerticalLinePaint.setStyle(Paint.Style.STROKE);
        mCropVerticalLinePaint.setStrokeWidth(
                res.getDimensionPixelSize(R.dimen.chart_grid_line_width));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Resources res = getResources();
        mHeight = getMeasuredHeight();
        mWidth = getMeasuredWidth();
        mPaddingBottom = getPaddingBottom();
        mChartPaddingTop = res.getDimensionPixelSize(R.dimen.run_review_section_margin);
        mChartHeight = res.getDimensionPixelSize(R.dimen.run_review_chart_height);

        mLabelPadding = res.getDimensionPixelSize(R.dimen.run_review_overlay_label_padding);
        mIntraLabelPadding = res.getDimensionPixelSize(
                R.dimen.run_review_overlay_label_intra_padding);
        mNotchHeight = res.getDimensionPixelSize(R.dimen.run_review_overlay_label_notch_height);
        mCornerRadius = res.getDimensionPixelSize(R.dimen.run_review_overlay_label_corner_radius);
        mCropFlagBufferX = mLabelPadding;

        mDotRadius = res.getDimensionPixelSize(R.dimen.run_review_value_label_dot_radius);
        mDotBackgroundRadius =
                res.getDimensionPixelSize(R.dimen.run_review_value_label_dot_background_radius);

        mChartMarginLeft = res.getDimensionPixelSize(R.dimen.chart_margin_size_left) +
                res.getDimensionPixelSize(R.dimen.stream_presenter_padding_sides);
        mChartMarginRight = res.getDimensionPixelSize(R.dimen.stream_presenter_padding_sides);
    }

    public void onDraw(Canvas canvas) {
        if (mIsCropping) {
            // TODO: Click listeners for these flags open up dialogs for entering crop timestamps.
            boolean cropStartOnChart = isXScreenPointInChart(mCropStartData.screenPoint);
            boolean cropEndOnChart = isXScreenPointInChart(mCropEndData.screenPoint);

            // Draw grey overlays first, behind everything
            if (cropStartOnChart) {
                canvas.drawRect(mChartMarginLeft, mHeight - mChartHeight - mPaddingBottom,
                        mCropStartData.screenPoint.x, mHeight, mCropBackgroundPaint);
                // We can handle crop seekbar visibility in onDraw because the Seekbar Group is
                // in charge of receiving touch events, and that is always visible during cropping.
                mCropSeekbarGroup.getStartSeekBar().setVisibility(View.VISIBLE);
            } else {
                mCropSeekbarGroup.getStartSeekBar().setVisibility(View.INVISIBLE);
            }
            if (cropEndOnChart) {
                canvas.drawRect(mCropEndData.screenPoint.x, mHeight - mChartHeight - mPaddingBottom,
                        mWidth - mChartMarginRight, mHeight, mCropBackgroundPaint);
                mCropSeekbarGroup.getEndSeekBar().setVisibility(View.VISIBLE);
            } else {
                mCropSeekbarGroup.getEndSeekBar().setVisibility(View.INVISIBLE);
            }

            // Draw the flags themselves
            if (cropStartOnChart) {
                // Drawing the start flag sets mFlagMeasurements to have the start flag's bounding
                // box. This will allow us to place the end flag appropriately.
                drawFlag(canvas, mCropStartData.timestamp, mCropStartData.screenPoint.x,
                        mCropStartData.label, mFlagMeasurements, true);
            } else {
                // Clear flag measurements when the left hand flag is offscreen, so that
                // drawFlagAfter does not see another flag to avoid.
                // This means pushing the expected previous flag measurements, stored in
                // mFlagMeasurements, off screen by at least the amount of the flag buffer, which
                // allows the next flag to start drawing at 0.
                // In drawFlagAfter we will use mFlagMeasurements.boxEnd to determine what to
                // avoid.
                mFlagMeasurements.boxEnd = -mCropFlagBufferX;
            }
            if (cropEndOnChart) {
                drawFlagAfter(canvas, mCropEndData.timestamp, mCropEndData.screenPoint.x,
                        mCropEndData.label, mFlagMeasurements, mFlagMeasurements.boxEnd, true);
            }

            // Neither flag can be drawn, but we might be in a region that can be cropped out
            // so the whole thing should be colored grey.
            if (!cropEndOnChart && !cropStartOnChart) {
                if (mCropStartData.timestamp > mExternalAxis.mXMax ||
                        mCropEndData.timestamp < mExternalAxis.mXMin) {
                    canvas.drawRect(mChartMarginLeft, mHeight - mChartHeight - mPaddingBottom,
                            mWidth - mChartMarginRight, mHeight, mCropBackgroundPaint);
                }
            }
        } else {
            boolean xOnChart = isXScreenPointInChart(mPointData.screenPoint);
            boolean yOnChart = isYScreenPointInChart(mPointData.screenPoint);
            if (xOnChart && yOnChart) {
                // We are not cropping. Draw a standard flag.
                drawFlag(canvas, mPointData.timestamp, mPointData.screenPoint.x, mPointData.label,
                        mFlagMeasurements, false);

                // Draw the vertical line from the point to the bottom of the flag
                float nudge = mDotRadius / 2;
                float cy = mHeight - mChartHeight - mPaddingBottom + mPointData.screenPoint.y -
                        2 * mDotBackgroundRadius + nudge;
                mPath.reset();
                mPath.moveTo(mPointData.screenPoint.x, mFlagMeasurements.notchBottom);
                mPath.lineTo(mPointData.screenPoint.x, cy);
                canvas.drawPath(mPath, mLinePaint);

                // Draw the selected point
                float cySmall = cy + 1.5f * mDotBackgroundRadius;
                canvas.drawCircle(mPointData.screenPoint.x, cySmall, mDotBackgroundRadius,
                        mDotBackgroundPaint);
                canvas.drawCircle(mPointData.screenPoint.x, cySmall, mDotRadius, mDotPaint);
            }
        }
    }

    /**
     * Determines if a screen point is inside of the chart.
     * @param screenPoint The point to test
     * @return true if the screen point is on the graph in the X axis
     */
    private boolean isXScreenPointInChart(PointF screenPoint) {
        if (screenPoint == null) {
            return false;
        }
        // Check if we can't draw the overlay balloon because the point is close to the edge of
        // the graph or offscreen.
        return screenPoint.x >= mChartMarginLeft && screenPoint.x <= mWidth - mChartMarginRight;
    }

    /**
     * Determines if a screen point is inside of the chart.
     * @param screenPoint The point to test
     * @return true if the screen point is on the graph in the Y axis
     */
    private boolean isYScreenPointInChart(PointF screenPoint) {
        if (screenPoint == null) {
            return false;
        }
        return screenPoint.y <= mChartHeight && screenPoint.y >= mChartPaddingTop;
    }

    /**
     * Draw a flag above a specific timestamp with a given value, but make sure the flag starts
     * after the given flagXToDrawAfter or that the flag is raised up to avoid intersecting it.
     * @param canvas The canvas to use
     * @param selectedTimestamp The timestamp to use on the label
     * @param cx The X position of the timestamp
     * @param label The value to use on the label
     * @param flagMeasurements This set of measurements will be updated in-place to hold the bounds
     *                         of the flag.
     * @param flagXToDrawAfter The x position past which the flag may not draw. If the flag needs
     *                       this space, it must draw itself higher.
     */
    private void drawFlagAfter(Canvas canvas, long selectedTimestamp, float cx, String label,
            FlagMeasurements flagMeasurements, float flagXToDrawAfter, boolean drawStem) {
        if (label == null) {
            label = "";
        }
        float labelWidth = mTextPaint.measureText(label);
        String timeLabel = mTimeFormat.formatToTenths(selectedTimestamp -
                mExternalAxis.getRecordingStartTime());
        float timeWidth = mTimePaint.measureText(timeLabel);

        // Ascent returns the distance above (negative) the baseline (ascent). Since it is negative,
        // negate it again to get the text height.
        float textSize = -1 * mTextPaint.ascent();

        flagMeasurements.boxTop = mHeight - mChartHeight - mPaddingBottom - textSize;
        flagMeasurements.boxBottom = flagMeasurements.boxTop + textSize + mLabelPadding * 2 + 5;
        float width = mIntraLabelPadding + 2 * mLabelPadding + timeWidth + labelWidth;
        // Ideal box layout
        flagMeasurements.boxStart = cx - width / 2;
        flagMeasurements.boxEnd = cx + width / 2;

        // Adjust it if the ideal doesn't work
        boolean isRaised = false;
        if (flagMeasurements.boxStart < flagXToDrawAfter + mCropFlagBufferX) {
            // See if we can simply offset the flag, if it doesn't cause the notch to be drawn
            // off the edge of the flag.
            if (flagXToDrawAfter + mCropFlagBufferX <
                    cx - mNotchHeight * SQRT_2_OVER_2 - mCornerRadius) {
                flagMeasurements.boxStart = flagXToDrawAfter + mCropFlagBufferX;
                flagMeasurements.boxEnd = flagMeasurements.boxStart + width;
            } else {
                // We need to move the flag up!
                moveUpToAvoid(flagMeasurements, textSize);
                isRaised = true;
            }
        }
        if (flagMeasurements.boxEnd > mWidth) {
            flagMeasurements.boxEnd = mWidth;
            flagMeasurements.boxStart = flagMeasurements.boxEnd - width;
            if (!isRaised && flagXToDrawAfter + mCropFlagBufferX > flagMeasurements.boxStart) {
                // We need to move the flag up!
                moveUpToAvoid(flagMeasurements, textSize);
                isRaised = true;
            }
        }
        flagMeasurements.notchBottom = flagMeasurements.boxBottom + mNotchHeight;

        mBoxRect.set(flagMeasurements.boxStart, flagMeasurements.boxTop,
                flagMeasurements.boxEnd, flagMeasurements.boxBottom);
        canvas.drawRoundRect(mBoxRect, mCornerRadius, mCornerRadius, mPaint);

        mPath.reset();
        mPath.moveTo((int) (cx - mNotchHeight * SQRT_2_OVER_2), flagMeasurements.boxBottom);
        mPath.lineTo(cx, flagMeasurements.boxBottom + mNotchHeight);
        mPath.lineTo((int) (cx + mNotchHeight * SQRT_2_OVER_2), flagMeasurements.boxBottom);
        canvas.drawPath(mPath, mPaint);

        float textBase = flagMeasurements.boxTop + mLabelPadding + textSize;
        canvas.drawText(timeLabel, flagMeasurements.boxStart + mLabelPadding, textBase, mTimePaint);
        canvas.drawText(label, flagMeasurements.boxEnd - labelWidth - mLabelPadding, textBase,
                mTextPaint);

        float center = flagMeasurements.boxStart + mLabelPadding + timeWidth +
                mIntraLabelPadding / 2;
        canvas.drawLine(center, flagMeasurements.boxTop + mLabelPadding, center,
                flagMeasurements.boxBottom - mLabelPadding, mCenterLinePaint);

        if (drawStem) {
            // Draws a vertical line to the flag notch from the base.
            // If there is a flag to draw after, does not overlap that flag.
            if (cx < flagXToDrawAfter) {
                canvas.drawLine(cx, mHeight, cx, mFlagMeasurements.boxBottom - 5 +
                        textSize + 3 * mLabelPadding, mCropVerticalLinePaint);
            } else {
                canvas.drawLine(cx, mHeight, cx, mFlagMeasurements.notchBottom - 5,
                        mCropVerticalLinePaint);
            }
        }
    }

    private void moveUpToAvoid(FlagMeasurements flagMeasurements, float textSize) {
        // We need to move the flag up! Use 3 times padding to cover the two
        // paddings within the other flag and one more padding value above the other flag.
        flagMeasurements.boxBottom -= textSize + 3 * mLabelPadding;
        flagMeasurements.boxTop -= textSize + 3 * mLabelPadding;
    }

    /**
     * Draw a flag above a specific timestamp with a given value.
     * @param canvas The canvas to use
     * @param selectedTimestamp The timestamp to use on the label
     * @param cx The X position of the timestamp
     * @param label The value to use on the label
     * @param flagMeasurements This set of measurements will be updated in-place to hold the bounds
     *                         of the flag.
     */
    private void drawFlag(Canvas canvas, long selectedTimestamp, float cx, String label,
            FlagMeasurements flagMeasurements, boolean drawStem) {
        drawFlagAfter(canvas, selectedTimestamp, cx, label, flagMeasurements, -mCropFlagBufferX,
                drawStem);
    }

    public void setChartController(ChartController controller) {
        mChartController = controller;
        mChartController.addChartDataLoadedCallback(this);
    }

    public void setGraphSeekBar(final GraphExploringSeekBar seekbar) {
        mSeekbar = seekbar;
        // Seekbar thumb is always blue, no matter the color of the grpah.
        int color = getResources().getColor(R.color.color_accent);
        mSeekbar.getThumb().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        mSeekbar.setVisibility(View.VISIBLE);

        mThumb = mSeekbar.getThumb();
        mSeekbar.setOnSeekBarChangeListener(new GraphExploringSeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ((GraphExploringSeekBar) seekBar).updateFullProgress(progress);
                refreshAfterChartLoad(fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // This is only called after the user starts moving, so we use an OnTouchListener
                // instead to get the requested UX behavior.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mOnSeekbarTouchListener != null) {
                    mOnSeekbarTouchListener.onTouchStop();
                }
                // If the user is as early on the seekbar as they can go, hide the overlay.
                ChartData.DataPoint point = getDataPointAtProgress(seekBar.getProgress());
                if (point == null || !mChartController.hasData() ||
                        point.getX() <= mChartController.getXMin()) {
                    setVisibility(View.INVISIBLE);
                }
                invalidate();
            }
        });

        // Use an OnTouchListener to activate the views even if the user doesn't move.
        mSeekbar.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mOnSeekbarTouchListener != null) {
                    mOnSeekbarTouchListener.onTouchStart();
                }
                mSeekbar.setThumb(mThumb); // Replace the thumb if it was missing after zoom/pan.
                setVisibility(View.VISIBLE);
                invalidate();
                return false;
            }
        });
    }

    public void setCropSeekBarGroup(CoordinatedSeekbarViewGroup cropGroup) {
        mCropSeekbarGroup = cropGroup;
        GraphExploringSeekBar.OnSeekBarChangeListener listener =
                new GraphExploringSeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Note that for crop seekbars, the full progress is updated in a change listener
                // on the crop bar, so we don't need to do that here.
                if (mIgnoreNextSeekbarProgressUpdate) {
                    // Don't refresh if we are still waiting for another seekbar to update,
                    // because this refresh would otherwise overwrite that update.
                    mIgnoreNextSeekbarProgressUpdate = false;
                } else {
                    refreshAfterChartLoad(fromUser);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ChartData.DataPoint point = getDataPointAtProgress(seekBar.getProgress());
                if (point == null || !mChartController.hasData() ||
                        point.getX() < mChartController.getXMin() ||
                        point.getX() > mChartController.getXMax()) {
                    seekBar.setVisibility(View.INVISIBLE);
                }
                invalidate();
            }
        };
        mCropSeekbarGroup.getStartSeekBar().addOnSeekBarChangeListener(listener);
        mCropSeekbarGroup.getEndSeekBar().addOnSeekBarChangeListener(listener);
    }

    /**
     * Refreshes the selected timestamp and value based on the seekbar progress value. Can
     * optionally take the value found and calculate the progress the seekbar should have, and
     * update the seekbar again to get it more perfectly in sync. This is useful when data is
     * sparce or zoomed in, to keep the seekbar and drawn point vertically aligned. Note that
     * updating the seekbar's progress with backUpdateProgressBar true causes this function to be
     * called again with backUpdateProgressBar set to false.
     * @param backUpdateProgressBar If true, updates the seekbar progress based on the found point.
     */
    public void refresh(boolean backUpdateProgressBar) {
        if (mIsCropping) {
            refreshFromSeekbar(mCropSeekbarGroup.getStartSeekBar(), mCropStartData);
            refreshFromSeekbar(mCropSeekbarGroup.getEndSeekBar(), mCropEndData);
            redrawCrop(backUpdateProgressBar);
        } else {
            refreshFromSeekbar(mSeekbar, mPointData);
            redrawFromSeekbar(mSeekbar, mPointData, backUpdateProgressBar);
        }
    }

    /**
     * Refreshes the OverlayPointData for a particular Seekbar's progress.
     */
    public void refreshFromSeekbar(GraphExploringSeekBar seekbar, OverlayPointData pointData) {
        // Determine the timestamp at the current seekbar progress.
        int progress = seekbar.getFullProgress();
        ChartData.DataPoint point = getDataPointAtProgress(progress);
        if (point == null) {
            // This happens when the user is dragging the thumb before the chart has loaded
            // data; there is no data loaded at all.
            // The bubble itself has been hidden in this case in RunReviewFragment, which hides
            // the RunReviewOverlay during line graph load and only shows it again once the
            // graph has been loaded successfully.
            return;
        }
        // Update the selected timestamp to one available in the chart data.
        pointData.timestamp = point.getX();
        pointData.value = point.getY();
        pointData.label = String.format(mTextFormat, pointData.value);
        seekbar.updateValuesForAccessibility(mExternalAxis.formatElapsedTimeForAccessibility(
                pointData.timestamp, getContext()), pointData.label);
    }

    /**
     * Redraws the RunReview overlay. See description of refresh() above for more on
     * backUpdateProgressBar.
     * @param backUpdateProgressBar If true, updates the seekbar progress based on the found point.
     */
    private void redrawFromSeekbar(GraphExploringSeekBar seekbar, OverlayPointData pointData,
            boolean backUpdateProgressBar) {
        if (pointData.timestamp == NO_TIMESTAMP_SELECTED) {
            return;
        }
        if (backUpdateProgressBar) {
            long axisDuration = mExternalAxis.mXMax - mExternalAxis.mXMin;
            int newProgress = (int) Math.round((GraphExploringSeekBar.SEEKBAR_MAX *
                    (pointData.timestamp - mExternalAxis.mXMin)) / axisDuration);
            if (seekbar.getFullProgress() != newProgress) {
                seekbar.setFullProgress(newProgress);
            }
        }

        if (mChartController.hasScreenPoints()) {
            pointData.screenPoint = mChartController.getScreenPoint(pointData.timestamp,
                    pointData.value);
        }
        if (mTimestampChangeListener != null) {
            mTimestampChangeListener.onTimestampChanged(pointData.timestamp);
        }
        postInvalidateOnAnimation();
    }

    /**
     * Redraws the RunReview overlay for the crop seekbars. See description of refresh() above
     * for more on backUpdateProgressBars.
     * @param backUpdateProgressBars If true, updates the seekbars progress based on the found point
     */
    private void redrawCrop(boolean backUpdateProgressBars) {
        if (mCropStartData.timestamp == NO_TIMESTAMP_SELECTED ||
                mCropEndData.timestamp == NO_TIMESTAMP_SELECTED) {
            return;
        }
        if (backUpdateProgressBars) {
            long axisDuration = mExternalAxis.mXMax - mExternalAxis.mXMin;
            int oldStartProgress = mCropSeekbarGroup.getStartSeekBar().getFullProgress();
            int oldEndProgress = mCropSeekbarGroup.getEndSeekBar().getFullProgress();
            int newStartProgress = (int) Math.round((GraphExploringSeekBar.SEEKBAR_MAX *
                    (mCropStartData.timestamp - mExternalAxis.mXMin)) / axisDuration);
            int newEndProgress = (int) Math.round((GraphExploringSeekBar.SEEKBAR_MAX *
                    (mCropEndData.timestamp - mExternalAxis.mXMin)) / axisDuration);
            boolean startNeedsProgressUpdate = oldStartProgress != newStartProgress;
            boolean endNeedsProgressUpdate = oldEndProgress != newEndProgress;

            if (startNeedsProgressUpdate && endNeedsProgressUpdate) {
                mIgnoreNextSeekbarProgressUpdate = true;
                // Need to set these in an order that doesn't cause them to push each other.
                // So if the start increases, the end needs to increase first.
                // If the end decreases, the start needs to decrease first.
                // Otherwise they may shift when CropSeekBar trys to keep the buffer.
                if (oldStartProgress < newStartProgress) {
                    mCropSeekbarGroup.getEndSeekBar().setFullProgress(newEndProgress);
                    mCropSeekbarGroup.getStartSeekBar().setFullProgress(newStartProgress);
                } else {
                    mCropSeekbarGroup.getStartSeekBar().setFullProgress(newStartProgress);
                    mCropSeekbarGroup.getEndSeekBar().setFullProgress(newEndProgress);
                }

            } else if (startNeedsProgressUpdate) {
                mCropSeekbarGroup.getStartSeekBar().setFullProgress(newStartProgress);
            } else if (endNeedsProgressUpdate) {
                mCropSeekbarGroup.getEndSeekBar().setFullProgress(newEndProgress);
            }
        }
        if (mChartController.hasScreenPoints()) {
            mCropStartData.screenPoint = mChartController.getScreenPoint(
                    mCropStartData.timestamp, mCropStartData.value);
            mCropEndData.screenPoint = mChartController.getScreenPoint(
                    mCropEndData.timestamp, mCropEndData.value);
        }
        invalidate();
    }

    private static int clipToSeekbarRange(double value) {
        return (int) (Math.min(Math.max(value, 0), GraphExploringSeekBar.SEEKBAR_MAX));
    }

    private ChartData.DataPoint getDataPointAtProgress(int progress) {
        double percent = progress / GraphExploringSeekBar.SEEKBAR_MAX;
        long axisDuration = mExternalAxis.mXMax - mExternalAxis.mXMin;
        long timestamp = (long) (percent * axisDuration +
                mExternalAxis.mXMin);
        // Get the data point closest to this timestamp.
        return mChartController.getClosestDataPointToTimestamp(timestamp);
    }

    /**
     * For the graph exploring seekbar only (not crop)
     */
    public void setOnTimestampChangeListener(OnTimestampChangeListener listener) {
        mTimestampChangeListener = listener;
    }

    /**
     * For the graph exploring seekbar only (not crop)
     */
    public void setOnSeekbarTouchListener(OnSeekbarTouchListener listener) {
        mOnSeekbarTouchListener = listener;
    }

    public void refreshAfterChartLoad(final boolean backUpdateProgressBar) {
        if (!mChartController.hasDrawnChart()) {
            // Refresh the Run Review Overlay after the line graph presenter's chart
            // has finished drawing itself.
            final ViewTreeObserver observer = mChartController.getChartViewTreeObserver();
            if (observer == null) {
                return;
            }
            observer.removeOnDrawListener(mOnDrawListener);
            mOnDrawListener = new ViewTreeObserver.OnDrawListener() {
                @Override
                public void onDraw() {
                    RunReviewOverlay.this.post(new Runnable() {
                        @Override
                        public void run() {
                            // The ViewTreeObserver calls its listeners without an iterator,
                            // so we need to remove the listener outside the flow or we risk
                            // an index-out-of-bounds crash in the case of multiple listeners.
                            observer.removeOnDrawListener(mOnDrawListener);
                            mOnDrawListener = null;
                            refresh(backUpdateProgressBar);
                        }
                    });

                }
            };
            observer.addOnDrawListener(mOnDrawListener);
        } else {
            refresh(backUpdateProgressBar);
        }
    }

    /**
     * When the Y axis changes, the data points may change position in Y.
     * Need to "refresh" to re-calculate the points associated with the timestamps,
     * if the timestamps are within the external axis range.
     */
    public void onYAxisAdjusted() {
        if (mIsCropping) {
            // TODO: Does this work if just one is offscreen on rotate?
            if (mExternalAxis.containsTimestamp(mCropStartData.timestamp) ||
                    mExternalAxis.containsTimestamp(mCropEndData.timestamp)) {
                refresh(false);
            }
        } else {
            if (mExternalAxis.containsTimestamp(mPointData.timestamp)) {
                refresh(false);
            }
        }
    }

    public void onDestroy() {
        if (mChartController != null) {
            mChartController.removeChartDataLoadedCallback(this);
            if (mOnDrawListener != null) {
                final ViewTreeObserver observer = mChartController.getChartViewTreeObserver();
                if (observer == null) {
                    return;
                }
                observer.removeOnDrawListener(mOnDrawListener);
            }
        }
    }

    /**
     * Sets the slider to a particular timestamp. The user did not initiate this action.
     */
    public void setActiveTimestamp(long timestamp) {
        if (updateActiveTimestamp(timestamp)) {
            refreshAfterChartLoad(true);
        };
    }

    /**
     * Updates the active timestamp and the seekbar's progress based on the timestamp. Returns true
     * if the update means that a visual refresh is needed.
     * @return true if the timestamp is within the range of the external axis and a refresh is
     *         needed.
     */
    private boolean updateActiveTimestamp(long timestamp) {
        mPointData.timestamp = timestamp;
        if (mExternalAxis.containsTimestamp(mPointData.timestamp)) {
            mSeekbar.setThumb(mThumb);
            double progress = (int) ((GraphExploringSeekBar.SEEKBAR_MAX *
                    (timestamp - mExternalAxis.mXMin)) /
                    (mExternalAxis.mXMax - mExternalAxis.mXMin));
            setVisibility(View.VISIBLE);
            mSeekbar.setFullProgress((int) Math.round(progress));
            // Only back-update the seekbar if the selected timestamp is in range.
            return true;
        } else {
            mSeekbar.setThumb(null);
            if (mChartController.hasDrawnChart()) {
                redrawFromSeekbar(mSeekbar, mPointData, true);
            }
            return false;
        }
    }

    public void setCropTimestamps(long startTimestamp, long endTimestamp) {
        if (updateCropTimestamps(startTimestamp, endTimestamp)) {
            refreshAfterChartLoad(true);
        }
    }

    /**
     * Updates the active timestamp and the seekbar's progress based on the timestamp, for the crop
     * seekbars. Returns true if the update means that a visual refresh is needed.
     * @return true if at least one of the timestamps is within the range of the external axis and
     *         a refresh is needed.
     */
    private boolean updateCropTimestamps(long startTimestamp, long endTimestamp) {
        mCropStartData.timestamp = startTimestamp;
        mCropEndData.timestamp = endTimestamp;
        boolean hasSeekbarInRange = false;
        boolean endSeekbarNeedsProgressUpdate =
                mExternalAxis.containsTimestamp(mCropEndData.timestamp);
        if (mExternalAxis.containsTimestamp(mCropStartData.timestamp)) {
            double progress = (int) ((GraphExploringSeekBar.SEEKBAR_MAX *
                    (mCropStartData.timestamp - mExternalAxis.mXMin)) /
                    (mExternalAxis.mXMax - mExternalAxis.mXMin));
            mIgnoreNextSeekbarProgressUpdate = endSeekbarNeedsProgressUpdate;
            mCropSeekbarGroup.getStartSeekBar().setFullProgress((int) Math.round(progress));
            hasSeekbarInRange = true;
        }
        if (endSeekbarNeedsProgressUpdate) {
            double progress = (int) ((GraphExploringSeekBar.SEEKBAR_MAX *
                    (mCropEndData.timestamp - mExternalAxis.mXMin)) /
                    (mExternalAxis.mXMax - mExternalAxis.mXMin));
            if (hasSeekbarInRange) {
                mIgnoreNextSeekbarProgressUpdate = true;
            }
            mCropSeekbarGroup.getEndSeekBar().setFullProgress((int) Math.round(progress));
            hasSeekbarInRange = true;
        }
        if (hasSeekbarInRange) {
            setVisibility(View.VISIBLE);
            // Only back-update the seekbar if the selected timestamp is in range.
            return true;
        } else if (mChartController.hasDrawnChart()) {
            redrawCrop(true);
        }
        return false;
    }

    /**
     * Set the timestamps for all three seekbars at once. This keeps us from doing extra redraw
     * work by only calling redraw once.
     */
    public void setAllTimestamps(long timestamp, long cropStartTimestamp, long cropEndTimestamp) {
        if (updateActiveTimestamp(timestamp) ||
                updateCropTimestamps(cropStartTimestamp, cropEndTimestamp)) {
            refreshAfterChartLoad(true);
        }
    }

    public long getTimestamp() {
        return mPointData.timestamp;
    }

    public long getCropStartTimestamp() {
        return mCropStartData.timestamp;
    }

    public long getCropEndTimestamp() {
        return mCropEndData.timestamp;
    }

    public void setExternalAxisController(ExternalAxisController externalAxisController) {
        mExternalAxis = externalAxisController;
        mExternalAxis.addAxisUpdateListener(new ExternalAxisController.AxisUpdateListener() {

            @Override
            public void onAxisUpdated(long xMin, long xMax, boolean isPinnedToNow) {
                mCropSeekbarGroup.setMillisecondsInRange(xMax - xMin);
                if (!mChartController.hasDrawnChart()) {
                    return;
                }
                if (mIsCropping && mCropStartData.timestamp != NO_TIMESTAMP_SELECTED &&
                        mCropEndData.timestamp != NO_TIMESTAMP_SELECTED) {
                    redrawCrop(true);
                } else if (mPointData.timestamp != NO_TIMESTAMP_SELECTED) {
                    if (mPointData.timestamp < xMin || mPointData.timestamp > xMax) {
                        mSeekbar.setThumb(null);
                    } else {
                        mSeekbar.setThumb(mThumb);
                    }
                    redrawFromSeekbar(mSeekbar, mPointData, true);
                }
            }
        });
    }

    public void setUnits(String units) {
        if (mSeekbar != null) {
            mSeekbar.setUnits(units);
        }
    }

    public void updateColor(int newColor) {
        mDotPaint.setColor(newColor);
        mPaint.setColor(newColor);
        mCropVerticalLinePaint.setColor(newColor);
    }

    public void setCropModeOn(boolean isCropping) {
        mIsCropping = isCropping;
        if (isCropping) {
            mSeekbar.setVisibility(View.GONE);
            mCropSeekbarGroup.setVisibility(View.VISIBLE);
            setCropTimestamps(mCropStartData.timestamp, mCropEndData.timestamp);
        } else {
            mSeekbar.setVisibility(View.VISIBLE);
            mCropSeekbarGroup.setVisibility(View.GONE);
        }
        refreshAfterChartLoad(true);
    }

    /**
     * Sets the crop timestamps to be at 10% and 90% of the current X axis.
     */
    public void resetCropTimestamps() {
        mCropStartData.timestamp = mExternalAxis.timestampAtAxisFraction(.1);
        mCropEndData.timestamp = mExternalAxis.timestampAtAxisFraction(.9);
    }

    public boolean getIsCropping() {
        return mIsCropping;
    }

    @Override
    public void onChartDataLoaded(long firstTimestamp, long lastTimestamp) {
        if (mPreviouslySelectedTimestamp != -1) {
            setAllTimestamps(mPreviouslySelectedTimestamp, mPreviousCropStartTimestamp,
                    mPreviousCropEndTimestamp);
        }
    }

    @Override
    public void onLoadAttemptStarted(boolean chartHiddenForLoad) {
        if (chartHiddenForLoad) {
            mPreviouslySelectedTimestamp = mPointData.timestamp;
            mPreviousCropStartTimestamp = mCropStartData.timestamp;
            mPreviousCropEndTimestamp = mCropEndData.timestamp;
        } else {
            mPreviouslySelectedTimestamp = -1;
            mPreviousCropStartTimestamp = -1;
            mPreviousCropEndTimestamp = -1;
        }
    }
}
