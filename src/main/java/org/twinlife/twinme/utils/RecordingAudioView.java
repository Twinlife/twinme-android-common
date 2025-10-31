/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RecordingAudioView extends View {

    private Paint mPaint;
    private int mViewWidth;
    private int mViewHeight;

    private final List<Rect> mRectList = new ArrayList<>();
    private Handler mHandler;
    private Runnable mRunnable;

    public RecordingAudioView(Context context) {

        super(context);
    }

    public RecordingAudioView(Context context, AttributeSet attrs) {

        super(context, attrs);

    }

    public RecordingAudioView(Context context, AttributeSet attrs, int defStyle) {

        super(context, attrs, defStyle);
    }

    @Override
    protected void onSizeChanged(int xNew, int yNew, int xOld, int yOld) {
        super.onSizeChanged(xNew, yNew, xOld, yOld);

        mViewWidth = xNew;
        mViewHeight = yNew;

        mHandler = new Handler();

        mRunnable = () -> {
            createWaves();
            invalidate();

            mHandler.postDelayed(mRunnable, 250);
        };
        mHandler.post(mRunnable);

    }

    @Override
    protected void onDraw(Canvas canvas) {

        canvas.drawColor(Color.TRANSPARENT);

        for (Rect r : mRectList) {

            canvas.drawRect(r, mPaint);
        }
    }

    private void createWaves() {
        int x = 2;
        int y;

        final Random r = new Random();
        mRectList.clear();
        while (x < mViewWidth) {
            int randomRectHeight = r.nextInt(7 - 1) + 1;

            float max = 100;
            if (x < (mViewWidth / 2)) {
                max = (((max / (float) (mViewWidth / 2)) * (float) (x - (mViewWidth / 2))) + max) * 0.001f;
            } else if (x > (mViewWidth / 2)) {
                max = (((max / ((float) (mViewWidth / 2)) * (float) ((mViewWidth / 2) - x))) + max) * 0.001f;
            }

            float rectWidth = mViewWidth * 0.005f;
            float space = mViewWidth * 0.008f;

            float rectHeight = 0.40f * (mViewWidth * randomRectHeight * max);
            y = (int) (((float) mViewHeight / 2) - (rectHeight / 2));

            Rect rect = new Rect(x, y, (int) rectWidth + x, (int) (y + rectHeight));
            mRectList.add(rect);
            x += (int) (rectWidth + space);
        }

        mPaint = new Paint();
        mPaint.setColor(Color.rgb(229, 227, 255));

    }
}
