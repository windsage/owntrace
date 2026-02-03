/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.fanstrace;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.SystemProperties;
import android.text.format.DateUtils;

import androidx.preference.PreferenceManager;

public class TraceService extends IntentService {
    protected static String INTENT_ACTION_FANS_STOP_TRACING =
            "com.android.fanstrace_FANS_STOP_TRACING";
    protected static String INTENT_ACTION_FANS_START_TRACING =
            "com.android.fanstrace_FANS_START_TRACING";
    protected static String INTENT_ACTION_DFX_STOP_TRACING =
            "com.android.fanstrace_DFX_STOP_TRACING";
    protected static String INTENT_ACTION_DFX_START_TRACING =
            "com.android.fanstrace_DFX_START_TRACING";

    protected static String INTENT_EXTRA_TAGS = "tags";
    protected static String INTENT_EXTRA_BUFFER = "buffer";
    protected static String INTENT_EXTRA_APPS = "apps";

    protected static final boolean BOOT_START =
            SystemProperties.getBoolean("persist.tr_trace.boot_start", true);

    // Notification IDs
    protected static int TRACE_NOTIFICATION = 1;
    protected static int SAVING_TRACE_NOTIFICATION = 2;

    // Default dfx trace buffer size
    static final int DEFAULT_DFX_BUFFER_SIZE = 8192;
    private static final int MIN_KEEP_COUNT = 1;
    private static final long MIN_KEEP_AGE = 2 * DateUtils.DAY_IN_MILLIS;
    // Threshold: 7.0GB in bytes (7.0 * 1024 * 1024 * 1024)
    // This allows 8GB devices (which report ~7.2-7.5GB) to enable FANS trace
    private static final long RAM_THRESHOLD_BYTES = 7516192768L;

    // Use lazy initialization for FANS_AUTO_START since we need Context
    private static Boolean sFansAutoStart = null;

    /**
     * Notification type for different trace states
     */
    protected enum NotificationType {
        TRACE_RECORDING,  // For Start services - use TRACE_NOTIFICATION (ID=1)
        TRACE_SAVING      // For Stop services - use SAVING_TRACE_NOTIFICATION (ID=2)
    }

    /**
     * CRITICAL: Start foreground service immediately in onCreate() to satisfy Android requirement.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannelsCreated();
        startForegroundWithGenericNotification();
    }
    /**
     * Check if FANS trace should auto-start based on device RAM
     * @param context Application context
     * @return true if device RAM >= 7.0GB (to catch 8GB+ devices), false otherwise
     */
    public static boolean isFansAutoStart(Context context) {
        if (sFansAutoStart == null) {
            sFansAutoStart = checkFansAutoStart(context);
        }
        return sFansAutoStart;
    }
    /**
     * Check if FANS trace should auto-start based on device configuration and RAM
     * @param context Application context
     * @return true if device should enable FANS auto-start, false otherwise
     */
    private static boolean checkFansAutoStart(Context context) {
        // First check low_ram device flag
        if (SystemProperties.getBoolean("ro.config.low_ram", false)) {
            LogUtils.i(TraceUtils.TAG, "FANS_AUTO_START disabled: low_ram device");
            return false;
        }

        // Check device type
        String deviceType = SystemProperties.get("ro.tr_build.device.type", "");
        if ("go".equals(deviceType) || "slim".equals(deviceType)) {
            LogUtils.i(TraceUtils.TAG, "FANS_AUTO_START disabled: device type is " + deviceType);
            return false;
        }

        // Check total RAM
        try {
            android.app.ActivityManager activityManager =
                    (android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);

            if (activityManager != null) {
                android.app.ActivityManager.MemoryInfo memInfo =
                        new android.app.ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memInfo);
                long totalRam = memInfo.totalMem;  // in bytes

                // Convert to GB for logging (using 1024-based calculation)
                double totalRamGB = totalRam / (1024.0 * 1024.0 * 1024.0);

                boolean shouldStart = totalRam >= RAM_THRESHOLD_BYTES;
                LogUtils.i(
                        TraceUtils.TAG,
                        String.format(java.util.Locale.US,
                                      "Device RAM: %.2f GB, Threshold: 7.0 GB, FANS_AUTO_START: %b",
                                      totalRamGB,
                                      shouldStart));

                return shouldStart;
            }
        } catch (Exception e) {
            LogUtils.e(TraceUtils.TAG, "Failed to get memory info: " + e.getMessage());
        }

        LogUtils.i(TraceUtils.TAG, "Cannot determine RAM size, defaulting FANS_AUTO_START to true");
        return true;
    }

    public static void startTracing(final Context context,
                                    Collection<String> tags,
                                    int bufferSizeKb,
                                    boolean apps) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_FANS_START_TRACING);
        intent.putExtra(INTENT_EXTRA_TAGS, new ArrayList(tags));
        intent.putExtra(INTENT_EXTRA_BUFFER, bufferSizeKb);
        intent.putExtra(INTENT_EXTRA_APPS, apps);
        context.startForegroundService(intent);
    }

    public static void startDfxTracing(final Context context,
                                       Collection<String> tags,
                                       int bufferSizeKb,
                                       boolean apps) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_DFX_START_TRACING);
        intent.putExtra(INTENT_EXTRA_TAGS, new ArrayList(tags));
        intent.putExtra(INTENT_EXTRA_BUFFER, bufferSizeKb);
        intent.putExtra(INTENT_EXTRA_APPS, apps);
        context.startForegroundService(intent);
    }

    public static void stopTracing(final Context context) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_FANS_STOP_TRACING);
        context.startForegroundService(intent);
    }

    public static void stopDfxTracing(final Context context) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_DFX_STOP_TRACING);
        context.startForegroundService(intent);
    }

    public TraceService() {
        this("TraceService");
    }

    protected TraceService(String name) {
        super(name);
        setIntentRedelivery(true);
    }

    /**
     * Create a notification builder for trace recording.
     *
     * @param action The action type (FANS or DFX)
     * @param isPlaceholder Whether this is a placeholder notification (minimal content)
     * @return Notification.Builder configured for trace recording
     */
    private Notification.Builder createRecordingNotificationBuilder(String action,
                                                                    boolean isPlaceholder) {
        Context context = getApplicationContext();

        Notification.Builder builder =
                new Notification.Builder(context, Receiver.NOTIFICATION_CHANNEL_TRACING)
                        .setSmallIcon(R.drawable.bugfood_icon)
                        .setLocalOnly(true)
                        .setColor(getColor(
                                com.android.internal.R.color.system_notification_accent_color));

        if (isPlaceholder) {
            builder.setContentTitle(getString(R.string.trace_is_being_recorded))
                    .setContentText(getString(R.string.stop_tracing));
        } else {
            String title = context.getString(R.string.trace_is_being_recorded);
            String msg = context.getString(R.string.tap_to_stop_tracing);

            String stopAction = INTENT_ACTION_DFX_START_TRACING.equals(action)
                                        ? Receiver.DFX_STOP_ACTION
                                        : Receiver.STOP_ACTION;

            Intent stopIntent = new Intent(stopAction, null, context, Receiver.class);
            stopIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

            builder.setContentTitle(title)
                    .setTicker(title)
                    .setContentText(msg)
                    .setContentIntent(PendingIntent.getBroadcast(
                            context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE))
                    .setOngoing(true);
        }

        return builder;
    }

    /**
     * Create a notification builder for trace saving.
     *
     * @param isPlaceholder Whether this is a placeholder notification (minimal content)
     * @return Notification.Builder configured for trace saving
     */
    private Notification.Builder createSavingNotificationBuilder(boolean isPlaceholder) {
        Notification.Builder builder =
                new Notification.Builder(this, Receiver.NOTIFICATION_CHANNEL_OTHER)
                        .setSmallIcon(R.drawable.bugfood_icon)
                        .setLocalOnly(true)
                        .setColor(getColor(
                                com.android.internal.R.color.system_notification_accent_color));

        if (isPlaceholder) {
            builder.setContentTitle(getString(R.string.saving_trace))
                    .setContentText(getString(R.string.saving_trace));
        } else {
            builder.setContentTitle(getString(R.string.saving_trace))
                    .setTicker(getString(R.string.saving_trace))
                    .setContentText(getString(R.string.saving_trace))
                    .setProgress(1, 0, true);  // Indeterminate progress
        }

        return builder;
    }

    @Override
    public void onHandleIntent(Intent intent) {
        Context context = getApplicationContext();
        String action = intent.getAction();

        if (INTENT_ACTION_FANS_STOP_TRACING.equals(action)) {
            stopTracingInternal(TraceUtils.getOutputFilename(INTENT_ACTION_FANS_STOP_TRACING),
                                true,
                                INTENT_ACTION_FANS_STOP_TRACING);
        } else if (INTENT_ACTION_FANS_START_TRACING.equals(action)) {
            startTracingInternal(intent.getStringArrayListExtra(INTENT_EXTRA_TAGS),
                                 intent.getIntExtra(INTENT_EXTRA_BUFFER,
                                                    Integer.parseInt(context.getString(
                                                            R.string.default_buffer_size))),
                                 intent.getBooleanExtra(INTENT_EXTRA_APPS, false),
                                 INTENT_ACTION_FANS_START_TRACING);
        } else if (INTENT_ACTION_DFX_STOP_TRACING.equals(action)) {
            stopTracingInternal(TraceUtils.getOutputFilename(INTENT_ACTION_DFX_STOP_TRACING),
                                true,
                                INTENT_ACTION_DFX_STOP_TRACING);
        } else if (INTENT_ACTION_DFX_START_TRACING.equals(action)) {
            startTracingInternal(intent.getStringArrayListExtra(INTENT_EXTRA_TAGS),
                                 DEFAULT_DFX_BUFFER_SIZE,
                                 intent.getBooleanExtra(INTENT_EXTRA_APPS, false),
                                 INTENT_ACTION_DFX_START_TRACING);
        }
    }

    /**
     * Internal method to start tracing with notification.
     * Replaces placeholder notification with full notification.
     *
     * @param tags The trace tags to enable
     * @param bufferSizeKb Buffer size in KB
     * @param appTracing Whether to enable app tracing
     * @param action The action type (INTENT_ACTION_FANS_START_TRACING or
     *         INTENT_ACTION_DFX_START_TRACING)
     */
    private void startTracingInternal(Collection<String> tags,
                                      int bufferSizeKb,
                                      boolean appTracing,
                                      String action) {
        Context context = getApplicationContext();

        Notification notification = createRecordingNotificationBuilder(action, false).build();
        startForeground(
                TRACE_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        int ret = TraceUtils.traceStart(tags, bufferSizeKb, appTracing, false, 0, 0, action);

        String prefKey = INTENT_ACTION_DFX_START_TRACING.equals(action)
                                 ? context.getString(R.string.pref_key_dfx_tracing_on)
                                 : context.getString(R.string.pref_key_tracing_on);

        if (ret == 1) {
            // Trace started successfully - detach notification (keep it visible)
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else if (ret == -1) {
            // Starting the trace was unsuccessful - cleanup
            TraceUtils.traceStop(action);
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(prefKey, false)
                    .commit();
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else if (ret == 0) {
            // Nothing to do
        }
    }

    /**
     * Internal method to stop tracing and save trace file.
     * Replaces placeholder notification with full saving notification.
     *
     * @param outputFilename The output file name
     * @param forceStop Whether this is a forced stop
     * @param action The action type (INTENT_ACTION_FANS_STOP_TRACING or
     *         INTENT_ACTION_DFX_STOP_TRACING)
     */
    private void stopTracingInternal(String outputFilename, boolean forceStop, String action) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);

        // Create and post full saving notification (replaces placeholder with same ID)
        Notification notification = createSavingNotificationBuilder(false).build();
        startForeground(SAVING_TRACE_NOTIFICATION, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        // Cancel any existing recording notification
        notificationManager.cancel(TRACE_NOTIFICATION);

        // Get output file and dump trace
        File file = TraceUtils.getOutputFile(outputFilename, forceStop, action);

        if (TraceUtils.traceDump(file, action)) {
            // Determine property key based on action
            String traceNameProperty = INTENT_ACTION_DFX_STOP_TRACING.equals(action)
                                               ? "tr_trace.dfx_trace.trace_name"
                                               : "tr_trace.fans_trace.trace_name";

            // Set trace name property
            SystemProperties.set(traceNameProperty, "");
            if (forceStop) {
                SystemProperties.set(traceNameProperty, outputFilename);
            }
        }

        // Remove saving notification and stop foreground
        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        // Cleanup old files
        TraceUtils.cleanupOlderFiles(MIN_KEEP_COUNT, MIN_KEEP_AGE);
    }

    /**
     * Ensure notification channels are created before calling startForeground.
     */
    private void ensureNotificationChannelsCreated() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        // Create TRACING channel if not exists
        if (nm.getNotificationChannel(Receiver.NOTIFICATION_CHANNEL_TRACING) == null) {
            NotificationChannel tracingChannel = new NotificationChannel(
                    Receiver.NOTIFICATION_CHANNEL_TRACING,
                    getString(R.string.trace_is_being_recorded),
                    NotificationManager.IMPORTANCE_HIGH);
            tracingChannel.setBypassDnd(true);
            tracingChannel.enableVibration(false);
            tracingChannel.setSound(null, null);
            nm.createNotificationChannel(tracingChannel);
        }

        // Create OTHER channel if not exists
        if (nm.getNotificationChannel(Receiver.NOTIFICATION_CHANNEL_OTHER) == null) {
            NotificationChannel saveTraceChannel = new NotificationChannel(
                    Receiver.NOTIFICATION_CHANNEL_OTHER,
                    getString(R.string.saving_trace),
                    NotificationManager.IMPORTANCE_HIGH);
            saveTraceChannel.setBypassDnd(true);
            saveTraceChannel.enableVibration(false);
            saveTraceChannel.setSound(null, null);
            nm.createNotificationChannel(saveTraceChannel);
        }
    }

    /**
     * Start foreground with generic notification immediately to satisfy system requirement.
     */
    private void startForegroundWithGenericNotification() {
        Notification notification = new Notification.Builder(this, Receiver.NOTIFICATION_CHANNEL_TRACING)
                .setSmallIcon(R.drawable.bugfood_icon)
                .setContentTitle(getString(R.string.trace_is_being_recorded))
                .setContentText(getString(R.string.stop_tracing))
                .setLocalOnly(true)
                .setColor(getColor(com.android.internal.R.color.system_notification_accent_color))
                .build();

        startForeground(TRACE_NOTIFICATION, notification,
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }
}
