# Web TTS Android — port tu web_tts_v2_9_manual_yt_translate.py

App doc phu de tieng Viet bang **giong he thong Google TTS**,
target **Android 10+** (minSdk 29). Khong can Kiwi, khong can Edge TTS / ffmpeg.

## Ket luan nhanh

- **Build APK tu logic Python: DUOC**, nhung phai port sang native Java
  (khong mang nguyen Python + Edge + ffmpeg sang APK).
- **Khong dung Kiwi**: app doc lap, mo video trong WebView cua app
  hoac chay thu cong voi file .srt.
- **Khong ket noi thang vao app Netflix**: Android sandbox + DRM
  khong cho app thu 3 doc position/sub tu app Netflix.
  Giai phap: mo Netflix tren trinh duyet trong WebView + nap sub .srt.

## Che do chay (V1)

1. **Thu cong (khuyen dung truoc):**
   mo app → `Chon .srt` → bam `Play` → keo SeekBar nhu timeline video.
   App tim cue hien tai (nhi phan) + doc 1 lan / cau.
2. **WebView (thu nghiem):**
   bam `Mo WebView xem video` → mo YouTube trong app →
   JS hook `video.currentTime` moi 250ms → doc theo.
   Phu de van nap thu cong (.srt) o giai doan 1.

## Port tu Python

| Python | Android (V1) |
|---|---|
| `clean_subtitle_text / parse_srt` | `SubtitleParser.java` (giua nguyen regex) |
| `find_subtitle_index` nhi phan | `SubtitleParser.findIndex()` |
| `playback_loop` + offset + seek 1.2s | `SubtitleSpeaker.onPosition()` |
| `audio_offset` 7 nut (+2.0…-1.0) | grid offset + SharedPreferences |
| `ffmpeg atempo MAX 2.5x` | `TextToSpeech.setSpeechRate(0.5-2.5)` |
| `pygame` phat audio | `TextToSpeech.speak(QUEUE_FLUSH)` — DUY NHAT o Activity |
| `google_translate_batch` | chua port — V1 chi doc sub tieng Viet co san |
| `Tkinter` Dieu khien | `MainActivity` + SeekBar toc do |
| chay nen tat man | `TtsPlaybackService` foreground (khong speak, chi giu process) |

V1 co tinh sua loi **doc trung giong**: truoc day ca Activity va Service
cung tao TextToSpeech + cung speak → 2 giong de len nhau.
Nay TTS chi nam o MainActivity.

## Yeu cau tren dien thoai

- Cai **Speech Services by Google** + giong **tieng Viet**
  (Cai dat → He thong → Ngon ngu & nhap lieu → Chuyen van ban thanh loi noi).
- Android 13+: cap quyen Thong bao de service nen chay.

## Build APK (giong youtube-lite, da co workflow san)

Khong can Android Studio tren LUNA36 — build tren GitHub Actions.
LUNA36 chua co Java/Gradle nen khong build local duoc.

1. Tao repo moi tren github.com (dung tick Add README).
2. Tren LUNA36 (mo PowerShell tai thu muc `web-tts-android`):
   ```
   git init
   git add .
   git -c user.name="tuyen36" -c user.email="tuyen36ptit13@gmail.com" commit -m "Web TTS Android v1.0"
   git branch -M main
   git remote add origin <url-repo>
   git -c http.sslBackend=openssl push -u origin main
   ```
   (LUNA36 loi schannel → them `-c http.sslBackend=openssl`.)
3. Vao repo → tab **Actions** → cho tick xanh (~5-10 phut).
4. Keo xuong **Artifacts → app-debug** → giai nen lay `.apk` → cai.
5. Do → bam job `build`, tim `error:` trong log.

## Cau truc

```
app/src/main/java/com/example/webtts/
  MainActivity.java        UI + che do thu cong + WebView hook
  SubtitleCue.java         1 cue {start, end, text}
  SubtitleParser.java      clean text + parse SRT + tim nhi phan
  SubtitleSpeaker.java     dieu khien Google TTS theo position (API 29+)
  TtsPlaybackService.java  foreground service giu app song (khong speak)
app/src/main/res/layout/activity_main.xml
.github/workflows/build-apk.yml
```

## Huong phat trien (V2, neu can)

- Port `google_translate_batch` → dich sub ngoai ngu sang Viet trong app.
- Hook sau hon trong WebView (bat su kien doi video, boc cues tu DOM).
- Chon giong TTS (nam/nu, toc do mac dinh theo video).
