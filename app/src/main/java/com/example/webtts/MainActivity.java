package com.example.webtts;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
 * V1.2: UI gon + da trang web + fullscreen + volume video goc.
 *
 * 1. THU CONG: chon .srt -> Play -> SeekBar nhu timeline.
 * 2. WEBVIEW: nhap link bat ky (YouTube / Netflix web / FPT / TV360...)
 *    hoac bam preset -> JS hook video.currentTime + paused.
 *    Phu de van nap thu cong (.srt).
 *
 * Ghi chu am thanh: TTS he thong va video WebView la 2 stream doc lap.
 * Khong giam truc tiep volume cua <video> tu Java, nhung co the
 * dat video.volume / muted qua JS. Mac dinh video 30% de giong doc noi.
 */
public class MainActivity extends AppCompatActivity
        implements TextToSpeech.OnInitListener {

    private static final int REQ_SRT = 1001;
    private static final int REQ_NOTIF = 1002;

    // Port tu AUDIO_OFFSET_OPTIONS Python.
    private static final float[] OFFSETS =
            {2.0f, 1.5f, 1.0f, 0.5f, 0.0f, -0.5f, -1.0f};

    private static final String PRESET_YT = "https://www.youtube.com/";
    private static final String PRESET_NF = "https://www.netflix.com/browse";
    private static final String PRESET_FPT = "https://fptplay.vn/";
    private static final String PRESET_TV360 = "https://tv360.vn/";

    private TextToSpeech tts;
    private SubtitleSpeaker speaker;
    private List<SubtitleCue> subs = new ArrayList<>();

    private TextView tvStatus;
    private TextView tvCue;
    private TextView tvRate;
    private TextView tvVideoVol;
    private Button btnPlayPause;
    private Button btnMute;
    private Button btnFullscreen;
    private SeekBar seekPos;
    private SeekBar seekRate;
    private SeekBar seekVideoVol;
    private LinearLayout rowOffset;
    private LinearLayout ttsPanel;
    private LinearLayout webBar;
    private LinearLayout rowPreset;
    private LinearLayout volBar;
    private EditText etUrl;
    private FrameLayout webContainer;
    private WebView webView;

    private boolean playing = false;
    private boolean ttsReady = false;
    private double position = 0;   // giay
    private double duration = 0;   // giay (max end cue)
    private float offset = 0f;
    private float rate = 1.0f;
    private int videoVol = 30;     // % am luong video goc
    private boolean videoMuted = false;
    private boolean fullscreen = false;
    /** Gia desktop de Netflix khong ep mo app goc. Mac dinh BAT. */
    private boolean desktopMode = true;
    private Button btnDesktop;

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
        tvVideoVol = findViewById(R.id.tvVideoVol);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnMute = findViewById(R.id.btnMute);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        seekPos = findViewById(R.id.seekPos);
        seekRate = findViewById(R.id.seekRate);
        seekVideoVol = findViewById(R.id.seekVideoVol);
        rowOffset = findViewById(R.id.rowOffset);
        ttsPanel = findViewById(R.id.ttsPanel);
        webBar = findViewById(R.id.webBar);
        rowPreset = findViewById(R.id.rowPreset);
        volBar = findViewById(R.id.volBar);
        etUrl = findViewById(R.id.etUrl);
        webContainer = findViewById(R.id.webContainer);
        webView = findViewById(R.id.webView);

        SharedPreferences sp =
                getSharedPreferences("webtts", MODE_PRIVATE);
        offset = sp.getFloat("offset", 0f);
        rate = sp.getFloat("rate", 1.0f);
        videoVol = sp.getInt("videoVol", 30);
        desktopMode = sp.getBoolean("desktopMode", true);
        String lastUrl = sp.getString("lastUrl", PRESET_YT);
        etUrl.setText(lastUrl);

        tts = new TextToSpeech(this, this);

        buildOffsetRow();
        seekRate.setProgress(Math.round((rate - 0.5f) / 2.0f * 200));
        tvRate.setText(String.format(Locale.US, "%.1fx", rate));
        seekVideoVol.setProgress(videoVol);
        tvVideoVol.setText(videoVol + "%");
        refreshMuteBtn();

        findViewById(R.id.btnPickSrt).setOnClickListener(v -> pickSrt());
        btnPlayPause.setOnClickListener(v -> setPlaying(!playing));
        findViewById(R.id.btnGo).setOnClickListener(v -> goUrl());
        findViewById(R.id.btnPresetYt).setOnClickListener(
                v -> openPreset(PRESET_YT));
        findViewById(R.id.btnPresetNf).setOnClickListener(
                v -> openPreset(PRESET_NF));
        findViewById(R.id.btnPresetFpt).setOnClickListener(
                v -> openPreset(PRESET_FPT));
        findViewById(R.id.btnPresetTv).setOnClickListener(
                v -> openPreset(PRESET_TV360));
        btnDesktop = findViewById(R.id.btnDesktop);
        btnDesktop.setOnClickListener(v -> toggleDesktopMode());
        refreshDesktopBtn();
        btnMute.setOnClickListener(v -> toggleMute());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

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
        seekVideoVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                videoVol = p;
                tvVideoVol.setText(p + "%");
                sp.edit().putInt("videoVol", p).apply();
                if (fromUser) {
                    if (p > 0 && videoMuted) {
                        videoMuted = false;
                        refreshMuteBtn();
                    }
                    applyVideoVolume();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // WebView: gia desktop + doc currentTime tu <video>.
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportMultipleWindows(false);
        applyDesktopMode(ws);
        // Cho fullscreen video HTML5 (vimeo/player mot so trang can).
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                injectHook();
                applyVideoVolume();
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

    // ---------- offset row (1 hang cuon ngang) ----------

    private void buildOffsetRow() {
        rowOffset.removeAllViews();
        for (float v : OFFSETS) {
            Button b = new Button(this);
            String sign = v > 0 ? "+" : "";
            b.setText(sign + String.format(Locale.US, "%.1fs", v));
            b.setTextSize(12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(2, 0, 2, 0);
            b.setLayoutParams(lp);
            final float val = v;
            b.setOnClickListener(view -> {
                offset = val;
                if (speaker != null) speaker.setAudioOffset(val);
                TtsPlaybackService.setSharedOffset(val);
                getSharedPreferences("webtts", MODE_PRIVATE)
                        .edit().putFloat("offset", val).apply();
                buildOffsetRow();
                toast("Offset " + sign + val + "s");
            });
            if (Math.abs(v - offset) < 0.01) {
                b.setBackgroundColor(0xFF4CAF50);
            }
            rowOffset.addView(b);
        }
    }

    // ---------- WebView da trang ----------

    private void goUrl() {
        String raw = etUrl.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("Nhap link trang web truoc.");
            return;
        }
        String url = raw;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        etUrl.setText(url);
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putString("lastUrl", url).apply();
        webView.loadUrl(url);
    }

    private void openPreset(String url) {
        etUrl.setText(url);
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putString("lastUrl", url).apply();
        webView.loadUrl(url);
    }

    // ---------- Gia desktop cho Netflix ----------

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/122.0.0.0 Safari/537.36";

    private void applyDesktopMode(WebSettings ws) {
        try {
            if (desktopMode) {
                ws.setUserAgentString(DESKTOP_UA);
            } else {
                ws.setUserAgentString(null); // ve UA mac dinh WebView
            }
        } catch (Exception ignored) {}
        refreshDesktopBtn();
    }

    private void refreshDesktopBtn() {
        if (btnDesktop != null) {
            btnDesktop.setText(desktopMode ? "Desktop: Bat" : "Desktop: Tat");
        }
    }

    private void toggleDesktopMode() {
        desktopMode = !desktopMode;
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putBoolean("desktopMode", desktopMode).apply();
        try {
            applyDesktopMode(webView.getSettings());
        } catch (Exception ignored) {}
        refreshDesktopBtn();
        // Doi UA phai load lai trang moi co tac dung.
        try {
            webView.reload();
        } catch (Exception ignored) {}
        toast(desktopMode
                ? "Gia desktop BAT — Netflix se cho xem web."
                : "Gia desktop TAT — ve giao dien mobile.");
    }

    private void injectHook() {
        // Hook moi <video> tren trang (khong chi video dau tien):
        // poll 250ms + bat su kien play/pause/seeked de dong bo nhanh.
        // Giu lastHooked de biet trang nao chua hook (SPA doi video khong load lai).
        String js = "(function(){"
                + "if(window._webttsTimer)clearInterval(window._webttsTimer);"
                + "window._webttsHook=false;"
                + "function vids(){return document.querySelectorAll('video');}"
                + "function best(){var vs=vids();if(!vs||!vs.length)return null;"
                + "var b=vs[0];for(var i=0;i<vs.length;i++){"
                + "if(!vs[i].paused){b=vs[i];break;}"
                + "if((vs[i].clientWidth||0)>(b.clientWidth||0))b=vs[i];}return b;}"
                + "function applyVol(){"
                + "try{var vs=vids();for(var i=0;i<vs.length;i++){"
                + "vs[i].volume=" + (videoVol / 100.0) + ";"
                + "vs[i].muted=" + (videoMuted ? "true" : "false") + ";"
                + "}}catch(e){}}"
                + "window._webttsApplyVol=applyVol;applyVol();"
                + "window._webttsTimer=setInterval(function(){"
                + "var v=best();if(!v)return;"
                + "try{WebTTS.onProgress(v.currentTime||0,!v.paused,v.duration||0);}catch(e){}"
                + "},250);})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    /** Dat volume/mute cho moi <video> tren trang. */
    private void applyVideoVolume() {
        String js = "(function(){"
                + "if(window._webttsApplyVol){try{window._webttsApplyVol();}catch(e){}}"
                + "try{var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){"
                + "vs[i].volume=" + (videoVol / 100.0) + ";"
                + "vs[i].muted=" + (videoMuted ? "true" : "false") + ";"
                + "}}catch(e){}})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    private void toggleMute() {
        videoMuted = !videoMuted;
        refreshMuteBtn();
        applyVideoVolume();
        toast(videoMuted ? "Da tat tieng video goc." : "Da mo tieng video goc.");
    }

    private void refreshMuteBtn() {
        btnMute.setText(videoMuted ? "Mo tieng" : "Tat tieng");
    }

    /** Fullscreen: an cum TTS + thanh trang de video chiem het man hinh. */
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        int gone = fullscreen ? View.GONE : View.VISIBLE;
        ttsPanel.setVisibility(gone);
        webBar.setVisibility(gone);
        rowPreset.setVisibility(gone);
        volBar.setVisibility(gone);
        btnFullscreen.setText(fullscreen ? "Thu nho" : "Phong to");
        if (fullscreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            try {
                setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } catch (Exception ignored) {}
            toast("Che do phong to — bam Thu nho de ve.");
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            try {
                setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Khong lam gi dac biet: layout tu gian.
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            toggleFullscreen();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private class VideoBridge {
        @JavascriptInterface
        public void onProgress(final double time, final boolean playingNow) {
            onProgress(time, playingNow, 0);
        }

        @JavascriptInterface
        public void onProgress(final double time, final boolean playingNow,
                               final double webDur) {
            runOnUiThread(() -> {
                position = time;
                if (webDur > 0 && (duration <= 0 || Math.abs(webDur - duration) > 5)) {
                    // Neu chua co sub, lay duration tu video de SeekBar chay duoc.
                    if (subs.isEmpty()) duration = webDur;
                }
                if (playingNow != playing) {
                    // Chi tu bat/tat khi da co sub; khong co sub thi giu nut tay.
                    if (!subs.isEmpty()) setPlaying(playingNow);
                    else {
                        playing = playingNow;
                        btnPlayPause.setText(playing ? "Pause" : "Play");
                    }
                } else {
                    pushToSpeaker();
                }
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
