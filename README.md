# RapidCsAC

Android 原生聊天客户端，应用 ID 为 `com.xiaobai.rapidcsac`，默认连接 `https://csac.chat`。支持 Android 7.0 及以上版本。

## 技术与结构

- Kotlin 2.4.20、Jetpack Compose 1.12.0、Miuix 0.9.4。
- `core` 负责 WS v2、账户凭据、SQLite 缓存与消息同步。
- `app` 负责 Compose 页面、Miuix 控件和 Android 平台能力。
- 会话使用平铺列表，菜单使用 Miuix 按钮锚定弹层，图片预览使用共享元素转场，支持浅色、深色、系统字号与减少动态效果。

## 构建

使用 JDK 17、Android SDK Platform 37.0、Build Tools 36.0.0。Gradle Wrapper 已锁定版本与 SHA-256 校验值。

在本机 `local.properties` 中填写 `sdk.dir`，或设置 `ANDROID_HOME`。

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:assemblePreview
```

网络需要 Maven 镜像时可增加 `-PcsacMirror=true`。

预览包位于 `app/build/outputs/apk/preview/app-preview.apk`，启用 R8 与资源压缩，使用本机调试签名。

## CI 与正式发布

`main` 推送会运行 Debug 构建与 Lint 检查，并生成固定签名的 Release APK，保存在对应 Actions 运行的 `signed-apk` 制品中。PR 执行构建与 Lint 检查。工作流支持从 `main` 手动运行。

仓库通过 GitHub Actions Secrets 提供 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。签名时将密钥临时还原到 Runner 私有目录，完成后清除。每次构建都会将 APK 的签名证书与 `.github/signing-certificate.sha256` 核对。

本机构建正式包时，设置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`，然后运行 `./gradlew :app:assembleRelease`。签名配置从环境变量读取。

发布新版本时，在 `app/build.gradle.kts` 中更新 `versionName`，递增 `versionCode`，将提交推送到 `main`，再推送对应的 `vX.Y.Z` 标签。CI 会核对版本号与标签、确认提交属于 `main`，随后自动创建 GitHub Release，附带签名 APK、SHA-256 校验文件、签名证书指纹与 AGPLv3 许可证。

首次从调试签名预览包切换到正式版时，需要先卸载预览包再安装正式 APK。后续正式版本使用同一密钥签名，可覆盖升级。

## 运行

登录页可修改 HTTPS 服务器地址。服务器需提供 `/ws/v2` 及对应用户 API。文字发送通过持久化队列保留幂等请求标识，重连时确认发送状态。图片、语音和文件通过 HTTPS 上传。

应用提供账户切换、好友与群组管理、申请处理、提及与回复通知、消息多选与转发、本机删除、按类型与日期筛选记录、群精华统计、群文件、动态、二维码与签名小程序。语音支持录音波形、发送前试听、暂停和拖动进度。会话令牌由 Android Keystore 加密保存。

私聊的蓝色双勾表示对方已读，灰色双勾表示服务端已接收。群聊维持灰色双勾，当前服务端协议尚未提供群成员阅读明细。本机删除与清空会保留过滤标记，防止重连回放恢复消息。扫描记录按账户保存在本机，可手动清空。

站点管理入口按服务端权限显示，提供用户与群组封禁、解封及注销冷静期账户恢复。对应 CsAC 服务端需要支持 `X-CsAC-Admin-Token` 请求头，一次性管理令牌只保存在内存中。

前台通过 WS 接收实时事件并进行断线回放。后台同步由 Android JobScheduler 调度，约每 15 分钟检查一次，实际时间受系统省电策略影响。进程结束后的即时通知依赖后续接入 FCM 或厂商推送。

## 开源许可

RapidCsAC 采用 AGPL-3.0-only 许可证，完整条款见 [LICENSE](LICENSE)。源码位于 [VasilyZa/RapidCsAC](https://github.com/VasilyZa/RapidCsAC)。

设置中的“开源许可”页面提供第三方库列表、版本、作者、项目主页及离线许可全文。构建时由 AboutLibraries 根据当前构建变体的直接和间接依赖生成许可数据，更新依赖后会自动同步。第三方代码保留各自许可证。

`config/opensource` 保存上游补充声明，包括 MaterialKolor 的 MIT 版权信息和 OkHttp 内置 Public Suffix List 的 MPL-2.0 许可。Gradle Wrapper 采用 Apache-2.0 许可证，条款见 `gradle/wrapper/LICENSE`。
