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

    private final TextToSpeech tts;
    private final Listener listener;

    /** Offset giay: + doc truoc phu de, - doc sau. Port tu audio_offset. */
    private volatile float audioOffset = 0f;
    /** Nguong seek giay. Port tu SEEK_THRESHOLD = 1.2. */
    private volatile float seekThreshold = 1.2f;
    /** Toc do doc 0.5 - 2.5. Thay cho ffmpeg atempo MAX_SPEED=2.5. */
    private volatile float speechRate = 1.0f;

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

        try {
            tts.setLanguage(new Locale("vi", "VN"));
        } catch (Exception ignored) {}
        try {
            tts.setSpeechRate(speechRate);
        } catch (Exception ignored) {}

        try {
            tts.stop();
            String utterId = "webtts-" + idx + "-" + utterSeq.incrementAndGet();
            if (Build.VERSION.SDK_INT >= 21) {
                Bundle b = new Bundle();
                b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utterId);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, utterId);
            } else {
                java.util.HashMap<String, String> params = new java.util.HashMap<>();
                params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utterId);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
            }
        } catch (Exception e) {
            if (listener != null) listener.onTtsError(idx);
        }
    }
}
