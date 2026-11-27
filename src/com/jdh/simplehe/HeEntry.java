package com.jdh.simplehe;

import android.view.ViewConfiguration;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 简单鹤音形挂载 3.7。
 *
 * <p>策略：<b>该码下简单鹤的全部候选排在最前，豆包自己的候选原样接在后面</b>，
 * 一个都不删。空格按简单鹤首选上屏。
 *
 * <p>点击对齐：{@code onCandidateBarItemButtonUp} 是 native 方法，
 * 上屏由 libkeyboard.so 按它自己的候选 ID 完成，改 Java 侧的字符串不参与取词。
 * 所以不走"改标签"，而是在上屏边界把结果换成用户按下时看到的那一格：
 *
 * <ul>
 *   <li>候选快照时记录整屏 {@code shown[]}（我们画的）及豆包原生首选；</li>
 *   <li>触摸抬起时读第 2 个参数（实测是被点格子的索引），
 *       目标词 = {@code shown[index]}，同时记下豆包准备上屏的那个词；</li>
 *   <li>在 {@code DoCommit} 和 {@code InputConnectionWrapper.commitText}
 *       两个边界上做替换，前者管 Java 记账，后者管真正落到输入框的文本。</li>
 * </ul>
 *
 * <p>2.7 优化：扁平无对象词库（int[] 编码索引 + 单一 byte[] 池）、精确下标对齐辅助串、
 * 精确 Hook 5 参 DoCommit、跨码清理残留状态、仅放行主进程。
 */
public final class HeEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.bytedance.android.doubaoime";
    private static final String KEYBOARD_JNI = TARGET + ".KeyboardJni";
    private static final String IC_WRAPPER = "com.bytedance.android.input.editor.InputConnectionWrapper";
    private static final String MODULE_PKG = "com.jdh.simplehe";
    private static final String DICT_ASSET = "assets/jdh_dict_multi.txt";

    private static final String CONFIG_FILE = "/sdcard/Download/jdh_config";
    private static final String SWITCH_FILE = "/sdcard/Download/jdh_enable";
    private static final String MODE_FILE = "/sdcard/Download/jdh_mode";

    private static final int ARG_WORDS = 2;
    private static final int ARG_AUX_1 = 3;
    private static final int ARG_AUX_2 = 4;
    private static final int ARG_CODE = 6;
    private static final int ARG_IDS = 8;
    private static final int ARG_TYPES = 10;
    private static final int ARG_OFFSETS = 18;
    private static final int ARG_MODE = 27;

    private static final int MODE_DOUBLE_SPELL = 3;
    private static final int FN_KEY_SPACE = 0x03;
    private static final int FN_KEY_STOP = 0x21;

    // ---- 配置 ----
    private static volatile boolean cfgEnabled = true;
    private static volatile boolean cfgMergeAll = true;
    private static volatile boolean cfgMergePrefix = false;
    private static volatile boolean cfgClickFix = true;
    private static volatile boolean cfgSpaceOverride = true;
    private static volatile boolean cfgDebug = false;
    private static volatile int cfgMinExactLen = 1;
    private static volatile int cfgMaxTotal = 24;
    private static volatile long cfgSpaceTtlMs = 1500L;
    private static volatile long cfgClickTtlMs = 2500L;

    // ---- 扁平词库（仅 3 个基本类型数组，避免 50 万常驻堆对象拖慢 GC） ----
    private static final class FlatDict {
        final int[] keys;     // 升序排列的编码打包值（每字母 5 bit，支持 1~6 码）
        final long[] meta;    // 高 44 bit: pool 起始偏移；低 20 bit: 字节长度
        final byte[] pool;    // 连续 UTF-8 词串池（词间单空格分隔）
        final int maxCodeLen;

        FlatDict(int[] keys, long[] meta, byte[] pool, int maxCodeLen) {
            this.keys = keys;
            this.meta = meta;
            this.pool = pool;
            this.maxCodeLen = maxCodeLen;
        }

        int size() {
            return keys.length;
        }

        String[] get(String code) {
            int key = packCode(code);
            if (key < 0) return null;
            int idx = Arrays.binarySearch(keys, key);
            if (idx < 0) return null;
            long m = meta[idx];
            int start = (int) (m >>> 20);
            int len = (int) (m & 0xFFFFFL);
            if (len <= 0) return null;
            String raw = new String(pool, start, len, StandardCharsets.UTF_8);
            return splitBySpace(raw);
        }

        String[] getPrefix(String code) {
            for (int len = code.length() - 1; len >= 1; len--) {
                String[] value = get(code.substring(0, len));
                if (value != null) return value;
            }
            return null;
        }
    }

    private static volatile FlatDict dict;

    /** 一帧画面：原子保存当前编码、简单鹤合并列表及豆包原生首选。 */
    private static final class Paint {
        final String code;
        final String[] shown;
        final int own;
        /** 简单鹤候选在 shown 里的起始下标（四码单字让位时为 1）。 */
        final int ownStart;
        final String engineFirst;

        Paint(String code, String[] shown, int own, int ownStart, String engineFirst) {
            this.code = code;
            this.shown = shown;
            this.own = own;
            this.ownStart = ownStart;
            this.engineFirst = engineFirst;
        }

        String first() {
            return shown[0];
        }
    }

    private static volatile Paint lastPaint;

    /** 本次点击待对齐：豆包会给出的词 → 用户看到的那一格。 */
    private static volatile String clickOrigin = "";
    private static volatile String clickTarget = "";
    private static volatile long clickAt;
    private static volatile boolean spaceArmed;
    private static volatile long spaceAt;
    private static volatile int lastMode = -1;

    // ---- 点击判定打点（排查"单击被当成长按"） ----
    /** 按下时刻（onCandidateBarItemButtonDown 捕获），0 表示没捕获到 DOWN。 */
    private static volatile long lastDownAt;
    /** 系统长按阈值，来自 ViewConfiguration，受过无障碍"按住延迟"等设置影响。 */
    private static volatile int longPressTimeout = -1;
    /** 本帧候选的 ID / 类型数组（与豆包实际收到的那份一致），用于日志。 */
    private static volatile int[] lastIds;
    private static volatile int[] lastTypes;

    /** 分段选词态：点击候选后若未上屏且剩余编码缩短，说明豆包正处于长句/分段选词，后续尾段交还豆包。 */
    private static volatile int lastSnapshotCodeLen;
    private static volatile boolean pendingPartialClick;
    private static volatile int clickedCodeLen;
    private static volatile boolean inPartialSelection;

    private static final Set<String> longSamples = new HashSet<>();
    private static Field asrFlag;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !TARGET.equals(lp.packageName)) return;
        // 仅在豆包输入法主进程挂载，避免子进程重复加载词库
        if (lp.processName != null && !TARGET.equals(lp.processName)) return;

        HeLog.initAndReport("INIT");
        loadConfig();
        HeLog.f("INIT", "简单鹤音形挂载 3.7 | 进程=" + lp.processName
                + " 开关=" + (cfgEnabled ? "ON" : "OFF")
                + " 全塞=" + (cfgMergeAll ? "ON" : "OFF")
                + " 上限=" + cfgMaxTotal
                + " 点击对齐=" + (cfgClickFix ? "ON" : "OFF")
                + " 空格替换=" + (cfgSpaceOverride ? "ON" : "OFF"));

        try {
            longPressTimeout = ViewConfiguration.getLongPressTimeout();
            HeLog.f("INIT", "系统长按阈值=" + longPressTimeout + "ms（无障碍「按住延迟」等设置会影响它）");
        } catch (Throwable t) {
            HeLog.f("ERR", "读取长按阈值失败: " + t);
        }

        if (!cfgEnabled) {
            HeLog.f("INIT", "总开关关闭，未安装任何 Hook");
            return;
        }

        startDictLoader();

        try {
            Class<?> keyboard = Class.forName(KEYBOARD_JNI, false, lp.classLoader);
            hookCandidateBar(keyboard);
            hookSpaceKey(keyboard);
            hookDoCommit(keyboard);
            hookCandidateTouch(keyboard);
            initAsrFlag(keyboard);
            HeLog.f("INIT", "Hook 安装完成");
        } catch (Throwable t) {
            HeLog.f("ERR", "定位 KeyboardJni 失败: " + t);
        }

        hookCommitText(lp.classLoader);
    }

    // =====================================================================
    // 配置
    // =====================================================================

    private static void loadConfig() {
        Map<String, String> conf = new HashMap<>();
        for (String line : readAllLines(CONFIG_FILE)) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            conf.put(line.substring(0, eq).trim().toLowerCase(),
                    line.substring(eq + 1).trim().toLowerCase());
        }

        String legacySwitch = firstLine(SWITCH_FILE);
        if (legacySwitch != null && "0".equals(legacySwitch)) cfgEnabled = false;
        if ("0".equals(conf.get("enable"))) cfgEnabled = false;
        if ("1".equals(conf.get("enable"))) cfgEnabled = true;

        String legacyMode = firstLine(MODE_FILE);
        if (legacyMode != null) {
            HeLog.f("INIT", "检测到 jdh_mode=" + legacyMode + "（以快照输入模式为准，此处仅记录）");
        }

        cfgMergeAll = bool(conf.get("merge_all"), cfgMergeAll);
        cfgMergePrefix = bool(conf.get("merge_prefix"), cfgMergePrefix);
        cfgClickFix = bool(conf.get("click_fix"), cfgClickFix);
        cfgSpaceOverride = bool(conf.get("space_override"), cfgSpaceOverride);
        cfgDebug = bool(conf.get("debug"), cfgDebug);
        cfgMinExactLen = clampInt(conf.get("min_exact_len"), cfgMinExactLen, 1, 4);
        cfgMaxTotal = clampInt(conf.get("max_total"), cfgMaxTotal, 9, 60);
        cfgSpaceTtlMs = clampLong(conf.get("space_ttl_ms"), cfgSpaceTtlMs, 200, 5000);
        cfgClickTtlMs = clampLong(conf.get("click_ttl_ms"), cfgClickTtlMs, 200, 5000);
    }

    private static boolean bool(String value, boolean fallback) {
        if (value == null) return fallback;
        return "1".equals(value) || "true".equals(value) || "on".equals(value);
    }

    private static int clampInt(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long clampLong(String value, long fallback, long min, long max) {
        if (value == null) return fallback;
        try {
            long parsed = Long.parseLong(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    // =====================================================================
    // 扁平词库加载
    // =====================================================================

    private static void startDictLoader() {
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    InputStream input = openDict();
                    if (input == null) {
                        HeLog.f("ERR", "词库打开失败，全程放行");
                        return;
                    }
                    FlatDict loaded = parseFlat(input);
                    dict = loaded;
                    long cost = System.currentTimeMillis() - t0;
                    HeLog.f("INIT", "词库就绪 " + loaded.size() + " 码，最大码长=" + loaded.maxCodeLen
                            + "，池=" + (loaded.pool.length / 1024) + "KB，耗时=" + cost + "ms");
                } catch (Throwable t) {
                    HeLog.f("ERR", "词库加载异常: " + t);
                }
            }
        }, "jdh-dict");
        loader.setDaemon(true);
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    /**
     * 是否是汉字单字（不含多字词、不含符号）。
     * 四码时只要简单鹤首选是单字，不论常用还是生僻，一律让位到第 2 位：
     * 手机上四码打单词远多于单个字，固定位次比"猜它常不常用"更省心。
     */
    private static boolean isHanChar(String w) {
        // 必须按 code point 判断：扩展 B 区及以上的汉字（如 𫵷 U+2BD77）
        // 在 Java 里一个字符占 2 个 char，String.length() 会是 2。
        // 早先写成 length()!=1 直接 return false，导致这类字的码永远不触发让位。
        if (w == null || w.isEmpty()) return false;
        if (w.codePointCount(0, w.length()) != 1) return false;
        int c = w.codePointAt(0);
        return (c >= 0x3400 && c <= 0x4DBF)      // 扩展 A
                || (c >= 0x4E00 && c <= 0x9FFF)  // 基本区
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容汉字
                || (c >= 0x20000 && c <= 0x323AF); // 扩展 B–H
    }

    private static InputStream openAsset(String asset) {
        try {
            ClassLoader cl = HeEntry.class.getClassLoader();
            if (cl != null) {
                InputStream in = cl.getResourceAsStream(asset);
                if (in != null) return in;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.security.ProtectionDomain domain = HeEntry.class.getProtectionDomain();
            java.security.CodeSource source = domain != null ? domain.getCodeSource() : null;
            if (source != null && source.getLocation() != null) {
                File apk = new File(source.getLocation().toURI());
                if (apk.isFile()) {
                    java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
                    java.util.zip.ZipEntry e = zip.getEntry(asset);
                    if (e != null) {
                        final java.util.zip.ZipFile z = zip;
                        return new java.io.FilterInputStream(zip.getInputStream(e)) {
                            @Override
                            public void close() throws java.io.IOException {
                                try { super.close(); } finally { z.close(); }
                            }
                        };
                    }
                    zip.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String[] dirs = {"/data/app", "/data/app-lib", "/mnt/asec"};
        for (String dir : dirs) {
            File[] apps = new File(dir).listFiles();
            if (apps == null) continue;
            for (File app : apps) {
                if (!app.getName().contains(MODULE_PKG)) continue;
                File apk = app.isDirectory() ? new File(app, "base.apk") : app;
                if (!apk.isFile()) continue;
                try {
                    java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
                    java.util.zip.ZipEntry e = zip.getEntry(asset);
                    if (e != null) {
                        final java.util.zip.ZipFile z = zip;
                        return new java.io.FilterInputStream(zip.getInputStream(e)) {
                            @Override
                            public void close() throws java.io.IOException {
                                try { super.close(); } finally { z.close(); }
                            }
                        };
                    }
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static InputStream openDict() {
        try {
            ClassLoader cl = HeEntry.class.getClassLoader();
            if (cl != null) {
                InputStream input = cl.getResourceAsStream(DICT_ASSET);
                if (input != null) {
                    HeLog.f("INIT", "词库来源=classpath");
                    return input;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            java.security.ProtectionDomain domain = HeEntry.class.getProtectionDomain();
            java.security.CodeSource source = domain != null ? domain.getCodeSource() : null;
            if (source != null && source.getLocation() != null) {
                File apk = new File(source.getLocation().toURI());
                if (apk.isFile()) {
                    InputStream stream = openEntryFromApk(apk);
                    if (stream != null) return stream;
                }
            }
        } catch (Throwable t) {
            HeLog.f("ERR", "读取模块 APK 失败: " + t);
        }

        String[] directories = {"/data/app", "/data/app-lib", "/mnt/asec"};
        for (String directory : directories) {
            File[] apps = new File(directory).listFiles();
            if (apps == null) continue;
            for (File app : apps) {
                if (!app.getName().contains(MODULE_PKG)) continue;
                File apk = app.isDirectory() ? new File(app, "base.apk") : app;
                if (!apk.isFile()) continue;
                try {
                    InputStream stream = openEntryFromApk(apk);
                    if (stream != null) return stream;
                } catch (Throwable t) {
                    HeLog.f("ERR", "读取 " + apk.getName() + " 失败: " + t);
                }
            }
        }
        return null;
    }

    private static InputStream openEntryFromApk(File apk) throws IOException {
        final ZipFile zip = new ZipFile(apk);
        ZipEntry entry = zip.getEntry(DICT_ASSET);
        if (entry == null) {
            zip.close();
            return null;
        }
        HeLog.f("INIT", "词库来源=" + apk.getAbsolutePath());
        return new FilterInputStream(zip.getInputStream(entry)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zip.close();
                }
            }
        };
    }

    /**
     * 将 1~6 位小写字母编码无损压入 30-bit 正整数（每字符 5 bit: 'a'->1..'z'->26）。
     * 不同长度高位不全相同，绝不碰撞。
     */
    private static int packCode(String code) {
        int len = code.length();
        if (len <= 0 || len > 6) return -1;
        int pack = 0;
        for (int i = 0; i < len; i++) {
            char c = code.charAt(i);
            if (c < 'a' || c > 'z') return -1;
            pack = (pack << 5) | (c - 'a' + 1);
        }
        return pack;
    }

    private static FlatDict parseFlat(InputStream input) throws Exception {
        int cap = 140000;
        long[] keyIdx = new long[cap]; // 高 32 bit: packKey, 低 32 bit: 原始行序号
        long[] rawMeta = new long[cap];
        int count = 0;
        int maxLen = 4;

        ByteArrayOutputStream poolOut = new ByteArrayOutputStream(2000000);
        StringBuilder filtered = new StringBuilder(64);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 32768)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab + 1 >= line.length()) continue;
                int key = packCodeRange(line, 0, tab);
                if (key < 0) continue;
                if (tab > maxLen) maxLen = tab;

                String value = cleanWords(line, tab + 1, filtered);
                if (value == null) continue;

                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                if (bytes.length == 0 || bytes.length > 0xFFFFF) continue;

                int start = poolOut.size();
                poolOut.write(bytes);

                if (count == cap) {
                    cap = cap + (cap >>> 1);
                    keyIdx = Arrays.copyOf(keyIdx, cap);
                    rawMeta = Arrays.copyOf(rawMeta, cap);
                }
                keyIdx[count] = (((long) key) << 32) | (count & 0xFFFFFFFFL);
                rawMeta[count] = (((long) start) << 20) | (bytes.length & 0xFFFFFL);
                count++;
            }
        }

        Arrays.sort(keyIdx, 0, count);

        int[] keys = new int[count];
        long[] meta = new long[count];
        int unique = 0;
        for (int i = 0; i < count; i++) {
            int k = (int) (keyIdx[i] >>> 32);
            int orig = (int) keyIdx[i];
            if (unique > 0 && keys[unique - 1] == k) {
                // 重复编码保留后出现的（因低 32 bit 序号递增，靠后即文件后行）
                meta[unique - 1] = rawMeta[orig];
            } else {
                keys[unique] = k;
                meta[unique] = rawMeta[orig];
                unique++;
            }
        }

        if (unique < count) {
            keys = Arrays.copyOf(keys, unique);
            meta = Arrays.copyOf(meta, unique);
        }
        return new FlatDict(keys, meta, poolOut.toByteArray(), maxLen);
    }

    private static int packCodeRange(String line, int start, int end) {
        int len = end - start;
        if (len <= 0 || len > 6) return -1;
        int pack = 0;
        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (c < 'a' || c > 'z') return -1;
            pack = (pack << 5) | (c - 'a' + 1);
        }
        return pack;
    }

    private static String cleanWords(String line, int start, StringBuilder sb) {
        int len = line.length();
        if (start >= len) return null;
        // 快路径：不含特殊占位词且无连续空格时直接截取
        if (line.indexOf("手写找字", start) < 0 && line.indexOf("  ", start) < 0
                && line.charAt(start) != ' ' && line.charAt(len - 1) != ' ') {
            return line.substring(start);
        }
        sb.setLength(0);
        int pos = start;
        while (pos < len) {
            int sp = line.indexOf(' ', pos);
            int end = sp >= 0 ? sp : len;
            if (end > pos) {
                String word = line.substring(pos, end);
                if (!"手写找字".equals(word)) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(word);
                }
            }
            if (sp < 0) break;
            pos = sp + 1;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String[] splitBySpace(String raw) {
        int firstSp = raw.indexOf(' ');
        if (firstSp < 0) {
            return new String[]{raw};
        }
        int count = 2;
        int pos = firstSp + 1;
        while ((pos = raw.indexOf(' ', pos)) >= 0) {
            count++;
            pos++;
        }
        String[] out = new String[count];
        int idx = 0;
        int start = 0;
        while (idx < count - 1) {
            int sp = raw.indexOf(' ', start);
            out[idx++] = raw.substring(start, sp);
            start = sp + 1;
        }
        out[idx] = raw.substring(start);
        return out;
    }

    // =====================================================================
    // 候选栏：全塞
    // =====================================================================

    private static void hookCandidateBar(Class<?> keyboard) {
        try {
            XposedBridge.hookAllMethods(keyboard, "notifyCandidateBarSnapshot", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onSnapshot(param.args);
                    } catch (Throwable t) {
                        HeLog.f("ERR", "候选栏处理异常: " + t);
                    }
                }
            });
            HeLog.f("INIT", "候选栏 Hook 已安装");
        } catch (Throwable t) {
            HeLog.f("ERR", "候选栏 Hook 失败: " + t);
        }
    }

    private static void onSnapshot(Object[] args) {
        if (args == null || args.length <= ARG_MODE) {
            resetPartialSelection();
            clearExact();
            return;
        }

        Object modeObj = args[ARG_MODE];
        if (!(modeObj instanceof Integer)) {
            resetPartialSelection();
            clearExact();
            return;
        }
        lastMode = (Integer) modeObj;
        if (lastMode != MODE_DOUBLE_SPELL) {
            resetPartialSelection();
            clearExact();
            return;
        }

        String code = args[ARG_CODE] instanceof CharSequence ? args[ARG_CODE].toString() : "";
        if (code.isEmpty() || !isTypingCode(code)) {
            resetPartialSelection();
            clearExact();
            return;
        }

        String[] words = args[ARG_WORDS] instanceof String[] ? (String[]) args[ARG_WORDS] : null;
        if (words == null || words.length == 0) {
            resetPartialSelection();
            clearExact();
            return;
        }

        // 点击候选后若未触发上屏且编码变短，判定为分段选词（如长句选完前半段后的剩余尾段），全程交还豆包
        if (pendingPartialClick) {
            if (code.length() < clickedCodeLen) {
                inPartialSelection = true;
                pendingPartialClick = false;
                if (cfgDebug) {
                    HeLog.throttled("PARTIAL", "进入分段选词，剩余编码=" + code + "，交还豆包");
                }
            } else if (code.length() > clickedCodeLen) {
                pendingPartialClick = false;
            }
        }
        lastSnapshotCodeLen = code.length();

        if (inPartialSelection) {
            clearExact();
            return;
        }

        FlatDict currentDict = dict;
        if (currentDict == null) {
            clearExact();
            return;
        }

        if (code.length() > currentDict.maxCodeLen) {
            clearExact();
            if (cfgDebug) probeLong(code);
            return;
        }

        int[] ids = args[ARG_IDS] instanceof int[] ? (int[]) args[ARG_IDS] : null;
        int[] types = args[ARG_TYPES] instanceof int[] ? (int[]) args[ARG_TYPES] : null;

        String[] mine = currentDict.get(code);
        boolean exact = mine != null;
        if (!exact) {
            if (!cfgMergePrefix) {
                clearExact();
                return;
            }
            mine = currentDict.getPrefix(code);
            if (mine == null) {
                clearExact();
                return;
            }
        } else if (code.length() < cfgMinExactLen) {
            clearExact();
            return;
        }

        merge(args, code, words, mine, ids, types, exact);
    }

    private static void merge(Object[] args, String code, String[] words, String[] mine,
                              int[] ids, int[] types, boolean exact) {
        int maxTotal = cfgMaxTotal;
        int own = Math.min(mine.length, maxTotal);
        if (own == 0) {
            clearExact();
            return;
        }

        // 编码切换时清掉上一帧残留的点击与空格状态，避免 TTL 内跨码串味
        Paint prev = lastPaint;
        if (prev == null || !code.equals(prev.code)) {
            clickOrigin = "";
            clickTarget = "";
            spaceArmed = false;
        }

        // 四码单字让位：简单鹤首选是汉字单字时，豆包首选占第 1 位，该字固定排第 2。
        // 不分常用/生僻——位置统一，不必再判断它常不常用。词与符号仍然占第 1 位。
        boolean demote = false;
        String mineFirst = mine[0];
        if (code.length() == 4 && isHanChar(mineFirst)
                && words.length > 0 && words[0] != null && !words[0].isEmpty()
                && !words[0].equals(mineFirst)) {
            demote = true;
        }

        int cap = Math.min(maxTotal, own + words.length);
        String[] buf = new String[cap];
        int[] origIdx = new int[cap];
        int total = 0;
        int wordFrom = 0;
        if (demote) {
            buf[total] = words[0];
            origIdx[total] = 0;
            total++;
            wordFrom = 1;
        }
        System.arraycopy(mine, 0, buf, total, own);
        Arrays.fill(origIdx, total, total + own, -1);
        int ownStart = total;
        total += own;

        for (int j = wordFrom; j < words.length; j++) {
            if (total >= cap) break;
            String word = words[j];
            if (word == null || word.isEmpty()) continue;
            if (containsWord(buf, total, word)) continue;
            buf[total] = word;
            origIdx[total] = j;
            total++;
        }

        String engineFirst = words[0] != null ? words[0] : "";
        String[] shown = total == buf.length ? buf : Arrays.copyOf(buf, total);

        // 注入格借用哪个候选的 ID/类型：优先"第一个词类型候选"，而不是末位。
        // 末位可能是推荐图标表情、用户词或工具项，借来会让豆包把这一格当成"操作项"而不是可上屏的词。
        int seed = 0;
        if (ids != null && types != null) {
            int limit = Math.min(types.length, words.length);
            seed = -1;
            for (int i = 0; i < limit; i++) {
                if (types[i] == 1) {
                    seed = i;
                    break;
                }
            }
            if (seed < 0) seed = 0;
        }

        if (cfgMergeAll && shown.length != words.length && ids != null && types != null) {
            resizeArrays(args, shown, words.length, ids, types, seed);
        } else {
            for (int i = 0; i < words.length; i++) {
                words[i] = i < shown.length ? shown[i] : "";
            }
        }
        syncAuxArray(args, ARG_AUX_1, origIdx, shown.length);
        syncAuxArray(args, ARG_AUX_2, origIdx, shown.length);

        lastIds = args[ARG_IDS] instanceof int[] ? (int[]) args[ARG_IDS] : null;
        lastTypes = args[ARG_TYPES] instanceof int[] ? (int[]) args[ARG_TYPES] : null;
        lastPaint = new Paint(code, shown, own, ownStart, engineFirst);

        if (cfgDebug && exact) {
            if (demote) HeLog.throttled("DEMOTE", "编码=" + code + " 单字[" + mineFirst + "]让位，豆包首选[" + words[0] + "]在前");
            HeLog.throttled("EXACT", "[全塞]编码=" + code + " 简单鹤" + own + "格 + 豆包"
                    + (shown.length - own) + "格 = " + shown.length
                    + " 注入格种子=格" + seed);
        }
    }

    private static boolean containsWord(String[] arr, int count, String target) {
        for (int i = 0; i < count; i++) {
            if (target.equals(arr[i])) return true;
        }
        return false;
    }

    /**
     * 按合并后的精确下标映射辅助串：简单鹤自有格置 null（回退为词本身），
     * 豆包顺延格按去重后的原始下标取值，避免跳过重复词导致辅助提示错位。
     */
    private static void syncAuxArray(Object[] args, int argIdx, int[] origIdx, int total) {
        if (args.length <= argIdx || !(args[argIdx] instanceof String[])) return;
        String[] src = (String[]) args[argIdx];
        if (src.length == 0) return;
        String[] copy = new String[total];
        for (int i = 0; i < total; i++) {
            int j = origIdx[i];
            if (j >= 0 && j < src.length) {
                copy[i] = src[j];
            }
        }
        args[argIdx] = copy;
    }

    /**
     * 词数组换成等长副本，同步扩缩候选 ID / 类型 / 偏移数组。
     * 扩展时注入格借用 seed 格的 ID/类型（默认第一个"词"类型候选），
     * 偏移数组仍复制末值让"子候选展开"分支退化为空；收窄时截断尾部避免空白格。
     */
    private static void resizeArrays(Object[] args, String[] shown, int base, int[] ids, int[] types, int seed) {
        int size = shown.length;
        args[ARG_WORDS] = shown;

        int seedIdx = 0;
        if (base > 0 && ids.length > 0 && types.length > 0) {
            seedIdx = Math.max(0, Math.min(seed, Math.min(base - 1, Math.min(ids.length, types.length) - 1)));
        }
        int copyLen = Math.min(base, size);

        int[] newIds = new int[size];
        System.arraycopy(ids, 0, newIds, 0, copyLen);
        if (size > base) {
            int fillId = ids.length > 0 ? ids[seedIdx] : 0;
            Arrays.fill(newIds, base, size, fillId);
        }
        args[ARG_IDS] = newIds;

        int[] newTypes = new int[size];
        System.arraycopy(types, 0, newTypes, 0, copyLen);
        if (size > base) {
            int fillType = types.length > 0 ? types[seedIdx] : 1;
            Arrays.fill(newTypes, base, size, fillType);
        }
        args[ARG_TYPES] = newTypes;

        if (args.length > ARG_OFFSETS && args[ARG_OFFSETS] instanceof int[]) {
            int[] offsets = (int[]) args[ARG_OFFSETS];
            if (offsets.length == base + 1) {
                int[] newOffsets = new int[size + 1];
                int offCopy = Math.min(offsets.length, newOffsets.length);
                System.arraycopy(offsets, 0, newOffsets, 0, offCopy);
                if (newOffsets.length > offsets.length) {
                    int last = offsets[offsets.length - 1];
                    Arrays.fill(newOffsets, offsets.length, newOffsets.length, last);
                }
                args[ARG_OFFSETS] = newOffsets;
            } else {
                args[ARG_OFFSETS] = null;
                if (cfgDebug) {
                    HeLog.throttled("WARN", "偏移数组长度 " + offsets.length + " ≠ " + (base + 1) + "，置空兜底");
                }
            }
        }
    }

    private static boolean isTypingCode(String code) {
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= 'a' && c <= 'z') continue;
            if (c == '\'') continue;
            return false;
        }
        return true;
    }

    /**
     * 彻底放手：清空当前编码状态 <b>以及画面快照</b>。
     */
    private static void clearExact() {
        lastPaint = null;
        clickOrigin = "";
        clickTarget = "";
        spaceArmed = false;
        lastIds = null;
        lastTypes = null;
    }

    private static void resetPartialSelection() {
        inPartialSelection = false;
        pendingPartialClick = false;
        clickedCodeLen = 0;
        lastSnapshotCodeLen = 0;
    }

    private static void probeLong(String code) {
        if (longSamples.size() >= 60 || !longSamples.add(code)) return;
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < code.length(); i += 2) {
            if (parts.length() > 0) parts.append('|');
            parts.append(code, i, Math.min(code.length(), i + 2));
        }
        HeLog.f("LONGSKIP", "编码=" + code + " 长度=" + code.length()
                + " 双拼切分=" + parts + "，交还豆包");
    }

    // =====================================================================
    // 点击：记下"被点那一格显示的是什么"
    // =====================================================================

    private static void hookCandidateTouch(Class<?> keyboard) {
        Method up = findMethod(keyboard, "onCandidateBarItemButtonUp",
                int.class, int.class, int.class,
                String.class, String.class, String.class, String.class,
                int.class, int.class, boolean.class, boolean.class);
        if (up != null) {
            XposedBridge.hookMethod(up, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onCandidateUp(param);
                    } catch (Throwable t) {
                        HeLog.f("ERR", "点击处理异常: " + t);
                    }
                }
            });
            HeLog.f("INIT", "候选点击 Hook 已安装");
        } else {
            HeLog.f("ERR", "未找到 onCandidateBarItemButtonUp");
        }

        Method down = findMethod(keyboard, "onCandidateBarItemButtonDown",
                int.class, int.class, int.class,
                String.class, String.class, String.class, String.class,
                boolean.class, boolean.class);
        if (down != null) {
            XposedBridge.hookMethod(down, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    lastDownAt = System.currentTimeMillis();
                    spaceArmed = false;
                }
            });
        }
    }

    /**
     * 实测参数语义（来自真机日志）：
     * {@code [1]} 与 {@code [2]} = 被点格子的索引，{@code [3]} = 豆包引擎自己的词，
     * {@code [5]} = 当前编码。用索引直接取画面，不靠词去猜。
     */
    private static void onCandidateUp(XC_MethodHook.MethodHookParam param) {
        spaceArmed = false;

        Object[] args = param.args;
        if (args == null || args.length < 7) return;

        int index = args[1] instanceof Integer ? (Integer) args[1] : -1;
        String engineWord = args[3] instanceof String ? (String) args[3] : "";
        String code = args[5] instanceof String ? (String) args[5] : "";

        if (cfgDebug) {
            long now = System.currentTimeMillis();
            long held = lastDownAt > 0 ? now - lastDownAt : -1;
            int[] ids = lastIds;
            int[] types = lastTypes;
            String idText = (ids != null && index >= 0 && index < ids.length) ? String.valueOf(ids[index]) : "未记录";
            String typeText = (types != null && index >= 0 && index < types.length) ? String.valueOf(types[index]) : "未记录";
            boolean overThreshold = held >= 0 && longPressTimeout > 0 && held >= longPressTimeout;
            HeLog.f("TAP", "编码=" + code + " 格" + index
                    + " 按住=" + (held < 0 ? "DOWN未捕获" : held + "ms")
                    + " 阈值=" + longPressTimeout + "ms"
                    + " id=" + idText + " type=" + typeText
                    + (overThreshold ? " ← 已超阈值，豆包会当长按处理" : ""));
        }

        int baseLen = !code.isEmpty() ? code.length() : lastSnapshotCodeLen;
        if (baseLen > 0) {
            clickedCodeLen = baseLen;
            pendingPartialClick = true;
        }

        Paint paint = lastPaint;
        if (paint == null || index < 0 || index >= paint.shown.length) {
            return;
        }

        if (code.isEmpty() || !code.equals(paint.code)) {
            if (cfgDebug) {
                HeLog.throttled("CLICKUP", "编码=" + code + " 与画面[" + paint.code + "]不符，不干预");
            }
            return;
        }

        String shownWord = paint.shown[index];
        if (shownWord == null || shownWord.isEmpty() || shownWord.equals(engineWord)) {
            if (cfgDebug) {
                HeLog.throttled("CLICKUP", "编码=" + code + " 格" + index + " 显示与引擎一致[" + shownWord + "]，不干预");
            }
            return;
        }

        clickOrigin = engineWord;
        clickTarget = shownWord;
        clickAt = System.currentTimeMillis();
        if (cfgDebug) {
            HeLog.f("CLICK", "编码=" + code + " 格" + index + " 显示[" + shownWord
                    + "] 引擎[" + engineWord + "]");
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            Method method = owner.getDeclaredMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 最后一道闸：豆包真正落到输入框的文本只有这里一个出口。
     * 若它给的还是引擎词，就换成用户看到的那一格。
     */
    private static void hookCommitText(ClassLoader loader) {
        try {
            Class<?> wrapper = Class.forName(IC_WRAPPER, false, loader);
            Method commit = wrapper.getDeclaredMethod("commitText", CharSequence.class, int.class);
            XposedBridge.hookMethod(commit, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onCommitText(param);
                    } catch (Throwable t) {
                        HeLog.f("ERR", "commitText 处理异常: " + t);
                    }
                }
            });
            HeLog.f("INIT", "commitText Hook 已安装（上屏最后一道闸）");
        } catch (Throwable t) {
            HeLog.f("ERR", "commitText Hook 失败: " + t);
        }
    }

    private static void onCommitText(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null || args.length < 2) return;
        if (!(args[0] instanceof CharSequence)) return;

        resetPartialSelection();
        long now = System.currentTimeMillis();
        String text = args[0].toString();

        // ① 点击对齐（兜底，不限制必须含汉字，兼容引擎给出纯字母/符号词的场景）
        String target = clickTarget;
        if (cfgClickFix && target != null && !target.isEmpty()) {
            if (now - clickAt > cfgClickTtlMs) {
                clickTarget = "";
                clickOrigin = "";
            } else if (text.equals(target)) {
                clickTarget = "";
                clickOrigin = "";
                return;
            } else if (text.equals(clickOrigin)) {
                args[0] = target;
                clickTarget = "";
                clickOrigin = "";
                if (cfgDebug) HeLog.f("CLICKFIX", "上屏 " + text + " -> " + target + "（对齐候选栏显示）");
                return;
            } else if (cfgDebug) {
                HeLog.f("CLICKMISS", "点击后上屏[" + text + "]，与显示[" + target + "]/引擎[" + clickOrigin + "]都不符");
            }
        }

        // ② 空格兜底：若豆包对纯字母/符号候选跳过 DoCommit 直接走 commitText
        if (cfgSpaceOverride && spaceArmed) {
            spaceArmed = false;
            if (now - spaceAt > cfgSpaceTtlMs) return;
            Paint paint = lastPaint;
            if (paint == null || paint.shown.length == 0) return;
            String first = paint.first();
            if (first == null || first.isEmpty() || text.equals(first)) return;
            if (shouldSpaceReplace(text, paint.engineFirst, paint.code)) {
                args[0] = first;
                if (cfgDebug) HeLog.f("SPACEPUT", "编码=" + paint.code + " 空格(commitText) " + text + " -> " + first);
            }
        }
    }

    // =====================================================================
    // 空格
    // =====================================================================

    private static void hookSpaceKey(Class<?> keyboard) {
        try {
            XposedBridge.hookAllMethods(keyboard, "DoFunctionKey", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length == 0 || !(args[0] instanceof Integer)) return;
                    int key = (Integer) args[0];
                    if (key != FN_KEY_SPACE && key != FN_KEY_STOP) return;
                    if (isAsrActive()) return;
                    if (lastMode != MODE_DOUBLE_SPELL) return;
                    Paint paint = lastPaint;
                    if (paint == null || paint.shown.length == 0) return;
                    spaceArmed = true;
                    spaceAt = System.currentTimeMillis();
                }
            });
            HeLog.f("INIT", "空格 Hook 已安装");
        } catch (Throwable t) {
            HeLog.f("ERR", "空格 Hook 失败: " + t);
        }
    }

    private static void initAsrFlag(Class<?> keyboard) {
        try {
            asrFlag = keyboard.getField("isAsrInput");
            HeLog.f("INIT", "语音态字段 isAsrInput 已定位");
        } catch (Throwable t) {
            asrFlag = null;
        }
    }

    private static boolean isAsrActive() {
        Field field = asrFlag;
        if (field == null) return false;
        try {
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =====================================================================
    // DoCommit：Java 侧记账 + 空格替换
    // =====================================================================

    private static void hookDoCommit(Class<?> keyboard) {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onDoCommit(param);
                    } catch (Throwable t) {
                        HeLog.f("ERR", "上屏处理异常: " + t);
                    }
                }
            };
            Method full = findMethod(keyboard, "DoCommit",
                    String.class, int.class, String.class, String.class, String.class);
            if (full != null) {
                XposedBridge.hookMethod(full, hook);
                HeLog.f("INIT", "上屏 Hook 已安装（精确 5 参）");
            } else {
                XposedBridge.hookAllMethods(keyboard, "DoCommit", hook);
                HeLog.f("INIT", "上屏 Hook 已安装（全重载回退）");
            }
        } catch (Throwable t) {
            HeLog.f("ERR", "上屏 Hook 失败: " + t);
        }
    }

    private static void onDoCommit(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null || args.length < 5) return;
        if (!(args[0] instanceof CharSequence)) return;

        resetPartialSelection();
        long now = System.currentTimeMillis();

        // ① 点击：把 Java 层的提交文本/原始文本一起对齐（commitText 那边还有一道）
        String target = clickTarget;
        if (cfgClickFix && target != null && !target.isEmpty()
                && now - clickAt <= cfgClickTtlMs) {
            String origin = args[0].toString();
            if (!origin.equals(target) && origin.equals(clickOrigin)) {
                args[0] = target;
                if (args[3] instanceof CharSequence) args[3] = target;
                if (cfgDebug) HeLog.f("COMMITFIX", "记账 " + origin + " -> " + target);
            }
        }

        // ② 空格：上屏简单鹤首选
        if (!cfgSpaceOverride) {
            spaceArmed = false;
            return;
        }
        if (!spaceArmed) return;
        spaceArmed = false;
        if (now - spaceAt > cfgSpaceTtlMs) return;

        Paint paint = lastPaint;
        if (paint == null || paint.shown.length == 0) return;
        String first = paint.first();
        if (first == null || first.isEmpty()) return;

        String origin = args[0].toString();
        if (origin.isEmpty() || origin.equals(first)) return;
        if (!shouldSpaceReplace(origin, paint.engineFirst, paint.code)) return;

        args[0] = first;
        if (args[3] instanceof CharSequence && ((CharSequence) args[3]).length() > 0) {
            args[3] = first;
        }
        if (cfgDebug) HeLog.f("SPACEPUT", "编码=" + paint.code + " 空格 " + origin + " -> " + first);
    }

    private static boolean shouldSpaceReplace(String origin, String engineFirst, String code) {
        String trimmed = origin.trim();
        if (trimmed.isEmpty()) return false;
        if (engineFirst != null && !engineFirst.isEmpty()
                && (origin.equals(engineFirst) || trimmed.equals(engineFirst))) {
            return true;
        }
        if (code != null && !code.isEmpty() && trimmed.equalsIgnoreCase(code)) {
            return true;
        }
        return containsHan(origin);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private static boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)) return true;
        }
        return false;
    }

    private static String firstLine(String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String value = reader.readLine();
                return value == null ? null : value.trim();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> readAllLines(String path) {
        List<String> lines = new ArrayList<>();
        try {
            File file = new File(path);
            if (!file.isFile()) return lines;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    lines.add(line);
                }
            }
        } catch (Throwable ignored) {
        }
        return lines;
    }
}
