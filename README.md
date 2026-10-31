# Shell2APK

在手机上直接把「一个动作」打包成独立 APK 的小工具。

配置一条 Shell 命令 / 一个 URL Scheme / 一个应用包名 / 一个自定义 Intent，
点一下就生成一个**独立的 APK**（有自己的包名、图标和桌面名称），
安装后点桌面图标，那个动作就会被执行。

## 功能

| 动作类型 | 用途 | 例子 |
|---|---|---|
| Shell 命令 | 执行命令，可回显输出；**执行身份可选普通 / Shizuku / Root** | `getprop ro.build.version.release` |
| 链接 | 打开 URL 或任意 URL Scheme | `https://example.com`、`weixin://`、`intent://…` |
| 活动 | 启动指定 App 的某个 Activity | `com.tencent.mm/.ui.LauncherUI`（可从已装应用里选） |
| Intent | 自定义 action / component / uri / extras | `android.intent.action.VIEW` |
| 广播 | 发送广播，action 必填、可指定目标包名 | `com.example.ACTION_DO` |

- Shell 命令支持三种执行身份（新建任务时的「执行身份」下拉）：
  - **普通**：`/system/bin/sh -c`，本应用自己的权限（默认）
  - **Shizuku**：走 [Shizuku](https://shizuku.rikka.app)，拿到 ADB shell 级权限（uid 2000），无需 root
  - **Root**：`su -c`，需要设备已 root（Magisk / KernelSU 等）
- 每个任务生成一个独立 APK，包名可自定义（留空则自动分配），互不冲突
  （Shizuku provider 的 authorities 会随包名一起改写，多个 APK 并存不冲突）
- 图标按动作类型着色，可自定义 1 个字符
- 首页支持文件夹分组，任务可归到文件夹里
- App 内自动更新，内置 4 条公共 GitHub 加速线路并发选优（gh-proxy.com / ghfast.top / ghproxy.net / ghproxy.link），直链兜底
- 生成的 APK 执行完动作后**彻底退出**：杀子进程 + 移除任务栈 + 结束进程，不留后台
- 生成的 APK 与生成器同签名，可直接覆盖升级
- 生成器自带「试运行」，先在当前 App 里验证动作再打包

### Shizuku 使用前提

手机上装 [Shizuku](https://shizuku.rikka.app) 并通过 ADB 或 root 启动服务（Shizuku 11+）。
生成出来的 APK 第一次用 Shizuku 身份执行时会弹授权框，允许即可。
未授权时 App 会提示「Shizuku 未运行 / 权限被拒」。

> 依赖锁在 `dev.rikka.shizuku:api:12.1.0`：13.x 把 `Shizuku.newProcess` 改成了 private，
> 12.1.0 仍是 public static，能直接拿到一个有 stdout/stderr 的 `Process`。

## 原理

1. `runner/` 编出一个极简模板 APK（透明 Activity，启动即读 `assets/config.json` 执行）
2. 生成器把模板塞进自己的 `assets/runner.apk`
3. 生成时在手机上做四件事：
   - 重写 `AndroidManifest.xml` 的二进制字符串池（换包名 / 换应用名）
   - 改写 `resources.arsc` 里 package chunk 的 name 字段（256 字节 UTF-16 定长数组，支持任意长度包名）
   - 替换 `assets/config.json` 与 `res/mipmap-*/ic_launcher.png`
   - 自研 zip 重打包（保证 `resources.arsc` 未压缩 + 4 字节对齐）→ 用 [apksig](https://developer.android.com/studio/command-line/apksigner) 重新签 v1+v2+v3

> 注意：`gradle.properties` 里的 `android.enableResourceOptimizations=false` 不能删。
> 默认开启时 AGP 会把 `res/mipmap-xxx/ic_launcher.png` 重命名成 `res/9w.png` 这种随机名，
> 生成器就找不到要替换的图标文件了。

## 构建

推送到 `main` 会触发 GitHub Actions：

```
runner 模板 → 塞进 app assets → 生成器 App → 上传 Release
```

Release 里只放生成器一个 APK，文件名带版本号：`shell2apk-v1.0.<run_number>.apk`。
App 内自动更新通过 GitHub Release API 取最新版本与 asset 地址，所以文件名带版本号不影响更新。

本地构建需要 JDK 17 + Android SDK 36：

```bash
gradle :runner:assembleRelease
cp runner/build/outputs/apk/release/runner-release.apk app/src/main/assets/runner.apk
gradle :app:assembleRelease
```

签名需要仓库 secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`。
没有 keystore 也能编出 unsigned APK，但生成功能不可用（签名是运行时做的）。

### 桌面等价验证

沙箱/CI 里跑不了 Android 运行时，所以 `tools/VerifyBuild.java` 把 App 里的打包逻辑
（AXML 字符串池重建 / arsc 包名改写 / 自研 zip 写入 / apksig 签名）逐行翻成 JVM 版，
可以在修改后先本地验证一遍：

```bash
curl -LO https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.13.2/apksig-8.13.2.jar
javac -cp apksig-8.13.2.jar -d out tools/VerifyBuild.java
java -cp apksig-8.13.2.jar:out VerifyBuild 模板.apk 输出.apk com.example.myapp "测试名字"
```

会打印 `verified=true` 与改后的包名，再用 `pyaxmlparser` 确认
`manifest package` 与 `arsc packages` 都变成新包名，即说明逻辑正确。

## 下载

最新版：<https://github.com/rentianyu/shell2apk/releases/latest/download/shell2apk.apk>

## 已知限制

- 非 root 下 Shell 命令以应用自身 uid 运行，只能访问本应用有权限的目录和系统命令；
  需要读整个存储时，到系统设置里给对应的生成 APK 开「所有文件访问」权限
- 生成的 APK 最低 Android 8.0（API 26），模板 targetSdk 36
- 不建议在 Play 上架：模板声明了 `QUERY_ALL_PACKAGES` / `MANAGE_EXTERNAL_STORAGE`

## License

MIT
