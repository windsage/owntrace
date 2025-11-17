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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
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

        return RUNTIME.exec(cmdarray, envp);
    }

    public static String getOutputFilename(String action) {
        String format = "yyyyMMddHHmmss";
        String now = new SimpleDateFormat(format, Locale.US).format(new Date());
        return String.format("%s.%s.%s", action.split("_")[1], now,
                mTraceEngine.getOutputExtension());
    }

    public static File getOutputFile(String filename, boolean fansStop, String action) {
        if (TraceService.INTENT_ACTION_DFX_STOP_TRACING.equals(action)) {
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
        // 过滤掉临时trace文件和子目录
        List<File> validFiles = new ArrayList<>();
        for (File file : files) {
            // 跳过目录
            if (file.isDirectory()) {
                continue;
            }

            // 跳过临时trace文件(正在运行中的trace)
            String name = file.getName();
            if (name.equals(".fans_trace-in-progress.trace") ||
                name.equals(".DFX_trace-in-progress.trace")) {
                LogUtils.i(TAG, "Skipping active trace file: " + name);
                continue;
            }

            validFiles.add(file);
        }
        File[] filesToCheck = validFiles.toArray(new File[0]);
        // Sort with newest files first
        Arrays.sort(filesToCheck, new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                return Long.compare(rhs.lastModified(), lhs.lastModified());
            }
        });

        // Keep at least minCount files
        boolean deleted = false;
        for (int i = minCount; i < filesToCheck.length; i++) {
            final File file = filesToCheck[i];

            // Keep files newer than minAgeMs
            final long age = System.currentTimeMillis() - file.lastModified();
            if (age > minAgeMs) {
                if (file.delete()) {
                    deleted = true;
                }
            }
        }
        return deleted;
    }

    /**
     * 检查并修复trace状态不一致问题
     */
    public static class TraceStateChecker {
        private static final String TAG = "TraceStateChecker";

        /**
         * 检查是否应该启动新的trace
         *
         * Perfetto行为:
         * - 运行中: 文件大小=0, 时间戳=创建时间(不更新)
         * - 因此只能用 perfetto进程状态 + 文件存在性 判断
         */
        public static boolean shouldStartTrace(String action) {
            String tempFile = TraceService.INTENT_ACTION_DFX_START_TRACING.equals(action)
                    ? PerfettoUtils.TEMP_DFX_LOCATION : PerfettoUtils.TEMP_TRACE_LOCATION;
            String traceName = action.contains("DFX") ? "DFX" : "FANS";

            boolean perfettoRunning = mTraceEngine.isTracingOn(action);
            boolean fileExists = new File(tempFile).exists();

            LogUtils.i(TAG, "Trace state check [" + traceName + "]: " +
                    "perfetto=" + perfettoRunning + ", file=" + fileExists);

            // === 场景1: 状态一致,正常运行中 ===
            if (perfettoRunning && fileExists) {
                LogUtils.i(TAG, "Trace is running normally, skip start");
                return false;  // 不需要重新启动
            }

            // === 场景2: 状态一致,没有在运行 ===
            if (!perfettoRunning && !fileExists) {
                LogUtils.i(TAG, "No trace running, should start new trace");
                return true;  // 应该启动新trace
            }

            // === 场景3: 状态不一致 - perfetto运行但文件缺失 ===
            if (perfettoRunning && !fileExists) {
                LogUtils.w(TAG, "INCONSISTENT: perfetto running but file missing!");
                LogUtils.w(TAG, "File may have been deleted by external process");
                LogUtils.w(TAG, "Attempting recovery...");

                return recoverAndRestart(action, tempFile, traceName);
            }

            // === 场景4: 状态不一致 - perfetto停止但文件残留 ===
            if (!perfettoRunning && fileExists) {
                File file = new File(tempFile);
                long fileSize = file.length();
                long fileAge = System.currentTimeMillis() - file.lastModified();

                LogUtils.w(TAG, "INCONSISTENT: orphan file detected");
                LogUtils.w(TAG, "File: size=" + fileSize + " bytes, age=" + fileAge + "ms");

                // 分析孤儿文件类型
                if (fileSize == 0) {
                    LogUtils.w(TAG, "Empty orphan file (perfetto crashed before stop)");
                } else {
                    LogUtils.w(TAG, "Non-empty orphan file (dump/rename failed)");
                }

                // 清理孤儿文件
                try {
                    Files.deleteIfExists(Paths.get(tempFile));
                    LogUtils.i(TAG, "Deleted orphan file: " + tempFile);
                } catch (Exception e) {
                    LogUtils.e(TAG, "Failed to delete orphan file: " + e.getMessage());
                }

                LogUtils.i(TAG, "Should start new trace after cleanup");
                return true;
            }

            // 默认允许启动
            return true;
        }


        /**
         * 检查是否需要执行Stop流程
         * <p>
         * 关键判断依据:
         * - 运行中文件: size=0
         * - 已stop文件: size>0
         */
        public static boolean shouldStopTrace(String action) {
            String tempFile = TraceService.INTENT_ACTION_DFX_START_TRACING.equals(action)
                    ? PerfettoUtils.TEMP_DFX_LOCATION : PerfettoUtils.TEMP_TRACE_LOCATION;
            String traceName = action.contains("DFX") ? "DFX" : "FANS";

            boolean perfettoRunning = mTraceEngine.isTracingOn(action);
            boolean fileExists = new File(tempFile).exists();

            LogUtils.i(TAG, "Stop trace check [" + traceName + "]: " +
                    "perfetto=" + perfettoRunning + ", file=" + fileExists);

            // === 场景1: 完全没有trace ===
            if (!perfettoRunning && !fileExists) {
                LogUtils.i(TAG, "No trace to stop, skipping entirely");
                return false;
            }

            // === 场景2: 正常情况 - perfetto运行且文件存在 ===
            if (perfettoRunning && fileExists) {
                File file = new File(tempFile);
                long fileSize = file.length();

                if (fileSize == 0) {
                    LogUtils.i(TAG, "Normal stop: perfetto running, empty file (expected)");
                } else {
                    // 这种情况理论上不应该出现
                    LogUtils.w(TAG, "ABNORMAL: perfetto running but file has data (" +
                            fileSize + " bytes)");
                    LogUtils.w(TAG, "File may have been written by previous failed stop");
                }

                return true;  // 执行stop流程
            }

            // === 场景3: 异常情况 - perfetto运行但文件缺失 ===
            if (perfettoRunning && !fileExists) {
                LogUtils.w(TAG, "ABNORMAL: perfetto running but file missing!");
                LogUtils.w(TAG, "File deleted by external process, trace data will be lost");
                LogUtils.w(TAG, "Will stop perfetto to prevent resource leak");

                return true;  // 必须stop perfetto
            }

            // === 场景4: perfetto停止但文件存在 ===
            if (!perfettoRunning && fileExists) {
                File file = new File(tempFile);
                long fileSize = file.length();
                long fileAge = System.currentTimeMillis() - file.lastModified();

                LogUtils.w(TAG, "ABNORMAL: orphan file exists");
                LogUtils.w(TAG, "File: size=" + fileSize + " bytes, age=" + fileAge + "ms");

                // === 根据文件大小区分场景 ===

                if (fileSize == 0) {
                    // 场景4a: 空文件 - perfetto异常退出,没有执行stop
                    LogUtils.w(TAG, "Empty orphan (perfetto crashed), deleting");

                    try {
                        Files.deleteIfExists(Paths.get(tempFile));
                        LogUtils.i(TAG, "Empty orphan file deleted");
                    } catch (Exception e) {
                        LogUtils.e(TAG, "Failed to delete orphan: " + e.getMessage());
                    }

                    return false;  // 已清理,不需要执行stop流程

                } else {
                    // 场景4b: 有数据 - perfetto已经stop并写入,但rename失败
                    LogUtils.w(TAG, "Non-empty orphan (previous dump failed)");

                    // 判断文件新鲜度
                    if (fileAge < 3000) {  // 3秒内
                        LogUtils.i(TAG, "Fresh file, likely just stopped, proceed with dump");
                        return true;  // 执行stop流程(实际是执行dump)

                    } else {
                        // 旧文件,尝试保存
                        LogUtils.w(TAG, "Old orphan file, attempting to save");

                        try {
                            // 生成orphan文件名(使用文件的修改时间)
                            String timestamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                                    .format(new Date(file.lastModified()));
                            String filename = traceName + ".orphan." + timestamp + ".ptrace";

                            // 确定目标目录
                            String targetDir = traceName.equals("DFX")
                                    ? TraceUtils.DFX_TRACE_DIRECTORY
                                    : TraceUtils.FANS_TRACE_DIRECTORY;
                            File targetFile = new File(targetDir, filename);

                            // 移动文件
                            Files.move(Paths.get(tempFile),
                                    Paths.get(targetFile.getAbsolutePath()),
                                    StandardCopyOption.REPLACE_EXISTING);

                            LogUtils.i(TAG, "Saved orphan file as: " + filename +
                                    " (size=" + fileSize + " bytes)");

                        } catch (Exception e) {
                            LogUtils.e(TAG, "Failed to save orphan file: " + e.getMessage());

                            // 保存失败就删除,避免一直残留
                            try {
                                Files.deleteIfExists(Paths.get(tempFile));
                                LogUtils.w(TAG, "Deleted orphan file after save failure");
                            } catch (Exception ex) {
                                LogUtils.e(TAG, "Failed to delete orphan: " + ex.getMessage());
                            }
                        }

                        return false;  // 已处理,不需要执行stop流程
                    }
                }
            }

            // 默认执行stop
            LogUtils.w(TAG, "Unexpected state, executing stop as fallback");
            return true;
        }

        /**
         * 恢复并重启trace
         */
        private static boolean recoverAndRestart(String action, String tempFile, String traceName) {
            LogUtils.w(TAG, "Starting recovery for " + traceName + " trace");

            // 1. 强制停止perfetto session
            String tag = action.contains("DFX")
                    ? PerfettoUtils.PERFETTO_DFX_TAG : PerfettoUtils.PERFETTO_TAG;
            forceStopPerfetto(tag);

            // 2. 清理可能残留的文件
            try {
                Files.deleteIfExists(Paths.get(tempFile));
                LogUtils.i(TAG, "Cleaned up temp file: " + tempFile);
            } catch (Exception e) {
                LogUtils.e(TAG, "Failed to cleanup temp file: " + e.getMessage());
            }

            // 3. 等待perfetto完全清理
            try {
                Thread.sleep(500);
            } catch (Exception e) {
                LogUtils.e(TAG, "Failed to wait for cleaning temp file: " + e.getMessage());
            }

            // 4. 验证恢复成功
            boolean stillRunning = mTraceEngine.isTracingOn(action);
            if (stillRunning) {
                LogUtils.e(TAG, "Recovery failed: perfetto still running after force stop!");
                // 再尝试一次
                forceStopPerfetto(tag);
                try {
                    Thread.sleep(500);
                } catch (Exception e) {
                    LogUtils.e(TAG, "Failed to stop perfetto again: " + e.getMessage());
                }
            }

            LogUtils.i(TAG, "Recovery complete, should start new trace");
            return true;
        }

        /**
         * 强制停止perfetto session
         */
        private static void forceStopPerfetto(String tag) {
            String cmd = "perfetto --stop --attach=" + tag;
            LogUtils.w(TAG, "Force stopping perfetto: " + cmd);

            try {
                Process process = exec(cmd);
                if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) {
                    LogUtils.e(TAG, "Force stop timeout, destroying process");
                    process.destroyForcibly();
                }

                int exitCode = process.exitValue();
                LogUtils.i(TAG, "Force stop exit code: " + exitCode);
            } catch (Exception e) {
                LogUtils.e(TAG, "Force stop failed: " + e.getMessage());
            }
        }

        /**
         * 等待trace文件就绪(stop后文件从0增长到最终大小)
         * <p>
         * 工作流程:
         * 1. perfetto --stop触发写入
         * 2. 文件从0字节开始增长
         * 3. 等待文件大小稳定
         */
        public static boolean waitForTraceFile(String tempFile, long timeoutMs) {
            LogUtils.i(TAG, "Waiting for trace file to be written: " + tempFile);

            long startTime = System.currentTimeMillis();
            long lastSize = -1;
            int stableCount = 0;
            boolean fileAppeared = false;

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                File file = new File(tempFile);

                if (!file.exists()) {
                    try {
                        Thread.sleep(100);
                    } catch (Exception e) {
                        LogUtils.e(TAG, "Fail to wait for trace file: " + e.getMessage());
                    }
                    continue;
                }

                if (!fileAppeared) {
                    LogUtils.i(TAG, "Trace file appeared after " +
                            (System.currentTimeMillis() - startTime) + "ms");
                    fileAppeared = true;
                }

                long currentSize = file.length();

                LogUtils.v(TAG, "File size: " + currentSize + " bytes");

                // 等待文件大小稳定
                if (currentSize == lastSize) {
                    stableCount++;

                    // 文件大小稳定在0,说明perfetto写入失败
                    if (currentSize == 0 && stableCount >= 10) {  // 稳定1秒
                        LogUtils.e(TAG, "File size remains 0 after stop! Write failed!");
                        listDirectory(new File(tempFile).getParent());
                        return false;
                    }

                    // 文件大小稳定且>0,写入完成
                    if (currentSize > 0 && stableCount >= 5) {  // 稳定500ms
                        LogUtils.i(TAG, "Trace file ready: size=" + currentSize +
                                " bytes, elapsed=" +
                                (System.currentTimeMillis() - startTime) + "ms");
                        return true;
                    }
                } else {
                    stableCount = 0;
                    lastSize = currentSize;
                }

                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                }
            }

            // 超时
            File file = new File(tempFile);
            if (file.exists()) {
                LogUtils.e(TAG, "Timeout: file unstable, final size=" + file.length());
            } else {
                LogUtils.e(TAG, "Timeout: file never appeared after stop!");
            }

            listDirectory(new File(tempFile).getParent());
            return false;
        }

        /**
         * 列出目录内容
         */
        private static void listDirectory(String dirPath) {
            try {
                File dir = new File(dirPath);
                File[] files = dir.listFiles();

                LogUtils.i(TAG, "Directory listing: " + dirPath);
                if (files != null && files.length > 0) {
                    for (File f : files) {
                        LogUtils.i(TAG, "  - " + f.getName() +
                                " (" + f.length() + " bytes, " +
                                "modified=" + (System.currentTimeMillis() - f.lastModified()) + "ms ago)");
                    }
                } else {
                    LogUtils.i(TAG, "  (empty or no permission)");
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Failed to list directory: " + e.getMessage());
            }
        }
    }
}
