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

import android.os.SystemProperties;
import android.system.Os;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import android.os.Build;
/**
 * Utility functions for calling Perfetto
 */
public class PerfettoUtils implements TraceUtils.TraceEngine {
    static final String TAG = "Fanstrace";
    public static final String NAME = "PERFETTO";

    private static final String OUTPUT_EXTENSION = "ptrace";
    private static final String TEMP_DIR = "/data/local/traces/fans/";
    private static final String TEMP_DFX_DIR = "/data/local/traces/DFX/";
    private static final String TEMP_TRACE_LOCATION =
            "/data/local/traces/.fans_trace-in-progress.trace";
    private static final String TEMP_DFX_LOCATION =
            "/data/local/traces/.DFX_trace-in-progress.trace";

    private static final String PERFETTO_TAG = "Fanstrace";
    private static final String PERFETTO_DFX_TAG = "DFXtrace";
    private static final String MARKER = "PERFETTO_ARGUMENTS";
    private static final int STARTUP_TIMEOUT_MS = 10000;

    private static final String MEMORY_TAG = "memory";
    private static final String POWER_TAG = "power";
    private static final String SCHED_TAG = "sched";

    public String getName() {
        return NAME;
    }

    public String getOutputExtension() {
        return OUTPUT_EXTENSION;
    }

    public int traceStart(Collection<String> tags, int bufferSizeKb, boolean apps,
            boolean longTrace, int maxLongTraceSizeMb, int maxLongTraceDurationMinutes,
            String action) {
        if (isTracingOn(action)) {
            LogUtils.e(TAG, "Attempting to start perfetto trace but trace is already in progress");
            return 0;
        } else {
            // Ensure the temporary trace file is cleared.
            LogUtils.e(TAG, "" + action);
            if (action == TraceService.INTENT_ACTION_DFX_START_TRACING) {
                try {
                    Files.deleteIfExists(Paths.get(TEMP_DFX_LOCATION));
                } catch (Exception e) {
                    LogUtils.e(TAG, "Starting DFX trace: deleting failed!");
                }
            } else {
                try {
                    Files.deleteIfExists(Paths.get(TEMP_TRACE_LOCATION));
                } catch (Exception e) {
                    LogUtils.e(TAG, "Starting Fans trace: deleting failed!");
                }
            }
        }

        // The user chooses a per-CPU buffer size due to atrace limitations.
        // So we use this to ensure that we reserve the correctly-sized buffer.
        int numCpus = Runtime.getRuntime().availableProcessors();

        // Build the perfetto config that will be passed on the command line.
        StringBuilder config =
                new StringBuilder()
                        .append("write_into_file: true\n")
                        // Ensure that we flush ftrace data every 30s even if cpus are idle.
                        .append("flush_period_ms: 30000\n");

        // If we have set one of the long trace parameters, we must also
        // tell Perfetto to notify Traceur when the long trace is done.
        config.append("file_write_period_ms: 604800000\n");
        // This is target_buffer: 0, which is used for ftrace.
        config.append("incremental_state_config {\n")
                .append("  clear_period_ms: 15000\n")
                .append("} \n");

        config.append("buffers {\n")
                .append("  size_kb: " + bufferSizeKb * numCpus + "\n")
                .append("  fill_policy: RING_BUFFER\n")
                .append("} \n")
                // This is target_buffer: 1, which is used for additional data sources.
                .append("buffers {\n")
                .append("  size_kb: 2048\n")
                .append("  fill_policy: RING_BUFFER\n")
                .append("} \n")
                .append("data_sources {\n")
                .append("  config {\n")
                .append("    name: \"linux.ftrace\"\n")
                .append("    target_buffer: 0\n")
                .append("    ftrace_config {\n");

        for (String tag : tags) {
            // Tags are expected to be only letters, numbers, and underscores.
            String cleanTag = tag.replaceAll("[^a-zA-Z0-9_]", "");
            if (!cleanTag.equals(tag)) {
                LogUtils.w(TAG, "Attempting to use an invalid tag: " + tag);
            }
            config.append("      atrace_categories: \"" + cleanTag + "\"\n");
        }

        config.append("      atrace_apps: \"*\"\n");

        // Request a dense encoding of the common sched events (sched_switch,
        // sched_waking).
        if (tags.contains(SCHED_TAG)) {
            config.append("      compact_sched {\n");
            config.append("        enabled: true\n");
            config.append("      }\n");
        } else {
            config.append("      ftrace_events: \"sched/sched_switch\"\n");
            config.append("      ftrace_events: \"sched/sched_waking\"\n");
            // config.append("      ftrace_events: \"sched/sched_wakeup_new\"\n");
            config.append("      ftrace_events: \"power/cpu_frequency\"\n");
            config.append("      ftrace_events: \"kgsl/gpu_frequency\"\n");
            config.append("      ftrace_events: \"power/gpu_frequency\"\n");
            config.append("      ftrace_events: \"power/cpu_frequency_limits\"\n");
            // config.append("      ftrace_events: \"power/cpu_idle\"\n");
            // config.append("      ftrace_events: \"power/suspend_resume\"\n");
            // config.append("      ftrace_events: \"raw_syscalls/sys_enter\"\n");
            // config.append("      ftrace_events: \"raw_syscalls/sys_exit\"\n");
            // Temporarily used by the storage analysis at Jan 8th, 2025 start
            config.append("      ftrace_events: \"block/block_rq_insert\"\n");
            if (Build.PRODUCT.contains("X6879") || bufferSizeKb != 8192) {
                config.append("      ftrace_events: \"block/block_rq_issue\"\n");
                config.append("      ftrace_events: \"block/block_rq_complete\"\n");
            }
            // Temporarily used by the storage analysis at Jan 8th, 2025 end
            config.append("      ftrace_events: \"sched/sched_blocked_reason\"\n");
            config.append("      ftrace_events: \"trans_sched/dump_info\"\n");
            config.append("      ftrace_events: \"trans_mem/alter_rwsem_list_add\"\n");
            config.append("      ftrace_events: \"trans_mem/rwsem_owner\"\n");
            config.append("      ftrace_events: \"scheduler/sched_frequency_limits\"\n");
        }
        // These parameters affect only the kernel trace buffer size and how
        // frequently it gets moved into the userspace buffer defined above.
        config.append("      buffer_size_kb: 8192\n")
                .append("      drain_period_ms: 1000\n")
                .append("    }\n")
                .append("  }\n")
                .append("}\n")
                .append(" \n");

        // For process association. If the memory tag is enabled,
        // poll periodically instead of just once at the beginning.
        config.append("data_sources {\n")
                .append("  config {\n")
                .append("    name: \"linux.process_stats\"\n")
                .append("    target_buffer: 1\n");
        if (tags.contains(MEMORY_TAG)) {
            config.append("    process_stats_config {\n")
                    .append("      proc_stats_poll_ms: 60000\n")
                    .append("    }\n");
        }
        config.append("  }\n").append("} \n");

        // add frametimeline
        config.append("data_sources {\n")
                .append("  config {\n")
                .append("    name: \"android.surfaceflinger.frametimeline\"\n")
                .append("    target_buffer: 0\n")
                .append("  }\n").append("} \n");

        if (tags.contains(POWER_TAG)) {
            config.append("data_sources: {\n")
                    .append("  config { \n")
                    .append("    name: \"android.power\"\n")
                    .append("    target_buffer: 1\n")
                    .append("    android_power_config {\n");
            if (longTrace) {
                config.append("      battery_poll_ms: 5000\n");
            } else {
                config.append("      battery_poll_ms: 1000\n");
            }
            config.append("      collect_power_rails: true\n")
                    .append("      battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT\n")
                    .append("      battery_counters: BATTERY_COUNTER_CHARGE\n")
                    .append("      battery_counters: BATTERY_COUNTER_CURRENT\n")
                    .append("    }\n")
                    .append("  }\n")
                    .append("}\n");
        }

        if (tags.contains(MEMORY_TAG)) {
            config.append("data_sources: {\n")
                    .append("  config { \n")
                    .append("    name: \"android.sys_stats\"\n")
                    .append("    target_buffer: 1\n")
                    .append("    sys_stats_config {\n")
                    .append("      vmstat_period_ms: 1000\n")
                    .append("    }\n")
                    .append("  }\n")
                    .append("}\n");
        }

        String configString = config.toString();

        // If the here-doc ends early, within the config string, exit immediately.
        // This should never happen.
        if (configString.contains(MARKER)) {
            LogUtils.e(TAG, "The arguments to the Perfetto command are malformed.");
            return -1;
        }
        String cmd;
        if (action == TraceService.INTENT_ACTION_DFX_START_TRACING) {
            cmd = "perfetto --detach=" + PERFETTO_DFX_TAG + " -o " + TEMP_DFX_LOCATION
                    + " -c - --txt"
                    + " <<" + MARKER + "\n" + configString + "\n" + MARKER;
        } else {
            cmd = "perfetto --detach=" + PERFETTO_TAG + " -o " + TEMP_TRACE_LOCATION + " -c - --txt"
                    + " <<" + MARKER + "\n" + configString + "\n" + MARKER;
        }
        LogUtils.e(TAG, "" + action);
        LogUtils.v(TAG, "Starting perfetto trace.");
        try {
            Process process = (action == TraceService.INTENT_ACTION_DFX_START_TRACING)
                    ? TraceUtils.exec(cmd, TEMP_DFX_DIR)
                    : TraceUtils.exec(cmd, TEMP_DIR);
            // If we time out, ensure that the perfetto process is destroyed.
            if (!process.waitFor(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LogUtils.e(TAG,
                        "perfetto traceStart has timed out after " + STARTUP_TIMEOUT_MS + " ms.");
                process.destroyForcibly();
                return -1;
            }

            if (process.exitValue() != 0) {
                LogUtils.e(TAG, "perfetto traceStart failed with: " + process.exitValue());
                return -1;
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Trace start failed: " + e.getMessage());
        }

        LogUtils.v(TAG, "perfetto traceStart succeeded!");
        return 1;
    }

    public void traceStop(String action) {
        LogUtils.v(TAG, "Stopping perfetto trace.");

        if (!isTracingOn(action)) {
            LogUtils.w(TAG,
                    "No trace appears to be in progress. Stopping perfetto trace may not work.");
        }
        String cmd;
        if (action == TraceService.INTENT_ACTION_DFX_STOP_TRACING) {
            cmd = "perfetto --stop --attach=" + PERFETTO_DFX_TAG;
        } else {
            cmd = "perfetto --stop --attach=" + PERFETTO_TAG;
        }
        LogUtils.e(TAG, "" + action);
        try {
            Process process = TraceUtils.exec(cmd);
            if (process.waitFor() != 0) {
                LogUtils.e(TAG, "perfetto traceStop failed with: " + process.exitValue());
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Trace stop failed: " + e.getMessage());
        }
    }

    private static double getFileSizeInMB(String filePath) {
        File file = new File(filePath);
        if (file.exists() && file.isFile()) {
            double sizeInMB = file.length() / 1048576.0;
            DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
            symbols.setDecimalSeparator('.');
            DecimalFormat df = new DecimalFormat("#.##", symbols);
            df.setGroupingUsed(false);
            try {
                return df.parse(df.format(sizeInMB)).doubleValue();
            } catch (ParseException e) {
                e.printStackTrace();
                return -1;
            }
        } else {
            return -1;
        }
    }

    public boolean traceDump(File outFile, String action) {
        traceStop(action);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        // Short-circuit if the file we're trying to dump to doesn't exist.
        if (action == TraceService.INTENT_ACTION_DFX_STOP_TRACING) {
            if (!Files.exists(Paths.get(TEMP_DFX_LOCATION))) {
                LogUtils.e(TAG, "In-progress trace file doesn't exist, aborting trace dump.");
                return false;
            }

            LogUtils.i(TAG, "Saving perfetto trace to " + outFile);
            SystemProperties.set("tr_trace.dfx_trace.dump_time", sdf.format(new Date()));
            try {
                Os.rename(TEMP_DFX_LOCATION, outFile.getCanonicalPath());
                double fileSize = getFileSizeInMB(outFile.getCanonicalPath());
                SystemProperties.set("tr_trace.dfx_trace.dump_size_mb", String.format(Locale.US, "%.2f", fileSize));
                LogUtils.i(TAG, "current dfx trace size is " + fileSize + " MB");
            } catch (Exception e) {
                LogUtils.e(TAG, "Trace dump failed: " + e.getMessage());
            }
        } else {
            if (!Files.exists(Paths.get(TEMP_TRACE_LOCATION))) {
                LogUtils.e(TAG, "In-progress trace file doesn't exist, aborting trace dump.");
                return false;
            }

            LogUtils.i(TAG, "Saving perfetto trace to " + outFile);
            SystemProperties.set("tr_trace.dfx_trace.dump_time", sdf.format(new Date()));

            try {
                Os.rename(TEMP_TRACE_LOCATION, outFile.getCanonicalPath());
                double fileSize = getFileSizeInMB(outFile.getCanonicalPath());
                SystemProperties.set("tr_trace.dfx_trace.dump_size_mb", String.format(Locale.US, "%.2f", fileSize));
                LogUtils.i(TAG, "current fans trace size is " + fileSize + " MB");
            } catch (Exception e) {
                LogUtils.e(TAG, "Trace dump failed: " + e.getMessage());
            }
        }
        LogUtils.e(TAG, "" + action);
        outFile.setReadable(true, false); // (readable, ownerOnly)
        outFile.setWritable(true, false); // (readable, ownerOnly)
        return true;
    }

    public boolean isTracingOn(String action) {
        String cmd;
        if (action == TraceService.INTENT_ACTION_DFX_START_TRACING
                || action == TraceService.INTENT_ACTION_DFX_STOP_TRACING) {
            cmd = "perfetto --is_detached=" + PERFETTO_DFX_TAG;
        } else {
            cmd = "perfetto --is_detached=" + PERFETTO_TAG;
        }
        LogUtils.e(TAG, "" + action);
        try {
            Process process = TraceUtils.exec(cmd);

            // 0 represents a detached process exists with this name
            // 2 represents no detached process with this name
            // 1 (or other error code) represents an error
            int result = process.waitFor();
            if (result == 0) {
                return true;
            } else if (result == 2) {
                return false;
            } else {
                LogUtils.e(TAG, "Perfetto error: " + result);
                return false;
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "Perfetto tracingOn error:" + e.getMessage());
            return false;
        }
    }
}
