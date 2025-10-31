/*
 *  Copyright (c) 2016-2020 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class RoundedView extends View {

    @SuppressWarnings("FieldCanBeLocal")
    private int mWidth = 0;
    private int mHeight = 0;
    private RectF mRectF;
    private Paint mPaint;
    private Paint mPaintBorder;
    private float mBorderWidth = 0f;

    public RoundedView(Context context) {

        super(context);

        init();
    }

    public RoundedView(Context context, AttributeSet attrs) {

        super(context, attrs);

        init();
    }

    public RoundedView(Context context, AttributeSet attrs, int defStyle) {

        super(context, attrs, defStyle);

        init();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {

        mWidth = width;
        mHeight = height;
        //noinspection SuspiciousNameCombination
        mRectF = new RectF(mBorderWidth, mBorderWidth, mWidth - mBorderWidth, mHeight - mBorderWidth);

        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {

        canvas.drawRoundRect(mRectF, mHeight * 0.5f, mHeight * 0.5f, mPaint);
        if (mBorderWidth > 0f) {
            canvas.drawRoundRect(mRectF, mHeight * 0.5f, mHeight * 0.5f, mPaintBorder);
        }

        super.onDraw(canvas);
    }

    public void setColor(int color) {

        mPaint.setColor(color);
    }

    //
    // Private Methods
    //

    private void init() {

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaintBorder = new Paint();
        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setAntiAlias(true);
        if (mBorderWidth > 0f) {
            mPaintBorder.setStrokeWidth(mBorderWidth);
        }
    }

    public void setBorder(float borderWidth, int color) {

        mBorderWidth = borderWidth;

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaintBorder = new Paint();
        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setAntiAlias(true);
        if (mBorderWidth > 0f) {
            mPaintBorder.setStrokeWidth(mBorderWidth);
            mPaintBorder.setColor(color);
        }

        invalidate();
    }
}
