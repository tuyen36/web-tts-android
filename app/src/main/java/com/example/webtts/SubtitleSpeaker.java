package com.example.webtts;

import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dieu khien Google TTS he thong de doc phu de theo timeline video.
 *
 * Port y tuong playback_loop() Python:
 * - tim cue hien tai theo position + audioOffset
 * - dang playing + co text -> speak() 1 lan / cau
 * - seek qua nguong -> huy cau cu, doc cau moi
 *
 * Khac Python: khong can Edge TTS / ffmpeg atempo / pygame.
 * Google TTS tu xu ly toc do qua setSpeechRate().
 */
public class SubtitleSpeaker {

    public interface Listener {
        void onSpeakStart(int index);
        void onSpeakDone(int index);
        void onTtsError(int index);
    }

    /** Callback hien toc do thuc te len UI. */
    public interface RateDisplay {
        void onRate(float effectiveRate);
    }

    private final TextToSpeech tts;
    private final Listener listener;
    private volatile RateDisplay rateDisplay;

    /** Offset giay: + doc truoc phu de, - doc sau. Port tu audio_offset. */
    private volatile float audioOffset = 0f;
    /** Nguong seek giay. Port tu SEEK_THRESHOLD = 1.2. */
    private volatile float seekThreshold = 1.2f;
    /** Toc do doc 0.5 - 2.5. Thay cho ffmpeg atempo MAX_SPEED=2.5. */
    private volatile float speechRate = 1.0f;
    /** Tran tu dong tang toc cho cau dai (port MAX_SPEED Python). */
    private static final float MAX_AUTO_RATE = 2.5f;
    /**
     * So ky tu doc duoc moi giay o toc do 1.0x (uoc luong cho giong Viet
     * Google TTS). Cau nao uoc tinh doc lau hon timeline cua no thi
     * tu tang rate de kip, toi da MAX_AUTO_RATE.
     */
    private static final float CHARS_PER_SEC_AT_1X = 11.0f;
    private volatile float lastEffectiveRate = 1.0f;
    /**
     * Volume rieng cua giong doc sub (0.0 - 1.0). Doc lap voi volume media
     * cua app Netflix/YouTube — dung de noi giong sub khi chay song song.
     */
    private volatile float ttsVolume = 1.0f;

    private volatile List<SubtitleCue> subtitles =
            java.util.Collections.emptyList();
    private volatile int currentIndex = -1;
    private volatile double lastPosition = -1;
    private volatile boolean playing = false;

    private final AtomicInteger utterSeq = new AtomicInteger(0);

    public SubtitleSpeaker(TextToSpeech tts, Listener listener) {
        this.tts = tts;
        this.listener = listener;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                int idx = idxOf(id);
                if (listener != null && idx >= 0) listener.onSpeakStart(idx);
            }
            @Override public void onDone(String id) {
                int idx = idxOf(id);
                if (listener != null && idx >= 0) listener.onSpeakDone(idx);
            }
            @Override public void onError(String id) {
                int idx = idxOf(id);
                if (listener != null && idx >= 0) listener.onTtsError(idx);
            }
        });
    }

    private static int idxOf(String utteranceId) {
        try {
            // dinh dang "webtts-<index>-<seq>"
            String[] p = utteranceId.split("-");
            return Integer.parseInt(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    public void setSubtitles(List<SubtitleCue> subs) {
        subtitles = subs == null
                ? java.util.Collections.emptyList() : subs;
        currentIndex = -1;
        lastPosition = -1;
        stop();
    }

    /** Chi reset khi la list moi (tranh mat vi tri khi poll lien tuc). */
    public void setSubtitlesIfNew(List<SubtitleCue> subs) {
        if (subs != subtitles) setSubtitles(subs);
    }

    public void setAudioOffset(float v) { audioOffset = v; }
    public float getAudioOffset() { return audioOffset; }
    public void setSeekThreshold(float v) { seekThreshold = v; }

    /** Doi toc do doc + ap dung ngay vao TTS he thong. */
    public void setSpeechRate(float v) {
        if (v < 0.5f) v = 0.5f;
        if (v > 2.5f) v = 2.5f;
        speechRate = v;
        try {
            tts.setSpeechRate(v);
        } catch (Exception ignored) {}
    }
    public float getSpeechRate() { return speechRate; }

    /** Toc do thuc te vua ap cho cau gan nhat (de UI hien thi). */
    public float getLastEffectiveRate() { return lastEffectiveRate; }

    public void setRateDisplay(RateDisplay d) { rateDisplay = d; }

    /** Volume giong doc 0..100% (rieng, khong anh huong app Netflix). */
    public void setTtsVolume(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        ttsVolume = percent / 100.0f;
    }
    public int getTtsVolume() { return Math.round(ttsVolume * 100); }

    /**
     * Tinh rate can thiet de doc het text trong slot giay.
     * Giong create_speed_audio() Python: raw/target, kep 1.0..MAX.
     */
    public float rateFor(String text, double slotSec) {
        if (text == null || text.isEmpty()) return speechRate;
        if (slotSec <= 0.05) return MAX_AUTO_RATE;
        int chars = text.codePointCount(0, text.length());
        float need = (chars / CHARS_PER_SEC_AT_1X) / (float) slotSec;
        float base = speechRate <= 0 ? 1.0f : speechRate;
        float want = base * need;
        // Chi tang toc, khong giam: cau ngan giu giong doc tu nhien.
        if (want < base) want = base;
        if (want > MAX_AUTO_RATE) want = MAX_AUTO_RATE;
        return want;
    }

    public void setPlaying(boolean p) {
        boolean was = playing;
        playing = p;
        if (was && !p) {
            stop();
        }
    }

    public void stop() {
        try {
            tts.stop();
        } catch (Exception ignored) {}
        currentIndex = -1;
    }

    public int getCurrentIndex() { return currentIndex; }

    /**
     * Goi moi khi biet vi tri video hien tai (giay).
     * - Che do thu cong: user bam Play/Pause + keo SeekBar trong app.
     * - Che do WebView: goi tu onProgressChanged / JS hook.
     */
    public void onPosition(double position) {
        List<SubtitleCue> subs = subtitles;
        if (subs.isEmpty()) return;

        // Phat hien seek dot ngot -> huy cau cu (port handle_seek).
        if (lastPosition >= 0
                && Math.abs(position - lastPosition) > seekThreshold
                && !(lastPosition > 60 && position < 3)) {
            // quang cao / chuyen doan: giu nguyen, khong coi la seek tay
            if (!(lastPosition > 60 && position < 3)) {
                stop();
            }
        }
        lastPosition = position;

        int idx = SubtitleParser.findIndex(subs, position + audioOffset);
        if (idx < 0) return;
        if (idx == currentIndex) return;

        currentIndex = idx;
        if (!playing) return;

        SubtitleCue cue = subs.get(idx);
        String text = cue.text;
        if (text.isEmpty()) return;

        // Slot con lai cua cau: tu position hien tai den end cue.
        // Port get_timeline() Python nhung tru phan da troi qua de tranh
        // doc cham dan o cau dai.
        double slot = cue.end - position - audioOffset;
        if (slot < 0.3) slot = 0.3;
        double cueLen = cue.duration();
        if (slot > cueLen) slot = cueLen;
        float effRate = rateFor(text, slot);
        lastEffectiveRate = effRate;
        RateDisplay rd = rateDisplay;
        if (rd != null) {
            try {
                rd.onRate(effRate);
            } catch (Exception ignored) {}
        }

        try {
            tts.setLanguage(new Locale("vi", "VN"));
        } catch (Exception ignored) {}
        try {
            tts.setSpeechRate(effRate);
        } catch (Exception ignored) {}

        try {
            tts.stop();
            String utterId = "webtts-" + idx + "-" + utterSeq.incrementAndGet();
            Bundle b = new Bundle();
            b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utterId);
            // Volume rieng giong doc (minSdk 29 nen API 21+ luon dung).
            b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, utterId);
        } catch (Exception e) {
            if (listener != null) listener.onTtsError(idx);
        }
    }
}
