package com.android.fanstrace;

import android.os.SystemProperties;
import android.util.Log;

public class LogUtils {

    /**
     * 日志级别枚举
     */
    public enum LogLevel {
        VERBOSE, DEBUG, INFO, WARN, ERROR, NONE
    }

    /**
     * 编译期默认日志级别
     */
    private static final LogLevel defaultLogLevel = LogLevel.INFO;

    /**
     * SystemProperties 的 key，用来运行时控制日志级别
     * 0-VERBOSE,1-DEBUG,2-INFO,3-WARN,4-ERROR,5-NONE
     */
    private static final String PROP_LOG_LEVEL = "tr_trace.dfx_trace.log.level";

    /**
     * 获取当前日志级别, 运行时读取 SystemProperties，如果没设置则用默认值
     */
    private static LogLevel getCurrentLogLevel() {
        int levelIndex = SystemProperties.getInt(PROP_LOG_LEVEL, defaultLogLevel.ordinal());
        if (levelIndex < 0 || levelIndex >= LogLevel.values().length) {
            return defaultLogLevel;
        }
        return LogLevel.values()[levelIndex];
    }

    // Verbose
    public static void v(String tag, String message) {
        if (shouldLog(LogLevel.VERBOSE)) {
            Log.v(tag, message);
        }
    }

    // Debug
    public static void d(String tag, String message) {
        if (shouldLog(LogLevel.DEBUG)) {
            Log.d(tag, message);
        }
    }

    // Info
    public static void i(String tag, String message) {
        if (shouldLog(LogLevel.INFO)) {
            Log.i(tag, message);
        }
    }

    // Warn
    public static void w(String tag, String message) {
        if (shouldLog(LogLevel.WARN)) {
            Log.w(tag, message);
        }
    }

    // Error
    public static void e(String tag, String message) {
        if (shouldLog(LogLevel.ERROR)) {
            Log.e(tag, message);
        }
    }

    /**
     * 判断是否需要输出该级别日志
     */
    private static boolean shouldLog(LogLevel logLevel) {
        return logLevel.ordinal() >= getCurrentLogLevel().ordinal();
    }
}
