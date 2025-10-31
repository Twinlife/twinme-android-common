/*
 *  Copyright (c) 2016-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributor: Houssem Temanni (Houssem.Temanni@twinlife-systems.com)
 *  Contributor: Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.percentlayout.widget.PercentRelativeLayout;

import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.transition.Transition;

import java.util.Arrays;

public class RoundedImageView extends AppCompatImageView {
    private static final String LOG_TAG = "RoundedImageView";
    private static final boolean DEBUG = false;

    private int mWidth = 0;
    private int mHeight = 0;
    private float mImageWidth = 0f;
    private float mImageHeight = 0f;
    private float mScale = 1f;
    private Paint mPaint;
    private Bitmap mBitmap;
    private float[] mCornerRadii;
    private RoundRectShape mRoundShape;
    private final Path mRoundedPath;
    private final RectF mRect;

    public RoundedImageView(Context context) {

        super(context);

        mWidth = getWidth();
        mHeight = getHeight();
        mBitmap = null;
        mRoundedPath = new Path();
        mRect = new RectF(0, 0, 0, 0);
    }

    public RoundedImageView(Context context, AttributeSet attrs) {

        super(context, attrs);
        mRoundedPath = new Path();
        mRect = new RectF(0, 0, 0, 0);
    }

    public RoundedImageView(Context context, AttributeSet attrs, int defStyle) {

        super(context, attrs, defStyle);
        mRoundedPath = new Path();
        mRect = new RectF(0, 0, 0, 0);
    }

    public void setCornerRadii(float[] cornerRadii) {

        mCornerRadii = Arrays.copyOf(cornerRadii, cornerRadii.length);
    }

    public void setImageBitmap(Bitmap bitmap, float[] cornerRadii) {

        if (bitmap != null && cornerRadii != null) {
            mBitmap = bitmap;
            mCornerRadii = Arrays.copyOf(cornerRadii, cornerRadii.length);
            mImageWidth = mBitmap.getWidth();
            mImageHeight = mBitmap.getHeight();

            BitmapShader shader = new BitmapShader(mBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setAntiAlias(true);
            mPaint.setShader(shader);
            mRoundShape = new RoundRectShape(mCornerRadii, null, null);
            mRoundShape.resize(mImageWidth, mImageHeight);

            scaleRoundShape();

            invalidate();
        } else {
            mBitmap = null;
            mCornerRadii = null;
            mPaint = null;
        }
    }

    @NonNull
    public CustomViewTarget<RoundedImageView, Bitmap>  getTarget(float[] cornerRadii) {

        return new RoundedImageViewTarget(this, cornerRadii);
    }

    //
    // Override View methods
    //

    @Override
    protected void onDraw(@NonNull Canvas canvas) {

        if (mPaint != null) {
            canvas.save();

            canvas.translate((mWidth - mImageWidth * mScale) * 0.5f, (mHeight - mImageHeight * mScale) * 0.5f);
            canvas.scale(mScale, mScale);
            mRoundShape.draw(canvas, mPaint);

            canvas.restore();

        } else {
            mRoundedPath.reset();
            mRect.bottom = getHeight();
            mRect.right = getWidth();
            if (mCornerRadii != null) {
                mRoundedPath.addRoundRect(mRect, mCornerRadii, Path.Direction.CW);
                mRoundedPath.close();
                canvas.clipPath(mRoundedPath);
            }
            super.onDraw(canvas);
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {

        super.onSizeChanged(width, height, oldWidth, oldHeight);

        mWidth = width;
        mHeight = height;

        scaleRoundShape();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        float aspectRatio = ((PercentRelativeLayout.LayoutParams) getLayoutParams()).getPercentLayoutInfo().aspectRatio;

        if (aspectRatio != 0) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (int) (width / aspectRatio);
            int newHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);

            super.onMeasure(widthMeasureSpec, newHeightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    //
    // Private methods
    //

    private void scaleRoundShape() {

        if (mWidth > 0 && mHeight > 0 && mImageWidth > 0 && mImageHeight > 0 && mCornerRadii != null) {
            float scaleX = mWidth / mImageWidth;
            float scaleY = mHeight / mImageHeight;
            mScale = Math.min(scaleX, scaleY);

            int length = mCornerRadii.length;
            float[] cornerRadii = new float[length];
            for (int i = 0; i < length; i++) {
                cornerRadii[i] = mCornerRadii[i] / mScale;
            }
            mRoundShape = new RoundRectShape(cornerRadii, null, null);
            mRoundShape.resize(mImageWidth, mImageHeight);
        }
    }

    private static final class RoundedImageViewTarget extends CustomViewTarget<RoundedImageView, Bitmap> {
        final float[] cornerRadii;

        public RoundedImageViewTarget(RoundedImageView view, final float[] cornerRadii) {
            super(view);

            this.cornerRadii = cornerRadii;
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

            this.view.setImageBitmap(null, null);
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
            this.view.setImageBitmap(null, null);
        }

        /**
         * The method that will be called when the resource load has finished.
         *
         * @param resource   the loaded resource.
         * @param transition
         */
        @Override
        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {

            this.view.setImageBitmap(resource, this.cornerRadii);
        }
    }
}
