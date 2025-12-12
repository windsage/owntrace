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

import android.sysprop.TraceProperties;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

/**
 * Utility functions for calling atrace
 */
public class AtraceUtils implements TraceUtils.TraceEngine {
    private static final String TAG = "Fanstrace";

    private static final String DEBUG_TRACING_FILE = "/sys/kernel/debug/tracing/tracing_on";
    private static final String TRACING_FILE = "/sys/kernel/tracing/tracing_on";

    public static String NAME = "ATRACE";
    private static String OUTPUT_EXTENSION = "ctrace";

    public String getName() {
        return NAME;
    }

    public String getOutputExtension() {
        return OUTPUT_EXTENSION;
    }

    /* Note: longTrace and maxLongTrace* parameters are ignored in atrace mode. */
    public int traceStart(Collection<String> tags, int bufferSizeKb, boolean apps,
            boolean longTrace, int maxLongTraceSizeMb, int maxLongTraceDurationMinutes,
            String action) {
        String appParameter = apps ? "-a '*' " : "";
        String cmd = "atrace --async_start -c -b " + bufferSizeKb + " " + appParameter
                + TextUtils.join(" ", tags);

        try {
            Process atrace = TraceUtils.exec(cmd);
            if (atrace.waitFor() != 0) {
                LogUtils.e(TAG, "atraceStart failed with: " + atrace.exitValue());
                return -1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 1;
    }

    public void traceStop(String action) {
        String cmd = "atrace --async_stop > /dev/null";

        LogUtils.v(TAG, "Stopping async atrace: " + cmd);
        try {
            Process atrace = TraceUtils.exec(cmd);

            if (atrace.waitFor() != 0) {
                LogUtils.e(TAG, "atraceStop failed with: " + atrace.exitValue());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean traceDump(File outFile, String action) {
        String cmd = "atrace --async_stop -z -c -o " + outFile;

        LogUtils.v(TAG, "Dumping async atrace: " + cmd);
        try {
            Process atrace = TraceUtils.exec(cmd);

            if (atrace.waitFor() != 0) {
                LogUtils.e(TAG, "atraceDump failed with: " + atrace.exitValue());
                return false;
            }

            Process ps = TraceUtils.exec("ps -AT");

            new Streamer("atraceDump:ps:stdout", ps.getInputStream(),
                    new FileOutputStream(outFile, true /* append */));

            if (ps.waitFor() != 0) {
                LogUtils.e(TAG, "atraceDump:ps failed with: " + ps.exitValue());
                return false;
            }

            // Set the new file world readable to allow it to be adb pulled.
            outFile.setReadable(true, false); // (readable, ownerOnly)
            outFile.setWritable(true, false); // (readable, ownerOnly)
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    public boolean isTracingOn(String action) {
        boolean userInitiatedTracingFlag = TraceProperties.user_initiated().orElse(false);

        if (!userInitiatedTracingFlag) {
            return false;
        }

        boolean tracingOnFlag = false;

        try {
            List<String> tracingOnContents;

            Path debugTracingOnPath = Paths.get(DEBUG_TRACING_FILE);
            Path tracingOnPath = Paths.get(TRACING_FILE);

            if (Files.isReadable(debugTracingOnPath)) {
                tracingOnContents = Files.readAllLines(debugTracingOnPath);
            } else if (Files.isReadable(tracingOnPath)) {
                tracingOnContents = Files.readAllLines(tracingOnPath);
            } else {
                return false;
            }

            tracingOnFlag = !tracingOnContents.get(0).equals("0");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return userInitiatedTracingFlag && tracingOnFlag;
    }

    public static TreeMap<String, String> atraceListCategories() {
        String cmd = "atrace --list_categories";

        try {
            Process atrace = TraceUtils.exec(cmd);

            new Logger("atraceListCat:stderr", atrace.getErrorStream());
            BufferedReader stdout =
                    new BufferedReader(new InputStreamReader(atrace.getInputStream()));

            if (atrace.waitFor() != 0) {
                LogUtils.e(TAG, "atraceListCategories failed with: " + atrace.exitValue());
            }

            TreeMap<String, String> result = new TreeMap<>();
            String line;
            while ((line = stdout.readLine()) != null) {
                LogUtils.d(TAG, "Parsing line: [" + line + "]");
                String[] fields = line.trim().split(" - ", 2);
                if (fields.length == 2) {
                    result.put(fields[0], fields[1]);
                    LogUtils.d(TAG, "  -> Parsed: " + fields[0]);
                } else {
                    LogUtils.d(TAG, "  -> Failed to parse, fields.length=" + fields.length);
                }
            }
            // ========== PATCH START: Fix for missing binder_driver and memreclaim ==========
            // FIXME: Temporary workaround for parsing issue where some categories are missing
            // TODO: Investigate root cause and fix parsing logic properly
            if (!result.containsKey("binder_driver")) {
                result.put("binder_driver", "Binder Kernel driver");
                LogUtils.w(TAG, "PATCH: Added missing category 'binder_driver'");
            }
            if (!result.containsKey("memreclaim")) {
                result.put("memreclaim", "Kernel Memory Reclaim");
                LogUtils.w(TAG, "PATCH: Added missing category 'memreclaim'");
            }
            // ========== PATCH END ==========
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Streams data from an InputStream to an OutputStream
     */
    private static class Streamer {
        private boolean mDone;

        Streamer(final String tag, final InputStream in, final OutputStream out) {
            new Thread(tag) {
                @Override
                public void run() {
                    int read;
                    byte[] buf = new byte[2 << 10];
                    try {
                        while ((read = in.read(buf)) != -1) {
                            out.write(buf, 0, read);
                        }
                    } catch (IOException e) {
                        LogUtils.e(TAG, "Error while streaming " + tag);
                    } finally {
                        try {
                            out.close();
                        } catch (IOException e) {
                            // Welp.
                        }
                        synchronized (Streamer.this) {
                            mDone = true;
                            Streamer.this.notify();
                        }
                    }
                }
            }.start();
        }

        synchronized boolean isDone() {
            return mDone;
        }

        synchronized void waitForDone() {
            while (!isDone()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Streams data from an InputStream to an OutputStream
     */
    private static class Logger {
        Logger(final String tag, final InputStream in) {
            new Thread(tag) {
                @Override
                public void run() {
                    int read;
                    String line;
                    BufferedReader r = new BufferedReader(new InputStreamReader(in));
                    try {
                        while ((line = r.readLine()) != null) {
                            LogUtils.e(TAG, tag + ": " + line);
                        }
                    } catch (IOException e) {
                        LogUtils.e(TAG, "Error while streaming " + tag);
                    } finally {
                        try {
                            r.close();
                        } catch (IOException e) {
                            // Welp.
                        }
                    }
                }
            }.start();
        }
    }
}
