# Security Architecture

## 2026-06-14 — 安全架构详解

### 数据库加密

- 使用 SQLCipher 加密 SQLite 数据库
- 库从 `net.zetetic:android-database-sqlcipher:4.5.4` 迁移到 `net.zetetic:sqlcipher-android:4.16.0`（支持 16 KB 页面对齐）
- 包名从 `net.sqlcipher.database` 改为 `net.zetetic.database.sqlcipher`
- `SQLiteDatabase.loadLibs(context)` 改为 `System.loadLibrary("sqlcipher")`
- 密码从 `getWritableDatabase(key)` 改为构造函数传入
- androidx.sqlite 从 2.2.0 升级到 2.6.2
- 加密密钥由 CryptoHelper 管理：
  1. 首次启动生成 32 字节随机密钥
  2. 使用 Android KeyStore 中的 AES-256 主密钥加密 DB 密钥
  3. 加密后的 DB 密钥 + IV 存储到 SharedPreferences (`lifetrack_crypto`)
  4. DB 密钥缓存到 volatile 变量，避免重复解密
- 明文→加密迁移：DiaryDbHelper.migrateIfNeeded() 在首次检测到未加密 DB 时执行
  - 读取所有表数据到内存
  - 重命名旧 DB 文件为 .bak
  - 创建新的加密 DB 并写入数据
  - 成功后删除 .bak，标记 db_encrypted=true

### API Key 管理

- 不再硬编码在 BuildConfig 中
- KeyManager 从服务端 `/api/keys` 获取（需 JWT 认证）
- 缓存到 EncryptedSharedPreferences (`key_cache`)
- 应用启动时 init() → loadFromCache() + refreshFromServerAsync()
- 静态 volatile 变量缓存，避免频繁读 SP

### 认证信息存储

- JWT Token + Username 存储到 EncryptedSharedPreferences (`lifetrack_auth`)
- 登录状态标记也在该 SP 中
- ApiClient.isLoggedIn() 检查 token 存在性 + logged_in 标记

### 登录策略

- 登录不再是强制要求，未登录用户可正常使用应用
- 未登录时：数据同步（syncAnalytics）自动跳过，不弹登录对话框
- Token 过期时：仅停止同步定时器，不强制重新登录
- ProfileFragment 中退出登录后不再触发强制登录弹窗
- 用户可在 ProfileFragment 中主动选择登录/退出

### 网络安全

- network_security_config.xml 允许明文 HTTP（用于连接后端 119.91.232.22）
- 后端使用 HTTP，未启用 HTTPS（已知安全风险）

### 后端安全

- JWT HS256 签名，有效期 720 小时（30 天）
- 密码 bcrypt 哈希存储
- Dashboard 独立密码保护（Session 认证）
- SECRET_KEY 和 DASHBOARD_PASSWORD 通过环境变量配置

### 已知安全风险

| 风险 | 当前状态 | 建议 |
|------|---------|------|
| 后端 HTTP 明文 | 未启用 HTTPS | 启用 Let's Encrypt |
| JWT 无刷新机制 | 30 天过期 | 实现 Refresh Token |
| Dashboard 默认密码 | lifetrack2024 | 强制环境变量设置 |
| SECRET_KEY 默认值 | lifetrack-dev-secret... | 生产环境必须设置强密钥 |
