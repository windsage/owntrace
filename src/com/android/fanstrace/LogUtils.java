package com.android.fanstrace;

import android.os.SystemProperties;
import android.util.Log;

public class LogUtils {
    // 控制日志输出的开关
    private static boolean isLogEnabled =
            SystemProperties.getBoolean("persist.sys.trace.log", false);

    // 设置日志开关的方法
    public static void setLogEnabled(boolean enabled) {
        isLogEnabled = enabled;
    }

    // Verbose日志
    public static void v(String tag, String message) {
        if (isLogEnabled) {
            Log.v(tag, message);
        }
    }

    // Info日志
    public static void i(String tag, String message) {
        Log.i(tag, message);
    }

    // Warn日志
    public static void w(String tag, String message) {
        if (isLogEnabled) {
            Log.w(tag, message);
        }
    }

    // Error日志
    public static void e(String tag, String message) {
        if (isLogEnabled) {
            Log.e(tag, message);
        }
    }
}
