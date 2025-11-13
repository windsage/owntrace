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

import java.util.ArrayList;
import java.util.Set;

public class StartDfxTraceService extends TraceService {
    private static final String TAG = TraceUtils.TAG;

    public StartDfxTraceService() {
        super("StartDfxTraceService");
        setIntentRedelivery(true);
    }

    /* If we stop a trace using this entrypoint, we must also reset the preference and the
     * Quick Settings UI, since this may be the only indication that the user wants to stop the
     * trace.
     */
    @Override
    public void onHandleIntent(Intent intent) {
        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // If the user thinks tracing is off and the trace processor agrees, we have no work to do.
        // We must still start a foreground service, but let's log as an FYI.
        if (TraceUtils.isTracingOn(INTENT_ACTION_DFX_START_TRACING)) {
            LogUtils.i(TAG, "StartDfxTraceService does see a trace is starting.");
        }

        prefs.edit().putBoolean(context.getString(R.string.pref_key_dfx_tracing_on), true).commit();
        // context.sendBroadcast(new Intent(MainFragment.ACTION_REFRESH_TAGS));
        Set<String> activeAvailableTags = Receiver.getActiveTags(context, prefs, true);
        int bufferSize = 8192; // 暂时写死
        LogUtils.i(TAG, "onHandleIntent bufferSize == " + bufferSize);
        boolean appTracing = prefs.getBoolean(context.getString(R.string.pref_key_apps), true);
        intent.setAction(INTENT_ACTION_DFX_START_TRACING);
        intent.putExtra(INTENT_EXTRA_TAGS, new ArrayList(activeAvailableTags));
        intent.putExtra(INTENT_EXTRA_BUFFER, bufferSize);
        intent.putExtra(INTENT_EXTRA_APPS, appTracing);
        super.onHandleIntent(intent);
    }
}
