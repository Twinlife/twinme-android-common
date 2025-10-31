/*
 *  Copyright (c) 2016-2020 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Denis Campredon (Denis.Campredon@twinlife-systems.com)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

public class SliderLayout extends RelativeLayout {

    private View mFrontView = null;
    private boolean mIsRTL;
    private float mOffsetX = 0;
    private boolean mOpen = false;

    public SliderLayout(Context context) {

        super(context);

        mIsRTL = CommonUtils.isLayoutDirectionRTL();

        setWillNotDraw(false);
    }

    public SliderLayout(Context context, AttributeSet attrs) {

        super(context, attrs);

        mIsRTL = CommonUtils.isLayoutDirectionRTL();

        setWillNotDraw(false);
    }

    public boolean getRightToLeft() {

        return mIsRTL;
    }

    public void setRightToLeft(boolean rtl) {

        mIsRTL = rtl;
    }

    public void setFrontView(View frontView) {

        mFrontView = frontView;
        mOpen = false;
    }

    public boolean isOpen() {

        return mOpen;
    }

    public float getOffsetX() {

        return mOffsetX;
    }

    public void slideX(float offsetX) {

        if (mIsRTL) {
            mOffsetX -= offsetX;
            if (mOffsetX < 0) {
                mOffsetX = 0;
            } else if (mOffsetX > getWidth()) {
                mOffsetX = getWidth();
            }
        } else {
            mOffsetX -= offsetX;
            if (mOffsetX > 0) {
                mOffsetX = 0;
            } else if (mOffsetX < -getWidth()) {
                mOffsetX = -getWidth();
            }
        }
        mFrontView.setTranslationX(mOffsetX);

        invalidate();
    }

    public void update() {

        if (mOpen) {
            if (mIsRTL) {
                if (mOffsetX > getWidth() * 0.75f) {
                    open();
                } else {
                    close();
                }
            } else {
                if (mOffsetX < -getWidth() * 0.75f) {
                    open();
                } else {
                    close();
                }
            }
        } else {
            if (mIsRTL) {
                if (mOffsetX > getWidth() * 0.25f) {
                    open();
                } else {
                    close();
                }
            } else {
                if (mOffsetX < -getWidth() * 0.25f) {
                    open();
                } else {
                    close();
                }
            }
        }
    }

    private void open() {

        mOpen = true;
        boolean openAnimation = false;
        if (mIsRTL) {
            if (mOffsetX < getWidth()) {
                mOffsetX = getWidth();
                openAnimation = true;
            }
        } else {
            if (mOffsetX > -getWidth()) {
                mOffsetX = -getWidth();
                openAnimation = true;
            }
        }
        if (openAnimation) {
            ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(mFrontView, "translationX", mOffsetX);
            objectAnimator.setDuration(200);
            objectAnimator.addUpdateListener(animation -> invalidate());
            objectAnimator.start();
        }
    }

    public void close() {

        mOpen = false;
        boolean closeAnimation = false;
        if (mIsRTL) {
            if (mOffsetX > 0) {
                mOffsetX = 0;
                closeAnimation = true;
            }
        } else {
            if (mOffsetX < 0) {
                mOffsetX = 0;
                closeAnimation = true;
            }
        }
        if (closeAnimation) {
            ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(mFrontView, "translationX", mOffsetX);
            objectAnimator.setDuration(200);
            objectAnimator.addUpdateListener(animation -> invalidate());
            objectAnimator.start();
        }
    }

    public boolean isInOpenSlider(float x, float y) {

        if (!mOpen) {

            return false;
        }

        Rect hitRect = new Rect();
        getDrawingRect(hitRect);
        int[] location = new int[2];
        getLocationOnScreen(location);
        hitRect.offset(location[0], location[1]);

        return hitRect.contains((int) x, (int) y);
    }

    @Override
    public void onDraw(@NonNull Canvas canvas) {

        if (mIsRTL) {
            canvas.clipRect(0, 0, mOffsetX, getHeight());
        } else {
            canvas.clipRect(getWidth() + mOffsetX, 0, getWidth(), getHeight());
        }

        super.onDraw(canvas);
    }
}
