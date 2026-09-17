package com.mixcasete.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.KeyEvent;

/**
 * Servicio de reproducción en primer plano.
 *
 * CAMBIO CLAVE: el WebView de video ya NO reproduce audio (siempre va muteado).
 * Por eso este servicio mantiene SIEMPRE el foco de audio y no reacciona a
 * pérdidas transitorias — no debería haber ninguna, porque no compite con nadie.
 */
public class PlaybackService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    public static final String CHANNEL = "mixcasete_play";
    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_SEEK = "seek";

    private PowerManager.WakeLock wl;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private MediaSession mediaSession;
    private String currentTitle = "Mix.Casete";
    private String currentArtist = "";
    private boolean prepared = false;

    public static void start(android.content.Context c, Intent
