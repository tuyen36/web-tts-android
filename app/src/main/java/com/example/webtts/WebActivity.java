package com.example.webtts;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
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

/**
 * V1.9: Man WebView RIENG — full man hinh.
 * Mo tu MainActivity (nut "Mo WebView") hoac tu nut noi ("Mo web").
 * Co nut "Cai dat" tren thanh tren de quay lai man thiet lap.
 * Doc sub chay nen qua TtsPlaybackService nen chuyen man khong ngat.
 */
public class WebActivity extends AppCompatActivity {

    private static final String PRESET_YT = "https://www.youtube.com/";
    private static final String PRESET_NF = "https://www.netflix.com/browse";
    private static final String PRESET_FPT = "https://fptplay.vn/";
    private static final String PRESET_TV360 = "https://tv360.vn/";

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/122.0.0.0 Safari/537.36";

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

    private View videoCustomView;
    private WebChromeClient.CustomViewCallback videoCustomCb;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);

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
        videoVol = sp.getInt("videoVol", 30);
        desktopMode = sp.getBoolean("desktopMode", true);
        String lastUrl = sp.getString("lastUrl", PRESET_YT);
        etUrl.setText(lastUrl);

        seekVideoVol.setProgress(videoVol);
        tvVideoVol.setText(videoVol + "%");
        refreshMuteBtn();
        refreshDesktopBtn();

        // Nut Cai dat: quay lai man thiet lap (MainActivity).
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        });
        findViewById(R.id.btnGo).setOnClickListener(v -> goUrl());
        findViewById(R.id.btnPresetYt).setOnClickListener(
                v -> openPreset(PRESET_YT));
        findViewById(R.id.btnPresetNf).setOnClickListener(
                v -> openPreset(PRESET_NF));
        findViewById(R.id.btnPresetFpt).setOnClickListener(
                v -> openPreset(PRESET_FPT));
        findViewById(R.id.btnPresetTv).setOnClickListener(
                v -> openPreset(PRESET_TV360));
        btnDesktop.setOnClickListener(v -> toggleDesktopMode());
        btnMute.setOnClickListener(v -> toggleMute());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

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

        // Mo link cuoi (hoac YouTube) ngay khi vao man.
        webView.loadUrl(etUrl.getText().toString());
    }

    // ---------- dieu huong ----------

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

    /** An thanh de WebView full (khac fullscreen video YouTube). */
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
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            webView.destroy();
        } catch (Exception ignored) {}
        super.onDestroy();
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
                // Day gio that ve service de doc sub theo (giong cu).
                TtsPlaybackService.setPosition(time);
                if (playingNow) {
                    TtsPlaybackService.cmd(WebActivity.this,
                            TtsPlaybackService.ACTION_PLAY);
                } else {
                    TtsPlaybackService.cmd(WebActivity.this,
                            TtsPlaybackService.ACTION_PAUSE);
                }
            });
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
