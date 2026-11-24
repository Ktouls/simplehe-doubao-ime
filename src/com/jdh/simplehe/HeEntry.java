package com.jdh.simplehe;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 简单鹤音形挂载 2.3。
 *
 * <p>策略：<b>该码下简单鹤的全部候选排在最前，豆包自己的候选原样接在后面</b>，
 * 一个都不删。空格按简单鹤首选上屏。
 *
 * <p>点击对齐（本版重点）：{@code onCandidateBarItemButtonUp} 是 native 方法，
 * 上屏由 libkeyboard.so 按它自己的候选 ID 完成，改 Java 侧的字符串不参与取词。
 * 所以不走"改标签"，而是在上屏边界把结果换成用户按下时看到的那一格：
 *
 * <ul>
 *   <li>候选快照时记录整屏 {@code shown[]}（我们画的）；</li>
 *   <li>触摸按下或抬起时读第 2 个参数（实测是被点格子的索引），
 *       目标词 = {@code shown[index]}，同时记下豆包准备上屏的那个词；</li>
 *   <li>在 {@code DoCommit} 和 {@code InputConnectionWrapper.commitText}
 *       两个边界上做替换，前者管 Java 记账，后者管真正落到输入框的文本。</li>
 * </ul>
 *
 * <p>不做：辅助码提示、字根拆分、简快码、造词、反查。豆包候选栏没有承载位置。
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
    private static volatile boolean cfgDebug = true;
    private static volatile int cfgMinExactLen = 1;
    private static volatile int cfgMaxTotal = 24;
    private static volatile long cfgSpaceTtlMs = 1500L;
    private static volatile long cfgClickTtlMs = 2500L;

    // ---- 词库 ----
    private static final Map<String, String[]> DICT = new HashMap<>(160000);
    private static volatile boolean dictReady;
    private static volatile int maxCodeLen = 4;

    /** 一帧画面：我们画上去的完整候选列表。 */
    private static final class Paint {
        final String code;
        final String[] shown;
        final int own;

        Paint(String code, String[] shown, int own) {
            this.code = code;
            this.shown = shown;
            this.own = own;
        }
    }

    private static volatile Paint lastPaint;

    /** 本次点击待对齐：豆包会给出的词 → 用户看到的那一格。 */
    private static volatile String clickOrigin = "";
    private static volatile String clickTarget = "";
    private static volatile long clickAt;

    private static volatile String exactFirst = "";
    private static volatile String exactCode = "";
    private static volatile boolean spaceArmed;
    private static volatile long spaceAt;
    private static volatile int lastMode = -1;

    private static final Set<String> longSamples = new HashSet<>();
    private static Field asrFlag;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !TARGET.equals(lp.packageName)) return;

        HeLog.initAndReport("INIT");
        loadConfig();
        HeLog.f("INIT", "简单鹤音形挂载 2.4 | 开关=" + (cfgEnabled ? "ON" : "OFF")
                + " 全塞=" + (cfgMergeAll ? "ON" : "OFF")
                + " 上限=" + cfgMaxTotal
                + " 点击对齐=" + (cfgClickFix ? "ON" : "OFF")
                + " 空格替换=" + (cfgSpaceOverride ? "ON" : "OFF"));

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
    // 词库
    // =====================================================================

    private static void startDictLoader() {
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream input = openDict();
                    if (input == null) {
                        HeLog.f("ERR", "词库打开失败，全程放行");
                        return;
                    }
                    parse(input);
                    dictReady = true;
                    HeLog.f("INIT", "词库就绪 " + DICT.size() + " 码，最大码长=" + maxCodeLen);
                } catch (Throwable t) {
                    HeLog.f("ERR", "词库加载异常: " + t);
                }
            }
        }, "jdh-dict");
        loader.setDaemon(true);
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    private static InputStream openDict() {
        try {
            java.security.CodeSource source = HeEntry.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                File apk = new File(source.getLocation().toURI());
                if (apk.isFile()) {
                    ZipFile zip = new ZipFile(apk);
                    ZipEntry entry = zip.getEntry(DICT_ASSET);
                    if (entry != null) {
                        HeLog.f("INIT", "词库来源=" + apk.getAbsolutePath());
                        return zip.getInputStream(entry);
                    }
                    zip.close();
                }
            }
        } catch (Throwable t) {
            HeLog.f("ERR", "读取模块 APK 失败: " + t);
        }

        try {
            InputStream input = HeEntry.class.getClassLoader().getResourceAsStream(DICT_ASSET);
            if (input != null) {
                HeLog.f("INIT", "词库来源=classpath");
                return input;
            }
        } catch (Throwable ignored) {
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
                    ZipFile zip = new ZipFile(apk);
                    ZipEntry entry = zip.getEntry(DICT_ASSET);
                    if (entry != null) {
                        HeLog.f("INIT", "词库来源=" + apk.getAbsolutePath());
                        return zip.getInputStream(entry);
                    }
                    zip.close();
                } catch (Throwable t) {
                    HeLog.f("ERR", "读取 " + apk.getName() + " 失败: " + t);
                }
            }
        }
        return null;
    }

    private static void parse(InputStream input) throws Exception {
        List<String> codes = new ArrayList<>(160000);
        Map<String, String> raw = new HashMap<>(200000);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String code = line.substring(0, tab);
                if (code.isEmpty()) continue;
                raw.put(code, line.substring(tab + 1));
                codes.add(code);
                if (code.length() > maxCodeLen) maxCodeLen = code.length();
            }
        }
        for (String code : codes) {
            String value = raw.get(code);
            if (value == null || value.isEmpty()) continue;
            String[] words = value.split(" ");
            if (words.length > 0) DICT.put(code, words);
        }
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
            clearExact();
            return;
        }

        Object modeObj = args[ARG_MODE];
        if (!(modeObj instanceof Integer)) {
            clearExact();
            return;
        }
        lastMode = (Integer) modeObj;
        if (lastMode != MODE_DOUBLE_SPELL) {
            clearExact();
            return;
        }

        String code = args[ARG_CODE] instanceof CharSequence ? args[ARG_CODE].toString() : "";
        if (code.isEmpty() || !isTypingCode(code)) {
            clearExact();
            return;
        }
        if (!dictReady) {
            clearExact();
            return;
        }

        String[] words = args[ARG_WORDS] instanceof String[] ? (String[]) args[ARG_WORDS] : null;
        if (words == null || words.length == 0) {
            clearExact();
            return;
        }

        if (code.length() > maxCodeLen) {
            clearExact();
            if (cfgDebug) probeLong(code);
            return;
        }

        int[] ids = args[ARG_IDS] instanceof int[] ? (int[]) args[ARG_IDS] : null;
        int[] types = args[ARG_TYPES] instanceof int[] ? (int[]) args[ARG_TYPES] : null;

        String[] mine = DICT.get(code);
        boolean exact = mine != null;
        if (!exact) {
            if (!cfgMergePrefix) {
                clearExact();
                return;
            }
            mine = lookupPrefix(code);
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
        List<String> mineList = new ArrayList<>(mine.length);
        Set<String> seen = new HashSet<>();
        for (String word : mine) {
            if (word == null || word.isEmpty() || "手写找字".equals(word)) continue;
            if (!seen.add(word)) continue;
            mineList.add(word);
            if (mineList.size() >= cfgMaxTotal) break;
        }
        if (mineList.isEmpty()) {
            clearExact();
            return;
        }
        int own = mineList.size();

        List<String> merged = new ArrayList<>(mineList);
        for (String word : words) {
            if (merged.size() >= cfgMaxTotal) break;
            if (word == null || word.isEmpty()) continue;
            if (!seen.add(word)) continue;
            merged.add(word);
        }

        exactFirst = merged.get(0);
        exactCode = code;
        String[] shown = merged.toArray(new String[0]);

        if (cfgMergeAll && shown.length > words.length && ids != null && types != null) {
            extendArrays(args, shown, words.length, ids, types);
        } else {
            for (int i = 0; i < words.length; i++) {
                words[i] = i < shown.length ? shown[i] : "";
            }
        }

        lastPaint = new Paint(code, shown, own);

        if (cfgDebug && exact) {
            HeLog.throttled("EXACT", "[全塞]编码=" + code + " 简单鹤" + own + "格 + 豆包"
                    + (shown.length - own) + "格 = " + shown.length);
        }
    }

    /**
     * 词数组换成更长的副本，同步扩展候选ID/类型/偏移数组。
     * 偏移数组 arg18 长度通常是 min+1，尾部复制末值让"子候选展开"分支退化为空。
     */
    private static void extendArrays(Object[] args, String[] shown, int base, int[] ids, int[] types) {
        int size = shown.length;
        args[ARG_WORDS] = shown;

        int[] newIds = new int[size];
        System.arraycopy(ids, 0, newIds, 0, base);
        int fillId = base > 0 ? ids[base - 1] : 0;
        for (int i = base; i < size; i++) newIds[i] = fillId;
        args[ARG_IDS] = newIds;

        int[] newTypes = new int[size];
        System.arraycopy(types, 0, newTypes, 0, base);
        int fillType = base > 0 ? types[base - 1] : 1;
        for (int i = base; i < size; i++) newTypes[i] = fillType;
        args[ARG_TYPES] = newTypes;

        if (args.length > ARG_OFFSETS && args[ARG_OFFSETS] instanceof int[]) {
            int[] offsets = (int[]) args[ARG_OFFSETS];
            if (offsets.length == base + 1) {
                int[] newOffsets = new int[size + 1];
                System.arraycopy(offsets, 0, newOffsets, 0, offsets.length);
                int last = offsets[offsets.length - 1];
                for (int i = offsets.length; i < newOffsets.length; i++) newOffsets[i] = last;
                args[ARG_OFFSETS] = newOffsets;
            } else {
                args[ARG_OFFSETS] = null;
                HeLog.throttled("WARN", "偏移数组长度 " + offsets.length + " ≠ " + (base + 1) + "，置空兜底");
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

    private static String[] lookupPrefix(String code) {
        for (int length = code.length() - 1; length >= 1; length--) {
            String[] value = DICT.get(code.substring(0, length));
            if (value != null) return value;
        }
        return null;
    }

    /**
     * 彻底放手：清空当前编码状态 <b>以及画面快照</b>。
     *
     * <p>2.3 的 bug 就在这里：超长码走 LONGSKIP 时只清了 exactFirst/exactCode，
     * 没清 lastPaint，于是 mwufme（简单鹤无此码）点候选时取了 mwuf 那一帧的
     * shown[0]="美神"，把豆包的"没什么"替换掉了。凡是"这个码我们不管"的路径，
     * 都必须把画面一起作废。
     */
    private static void clearExact() {
        exactFirst = "";
        exactCode = "";
        lastPaint = null;
        clickOrigin = "";
        clickTarget = "";
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

        Paint paint = lastPaint;
        if (paint == null || index < 0 || index >= paint.shown.length) {
            return;
        }

        // 画面必须是"当前这个编码"的画面。超长码/被放行的码，lastPaint 已作废，
        // 若这里不校验，就会拿上一个编码的格子去替换（mwufme 取到 mwuf 的"美神"）。
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
        if (!cfgClickFix) return;

        Object[] args = param.args;
        if (args == null || args.length < 2) return;
        if (!(args[0] instanceof CharSequence)) return;

        String target = clickTarget;
        if (target == null || target.isEmpty()) return;
        if (System.currentTimeMillis() - clickAt > cfgClickTtlMs) {
            clickTarget = "";
            clickOrigin = "";
            return;
        }

        String text = args[0].toString();
        if (text.equals(target)) {
            clickTarget = "";
            clickOrigin = "";
            return;
        }
        // 只替换"豆包准备上屏的那个引擎词"，其它文本一律不动
        if (!text.equals(clickOrigin)) return;
        if (!containsHan(text)) return;

        args[0] = target;
        clickTarget = "";
        clickOrigin = "";
        HeLog.f("CLICKFIX", "上屏 " + text + " -> " + target + "（对齐候选栏显示）");
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
                    if (exactFirst == null || exactFirst.isEmpty()) return;
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
            XposedBridge.hookAllMethods(keyboard, "DoCommit", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onDoCommit(param);
                    } catch (Throwable t) {
                        HeLog.f("ERR", "上屏处理异常: " + t);
                    }
                }
            });
            HeLog.f("INIT", "上屏 Hook 已安装");
        } catch (Throwable t) {
            HeLog.f("ERR", "上屏 Hook 失败: " + t);
        }
    }

    private static void onDoCommit(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null || args.length < 5) return;
        if (!(args[0] instanceof CharSequence)) return;

        long now = System.currentTimeMillis();

        // ① 点击：把 Java 层的提交文本/原始文本一起对齐（commitText 那边还有一道）
        String target = clickTarget;
        if (cfgClickFix && target != null && !target.isEmpty()
                && now - clickAt <= cfgClickTtlMs) {
            String origin = args[0].toString();
            if (!origin.equals(target) && origin.equals(clickOrigin)) {
                args[0] = target;
                if (args[3] instanceof CharSequence) args[3] = target;
                HeLog.f("COMMITFIX", "记账 " + origin + " -> " + target);
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

        String first = exactFirst;
        if (first == null || first.isEmpty()) return;

        String origin = args[0].toString();
        if (origin.isEmpty() || !containsHan(origin) || origin.equals(first)) return;

        args[0] = first;
        if (args[3] instanceof CharSequence && ((CharSequence) args[3]).length() > 0) {
            args[3] = first;
        }
        HeLog.f("SPACEPUT", "编码=" + exactCode + " 空格 " + origin + " -> " + first);
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