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

- 每个任务生成一个独立 APK，包名可自定义（留空则自动分配），互不冲突
- 图标按动作类型着色，可自定义 1 个字符
- 生成的 APK 与生成器同签名，可直接覆盖升级
- 生成器自带「试运行」，先在当前 App 里验证动作再打包

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
