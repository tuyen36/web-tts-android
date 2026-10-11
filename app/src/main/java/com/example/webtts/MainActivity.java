package com.example.webtts;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Man hinh chinh Web TTS Android.
 *
 * 2 che do chay (tuong ung 2 che do Python):
 * 1. THU CONG (khuyen dung truoc): chon file .srt -> bam Play ->
 *    SeekBar dong vai tro timeline video -> SubtitleSpeaker doc theo.
 *    Giong cua so Dieu khien + ask_srt_manual() ban Python.
 * 2. WEBVIEW (thu nghiem): mo trang video trong WebView cua app,
 *    JS hook video.currentTime + paused -> SubtitleSpeaker doc theo.
 *    Khong can Kiwi. Sub van nap thu cong (.srt) o giai doan 1.
 *
 * Khong ket noi thang vao app Netflix: Android khong cho app thu 3
 * doc position/sub tu app Netflix (sandbox + DRM). Giai phap thuc te:
 * mo Netflix tren trinh duyet trong WebView + nap sub thu cong.
 */
public class MainActivity extends AppCompatActivity
        implements TextToSpeech.OnInitListener {

    private static final int REQ_SRT = 1001;
    private static final int REQ_NOTIF = 1002;

    // Port tu AUDIO_OFFSET_OPTIONS Python.
    private static final float[] OFFSETS =
            {2.0f, 1.5f, 1.0f, 0.5f, 0.0f, -0.5f, -1.0f};

    private TextToSpeech tts;
    private SubtitleSpeaker speaker;
    private List<SubtitleCue> subs = new ArrayList<>();

    private TextView tvStatus;
    private TextView tvCue;
    private TextView tvRate;
    private Button btnPlayPause;
    private SeekBar seekPos;
    private SeekBar seekRate;
    private GridLayout gridOffset;
    private WebView webView;

    private boolean playing = false;
    private boolean ttsReady = false;
    private double position = 0;   // giay
    private double duration = 0;   // giay (max end cue)
    private float offset = 0f;
    private float rate = 1.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (playing && duration > 0) {
                position += 0.25;
                if (position >= duration) {
                    position = duration;
                    setPlaying(false);
                }
                syncUi();
                pushToSpeaker();
            }
            handler.postDelayed(this, 250);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvCue = findViewById(R.id.tvCue);
        tvRate = findViewById(R.id.tvRate);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        seekPos = findViewById(R.id.seekPos);
        seekRate = findViewById(R.id.seekRate);
        gridOffset = findViewById(R.id.gridOffset);
        webView = findViewById(R.id.webView);

        SharedPreferences sp =
                getSharedPreferences("webtts", MODE_PRIVATE);
        offset = sp.getFloat("offset", 0f);
        rate = sp.getFloat("rate", 1.0f);

        tts = new TextToSpeech(this, this);

        buildOffsetGrid();
        seekRate.setProgress(Math.round((rate - 0.5f) / 2.0f * 200));
        tvRate.setText(String.format(Locale.US, "%.1fx", rate));

        findViewById(R.id.btnPickSrt).setOnClickListener(v -> pickSrt());
        btnPlayPause.setOnClickListener(v -> setPlaying(!playing));
        findViewById(R.id.btnOpenWeb).setOnClickListener(v -> openWeb());

        seekPos.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && duration > 0) {
                    position = duration * p / 1000.0;
                    pushToSpeaker();
                    syncUi();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                rate = 0.5f + p / 200.0f * 2.0f;
                tvRate.setText(String.format(Locale.US, "%.1fx", rate));
                if (speaker != null) speaker.setSpeechRate(rate);
                TtsPlaybackService.setSharedRate(rate);
                sp.edit().putFloat("rate", rate).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // WebView: doc currentTime tu <video> dau tien tren trang.
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                injectHook();
            }
        });
        webView.addJavascriptInterface(new VideoBridge(), "WebTTS");

        handler.post(ticker);
        askNotif();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts.setLanguage(new Locale("vi", "VN"));
            } catch (Exception ignored) {}
            ttsReady = true;
            speaker = new SubtitleSpeaker(tts, new SubtitleSpeaker.Listener() {
                @Override public void onSpeakStart(int index) {
                    runOnUiThread(() -> highlight(index));
                }
                @Override public void onSpeakDone(int index) {}
                @Override public void onTtsError(int index) {
                    runOnUiThread(() -> toast("TTS loi cau " + (index + 1)));
                }
            });
            // Hien toc do thuc te (co the > muc user chon khi gap cau dai).
            speaker.setRateDisplay(new SubtitleSpeaker.RateDisplay() {
                @Override public void onRate(float eff) {
                    runOnUiThread(() -> tvRate.setText(
                        String.format(Locale.US, "%.1fx (cau nay %.1fx)",
                            rate, eff)));
                }
            });
            speaker.setSubtitles(subs);
            speaker.setAudioOffset(offset);
            speaker.setSpeechRate(rate);
            TtsPlaybackService.setSharedSubtitles(subs);
            TtsPlaybackService.setSharedOffset(offset);
            TtsPlaybackService.setSharedRate(rate);
        } else {
            toast("Chua co Google TTS tieng Viet. Cai 'Speech Services by Google'.");
        }
    }

    // ---------- che do thu cong ----------

    private void pickSrt() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/x-subrip", "text/plain"});
        try {
            startActivityForResult(i, REQ_SRT);
        } catch (Exception e) {
            toast("Khong mo duoc chon file: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SRT && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            loadSrt(data.getData());
        }
    }

    private void loadSrt(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            byte[] buf = new byte[256 * 1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            // Bo BOM giong utf-8-sig Python.
            String content = sb.toString();
            if (!content.isEmpty() && content.charAt(0) == 0xFEFF) {
                content = content.substring(1);
            }
            List<SubtitleCue> parsed = SubtitleParser.parseSrt(content);
            if (parsed.isEmpty()) {
                toast("SRT khong co phu de hop le.");
                return;
            }
            subs = parsed;
            duration = 0;
            for (SubtitleCue c : subs) duration = Math.max(duration, c.end);
            position = 0;
            if (speaker != null) speaker.setSubtitles(subs);
            TtsPlaybackService.setSharedSubtitles(subs);
            tvStatus.setText("Da nap " + subs.size() + " cau phu de.");
            toast("Da nap " + subs.size() + " cau.");
            syncUi();
        } catch (Exception e) {
            toast("Khong doc duoc SRT: " + e.getMessage());
        }
    }

    private void setPlaying(boolean p) {
        if (p && subs.isEmpty()) {
            toast("Chon file .srt truoc.");
            return;
        }
        playing = p;
        btnPlayPause.setText(p ? "Pause" : "Play");
        if (speaker != null) speaker.setPlaying(p);
        if (p) startService();
        pushToSpeaker();
    }

    private void startService() {
        Intent i = new Intent(this, TtsPlaybackService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void pushToSpeaker() {
        // V1: TTS duy nhat o Activity. Service chi giu process song.
        if (speaker != null && ttsReady) {
            speaker.setPlaying(playing);
            speaker.onPosition(position);
        }
    }

    private void highlight(int index) {
        if (index >= 0 && index < subs.size()) {
            tvCue.setText(subs.get(index).text);
        }
    }

    private void syncUi() {
        if (duration > 0) {
            seekPos.setProgress((int) (position / duration * 1000));
        }
        int idx = SubtitleParser.findIndex(subs, position + offset);
        if (idx >= 0 && idx < subs.size() && idx != lastShown) {
            lastShown = idx;
            tvCue.setText(subs.get(idx).text);
        }
    }

    private int lastShown = -2;

    // ---------- offset grid ----------

    private void buildOffsetGrid() {
        gridOffset.removeAllViews();
        for (float v : OFFSETS) {
            Button b = new Button(this);
            String sign = v > 0 ? "+" : "";
            b.setText(sign + String.format(Locale.US, "%.1fs", v));
            b.setOnClickListener(view -> {
                offset = v;
                if (speaker != null) speaker.setAudioOffset(v);
                TtsPlaybackService.setSharedOffset(v);
                getSharedPreferences("webtts", MODE_PRIVATE)
                        .edit().putFloat("offset", v).apply();
                buildOffsetGrid();
                toast("Offset " + sign + v + "s");
            });
            if (Math.abs(v - offset) < 0.01) {
                b.setBackgroundColor(0xFF4CAF50);
            }
            gridOffset.addView(b);
        }
    }

    // ---------- WebView ----------

    private void openWeb() {
        String url = "https://www.youtube.com/";
        webView.loadUrl(url);
        toast("Mo YouTube trong app. Phu de van nap thu cong (.srt).");
    }

    private void injectHook() {
        // Poll video dau tien moi 250ms, day ve Java.
        String js = "(function(){"
                + "if(window._webttsHook)return;window._webttsHook=true;"
                + "setInterval(function(){"
                + "var v=document.querySelector('video');"
                + "if(!v)return;"
                + "try{WebTTS.onProgress(v.currentTime||0,!v.paused);}catch(e){}"
                + "},250);})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    private class VideoBridge {
        @JavascriptInterface
        public void onProgress(final double time, final boolean playingNow) {
            runOnUiThread(() -> {
                position = time;
                if (playingNow != playing) setPlaying(playingNow);
                else pushToSpeaker();
                syncUi();
            });
        }
    }

    // ---------- misc ----------

    private void askNotif() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        try {
            stopService(new Intent(this, TtsPlaybackService.class));
        } catch (Exception ignored) {}
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
