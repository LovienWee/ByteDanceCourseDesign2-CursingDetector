# CursingDetector —— 本地视频脏话检测 Demo（Android / Java）

本项目为 **客户端训练营 - 作业二：CursingDetector 视频脏话检测 Demo**。

实现了一条完整的处理链路：

> **本地视频文件 → 提取音轨 → 语音转文字（多种引擎可切换） → 英文脏话检测 →
> 高亮展示脏话片段 + 统计结果**

---

## ✨ 功能特性（Features）

### 1. 本地视频导入与播放（ExoPlayer）

* 使用系统文件选择器从本地选择一个视频文件（mp4 等）。
* 使用 **ExoPlayer + StyledPlayerView** 播放视频。
* 支持：

  * 播放 / 暂停
  * 进度拖动
  * 全屏 / 退出全屏（通过自定义按钮切换 `playerContainer` 布局高度）
* 横竖屏切换通过 Manifest 的 `configChanges` 接管，避免播放器被重建。

---

### 2. 多引擎语音转文字（SpeechToTextEngine 抽象）

项目中封装了一个统一接口：

```java
public interface SpeechToTextEngine {
    void start(Context context, Uri videoUri, ResultCallback callback);
    void stop();
}
```

并提供了多种实现：

* `FakeSpeechToTextEngine`

  * **纯本地模拟**，不请求网络。
  * 固定在几个时间点“假装”识别出几句台词，其中有脏话也有正常句子。
  * 非常适合在没有配置任何 Key 的情况下，完整跑通整条流程。

* `BaiduSpeechToTextEngine`

  * 使用 **Baidu 语音识别 REST API**。
  * 通过 `AudioExtractor` 提取音频、解码成 PCM，再用 `WavHeaderPatcher` 补写 WAV 头，符合百度接口要求。
  * 利用 `BaiduAuth` 获取 `access_token`。
  * 支持 **两种模型**（通过 `devPid` 控制）：

    * 英文模型（示例中使用 `1737`）
    * 中文普通话模型（示例中使用 `1537`）

* `CloudSpeechToTextEngine`

  * 为指向 **OpenAI Whisper** 风格的接口。
  * 通过 `OkHttp` 构造 multipart/form-data 上传音频文件，解析返回 JSON。
  * 在当前版本中，代码已经写好，但受限于网络通信以及需要付费调用，可以通过配置openai api key进行调用。

在 `MainActivity` 中，通过一个内部枚举来统一管理当前选择的引擎：

```java
private enum EngineType {
    BAIDU_EN,   // 百度英文模型
    BAIDU_ZH,   // 百度中文模型
    OPENAI,     // OpenAI引擎（预留）
    FAKE        // 本地模拟
}
```

顶部有一个 `Spinner`（使用 `R.array.engine_names`），用于切换当前引擎，并在状态栏展示：

* 当前引擎：百度英文模型
* 当前引擎：百度中文模型
* 当前引擎：OpenAI（可能受网络限制）
* 当前引擎：本地模拟结果

真正开始检测时，会调用 `createEngine()` 按当前选项 new 对应的引擎实例。

---

### 3. 英文脏话检测（ProfanityDetector）

语音转文字得到的是一组 `TranscriptSegment`：

```java
public class TranscriptSegment {
    public double startSec;
    public double endSec;
    public String text;
}
```

项目内实现了一个 **规则驱动、基于正则表达式的英文脏话检测器**：

```java
public class ProfanityDetector {
    // Rule 中包含基础词 + Pattern
    // 例如：fuck / fucking / fuckin / shit / shitty / asshole / damn / bitch...
}
```

特点：

* 按 **完整单词** 匹配，避免把 `hello` 误识别成 `hell`。
* 处理常见派生词，如：

  * `fuck`, `fucking`, `fuckin`
  * `shit`, `shitty`
  * `ass`, `asshole`, `assholes`
  * `bitch`, `bitches`
* 每次识别到一条台词，会对整句文本跑一遍规则，输出若干 `ProfanityHit`：

```java
public class ProfanityHit {
    public String word;       // 命中的脏话基础词
    public double startSec;   // 片段起始时间
    public double endSec;     // 片段结束时间
    public String lineText;   // 整句原文
}
```

---

### 4. 脏话统计与列表展示（ProfanityStats + RecyclerView）

* 所有命中的 `ProfanityHit` 会被汇总到 `ProfanityStats` 中：

  * `totalCount`：总脏话次数
  * `perWordCount`：每种基础词的次数统计
  * `hits`：所有命中列表（用于 UI 展示）

* UI 侧使用了一个 `RecyclerView` 列表，适配器为 `ProfanityHitAdapter`：

  * 每一行展示：

    * 脏话关键词 + 时间区间（例如 `fuck  00:20.5 - 00:21.5`）
    * 对应的整句台词文本。
  * 点击任意一行会触发回调：

    * 跳转播放器进度到该片段起点附近。
    * 同时配合静音逻辑（见下一条）。

* 顶部状态区域会显示当前统计信息，如：

  * 是否已检测完成
  * 当前累计的脏话次数
  * 使用的是哪个引擎。

---

### 5. 点击列表一键静音脏话片段

项目预留了一个简单但非常好玩的功能：

> 点击某一条脏话记录 → 播放器跳过去播放 → **在该时间段自动静音**，播放完后自动恢复音量。

核心逻辑：

* 在点击某个 `ProfanityHit` 后，调用：

```java
private void muteForSegment(double startSec, double endSec) {
    exoPlayer.setVolume(0f);
    currentMuteEndSec = (exoPlayer.getCurrentPosition() / 1000.0) + duration;
    startVolumeMonitor();
}
```

* `startVolumeMonitor()` 会每 200ms 检查当前播放进度，当超过 `currentMuteEndSec` 时，把音量恢复为 1f，并重置状态。

---

### 6. 结束时的统计弹窗

* 检测流程结束后，可以通过 `ProfanityStats.buildSummary()` 生成一段统计文案，包括：

  * 总脏话次数
  * 每个词被说了几次
* UI 里使用 `AlertDialog` 弹出“本局脏话统计”对话框，作为一局视频检测的总结。

---

## 🏛 架构设计（Architecture）

整体上是一个 **单 Activity + 多工具类** 的轻量结构，核心是把几个关键职责解耦：

```
UI 层（MainActivity）
    ├─ 播放器控制（ExoPlayer + StyledPlayerView）
    ├─ 引擎选择（Spinner + EngineType）
    ├─ 结果展示（RecyclerView + TextView）
    └─ 静音控制 + 对话框

业务逻辑
    ├─ SpeechToTextEngine 接口  → 多种 STT 实现（Fake / Baidu / Cloud）
    ├─ ProfanityDetector       → 对 TranscriptSegment 做脏话规则判定
    └─ ProfanityStats          → 统计汇总 & 生成摘要文案

工具层
    ├─ AudioExtractor          → 从视频中提取音频为 PCM
    └─ WavHeaderPatcher        → 补写 WAV 头以符合 Baidu 要求
```

---

## 📂 项目结构（Project Structure）

```text
app/src/main/java/com/example/cursingdetector
│
├── model/
│   ├── ProfanityHit.java           # 单次脏话命中信息
│   └── TranscriptSegment.java      # 一段识别后的语音文本
│
├── profanity/
│   ├── ProfanityDetector.java      # 正则规则驱动的英文脏话检测器
│   └── ProfanityStats.java         # 统计与摘要生成
│
├── stt/                            # Speech To Text 模块
│   ├── SpeechToTextEngine.java     # 抽象接口
│   ├── FakeSpeechToTextEngine.java # 本地模拟引擎（无需网络）
│   ├── BaiduAuth.java              # 获取 Baidu access_token
│   ├── BaiduSpeechToTextEngine.java# 调用 Baidu 语音识别 REST API
│   └── CloudSpeechToTextEngine.java# 调用 OpenAI Whisper 接口
│
├── ui/
│   └── player/
│       ├── MainActivity.java       # 唯一 Activity，负责 UI + 流程协调
│       └── ProfanityHitAdapter.java# RecyclerView 适配器
│
└── util/
    ├── AudioExtractor.java         # 提取音轨 & 解码为 PCM
    └── WavHeaderPatcher.java       # 补写 WAV 文件头
```

资源文件结构（简略）：

```text
app/src/main/res
├── layout/
│   ├── activity_main.xml           # 主界面布局（播放器 + 状态 + 列表）
│   └── item_profanity_hit.xml      # 脏话记录列表单行布局
├── values/
│   ├── strings.xml                 # 文案字符串（app 名等）
│   ├── colors.xml                  # 主题颜色
│   ├── themes.xml                  # 主题样式
│   └── arrays.xml                  # 引擎名称数组（Spinner 用）
└── mipmap-*/ & drawable/           # 图标资源
```

---

## 🔧 技术栈（Tech Stack）

| 技术 / 库                                     | 用途                        |
| ------------------------------------------ | ------------------------- |
| Java + Android View                        | UI 开发                     |
| ExoPlayer (`com.google.android.exoplayer`) | 视频播放（本地文件）                |
| Material Components (`MaterialToolbar` 等)  | 顶部 AppBar、按钮等             |
| RecyclerView                               | 脏话命中列表展示                  |
| OkHttp (`com.squareup.okhttp3:okhttp`)     | 调用 Baidu / OpenAI HTTP 接口 |
| MediaExtractor + MediaCodec                | 提取与解码视频音轨                 |

---

## 🚀 运行方式（How to Run）

1. **克隆或解压项目**

   * 从 GitHub 或本地 zip 打开 `CursingDetector` 根目录。

2. **使用 Android Studio 打开项目**

   * 等待 Gradle 同步完成。

3. **配置语音识别 Key**

   * 打开 `MainActivity.java` 中的 `createEngine()` 方法。
   * 将示例中的 `baiduApiKey` / `baiduSecretKey` 替换成你自己的 Baidu 语音识别 Key（项目中的Key已注销）
   * 如果要尝试 OpenAI API，在 `CloudSpeechToTextEngine` 中配置自己的 API Key（目前代码中已经预留）。

4. **选择默认引擎的推荐顺序**

   * 想先验证整体流程：选择 **“本地模拟”**（Fake 引擎），不需要任何网络或 Key。
   * 想看真机识别效果：选择 **百度英文 / 中文模型**，确保网络畅通并填好 Key。

5. **运行应用**

   * 连接模拟器或真机。
   * 点击 Run（▶）。

6. **体验流程**

   * 点击 “选择本地视频” 按钮，选一个带语音的视频。
   * 选择引擎 → 点击“开始检测”（或项目中对应按钮）。
   * 等待识别 + 检测过程：

     * 上方播放器正常播放视频。
     * 下方列表陆续出现“脏话记录”。
   * 点击任意一条记录：

     * 播放器跳到对应时间。
     * 该时间段自动静音，播放完成后恢复音量。
   * 检测结束后点击按钮或等待触发“统计弹窗”，查看本局脏话汇总。

---

## 📷 截图展示（Screenshots）

![alt text](image.png)

---

## 🐛 关键问题与解决（Debug Notes）

1. **音频提取与 Baidu 接口格式不匹配**

* 问题现象：
  Baidu 语音识别接口返回报错，提示音频格式不正确或长度异常。
* 问题原因：
  直接把 MediaExtractor 解出的 PCM 数据当成 WAV 传给接口，缺失正确的 WAV 头信息。
* 解决方案：
  使用 `WavHeaderPatcher` 手动写入标准 WAV 头（PCM 16bit, mono, 16kHz），再上传。

2. **本地视频没有音轨 / 非音频轨道**

* 问题现象：
  `AudioExtractor` 遍历 track 时找不到 `audio/` 开头的 MIME，抛出异常。
* 解决方案：
  在 `AudioExtractor` 中检查是否找到音频轨道，否则抛出清晰的错误；UI 层进行捕获并提示用户换一个视频。

3. **网络调用异常导致 UI 卡死**

* 问题现象：
  如果在主线程尝试做网络 I/O，会导致界面卡死甚至 ANR。
* 解决方案：
  无论是 Baidu 还是 Cloud 引擎，`start()` 内部均使用 **新线程** 执行网络逻辑，通过回调接口把结果抛回主线程。

---

## 🧭 后续扩展方向（Future Work）

* 支持 **完整的 OpenAI Whisper / 其他云厂商 STT**，并通过 UI 动态配置 API Key。
* 扩展 **中文脏话规则库**，实现中英双语综合检测。
* 增加：

  * “只静音脏话一小段”的更精细时间控制
  * 自动给视频生成“脏话时间轴标记”
  * 导出检测报告（JSON / TXT）
* 将当前规则式检测替换为 **本地小模型 / 远程大模型分类 API**，做更复杂的内容安全分析。

---
## 📄 License

Copyright © 2025 LovienWee.

本项目采用 **GPL v3** 开源协议。

这意味着你可以自由使用、修改和分发本软件，但任何基于本项目的衍生作品也必须以相同的开源方式发布。

详见 [LICENSE](LICENSE) 文件。