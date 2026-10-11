package com.hy300.lanstream;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The cues of a WebVTT subtitle file (what the server's /subs/ID/TRACK.vtt returns), in time order. */
final class Vtt {
    private static final Pattern TIMING = Pattern.compile(
            "(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})\\s+-->\\s+(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})");

    private final List<long[]> times = new ArrayList<>();   // {startMs, endMs}
    private final List<String> texts = new ArrayList<>();

    static Vtt parse(String file) {
        Vtt v = new Vtt();
        for (String block : file.replace("\r", "").split("\n\n+")) {
            Matcher m = TIMING.matcher(block);
            if (!m.find()) continue;
            int lineEnd = block.indexOf('\n', m.end());
            if (lineEnd < 0) continue;      // a timing line with no text under it
            String text = block.substring(lineEnd + 1);
            text = text.replaceAll("<[^>]+>", "").replaceAll("\\{\\\\[^}]*\\}", "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim();
            if (text.isEmpty()) continue;
            v.times.add(new long[]{ms(m, 1), ms(m, 5)});
            v.texts.add(text);
        }
        return v;
    }

    private static long ms(Matcher m, int g) {
        long h = m.group(g) == null ? 0 : Long.parseLong(m.group(g));
        return ((h * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000
                + Long.parseLong(m.group(g + 3));
    }

    boolean isEmpty() { return texts.isEmpty(); }

    /** What should be on screen at this moment ("" for nothing). Overlapping cues are stacked. */
    String at(long positionMs) {
        // First cue that starts after the position, by binary search; the visible ones are just before it.
        int lo = 0, hi = times.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times.get(mid)[0] <= positionMs) lo = mid + 1; else hi = mid;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lo - 4); i < lo; i++) {
            if (times.get(i)[1] > positionMs) sb.append(sb.length() > 0 ? "\n" : "").append(texts.get(i));
        }
        return sb.toString();
    }
}
