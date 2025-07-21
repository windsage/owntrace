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
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/**
 * Utility functions for tracing.
 * Will call atrace or perfetto depending on the setting.
 */
public class TraceUtils {
    static final String TAG = "Fanstrace";

    public static final String FANS_TRACE_DIRECTORY = "/data/local/traces/fans/";
    public static final String DFX_TRACE_DIRECTORY = "/data/local/traces/DFX/";
    public static final String TRACE_DIRECTORY = "/data/local/traces/";

    // To change Traceur to use atrace to collect traces,
    // change mTraceEngine to point to AtraceUtils().
    private static TraceEngine mTraceEngine = new PerfettoUtils();

    private static final Runtime RUNTIME = Runtime.getRuntime();

    public interface TraceEngine {
        public String getName();

        public String getOutputExtension();

        public int traceStart(Collection<String> tags, int bufferSizeKb, boolean apps,
                              boolean longTrace, int maxLongTraceSizeMb, int maxLongTraceDurationMinutes,
                              String action);

        public void traceStop(String action);

        public boolean traceDump(File outFile, String action);

        public boolean isTracingOn(String action);
    }

    public static String currentTraceEngine() {
        return mTraceEngine.getName();
    }

    public static int traceStart(Collection<String> tags, int bufferSizeKb, boolean apps,
                                 boolean longTrace, int maxLongTraceSizeMb, int maxLongTraceDurationMinutes,
                                 String action) {
        return mTraceEngine.traceStart(tags, bufferSizeKb, apps, longTrace, maxLongTraceSizeMb,
                maxLongTraceDurationMinutes, action);
    }

    public static void traceStop(String action) {
        mTraceEngine.traceStop(action);
    }

    public static boolean traceDump(File outFile, String action) {
        return mTraceEngine.traceDump(outFile, action);
    }

    public static boolean isTracingOn(String action) {
        return mTraceEngine.isTracingOn(action);
    }

    public static TreeMap<String, String> listCategories() {
        return AtraceUtils.atraceListCategories();
    }

    public static void clearSavedTraces() {
        String cmd = "rm -f " + FANS_TRACE_DIRECTORY + "FANS-*.*trace";

        LogUtils.v(TAG, "Clearing trace directory: " + cmd);
        try {
            Process rm = exec(cmd);

            if (rm.waitFor() != 0) {
                LogUtils.e(TAG, "clearSavedTraces failed with: " + rm.exitValue());
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "clear Traces failed: " + e.getMessage());
        }
    }

    public static Process exec(String cmd) throws IOException {
        return exec(cmd, null);
    }

    public static Process exec(String cmd, String tmpdir) throws IOException {
        String[] cmdarray = {"sh", "-c", cmd};
        String[] envp = {"TMPDIR=" + tmpdir};
        envp = tmpdir == null ? null : envp;

        LogUtils.v(TAG, "exec: " + Arrays.toString(envp) + " " + Arrays.toString(cmdarray));

        return RUNTIME.exec(cmdarray, envp);
    }

    public static String getOutputFilename(String action) {
        String format = "yyyyMMddHHmmss";
        String now = new SimpleDateFormat(format, Locale.US).format(new Date());
        return String.format("%s.%s.%s", action.split("_")[1], now,
                mTraceEngine.getOutputExtension());
    }

    public static File getOutputFile(String filename, boolean fansStop, String action) {
        if (action == TraceService.INTENT_ACTION_DFX_STOP_TRACING) {
            return new File(TraceUtils.DFX_TRACE_DIRECTORY, filename);
        } else {
            return new File(fansStop ? TraceUtils.FANS_TRACE_DIRECTORY : TraceUtils.TRACE_DIRECTORY,
                    filename);
        }
    }

    protected static void cleanupOlderFiles(final int minCount, final long minAge) {
        FutureTask<Void> task = new FutureTask<Void>(
                () -> {
                    try {
                        deleteOlderFiles(new File(TRACE_DIRECTORY), minCount, minAge);
                    } catch (RuntimeException e) {
                        LogUtils.e(TAG, "Failed to delete older traces " + e.getMessage());
                    }
                    return null;
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // execute() instead of submit() because we don't need the result.
        executor.execute(task);
    }


    public static boolean deleteOlderFiles(File dir, int minCount, long minAgeMs) {
        if (minCount < 0 || minAgeMs < 0) {
            throw new IllegalArgumentException("Constraints must be positive or 0");
        }

        final File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        // Sort with newest files first
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                return Long.compare(rhs.lastModified(), lhs.lastModified());
            }
        });

        // Keep at least minCount files
        boolean deleted = false;
        for (int i = minCount; i < files.length; i++) {
            final File file = files[i];

            // Keep files newer than minAgeMs
            final long age = System.currentTimeMillis() - file.lastModified();
            if (age > minAgeMs) {
                if (file.delete()) {
                    LogUtils.v(TAG, "Deleted old file " + file);
                    deleted = true;
                }
            }
        }
        return deleted;
    }

}
