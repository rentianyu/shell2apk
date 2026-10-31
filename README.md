# Shell2APK

在手机上直接把「一个动作」打包成独立 APK 的小工具。

配置一条 Shell 命令 / 一个 URL Scheme / 一个应用包名 / 一个自定义 Intent，
点一下就生成一个**独立的 APK**（有自己的包名、图标和桌面名称），
安装后点桌面图标，那个动作就会被执行。

## 功能

| 动作类型 | 用途 | 例子 |
|---|---|---|
| Shell 命令 | 用 `/system/bin/sh -c` 执行命令，可回显输出 | `getprop ro.build.version.release` |
| 链接 | 打开 URL 或任意 URL Scheme | `https://example.com`、`weixin://`、`intent://…` |
| 应用 | 按包名启动已安装的应用 | `com.tencent.mm` |
| Intent | 自定义 action / component / uri / extras | `android.intent.action.VIEW` |

- 每个任务生成一个独立 APK，包名自动分配，互不冲突
- 图标按动作类型着色，可自定义 1 个字符
- 生成的 APK 与生成器同签名，可直接覆盖升级
- 生成器自带「试运行」，先在当前 App 里验证动作再打包

## 原理

1. `runner/` 编出一个极简模板 APK（透明 Activity，启动即读 `assets/config.json` 执行）
2. 生成器把模板塞进自己的 `assets/runner.apk`
3. 生成时在手机上做四件事：
   - 重写 `AndroidManifest.xml` 的二进制字符串池（换包名 / 换应用名）
   - 等长替换 `resources.arsc` 里的包名
   - 替换 `assets/config.json` 与 `res/mipmap-*/ic_launcher.png`
   - 自研 zip 重打包（保证 `resources.arsc` 未压缩 + 4 字节对齐）→ 用 [apksig](https://developer.android.com/studio/command-line/apksigner) 重新签 v1+v2+v3

## 构建

推送到 `main` 会触发 GitHub Actions：

```
runner 模板 → 塞进 app assets → 生成器 App → 上传 Release
```

本地构建需要 JDK 17 + Android SDK 36：

```bash
gradle :runner:assembleRelease
cp runner/build/outputs/apk/release/runner-release.apk app/src/main/assets/runner.apk
gradle :app:assembleRelease
```

签名需要仓库 secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`。
没有 keystore 也能编出 unsigned APK，但生成功能不可用（签名是运行时做的）。

## 下载

最新版：<https://github.com/rentianyu/shell2apk/releases/latest/download/shell2apk.apk>

## 已知限制

- 非 root 下 Shell 命令以应用自身 uid 运行，只能访问本应用有权限的目录和系统命令；
  需要读整个存储时，到系统设置里给对应的生成 APK 开「所有文件访问」权限
- 生成的 APK 最低 Android 8.0（API 26），模板 targetSdk 36
- 不建议在 Play 上架：模板声明了 `QUERY_ALL_PACKAGES` / `MANAGE_EXTERNAL_STORAGE`

## License

MIT
