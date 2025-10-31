/*
 *  Copyright (c) 2021 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class EphemeralView extends View {

    private static final int NB_DASHES = 8;
    private static final float DESIGN_DASH_PERCENT = 0.75f;
    private static final float DESIGN_BLANK_PERCENT = 0.25f;
    private static final float DESIGN_STROKE_WIDTH = 4f;
    private static final float DESIGN_START_ANGLE = 270f;

    private int mWidth = 0;
    private int mHeight = 0;
    private RectF mRectF;
    private Paint mPaint;

    private float mPercent = 1f;

    public EphemeralView(Context context) {

        super(context);

        init();
    }

    public EphemeralView(Context context, AttributeSet attrs) {

        super(context, attrs);

        init();
    }

    public EphemeralView(Context context, AttributeSet attrs, int defStyle) {

        super(context, attrs, defStyle);

        init();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {

        mWidth = width;
        mHeight = height;

        mRectF = new RectF(0, 0, mWidth, mHeight);

        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas) {

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(DESIGN_STROKE_WIDTH);
        mPaint.setStrokeJoin(Paint.Join.ROUND);

        double perimeter = 2 * Math.PI * (mHeight * 0.5);
        float stepSize = (float) (perimeter / NB_DASHES);
        float[] intervals = new float[]{stepSize * DESIGN_DASH_PERCENT, stepSize * DESIGN_BLANK_PERCENT};

        mPaint.setPathEffect(new DashPathEffect(intervals, 0));
        canvas.drawCircle((float) (mWidth * 0.5), (float) (mHeight * 0.5), (float) (mHeight * 0.5), mPaint);

        intervals = new float[]{(float) (mHeight * 0.5) - DESIGN_STROKE_WIDTH, DESIGN_STROKE_WIDTH};

        mPaint.setPathEffect(new DashPathEffect(intervals, 0));
        canvas.drawLine((float) (mWidth * 0.5), (float) (mHeight * 0.5), (float) (mWidth * 0.5), 0, mPaint);

        float degrees = 360 * mPercent;
        float radians = (float) ((degrees * Math.PI) / 180.0);
        float startAngleLine = (float) (3 * Math.PI * 0.5);
        float endAngleLine = startAngleLine + radians;

        mPaint.setPathEffect(null);
        canvas.drawArc(mRectF, DESIGN_START_ANGLE, degrees, false, mPaint);

        float endX = (float) ((mWidth * 0.5) + ((mWidth * 0.5) * Math.cos(endAngleLine)));
        float endY = (float) ((mHeight * 0.5) + ((mHeight * 0.5) * Math.sin(endAngleLine)));

        mPaint.setPathEffect(new DashPathEffect(intervals, 0));
        canvas.drawLine((float) (mWidth * 0.5), (float) (mHeight * 0.5), endX, endY, mPaint);

        super.onDraw(canvas);
    }

    public void setColor(int color) {

        mPaint.setColor(color);
    }

    public void updateWithProgress(float percent) {

        mPercent = percent;
        invalidate();
    }

    //
    // Private Methods
    //

    private void init() {

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
    }
}