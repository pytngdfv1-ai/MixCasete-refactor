package com.mixcasete.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashMap;
import java.util.Map;

public class PlaybackService extends Service {
    private static final String TAG = "MixCaseteAudio";
    public static final String CHANNEL = "mixcasete_play";
    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_SEEK = "seek";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private PowerManager.WakeLock wl;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private String currentTitle = "Mix.Casete";
    private String currentArtist = "";
    private String currentUrl = "";

    public static void start(android.content.Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        crearCanal();
        setupMediaSession();
        setupPlayer();
        startForeground(1, buildNotif("Mix.Casete", false));
    }

    private void setupPlayer() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", "https://www.youtube.com/");
        headers.put("Origin", "https://www.youtube.com");

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setUserAgent(UA)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                updatePlaybackState(playing);
                updateNotif(playing);
                notifyJs(playing ? "playing" : "paused");
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    releaseWakeLock();
                    updatePlaybackState(false);
                    updateNotif(false);
                    notifyJs("ended");
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                String code = error == null ? "unknown" : error.getErrorCodeName();
                String message = error == null ? "unknown" : String.valueOf(error.getMessage());
                String cause = error == null || error.getCause() == null
                        ? "none" : error.getCause().getClass().getSimpleName() + ": " + error.getCause().getMessage();
                Log.e(TAG, "Audio error track='" + currentTitle + "' code=" + code
                        + " message=" + message + " cause=" + cause
                        + " url=" + redactUrl(currentUrl), error);
                releaseWakeLock();
                updateNotif(false);
                // Keep the public event stable for the current JavaScript UI.
                notifyJs("error");
            }
        });
    }

    private String redactUrl(String url) {
        if (url == null || url.isEmpty()) return "empty";
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPath() == null ? "" : u.getPath());
        } catch (Exception e) { return "invalid"; }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "MixCaseteSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { doCmd("play"); }
            @Override public void onPause() { doCmd("pause"); }
            @Override public void onStop() { doCmd("stop"); }
            @Override public void onSeekTo(long pos) { if (player != null) player.seekTo(pos); }
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (ev.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            doCmd(player != null && player.isPlaying() ? "pause" : "play"); return true;
                        case KeyEvent.KEYCODE_MEDIA_PLAY: doCmd("play"); return true;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE: doCmd("pause"); return true;
                        case KeyEvent.KEYCODE_MEDIA_STOP: doCmd("stop"); return true;
                        case KeyEvent.KEYCODE_MEDIA_NEXT: notifyJs("btn_next"); return true;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS: notifyJs("btn_prev"); return true;
                    }
                }
                return super.onMediaButtonEvent(intent);
            }
        });
        mediaSession.setActive(true);
    }

    private void doCmd(String cmd) {
        Intent i = new Intent(this, PlaybackService.class);
        i.putExtra(EXTRA_CMD, cmd);
        onStartCommand(i, 0, 0);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String cmd = intent.getStringExtra(EXTRA_CMD);
        if (cmd == null) return START_STICKY;
        switch (cmd) {
            case "play_url":
                String url = intent.getStringExtra(EXTRA_URL);
                if (intent.hasExtra(EXTRA_TITLE)) currentTitle = intent.getStringExtra(EXTRA_TITLE);
                currentArtist = intent.getStringExtra(EXTRA_ARTIST);
                startPlayback(url);
                break;
            case "play": if (player != null) { player.play(); acquireWakeLock(); } break;
            case "pause": if (player != null) player.pause(); releaseWakeLock(); break;
            case "stop": stopPlayback(); stopSelf(); break;
            case "seek": if (player != null) player.seekTo(Math.max(0, intent.getIntExtra(EXTRA_SEEK, 0) * 1000L)); break;
        }
        return START_STICKY;
    }

    private void startPlayback(String url) {
        if (player == null || url == null || url.trim().isEmpty()) {
            Log.e(TAG, "Audio URL empty for track='" + currentTitle + "'");
            notifyJs("error");
            return;
        }
        try {
            currentUrl = url;
            acquireWakeLock();
            updateMetadata();
            player.stop();
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.play();
            Log.d(TAG, "Playback requested track='" + currentTitle + "' url=" + redactUrl(url));
        } catch (Exception e) {
            Log.e(TAG, "Playback start failed track='" + currentTitle + "'", e);
            releaseWakeLock();
            notifyJs("error");
        }
    }

    private void stopPlayback() {
        if (player != null) player.stop();
        releaseWakeLock();
        if (mediaSession != null) mediaSession.setActive(false);
    }

    private void acquireWakeLock() {
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mixcasete:playback");
            wl.setReferenceCounted(false);
        }
        if (!wl.isHeld()) wl.acquire(4 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() { if (wl != null && wl.isHeld()) wl.release(); }

    private void updateMetadata() {
        if (mediaSession == null) return;
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        currentArtist == null || currentArtist.isEmpty() ? "Mix.Casete" : currentArtist)
                .build());
        mediaSession.setActive(true);
    }

    private void updatePlaybackState(boolean playing) {
        if (mediaSession == null) return;
        long pos = player == null ? 0 : player.getCurrentPosition();
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        pos, playing ? 1f : 0f).build());
        mediaSession.setActive(true);
    }

    private Notification buildNotif(String title, boolean playing) {
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pp = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).putExtra(EXTRA_CMD, playing ? "pause" : "play"), fl);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, PlaybackService.class).putExtra(EXTRA_CMD, "stop"), fl);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(playing ? "▶ Reproduciendo" : "❚❚ En pausa")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(playing)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausa" : "Reproducir", pp)
                .addAction(android.R.drawable.ic_delete, "Parar", stop);
        if (mediaSession != null) {
            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken());
            style.setShowActionsInCompactView(0, 1);
            b.setStyle(style);
        }
        return b.build();
    }

    private void updateNotif(boolean playing) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, buildNotif(currentTitle, playing)); }
        catch (Exception e) { Log.e(TAG, "Notification update failed", e); }
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Audio de Mix.Casete");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private void notifyJs(String event) {
        if (MainActivity.self != null && MainActivity.self.get() != null)
            MainActivity.self.get().onPlayerEvent(event);
    }

    @Override public void onTaskRemoved(Intent rootIntent) { super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        stopPlayback();
        if (player != null) { player.release(); player = null; }
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }
}
