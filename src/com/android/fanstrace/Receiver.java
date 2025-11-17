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
            long bootTime = SystemClock.elapsedRealtime();
            long currentUptime = SystemClock.uptimeMillis();

            LogUtils.i(TAG, "BOOT_COMPLETED received - bootTime: " + bootTime +
                       ", uptime: " + currentUptime);

            // important: 不知何故会在异常重启后收到开机广播
            // 如果系统运行时间很长,说明这不是真正的开机
            if (bootTime > 60000) {
                LogUtils.w(TAG, "BOOT_COMPLETED received but system uptime is " +
                           bootTime + "ms, ignoring as false boot signal");
                return;  // 忽略这个虚假的开机广播
            }
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
}
