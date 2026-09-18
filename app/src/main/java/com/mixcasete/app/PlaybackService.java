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

    public static final String CHANNEL = "mixcasete_play";
    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_SEEK = "seek";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private PowerManager.WakeLock wl;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private String currentTitle = "Mix.Casete";
    private String currentArtist = "";
    private boolean prepared = false;

    public static void start(android.content.Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(android.content.Context c) {
        c.stopService(new Intent(c, PlaybackService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
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
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                        true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    prepared = true;
                    if (player.isPlaying()) {
                        updatePlaybackState(true);
                        updateNotif(true);
                        notifyJs("playing");
                    }
                } else if (state == Player.STATE_ENDED) {
                    prepared = false;
                    updatePlaybackState(false);
                    updateNotif(false);
                    notifyJs("ended");
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                updatePlaybackState(playing);
                updateNotif(playing);
                notifyJs(playing ? "playing" : "paused");
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                prepared = false;
                notifyJs("error");
                updateNotif(false);
            }
        });
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "MixCaseteSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { doCmd("play"); }
            @Override public void onPause() { doCmd("pause"); }
            @Override public void onStop() { doCmd("stop"); }
            @Override public void onSeekTo(long pos) {
                if (player != null) player.seekTo(pos);
            }
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                Object evObj = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (evObj instanceof KeyEvent) {
                    KeyEvent ev = (KeyEvent) evObj;
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                        int code = ev.getKeyCode();
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            if (player != null && player.isPlaying()) doCmd("pause"); else doCmd("play");
                            return true;
                        } else if (code == KeyEvent.KEYCODE_MEDIA_PLAY) { doCmd("play"); return true; }
                        else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) { doCmd("pause"); return true; }
                        else if (code == KeyEvent.KEYCODE_MEDIA_STOP) { doCmd("stop"); return true; }
                        else if (code == KeyEvent.KEYCODE_MEDIA_NEXT
                                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                            notifyJs(code == KeyEvent.KEYCODE_MEDIA_NEXT ? "btn_next" : "btn_prev");
                            return true;
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        mediaSession.setActive(true);
    }

    private void doCmd(String cmd) {
        Intent i = new Intent(this, PlaybackService.class);
        i.putExtra(EXTRA_CMD, cmd);
        onStartCommand(i, 0, 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String cmd = intent.getStringExtra(EXTRA_CMD);
        if (cmd == null) return START_STICKY;

        switch (cmd) {
            case "play_url":
                String url = intent.getStringExtra(EXTRA_URL);
                String title = intent.getStringExtra(EXTRA_TITLE);
                String artist = intent.getStringExtra(EXTRA_ARTIST);
                if (title != null) currentTitle = title;
                currentArtist = artist != null ? artist : "";
                startPlayback(url);
                break;
            case "play":
                if (player != null) { player.play(); acquireWakeLock(); }
                break;
            case "pause":
                if (player != null) player.pause();
                break;
            case "stop":
                stopPlayback();
                stopSelf();
                break;
            case "seek":
                int sec = intent.getIntExtra(EXTRA_SEEK, 0);
                if (player != null) player.seekTo(sec * 1000L);
                break;
        }
        return START_STICKY;
    }

    private void startPlayback(String url) {
        if (player == null) return;
        acquireWakeLock();
        updateMetadata();
        try {
            MediaItem item = MediaItem.fromUri(url);
            player.setMediaItem(item);
            player.prepare();
            player.play();
        } catch (Exception e) {
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
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mixcasete:play");
        }
        if (!wl.isHeld()) wl.acquire(4 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wl != null && wl.isHeld()) wl.release();
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        MediaMetadata md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        currentArtist == null || currentArtist.isEmpty() ? "Mix.Casete" : currentArtist)
                .build();
        mediaSession.setMetadata(md);
    }

    private void updatePlaybackState(boolean playing) {
        if (mediaSession == null) return;
        long pos = 0;
        try { if (player != null) pos = player.getCurrentPosition(); } catch (Exception e) {}
        PlaybackState st = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        pos, playing ? 1f : 0f)
                .build();
        mediaSession.setPlaybackState(st);
        mediaSession.setActive(true);
    }

    private Notification buildNotif(String title, boolean playing) {
        Intent pause = new Intent(this, PlaybackService.class).putExtra(EXTRA_CMD, playing ? "pause" : "play");
        Intent stop  = new Intent(this, PlaybackService.class).putExtra(EXTRA_CMD, "stop");
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pP = PendingIntent.getService(this, 1, pause, fl);
        PendingIntent pS = PendingIntent.getService(this, 3, stop, fl);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, fl);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(playing ? "▶ Reproduciendo" : "❚❚ En pausa")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pOpen)
                .setOngoing(playing)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausa" : "Seguir", pP)
                .addAction(android.R.drawable.ic_delete, "Parar", pS);

        if (mediaSession != null) {
            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mediaSession.getSessionToken());
            style.setShowActionsInCompactView(0, 1);
            b.setStyle(style);
        }
        return b.build();
    }

    private void updateNotif(boolean playing) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try { nm.notify(1, buildNotif(currentTitle, playing)); } catch (Exception e) {}
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Audio de Mix.Casete");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    private void notifyJs(String event) {
        if (MainActivity.self != null && MainActivity.self.get() != null) {
            MainActivity.self.get().onPlayerEvent(event);
        }
    }

    @Override
    public void onDestroy() {
        stopPlayback();
        if (player != null) { player.release(); player = null; }
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }
}
