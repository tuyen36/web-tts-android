package com.example.webtts;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
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
import java.util.Timer;
import java.util.TimerTask;

/**
 * V1.9: Man CAI DAT rieng (TTS) — mo app len thay man nay.
 * - Nap .srt, Play/Pause, offset, toc do, volume sub, chon giong,
 *   nut noi, xoa cache.
 * - Nut "Mo WebView" sang WebActivity xem YouTube full man hinh.
 * - Xem Netflix app goc: bam Play o day roi thoat sang Netflix,
 *   dieu khien bang nut noi mo 30%.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_SRT = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int REQ_OVERLAY = 1003;

    // Port tu AUDIO_OFFSET_OPTIONS Python.
    private static final float[] OFFSETS =
            {2.0f, 1.5f, 1.0f, 0.5f, 0.0f, -0.5f, -1.0f};

    private List<SubtitleCue> subs = new ArrayList<>();

    private TextView tvStatus;
    private TextView tvCue;
    private TextView tvRate;
    private TextView tvTtsVol;
    private Button btnPlayPause;
    private Button btnFloat;
    private Button btnVoice;
    private Button btnClearCache;
    private int voice = TtsPlaybackService.VOICE_GOOGLE;
    private SeekBar seekPos;
    private SeekBar seekRate;
    private SeekBar seekTtsVol;
    private LinearLayout rowOffset;

    private boolean playing = false;
    private double position = 0;
    private double duration = 0;
    private float offset = 0f;
    private float rate = 1.0f;
    private int ttsVol = 100;

    private Timer uiTimer;

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
        rowOffset = findViewById(R.id.rowOffset);

        // Hang volume giong doc (them bang code).
        tvTtsVol = new TextView(this);
        tvTtsVol.setText("Sub 100%");
        tvTtsVol.setTextColor(0xFFFFFFFF);
        tvTtsVol.setTextSize(12);
        seekTtsVol = new SeekBar(this);
        seekTtsVol.setMax(100);
        LinearLayout rateRow = (LinearLayout) seekRate.getParent();
        rateRow.addView(tvTtsVol);
        rateRow.addView(seekTtsVol);
        LinearLayout.LayoutParams ttsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        seekTtsVol.setLayoutParams(ttsLp);

        // Hang nut: nut noi + giong + xoa cache (them bang code).
        LinearLayout playRow = (LinearLayout) btnPlayPause.getParent();
        btnFloat = new Button(this);
        btnFloat.setText("Nut noi");
        btnFloat.setTextSize(12);
        playRow.addView(btnFloat);

        btnVoice = new Button(this);
        btnVoice.setTextSize(12);
        playRow.addView(btnVoice);
        btnVoice.setOnClickListener(v -> toggleVoice());

        btnClearCache = new Button(this);
        btnClearCache.setTextSize(12);
        playRow.addView(btnClearCache);
        btnClearCache.setOnClickListener(v -> clearCacheUi());

        SharedPreferences sp =
                getSharedPreferences("webtts", MODE_PRIVATE);
        offset = sp.getFloat("offset", 0f);
        rate = sp.getFloat("rate", 1.0f);
        ttsVol = sp.getInt("ttsVol", 100);
        voice = sp.getInt("voice", TtsPlaybackService.VOICE_GOOGLE);

        buildOffsetRow();
        refreshVoiceBtn();
        refreshCacheBtn();
        seekRate.setProgress(Math.round((rate - 0.5f) / 2.0f * 200));
        tvRate.setText(String.format(Locale.US, "%.1fx", rate));
        seekTtsVol.setProgress(ttsVol);
        tvTtsVol.setText("Sub " + ttsVol + "%");
        refreshPlayBtn();

        findViewById(R.id.btnPickSrt).setOnClickListener(v -> pickSrt());
        btnPlayPause.setOnClickListener(v -> setPlaying(!playing));
        btnFloat.setOnClickListener(v -> ensureFloat());
        findViewById(R.id.btnOpenWeb).setOnClickListener(v -> {
            startActivity(new Intent(this, WebActivity.class));
        });

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
                tvTtsVol.setText("Sub " + p + "%");
                TtsPlaybackService.setTtsVolume(p);
                sp.edit().putInt("ttsVol", p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // Poll trang thai service de refresh nut Play + cau hien tai.
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

    // ---------- che do song song ----------

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
        } else if (requestCode == REQ_OVERLAY) {
            if (TtsPlaybackService.canDrawOverlay(this)) {
                TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_SHOW_FLOAT);
                toast("Da bat nut noi — sang Netflix van dieu khien duoc.");
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
            toast("Da nap " + subs.size() + " cau. Bam Play roi sang Netflix.");
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
        btnPlayPause.setText(playing ? "Pause" : "Play");
    }

    /** Doi giong Google offline <-> HoaiMy online. */
    private void toggleVoice() {
        voice = (voice == TtsPlaybackService.VOICE_GOOGLE)
                ? TtsPlaybackService.VOICE_HOAIMY
                : TtsPlaybackService.VOICE_GOOGLE;
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putInt("voice", voice).apply();
        ensureService();
        TtsPlaybackService.setVoice(voice);
        refreshVoiceBtn();
        if (voice == TtsPlaybackService.VOICE_HOAIMY) {
            toast("Giong HoaiMy (online) — can mang, preload 35 cau.");
        } else {
            toast("Giong Google (offline) — khong can mang.");
        }
    }

    private void refreshVoiceBtn() {
        if (btnVoice == null) return;
        btnVoice.setText(voice == TtsPlaybackService.VOICE_HOAIMY
                ? "HoaiMy" : "Google");
    }

    /** Xoa cache HoaiMy + cap nhat chu tren nut (VD "Xoa cache (35MB)"). */
    private void clearCacheUi() {
        ensureService();
        int removed = TtsPlaybackService.clearCache();
        refreshCacheBtn();
        toast(removed > 0 ? "Da xoa " + removed + " file cache."
                : "Cache dang trong.");
    }

    private void refreshCacheBtn() {
        if (btnClearCache == null) return;
        try {
            btnClearCache.setText(TtsPlaybackService.cacheText());
        } catch (Exception e) {
            btnClearCache.setText("Xoa cache");
        }
    }

    private void ensureService() {
        Intent i = new Intent(this, TtsPlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    /** Xin quyen overlay roi hien nut noi mo 30%. */
    private void ensureFloat() {
        ensureService();
        if (!TtsPlaybackService.canDrawOverlay(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_OVERLAY);
                toast("Cap quyen 'Hien thi tren ung dung khac' roi quay lai.");
            } catch (Exception e) {
                toast("Khong mo duoc xin quyen: " + e.getMessage());
            }
            return;
        }
        TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_SHOW_FLOAT);
        toast("Da bat nut noi — sang Netflix van dieu khien duoc.");
    }

    private void syncUi() {
        if (duration > 0) {
            seekPos.setProgress((int) (position / duration * 1000));
        }
        int idx = SubtitleParser.findIndex(subs, position + offset);
        if (idx >= 0 && idx < subs.size()) {
            tvCue.setText(subs.get(idx).text);
        }
    }

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
                TtsPlaybackService.setOffset(val);
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
    protected void onResume() {
        super.onResume();
        playing = TtsPlaybackService.isPlayingNow();
        voice = TtsPlaybackService.getVoice();
        position = TtsPlaybackService.getPosition();
        refreshPlayBtn();
        refreshVoiceBtn();
        refreshCacheBtn();
        syncUi();
    }

    @Override
    protected void onDestroy() {
        if (uiTimer != null) uiTimer.cancel();
        super.onDestroy();
        // Khong stop service: de doc nen khi sang Netflix.
    }
}
