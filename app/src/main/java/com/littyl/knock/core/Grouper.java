package com.littyl.knock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns classified knocks into commands: knocks 0.12–0.6 s apart form one group; when 0.6 s pass
 * with no further knock, a group of 2 or 3 knocks that all agree on one spot becomes a command.
 * Any unsure knock, disagreement, or other count rejects the whole group.
 */
public final class Grouper {
    public static final double GAP_MIN = 0.12, GAP_MAX = 0.6;

    public interface Sink {
        void onCommand(int spot, int count, List<Knock> knocks);
        void onRejected(int count, String why);
    }

    private final Sink sink;
    private final List<Knock> cur = new ArrayList<>();
    private final List<Integer> labels = new ArrayList<>();

    public Grouper(Sink sink) { this.sink = sink; }

    public void add(Knock k, int label) {
        if (!cur.isEmpty()) {
            double gap = k.timeSec - cur.get(cur.size() - 1).timeSec;
            if (gap > GAP_MAX) flush();
            else if (gap < GAP_MIN) return;    // ringing of the previous knock
        }
        cur.add(k);
        labels.add(label);
    }

    /** Call regularly with the current audio time; closes a group once it has gone quiet. */
    public void tick(double nowSec) {
        if (!cur.isEmpty() && nowSec - cur.get(cur.size() - 1).timeSec > GAP_MAX) flush();
    }

    private void flush() {
        int n = cur.size();
        int spot = labels.get(0);
        boolean agree = true;
        for (int l : labels) if (l != spot) agree = false;
        if (n < 2 || n > 3) sink.onRejected(n, n == 1 ? "single knock" : n + " knocks");
        else if (!agree || spot == Model.UNSURE) sink.onRejected(n, "unsure where");
        else sink.onCommand(spot, n, new ArrayList<>(cur));
        cur.clear();
        labels.clear();
    }
}
