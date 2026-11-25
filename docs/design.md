# 设计说明：为什么这样 Hook

本文记录反编译核对过的结论，避免后来人（或未来的我）重复踩坑。

分析对象：豆包输入法 1.5.6 的 `classes*.dex` 与 `lib/arm64-v8a/libkeyboard.so`。

---

## 1. 候选快照的 37 个参数

```
KeyboardJni.notifyCandidateBarSnapshot(
    J I [String [String [String [String String
    [I [I [I [I [I [I [I
    String String [I [String [I
    Z Z Z Z Z String Z Z I Z Z Z
    [String String [I Z J I )V
```

按 0 起始的 `args[]`，实际用到的：

| 下标 | 类型 | 含义 | 依据 |
|---|---|---|---|
| 2 | String[] | 候选词（显示） | 循环上界 `min(arg2,arg8,arg10)` |
| 3 | String[] | 候选辅助串（null 时回退为词本身） | `strArr2[i]==null ? 词 : strArr2[i]` |
| 4 | String[] | 同上 | 同上 |
| 6 | String | 当前编码 | 塞进候选对象的第 7 个字段 |
| 8 | int[] | 候选 ID | 与 arg2 等长，点击时回引擎取词 |
| 10 | int[] | 候选类型 | `iArr4[i]==1` 判是否为"词" |
| 18 | int[] | 偏移数组 | 长度通常 `min+1` |
| 27 | int | `KeyboardInputMode` | smali 里 `p28` 连续 6 次与 `getInputModeValue()` 比较 |

```java
enum KeyboardInputMode {
    PY26(0), PY9(1), ENGLISH26(2), DOUBLE_SPELL(3), HANDWRITING(4), STROKE(5), WUBI(6);
}
```

**`args[27] == 3` 就是小鹤双拼。** 每次候选刷新都会带过来，比反射
`SettingsConfigNext` 或读 `/sdcard` 可靠得多，也快得多。

### 全塞为什么要连带扩展 arg8/arg10/arg18

循环上界是三者最短，所以只加长 arg2 会被截断。三者一起换成长度 `size` 的副本：

- `arg8`：新增槽位复制末值（候选 ID）
- `arg10`：新增槽位复制末值（类型）
- `arg18`：长度 `base+1` 时扩成 `size+1`，尾部复制末值，
  让"子候选展开"分支（`iArr9[i] < iArr9[i+1]`）退化为空；长度不符则置 null 兜底

节点：数组是引用传参，换掉的是这一帧的副本，不写豆包的缓存。

---

## 2. 点击上屏走 native

```java
public native void onCandidateBarItemButtonUp(int, int, int,
        String, String, String, String, int, int, boolean, boolean);
```

`libkeyboard.so` 里通过 `GetMethodID` 找到这个方法名但不依赖 Java 实现，
取词用的是引擎自己的候选 ID。

**推论**：改 `expectedText`、改 `args[2]` 都不会影响上屏结果。
"改字符串让点击正确"原理上不通。

### 参数语义（真机日志实测定标）

```
点第 1 格：[0]0 [1]0 [2]0 [3]"我"   [4]"" [5]"w"   [6]"我"   [9]false [10]true
点第 2 格：[0]0 [1]1 [2]1 [3]"从你" [4]"" [5]"csn" [6]"从你" [9]false [10]true
点第 3 格：[0]0 [1]2 [2]2 [3]"达"   [4]"" [5]"da"  [6]"达"   [9]false [10]true
```

- `[1]`、`[2]`：被点格索引（点第 2 格 = 1）
- `[3]`、`[6]`：豆包引擎自己的词
- `[5]`：当前编码
- `[0]`：恒为 0，语义未定，不使用

---

## 3. 上屏的两道边界

| 边界 | 性质 | 用途 |
|---|---|---|
| `KeyboardJni.DoCommit(String,int,String,String,String)` | Java，5 参完整实现；3/4 参是桥接 | 记账、`originText`（学习上报）、空格替换 |
| `com.bytedance.android.input.editor.InputConnectionWrapper.commitText(CharSequence,int)` | 豆包自己的包装类，所有上屏的唯一出口 | 真正落到输入框的文本 |

`DoCommit` 第 4 参 `originText` 会被喂给学习上报：

```java
a9.e.l(originText, ...);   // 学习/统计
```

所以替换上屏文本时必须一起改它，否则豆包学到的是原词，用久了自学习会被带偏。

---

## 4. 空格键码

```
KEY_BACKSPACE    = 0x01
KEY_ENTER        = 0x02
KEY_SPACE        = 0x03   ← Java 全 APK 零引用
KEY_START_RECORD = 0x20
KEY_STOP_RECORD  = 0x21   ← 空格事件实测也以此码到达
```

原生侧空格走 `KeyboardCallbackImpl::DoFunctionKey` 的独立分支。
Java 侧两个码都接受，并用 `KeyboardJni.isAsrInput` 排除语音场景
（停止录音也发 0x21，不排除会误判）。

---

## 5. 2.4 修的那个 bug：跨编码误替换

```
EXACT:    [全塞]编码=mwuf   简单鹤2格 + 豆包9格 = 11
LONGSKIP: 编码=mwufme 长度=6，交还豆包
CLICK:    编码=mwufme 格0 显示[美神] 引擎[没什么]   ← 错
```

`mwuf → 美神 䆊`，`mwufme` 简单鹤无此码。

原因：超长码放手时只清了 `exactFirst`/`exactCode`，**没清画面快照 `lastPaint`**。
它还停在 `mwuf` 那一帧，于是 `mwufme` 的第 0 格取到了旧画面的 `shown[0]="美神"`。

同一 bug 还影响 `vffule`（显示振幅 → 真服了）、空编码（显示问题 → 不大）。

修法：

1. `clearExact()` 一并作废 `lastPaint`、`clickOrigin`、`clickTarget`；
   所有放手路径（非双拼、空编码、非字母编码、词库未就绪、空词数组、超长码、
   无命中、短于 `min_exact_len`）都要调用它；
2. 点击时校验 `画面编码 == 当前编码`，不符则不干预。

教训：**"这个码我们不管"和"没东西可管"必须走同一条清理路径。**

---

## 6. 2.5 修的那个 bug：非汉字原生候选漏替换

```
编码=of/or/oa/on → 简单鹤首选=《 / ） / ！ / ；，豆包第 0 格="of"/"or"/"oa"/"on"
编码=sm          → 简单鹤首选=什么，部分设备豆包第 0 格="SM"
```

原因：旧版 `onDoCommit` 与 `onCommitText` 均用 `!containsHan(...)` 过滤待替换文本，
导致豆包原生第 0 格为纯字母或非汉字时直接放行，候选栏显示正确但空格/点选依然上屏字母。

修法：

1. `merge()` 时记录豆包原生第 0 格 `exactEngineFirst`；
2. `onDoCommit()` 改为 `shouldSpaceReplace()`：只要非纯空白，且命中 `exactEngineFirst`、
   忽略大小写命中当前编码 `exactCode`、或包含汉字，均替换为简单鹤首选；
3. `onCommitText()` 移除冗余的 `!containsHan(text)` 限制，并补充 `spaceArmed` 兜底，
   防止豆包对纯字母候选跳过 `DoCommit` 直接走 `commitText`。

---

## 7. 2.6 性能优化要点

1. **热路径零磁盘 I/O**：`cfgDebug` 默认关闭（`false`），且 `HeLog` 改为 `ArrayBlockingQueue` + 单后台守护线程批量写盘与定时 flush，消除打字停顿后首键在 FUSE 上同步 `flush()` 造成的毛刺，同时将 `SimpleDateFormat` 隔离到后台线程。
2. **热路径去重零集合分配**：`merge()` 不再每次按键创建 `ArrayList` × 2 与 `HashSet`，改为栈上直接构造定长数组，并通过小循环比对 `0..own-1`（通常 1~3 格）完成去重。
3. **冷启动提速与减内存**：`openDict()` 优先走 `ClassLoader.getResourceAsStream`，消除每次启动必现的 `ProtectionDomain` 空指针异常；`parse()` 改为单遍流式读取 + `indexOf(' ')` 手写切分，去掉 13.7 万行的 `codes`/`raw` 临时容器与正则 `split`；`handleLoadPackage` 跳过 `:push`/`:daemon` 等后台子进程。
4. **状态原子化与辅助串对齐**：将 `code`、`shown`、`own`、`engineFirst` 统一收口进不可变 `Paint` 快照；合并时同步将 `args[3]`/`args[4]` 前 `own` 格置 `null` 并顺延其余格，避免辅助提示错位。

---

## 8. 2.7 深层优化：扁平无对象词库与边界加固

1. **扁平无对象词库（`FlatDict`）**：137,228 个 `1~4` 码（支持至 6 码）按每字符 5 bit 无损打包成 30-bit `int` 并升序存放于 `int[] keys`，词条 UTF-8 字节顺序拼入单一 `byte[] pool`，偏移与长度压缩进 `long[] meta`（高 44 bit 偏移 + 低 20 bit 长度）。全库仅 **3 个基本类型数组、约 3.2 MB 堆内存**，相比 `HashMap<String, String[]>` 消除了约 50 万个常驻 Java 对象的 ART GC 扫描开销，查询走 `Arrays.binarySearch` 最多 17 次整数比较。
2. **辅助串精确下标映射**：`merge()` 在去重豆包候选时同步记录每个保留词的原始下标 `origIdx[]`，`syncAuxArray()` 按 `origIdx` 精确拷贝 `args[3]`/`args[4]`（简单鹤自有格置 `null`），彻底消除跳过重复词引起的辅助提示错位。
3. **数组双向扩缩**：`resizeArrays()` 同时处理扩展与收窄，避免 `shown.length < words.length` 时尾部留出 `""` 空白占位格。
4. **精确 Hook 与主进程白名单**：`DoCommit` 优先精确 Hook 5 参实现（减少 3/4 参桥接重载的 Xposed 分发）；`merge()` 检测到编码切换时主动清理上一帧残留的点击与空格状态；进程过滤锁死仅放行主进程 `com.bytedance.android.doubaoime`。

---

## 9. 词库顺序

`jde.dict.yaml` 191,096 行全是 2 列（`词 \t 码`），没有权重列。

所以 **行序就是排序**。`gen_dict.py` 严格按文件行序累积、同码内保持首次出现顺序、
同 (码, 词) 去重。任何"按词频重排"都会破坏简单鹤的原生候选顺序。

构建顺序：`jde.dict.yaml` → `jde_ext.dict.yaml` → `my_user.dict.yaml`。
旧版从 `简单鹤V9.3.0.txt`（导出文本）转换，缺 1,242 个 3 码与 9,452 个 4 码
（主要是 `jde_ext` 扩展字集）。

---

## 10. 为什么不做长句形码筛选

双拼音节是定长 2 字母（`wo|zd|ce|ui|yi|xx` = 我在测试一下）。
简单鹤"音节 + 形码"是 2 + 2 = 4 字母。

```
"1 音节 + 2 形码" 与 "2 音节" 在字符串上完全相同
cs|na  ==  csn|a
```

豆包双拼键盘不产生 `'` 分隔符，没有可靠区分手段。硬做就是靠猜。

`LONGSKIP` 记录样本（每码一条、上限 60、带双拼切分）用于后续判断：
若用户习惯"整句打完再选"，这一档不需要；若"边打边收窄"，
才值得做"末段 4 码优先"的启发式（会有少量误判）。