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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public class StopDfxTraceService extends TraceService {
    private static final String TAG = TraceUtils.TAG;

    public StopDfxTraceService() {
        super("StopDfxTraceService");
        setIntentRedelivery(true);
    }

    /* If we stop a trace using this entrypoint, we must also reset the preference and the
     * Quick Settings UI, since this may be the only indication that the user wants to stop the
     * trace.
     */
    @Override
    public void onHandleIntent(Intent intent) {
        ensureForegroundStarted(NotificationType.TRACE_SAVING);
        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // If the user thinks tracing is off and the trace processor agrees, we have no work to do.
        // We must still start a foreground service, but let's log as an FYI.
        if (!TraceUtils.TraceStateChecker.shouldStopTrace(INTENT_ACTION_DFX_STOP_TRACING)) {
            LogUtils.i(TAG, "StopDfxTraceService: nothing to stop, skipping");

            // 重置preference
            prefs.edit().putBoolean(context.getString(R.string.pref_key_dfx_tracing_on), false).commit();
            cleanupPlaceholderNotification(NotificationType.TRACE_SAVING);
            return;
        }

        LogUtils.i(TAG, "StopDfxTraceService: executing stop flow");
        prefs.edit().putBoolean(context.getString(R.string.pref_key_dfx_tracing_on), false).commit();
        // context.sendBroadcast(new Intent(MainFragment.ACTION_REFRESH_TAGS));

        intent.setAction(INTENT_ACTION_DFX_STOP_TRACING);
        super.onHandleIntent(intent);
    }
}
