/*
 *  Copyright (c) 2014-2019 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import org.twinlife.twinme.skin.CircularImageDescriptor;
import org.twinlife.twinme.skin.CircularShadowDescriptor;

/*
    Shadow, if present, is clipped to the view bounds
 */

public class CircularImageView extends View {
    private static final String LOG_TAG = "CircularImageView";
    private static final boolean DEBUG = false;

    private float mShadowImageCenterX;
    private float mShadowImageCenterY;
    private float mShadowImageRadius;
    private float mImageCenterX;
    private float mImageCenterY;
    private float mImageRadius;
    private float mBorderThickness;
    private float mBorderInset;

    private boolean mHasShadow = false;
    private RectF mShadowRect;
    private Paint mShadowPaint;
    private float mShadowScale;
    private float mShadowTranslateX;
    private float mShadowTranslateY;
    private boolean mHasImage = false;
    private RectF mImageRect;
    private Paint mImagePaint;
    private boolean mHasBorder = false;
    private RectF mBorderRect;
    private Paint mBorderPaint;
    private float mImageScale;
    private float mImageTranslateX;
    private float mImageTranslateY;
    private float mBorderScale;
    private float mBorderTranslateX;
    private float mBorderTranslateY;

    private int mColorFilter;

    public CircularImageView(Context context) {

        super(context);
    }

    public CircularImageView(Context context, AttributeSet attrs) {

        super(context, attrs);
    }

    public CircularImageView(Context context, AttributeSet attrs, int defStyle) {

        super(context, attrs, defStyle);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {

        if (!mHasImage) {

            return;
        }

        if (mHasShadow) {
            canvas.save();
            canvas.translate(mShadowTranslateX, mShadowTranslateY);
            canvas.scale(mShadowScale, mShadowScale);
            canvas.drawRect(mShadowRect, mShadowPaint);
            canvas.restore();
        }

        if (mHasBorder) {
            canvas.save();
            canvas.translate(mImageTranslateX, mImageTranslateY);
            canvas.scale(mImageScale, mImageScale);
            canvas.drawOval(mImageRect, mImagePaint);
            canvas.restore();

            canvas.save();
            canvas.translate(mBorderTranslateX, mBorderTranslateY);
            canvas.scale(mBorderScale, mBorderScale);
            mBorderPaint.setStrokeWidth(getWidth() * mBorderThickness / mBorderScale);
            canvas.drawArc(mBorderRect, 360, 360, false, mBorderPaint);
            canvas.restore();
        } else {
            canvas.save();
            canvas.translate(mImageTranslateX, mImageTranslateY);
            canvas.scale(mImageScale, mImageScale);
            canvas.drawOval(mImageRect, mImagePaint);
            canvas.restore();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {

        super.onSizeChanged(width, height, oldWidth, oldHeight);

        if (mHasImage) {
            update();
        }
    }

    public void setColorFilter(int color) {

        mColorFilter = color;
    }

    public void setImage(Context context, CircularShadowDescriptor shadowDescriptor, CircularImageDescriptor imageDescriptor) {

        if (imageDescriptor == null || imageDescriptor.image == null) {

            return;
        }

        if (shadowDescriptor != null) {
            mShadowImageCenterX = Math.max(Math.min(shadowDescriptor.imageCenterX, 1), 0);
            mShadowImageCenterY = Math.max(Math.min(shadowDescriptor.imageCenterY, 1), 0);
            mShadowImageRadius = Math.max(Math.min(shadowDescriptor.imageRadius, 0.5f), 0);

            try {
                Bitmap shadowBitmap = BitmapFactory.decodeResource(context.getResources(), shadowDescriptor.shadow);
                if (shadowBitmap != null) {
                    mShadowRect = new RectF(0f, 0f, shadowBitmap.getWidth(), shadowBitmap.getHeight());
                    mShadowPaint = new Paint();
                    mShadowPaint.setAntiAlias(true);
                    mShadowPaint.setDither(true);
                    mShadowPaint.setShader(new BitmapShader(shadowBitmap, BitmapShader.TileMode.CLAMP, BitmapShader.TileMode.CLAMP));
                    mHasShadow = true;
                }
            } catch (OutOfMemoryError ex) {
                Log.w(LOG_TAG, "Not enough memory for shadow");
                mShadowRect = null;
                mShadowPaint = null;
                mHasShadow = false;
            }
        } else {
            mShadowRect = null;
            mShadowPaint = null;
            mHasShadow = false;
        }

        mImageCenterX = Math.max(Math.min(imageDescriptor.centerX, 1), 0);
        mImageCenterY = Math.max(Math.min(imageDescriptor.centerY, 1), 0);
        mImageRadius = Math.max(Math.min(imageDescriptor.radius, 0.5f), 0);

        mImageRect = new RectF(0, 0, imageDescriptor.image.getWidth(), imageDescriptor.image.getHeight());

        mImagePaint = new Paint();
        mImagePaint.setAntiAlias(true);
        mImagePaint.setDither(true);
        mImagePaint.setShader(new BitmapShader(imageDescriptor.image, BitmapShader.TileMode.CLAMP, BitmapShader.TileMode.CLAMP));

        if (mColorFilter != 0) {
            mImagePaint.setColorFilter(new PorterDuffColorFilter(mColorFilter, PorterDuff.Mode.SRC_IN));
        }

        mHasImage = true;

        if (imageDescriptor.hasBorder) {
            mBorderThickness = Math.max(Math.min(imageDescriptor.borderThickness, mImageRadius), 0);
            mBorderInset = Math.max(Math.min(imageDescriptor.borderInset, mImageRadius - mBorderThickness), 0);
            mBorderRect = new RectF(0, 0, 128, 128);
            mBorderPaint = new Paint();
            mBorderPaint.setAntiAlias(true);
            mBorderPaint.setStyle(Paint.Style.STROKE);
            mBorderPaint.setColor(imageDescriptor.borderColor);
            mHasBorder = true;
        } else {
            mBorderRect = null;
            mBorderPaint = null;
            mHasBorder = false;
        }

        if (getWidth() > 0 && getHeight() > 0) {
            update();
        }

        invalidate();
    }

    public void dispose() {

        mShadowPaint = null;
        mImagePaint = null;
        mBorderPaint = null;
        mImageRect = null;
        mHasImage = false;
    }

    //
    // Private methods
    //

    private void update() {

        if (DEBUG) {
            Log.d(LOG_TAG, "onSizeChanged:");
            Log.d(LOG_TAG, " view width=" + getWidth() + " height=" + getWidth());
            Log.d(LOG_TAG, " image radius=" + mImageRadius + " centerX=" + mImageCenterX + " centerY=" + mImageCenterY);
            Log.d(LOG_TAG, " image width=" + mImageRect.width() + " height=" + mImageRect.height());
            Log.d(LOG_TAG, " image%view width=" + getWidth() * mImageRadius * 2 + " height=" + getHeight() * mImageRadius * 2);
        }

        if (mHasShadow) {
            float scaleX = (getWidth() * mImageRadius) / (mShadowImageRadius * mShadowRect.width());
            float scaleY = (getHeight() * mImageRadius) / (mShadowImageRadius * mShadowRect.height());
            mShadowScale = Math.min(scaleX, scaleY);
            mShadowTranslateX = getWidth() * mImageCenterX - mShadowRect.width() * mShadowImageCenterX * mShadowScale;
            mShadowTranslateY = getHeight() * mImageCenterY - mShadowRect.height() * mShadowImageCenterY * mShadowScale;
            float deltaLeft = mShadowTranslateX;
            float deltaRight = mShadowRect.width() * mShadowScale + mShadowTranslateX - getWidth();
            float deltaTop = mShadowTranslateY;
            float deltaBottom = mShadowRect.height() * mShadowScale + mShadowTranslateY - getHeight();
            int shadowDeltaX = (int) Math.ceil(Math.max(deltaLeft < 0 ? -deltaLeft : 0, deltaRight));
            int shadowDeltaY = (int) Math.ceil(Math.max(deltaTop < 0 ? -deltaTop : 0f, deltaBottom));
            if (shadowDeltaX > 0 || shadowDeltaY > 0) {
                if (DEBUG) {
                    Log.w(LOG_TAG, " Shadow clipping: shadowDeltaX=" + shadowDeltaX + " shadowDeltaY=" + shadowDeltaY);
                }
            }
            if (DEBUG) {
                Log.d(LOG_TAG, " shadow width=" + mShadowRect.width() + " height=" + mShadowRect.width());
                Log.d(LOG_TAG, " shadow radius=" + mShadowImageRadius + " centerX=" + mShadowImageCenterX + " centerY=" + mShadowImageCenterY);
                Log.d(LOG_TAG, " image%shadow width=" + mShadowRect.width() * mShadowScale * mShadowImageRadius * 2 + " height=" + mShadowRect.width() * mShadowScale * mShadowImageRadius * 2);
                Log.d(LOG_TAG, " shadow translateX=" + mShadowTranslateX + " translateY=" + mShadowTranslateY);
                Log.d(LOG_TAG, " shadow deltaX=" + shadowDeltaX + " deltaY=" + shadowDeltaY);
            }
        }

        float imageRadius;
        if (mHasBorder) {
            imageRadius = mImageRadius - mBorderThickness - mBorderInset;

            float borderRadius = mImageRadius - mBorderThickness;
            float borderScaleX = getWidth() * borderRadius * 2 / mBorderRect.width();
            float borderScaleY = getHeight() * borderRadius * 2 / mBorderRect.height();
            mBorderScale = Math.min(borderScaleX, borderScaleY);
            mBorderTranslateX = getWidth() * mImageCenterX - mBorderRect.width() * mBorderScale * 0.5f;
            mBorderTranslateY = getHeight() * mImageCenterY - mBorderRect.height() * mBorderScale * 0.5f;
        } else {
            imageRadius = mImageRadius;
        }
        float imageScaleX = getWidth() * imageRadius * 2 / mImageRect.width();
        float imageScaleY = getHeight() * imageRadius * 2 / mImageRect.height();
        mImageScale = Math.min(imageScaleX, imageScaleY);
        mImageTranslateX = getWidth() * mImageCenterX - mImageRect.width() * mImageScale * 0.5f;
        mImageTranslateY = getHeight() * mImageCenterY - mImageRect.height() * mImageScale * 0.5f;
    }
}
