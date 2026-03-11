# ClashMetaForAndroid 开发笔记

**日期**: 2026-03-11 15:34:03

## 项目概述

这是 fork 自 `MetaCubeX/ClashMetaForAndroid` 的项目，添加了自动更新功能和上游同步工作流。

---

## 已实现功能

### 1. 自动更新功能

#### 核心特性
- **动态仓库检测**: 从 `BuildConfig.GITHUB_REPO` 读取仓库信息，谁 fork 就检查谁的 releases
- **自动下载安装**: 使用 `DownloadManager` 后台下载，带进度条，完成后自动触发系统安装器
- **版本比对**: 通过 GitHub API 获取最新 release，对比版本号决定是否提示更新

#### 实现文件
- `app/src/main/java/com/github/kr328/clash/util/UpdateChecker.kt`
  - 检查 GitHub releases 最新版本
  - 优先选择 universal APK，其次 meta，最后任意 APK
  - 版本号解析：`major * 100000 + minor * 1000 + patch`

- `app/src/main/java/com/github/kr328/clash/util/ApkInstaller.kt`
  - `downloadApk()`: 返回 `Flow<DownloadState>` 实时上报下载进度
  - `installApk()`: 通过 FileProvider 触发系统安装

- `app/src/main/java/com/github/kr328/clash/AppSettingsActivity.kt`
  - 在"应用设置"中添加"检查更新"按钮
  - 显示 ProgressDialog 展示下载进度
  - 下载完成自动跳转安装界面

- `design/src/main/java/com/github/kr328/clash/design/AppSettingsDesign.kt`
  - 在"关于"分类下添加"检查更新"选项

#### 配置文件
- `build.gradle.kts`: 注入 `BuildConfig.GITHUB_REPO`，从 `GITHUB_REPOSITORY` 环境变量读取
- `app/src/main/AndroidManifest.xml`: 添加权限和 FileProvider
  - `REQUEST_INSTALL_PACKAGES`: 安装 APK 权限
  - `WRITE_EXTERNAL_STORAGE`: 写外部存储（Android 9 及以下）
  - FileProvider: `${applicationId}.update_provider`

- `app/src/main/res/xml/update_file_paths.xml`: FileProvider 路径配置

#### 字符串资源
- `design/src/main/res/values/strings.xml`: 英文
- `design/src/main/res/values-zh/strings.xml`: 中文
- 新增字符串：
  - `check_for_updates`, `update_available`, `download_and_install`
  - `downloading_update`, `downloading_please_wait`, `download_failed`

---

### 2. 上游同步工作流

#### 文件
- `.github/workflows/sync-upstream.yaml`

#### 功能
- **定时同步**: 每天 UTC 00:00 自动执行
- **手动触发**: 支持 `workflow_dispatch`
- **自动合并**: 从 `MetaCubeX/ClashMetaForAndroid` 的 `main` 分支合并
- **冲突处理**: 如果合并失败，自动创建 PR 供手动解决

#### 注意事项
- 需要在仓库设置中将 Actions 权限改为 "Read and write permissions"
- 如果 main 分支有保护规则，需要使用 PAT 替代 `GITHUB_TOKEN`

---

## 构建发布流程

### 前置准备

#### 1. 生成签名 Keystore
```bash
keytool -genkey -v \
  -keystore my-release.keystore \
  -alias mykey \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

#### 2. 转换为 Base64
```bash
# Linux/macOS
base64 -w 0 my-release.keystore

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("my-release.keystore"))
```

#### 3. 配置 GitHub Secrets
进入仓库 **Settings → Secrets and variables → Actions**，添加：

| Secret 名称 | 说明 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | keystore 文件的 Base64 编码 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | key alias（如 `mykey`） |
| `SIGNING_KEY_PASSWORD` | key 密码 |

#### 4. 设置 Actions 权限
**Settings → Actions → General → Workflow permissions** → 选择 **Read and write permissions**

### 触发构建

#### 正式版本
1. 进入 **Actions → Build Release → Run workflow**
2. 输入版本号（如 `v2.11.25`）
3. 点击 **Run workflow**
4. 构建完成后 APK 自动发布到 Releases

#### 预发布版本
- 推送到 `main` 分支自动触发 `build-pre-release.yaml`
- 生成 `Prerelease-alpha` tag 和 release

---

## 技术细节

### BuildConfig 注入机制
```kotlin
// build.gradle.kts
val githubRepo = System.getenv("GITHUB_REPOSITORY")
    ?: (queryConfigProperty("github.repository") as? String)
    ?: "MetaCubeX/ClashMetaForAndroid"
buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
```

- GitHub Actions 自动注入 `GITHUB_REPOSITORY` 环境变量（格式：`用户名/仓库名`）
- 本地开发可在 `local.properties` 中设置 `github.repository=用户名/仓库名`
- 默认回退到上游仓库

### 下载安装流程
1. 用户点击"检查更新" → `UpdateChecker.checkForUpdate()`
2. 发现新版本 → 弹出对话框询问是否下载安装
3. 用户确认 → `ApkInstaller.downloadApk()` 开始下载
4. `DownloadManager` 后台下载，通过 `Flow` 上报进度
5. 下载完成 → `ApkInstaller.installApk()` 触发系统安装器
6. 用户在系统界面确认安装

### 权限说明
- `REQUEST_INSTALL_PACKAGES`: Android 8.0+ 必需，首次安装时系统会引导用户授权
- `WRITE_EXTERNAL_STORAGE`: Android 9 及以下需要，用于保存下载的 APK
- FileProvider: 用于在 Android 7.0+ 安全地共享 APK 文件给系统安装器

---

## 常见问题

### Q: 为什么上游不做自动更新？
A: 主要原因：
1. **分发渠道限制**: F-Droid 有自己的更新机制，内置更新会冲突
2. **Google Play 政策**: 禁止应用自行下载安装 APK
3. **安全考量**: `REQUEST_INSTALL_PACKAGES` 权限较敏感
4. **用户群体**: Clash Meta 用户技术水平较高，能自行管理更新
5. **维护成本**: 需要处理各种网络环境和边界情况

### Q: 如何测试更新功能？
A: 
1. 先构建一个低版本号的 APK 安装到手机
2. 再构建一个高版本号的 APK 发布到 GitHub Releases
3. 在应用设置中点击"检查更新"

### Q: 更新检查失败怎么办？
A: 检查以下几点：
- 手机网络是否能访问 GitHub API
- 仓库是否有 releases（至少一个）
- release 中是否包含 APK 文件
- 查看 Logcat 中 `UpdateChecker` 的日志

---

## 提交记录

### Commit 1: feat: add auto-update checker and upstream sync workflow
- 添加 `UpdateChecker.kt` 检查更新
- 添加"检查更新"设置项
- 添加 `sync-upstream.yaml` 工作流
- 添加中英文字符串资源

### Commit 2: feat: auto download & install update from fork's own GitHub releases
- `BuildConfig.GITHUB_REPO` 动态注入
- `ApkInstaller.kt` 实现下载和安装
- `AppSettingsActivity.kt` 添加进度对话框
- AndroidManifest 添加权限和 FileProvider
- 更新字符串资源

### Commit 3: fix: support SIGNING_KEYSTORE_BASE64 secret for keystore injection in CI
- 支持从 Secret 注入 keystore Base64
- 更新 `build-release.yaml` 和 `build-pre-release.yaml`

---

## 后续优化建议

1. **后台更新**: 可以添加"自动检查更新"开关，应用启动时静默检查
2. **增量更新**: 如果 APK 体积大，可以考虑差分更新（需要服务端支持）
3. **更新通知**: 发现新版本时发送系统通知
4. **下载管理**: 支持暂停/恢复下载，断点续传
5. **更新日志**: 在更新对话框中展示 release notes

---

**文档生成时间**: 2026-03-11 15:34:03
