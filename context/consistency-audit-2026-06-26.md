# 项目一致性审查 — 2026-06-26

## 审查范围
经 12 轮 AI 修改（累计 147 个 bug 修复）后的全项目一致性检查。
4 个维度并行审查：数据库 schema、资源引用与 Manifest、客户端与服务端 API、import 与代码一致性。

## 不匹配清单（按严重程度分级）

### P0 致命（编译失败 / 运行崩溃）

**P0-1 ProfileFragment 引用 6 个未定义的 R.id（clean build 必崩）**
- 文件: [ProfileFragment.java:154-174](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ProfileFragment.java)
- 未定义 id: menuSubscription / menuExport / menuExportDiary / menuImportDiary / menuPreferences / menuAbout
- 现状: 当前能运行是依赖 `app/build/intermediates/` 中残留的旧 fragment_profile.xml 缓存（旧布局含 @+id/menuSubscription 等声明）。当前 src 主线 `fragment_profile.xml` 已重构为动态菜单但旧 id 未迁移到 `ids.xml`
- 修复方向: 新建 `res/values/ids.xml` 声明这 6 个 `<item name="..." type="id"/>`，或回退 fragment_profile.xml

### P1 严重（功能异常 / 资源泄漏 / 安全风险）

**P1-1 summaries 表迁移漏读 prompt_version 列**
- 文件: [DiaryDbHelper.java:330-333](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DiaryDbHelper.java)
- migrateIfNeeded 读 6 列，未含 COLUMN_PROMPT_VERSION。旧库迁移后 prompt_version 落到 DEFAULT 0，与 CURRENT_PROMPT_VERSION=2 不符，触发全部总结重新生成

**P1-2 daily_usage 同步响应缺 `updated` 字段**
- 客户端: [ApiClient.java:286-287](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ApiClient.java) 读 `synced` + `updated`
- 服务端: [sync.py:165](file:///d:/Coding/AISummary/LifeTrack/server/sync.py) 仅返回 `synced`（空记录路径才返回 updated）
- 后果: SyncCallback.onSuccess 拿到的 updated 永远为 0

**P1-3 KeyManager 收到 401 未联动清除登录态**
- 文件: [KeyManager.java:114-120](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/KeyManager.java)
- token 过期后仅清空本地 keys + DeepSeekClient 缓存，未调用 ApiClient.clearAuth/setLoggedIn(false)/notifyAuthExpired
- 后果: AI 功能立即失效但 UI 仍显示"已登录"，用户困惑

**P1-4 客户端未实现删除账号功能**
- 服务端: [auth.py:138](file:///d:/Coding/AISummary/LifeTrack/server/auth.py) 定义 `POST /api/auth/delete`
- 客户端: ApiClient 无对应方法，全工程无 `auth/delete` 调用

**P1-5 构建脚本硬编码签名密钥库口令**
- 文件: [app/build.gradle.kts:24,26](file:///d:/Coding/AISummary/LifeTrack/app/build.gradle.kts)
- 默认值 `"zzl0529"` 明文写入版本库，与 lifetrack-release.jks 配合可伪造签名

**P1-6 ApiClient 使用裸 new Thread()（3 处）**
- 文件: [ApiClient.java:87,130,239](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ApiClient.java)
- 与 AppExecutors 统一线程池约定不一致

**P1-7 DoubaoClient / TrackFragment 自建独立线程池**
- [DoubaoClient.java:39](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DoubaoClient.java) static imageExecutor
- [TrackFragment.java:92](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/TrackFragment.java) preloadExecutor
- 未走 AppExecutors，缺统一背压与命名

**P1-8 Handler 清理不彻底（3 处）**
- [RecordFragment.java:1052](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/RecordFragment.java) 仅 removeCallbacks 指定 Runnable
- [DiaryFragment.java:297](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DiaryFragment.java) 同上
- [MainActivity.java:65,86,94](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/MainActivity.java) 前 3 处只移除单一 Runnable，仅 onDestroy 全清

**P1-9 DiaryDbHelper 空 catch 块吞掉关闭异常**
- 文件: [DiaryDbHelper.java:382](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/DiaryDbHelper.java)
- `try { oldDb.close(); } catch (Exception ignored) {}` 无日志，close 失败可能文件句柄泄漏

**P1-10 ProfileFragment.btnSettings 死按钮**
- 布局: [fragment_profile.xml:33](file:///d:/Coding/AISummary/LifeTrack/app/src/main/res/layout/fragment_profile.xml)
- ImageView 设了 clickable=true 但 ProfileFragment.java 无 R.id.btnSettings 引用
- 用户点击齿轮图标无反应

### P2 中等（潜在隐患 / 死代码 / 风格不统一）

**资源/Manifest**
- AndroidManifest 缺少 POST_NOTIFICATIONS 权限（target SDK 36）
- backup_rules.xml 与 data_extraction_rules.xml 内容全为注释，allowBackup=true 可能备份敏感 SP
- file_paths.xml 仅声明 Pictures，未来分享音频会触发 IllegalArgumentException
- values-night 缺少 styles.xml
- SubscriptionActivity 硬编码中文与价格（planNames/planPrices 数组）
- 死布局: dialog_subscription.xml、dialog_verify_success.xml
- 死颜色: brown_accent_dark / green_primary_dark / bg_nav_bar / nav_divider
- 死尺寸: menu_icon_size / card_radius / icon_md / icon_sm

**数据库**
- migrateIfNeeded 读 daily_usage 7 列对 v5 旧库不友好（旧库只 2 列，整表迁移失败）
- onUpgrade v6 与 v8 的 ALTER TABLE 异常处理风格不一致

**API/网络**
- 硬编码 IP 重复: [ApiClient.java:23](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ApiClient.java) + [ProfileFragment.java:60](file:///d:/Coding/AISummary/LifeTrack/app/src/main/java/com/example/lifetrack/ProfileFragment.java)
- ProfileFragment 用 HttpURLConnection，与项目 OkHttp 风格脱节
- 服务端 4 个端点客户端未调用: /api/sync/sessions、/api/sync/features、/api/stats/overview、/api/stats/daily
- 全链路 HTTP 明文（注册/登录密码明文）
- /api/keys 默认回退到硬编码真实 API Key（config.py:8-9）
- admin 端点用 query param 传密码（stats.py 4 处）

**代码冗余**
- TrackFragment 3 个常量未使用: PREFS_PROFILE / KEY_API_KEY / KEY_API_VERIFIED
- 服务器地址常量重复定义（ApiClient + ProfileFragment）
- PREFS_PROFILE 三处重复定义（TrackFragment 未用 + ProfileFragment + SharedPreferencesHelper）
- crypto SP 文件名重复定义（CryptoHelper + EncryptedPrefsHelper）
- AIHttpUtils.getNetworkErrorMessage 公共方法无调用方
- AnalyticsDao.getSessionCount 无调用方且实现误导（返回 daily_usage 行数而非 sessions）
- DiaryDao.getAll() 返回 2 元素数组，其他查询返回 3 元素，API 形状不统一
- RecordFragment.onDestroyView 未将 recordingHandler 字段置 null

## 已验证一致（无问题项）

- DATABASE_VERSION=9，onUpgrade 1→9 连续无跳号
- 7 张业务表列名在所有 DAO 引用全部正确，无引用不存在列
- UNIQUE 约束与 CONFLICT_REPLACE 策略匹配
- SQLCipher 全部使用 net.zetetic.database.sqlcipher 新包，无旧包残留
- 无 androidx/support 混用，Fragment 全部 androidx.fragment.app.Fragment
- 回调接口契约（DeepSeekClient/DoubaoClient 各 *Callback）签名一致
- 5 个 Activity 在 Manifest 与 Java 类一一对应，launcher 在 SplashActivity
- 权限声明与 RecordFragment 实际使用匹配（CAMERA/RECORD_AUDIO/READ_MEDIA_IMAGES）
- FileProvider authorities 与 RecordFragment 调用一致
- R.layout/R.string/R.color/R.drawable Java 引用均存在（除 P0-1 的 R.id 例外）
- bottom_nav_menu.xml 与 MainActivity R.id.nav_* 一致
- libs.versions.toml 与 build.gradle.kts 依赖版本对齐
- 客户端 5 个端点与服务端路由路径/HTTP 方法匹配
- API Key 分发字段名（deepseek_key/doubao_key）一致
- requirements.txt 与代码 import 一致
