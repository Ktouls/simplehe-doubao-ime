package com.jdh.simplehe;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 简单鹤日志 2.0。
 *
 * <p>规则：
 * <ul>
 *   <li>只写一个文件，常驻 BufferedWriter；</li>
 *   <li>累积 {@link #FLUSH_BYTES} 字节或 {@link #FLUSH_MS} 毫秒才落盘；</li>
 *   <li>只有 ERR / INIT 走 XposedBridge.log，打字路径完全不碰 logd；</li>
 *   <li>重载不清空旧日志，超过上限才截断。</li>
 * </ul>
 */
public final class HeLog {
    private static final String TAG = "JDH-He";
    private static final String[] CANDIDATES = {
            "/sdcard/Download/jdh_he.log",
            "/sdcard/Android/data/com.bytedance.android.doubaoime/files/jdh_he.log",
            "/data/user/0/com.bytedance.android.doubaoime/files/jdh_he.log"
    };

    private static final int FLUSH_BYTES = 4096;
    private static final long FLUSH_MS = 1000L;
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());

    private static BufferedWriter writer;
    private static String primary = CANDIDATES[0];
    private static int pendingBytes;
    private static long lastFlush;

    private static String lastKey = "";
    private static long lastKeyAt;

    private HeLog() {}

    public static void initAndReport(String tag) {
        StringBuilder report = new StringBuilder();
        for (String path : CANDIDATES) {
            try {
                File file = new File(path);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (!file.exists() && !file.createNewFile()) continue;
                if (file.canWrite()) {
                    primary = path;
                    report.append("可写 ").append(path).append("; ");
                    break;
                }
            } catch (Throwable t) {
                report.append("跳过 ").append(path).append('(')
                        .append(t.getClass().getSimpleName()).append("); ");
            }
        }
        f(tag, "日志路径探测: " + report + " 通道=" + primary);
    }

    public static void f(String tag, String message) {
        boolean echo = "ERR".equals(tag) || "INIT".equals(tag);
        if (!echo && !isDebugTag(tag)) return;

        String line = FORMAT.format(new Date()) + " " + tag + ": " + safe(message) + "\n";
        synchronized (LOCK) {
            if (writer == null) openLocked();
            if (writer != null) {
                try {
                    writer.write(line);
                    pendingBytes += line.length();
                    long now = System.currentTimeMillis();
                    if (echo || pendingBytes >= FLUSH_BYTES || now - lastFlush >= FLUSH_MS) {
                        writer.flush();
                        pendingBytes = 0;
                        lastFlush = now;
                    }
                } catch (Throwable ignored) {
                    try { writer.close(); } catch (Throwable ignored2) {}
                    writer = null;
                }
            }
        }
        if (echo) {
            try { XposedBridge.log("[" + TAG + "] " + tag + ": " + message); } catch (Throwable ignored) {}
        }
    }

    /**
     * 同一 key 80ms 内只记一条。打字路径专用，避免候选快照连续触发把日志写爆。
     */
    public static void throttled(String key, String message) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (key.equals(lastKey) && now - lastKeyAt < 80L) return;
            lastKey = key;
            lastKeyAt = now;
        }
        f(key, message);
    }

    private static boolean isDebugTag(String tag) {
        return "EXACT".equals(tag) || "PREFIX".equals(tag) || "CLICK".equals(tag)
                || "CLICKUP".equals(tag) || "CLICKFIX".equals(tag) || "PAINT".equals(tag)
                || "SPACEPUT".equals(tag) || "SKIPSHORT".equals(tag) || "LONGSKIP".equals(tag)
                || "WARN".equals(tag);
    }

    private static void openLocked() {
        try {
            File file = new File(primary);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (file.exists() && file.length() > MAX_BYTES) {
                try (FileWriter truncate = new FileWriter(file, false)) {
                    truncate.write("");
                }
            }
            writer = new BufferedWriter(new FileWriter(file, true));
            pendingBytes = 0;
            lastFlush = System.currentTimeMillis();
        } catch (Throwable ignored) {
            writer = null;
        }
    }

    public static String primaryPath() {
        return primary;
    }

    private static String safe(String message) {
        if (message == null) return "null";
        String value = message.replace('\n', ' ').replace('\r', ' ');
        return value.length() > 400 ? value.substring(0, 400) + "..." : value;
    }
}