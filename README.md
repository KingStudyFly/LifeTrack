# LifeTrack

**Android 智能生活记录与追踪应用：日记、周期总结、趋势追踪与 AI 洞察问答，本地数据全程加密。**

LifeTrack 是一款用 Java 编写的 Android 智能生活记录应用，把日记记录、周期性回顾、趋势追踪和洞察问答合在一个 App 里。它接入了两个 AI 服务：DeepSeek 负责日期分析、总结生成、洞察问答和日记打标签，豆包（火山引擎 ARK）负责图片文字提取和语音转写。数据库使用 SQLCipher 加密，密钥由 Android KeyStore 管理，本地数据全程加密。

## 功能特性

- 多模态日记记录：支持图片（单张或批量，最多 20 张）、语音（录音转写，AAC 格式，最长 5 分钟）和文字三种输入方式
- 日期分析：从日记内容中识别日期，支持一次记录多日拆分
- 维度分析：拆分日期时一并提取运动、睡眠、社交三个维度并打分
- 周期总结：提供日、周、月、季、年和自定义六种周期，按周期粒度切换提示词生成回顾总结
- 洞察问答：基于日记内容的多轮对话，带追问建议和上下文压缩
- 标签系统：AI 自动打标签，内置 20 个预置标签，支持回顾时选取
- 趋势追踪：以图表方式呈现各维度与情绪的变化趋势
- 待办与桌面小组件：待办清单支持桌面小组件直接查看
- 定时提醒：到点发送通知，设备重启后自动重新调度
- 使用统计：统计各页签打开次数与前台时长，并提供近 7 天数据
- 数据加密：SQLCipher 加密数据库（11 张表），密钥由 KeyStore 托管，敏感配置使用 EncryptedSharedPreferences
- 导出导入：日记以 JSON 格式通过系统文件选择器（SAF）导出和导入，导入走事务批量写入

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 11 |
| 构建 | Gradle / AGP | 9.4.1 / 9.2.x |
| Min / Target SDK | Android 8.0 / 36 | 26 / 36 |
| 网络 | OkHttp（含 SSE 模块） | 4.12.0 |
| 数据库 | SQLCipher 加密 SQLite | 4.6.0 |
| 安全存储 | EncryptedSharedPreferences | 1.1.0 |
| UI | Material Design + BlurView 毛玻璃 | 1.10.0 / 3.2.0 |
| AI 服务 | DeepSeek + 豆包（火山引擎 ARK） | — |

AI 的流式响应通过 SSE 实时展示，网络请求统一由 OkHttp 客户端工厂管理。

## 快速开始

1. 用 Android Studio 打开仓库根目录（Gradle 会自动下载 9.4.1 发行版）；
2. 在根目录创建 `local.properties`（该文件已被 .gitignore 排除，不会提交），填入 AI 服务凭证：

```properties
DEEPSEEK_API_KEY=你的 DeepSeek API Key
DOUBAO_API_KEY=你的豆包（火山引擎 ARK）API Key
```

3. 连接 Android 8.0（API 26）及以上真机或模拟器，运行 `app` 配置；
4. 首次启动进入登录页，当前版本为本地简化校验（输入非空即可进入），不依赖后端服务。

构建正式 release 包时，如需正式签名，在 `local.properties` 中追加 `KEYSTORE_PASSWORD` 和 `KEY_PASSWORD`，并将签名文件 `lifetrack-release.jks` 放在根目录；未配置时自动回退 debug 签名。

针对 Android 15+ 的 16 KB 页面设备，仓库提供了 `align_elf_16kb.py` 脚本和 `alignElf16kb` Gradle 任务，打包时会自动对齐第三方 .so 的 ELF LOAD 段。

## 项目结构

根目录主要文件与目录说明如下：

- **app/** — Android 主模块：全部源码（48 个 Java 文件）、资源（31 个布局）与模块级构建脚本
- **gradle/** — Gradle Wrapper 与版本目录（libs.versions.toml，统一管理依赖版本）
- **build.gradle.kts / settings.gradle.kts / gradle.properties** — 项目级构建配置
- **gradlew / gradlew.bat** — Gradle Wrapper 启动脚本
- **align_elf_16kb.py** — ELF LOAD 段 16 KB 对齐脚本，供 Gradle 任务调用
- **CODE_WIKI.md** — 项目代码百科：架构图、各层职责、核心类与关键逻辑的完整说明
- **context/** — 23 篇开发上下文文档，记录 AI 集成、安全架构、深洞察系统及历次修复的决策过程
- **reports/** — 竞品调研报告
- **figma_export/ / figma_screenshot.js** — 设计稿导出资产与截图脚本
- **example.png** — 应用截图
- 其余 `*.md` / `*.docx` — 浮墨笔记（flomo）对标改进、情绪可视化等专题调研报告

源码位于 `app/src/main/java/com/example/lifetrack/`，按职责分为：Activity 层（Splash、Login、Main、UsageDetail、NotificationList）、Fragment 层（Record、Track、Diary、Insight、Profile 五个主页面）、业务层（DeepSeekClient、DoubaoClient、AIHttpUtils、OkHttpHelper、DialogHelper）、数据层（DiaryDbHelper 及七个 DAO）和安全层（CryptoHelper、EncryptedPrefsHelper、KeyManager）。

## 已知说明

- 仓库内不包含后端代码，登录页当前为本地简化校验；
- 两个 AI 服务的 API Key 均从 `local.properties` 经 BuildConfig 注入，不进入版本库；
- 语音转写依赖豆包 ASR，图片分析依赖豆包 Vision，均需配置 `DOUBAO_API_KEY` 才能使用。

## 相关文档

详细的架构与代码说明见 [CODE_WIKI.md](CODE_WIKI.md)，开发决策过程见 [context/](context/) 目录。

## License

暂未声明 License，保留所有权利。
