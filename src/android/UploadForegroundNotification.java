package com.spoon.backgroundfileupload;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import androidx.annotation.IntegerRes;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class UploadForegroundNotification {
    private static final Map<UUID, Float> collectiveProgress = Collections.synchronizedMap(new HashMap<>());
    private static final AtomicLong lastNotificationUpdateMs = new AtomicLong(0);
    private static ForegroundInfo cachedInfo;

    private static final int notificationId = new Random().nextInt();
    public static String notificationTitle = "Default title";

    @IntegerRes
    public static int notificationIconRes = 0;
    public static String notificationIntentActivity;

    public static void configure(final String title, @IntegerRes final int icon, final String intentActivity) {
        notificationTitle = title;
        notificationIconRes = icon;
        notificationIntentActivity = intentActivity;
    }

    public static void progress(final UUID uuid, final float progress) {
        collectiveProgress.put(uuid, progress);
    }

    public static void done(final UUID uuid) {
        collectiveProgress.remove(uuid);
    }

    static ForegroundInfo getForegroundInfo(final Context context) {
        final long now = System.currentTimeMillis();
        // Set to now to ensure other worker will be throttled
        final long lastUpdate = lastNotificationUpdateMs.getAndSet(now);

        // Throttle, 200ms delay
        if (cachedInfo != null && now - lastUpdate <= UploadTask.DELAY_BETWEEN_NOTIFICATION_UPDATE_MS) {
            // Revert value
            lastNotificationUpdateMs.set(lastUpdate);
            return cachedInfo;
        }

        List<WorkInfo> workInfo;
        try {
            workInfo = WorkManager.getInstance(context)
                    .getWorkInfosByTag(FileTransferBackground.getCurrentTag(context))
                    .get();
        } catch (ExecutionException | InterruptedException e) {
            Log.w(UploadTask.TAG, "getForegroundInfo: Problem while retrieving task list:", e);
            workInfo = Collections.emptyList();
        }

        float uploadingProgress = 0f;
        int uploadDone = 0;
        int uploadCount = 0;
        for (WorkInfo info : workInfo) {
            if (!info.getState().isFinished()) {
                final Float progress = collectiveProgress.get(info.getId());
                if (progress != null) {
                    uploadingProgress += progress;
                }
            } else {
                uploadDone++;
            }
            uploadCount++;
        }

        float totalProgressStore = ((float) uploadDone) / uploadCount;

        Log.d(UploadTask.TAG, "eventLabel='getForegroundInfo: general (" + uploadingProgress + ") all (" + collectiveProgress + ")'");

        Class<?> mainActivityClass = null;
        try {
            mainActivityClass = Class.forName(notificationIntentActivity);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        Intent notificationIntent = new Intent(context, mainActivityClass);
        int pendingIntentFlag;
        if (Build.VERSION.SDK_INT >= 23) {
            pendingIntentFlag = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingIntentFlag = 0;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, pendingIntentFlag);

        // TODO: click intent open app
        Notification notification = new NotificationCompat.Builder(context, UploadTask.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setTicker(notificationTitle)
                .setSmallIcon(notificationIconRes)
                .setColor(Color.rgb(57, 100, 150))
                .setOngoing(true)
                .setProgress(100, (int) (totalProgressStore * 100f), false)
                .setContentIntent(pendingIntent)
                .addAction(notificationIconRes, "Open", pendingIntent)
                .build();

        notification.flags |= Notification.FLAG_NO_CLEAR;
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;


        cachedInfo = new ForegroundInfo(notificationId, notification);
        return cachedInfo;
    }
}
