/*
 * <!--
 *    Copyright (C) 2014 The NamelessRom Project
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * -->
 */

package org.namelessrom.screencast.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.Toast;

import org.namelessrom.screencast.Logger;
import org.namelessrom.screencast.R;
import org.namelessrom.screencast.Utils;
import org.namelessrom.screencast.receivers.ControlReceiver;
import org.namelessrom.screencast.recording.RecordingDevice;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

public class ScreencastService extends Service {
    public static final String ACTION_START_SCREENCAST = "org.namelessrom.ACTION_START_SCREENCAST";
    public static final String ACTION_STOP_SCREENCAST = "org.namelessrom.ACTION_STOP_SCREENCAST";
    public static final String ACTION_SHOW_TOUCHES = "org.namelessrom.SHOW_TOUCHES";
    public static final String ACTION_DELETE_SCREENCAST =
            "org.namelessrom.ACTION_DELETE_SCREENCAST";

    private static final int COLOR_NOTIFICATION = Color.argb(255, 75, 172, 79);

    private Notification.Builder mBuilder;
    private RecordingDevice mRecorder;

    private long mStartTime;
    private Timer mTimer;

    private SimpleDateFormat mSimpleDateFormat;

    @Override public IBinder onBind(final Intent intent) { return null; }

    @Override public void onDestroy() {
        cleanup();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
        super.onDestroy();
    }

    @Override public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null) {
            stopSelf();
            return START_STICKY;
        }

        Logger.setEnabled(SystemProperties.getBoolean("persist.nameless.debug",
                SystemProperties.getBoolean("ro.nameless.debug", Logger.getEnabled())));

        final String action = intent.getAction();

        if (TextUtils.equals(action, ACTION_DELETE_SCREENCAST)) {
            final File recording = new File(intent.getData().getPath());
            Logger.i(this, "Deleting -> %s -> %s", recording.getAbsolutePath(), recording.delete());
            cancelShareNotification();
            return START_STICKY;
        }

        if (TextUtils.equals(action, ACTION_START_SCREENCAST)) {
            Utils.setRecording(this, true);
            if (!hasAvailableSpace()) {
                Toast.makeText(this, R.string.insufficient_storage, Toast.LENGTH_LONG).show();
                return START_STICKY;
            }

            mStartTime = System.currentTimeMillis();
            mSimpleDateFormat = new SimpleDateFormat("mm:ss", Locale.getDefault());
            mSimpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            try {
                registerScreencaster();
            } catch (Exception e) {
                Logger.e(this, "Failed to register screen caster", e);
            }

            mBuilder = createNotificationBuilder();

            final boolean showTouches = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(Settings.System.SHOW_TOUCHES, true);
            Settings.System.putInt(getContentResolver(), Settings.System.SHOW_TOUCHES,
                    showTouches ? 1 : 0);
            addNotificationTouchButton(showTouches);

            mTimer = new Timer();
            mTimer.scheduleAtFixedRate(new TimerTask() {
                public void run() {
                    updateNotification();
                }
            }, 100L, 1000L);

            return START_STICKY;
        }

        if (TextUtils.equals(action, ACTION_STOP_SCREENCAST)) {
            Utils.setRecording(this, false);
            Settings.System.putInt(getContentResolver(), Settings.System.SHOW_TOUCHES, 0);
            if (!hasAvailableSpace()) {
                Toast.makeText(this, R.string.insufficient_storage, Toast.LENGTH_LONG).show();
                return START_STICKY;
            }

            final String filePath = mRecorder != null ? mRecorder.getRecordingFilePath() : null;
            cleanup();
            sendShareNotification(filePath);
        }

        if (TextUtils.equals(action, ACTION_SHOW_TOUCHES)) {
            final String show = intent.getStringExtra(Settings.System.SHOW_TOUCHES);

            mBuilder = createNotificationBuilder();
            addNotificationTouchButton(TextUtils.equals("on", show));
        }

        return START_STICKY;
    }

    private Notification.Builder createNotificationBuilder() {
        final Intent intent = new Intent(ACTION_STOP_SCREENCAST);
        final Notification.Builder builder = new Notification.Builder(this);
        builder.setOngoing(true)
                .setSmallIcon(R.drawable.ic_stat_videocam)
                .setContentTitle(getString(R.string.recording))
                .setColor(COLOR_NOTIFICATION)
                .addAction(R.drawable.ic_stop, getString(R.string.stop),
                        PendingIntent.getBroadcast(this, 0, intent, 0));
        return builder;
    }

    private void updateNotification() {
        final long delta = System.currentTimeMillis() - mStartTime;
        final String deltaString = mSimpleDateFormat.format(new Date(delta));
        mBuilder.setContentText(getString(R.string.video_length, deltaString));

        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(0, mBuilder.build());
    }

    private void addNotificationTouchButton(final boolean showTouches) {
        final Intent intent = new Intent(ACTION_SHOW_TOUCHES);
        if (showTouches) {
            Settings.System.putInt(getContentResolver(), Settings.System.SHOW_TOUCHES, 1);
            intent.putExtra(Settings.System.SHOW_TOUCHES, "off");
            mBuilder.addAction(R.drawable.ic_touch_on,
                    getString(R.string.show_touches),
                    PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            Settings.System.putInt(getContentResolver(), Settings.System.SHOW_TOUCHES, 0);
            intent.putExtra(Settings.System.SHOW_TOUCHES, "on");
            mBuilder.addAction(R.drawable.ic_touch_off,
                    getString(R.string.show_touches),
                    PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
        }

        updateNotification();
    }


    private Notification.Builder createShareNotificationBuilder(final String filePath) {
        final String mime = "video/mp4";
        // parse the Uri
        final Uri localUri = Uri.parse(String.format("file://%s", filePath));
        Logger.i(this, "Video complete: %s", localUri);

        // create an temporary intent, which will be used to create the chooser
        final Intent tmpIntent = new Intent(Intent.ACTION_SEND);
        tmpIntent.setType(mime);
        tmpIntent.putExtra(Intent.EXTRA_STREAM, localUri);
        tmpIntent.putExtra(Intent.EXTRA_SUBJECT, filePath);

        // create the intent, which lets us choose how we want to share the screencast
        final Intent shareIntent = Intent.createChooser(tmpIntent, null);
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        // create an intent, which opens the screencast
        final Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(localUri, mime);
        viewIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // create an intent, which deletes the record
        final Intent delIntent = new Intent(this, ControlReceiver.class);
        delIntent.setAction(ACTION_DELETE_SCREENCAST);
        delIntent.setData(localUri);

        // get the duration of the screencast in minute:seconds format
        final String duration = mSimpleDateFormat
                .format(new Date(System.currentTimeMillis() - mStartTime));

        // create our pending intents
        final PendingIntent pendingViewIntent =
                PendingIntent.getActivity(this, 0, viewIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        final PendingIntent pendingShareIntent =
                PendingIntent.getActivity(this, 0, shareIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        final PendingIntent pendingDeleteIntent =
                PendingIntent.getBroadcast(this, 0, delIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        // build the notification
        return new Notification.Builder(this)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.ic_stat_videocam)
                .setColor(COLOR_NOTIFICATION)
                .setContentTitle(getString(R.string.recording_ready_to_share))
                .setContentText(getString(R.string.video_length, duration))
                .setContentIntent(pendingViewIntent)
                .addAction(android.R.drawable.ic_menu_share, getString(R.string.share),
                        pendingShareIntent)
                .addAction(android.R.drawable.ic_menu_delete, getString(R.string.delete),
                        pendingDeleteIntent);
    }

    private boolean hasAvailableSpace() {
        final StatFs localStatFs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        return localStatFs.getBlockSizeLong() * localStatFs.getBlockCountLong() / 1048576L >= 100L;
    }

    private void sendShareNotification(final String filePath) {
        final NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mBuilder = createShareNotificationBuilder(filePath);
        notificationManager.notify(0, mBuilder.build());
    }

    private void cancelShareNotification() {
        final NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(0);
    }

    private void cleanup() {
        if (mRecorder != null) {
            try {
                mRecorder.stop();
            } catch (IllegalStateException ise) {
                Logger.e(this, "Failed to stop recorder", ise);
            }
            mRecorder = null;
        }
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
    }

    private Point getNativeResolution(Display display, Point point) {
        try {
            // try to get the real size and return it
            display.getRealSize(point);
            return point;
        } catch (Exception exception) {
            Logger.e(this, "Failed getting real size", exception);
            // fall back to reflection
            try {
                Logger.e(this, "Failed getting real size again", exception);
                // get the raw width
                final Method getRawWidth = Display.class.getMethod("getRawWidth");
                point.x = ((Integer) getRawWidth.invoke(display, (Object[]) null));

                // get the raw height
                final Method getRawHeight = Display.class.getMethod("getRawHeight");
                point.y = ((Integer) getRawHeight.invoke(display, (Object[]) null));

                return point;
            } catch (Exception notAnotherException) {
                Logger.e(this, "Failed getting real size again again!", exception);
                // our last resort...
                display.getSize(point);
            }
        }
        return point;
    }

    private void registerScreencaster() {
        final DisplayManager dm = ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE));
        final Display display = dm.getDisplay(0);

        final DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        final Point point = new Point();
        getNativeResolution(display, point);
        mRecorder = new RecordingDevice(this, point.x, point.y);

        final VirtualDisplay virtualDisplay = mRecorder.registerVirtualDisplay(this, metrics);
        if (virtualDisplay == null) {
            cleanup();
        }
    }

}
