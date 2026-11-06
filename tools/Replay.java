import com.littyl.knock.core.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Desktop check: run the app's Detector/Model/Grouper over recorded sessions (data/raw). */
public class Replay {
    record Session(String name, String label, List<Knock> knocks, double dur) { }

    static Session run(Path json) throws IOException {
        String meta = Files.readString(json);
        String base = json.toString().replaceAll("\\.json$", "");
        Matcher m = Pattern.compile("\"ts_start\":\\[(\\d+),(\\d+)\\]").matcher(meta); m.find();
        long f0 = Long.parseLong(m.group(1)), n0 = Long.parseLong(m.group(2));
        Matcher lm = Pattern.compile("\"label\":\"([^\"]+)\"").matcher(meta); lm.find();
        byte[] wav = Files.readAllBytes(Paths.get(base + ".wav"));
        ShortBuffer pcm = ByteBuffer.wrap(wav, 44, wav.length - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        List<long[]> acc = new ArrayList<>();
        List<float[]> accv = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(base + ".accel.csv"))) {
            if (line.startsWith("t")) continue;
            String[] p = line.split(",");
            acc.add(new long[]{Long.parseLong(p[0])});
            accv.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
        }
        List<Knock> ks = new ArrayList<>();
        Detector d = new Detector(ks::add);
        d.setTimestamp(f0, n0);
        short[] buf = new short[1920];
        int ai = 0;
        long frames = 0;
        while (pcm.remaining() > 0) {
            int n = Math.min(buf.length, pcm.remaining());
            pcm.get(buf, 0, n);
            frames += n / 2;
            long tEnd = n0 + (frames - f0) * 1_000_000_000L / 48000;
            while (ai < acc.size() && acc.get(ai)[0] <= tEnd) { float[] v = accv.get(ai); d.accel(acc.get(ai)[0], v[0], v[1], v[2]); ai++; }
            d.audio(buf, n);
        }
        if (lm.group(1).equals("live")) for (Knock k : ks) k.media = true;
        return new Session(json.getFileName().toString().replace(".json", ""), lm.group(1), ks, frames / 48000.0);
    }

    public static void main(String[] a) throws IOException {
        List<Session> ss = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(a[0]), "*_portrait_*.json")) {
            for (Path p : ds) ss.add(run(p));
        }
        ss.sort(Comparator.comparing(Session::name));
        for (Session s : ss) {
            double[] lag = s.knocks.stream().filter(k -> k.accelRatio >= 25 && k.loud >= 100).mapToDouble(k -> k.lag).sorted().toArray();
            System.out.printf("%-46s onsets %3d strong %3d lag median %s%n", s.name, s.knocks.size(), lag.length, lag.length > 0 ? String.valueOf(lag[lag.length / 2]) : "-");
        }
        String[][] pairs = {{"092343", "092405"}, {"095901", "103730"}, {"133747", "133829"}, {"134058", "134137"}};
        for (String[] pr : pairs) {
            Session A = ss.stream().filter(s -> s.name.contains(pr[0])).findFirst().get();
            Session B = ss.stream().filter(s -> s.name.contains(pr[1])).findFirst().get();
            List<Knock> ta = strong(A.knocks).subList(0, 8), tb = strong(B.knocks).subList(0, 8);
            Model model = Model.parse(Model.teach(ta, tb).serialize());   // round-trip persistence too
            int[] tot = new int[3];
            int falseCmds = 0, realCmds = 0, realWrong = 0;
            double deskMin = 0;
            for (Session s : ss) {
                boolean desk = s.label.equals("desk-life");
                if (!desk && !s.label.equals("near") && !s.label.equals("far")) continue;
                String truth = s.label;
                List<int[]> groups = pyGroups(model, s.knocks);
                int skip = (s == A || s == B) ? 8 : 0;
                if (desk) deskMin += s.dur / 60;
                for (int i = skip; i < groups.size() && !desk; i++) {
                    int spot = groups.get(i)[1];
                    String name = spot == Model.UNSURE ? "?" : spot == Model.SPOT_A ? "near" : "far";
                    tot[name.equals("?") ? 2 : name.equals(truth) ? 0 : 1]++;
                }
                // the real thing: Grouper commands
                int[] c = {0, 0};
                Grouper g = new Grouper(new Grouper.Sink() {
                    public void onCommand(int spot, int count, List<Knock> ks) { c[0]++; if (!desk && (spot == 0) != truth.equals("near")) c[1]++; }
                    public void onRejected(int count, String why) { }
                });
                for (Knock k : s.knocks) { int l = model.classify(k); if (l != Model.NOT_KNOCK) g.add(k, l); }
                g.tick(1e9);
                if (desk) falseCmds += c[0]; else { realCmds += c[0]; realWrong += c[1]; }
            }
            for (Session s : ss) {
                if (!s.label.equals("live")) continue;
                StringBuilder sb = new StringBuilder();
                Grouper g = new Grouper(new Grouper.Sink() {
                    public void onCommand(int spot, int count, List<Knock> ks) { sb.append(String.format("  %.1f:%s%d", ks.get(0).timeSec, spot == 0 ? "A" : "B", count)); }
                    public void onRejected(int count, String why) { if (count > 1) sb.append(String.format("  %.1f:x%d", 0.0, count)); }
                });
                int[] lab = new int[4];
                for (Knock k : s.knocks) { int l = model.classify(k); lab[l]++; if (l != Model.NOT_KNOCK) { g.add(k, l); g.tick(k.timeSec); } }
                g.tick(1e9);
                System.out.printf("   live A %d B %d unsure %d not %d |%s%n", lab[0], lab[1], lab[2], lab[3], sb);
            }
            System.out.printf("teach %s/%s: %s%n   per-group R %d W %d U %d | commands (2-3 knocks) on spot sessions %d (wrong %d) | desk-life false commands %d in %.1f min%n",
                    pr[0], pr[1], model.describe(), tot[0], tot[1], tot[2], realCmds, realWrong, falseCmds, deskMin);
        }
    }

    static List<Knock> strong(List<Knock> ks) {
        List<Knock> o = new ArrayList<>();
        for (Knock k : ks) if (k.accelRatio >= 25 && k.loud >= 100) o.add(k);
        return o;
    }

    /** analysis/teach.py run_cmds: groups of any size, spot only if every knock agrees. */
    static List<int[]> pyGroups(Model m, List<Knock> ks) {
        List<int[]> out = new ArrayList<>();
        double last = -1e9; int n = 0; int spot = -1; boolean bad = false;
        for (Knock k : ks) {
            int l = m.classify(k);
            if (l == Model.NOT_KNOCK) continue;
            if (n > 0 && k.timeSec - last > Grouper.GAP_MAX) { out.add(new int[]{n, bad ? Model.UNSURE : spot}); n = 0; }
            if (n > 0 && k.timeSec - last < Grouper.GAP_MIN) continue;
            if (n == 0) { spot = l; bad = l == Model.UNSURE; } else if (l != spot) bad = true;
            n++; last = k.timeSec;
        }
        if (n > 0) out.add(new int[]{n, bad ? Model.UNSURE : spot});
        return out;
    }
}
