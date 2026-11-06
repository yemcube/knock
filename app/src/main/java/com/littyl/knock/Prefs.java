package com.littyl.knock;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;

/** Preference keys and the naming of the four commands. */
final class Prefs {
    static final String MODEL = "model_", ACTION = "action_",
            SOURCE = "source", STEREO = "stereo", PRACTICE = "practice", HAPTICS = "haptics", ENABLED = "enabled";

    static SharedPreferences get(Context c) { return c.getSharedPreferences("knock", Context.MODE_PRIVATE); }

    /** Slots: A2, A3, B2, B3 (spot × knock count). */
    static String slot(int spot, int count) { return (spot == 0 ? "A" : "B") + count; }

    static final String[] SLOTS = {"A2", "A3", "B2", "B3"};

    static String defaultAction(String slot) {
        switch (slot) {
            case "A2": return Actions.SCROLL_DOWN;
            case "A3": return Actions.SCROLL_UP;
            case "B2": return Actions.ASSISTANT;
            default: return Actions.PLAY_PAUSE;
        }
    }

    static String[] suggestedSpots(String orient) {
        return orient.equals("landscape") ? new String[]{"Left", "Right"} : new String[]{"Near", "Far"};
    }

    static String commandName(String orient, int spot, int count) {
        return "Knock " + (count == 2 ? "twice" : "3 times") + " " + spotPhrase(orient, spot);
    }

    static String spotPhrase(String orient, int spot) {
        String n = suggestedSpots(orient)[spot];
        return orient.equals("landscape") ? "on the " + n.toLowerCase() : n.equals("Near") ? "near the phone" : "far from the phone";
    }

    // ---- profiles: one per table or setup. "default" keeps the original keys, so old installs carry over. ----

    static final String PROFILES = "profiles", ACTIVE = "active_profile", DEFAULT = "default";

    /** [id, name] pairs, Default first. */
    static java.util.List<String[]> profiles(SharedPreferences p) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        out.add(new String[]{DEFAULT, "Default"});
        for (String line : p.getString(PROFILES, "").split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0) out.add(new String[]{line.substring(0, t), line.substring(t + 1)});
        }
        return out;
    }

    static void saveProfiles(SharedPreferences p, java.util.List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] pr : list) if (!pr[0].equals(DEFAULT)) sb.append(pr[0]).append('\t').append(pr[1].replace('\n', ' ').replace('\t', ' ')).append('\n');
        p.edit().putString(PROFILES, sb.toString()).apply();
    }

    static String active(SharedPreferences p) {
        String a = p.getString(ACTIVE, DEFAULT);
        for (String[] pr : profiles(p)) if (pr[0].equals(a)) return a;
        return DEFAULT;
    }

    static String activeName(SharedPreferences p) {
        String a = active(p);
        for (String[] pr : profiles(p)) if (pr[0].equals(a)) return pr[1];
        return "Default";
    }

    static String prefix(String profile) { return profile.equals(DEFAULT) ? "" : profile + "_"; }

    static String modelKey(String profile, String orient) { return MODEL + prefix(profile) + orient; }

    /** A profile's action for a slot; new profiles start from Default's choices. */
    static String action(SharedPreferences p, String profile, String slot) {
        String own = p.getString(ACTION + prefix(profile) + slot, null);
        if (own != null) return own;
        return p.getString(ACTION + slot, defaultAction(slot));
    }

    static void setAction(SharedPreferences p, String profile, String slot, String action) {
        p.edit().putString(ACTION + prefix(profile) + slot, action).apply();
    }

    static String newProfile(SharedPreferences p, String name) {
        java.util.List<String[]> list = profiles(p);
        String id = "p" + Long.toString(System.currentTimeMillis(), 36);
        list.add(new String[]{id, name});
        saveProfiles(p, list);
        return id;
    }

    static void deleteProfile(SharedPreferences p, String id) {
        if (id.equals(DEFAULT)) return;
        java.util.List<String[]> list = profiles(p);
        list.removeIf(pr -> pr[0].equals(id));
        saveProfiles(p, list);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith(MODEL + id + "_") || k.startsWith(ACTION + id + "_")) e.remove(k);
        if (id.equals(p.getString(ACTIVE, DEFAULT))) e.putString(ACTIVE, DEFAULT);
        e.apply();
    }

    static void renameProfile(SharedPreferences p, String id, String name) {
        java.util.List<String[]> list = profiles(p);
        for (String[] pr : list) if (pr[0].equals(id)) pr[1] = name;
        saveProfiles(p, list);
    }

    static int source(SharedPreferences p) { return p.getInt(SOURCE, MediaRecorder.AudioSource.CAMCORDER); }
}
