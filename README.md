# 简单鹤音形挂载 · 完整版（豆包输入法 · LSPosed 模块）

把 **简单鹤（jde）** 的音形码表**原样**挂到 **豆包输入法** 自带的小鹤双拼候选栏上。

**原汁原味**：简单鹤的候选在最前，豆包自己的候选**原样顺延在后、一个都不删**；
不做任何位次调整，不改词序。长句拼音完整交还豆包，不动它的整句与智能联想。

```
打 csn  →  悰 | 从你 | 从那 | 从您 | ← 简单鹤（置顶）
           从哪 | 从南 | ……          ← 豆包原生候选（顺延）
```

- 适用：豆包输入法 `com.bytedance.android.doubaoime`（实测 1.5.7）
- 环境：Android 8.0+，LSPosed / NPatch 内置
- 许可证：GPL-3.0（词库派生自 GPL-3.0 上游方案）

## 两个版本，怎么选

同一套代码，两个分支，**只有四码处理不同**。

| | 完整版（本分支） | 简码保留版 |
|---|---|---|
| 分支 | `main` | `single-only` |
| 版本 | **3.4** | **3.5** |
| 词库来源 | 上游 yaml 全量，**原样不改** | 生成时筛选四码 |
| 一简 / 二简 / 三简 | 全留 | 全留 |
| 四码：该码有单字 | **全挂**（字、词、符号） | 只挂单字与符号，词剔除 |
| 四码：该码无单字 | **全挂** | 保留词（`smuh → 什么时候`） |
| 四码候选位次 | **不动**，简单鹤在前、豆包顺延 | 首选是单字时，该字固定第 2、豆包首选第 1 |
| 编码 / 候选项 | 137,228 / 217,805 | 137,228 / 203,255 |
| 词库 / APK | 2.50 MB / 1.36 MB | 2.33 MB / 1.29 MB |
| 适合 | 想要完整码表、词都在候选栏第一屏 | 四码打单字更准，位次固定 |

- [下载 完整版 3.4](https://github.com/Ktouls/simplehe-doubao-ime/releases/download/v3.4-full/SimpleHe-DoubaoIME-3.4-full.apk)（本页）
- [下载 简码保留版 3.5](https://github.com/Ktouls/simplehe-doubao-ime/releases/download/v3.5-single/SimpleHe-DoubaoIME-3.5-single.apk)

两个版本签名相同（`c98b2032…`），可以互相覆盖安装，也可以按需来回切换。

## 完整版的行为

**不做任何位次调整**：简单鹤候选按词库原顺序铺开，豆包候选顺延。

```
jde   →  简单鹤                      三简
jd    →  简单 见到 觉得               二简
csn   →  悰 从你 从那 从您 …          三码，简单鹤在前
uiqk  →  鲥 豆包候选…                 四码，简单鹤首选仍在第 1
hvld  →  回来 会来 䏨 豆包候选…        四码，词也在，原顺序
smuh  →  什么时候 豆包候选…           四码只有词，原样
oyy   →  ① Ⅰ ㊀ （一） -1            符号
jdh   →  接电话 简单鹤 简单化 …        四码词，全都保留
```

想要「四码单字固定第 2、四码词不挂」的行为，用简码保留版。

---

## 一、它做了什么

只有三个 Hook 点。

| Hook | 作用 |
|---|---|
| `KeyboardJni.notifyCandidateBarSnapshot` | 候选栏：把该码下简单鹤的候选排在最前，豆包候选顺延 |
| `KeyboardJni.DoFunctionKey` / `DoCommit` | 空格：上屏简单鹤首选 |
| `KeyboardJni.onCandidateBarItemButtonUp` + `InputConnectionWrapper.commitText` | 点击：让上屏的字等于你点的那一格 |

### 为什么需要这些 Hook

豆包有几个细节，靠猜会一直做错，反编译 dex/smali 才能确定：

**1. 候选快照 37 个参数，编码和模式都在里面**

```
notifyCandidateBarSnapshot(J I [String [String [String [String String [I ... Z J I)V
```

| 下标 | 含义 |
|---|---|
| 2 | 候选词数组（显示用） |
| 6 | 当前编码串 |
| 8 | 候选 ID（与 arg2 等长） |
| 10 | 候选类型 |
| 18 | 偏移数组（长度通常是 `min(arg2,arg8,arg10)+1`） |
| 27 | `KeyboardInputMode`，`3` = 小鹤双拼 |

`args[27] == 3` 就是"现在是小鹤双拼"，每次刷新都带过来。
用它判断模式，不必反射 `SettingsConfigNext`、也不必读文件。

**2. 点击上屏走 native，改字符串没用**

```java
public native void onCandidateBarItemButtonUp(int, int, int, String, String, String, String, int, int, boolean, boolean);
```

真正的取词和上屏在 `libkeyboard.so`，它**按引擎自己的候选 ID** 取词。
Java 侧我们塞进 `args[2]` 的词、改过的 `expectedText`，对它只是标签。

所以"改标签"这条路原理上不通 —— 空格能成（走 `DoCommit`，Java 方法），
点候选不成，就是这个原因。

**3. `args[1]` 就是被点的格索引**

真机日志（`CLICKUP`）：

```
点第 1 格：[0]0 [1]0 [2]0 [3]"我"   [5]"w"
点第 2 格：[0]0 [1]1 [2]1 [3]"从你" [5]"csn"
点第 3 格：[0]0 [1]2 [2]2 [3]"达"   [5]"da"
```

有了索引，就不必用词去猜"用户点的是哪一格"。

### 点击对齐的做法

1. 候选快照时记录整屏 `shown[]`（我们画上去的每格文本）和当时的编码；
2. 点击抬起时，读 `args[1]` 作为格索引 → 目标词 = `shown[index]`；
3. **校验 `画面编码 == 当前编码`**，不符就不干预；
4. 在 `DoCommit`（Java 记账）和 `InputConnectionWrapper.commitText`
   （真正落到输入框）两处，把"豆包准备上屏的那个词"换成目标词。

第 3 步是 2.4 补上的关键校验。之前漏了它，`mwufme`（简单鹤无此码）会取到
上一帧 `mwuf` 的画面，把豆包的「没什么」替换成「美神」。

---

## 二、安装

### LSPosed

1. 安装 `apksigner` 签过的模块 APK；
2. LSPosed 里启用，作用域勾 `com.bytedance.android.doubaoime`；
3. **强停**豆包，重新打开，切到小鹤双拼。

### NPatch 内置

1. 把模块 APK 放进 NPatch 的模块列表，内置到豆包；
2. 安装内置产物，强停重开。

模块没有桌面图标、没有管理页，这是有意的（纯挂载，不占地方）。

---

## 三、配置

放 `/sdcard/Download/jdh_config`，改完**强停豆包重开**生效。
不存在时用默认值。

```ini
enable=1              # 总开关
min_exact_len=1       # 介入的最小码长，1 = 一简码也介入
merge_all=1           # 全塞：简单鹤在前，豆包顺延
max_total=24          # 单码候选上限（个别符号码有 89 个，必须封顶）
merge_prefix=0        # 没精确命中时用较短码顶上，默认关
click_fix=1           # 点击对齐
click_ttl_ms=2500
space_override=1      # 空格上屏简单鹤首选
space_ttl_ms=1500
debug=0               # 默认 0（只写 INIT/ERR），排查问题可改 1
```

1.x 的两个老开关仍然有效：`jdh_enable` 首行 `0` 关闭；`jdh_mode` 仅作记录。

日志：`/sdcard/Download/jdh_he.log`

| 标签 | 含义 |
|---|---|
| `EXACT` | 候选合并结果 |
| `CLICK` | 点击的格索引、显示词、引擎词 |
| `CLICKUP` | 不干预的原因 |
| `CLICKFIX` / `COMMITFIX` | 上屏对齐了 |
| `SPACEPUT` | 空格替换了 |
| `LONGSKIP` | 超长码交还豆包（带双拼切分，供分析） |

---

## 四、词库

词库**不在仓库里**（它是上游码表的衍生物，且每次生成 2.5 MB）。
用脚本从你的 Rime 方案目录生成：

```bash
python3 tools/gen_dict.py /path/to/rime-JDhe-CyIME assets/jdh_dict_multi.txt
# 输出：137,228 码 / 217,803 候选项
```

规则：

- 严格按 yaml **文件行序**累积，同码内保持首次出现顺序；
- 同 (码, 词) 去重；
- 丢弃含非小写字母的编码（反引号反查、分隔符不属于音形主码）。

`jde.dict.yaml` 191,096 行全是 2 列（没有权重列），**行序就是排序**，
不能重排 —— 这一点必须原样保留。

生成后放到 `assets/jdh_dict_multi.txt`，再打包。

---

## 五、构建

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk   # 需要 build-tools + platform android.jar
bash build.sh
```

流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 塞 assets → `zipalign` → `apksigner`。

`libs/api82.jar` 是 Xposed API，**只在编译期用，不能进 dex**（否则触发 Xposed 两铁律）。
`build.sh` 末尾会检查 dex 里没有 `de/robv/android/xposed` 类定义。

### 没有 aapt2 的机器

`tools/repack.py` 是备选路线：以一个已签名的旧 APK 为壳，替换 `classes.dex`
与 `assets/`，等长改写 AXML 里的 versionName，再 zipalign + apksigner。
适用于只改代码、不改资源的迭代（例如 aapt2 是 x86_64 二进制、跑在 arm64 设备上
会 `Exec format error` 的情况）。

签名：仓库**不含任何 keystore**。自己生成一个，覆盖安装需要与之前签名一致
（升级才不用卸载）。

```bash
keytool -genkeypair -v -keystore debug.keystore -alias jdh \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android -dname "CN=JDH, OU=Dev, O=Dev, C=CN"
```

---

## 六、已知限制

**长句中途用形码筛选：不做。**

双拼每个音节在字符串里是定长 2 字母（`wo|zd|ce|ui|yi|xx` = 我在测试一下），
简单鹤的"音节 + 形码"是 2 + 2 = 4 字母。于是

```
"1 个音节 + 2 形码" 与 "2 个音节" 在字符串上完全相同
cs|na  ==  csn|a
```

没有分隔符就无法可靠区分。硬做就是靠猜，猜错会把豆包的长句结果改坏，
所以这一档先不做。

`LONGSKIP` 会记录每个不同超长码的样本（带双拼切分，上限 60 条），
攒够数据再决定是否做"末段 4 码优先"的启发式。

**不做 PC 版的这些特性：**

| PC 版 | 为什么不做 |
|---|---|
| 辅助码提示 | 豆包候选只有"词 + 注释"两个视觉位，注释还要跟候选类型竞争 |
| 字根拆分 `[扌斥]` | 需要 OpenCC 滤镜 + `ChaiPUA` 字体，豆包候选栏字体不可控，会出方框 |
| `⌃` 简快码提示 | 占豆包的注释位 |
| `//` 造词 | 要写豆包的用户词典，只会污染它的自学习 |
| 反引号反查 / 部件拆字 | 需要 Rime 的 `recognizer` 和独立词典，豆包没有对应通道 |
| 固词 / 置顶造词 | 那是 Rime 的候选重排，豆包排序是整句概率模型 |

一句话：**在豆包候选栏里能自然表达的，只有"哪些词排前面"。**

---

## 七、版本历史

| 版本 | 关键改动 |
|---|---|
| 1.0–1.2 | 只写候选数组第 0 格；空格用 1200ms 时间窗；模式靠反射 + 读 `/sdcard` |
| 2.0 | 只改第 0 格，其余交还豆包（过度保守） |
| 2.1 | 全塞：简单鹤 + 豆包顺延；改用 `args[27]` 判模式；词库后台加载 |
| 2.2 | 触摸按下时冻结画面，用配对表对齐上屏 |
| 2.3 | 确认点击走 native；用 `args[1]` 格索引；在 `commitText` 上对齐 |
| 2.4 | **修跨编码误替换**：所有放手路径作废画面，点击前校验编码一致 |
| 2.5 | **修非汉字原生候选漏替换**：记录豆包原生第 0 格，修复 `of`/`or`/`oa`/`on` 快捷符号与 `sm`（`SM`）空格/点击不上屏简单鹤首选的问题，并在 `commitText` 补空格兜底 |
| 2.6 | **性能优化**：`HeLog` 改为后台单线程异步写盘并默认关闭过程日志；`merge()` 去除 `HashSet`/`ArrayList` 热路径分配；词库改为单遍手写切分加载、消除启动 `NPE` 并跳过后台子进程 |
| 2.7 | **深层优化与加固**：词库改为 `int[]` 编码索引 + 单一 `byte[]` 池（仅 3 个常驻数组、~3.2MB 内存，消除 ~50 万堆对象 GC 负担）；修复去重后 `args[3]`/`args[4]` 辅助串错位；精确 Hook 5 参 `DoCommit`；跨码清理残留状态并收窄尾格；仅放行输入法主进程 |
| 2.8 | 修长句分段选词错位：点击候选后若未触发上屏且剩余编码缩短，自动锁定分段选词态并将后续尾段全程交还豆包，直至整句上屏或清空编码 |
| 2.9 | 修 `oyy` 丢候选：`gen_dict.py` 的「清理 ① 占位」曾误删 `oyy` 的正规 `①`；新增 `RESTORE` 表只补回 `oyy`，四码冗余位保持删除 |
| **3.0** | **完整版**：重新生成词库，修复 `ofyu` 补回符号组里的 ①（137,228 码）|
| 3.1（简码保留分支） | 四码若含扩展生僻字，主表候选一并挂上、生僻字垫底 |
| 3.2（简码保留分支） | 四码生僻字自动让位：首选是生僻字时豆包首选占第 1、简单鹤字排第 2 |
| 3.3（简码保留分支） | 四码单字一律让位：取消常用字判断，首选只要是汉字单字（不分常用/生僻）就固定排第 2 |
| **3.4** | 与简码保留版同批发布、README 区分介绍。完整版行为保持 3.0 原样：**词库优先、豆包顺延，不做位次调整** |
| 3.5（简码保留分支） | 四码该码没有单字时保留词（如 `smuh → 什么时候`），选词规则细化 |

---

## 八、许可证与致谢

- 本项目：**GPL-3.0**（因为分发的词库派生自 GPL-3.0 的上游方案）
- 码表作者：**简单**
- 上游方案：[rimeinn/rime-JDhe](https://github.com/rimeinn/rime-JDhe)（GPL-3.0）
- 安卓适配版：[Ktouls/rime-JDhe-CyIME](https://github.com/Ktouls/rime-JDhe-CyIME)
- PC 参考：[Fun10165/rime-JDhe-win](https://github.com/Fun10165/rime-JDhe-win)
- 豆包输入法：字节跳动，本项目与官方无关

逆向与 Hook 仅用于个人输入习惯的本地适配，不分发豆包任何代码或资源。