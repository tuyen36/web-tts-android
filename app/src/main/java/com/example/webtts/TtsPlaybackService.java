package com.example.webtts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.Locale;

/**
 * V1.4: Service doc sub CHAY NEN + nut noi dieu khien.
 *
 * - Om tron TextToSpeech + SubtitleSpeaker o day (DUY NHAT, khong con
 *   TTS o Activity -> het loi doc trung giong).
 * - MainActivity chi gui lenh (play/pause/seek/offset/rate/volume) + sub.
 * - Nut noi tron mo 50% luon tren cung (TYPE_APPLICATION_OVERLAY),
 *   keo di duoc. Bam vao bung panel: Play/Pause, -0.5s/+0.5s,
 *   volume giong doc, Stop.
 * - Ticker chay doc lap 250ms nen tat man / sang app Netflix van doc.
 */
public class TtsPlaybackService extends Service
        implements TextToSpeech.OnInitListener {

    // ----- lenh tu Activity / nut noi -----
    public static final String ACTION_PLAY = "com.example.webtts.PLAY";
    public static final String ACTION_PAUSE = "com.example.webtts.PAUSE";
    public static final String ACTION_TOGGLE = "com.example.webtts.TOGGLE";
    public static final String ACTION_STOP_ALL = "com.example.webtts.STOP_ALL";
    public static final String ACTION_NUDGE = "com.example.webtts.NUDGE";
    public static final String ACTION_SHOW_FLOAT = "com.example.webtts.SHOW_FLOAT";
    public static final String ACTION_HIDE_FLOAT = "com.example.webtts.HIDE_FLOAT";
    public static final String EXTRA_DELTA = "delta";

    private static final String CH_ID = "webtts_playback";
    private static final int NOTIF_ID = 1001;

    // Do mo nut noi theo yeu cau user: 50%.
    private static final float FLOAT_ALPHA = 0.5f;

    // ----- state dung chung (static de Activity doc nhanh) -----
    private static List<SubtitleCue> sharedSubs =
            java.util.Collections.emptyList();
    private static int sharedCount = 0;

    public static void setSharedSubtitles(List<SubtitleCue> subs) {
        sharedSubs = subs == null
                ? java.util.Collections.emptyList() : subs;
        sharedCount = sharedSubs.size();
        if (running != null) running.applyShared();
    }

    private static TtsPlaybackService running;

    public static boolean isRunning() { return running != null; }
    public static boolean isPlayingNow() {
        return running != null && running.playing;
    }
    public static String currentCueText() {
        if (running == null) return "...";
        return running.lastCueText;
    }

    // ----- instance -----
    private TextToSpeech tts;
    private SubtitleSpeaker speaker;
    private boolean ttsReady = false;

    private boolean playing = false;
    private double position = 0;
    private double duration = 0;
    private float offset = 0f;
    private float rate = 1.0f;
    private int ttsVol = 100;
    /** true = sub ra loa bao thuc (tach khoi Netflix), false = chung media. */
    private boolean splitAudio = true;
    private String lastCueText = "...";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (playing && duration > 0 && ttsReady && speaker != null) {
                position += 0.25;
                if (position >= duration) {
                    position = duration;
                    setPlaying(false);
                }
                speaker.onPosition(position);
                updateNotif();
            }
            refreshFloatTime();
            handler.postDelayed(this, 250);
        }
    };

    // ----- nut noi -----
    private WindowManager wm;
    private View floatRoot;
    private Button floatDot;
    private LinearLayout floatPanel;
    private TextView floatCue;
    private TextView floatTime;
    private Button floatPlayPause;
    private WindowManager.LayoutParams floatParams;
    private boolean floatShown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        loadPrefs();
        startFg();
        tts = new TextToSpeech(getApplicationContext(), this);
        handler.post(ticker);
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences("webtts", MODE_PRIVATE);
        offset = sp.getFloat("offset", 0f);
        rate = sp.getFloat("rate", 1.0f);
        ttsVol = sp.getInt("ttsVol", 100);
        splitAudio = sp.getBoolean("splitAudio", true);
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
                    handler.post(() -> {
                        if (index >= 0 && index < sharedSubs.size()) {
                            lastCueText = sharedSubs.get(index).text;
                            if (floatCue != null) floatCue.setText(lastCueText);
                        }
                    });
                }
                @Override public void onSpeakDone(int index) {}
                @Override public void onTtsError(int index) {}
            });
            speaker.setSubtitles(sharedSubs);
            speaker.setAudioOffset(offset);
            speaker.setSpeechRate(rate);
            speaker.setTtsVolume(ttsVol);
            applySplitAudio();
            recomputeDuration();
            updateNotif();
        }
    }

    /**
     * Tach/gop stream am thanh giong doc.
     * GHI CHU (gioi han Android): Google TTS mac dinh phat tren STREAM_MUSIC
     * chung voi Netflix -> phim cung to/nho theo. Dat PARAM_STREAM=ALARM thi
     * phim cung media khong anh huong sub; sub doi sang volume bao thuc.
     * Nhuoc: may de che do im lang / volume bao thuc nho thi sub nho theo.
     */
    private void applySplitAudio() {
        if (speaker == null) return;
        try {
            if (splitAudio) {
                speaker.setTtsStream(android.media.AudioManager.STREAM_ALARM);
            } else {
                speaker.setTtsStream(android.media.AudioManager.STREAM_MUSIC);
            }
        } catch (Exception ignored) {}
    }

    public static void setSplitAudio(boolean v) {
        if (running != null) {
            running.splitAudio = v;
            running.applySplitAudio();
        }
    }

    public static boolean getSplitAudio() {
        return running == null || running.splitAudio;
    }

    /** "mm:ss / mm:ss" de can dong bo voi Netflix. */
    public static String timeText() {
        if (running == null) return "00:00 / 00:00";
        return fmtTime(running.position) + " / " + fmtTime(running.duration);
    }

    private static String fmtTime(double sec) {
        if (sec < 0) sec = 0;
        int s = (int) sec;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void startFg() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Web TTS", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Intent tgI = new Intent(this, TtsPlaybackService.class);
        tgI.setAction(ACTION_TOGGLE);
        PendingIntent tgPi = PendingIntent.getService(this, 2, tgI, flags);

        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("Web TTS dang chay nen")
                .setContentText(notifText())
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", tgPi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private String notifText() {
        if (sharedCount > 0) {
            return (playing ? "Dang doc" : "Tam dung") + " "
                    + sharedCount + " cau sub";
        }
        return "Cho nap phu de .srt";
    }

    private void updateNotif() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Intent open = new Intent(this, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
            Notification n = new NotificationCompat.Builder(this, CH_ID)
                    .setContentTitle("Web TTS dang chay nen")
                    .setContentText(notifText())
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {}
    }

    // ----- nhan lenh -----
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_PLAY.equals(a)) setPlaying(true);
            else if (ACTION_PAUSE.equals(a)) setPlaying(false);
            else if (ACTION_TOGGLE.equals(a)) setPlaying(!playing);
            else if (ACTION_STOP_ALL.equals(a)) {
                setPlaying(false);
                hideFloat();
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_NUDGE.equals(a)) {
                double d = intent.getDoubleExtra(EXTRA_DELTA, 0);
                nudge(d);
            } else if (ACTION_SHOW_FLOAT.equals(a)) {
                showFloat();
            } else if (ACTION_HIDE_FLOAT.equals(a)) {
                hideFloat();
            }
        }
        startFg();
        return START_STICKY;
    }

    private void setPlaying(boolean p) {
        if (p && sharedSubs.isEmpty()) {
            playing = false;
            updateNotif();
            return;
        }
        playing = p;
        if (speaker != null) speaker.setPlaying(p);
        if (!p && tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
        }
        refreshFloatPlayBtn();
        updateNotif();
    }

    private void nudge(double delta) {
        position += delta;
        if (position < 0) position = 0;
        if (duration > 0 && position > duration) position = duration;
        if (speaker != null) speaker.onPosition(position);
    }

    private void applyShared() {
        if (speaker != null) {
            speaker.setSubtitlesIfNew(sharedSubs);
            speaker.setAudioOffset(offset);
            speaker.setSpeechRate(rate);
            speaker.setTtsVolume(ttsVol);
        }
        recomputeDuration();
        updateNotif();
    }

    private void recomputeDuration() {
        duration = 0;
        for (SubtitleCue c : sharedSubs) duration = Math.max(duration, c.end);
    }

    // ----- API static cho Activity / nut noi -----
    public static void cmd(Context ctx, String action) {
        Intent i = new Intent(ctx, TtsPlaybackService.class);
        i.setAction(action);
        startSvc(ctx, i);
    }

    public static void cmdNudge(Context ctx, double delta) {
        Intent i = new Intent(ctx, TtsPlaybackService.class);
        i.setAction(ACTION_NUDGE);
        i.putExtra(EXTRA_DELTA, delta);
        startSvc(ctx, i);
    }

    private static void startSvc(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }

    public static void setOffset(float v) {
        if (running != null) {
            running.offset = v;
            if (running.speaker != null) running.speaker.setAudioOffset(v);
        }
    }

    public static float getOffset() {
        return running != null ? running.offset : 0f;
    }

    public static void setRate(float v) {
        if (running != null) {
            running.rate = v;
            if (running.speaker != null) running.speaker.setSpeechRate(v);
        }
    }

    public static void setTtsVolume(int p) {
        if (running != null) {
            running.ttsVol = p;
            if (running.speaker != null) running.speaker.setTtsVolume(p);
        }
    }

    public static int getTtsVolume() {
        return running != null ? running.ttsVol : 100;
    }

    public static void setPosition(double pos) {
        if (running != null) {
            running.position = pos;
            if (running.speaker != null) running.speaker.onPosition(pos);
        }
    }

    public static double getPosition() {
        return running != null ? running.position : 0;
    }

    // ----- nut noi -----
    public static boolean canDrawOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    private void showFloat() {
        if (floatShown) return;
        if (!canDrawOverlay(this)) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            buildFloatViews();
            int type;
            if (Build.VERSION.SDK_INT >= 26) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_PHONE;
            }
            floatParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            floatParams.gravity = Gravity.TOP | Gravity.END;
            floatParams.x = 16;
            floatParams.y = 200;
            wm.addView(floatRoot, floatParams);
            floatShown = true;
        } catch (Exception ignored) {}
    }

    private void hideFloat() {
        if (!floatShown) return;
        try {
            if (wm != null && floatRoot != null) wm.removeView(floatRoot);
        } catch (Exception ignored) {}
        floatShown = false;
        floatRoot = null;
    }

    private void buildFloatViews() {
        Context ctx = this;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        floatDot = new Button(ctx);
        floatDot.setText("TTS");
        floatDot.setTextSize(13);
        floatDot.setAlpha(FLOAT_ALPHA);
        floatDot.setBackgroundColor(0xFF0066CC);
        floatDot.setTextColor(0xFFFFFFFF);
        floatDot.setPadding(24, 24, 24, 24);
        floatDot.setOnClickListener(v -> togglePanel());
        floatDot.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (floatParams == null || wm == null) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = floatParams.x - e.getRawX();
                        dy = floatParams.y - e.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        floatParams.x = (int) (e.getRawX() + dx);
                        floatParams.y = (int) (e.getRawY() + dy);
                        try {
                            wm.updateViewLayout(floatRoot, floatParams);
                        } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });

        floatPanel = new LinearLayout(ctx);
        floatPanel.setOrientation(LinearLayout.VERTICAL);
        floatPanel.setBackgroundColor(0xEE222222);
        floatPanel.setPadding(12, 12, 12, 12);
        floatPanel.setVisibility(View.GONE);

        floatCue = new TextView(ctx);
        floatCue.setText(lastCueText);
        floatCue.setTextColor(0xFFFFFFFF);
        floatCue.setTextSize(13);
        floatCue.setMaxLines(2);
        floatPanel.addView(floatCue);

        // Dong ho chay de can dong bo voi Netflix: "mm:ss / mm:ss".
        floatTime = new TextView(ctx);
        floatTime.setText(timeText());
        floatTime.setTextColor(0xFF90CAF9);
        floatTime.setTextSize(13);
        floatPanel.addView(floatTime);

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        floatPlayPause = new Button(ctx);
        floatPlayPause.setText(playing ? "Pause" : "Play");
        floatPlayPause.setTextSize(12);
        floatPlayPause.setOnClickListener(v -> setPlaying(!playing));
        Button bBack = new Button(ctx);
        bBack.setText("-1s");
        bBack.setTextSize(12);
        bBack.setOnClickListener(v -> nudge(-1.0));
        Button bFwd = new Button(ctx);
        bFwd.setText("+1s");
        bFwd.setTextSize(12);
        bFwd.setOnClickListener(v -> nudge(1.0));
        row1.addView(floatPlayPause);
        row1.addView(bBack);
        row1.addView(bFwd);
        floatPanel.addView(row1);

        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb = new TextView(ctx);
        lb.setText("Sub:");
        lb.setTextColor(0xFFCCCCCC);
        lb.setTextSize(12);
        SeekBar vol = new SeekBar(ctx);
        vol.setMax(100);
        vol.setProgress(ttsVol);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vol.setLayoutParams(lp);
        final TextView vv = new TextView(ctx);
        vv.setText(ttsVol + "%");
        vv.setTextColor(0xFFFFFFFF);
        vv.setTextSize(12);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                ttsVol = p;
                vv.setText(p + "%");
                getSharedPreferences("webtts", MODE_PRIVATE)
                        .edit().putInt("ttsVol", p).apply();
                if (speaker != null) speaker.setTtsVolume(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row2.addView(lb);
        row2.addView(vol);
        row2.addView(vv);
        floatPanel.addView(row2);

        LinearLayout row3 = new LinearLayout(ctx);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button bHide = new Button(ctx);
        bHide.setText("An nut");
        bHide.setTextSize(12);
        bHide.setOnClickListener(v -> hideFloat());
        Button bSplit = new Button(ctx);
        bSplit.setText(splitAudio ? "Gop loa" : "Tach loa");
        bSplit.setTextSize(12);
        bSplit.setOnClickListener(v -> {
            splitAudio = !splitAudio;
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putBoolean("splitAudio", splitAudio).apply();
            applySplitAudio();
            bSplit.setText(splitAudio ? "Gop loa" : "Tach loa");
            android.widget.Toast.makeText(ctx, splitAudio
                    ? "Sub ra loa bao thuc — phim cung media khong anh huong."
                    : "Sub chung loa media — phim cung to/nho theo.",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        Button bStop = new Button(ctx);
        bStop.setText("Dung doc");
        bStop.setTextSize(12);
        bStop.setOnClickListener(v -> {
            setPlaying(false);
            hideFloat();
        });
        Button bOpen = new Button(ctx);
        bOpen.setText("Mo app");
        bOpen.setTextSize(12);
        bOpen.setOnClickListener(v -> {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        row3.addView(bHide);
        row3.addView(bSplit);
        row3.addView(bStop);
        row3.addView(bOpen);
        floatPanel.addView(row3);

        root.addView(floatDot);
        root.addView(floatPanel);
        floatRoot = root;
    }

    private void togglePanel() {
        if (floatPanel == null) return;
        boolean show = floatPanel.getVisibility() != View.VISIBLE;
        floatPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (floatCue != null) floatCue.setText(lastCueText);
            refreshFloatTime();
        }
    }

    /** Cap nhat "mm:ss / mm:ss" tren nut noi de can voi Netflix. */
    private void refreshFloatTime() {
        if (!floatShown || floatTime == null) return;
        try {
            floatTime.setText(timeText());
        } catch (Exception ignored) {}
    }

    private void refreshFloatPlayBtn() {
        if (floatPlayPause != null) {
            floatPlayPause.setText(playing ? "Pause" : "Play");
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        hideFloat();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        running = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
