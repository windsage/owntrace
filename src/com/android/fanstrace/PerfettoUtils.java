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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import perfetto.protos.DataSourceDescriptorOuterClass.DataSourceDescriptor;
import perfetto.protos.FtraceDescriptorOuterClass.FtraceDescriptor.AtraceCategory;
import perfetto.protos.TracingServiceStateOuterClass.TracingServiceState;
import perfetto.protos.TracingServiceStateOuterClass.TracingServiceState.DataSource;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import android.os.Build;
import android.os.SystemProperties;
import android.system.Os;
/**
 * Utility functions for calling Perfetto
 */
public class PerfettoUtils implements TraceUtils.TraceEngine {
    private static final String TAG = "Fanstrace";
    public static final String NAME = "PERFETTO";

    private static final String OUTPUT_EXTENSION = "ptrace";
    private static final String TEMP_DIR = "/data/local/traces/fans/";
    private static final String TEMP_DFX_DIR = "/data/local/traces/DFX/";
    static final String TEMP_TRACE_LOCATION = "/data/local/traces/.fans_trace-in-progress.trace";
    static final String TEMP_DFX_LOCATION = "/data/local/traces/.DFX_trace-in-progress.trace";

    static final String PERFETTO_TAG = "Fanstrace";
    static final String PERFETTO_DFX_TAG = "DFXtrace";
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

    public int traceStart(Collection<String> tags,
                          int bufferSizeKb,
                          boolean apps,
                          boolean longTrace,
                          int maxLongTraceSizeMb,
                          int maxLongTraceDurationMinutes,
                          String action) {
        // Ensure the temporary trace file is cleared.
        LogUtils.i(TAG, action);
        String tempFile = TraceService.INTENT_ACTION_DFX_START_TRACING.equals(action)
                                  ? TEMP_DFX_LOCATION
                                  : TEMP_TRACE_LOCATION;
        File file = new File(tempFile);
        if (file.exists()) {
            // 理论上不应该走到这里
            long fileSize = file.length();
            LogUtils.w(TAG,
                       "Unexpected: temp file still exists (size=" + fileSize +
                               "), deleting as fallback");
            // 确保删除旧文件
            try {
                Files.deleteIfExists(Paths.get(tempFile));
                LogUtils.i(TAG, "Deleted old temp file: " + tempFile);
            } catch (Exception e) {
                LogUtils.e(TAG, "Failed to delete old temp file: " + e.getMessage());
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
                .append("producers {\n")
                .append("  producer_name: \"traced_probes\"\n")
                .append("  shm_size_kb: 32768\n")
                .append("  page_size_kb: 32\n")
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
            config.append("      ftrace_events: \"gpu_mem/gpu_mem_total\"\n");
            config.append("      ftrace_events: \"power/gpu_work_period\"\n");
            config.append("      ftrace_events: \"power/gpu_frequency\"\n");
            config.append("      ftrace_events: \"power/cpu_frequency_limits\"\n");
            // config.append("      ftrace_events: \"power/cpu_idle\"\n");
            // config.append("      ftrace_events: \"power/suspend_resume\"\n");
            // config.append("      ftrace_events: \"raw_syscalls/sys_enter\"\n");
            // config.append("      ftrace_events: \"raw_syscalls/sys_exit\"\n");
            // Temporarily used by the storage analysis at Jan 8th, 2025 start
            config.append("      ftrace_events: \"block/block_rq_insert\"\n");
            config.append("      ftrace_events: \"block/block_rq_issue\"\n");
            config.append("      ftrace_events: \"block/block_rq_complete\"\n");
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
                .append("  }\n")
                .append("} \n");

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
        if (TraceService.INTENT_ACTION_DFX_START_TRACING.equals(action)) {
            cmd = "perfetto --detach=" + PERFETTO_DFX_TAG + " -o " + TEMP_DFX_LOCATION +
                  " -c - --txt"
                  + " <<" + MARKER + "\n" + configString + "\n" + MARKER;
        } else {
            cmd = "perfetto --detach=" + PERFETTO_TAG + " -o " + TEMP_TRACE_LOCATION + " -c - --txt"
                  + " <<" + MARKER + "\n" + configString + "\n" + MARKER;
        }
        LogUtils.i(TAG, action);
        try {
            Process process = (TraceService.INTENT_ACTION_DFX_START_TRACING.equals(action))
                                      ? TraceUtils.exec(cmd, TEMP_DFX_DIR)
                                      : TraceUtils.exec(cmd, TEMP_DIR);
            // If we time out, ensure that the perfetto process is destroyed.
            if (!process.waitFor(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LogUtils.e(TAG,
                           "perfetto traceStart has timed out after " + STARTUP_TIMEOUT_MS +
                                   " ms.");
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
            LogUtils.i(TAG,
                       "No trace appears to be in progress. Stopping perfetto trace may not work.");
        }
        String cmd;
        if (TraceService.INTENT_ACTION_DFX_STOP_TRACING.equals(action)) {
            cmd = "perfetto --stop --attach=" + PERFETTO_DFX_TAG;
        } else {
            cmd = "perfetto --stop --attach=" + PERFETTO_TAG;
        }
        LogUtils.i(TAG, action);
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
            // 直接四舍五入到两位小数
            return Math.round(sizeInMB * 100.0) / 100.0;
        }
        return -1.0;
    }

    public boolean traceDump(File outFile, String action) {
        traceStop(action);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        String tempFile = TraceService.INTENT_ACTION_DFX_STOP_TRACING.equals(action)
                                  ? TEMP_DFX_LOCATION
                                  : TEMP_TRACE_LOCATION;

        // === 等待文件就绪 ===
        if (!TraceUtils.TraceStateChecker.waitForTraceFile(tempFile, 10000)) {
            LogUtils.e(TAG, "In-progress trace file doesn't exist, aborting trace dump.");
            return false;
        }

        if (TraceService.INTENT_ACTION_DFX_STOP_TRACING.equals(action)) {
            LogUtils.i(TAG, "Saving perfetto trace to " + outFile);
            SystemProperties.set("tr_trace.dfx_trace.dump_time", sdf.format(new Date()));

            try {
                Os.rename(TEMP_DFX_LOCATION, outFile.getCanonicalPath());
                double fileSize = getFileSizeInMB(outFile.getCanonicalPath());
                SystemProperties.set("tr_trace.dfx_trace.dump_size_mb",
                                     String.format(Locale.US, "%.2f", fileSize));
                LogUtils.i(TAG, "current dfx trace size is " + fileSize + " MB");
            } catch (Exception e) {
                LogUtils.e(TAG, "Trace dump failed: " + e.getMessage());
                return false;
            }
        } else {
            LogUtils.i(TAG, "Saving perfetto trace to " + outFile);
            SystemProperties.set("tr_trace.fans_trace.dump_time", sdf.format(new Date()));

            try {
                Os.rename(TEMP_TRACE_LOCATION, outFile.getCanonicalPath());
                double fileSize = getFileSizeInMB(outFile.getCanonicalPath());
                SystemProperties.set("tr_trace.fans_trace.dump_size_mb",
                                     String.format(Locale.US, "%.2f", fileSize));
                LogUtils.i(TAG, "current fans trace size is " + fileSize + " MB");
            } catch (Exception e) {
                LogUtils.e(TAG, "Trace dump failed: " + e.getMessage());
                return false;
            }
        }

        LogUtils.i(TAG, action);
        outFile.setReadable(true, false);
        outFile.setWritable(true, false);
        return true;
    }

    public boolean isTracingOn(String action) {
        if (action == null) {
            return isDetached(PERFETTO_TAG) || isDetached(PERFETTO_DFX_TAG);
        }

        String tag;
        if (TraceService.INTENT_ACTION_DFX_START_TRACING.equals(action) ||
            TraceService.INTENT_ACTION_DFX_STOP_TRACING.equals(action)) {
            tag = PERFETTO_DFX_TAG;
        } else {
            tag = PERFETTO_TAG;
        }

        return isDetached(tag);
    }

    private boolean isDetached(String tag) {
        String cmd = "perfetto --is_detached=" + tag;
        try {
            Process process = TraceUtils.exec(cmd);
            int result = process.waitFor();
            return result == 0;
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to check perfetto status for " + tag + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 原先AtraceUtils获取categories会有截取，以下方法移植自Traceur PerfettoUtils.java
     *
     */
    public static TreeMap<String, String> perfettoListCategories() {
        String cmd = "perfetto --query-raw";

        LogUtils.v(TAG, "Listing categories via perfetto --query-raw");

        try {
            TreeMap<String, String> result = new TreeMap<>();

            Process perfetto = TraceUtils.exec(cmd);

            TracingServiceState serviceState =
                    TracingServiceState.parseFrom(perfetto.getInputStream());

            if (!perfetto.waitFor(10000, TimeUnit.MILLISECONDS)) {
                LogUtils.e(TAG, "perfetto --query-raw timed out after 10 seconds");
                perfetto.destroyForcibly();
                return getFallbackCategories();
            }

            if (perfetto.exitValue() != 0) {
                LogUtils.e(TAG, "perfetto --query-raw failed with exit code: "
                          + perfetto.exitValue());
                return getFallbackCategories();
            }

            // 遍历数据源,查找linux.ftrace
            List<AtraceCategory> categories = null;
            for (DataSource dataSource : serviceState.getDataSourcesList()) {
                DataSourceDescriptor descriptor = dataSource.getDsDescriptor();
                if ("linux.ftrace".equals(descriptor.getName())){
                    categories = descriptor.getFtraceDescriptor()
                            .getAtraceCategoriesList();
                    LogUtils.d(TAG, "Found linux.ftrace data source");
                    break;
                }
            }

            if (categories != null && !categories.isEmpty()) {
                for (AtraceCategory category : categories) {
                    result.put(category.getName(), category.getDescription());
                }
                LogUtils.i(TAG, "Successfully loaded " + result.size()
                          + " categories from perfetto");
            } else {
                LogUtils.w(TAG, "No categories found in linux.ftrace data source");
                return getFallbackCategories();
            }

            return result;

        } catch (Exception e) {
            LogUtils.e(TAG, "perfettoListCategories exception: " + e.getMessage());
            return getFallbackCategories();
        }
    }

    // 备用方案:返回硬编码的完整category列表
    private static TreeMap<String, String> getFallbackCategories() {
        LogUtils.w(TAG, "Using fallback hardcoded category list");

        TreeMap<String, String> fallback = new TreeMap<>();

        // Userspace categories
        fallback.put("gfx", "Graphics");
        fallback.put("input", "Input");
        fallback.put("view", "View System");
        fallback.put("webview", "WebView");
        fallback.put("wm", "Window Manager");
        fallback.put("am", "Activity Manager");
        fallback.put("sm", "Sync Manager");
        fallback.put("audio", "Audio");
        fallback.put("video", "Video");
        fallback.put("camera", "Camera");
        fallback.put("hal", "Hardware Modules");
        fallback.put("res", "Resource Loading");
        fallback.put("dalvik", "Dalvik VM");
        fallback.put("rs", "RenderScript");
        fallback.put("bionic", "Bionic C Library");
        fallback.put("power", "Power Management");
        fallback.put("pm", "Package Manager");
        fallback.put("ss", "System Server");
        fallback.put("database", "Database");
        fallback.put("network", "Network");
        fallback.put("adb", "ADB");
        fallback.put("vibrator", "Vibrator");
        fallback.put("aidl", "AIDL calls");
        fallback.put("nnapi", "NNAPI");
        fallback.put("rro", "Runtime Resource Overlay");

        fallback.put("sched", "CPU Scheduling");
        fallback.put("irq", "IRQ Events");
        fallback.put("i2c", "I2C Events");
        fallback.put("freq", "CPU Frequency");
        fallback.put("idle", "CPU Idle");
        fallback.put("disk", "Disk I/O");
        fallback.put("sync", "Synchronization");
        fallback.put("workq", "Kernel Workqueues");
        fallback.put("memreclaim", "Kernel Memory Reclaim");
        fallback.put("binder_driver", "Binder Kernel driver");
        fallback.put("binder_lock", "Binder global lock trace");
        fallback.put("memory", "Memory");

        return fallback;
    }
}
