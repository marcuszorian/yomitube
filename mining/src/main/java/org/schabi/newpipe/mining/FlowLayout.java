package org.schabi.newpipe.mining;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out left-to-right, wrapping to new rows — used for the sentence's word chips. */
public class FlowLayout extends ViewGroup {

    private final int hGap;
    private final int vGap;

    public FlowLayout(Context context) {
        this(context, null);
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        hGap = Math.round(2 * d);
        vGap = Math.round(6 * d);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        boolean unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED;
        int x = 0;
        int rowHeight = 0;
        int height = 0;
        int widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, MeasureSpec.makeMeasureSpec(Math.max(0, maxWidth),
                    unbounded ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST), heightMeasureSpec);
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (!unbounded && x > 0 && x + w > maxWidth) {
                height += rowHeight + vGap;
                x = 0;
                rowHeight = 0;
            }
            x += w + hGap;
            widest = Math.max(widest, x - hGap);
            rowHeight = Math.max(rowHeight, h);
        }
        height += rowHeight;
        int width = unbounded ? widest : MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(resolveSize(width + (unbounded ? getPaddingLeft() + getPaddingRight() : 0), widthMeasureSpec),
                resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = getPaddingTop();
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > maxWidth) {
                y += rowHeight + vGap;
                x = 0;
                rowHeight = 0;
            }
            int left = getPaddingLeft() + x;
            child.layout(left, y, left + w, y + h);
            x += w + hGap;
            rowHeight = Math.max(rowHeight, h);
        }
    }
}
