/*
 *  Copyright (c) 2014-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Thibaud David (contact@thibauddavid.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.transition.Transition;

public class AvatarView extends View {
    private static final String LOG_TAG = "AvatarView";
    private static final boolean DEBUG = false;

    private float mWidth;
    private float mHeight;
    private float mImageWidth;
    private float mImageHeight;
    private RectF mRectF;
    private Paint mPaint;
    private Paint mPaintBackground;
    private Paint mPaintBorder;

    private int mColorFilter;

    public AvatarView(Context context) {

        super(context);
    }

    public AvatarView(Context context, AttributeSet attrs) {

        super(context, attrs);
    }

    public AvatarView(Context context, AttributeSet attrs, int defStyle) {

        super(context, attrs, defStyle);
    }

    @NonNull
    public CustomViewTarget<AvatarView, Bitmap> getTarget(int bgColor, float borderWidth, int color) {

        return new AvatarViewTarget(this, bgColor, borderWidth, color);
    }

    @NonNull
    public CustomViewTarget<AvatarView, Bitmap> getTarget() {

        return new AvatarViewTarget(this, Color.TRANSPARENT, 0f, Color.TRANSPARENT);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {

        if (mPaint != null) {
            canvas.save();

            float scaleX = mWidth / mImageWidth;
            float scaleY = mHeight / mImageHeight;
            float scale = Math.min(scaleX, scaleY);
            canvas.translate((mWidth - mImageWidth * scale) * 0.5f, (mHeight - mImageHeight * scale) * 0.5f);
            canvas.scale(scale, scale);
            if (mPaintBackground != null) {
                canvas.drawOval(mRectF, mPaintBackground);
            }
            if (mPaintBorder != null) {
                canvas.drawArc(mRectF, 360, 360, false, mPaintBorder);
            }
            canvas.drawOval(mRectF, mPaint);

            canvas.restore();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {

        super.onSizeChanged(width, height, oldWidth, oldHeight);

        mWidth = width;
        mHeight = height;
    }

    public void setImageBitmap(Bitmap bitmap) {

        setImageBitmap(bitmap, Color.TRANSPARENT, 0f, Color.TRANSPARENT);
    }

    public void setColorFilter(int color) {

        mColorFilter = color;
    }

    @SuppressWarnings({"SameParameterValue", "WeakerAccess"})
    public void setImageBitmap(Bitmap bitmap, int bgColor, float borderWidth, int color) {

        borderWidth = borderWidth * 3 / 4;
        if (bitmap != null) {
            mImageWidth = bitmap.getWidth();
            mImageHeight = bitmap.getHeight();
            if (borderWidth > 0f) {
                mRectF = new RectF(borderWidth * 0.5f, borderWidth * 0.5f, mImageWidth - borderWidth * 0.5f, mImageHeight - borderWidth * 0.5f);
            } else {
                mRectF = new RectF(0f, 0f, mImageWidth, mImageHeight);
            }
            if (bitmap.hasAlpha()) {
                mPaintBackground = new Paint();
                mPaintBackground.setAntiAlias(true);
                mPaintBackground.setStyle(Paint.Style.FILL);
                mPaintBackground.setColor(bgColor);
            }
            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setDither(true);
            mPaint.setShader(new BitmapShader(bitmap, BitmapShader.TileMode.CLAMP, BitmapShader.TileMode.CLAMP));

            if (mColorFilter != 0) {
                mPaint.setColorFilter(new PorterDuffColorFilter(mColorFilter, PorterDuff.Mode.SRC_IN));
            }

            if (borderWidth > 0f) {
                mPaintBorder = new Paint();
                mPaintBorder.setAntiAlias(true);
                mPaintBorder.setStyle(Paint.Style.STROKE);
                mPaintBorder.setStrokeWidth(borderWidth);
                mPaintBorder.setColor(color);
            }

            invalidate();
        }
    }

    private static final class AvatarViewTarget extends CustomViewTarget<AvatarView, Bitmap> {
        private final int bgColor;
        private final float borderWidth;
        private final int color;

        public AvatarViewTarget(@NonNull AvatarView view, int bgColor, float borderWidth, int color) {
            super(view);

            this.bgColor = bgColor;
            this.borderWidth = borderWidth;
            this.color = color;
        }

        /**
         * A required callback invoked when the resource is no longer valid and must be freed.
         *
         * <p>You must ensure that any current Drawable received in onResourceReady(Object,
         * Transition) is no longer used before redrawing the container (usually a View) or changing its
         * visibility. <b>Not doing so will result in crashes in your app.</b>
         *
         * @param placeholder The placeholder drawable to optionally show, or null.
         */
        @Override
        protected void onResourceCleared(@Nullable Drawable placeholder) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onResourceCleared view=" + this.view);
            }

            this.view.setImageBitmap(null, bgColor, borderWidth, color);
        }

        /**
         * A <b>mandatory</b> lifecycle callback that is called when a load fails.
         *
         * <p>Note - This may be called before {@link #onLoadStarted(Drawable) }
         * if the model object is null.
         *
         * <p>You <b>must</b> ensure that any current Drawable received in onResourceReady(Object,
         * Transition) is no longer used before redrawing the container (usually a View) or changing its
         * visibility.
         *
         * @param errorDrawable The error drawable to optionally show, or null.
         */
        @Override
        public void onLoadFailed(@Nullable Drawable errorDrawable) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onLoadFailed view=" + this.view);
            }

            Log.e(LOG_TAG, "Loading image descriptor failed");
            this.view.setImageBitmap(null, bgColor, borderWidth, color);
        }

        /**
         * The method that will be called when the resource load has finished.
         *
         * @param resource   the loaded resource.
         * @param transition
         */
        @Override
        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {

            this.view.setImageBitmap(resource, bgColor, borderWidth, color);
        }
    }
}
