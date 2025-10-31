/*
 *  Copyright (c) 2021-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioTrackView extends View {

    private final static int BYTES_IN_FLOAT = Float.SIZE / Byte.SIZE;

    private static final float DESIGN_LINE_SPACE = 4f;
    private static final float DESIGN_LINE_WIDTH = 3f;

    private static final int FADE_IN_DURATION_MS = 300; // Duration for the fade-in animation

    // TODO use an existing Executor (Twinlife's ?)
    @NonNull
    private static final ExecutorService BITMAP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BitmapGenerator");
        t.setDaemon(true); // Make sure the executor won't prevent the app from shutting down.
        return t;
    });

    @NonNull
    private static final Handler MAIN_THREAD_HANDLER = new Handler(Looper.getMainLooper());

    private int mWidth = 0;
    private int mHeight = 0;
    @NonNull
    private final Paint mPaint = new Paint();

    private int mBackgroundColor;
    @Nullable
    private Bitmap mTrackBitmap;

    @Nullable
    private AudioTrack mAudioTrack;

    private int mProgress = 0;

    private boolean mIsBitmapGenerating = false;

    private int mBitmapAlpha = 0; // Current alpha for the bitmap (0-255)
    @Nullable
    private ValueAnimator mFadeInAnimator;

    @NonNull
    private Rect mBackgroundRect = new Rect(0, 0, mWidth, mHeight);
    @NonNull
    private Rect mProgressRect = new Rect(0, 0, 0, mHeight);
    @Nullable
    private ColorFilter mProgressColorFilter;


    public AudioTrackView(Context context) {

        super(context);

        init();
    }

    public AudioTrackView(Context context, AttributeSet attrs) {

        super(context, attrs);

        init();
    }

    public AudioTrackView(Context context, AttributeSet attrs, int defStyle) {

        super(context, attrs, defStyle);

        init();
    }

    public void initTrack(@Nullable AudioTrack audioTrack, int backgroundColor, int progressColor) {

        boolean changed = audioTrack != null && (!Objects.equals(mAudioTrack, audioTrack) || mBackgroundColor != backgroundColor);

        mAudioTrack = audioTrack;
        mBackgroundColor = backgroundColor;
        mProgressColorFilter = new PorterDuffColorFilter(progressColor, PorterDuff.Mode.SRC_IN);

        if (changed) {
            generateTrackBitmap();
        } else if (mAudioTrack == null) {
            mTrackBitmap = null;
            invalidate();
        }
    }

    public void setProgress(int progress) {

        mProgress = progress;

        mBackgroundRect = new Rect(mProgress, 0, mWidth, mHeight);
        mProgressRect = new Rect(0, 0, mProgress, mHeight);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);

        boolean changed = width != mWidth || height != mHeight;

        mWidth = width;
        mHeight = height;

        mBackgroundRect = new Rect(mProgress, 0, mWidth, mHeight);
        mProgressRect = new Rect(0, 0, mProgress, mHeight);

        if (changed && mWidth > 0 && mHeight > 0) {
            mTrackBitmap = null;
            generateTrackBitmap();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (mTrackBitmap != null && !mIsBitmapGenerating) {
            canvas.drawBitmap(mTrackBitmap, mBackgroundRect, mBackgroundRect, mPaint);


            if (mProgress > 0 && mProgressColorFilter != null) {
                mPaint.setColorFilter(mProgressColorFilter);
                canvas.drawBitmap(mTrackBitmap, mProgressRect, mProgressRect, mPaint);
                mPaint.setColorFilter(null);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mFadeInAnimator != null && mFadeInAnimator.isRunning()) {
            mFadeInAnimator.cancel();
        }
    }

    //
    // Private Methods
    //

    private void init() {

        setWillNotDraw(false);
        mPaint.setAntiAlias(true);
    }

    private void generateTrackBitmap() {
        mIsBitmapGenerating = true;

        final AudioTrack audioTrack = mAudioTrack;
        final int width = mWidth;
        final int height = mHeight;

        mBitmapAlpha = 0; // Reset alpha for new bitmap, ensure it starts transparent
        mPaint.setAlpha(mBitmapAlpha);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(DESIGN_LINE_WIDTH);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setColor(mBackgroundColor);
        final Paint paint = new Paint(mPaint);

        BITMAP_EXECUTOR.execute(() -> {
                if (audioTrack != null && audioTrack.getBytes() != null && audioTrack.getBytes().length > 0 && width > 0 && height > 0) {
                    Bitmap.Config conf = Bitmap.Config.ARGB_8888;
                    Bitmap trackBitmap = Bitmap.createBitmap(width, height, conf);
                    Canvas canvasTrack = new Canvas(trackBitmap);

                    float startX = 1;

                    float[] linesValue = toFloatArray(audioTrack.getBytes());
                    for (float lineByte : linesValue) {
                        float lineHeight = lineByte * height;
                        if (lineHeight <= 1.0) {
                            lineHeight = 1;
                        } else if (lineHeight > height) {
                            lineHeight = height;
                        }
                        float startY = (height - lineHeight) / 2;
                        canvasTrack.drawLine(startX, startY, startX, startY + lineHeight, paint);
                        startX += DESIGN_LINE_SPACE;
                    }

                    MAIN_THREAD_HANDLER.post(() -> {
                        mTrackBitmap = trackBitmap;
                        mIsBitmapGenerating = false;
                        startFadeInAnimation();
                    });
                }
        });
    }

    private void startFadeInAnimation() {
        // Cancel any existing animation
        if (mFadeInAnimator != null && mFadeInAnimator.isRunning()) {
            mFadeInAnimator.cancel();
        }

        mBitmapAlpha = 0; // Ensure it starts transparent for the animation
        mPaint.setAlpha(mBitmapAlpha);

        mFadeInAnimator = ValueAnimator.ofInt(0, 255);
        mFadeInAnimator.setDuration(FADE_IN_DURATION_MS);
        mFadeInAnimator.setInterpolator(new AccelerateDecelerateInterpolator()); // Smooth animation

        mFadeInAnimator.addUpdateListener(animation -> {
            mBitmapAlpha = (Integer) animation.getAnimatedValue();
            mPaint.setAlpha(mBitmapAlpha); // Update paint's alpha
            invalidate(); // Redraw with new alpha
        });
        mFadeInAnimator.start();
    }

    @NonNull
    private float[] toFloatArray(@NonNull byte[] byteArray) {

        float[] result = new float[byteArray.length / BYTES_IN_FLOAT];
        ByteBuffer.wrap(byteArray).asFloatBuffer().get(result, 0, result.length);
        return result;
    }
}
