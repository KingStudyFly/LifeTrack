# LifeTrack — Project Context

- **What it is:** Android 智能生活记录与追踪应用，结合 DeepSeek（日期分析/总结生成）和豆包（图片分析/语音识别）两大 AI 服务
- **Who it's for:** 个人用户，用于日记记录、周期性回顾、趋势追踪和洞察问答
- **Key differentiator:** 双 AI 服务集成 + 结构化洞察积累 + 趋势追踪 + 数据库加密
- **Current stage:** 已发布，持续迭代中
- **Current goals:** 深化日记内容利用效率，完善洞察和趋势系统

## Tech Stack

| 层级 | 技术 |
|-----|------|
| Android 语言 | Java 11 |
| Android 构建 | Gradle 9.4.1 / AGP 9.2.0 |
| Min SDK / Target SDK | 26 / 36 |
| 网络库 | OkHttp（default/sse/longTimeout 三实例） |
| 数据库 | SQLCipher 加密 SQLite，版本 7 |
| 安全 | Android KeyStore + AES/GCM 加密 DB 密钥 |
| 认证存储 | EncryptedSharedPreferences |
| 后端 | Python Flask + Gunicorn + Nginx |
| 后端数据库 | SQLite WAL 模式 |
| 后端认证 | JWT (PyJWT) + bcrypt |
| Dashboard | Chart.js 4.4.0 |

## Architecture

```
SplashActivity → LoginActivity → MainActivity
                                    ├── RecordFragment (图片/语音/文字输入)
                                    ├── TrackFragment (周期总结 + 趋势图)
                                    ├── DiaryFragment (日历视图)
                                    ├── InsightFragment (洞察问答)
                                    └── ProfileFragment (个人中心)

Business: DeepSeekClient | DoubaoClient | ApiClient | KeyManager
Data: DiaryDbHelper → DiaryDao | SummaryDao | AnalyticsDao | InsightDao | TrendDao
Security: CryptoHelper | EncryptedPrefsHelper
Network: OkHttpHelper (3 client variants)
```

## Database Schema (v7, 7 tables)

| 表 | 用途 | UNIQUE 约束 |
|----|------|-------------|
| diary | 日记 (timestamp, content) | - |
| summaries | 周期总结 (period_type, period_key, title, content, entry_count) | (period_type, period_key) |
| sessions | 会话追踪 | - |
| feature_usage | 功能使用追踪 | - |
| daily_usage | 每日使用统计 (date PK, foreground_seconds, app/record/track/diary/profile_opens) | date |
| insights | 结构化洞察 (key_persons, key_projects, key_events, emotion_themes, decisions) | (period_type, period_key) |
| trend_metrics | 趋势指标 (mood/energy/social/work_pressure/health_score, 1-10 REAL) | (period_type, period_key) |

## Key Files Quick Reference

| 文件 | 核心职责 |
|------|---------|
| DeepSeekClient.java | 日期分析、总结生成、洞察+趋势提取、时间范围分析、问答、日记压缩 |
| DoubaoClient.java | 图片流式分析（单张/批量并发）、语音识别（Responses API） |
| ApiClient.java | 后端认证/同步、EncryptedSharedPreferences 存储 JWT |
| KeyManager.java | 从服务端获取 API Key，缓存到 EncryptedSharedPreferences |
| CryptoHelper.java | Android KeyStore 管理 DB 加密密钥，明文→加密迁移 |
| TrackFragment.java | 6 种周期总结、缓存策略、趋势图 Canvas 渲染、预加载 |
| InsightFragment.java | 洞察问答、日记压缩（>50000 字按月分块压缩）、流式回答 |
| RecordFragment.java | 图片/语音/文字输入、Flashback 回忆卡片 |

## API Key Management

API Key 不再硬编码在 BuildConfig 中，而是通过 KeyManager 从服务端 `/api/keys` 端点获取，缓存到 EncryptedSharedPreferences。KeyManager.init() 在应用启动时调用。

## Data Sync Flow

1. 客户端 AnalyticsDao 记录 daily_usage 到本地 SQLite
2. MainActivity 每 1 分钟通过 ApiClient.syncAnalytics() 同步到服务端
3. 服务端 `/api/sync/daily_usage` 接收并存储
4. Dashboard 展示用户统计

## Period Types

| 位置 | 名称 | period_type | period_key 格式 |
|-----|------|------------|----------------|
| 0 | 每日总结 | daily | yyyy-MM-dd |
| 1 | 每周总结 | weekly | yyyy-Www |
| 2 | 月度总结 | monthly | yyyy-MM |
| 3 | 季度总结 | quarterly | yyyy-Qn |
| 4 | 年度总结 | yearly | yyyy |
| 5 | 任意时段 | custom | start_end |

## Constraints

- 所有 AI 请求均为异步（new Thread），回调切回 UI 线程
- DB 写入禁止在 UI 线程
- Insight Q&A 回复纯文本，无 Markdown
- 日记超 50000 字需压缩至 40000 字以内
- SSE 流式解析统一模式：readLine → data: 前缀 → [DONE] 结束
