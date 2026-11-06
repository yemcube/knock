package com.littyl.knock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

/** Performs gestures and system buttons for knock commands. Reads no screen content. */
public class KnockAccess extends AccessibilityService {
    static volatile KnockAccess instance;

    @Override protected void onServiceConnected() { instance = this; }

    @Override public boolean onUnbind(android.content.Intent i) { instance = null; return super.onUnbind(i); }

    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }

    /** Swipe between two points given as fractions of the screen. */
    boolean swipe(float x0, float y0, float x1, float y1) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Path p = new Path();
        p.moveTo(x0 * dm.widthPixels, y0 * dm.heightPixels);
        p.lineTo(x1 * dm.widthPixels, y1 * dm.heightPixels);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 220)).build(), null, null);
    }
}
