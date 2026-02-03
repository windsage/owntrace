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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.ArraySet;

import androidx.preference.PreferenceManager;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class Receiver extends BroadcastReceiver {
    public static final String STOP_ACTION = "com.android.fanstrace.STOP";
    public static final String OPEN_ACTION = "com.android.fanstrace.OPEN";

    public static final String DFX_STOP_ACTION = "com.android.fanstrace.DFXSTOP";
    public static final String DFX_OPEN_ACTION = "com.android.fanstrace.DFXOPEN";

    public static final String NOTIFICATION_CHANNEL_TRACING = "trace-is-being-recorded";
    public static final String NOTIFICATION_CHANNEL_OTHER = "system-tracing";

    private static final String SECRET_CODE_ACTION = "android.provider.Telephony.SECRET_CODE";

    private final Uri mDebugUri = Uri.parse("android_secret_code://54321");
    private final Uri mRootUri = Uri.parse("android_secret_code://67890");

    private static final List<String> TRACE_TAGS = Arrays.asList(
            "am", "camera", "gfx", "hal", "aidl", "input", "view", "binder_driver", "wm", "dalvik", "memreclaim");

    /* The user list doesn't include workq, irq, or sync, because the user builds don't have
     * permissions for them. */
    private static final List<String> TRACE_TAGS_USER = Arrays.asList(
            "am", "camera", "gfx", "hal", "aidl", "input", "view", "binder_driver", "wm", "dalvik", "memreclaim");

    private static final String TAG = "Fanstrace";

    private static Set<String> mDefaultTagList = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        LogUtils.i(TAG, "onReceive action =" + intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            long elapsedRealtime = SystemClock.elapsedRealtime();
            long uptimeMillis = SystemClock.uptimeMillis();
            long currentTimeMillis = System.currentTimeMillis();

            LogUtils.i(TAG, "BOOT_COMPLETED received - elapsed: " + elapsedRealtime +
                       "ms, uptime: " + uptimeMillis + "ms, realTime: " + currentTimeMillis);

            // Multi-dimensional time check to distinguish real boot from false broadcast
            if (!isRealBootBroadcast(context, elapsedRealtime, uptimeMillis, currentTimeMillis)) {
                LogUtils.w(TAG, "BOOT_COMPLETED is a false signal (force-stop triggered), ignoring");
                return;
            }

            LogUtils.i(TAG, "BOOT_COMPLETED verified as real boot signal, proceeding with trace startup");
            if (TraceService.BOOT_START) {
                createNotificationChannels(context);

                // We know that Perfetto won't be tracing already at boot, so pass the
                // tracingIsOff argument to avoid the Perfetto check.
                prefs.edit()
                        .putBoolean(context.getString(R.string.pref_key_tracing_on), true)
                        .putBoolean(context.getString(R.string.pref_key_dfx_tracing_on), true)
                        .commit();
                updateTracing(context, /* assumeTracingIsOff= */ true);
            }
        } else if (SECRET_CODE_ACTION.equals(intent.getAction())) {
            Uri uri = intent.getData();
            Intent in = new Intent(context, MainActivity.class);

            if (mDebugUri.equals(uri)) {
                in.putExtra("switch", 0);
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(in);
            } else if (mRootUri.equals(uri)) {
                in.putExtra("switch", 1);
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(in);
            }
        }
    }

    /*
     * Updates the current tracing state based on the current state of preferences.
     */
    public static void updateTracing(Context context) {
        updateTracing(context, false);
    }

    public static void updateTracing(Context context, boolean assumeTracingIsOff) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean prefsFansTracingOn =
                prefs.getBoolean(context.getString(R.string.pref_key_tracing_on), false);
        boolean prefsDfxTracingOn =
                prefs.getBoolean(context.getString(R.string.pref_key_dfx_tracing_on), false);

        boolean fansTracingOn = assumeTracingIsOff
                ? false
                : TraceUtils.isTracingOn(TraceService.INTENT_ACTION_FANS_START_TRACING);
        boolean dfxTracingOn = assumeTracingIsOff
                ? false
                : TraceUtils.isTracingOn(TraceService.INTENT_ACTION_DFX_START_TRACING);

        // Handle FANS trace
        if (prefsFansTracingOn != fansTracingOn) {
            if (prefsFansTracingOn) {
                Set<String> activeAvailableTags = getActiveTags(context, prefs, true);
                int bufferSize = Integer.parseInt(
                        prefs.getString(context.getString(R.string.pref_key_buffer_size),
                                context.getString(R.string.default_buffer_size)));
                boolean appTracing =
                        prefs.getBoolean(context.getString(R.string.pref_key_apps), false);

                if (TraceService.isFansAutoStart(context)) {
                    TraceService.startTracing(context, activeAvailableTags, bufferSize, appTracing);
                }
            } else {
                if (TraceService.isFansAutoStart(context)) {
                    TraceService.stopTracing(context);
                }
            }
        }

        // Handle DFX trace
        if (prefsDfxTracingOn != dfxTracingOn) {
            if (prefsDfxTracingOn) {
                Set<String> activeAvailableTags = getActiveTags(context, prefs, true);
                boolean appTracing =
                        prefs.getBoolean(context.getString(R.string.pref_key_apps), false);
                TraceService.startDfxTracing(context, activeAvailableTags, TraceService.DEFAULT_DFX_BUFFER_SIZE, appTracing);
            } else {
                TraceService.stopDfxTracing(context);
            }
        }
    }

    private static void createNotificationChannels(Context context) {
        NotificationChannel tracingChannel = new NotificationChannel(NOTIFICATION_CHANNEL_TRACING,
                context.getString(R.string.trace_is_being_recorded),
                NotificationManager.IMPORTANCE_HIGH);
        tracingChannel.setBypassDnd(true);
        tracingChannel.enableVibration(false);
        tracingChannel.setSound(null, null);

        NotificationChannel saveTraceChannel = new NotificationChannel(NOTIFICATION_CHANNEL_OTHER,
                context.getString(R.string.saving_trace), NotificationManager.IMPORTANCE_HIGH);
        saveTraceChannel.setBypassDnd(true);
        saveTraceChannel.enableVibration(false);
        saveTraceChannel.setSound(null, null);

        NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(tracingChannel);
        notificationManager.createNotificationChannel(saveTraceChannel);
    }

    public static Set<String> getActiveTags(
            Context context, SharedPreferences prefs, boolean onlyAvailable) {
        Set<String> tags =
                prefs.getStringSet(context.getString(R.string.pref_key_tags), getDefaultTagList());
        Set<String> available = TraceUtils.listCategories().keySet();

        if (onlyAvailable) {
            tags.retainAll(available);
        }

        return tags;
    }

    public static Set<String> getActiveUnavailableTags(Context context, SharedPreferences prefs) {
        Set<String> tags =
                prefs.getStringSet(context.getString(R.string.pref_key_tags), getDefaultTagList());
        Set<String> available = TraceUtils.listCategories().keySet();

        tags.removeAll(available);
        return tags;
    }

    public static Set<String> getDefaultTagList() {
        if (mDefaultTagList == null) {
            mDefaultTagList =
                    new ArraySet<String>(Build.TYPE.equals("user") ? TRACE_TAGS_USER : TRACE_TAGS);
        }

        return mDefaultTagList;
    }

    /**
     * Detect whether this is a real boot broadcast or a false broadcast triggered by force-stop.
     * Uses multi-dimensional time analysis to make accurate judgment.
     *
     * @param context Application context
     * @param elapsedRealtime System elapsed time including sleep (from SystemClock.elapsedRealtime())
     * @param uptimeMillis System uptime excluding sleep (from SystemClock.uptimeMillis())
     * @param currentTimeMillis Current real time (from System.currentTimeMillis())
     * @return true if this is a real boot broadcast, false if it's a false signal
     */
    private boolean isRealBootBroadcast(Context context, long elapsedRealtime,
                                         long uptimeMillis, long currentTimeMillis) {
        final long EARLY_BOOT_THRESHOLD = 120000;  // 120 seconds - boot broadcast may arrive 60-120s after boot
        final long EXTENDED_BOOT_THRESHOLD = 300000;  // 300 seconds - extended window for slow boots
        final long SLEEP_TIME_THRESHOLD = 10000;  // 10 seconds - max acceptable sleep time during early boot
        final long FALSE_BROADCAST_INTERVAL = 60000;  // 60 seconds - force-stop broadcasts arrive within this window
        final long REAL_TIME_JUMP_THRESHOLD = 3600000;  // 1 hour - real time jump indicating false broadcast

        LogUtils.d(TAG, "Boot detection - elapsed: " + elapsedRealtime + "ms, uptime: " + uptimeMillis +
                   "ms, sleepTime: " + (elapsedRealtime - uptimeMillis) + "ms");

        if (elapsedRealtime < EARLY_BOOT_THRESHOLD && uptimeMillis < EARLY_BOOT_THRESHOLD) {
            LogUtils.i(TAG, "Boot detection: REAL BOOT - system recently started (dimension 1)");
            updateBootTimestamp(context, elapsedRealtime, currentTimeMillis);
            return true;
        }

        long sleepTime = elapsedRealtime - uptimeMillis;
        if (elapsedRealtime < EXTENDED_BOOT_THRESHOLD && sleepTime < SLEEP_TIME_THRESHOLD) {
            LogUtils.i(TAG, "Boot detection: REAL BOOT - minimal sleep time during boot window (dimension 2)");
            updateBootTimestamp(context, elapsedRealtime, currentTimeMillis);
            return true;
        }

        SharedPreferences prefs = context.getSharedPreferences("fanstrace_boot_detector", Context.MODE_PRIVATE);
        long lastBootElapsed = prefs.getLong("last_boot_elapsed", 0);
        long lastBootRealTime = prefs.getLong("last_boot_real_time", 0);

        LogUtils.d(TAG, "Boot detection - last: elapsed=" + lastBootElapsed + "ms, realTime=" + lastBootRealTime);

        if (lastBootElapsed > elapsedRealtime) {
            LogUtils.i(TAG, "Boot detection: REAL BOOT - elapsed time reset detected (dimension 3)");
            updateBootTimestamp(context, elapsedRealtime, currentTimeMillis);
            return true;
        }

        if (lastBootElapsed > 0) {
            long realTimeDiff = Math.abs(currentTimeMillis - lastBootRealTime);
            long elapsedDiff = Math.abs(elapsedRealtime - lastBootElapsed);

            LogUtils.d(TAG, "Boot detection - diffs: realTime=" + realTimeDiff + "ms, elapsed=" + elapsedDiff + "ms");

            if (realTimeDiff > REAL_TIME_JUMP_THRESHOLD && elapsedDiff < FALSE_BROADCAST_INTERVAL) {
                LogUtils.w(TAG, "Boot detection: FALSE BOOT - force-stop pattern detected (dimension 4)");
                return false;
            }
        }

        LogUtils.i(TAG, "Boot detection: REAL BOOT - default conservative judgment (dimension 5)");
        updateBootTimestamp(context, elapsedRealtime, currentTimeMillis);
        return true;
    }

    /**
     * Update boot timestamp records for next detection cycle.
     *
     * @param context Application context
     * @param elapsedRealtime Current elapsed realtime
     * @param currentTimeMillis Current real time
     */
    private void updateBootTimestamp(Context context, long elapsedRealtime, long currentTimeMillis) {
        SharedPreferences prefs = context.getSharedPreferences("fanstrace_boot_detector", Context.MODE_PRIVATE);
        prefs.edit()
            .putLong("last_boot_elapsed", elapsedRealtime)
            .putLong("last_boot_real_time", currentTimeMillis)
            .apply();

        LogUtils.d(TAG, "Boot timestamp updated - elapsed: " + elapsedRealtime + "ms, realTime: " + currentTimeMillis);
    }
}
