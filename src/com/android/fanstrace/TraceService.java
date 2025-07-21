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

import android.app.IntentService;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

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
    protected static final boolean FANS_AUTO_START = !(SystemProperties.getBoolean("ro.config.low_ram", false) ||
            "go".equals(SystemProperties.get("ro.tr_build.device.type", "")) ||
            "slim".equals(SystemProperties.get("ro.tr_build.device.type", "")));
    private static int TRACE_NOTIFICATION = 1;
    private static int SAVING_TRACE_NOTIFICATION = 2;

    private static final int MIN_KEEP_COUNT = 1;
    private static final long MIN_KEEP_AGE = 2 * DateUtils.DAY_IN_MILLIS;

    public static void startTracing(
            final Context context, Collection<String> tags, int bufferSizeKb, boolean apps) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_FANS_START_TRACING);
        intent.putExtra(INTENT_EXTRA_TAGS, new ArrayList(tags));
        intent.putExtra(INTENT_EXTRA_BUFFER, bufferSizeKb);
        intent.putExtra(INTENT_EXTRA_APPS, apps);
        context.startForegroundService(intent);
    }

    public static void startDfxTracing(
            final Context context, Collection<String> tags, int bufferSizeKb, boolean apps) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_DFX_START_TRACING);
        intent.putExtra(INTENT_EXTRA_TAGS, new ArrayList(tags));
        intent.putExtra(INTENT_EXTRA_BUFFER, bufferSizeKb); // receiver调用处写死启动缓冲区大小
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

    @Override
    public void onHandleIntent(Intent intent) {
        Context context = getApplicationContext();

        if (intent.getAction().equals(INTENT_ACTION_FANS_STOP_TRACING)) {
            stopTracingInternal(
                    TraceUtils.getOutputFilename(INTENT_ACTION_FANS_STOP_TRACING), true);
        } else if (intent.getAction().equals(INTENT_ACTION_FANS_START_TRACING)) {
            startTracingInternal(intent.getStringArrayListExtra(INTENT_EXTRA_TAGS),
                    intent.getIntExtra(INTENT_EXTRA_BUFFER,
                            Integer.parseInt(context.getString(R.string.default_buffer_size))),
                    intent.getBooleanExtra(INTENT_EXTRA_APPS, false));
        } else if (intent.getAction().equals(INTENT_ACTION_DFX_STOP_TRACING)) {
            stopDfxTracingInternal(
                    TraceUtils.getOutputFilename(INTENT_ACTION_DFX_STOP_TRACING), true);
        } else if (intent.getAction().equals(INTENT_ACTION_DFX_START_TRACING)) {
            startDfxTracingInternal(intent.getStringArrayListExtra(INTENT_EXTRA_TAGS),
                    8192, // 暂时写死DFX默认buffer
                    intent.getBooleanExtra(INTENT_EXTRA_APPS, false));
        }
    }

    private void startTracingInternal(
            Collection<String> tags, int bufferSizeKb, boolean appTracing) {
        Context context = getApplicationContext();
        Intent stopIntent = new Intent(Receiver.STOP_ACTION, null, context, Receiver.class);
        stopIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        String title = context.getString(R.string.trace_is_being_recorded);
        String msg = context.getString(R.string.tap_to_stop_tracing);

        Notification.Builder notification =
                new Notification.Builder(context, Receiver.NOTIFICATION_CHANNEL_TRACING)
                        .setSmallIcon(R.drawable.bugfood_icon)
                        .setContentTitle(title)
                        .setTicker(title)
                        .setContentText(msg)
                        .setContentIntent(PendingIntent.getBroadcast(context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE))
                        .setOngoing(true)
                        .setLocalOnly(true)
                        .setColor(getColor(
                                com.android.internal.R.color.system_notification_accent_color));

        startForeground(TRACE_NOTIFICATION, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        int ret = TraceUtils.traceStart(
                tags, bufferSizeKb, appTracing, false, 0, 0, INTENT_ACTION_FANS_START_TRACING);
        if (ret == 1) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else if (ret == -1) {
            // Starting the trace was unsuccessful, so ensure that tracing
            // is stopped and the preference is reset.
            TraceUtils.traceStop(INTENT_ACTION_FANS_START_TRACING);
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(context.getString(R.string.pref_key_tracing_on), false)
                    .commit();
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else if (ret == 0) {
            // Nothing do
        }
    }

    private void stopTracingInternal(String outputFilename, boolean forceStop) {
        Context context = getApplicationContext();
        NotificationManager notificationManager = getSystemService(NotificationManager.class);

        Notification.Builder notification =
                new Notification.Builder(this, Receiver.NOTIFICATION_CHANNEL_OTHER)
                        .setSmallIcon(R.drawable.bugfood_icon)
                        .setContentTitle(getString(R.string.saving_trace))
                        .setTicker(getString(R.string.saving_trace))
                        .setLocalOnly(true)
                        .setProgress(1, 0, true)
                        .setColor(getColor(
                                com.android.internal.R.color.system_notification_accent_color));

        startForeground(SAVING_TRACE_NOTIFICATION, notification.build());

        notificationManager.cancel(TRACE_NOTIFICATION);

        File file = TraceUtils.getOutputFile(
                outputFilename, forceStop, INTENT_ACTION_FANS_STOP_TRACING);

        if (TraceUtils.traceDump(file, INTENT_ACTION_FANS_STOP_TRACING)) {
            SystemProperties.set("tr_trace.dfx_trace.trace_name", "");
            if (forceStop) {
                SystemProperties.set("tr_trace.dfx_trace.trace_name", outputFilename);
            }
        }

        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        TraceUtils.cleanupOlderFiles(MIN_KEEP_COUNT, MIN_KEEP_AGE);
    }

    // SPD: add for jobService for fanstrace by zhanwei.bai at 20210624 end
    private void startDfxTracingInternal(
            Collection<String> tags, int bufferSizeKb, boolean appTracing) {
        Context context = getApplicationContext();
        Intent stopIntent = new Intent(Receiver.DFX_STOP_ACTION, null, context,
                Receiver.class); // 判断DFX和fans不同的ACTION
        stopIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        String title = context.getString(R.string.trace_is_being_recorded);
        String msg = context.getString(R.string.tap_to_stop_tracing);

        Notification.Builder notification =
                new Notification.Builder(context, Receiver.NOTIFICATION_CHANNEL_TRACING)
                        .setSmallIcon(R.drawable.bugfood_icon)
                        .setContentTitle(title)
                        .setTicker(title)
                        .setContentText(msg)
                        .setContentIntent(PendingIntent.getBroadcast(context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE))
                        .setOngoing(true)
                        .setLocalOnly(true)
                        .setColor(getColor(
                                com.android.internal.R.color.system_notification_accent_color));

        startForeground(TRACE_NOTIFICATION, notification.build());
        int ret = TraceUtils.traceStart(tags, bufferSizeKb, appTracing, false, 0, 0,
                INTENT_ACTION_DFX_START_TRACING); // 传值时buffer已经被固定到1000
        if (ret == 1) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else if (ret == -1) {
            // Starting the trace was unsuccessful, so ensure that tracing
            // is stopped and the preference is reset.
            TraceUtils.traceStop(INTENT_ACTION_DFX_STOP_TRACING);
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(context.getString(R.string.pref_key_tracing_on), false)
                    .commit();
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else if (ret == 0) {
            // Nothing do
        }
    }

    private void stopDfxTracingInternal(String outputFilename, boolean forceStop) {
        Context context = getApplicationContext();
        NotificationManager notificationManager = getSystemService(NotificationManager.class);

        Notification.Builder notification =
                new Notification.Builder(this, Receiver.NOTIFICATION_CHANNEL_OTHER)
                        .setSmallIcon(R.drawable.bugfood_icon)
                        .setContentTitle(getString(R.string.saving_trace))
                        .setTicker(getString(R.string.saving_trace))
                        .setLocalOnly(true)
                        .setProgress(1, 0, true)
                        .setColor(getColor(
                                com.android.internal.R.color.system_notification_accent_color));

        startForeground(SAVING_TRACE_NOTIFICATION, notification.build());

        notificationManager.cancel(TRACE_NOTIFICATION);

        File file =
                TraceUtils.getOutputFile(outputFilename, forceStop, INTENT_ACTION_DFX_STOP_TRACING);

        if (TraceUtils.traceDump(file, INTENT_ACTION_DFX_STOP_TRACING)) {
            SystemProperties.set("tr_trace.dfx_trace.trace_name", "");
            if (forceStop) {
                SystemProperties.set("tr_trace.dfx_trace.trace_name", outputFilename);
            }
        }

        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        TraceUtils.cleanupOlderFiles(MIN_KEEP_COUNT, MIN_KEEP_AGE);
    }
}
