/*
 *  Copyright (c) 2016-2017 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Tristan Garaud (Tristan.Garaud@twinlife-systems.com)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Thibaud David (contact@thibauddavid.com)
 */

package org.twinlife.twinme.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.ImageButton;

import org.twinlife.twinme.skin.GradientDescriptor;

@SuppressLint("AppCompatCustomView")
public class RoundedImageButton extends ImageButton {

    @SuppressWarnings("FieldCanBeLocal")
    private int mWidth = 0;
    private int mHeight = 0;
    private RectF mRectF;
    private Paint mPaint;
    private Paint mPaintBorder;
    private final float mBorderWidth = 0f;
    private GradientDescriptor mGradientDescriptor;
    private LinearGradient mGradient;
    private LinearGradient mGradientPressed;

    public RoundedImageButton(Context context) {

        super(context);

        init();
    }

    public RoundedImageButton(Context context, AttributeSet attrs) {

        super(context, attrs);

        init();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {

        mWidth = width;
        mHeight = height;
        //noinspection SuspiciousNameCombination
        mRectF = new RectF(mBorderWidth, mBorderWidth, mWidth - mBorderWidth, mHeight - mBorderWidth);
        updateGradients();

        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {

        canvas.drawRoundRect(mRectF, mHeight * 0.5f, mHeight * 0.5f, mPaint);
        //noinspection ConstantConditions
        if (mBorderWidth > 0f) {
            canvas.drawRoundRect(mRectF, mHeight * 0.5f, mHeight * 0.5f, mPaintBorder);
        }

        updateGradients();

        super.onDraw(canvas);
    }

    @Override
    public void setPressed(boolean pressed) {

        if (mGradient != null && mGradientPressed != null) {
            mPaint.setShader(pressed ? mGradientPressed : mGradient);
        }
        invalidate();

        super.setPressed(pressed);
    }

    public void setGradient(final GradientDescriptor gradientDescriptor) {

        mGradientDescriptor = gradientDescriptor;
        updateGradients();
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
        //noinspection ConstantConditions
        if (mBorderWidth > 0f) {
            mPaintBorder.setStrokeWidth(mBorderWidth);
        }
    }

    private void updateGradients() {

        if (mGradientDescriptor != null) {
            mGradient = new LinearGradient(0, 0, 0, mHeight, mGradientDescriptor.fromColor, mGradientDescriptor.toColor, Shader.TileMode.REPEAT);
            mGradientPressed = new LinearGradient(0, 0, 0, mHeight, mGradientDescriptor.fromColorPressed, mGradientDescriptor.toColorPressed, Shader.TileMode.REPEAT);
            mPaint.setShader(mGradient);
            invalidate();
        }
    }
}
