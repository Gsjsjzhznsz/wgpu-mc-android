# 多平台适配：Android 之外的第二条腿

android.35 起，release jar 同时携带四个平台的 native 库。加载器（`WgpuNative.kt`）按运行平台探测对应目录，错误信息按平台命名——此前 iOS 上报 `the jar in use does not carry an Android build` 就是文案与目录都只认 Android 的直接后果。

## jar 里的布局

```
assets/wgpu_mc/natives/
  android-arm64-v8a/libwgpu_mc_jni.so    ← 所有真机（FCL/Pojav Android）
  android-x86_64/libwgpu_mc_jni.so       ← 模拟器
  ios-arm64/libwgpu_mc_jni.dylib         ← iPhone / iPad（Metal）
  macos-arm64/libwgpu_mc_jni.dylib       ← Apple Silicon Mac
  libwgpu_mc_jni.so                      ← 宿主平台库（仅本机构建时存在）
```

CI（`.github/workflows/android.yml`）分两个 job：

- `natives-apple`（macos-latest）：交叉编译 `aarch64-apple-ios` 与 `aarch64-apple-darwin` 的 cdylib，`ldid -S`（回退 `codesign --sign -`）ad-hoc 签名，按 `target/<triple>/release/` 的目录结构上传 artifact；
- `build`（ubuntu）：下载 artifact 精确投放进 `wgpu-mc-neo/rust/target/`，`copyNatives` 像对待 Android 三元组一样把它打包；jar 内容断言四个库全部在位，缺任一则发版失败。

## 加载器探测顺序

| 平台判定 | probe 顺序 |
|---|---|
| Android（`AndroidRuntime.isAndroid`） | `android-<abi>/` → 平铺 → `META-INF/natives/` |
| iOS（`os.name` 含 ios/iphone/ipad，或 `/var/mobile` 存在） | `ios-arm64/` → `macos-arm64/` → `macos-x86_64/` → 平铺 |
| macOS（`os.name` 含 mac os/darwin，或 SystemVersion.plist 存在） | `macos-arm64/`（arm64 架构）/ `macos-x86_64/`（Intel）→ `ios-arm64/` → 平铺 |
| 其余（Windows/Linux） | 平铺 → `META-INF/natives/` |

误配不会静默：`System.load` 拒绝平台不符的 Mach-O，android.27 引入的强制重提取重试会按名字报告失败。

iOS/macOS 的判定不依赖 `os.name` 单一信号——移动启动器惯于给 JVM 传 `-Dos.name=Linux`（Android 侧日志可见），所以文件系统信号（`/var/mobile`、SystemVersion.plist）是第二答案。

## iOS 启动器契约（渲染窗口）

mod 侧已完成 Rust 侧全部工作（`device.rs::surface_target` 的 `target_os = "ios"` 分支，UiKit raw handle）。要让渲染真正出画，iOS 启动器需要满足：

1. **窗口指针**：JNI 侧把“游戏视图”指针传给 `createWmRenderer(display, window)`——`window` 必须是承载游戏的 **`UIView`**（不是 `CAMetalLayer` 本身：wgpu 走 `-[UIView layer]` 取层，layer 不响应该 selector）。视图的 `layer` 必须是 `CAMetalLayer`（或调用前设置好）。
2. **主线程**：surface 创建在调用线程上进行，wgpu 的 metal 后端要求这是进程主线程。FCL/Pojav 系启动器的游戏线程就是主线程，契约天然成立；若启动器在子线程建渲染器，需从主线程调用。
3. **后端选择**：iOS 上 wgpu 只有 Metal 后端，`config/wgpu-mc-renderer.json` 的 `backend` 需选 Vulkan 语义的自动路径或显式 Metal（能力探测在启动时如实报告）。

Android 侧的 `createWmRendererOnAndroidSurface`（Surface → ANativeWindow）保持不变；iOS 没有对应物，走的是 `createWmRenderer` 的裸指针路径。

## 尚未覆盖

- **Intel Mac**（`x86_64-apple-darwin`）：构建映射与加载器 probe 已就位，CI 未构建该三元组（Apple Silicon 时代收益有限）。需要时在 `natives-apple` 里加一行 `cargo build --target x86_64-apple-darwin` + staging 拷贝即可。
- **Windows/Linux 桌面**：Rust 侧 surface 分支早已存在（Win32/Xlib），jar 打包未接入——桌面用户走上游 wgpu-mc 的自建路径。
- iOS dylib 的首次真机验证依赖启动器侧契约（上述 1/2），本仓库的 CI 只能证明“编译、签名、打包、可被 loader 找到”。
