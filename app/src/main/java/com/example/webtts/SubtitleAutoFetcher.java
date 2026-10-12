package com.example.webtts;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.2: Tu lay sub YouTube bang NewPipeExtractor (khong hook WebView).
 *
 * - Tach videoId tu URL (watch?v= / youtu.be/ / shorts/ / embed/).
 * - getSubtitles(VTT): uu tien 'vi' -> lay luon; neu chi co 'en'
 *   thi them &tlang=vi de xin ban YouTube tu dich (giong .py cho
 *   user bat "Dich tu dong -> Tieng Viet", nhung app tu xin).
 * - Tai file VTT -> parse thanh SubtitleCue (giong SubtitleParser).
 * - Khong co sub / tuoi han che / live: bao loi de fallback .srt tay.
 */
public class SubtitleAutoFetcher {

    public interface Callback {
        void onOk(List<SubtitleCue> cues, String label);
        void onError(String msg);
    }

    public static void fetch(String pageUrl, Callback cb) {
        new Thread(() -> {
            try {
                String videoId = extractVideoId(pageUrl);
                if (videoId == null || videoId.isEmpty()) {
                    cb.onError("Khong tim duoc videoId trong link.");
                    return;
                }
                NewPipeHolder.initIfNeeded();
                StreamingService yt = NewPipe.getService(0);
                StreamExtractor ex = yt.getStreamExtractor(
                        "https://www.youtube.com/watch?v=" + videoId);
                ex.fetchPage();
                List<SubtitlesStream> subs;
                try {
                    subs = ex.getSubtitles(MediaFormat.VTT);
                } catch (Exception e) {
                    subs = new ArrayList<>();
                }
                if (subs == null || subs.isEmpty()) {
                    try {
                        subs = ex.getSubtitlesDefault();
                    } catch (Exception e) {
                        subs = new ArrayList<>();
                    }
                }
                if (subs == null || subs.isEmpty()) {
                    cb.onError("Video nay khong co phu de (thu file .srt tay).");
                    return;
                }

                // 1. Tim tieng Viet san.
                SubtitlesStream vi = null;
                for (SubtitlesStream s : subs) {
                    String tag = s.getLanguageTag();
                    if (tag != null && tag.toLowerCase(Locale.US).startsWith("vi")) {
                        vi = s;
                        break;
                    }
                }
                if (vi != null) {
                    String url = vi.getContent();
                    // Ep kieu VTT de parse on dinh.
                    if (!url.contains("fmt=")) url += "&fmt=vtt";
                    String body = download(url);
                    List<SubtitleCue> cues = parseVtt(body);
                    if (cues.isEmpty()) {
                        cb.onError("Phu de Viet rong.");
                    } else {
                        cb.onOk(cues, "vi (" + cues.size() + " cau)");
                    }
                    return;
                }

                // 2. Khong co Viet: lay tieng Anh roi xin ban dich tlang=vi.
                SubtitlesStream en = null;
                for (SubtitlesStream s : subs) {
                    String tag = s.getLanguageTag();
                    if (tag != null && tag.toLowerCase(Locale.US).startsWith("en")) {
                        en = s;
                        break;
                    }
                }
                if (en == null) en = subs.get(0);
                String base = en.getContent();
                if (!base.contains("fmt=")) base += "&fmt=vtt";
                String viUrl = base + "&tlang=vi";
                String body = download(viUrl);
                List<SubtitleCue> cues = parseVtt(body);
                if (!cues.isEmpty()) {
                    cb.onOk(cues, en.getLanguageTag() + "→vi (" + cues.size() + " cau)");
                    return;
                }
                // 3. Dich that bai: dung luon ban goc (de user nghe hieu / dich sau).
                String orig = download(base);
                List<SubtitleCue> origCues = parseVtt(orig);
                if (!origCues.isEmpty()) {
                    cb.onOk(origCues, en.getLanguageTag()
                            + " goc (" + origCues.size() + " cau, chua dich)");
                } else {
                    cb.onError("Khong doc duoc phu de.");
                }
            } catch (Exception e) {
                String m = e.getMessage();
                cb.onError(m == null || m.isEmpty()
                        ? e.getClass().getSimpleName() : m);
            }
        }, "SubAutoFetch").start();
    }

    /** Tach videoId 11 ky tu tu moi dang link YouTube. */
    public static String extractVideoId(String url) {
        if (url == null) return null;
        String u = url.trim();
        // watch?v=XXXX / &v=XXXX
        Matcher m = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})").matcher(u);
        if (m.find()) return m.group(1);
        // youtu.be/XXXX, /shorts/XXXX, /embed/XXXX, /live/XXXX, /v/XXXX
        m = Pattern.compile("(?:youtu\\.be/|/(?:shorts|embed|live|v)/)"
                + "([A-Za-z0-9_-]{11})").matcher(u);
        if (m.find()) return m.group(1);
        // Dung 11 ky tu (user paste truc tiep videoId).
        if (u.matches("[A-Za-z0-9_-]{11}")) return u;
        return null;
    }

    private static String download(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            br.close();
            return sb.toString();
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    /** Parse VTT/TTML don gian thanh cues (dau -> end + text). */
    private static List<SubtitleCue> parseVtt(String body) {
        List<SubtitleCue> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        // VTT: 00:00:01.000 --> 00:00:03.500 (+ co the co setting sau end).
        Pattern p = Pattern.compile(
                "(\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*"
                + "(\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})[^\\n]*\\n([\\s\\S]*?)"
                + "(?=\\n\\n|\\n\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s*-->|$)");
        Matcher m = p.matcher(body);
        while (m.find()) {
            double s = vttTime(m.group(1));
            double e = vttTime(m.group(2));
            if (e <= s) continue;
            String text = SubtitleParser.cleanText(m.group(3));
            if (text.isEmpty()) continue;
            out.add(new SubtitleCue(s, e, text));
        }
        // TTML fallback: <p begin=".." end="..">text</p>.
        if (out.isEmpty() && body.contains("<p ")) {
            Pattern p2 = Pattern.compile(
                    "<p[^>]*begin=\"([^\"]+)\"[^>]*end=\"([^\"]+)\"[^>]*>"
                    + "([\\s\\S]*?)</p>");
            Matcher m2 = p2.matcher(body);
            while (m2.find()) {
                double s = ttmlTime(m2.group(1));
                double e = ttmlTime(m2.group(2));
                if (e <= s) continue;
                String text = SubtitleParser.cleanText(m2.group(3));
                if (text.isEmpty()) continue;
                out.add(new SubtitleCue(s, e, text));
            }
        }
        out.sort((a, b) -> Double.compare(a.start, b.start));
        return out;
    }

    private static double vttTime(String v) {
        try {
            String t = v.replace(',', '.');
            String[] hm = t.split(":");
            double h = Double.parseDouble(hm[0]);
            double min = Double.parseDouble(hm[1]);
            double sec = Double.parseDouble(hm[2]);
            return h * 3600 + min * 60 + sec;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double ttmlTime(String v) {
        try {
            v = v.trim();
            if (v.endsWith("s")) {
                return Double.parseDouble(v.substring(0, v.length() - 1));
            }
            if (v.contains(":")) return vttTime(v);
            return Double.parseDouble(v);
        } catch (Exception e) {
            return 0;
        }
    }
}
