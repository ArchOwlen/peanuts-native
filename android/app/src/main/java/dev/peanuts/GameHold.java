package dev.peanuts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the game's process at its normal priority while the Thor's lid is closed. With the lid closed the AYN Thor puts Android to sleep (the lid is its sleep button)
 * while its own shell keeps both panels running, so the player plays on, but Android sees the top activity as "top-sleeping" and moves its process to the background
 * cpuset: the three 2.0 GHz cores, none of the 2.8 and 3.2 GHz ones. Fire Emblem: Three Houses' battle map fell from 60 to 50 fps that way, with the host's replay and
 * encoder threads waiting for a core. A process with a foreground service stays in the foreground group whether Android is awake or not, so this service runs in the
 * game's process (":game") from the game's start to its end: a media-playback service, which is what a game with sound is, with a quiet notification.
 */
public final class GameHold extends Service {
    private static final String TAG = Companion.TAG, CHANNEL = "peanuts_game";
    private static final int NOTE = 7101;

    public static void start(Context c, String gameName) {
        try {
            c.startForegroundService(new Intent(c, GameHold.class).putExtra("name", gameName));
        } catch (RuntimeException e) {   // not allowed from the background, or the manifest lacks the service
            Log.w(TAG, "game hold not started: " + e);
        }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, GameHold.class)); } catch (RuntimeException e) { Log.w(TAG, "game hold stop: " + e); }
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String name = i == null ? null : i.getStringExtra("name");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Game running", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(name == null || name.isEmpty() ? "Game running" : name)
                .setContentText("Playing").setOngoing(true).build();   // (the channel's importance is MIN: no sound, no peek)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTE, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "game hold: " + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (RuntimeException ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
