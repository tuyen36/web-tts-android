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
import android.provider.Settings;
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
import android.widget.ScrollView;
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
import java.util.Timer;
import java.util.TimerTask;

/**
 * V2.0: 1 Activity 2 TAB — Cai dat + Web chung 1 WebView.
 *
 * - Bo offset dong bo (can bang nut noi +-1s + gio chay).
 * - Bo WebActivity rieng: doi tab chi setVisibility -> WebView KHONG
 *   tai lai trang, video van phat.
 * - Sub doc tu service nen: ca 2 tab nghe chung 1 tieng (het mat tieng
 *   khi sang tab web). WebView day currentTime ve service nhu cu.
 * - UI hien dai theo ban Netflix TTS.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_SRT = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int REQ_OVERLAY = 1003;

    private static final String PRESET_YT = "https://www.youtube.com/";
    private static final String PRESET_FPT = "https://fptplay.vn/";
    private static final String PRESET_TV360 = "https://tv360.vn/";

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/122.0.0.0 Safari/537.36";

    // ----- tab -----
    private Button tabSetup;
    private Button tabWeb;
    private ScrollView paneSetup;
    private LinearLayout paneWeb;

    // ----- cai dat -----
    private List<SubtitleCue> subs = new ArrayList<>();
    private TextView tvStatus;
    private TextView tvCue;
    private TextView tvTime;
    private TextView tvRate;
    private TextView tvTtsVol;
    private TextView tvVoiceBadge;
    private Button btnPlayPause;
    private Button btnFloat;
    private Button btnVoiceGoogle;
    private Button btnVoiceHoaiMy;
    private Button btnClearCache;
    private int voice = TtsPlaybackService.VOICE_GOOGLE;
    private SeekBar seekPos;
    private SeekBar seekRate;
    private SeekBar seekTtsVol;

    private boolean playing = false;
    private double position = 0;
    private double duration = 0;
    private float rate = 1.0f;
    private int ttsVol = 100;

    // ----- web -----
    private EditText etUrl;
    private WebView webView;
    private FrameLayout webContainer;
    private LinearLayout webBar;
    private LinearLayout rowPreset;
    private LinearLayout volBar;
    private TextView tvVideoVol;
    private SeekBar seekVideoVol;
    private Button btnMute;
    private Button btnFullscreen;
    private Button btnDesktop;

    private int videoVol = 30;
    private boolean videoMuted = false;
    private boolean fullscreen = false;
    private boolean desktopMode = true;
    private boolean webLoaded = false;

    private View videoCustomView;
    private WebChromeClient.CustomViewCallback videoCustomCb;

    private Timer uiTimer;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Tab
        tabSetup = findViewById(R.id.tabSetup);
        tabWeb = findViewById(R.id.tabWeb);
        paneSetup = findViewById(R.id.paneSetup);
        paneWeb = findViewById(R.id.paneWeb);
        tabSetup.setOnClickListener(v -> showTab(true));
        tabWeb.setOnClickListener(v -> showTab(false));

        // Cai dat
        tvStatus = findViewById(R.id.tvStatus);
        tvCue = findViewById(R.id.tvCue);
        tvTime = findViewById(R.id.tvTime);
        tvRate = findViewById(R.id.tvRate);
        tvTtsVol = findViewById(R.id.tvTtsVol);
        tvVoiceBadge = findViewById(R.id.tvVoiceBadge);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnFloat = findViewById(R.id.btnFloat);
        btnVoiceGoogle = findViewById(R.id.btnVoiceGoogle);
        btnVoiceHoaiMy = findViewById(R.id.btnVoiceHoaiMy);
        btnClearCache = findViewById(R.id.btnClearCache);
        seekPos = findViewById(R.id.seekPos);
        seekRate = findViewById(R.id.seekRate);
        seekTtsVol = findViewById(R.id.seekTtsVol);

        // Web
        etUrl = findViewById(R.id.etUrl);
        webView = findViewById(R.id.webView);
        webContainer = findViewById(R.id.webContainer);
        webBar = findViewById(R.id.webBar);
        rowPreset = findViewById(R.id.rowPreset);
        volBar = findViewById(R.id.volBar);
        tvVideoVol = findViewById(R.id.tvVideoVol);
        seekVideoVol = findViewById(R.id.seekVideoVol);
        btnMute = findViewById(R.id.btnMute);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnDesktop = findViewById(R.id.btnDesktop);

        SharedPreferences sp = getSharedPreferences("webtts", MODE_PRIVATE);
        rate = sp.getFloat("rate", 1.0f);
        ttsVol = sp.getInt("ttsVol", 100);
        voice = sp.getInt("voice", TtsPlaybackService.VOICE_GOOGLE);
        videoVol = sp.getInt("videoVol", 30);
        desktopMode = sp.getBoolean("desktopMode", true);
        etUrl.setText(sp.getString("lastUrl", PRESET_YT));

        refreshVoiceUi();
        refreshCacheBtn();
        seekRate.setProgress(Math.round((rate - 0.5f) / 2.0f * 200));
        tvRate.setText(String.format(Locale.US, "%.1fx", rate));
        seekTtsVol.setProgress(ttsVol);
        tvTtsVol.setText(ttsVol + "%");
        seekVideoVol.setProgress(videoVol);
        tvVideoVol.setText(videoVol + "%");
        refreshMuteBtn();
        refreshDesktopBtn();
        refreshPlayBtn();
        showTab(true);

        findViewById(R.id.btnPickSrt).setOnClickListener(v -> pickSrt());
        btnPlayPause.setOnClickListener(v -> setPlaying(!playing));
        btnFloat.setOnClickListener(v -> ensureFloat());
        btnVoiceGoogle.setOnClickListener(v -> setVoice(
                TtsPlaybackService.VOICE_GOOGLE));
        btnVoiceHoaiMy.setOnClickListener(v -> setVoice(
                TtsPlaybackService.VOICE_HOAIMY));
        btnClearCache.setOnClickListener(v -> clearCacheUi());

        findViewById(R.id.btnGo).setOnClickListener(v -> goUrl());
        findViewById(R.id.btnPresetYt).setOnClickListener(
                v -> openPreset(PRESET_YT));
        findViewById(R.id.btnPresetFpt).setOnClickListener(
                v -> openPreset(PRESET_FPT));
        findViewById(R.id.btnPresetTv).setOnClickListener(
                v -> openPreset(PRESET_TV360));
        btnDesktop.setOnClickListener(v -> toggleDesktopMode());
        btnMute.setOnClickListener(v -> toggleMute());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        seekPos.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && duration > 0) {
                    position = duration * p / 1000.0;
                    TtsPlaybackService.setPosition(position);
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
                TtsPlaybackService.setRate(rate);
                sp.edit().putFloat("rate", rate).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekTtsVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                ttsVol = p;
                tvTtsVol.setText(p + "%");
                TtsPlaybackService.setTtsVolume(p);
                sp.edit().putInt("ttsVol", p).apply();
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

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportMultipleWindows(false);
        applyDesktopMode(ws);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                enterVideoFullscreen(view, cb);
            }
            @Override
            public void onHideCustomView() {
                exitVideoFullscreen();
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                injectHook();
                applyVideoVolume();
            }
        });
        webView.addJavascriptInterface(new VideoBridge(), "WebTTS");

        uiTimer = new Timer();
        uiTimer.schedule(new TimerTask() {
            @Override public void run() {
                runOnUiThread(() -> {
                    boolean p = TtsPlaybackService.isPlayingNow();
                    if (p != playing) {
                        playing = p;
                        refreshPlayBtn();
                    }
                    String cue = TtsPlaybackService.currentCueText();
                    if (cue != null && !cue.equals(tvCue.getText().toString())) {
                        tvCue.setText(cue);
                    }
                    tvTime.setText(TtsPlaybackService.timeText());
                    double sp2 = TtsPlaybackService.getPosition();
                    if (duration > 0 && Math.abs(sp2 - position) > 0.6) {
                        position = sp2;
                        syncUi();
                    }
                });
            }
        }, 500, 500);

        askNotif();
    }

    // ---------- tab (khong tai lai trang) ----------

    private void showTab(boolean setup) {
        paneSetup.setVisibility(setup ? View.VISIBLE : View.GONE);
        paneWeb.setVisibility(setup ? View.GONE : View.VISIBLE);
        tabSetup.setAlpha(setup ? 1.0f : 0.5f);
        tabWeb.setAlpha(setup ? 0.5f : 1.0f);
        if (!setup && !webLoaded) {
            webLoaded = true;
            webView.loadUrl(etUrl.getText().toString());
        }
    }

    // ---------- cai dat ----------

    private void pickSrt() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/x-subrip", "text/plain"});
        try {
            startActivityForResult(i, 1001);
        } catch (Exception e) {
            toast("Khong mo duoc chon file: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            loadSrt(data.getData());
        } else if (requestCode == 1003) {
            if (TtsPlaybackService.canDrawOverlay(this)) {
                TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_SHOW_FLOAT);
                toast("Da bat nut noi.");
            }
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
            ensureService();
            TtsPlaybackService.setSharedSubtitles(subs);
            tvStatus.setText("Da nap " + subs.size() + " cau phu de.");
            toast("Da nap " + subs.size() + " cau.");
            refreshCacheBtn();
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
        if (p && voice == TtsPlaybackService.VOICE_HOAIMY) {
            toast("HoaiMy online: dang tai 35 cau dau, cho chut...");
        }
        playing = p;
        refreshPlayBtn();
        ensureService();
        if (p) TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_PLAY);
        else TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_PAUSE);
    }

    private void refreshPlayBtn() {
        if (playing) {
            btnPlayPause.setText("⏸  Pause");
            btnPlayPause.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFA40E26));
        } else {
            btnPlayPause.setText("▶  Play");
            btnPlayPause.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1F6FEB));
        }
    }

    private void setVoice(int v) {
        voice = v;
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putInt("voice", v).apply();
        ensureService();
        TtsPlaybackService.setVoice(v);
        refreshVoiceUi();
    }

    private void refreshVoiceUi() {
        boolean hoaimy = voice == TtsPlaybackService.VOICE_HOAIMY;
        tvVoiceBadge.setText(hoaimy ? "HoaiMy" : "Google");
        btnVoiceGoogle.setAlpha(hoaimy ? 0.5f : 1.0f);
        btnVoiceHoaiMy.setAlpha(hoaimy ? 1.0f : 0.5f);
    }

    private void clearCacheUi() {
        ensureService();
        int removed = TtsPlaybackService.clearCache();
        refreshCacheBtn();
        toast(removed > 0 ? "Da xoa " + removed + " file cache."
                : "Cache dang trong.");
    }

    private void refreshCacheBtn() {
        try {
            findViewById(R.id.btnClearCache);
            ((Button) findViewById(R.id.btnClearCache))
                    .setText("🗑  " + TtsPlaybackService.cacheText());
        } catch (Exception ignored) {}
    }

    private void ensureService() {
        Intent i = new Intent(this, TtsPlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    private void ensureFloat() {
        ensureService();
        if (!TtsPlaybackService.canDrawOverlay(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, 1003);
                toast("Cap quyen 'Hien thi tren ung dung khac' roi quay lai.");
            } catch (Exception e) {
                toast("Khong mo duoc xin quyen: " + e.getMessage());
            }
            return;
        }
        TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_SHOW_FLOAT);
        toast("Da bat nut noi.");
    }

    private void syncUi() {
        if (duration > 0) {
            seekPos.setProgress((int) (position / duration * 1000));
        }
        tvTime.setText(TtsPlaybackService.timeText());
        int idx = SubtitleParser.findIndex(subs, position);
        if (idx >= 0 && idx < subs.size()) {
            tvCue.setText(subs.get(idx).text);
        }
    }

    // ---------- web (chung WebView) ----------

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

    private void applyDesktopMode(WebSettings ws) {
        try {
            if (desktopMode) ws.setUserAgentString(DESKTOP_UA);
            else ws.setUserAgentString(null);
        } catch (Exception ignored) {}
        refreshDesktopBtn();
    }

    private void refreshDesktopBtn() {
        if (btnDesktop == null) return;
        btnDesktop.setText(desktopMode ? "Desktop: Bat" : "Desktop: Tat");
    }

    private void toggleDesktopMode() {
        desktopMode = !desktopMode;
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putBoolean("desktopMode", desktopMode).apply();
        try {
            applyDesktopMode(webView.getSettings());
        } catch (Exception ignored) {}
        refreshDesktopBtn();
        try {
            webView.reload();
        } catch (Exception ignored) {}
    }

    private void injectHook() {
        String js = "(function(){"
                + "if(window._webttsTimer)clearInterval(window._webttsTimer);"
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
    }

    private void refreshMuteBtn() {
        btnMute.setText(videoMuted ? "Mo tieng" : "Tat tieng");
    }

    private void toggleFullscreen() {
        if (videoCustomView != null) {
            exitVideoFullscreen();
            return;
        }
        fullscreen = !fullscreen;
        int gone = fullscreen ? View.GONE : View.VISIBLE;
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
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            try {
                setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            } catch (Exception ignored) {}
        }
    }

    private void enterVideoFullscreen(View view,
                                      WebChromeClient.CustomViewCallback cb) {
        if (videoCustomView != null) {
            try { cb.onCustomViewHidden(); } catch (Exception ignored) {}
            return;
        }
        videoCustomView = view;
        videoCustomCb = cb;
        try {
            android.widget.FrameLayout decor =
                    (android.widget.FrameLayout) getWindow().getDecorView();
            decor.addView(videoCustomView,
                    new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.Gravity.CENTER));
        } catch (Exception ignored) {}
        webBar.setVisibility(View.GONE);
        rowPreset.setVisibility(View.GONE);
        volBar.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Exception ignored) {}
        btnFullscreen.setText("Thu nho");
    }

    private void exitVideoFullscreen() {
        if (videoCustomView == null) return;
        try {
            android.widget.FrameLayout decor =
                    (android.widget.FrameLayout) getWindow().getDecorView();
            decor.removeView(videoCustomView);
        } catch (Exception ignored) {}
        try {
            if (videoCustomCb != null) videoCustomCb.onCustomViewHidden();
        } catch (Exception ignored) {}
        videoCustomView = null;
        videoCustomCb = null;
        fullscreen = false;
        webBar.setVisibility(View.VISIBLE);
        rowPreset.setVisibility(View.VISIBLE);
        volBar.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } catch (Exception ignored) {}
        btnFullscreen.setText("Phong to");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onBackPressed() {
        if (videoCustomView != null) {
            exitVideoFullscreen();
            return;
        }
        if (fullscreen) {
            toggleFullscreen();
            return;
        }
        // O tab web ma web lui duoc thi lui web, khong thoat app.
        if (paneWeb.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (uiTimer != null) uiTimer.cancel();
        try {
            webView.destroy();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    /**
     * WebView day gio YouTube that ve service (moi 250ms).
     * Service tu Play/Pause theo video + nhay moc khi seek.
     * Khong cat cau khi lech nho (<1.2s) de het mat tieng.
     */
    private class VideoBridge {
        @JavascriptInterface
        public void onProgress(final double time, final boolean playingNow) {
            onProgress(time, playingNow, 0);
        }

        @JavascriptInterface
        public void onProgress(final double time, final boolean playingNow,
                               final double webDur) {
            // Day thang ve service (khong qua bien playing cua Activity).
            TtsPlaybackService.cmdWebProgress(MainActivity.this, time,
                    playingNow && !subs.isEmpty());
            // Refresh nut Play tren UI theo service.
            runOnUiThread(() -> {
                boolean p = TtsPlaybackService.isPlayingNow();
                if (p != playing) {
                    playing = p;
                    refreshPlayBtn();
                }
            });
        }
    }

    private void askNotif() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1002);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        playing = TtsPlaybackService.isPlayingNow();
        voice = TtsPlaybackService.getVoice();
        position = TtsPlaybackService.getPosition();
        refreshPlayBtn();
        refreshVoiceUi();
        refreshCacheBtn();
        syncUi();
    }
}
