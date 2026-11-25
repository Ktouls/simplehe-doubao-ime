import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验 2.7 扁平词库（int[] keys + long[] meta + byte[] pool + binarySearch）
 * 与朴素 HashMap 版本的查询结果是否完全一致。
 *
 * 算法复制自 HeEntry 的 packCodeRange / parseFlat / FlatDict.get。
 */
public final class DictVerify {

    // ---- 复制自 HeEntry ----
    static final class FlatDict {
        final int[] keys;
        final long[] meta;
        final byte[] pool;
        final int maxCodeLen;

        FlatDict(int[] keys, long[] meta, byte[] pool, int maxCodeLen) {
            this.keys = keys;
            this.meta = meta;
            this.pool = pool;
            this.maxCodeLen = maxCodeLen;
        }

        int size() { return keys.length; }

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

    static int packCode(String code) {
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

    static int packCodeRange(String line, int start, int end) {
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

    static String cleanWords(String line, int start, StringBuilder sb) {
        int len = line.length();
        if (start >= len) return null;
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

    static String[] splitBySpace(String raw) {
        int firstSp = raw.indexOf(' ');
        if (firstSp < 0) return new String[]{raw};
        int count = 2;
        int pos = firstSp + 1;
        while ((pos = raw.indexOf(' ', pos)) >= 0) { count++; pos++; }
        String[] out = new String[count];
        int idx = 0, start = 0;
        while (idx < count - 1) {
            int sp = raw.indexOf(' ', start);
            out[idx++] = raw.substring(start, sp);
            start = sp + 1;
        }
        out[idx] = raw.substring(start);
        return out;
    }

    static FlatDict parseFlat(java.io.InputStream input, int[] outCount) throws Exception {
        int cap = 140000;
        long[] keyIdx = new long[cap];
        long[] rawMeta = new long[cap];
        int count = 0;
        int maxLen = 4;

        java.io.ByteArrayOutputStream poolOut = new java.io.ByteArrayOutputStream(2000000);
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
        outCount[0] = unique;
        return new FlatDict(keys, meta, poolOut.toByteArray(), maxLen);
    }

    /** 朴素参考实现：后写覆盖，值按空格切分。 */
    static Map<String, String[]> parseNaive(List<String> lines) {
        Map<String, String[]> map = new HashMap<>(200000);
        StringBuilder sb = new StringBuilder(64);
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab + 1 >= line.length()) continue;
            if (packCodeRange(line, 0, tab) < 0) continue;
            String value = cleanWords(line, tab + 1, sb);
            if (value == null) continue;
            map.put(line.substring(0, tab), splitBySpace(value));
        }
        return map;
    }

    public static void main(String[] args) throws Exception {
        Path dictPath = Path.of(args[0]);
        List<String> lines = Files.readAllLines(dictPath, StandardCharsets.UTF_8);

        int[] c = new int[1];
        FlatDict flat;
        long t0 = System.currentTimeMillis();
        try (java.io.InputStream in = Files.newInputStream(dictPath)) {
            flat = parseFlat(in, c);
        }
        long t1 = System.currentTimeMillis();
        Map<String, String[]> naive = parseNaive(lines);
        long t2 = System.currentTimeMillis();

        System.out.println("扁平词库: " + flat.size() + " 码, 池=" + (flat.pool.length / 1024)
                + "KB, maxCodeLen=" + flat.maxCodeLen + ", 加载=" + (t1 - t0) + "ms");
        System.out.println("朴素词库: " + naive.size() + " 码, 加载=" + (t2 - t1) + "ms");

        int fail = 0, checked = 0;
        for (Map.Entry<String, String[]> e : naive.entrySet()) {
            String code = e.getKey();
            String[] expect = e.getValue();
            String[] actual = flat.get(code);
            checked++;
            if (actual == null) {
                if (fail++ < 10) System.out.println("  查询缺失: " + code + " 期望=" + Arrays.toString(expect));
                continue;
            }
            if (!Arrays.equals(expect, actual)) {
                if (fail++ < 10) System.out.println("  值不一致: " + code
                        + "\n    期望=" + Arrays.toString(expect)
                        + "\n    实际=" + Arrays.toString(actual));
            }
        }
        System.out.println("全量对比: 检查 " + checked + " 码, 失败 " + fail);

        // 反向：扁平词库里不该有朴素版没有的码
        int extra = 0;
        for (int i = 0; i < flat.keys.length; i++) {
            int key = flat.keys[i];
            String code = unpack(key);
            if (code != null && !naive.containsKey(code)) {
                if (extra++ < 5) System.out.println("  多余码: " + code);
            }
        }
        System.out.println("多余码: " + extra);

        // 碰撞检查：不同编码不得映射到同一 key
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (String code : naive.keySet()) {
            int k = packCode(code);
            if (!seen.add(k)) System.out.println("  !! key 碰撞: " + code);
        }
        System.out.println("碰撞检查完成, 不同 key 数=" + seen.size());

        System.out.println("=== 抽查 ===");
        for (String code : new String[]{"a", "w", "jde", "jd", "wo", "en", "csn", "mwuf", "mwufme", "of", "sm"}) {
            System.out.printf("  %-8s flat=%s | naive=%s%n", code,
                    Arrays.toString(flat.get(code)),
                    Arrays.toString(naive.get(code)));
        }
        System.out.println(fail == 0 && extra == 0 ? "结论: 通过" : "结论: 存在差异，需修");
    }

    static String unpack(int key) {
        if (key <= 0) return null;
        StringBuilder sb = new StringBuilder();
        List<Integer> digits = new ArrayList<>();
        int k = key;
        while (k > 0) { digits.add(k & 31); k >>>= 5; }
        for (int i = digits.size() - 1; i >= 0; i--) {
            int d = digits.get(i);
            if (d < 1 || d > 26) return null;
            sb.append((char) ('a' + d - 1));
        }
        return sb.toString();
    }
}