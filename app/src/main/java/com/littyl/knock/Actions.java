package com.littyl.knock;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Everything a knock command can do. Ids are stored in prefs, so never renumber them. */
final class Actions {
    static final String NONE = "none", SCROLL_DOWN = "scroll_down", SCROLL_UP = "scroll_up",
            SWIPE_LEFT = "swipe_left", SWIPE_RIGHT = "swipe_right", BACK = "back", HOME = "home",
            RECENTS = "recents", NOTIFICATIONS = "notifications", QUICK_SETTINGS = "quick_settings",
            PLAY_PAUSE = "play_pause", NEXT_TRACK = "next_track", PREV_TRACK = "prev_track",
            ASSISTANT = "assistant", SCREENSHOT = "screenshot", LOCK = "lock", TORCH = "torch",
            OPEN_APP = "app:";

    static final String[] IDS = {SCROLL_DOWN, SCROLL_UP, SWIPE_LEFT, SWIPE_RIGHT, BACK, HOME, RECENTS,
            NOTIFICATIONS, QUICK_SETTINGS, PLAY_PAUSE, NEXT_TRACK, PREV_TRACK, ASSISTANT, SCREENSHOT, LOCK, TORCH, NONE};
    static final String[] NAMES = {"Scroll down (next)", "Scroll up (previous)", "Swipe left", "Swipe right", "Back", "Home",
            "Recent apps", "Notifications", "Quick settings", "Play / pause music", "Next track", "Previous track",
            "Assistant", "Screenshot", "Lock screen", "Flashlight", "Do nothing"};

    static String name(Context c, String id) {
        if (id == null) return "Do nothing";
        if (id.startsWith(OPEN_APP)) {
            String pkg = id.substring(OPEN_APP.length());
            try {
                PackageManager pm = c.getPackageManager();
                return "Open " + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                return "Open app (uninstalled)";
            }
        }
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return "Do nothing";
    }

    /** True if the action needs the accessibility service to be on. */
    static boolean needsAccessibility(String id) {
        return id != null && !id.equals(NONE) && !id.equals(PLAY_PAUSE) && !id.equals(NEXT_TRACK)
                && !id.equals(PREV_TRACK) && !id.equals(TORCH);
    }

    private static boolean torchOn;

    /** Returns null on success, otherwise a short reason. */
    static String run(Context c, String id) {
        if (id == null || id.equals(NONE)) return null;
        switch (id) {
            case PLAY_PAUSE: return media(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case NEXT_TRACK: return media(c, KeyEvent.KEYCODE_MEDIA_NEXT);
            case PREV_TRACK: return media(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case TORCH: return torch(c);
        }
        KnockAccess a = KnockAccess.instance;
        if (a == null) return "accessibility is off";
        switch (id) {
            case SCROLL_DOWN: return a.swipe(0.5f, 0.72f, 0.5f, 0.28f) ? null : "gesture failed";
            case SCROLL_UP: return a.swipe(0.5f, 0.28f, 0.5f, 0.72f) ? null : "gesture failed";
            case SWIPE_LEFT: return a.swipe(0.82f, 0.5f, 0.18f, 0.5f) ? null : "gesture failed";
            case SWIPE_RIGHT: return a.swipe(0.18f, 0.5f, 0.82f, 0.5f) ? null : "gesture failed";
            case BACK: return global(a, AccessibilityService.GLOBAL_ACTION_BACK);
            case HOME: return global(a, AccessibilityService.GLOBAL_ACTION_HOME);
            case RECENTS: return global(a, AccessibilityService.GLOBAL_ACTION_RECENTS);
            case NOTIFICATIONS: return global(a, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            case QUICK_SETTINGS: return global(a, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
            case SCREENSHOT: return global(a, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
            case LOCK: return global(a, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            case ASSISTANT: return assistant(a);
        }
        if (id.startsWith(OPEN_APP)) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(id.substring(OPEN_APP.length()));
            if (i == null) return "app not found";
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // Started from the accessibility service: a system-bound service may open activities from the background.
            a.startActivity(i);
            return null;
        }
        return "unknown action";
    }

    private static String global(KnockAccess a, int action) {
        return a.performGlobalAction(action) ? null : "not supported here";
    }

    private static String assistant(Context c) {
        for (String action : new String[]{Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST}) {
            try {
                c.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return null;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        return "no assistant app";
    }

    private static String media(Context c, int code) {
        AudioManager am = c.getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        return null;
    }

    private static String torch(Context c) {
        CameraManager cm = c.getSystemService(CameraManager.class);
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    torchOn = !torchOn;
                    cm.setTorchMode(id, torchOn);
                    return null;
                }
            }
            return "no flashlight";
        } catch (CameraAccessException | IllegalArgumentException e) {
            return "flashlight busy";
        }
    }
}
