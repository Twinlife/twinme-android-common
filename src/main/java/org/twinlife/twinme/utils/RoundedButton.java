/*
 *  Copyright (c) 2015-2017 twinlife SA.
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
import android.widget.Button;

import org.twinlife.twinme.skin.GradientDescriptor;

@SuppressLint("AppCompatCustomView")
public class RoundedButton extends Button {

    @SuppressWarnings("FieldCanBeLocal")
    private int mWidth = 0;
    private int mHeight = 0;
    private RectF mRectF;
    private Paint mPaint;
    private Paint mPaintBorder;
    private final float mBorderThickness = 0f;
    private int mColor;
    private int mColorPressed;
    private int mTextColor;
    private int mTextColorPressed;
    private GradientDescriptor mGradientDescriptor;
    private LinearGradient mGradient;
    private LinearGradient mGradientPressed;

    public RoundedButton(Context context) {

        super(context);

        init();
    }

    public RoundedButton(Context context, AttributeSet attrs) {

        super(context, attrs);

        init();
    }

    public void setTextColors(int colorNormal, int colorPressed) {

        mTextColor = colorNormal;
        mTextColorPressed = colorPressed;
        setTextColor(mTextColor);
    }

    public void setColors(int colorNormal, int colorPressed) {

        mColor = colorNormal;
        mColorPressed = colorPressed;
        mPaint.setColor(mColor);
    }

    public void setGradient(final GradientDescriptor gradientDescriptor) {

        mGradientDescriptor = gradientDescriptor;
        updateGradients();
    }

    //
    // Override View Methods
    //

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {

        mWidth = width;
        mHeight = height;
        mRectF = new RectF(mBorderThickness, mBorderThickness, mWidth - mBorderThickness, mHeight - mBorderThickness);

        updateGradients();

        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {

        canvas.drawRoundRect(mRectF, mHeight * 0.5f, mHeight * 0.5f, mPaint);
        //noinspection ConstantConditions
        if (mBorderThickness > 0f) {
            canvas.drawRoundRect(mRectF, mHeight * 0.5f, mHeight * 0.5f, mPaintBorder);
        }

        super.onDraw(canvas);
    }

    @Override
    public void setPressed(boolean pressed) {

        if (mGradient != null) {
            mPaint.setShader(pressed ? mGradientPressed : mGradient);
        } else {
            mPaint.setColor(pressed ? mColorPressed : mColor);
        }
        setTextColor(pressed ? mTextColorPressed : mTextColor);
        invalidate();

        super.setPressed(pressed);
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
        if (mBorderThickness > 0f) {
            mPaintBorder.setStrokeWidth(mBorderThickness);
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
