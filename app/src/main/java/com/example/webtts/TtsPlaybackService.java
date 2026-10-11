package com.example.webtts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service GIU PROCESS SONG khi tat man hinh.
 *
 * V1: TTS nam DUY NHAT o MainActivity (SubtitleSpeaker).
 * Service khong tao TextToSpeech rieng -> tranh doc trung giong
 * (loi gap khi ca Activity va Service cung speak).
 * Service chi hien notification ongoing de Android khong kill app.
 */
public class TtsPlaybackService extends Service {

    private static final String CH_ID = "webtts_playback";
    private static final int NOTIF_ID = 1001;

    /** Sub dung chung (tham khao, khong speak o day). */
    private static int sharedCount = 0;

    public static void setSharedSubtitles(java.util.List<SubtitleCue> subs) {
        sharedCount = subs == null ? 0 : subs.size();
    }

    public static void setSharedOffset(float v) {}
    public static void setSharedRate(float v) {}

    @Override
    public void onCreate() {
        super.onCreate();
        startFg();
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
        String text = sharedCount > 0
                ? "Dang doc " + sharedCount + " cau phu de"
                : "Doc phu de bang giong he thong";
        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("Web TTS dang chay")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Refresh notification text khi co sub moi.
        startFg();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
