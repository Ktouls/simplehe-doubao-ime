package com.jdh.simplehe;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

/**
 * 简单鹤日志 2.6（异步后台写盘版）。
 *
 * <p>规则：
 * <ul>
 *   <li>打字热路径只做内存入队，不在输入回调线程执行磁盘写入或 flush；</li>
 *   <li>单后台守护线程批量写盘并定时 flush；</li>
 *   <li>{@link SimpleDateFormat} 仅在后台写线程使用，消除线程安全与分配开销；</li>
 *   <li>只有 ERR / INIT 走 XposedBridge.log，打字路径不碰 logd；</li>
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
    private static final int QUEUE_CAPACITY = 512;

    private static final class Entry {
        final long timeMs;
        final String tag;
        final String message;
        final boolean echo;

        Entry(long timeMs, String tag, String message, boolean echo) {
            this.timeMs = timeMs;
            this.tag = tag;
            this.message = message;
            this.echo = echo;
        }
    }

    private static final ArrayBlockingQueue<Entry> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final Object THROTTLE_LOCK = new Object();

    private static volatile String primary = CANDIDATES[0];
    private static volatile boolean workerStarted;

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
        ensureWorker();
        f(tag, "日志路径探测: " + report + " 通道=" + primary);
    }

    public static void f(String tag, String message) {
        boolean echo = "ERR".equals(tag) || "INIT".equals(tag);
        if (!echo && !isDebugTag(tag)) return;

        ensureWorker();
        Entry item = new Entry(System.currentTimeMillis(), tag, message, echo);
        if (!QUEUE.offer(item)) {
            // 队列满时丢弃最旧的非关键日志，绝不阻塞打字线程
            QUEUE.poll();
            QUEUE.offer(item);
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
        synchronized (THROTTLE_LOCK) {
            if (key.equals(lastKey) && now - lastKeyAt < 80L) return;
            lastKey = key;
            lastKeyAt = now;
        }
        f(key, message);
    }

    private static void ensureWorker() {
        if (workerStarted) return;
        synchronized (HeLog.class) {
            if (workerStarted) return;
            workerStarted = true;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWriterLoop();
                }
            }, "jdh-log");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private static void runWriterLoop() {
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
        Date date = new Date();
        BufferedWriter writer = null;
        int pendingBytes = 0;
        long lastFlush = System.currentTimeMillis();

        while (true) {
            try {
                Entry first = QUEUE.poll(FLUSH_MS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    if (writer == null) {
                        writer = openWriter();
                        pendingBytes = 0;
                        lastFlush = System.currentTimeMillis();
                    }
                    if (writer != null) {
                        boolean forceFlush = writeOne(writer, format, date, first);
                        pendingBytes += 64 + (first.message != null ? first.message.length() : 4);

                        Entry next;
                        while ((next = QUEUE.poll()) != null) {
                            if (writeOne(writer, format, date, next)) forceFlush = true;
                            pendingBytes += 64 + (next.message != null ? next.message.length() : 4);
                        }

                        long now = System.currentTimeMillis();
                        if (forceFlush || pendingBytes >= FLUSH_BYTES || now - lastFlush >= FLUSH_MS) {
                            writer.flush();
                            pendingBytes = 0;
                            lastFlush = now;
                        }
                    }
                } else if (writer != null && pendingBytes > 0) {
                    writer.flush();
                    pendingBytes = 0;
                    lastFlush = System.currentTimeMillis();
                }
            } catch (Throwable ignored) {
                if (writer != null) {
                    try { writer.close(); } catch (Throwable ignored2) {}
                    writer = null;
                }
            }
        }
    }

    private static boolean writeOne(BufferedWriter writer, SimpleDateFormat format, Date date, Entry entry)
            throws Exception {
        date.setTime(entry.timeMs);
        writer.write(format.format(date));
        writer.write(' ');
        writer.write(entry.tag);
        writer.write(": ");
        writer.write(safe(entry.message));
        writer.write('\n');
        return entry.echo;
    }

    private static boolean isDebugTag(String tag) {
        return "EXACT".equals(tag) || "PREFIX".equals(tag) || "CLICK".equals(tag)
                || "CLICKUP".equals(tag) || "CLICKFIX".equals(tag) || "COMMITFIX".equals(tag)
                || "PAINT".equals(tag) || "SPACEPUT".equals(tag) || "SKIPSHORT".equals(tag)
                || "LONGSKIP".equals(tag) || "PARTIAL".equals(tag) || "DEMOTE".equals(tag) || "WARN".equals(tag);
    }

    private static BufferedWriter openWriter() {
        try {
            File file = new File(primary);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (file.exists() && file.length() > MAX_BYTES) {
                try (FileWriter truncate = new FileWriter(file, false)) {
                    truncate.write("");
                }
            }
            return new BufferedWriter(new FileWriter(file, true));
        } catch (Throwable ignored) {
            return null;
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