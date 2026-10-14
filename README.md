# wgpu-mc-android

NTS-Official [wgpu-mc-neo](https://github.com/NTS-Official/wgpu-mc-neo)（Rust 渲染引擎）与 [Neolectrum](https://github.com/NTS-Official/Neolectrum)（NeoForge mod）的 **Android 移植**。

它解决的问题是：在 FCL / Pojav 系启动器上启动 NeoForge 客户端时，mod 在 Bootstrap 阶段崩溃——

```
java.io.FileNotFoundException: Could not find lib libwgpu_mc_jni.so in jar
(looked in META-INF/natives/, assets/wgpu_mc/natives/)
```

原因有两层：mod jar 里从来没有 Android ABI 的 `libwgpu_mc_jni.so`；而且即使有，Rust 侧 `GraphicsBackend::Vulkan` 在 `target_os = "android"` 上的可用性判断是 `false`（Android 不是 `target_os = "linux"`），surface 创建也只有 Win32/Xlib 两条路。本仓库把这三件事都补齐了。

## 改动一览

### Neolectrum（mod，Kotlin）

| 文件 | 改动 |
|---|---|
| `AndroidRuntime.kt`（新增） | 纯反射的 Android 运行时工具：识别 Android（boot classpath 上的 `ActivityThread`）、从启动器视图层级找游戏 `Surface`（SurfaceView holder 优先、ViewRootImpl.mSurface 兜底）、ABI 目录映射、可写解包目录回退。不依赖 `android.jar`，不点名任何启动器类 |
| `WgpuNative.kt` | 加载逻辑支持按 ABI 子目录探测（`assets/wgpu_mc/natives/android-<abi>/` → 平铺目录 → `META-INF/natives/`）；解包目录增加 `java.io.tmpdir` 回退；新增 `external fun createWmRendererOnAndroidSurface()`；每次解包都打日志 |
| `WgpuBackend.kt` | `createDevice` 增加 Android 分支：不再走 GLFW 原生句柄（启动器 GLFW 是 stub，`glfwGetPlatform` 恒报 x11、native accessor 未实现），改为把游戏 `Surface` 交给 Rust |
| `build.gradle.kts` | `copyNatives` 同时打包 `rust/target/<triple>/release/libwgpu_mc_jni.so` 到 `assets/wgpu_mc/natives/android-<abi>/`；可用 `-Pwgpu_mc_android_abis=` 圈定 ABI |
| `gradle.properties` | 版本 `2612.0.8-android.1`（semver 排在 `2612.0.8-beta` 之后） |
| lang 文件 | 新增 `wgpu_mc.option.backend.gles`（en/zh） |

### wgpu-mc-neo（引擎，Rust）

| 文件 | 改动 |
|---|---|
| `settings.rs` | `GraphicsBackend` 新增 `GLES`（OpenGL ES）变体；`is_available_here()` 把 `target_os = "android"` 算进 Vulkan；Android 上 Vulkan 的后备后端是 GLES（桌面仍是 Vulkan↔DX12） |
| `device.rs` | `surface_target()` 新增 `#[cfg(target_os = "android")]` 分支，用 `AndroidNdkWindowHandle` 包 `ANativeWindow*`；`wgpu_backends()` 增加 `GLES → Backends::GL`；新增 JNI 入口 `createWmRendererOnAndroidSurface`（`ANativeWindow_fromSurface`，直接对 `libandroid.so` 声明，规避 `ndk` crate 的 API 34+ `libnativewindow.so` 链接依赖）；`MAPPABLE_PRIMARY_BUFFERS` / `DEPTH_CLIP_CONTROL` 改为“适配器有才要”——Mali 的 Vulkan 驱动常缺 `VK_EXT_depth_clip_control`，原先的无条件请求会直接让设备创建失败（这两个 feature 全仓库没有任何 pipeline 实际使用） |
| `Cargo.toml` | 移除从未使用的 `winit` 依赖（它在 android target 会拖进无法编译的 `android-activity`）；Android target 零新增依赖——三个 `ANativeWindow` 函数直接对 `libandroid.so` 声明 |
| `rust/.cargo/config.toml`（新增） | 四个 Android target 的 NDK clang 链接器名（API 24 起，`libvulkan.so` 的最低版本） |

### CI（`.github/workflows/android.yml`）

JDK 25 + NDK r28 + 钉定的 nightly 工具链，交叉编译 arm64-v8a 与 x86_64 两份 `libwgpu_mc_jni.so`（`-C strip=debuginfo` 控制体积），`./gradlew jarJar` 打出内置 native 库的 mod jar。push 到 main 即可拿到 artifact；推 `v*` tag 自动发 Release。

## 在启动器里用

1. 从 CI artifact / Release 下载 `wgpu_mc-2612.0.8-android.1-all.jar`；
2. 放进实例的 `mods/` 目录（需要 NeoForge 26.1.x + Kotlin for Forge，和桌面版一样）；
3. 首选后端就是 Vulkan（Mali G610 / Adreno 6xx+ 都可以）；Vulkan 起不来时渲染器会自动落到 OpenGL ES，再不行才会报“无可用后端”；
4. 日志里搜 `wgpu:` 能看到：运行时识别、解包路径、命中的 jar 目录、`ANativeWindow` 地址与尺寸、选中的后端与适配器。

## 自己构建

```bash
# 1. Rust（仓库钉定 nightly-2026-09-26，进 rust/ 目录自动生效）
rustup target add aarch64-linux-android x86_64-linux-android

# 2. Android NDK，并把它的 toolchains/llvm/prebuilt/<host>/bin 放进 PATH
#    （链接器按名字解析，见 rust/.cargo/config.toml）

# 3. 编译 JNI 库（两个 ABI 按需）
cd wgpu-mc-neo/rust
RUSTFLAGS="-C strip=debuginfo" cargo build --release -p wgpu-mc-jni \
    --target aarch64-linux-android

# 4. 打 mod jar（默认找 ../wgpu-mc-neo/rust，monorepo 布局正好；JDK 25）
cd ../../Neolectrum
./gradlew jarJar
# 产物：build/libs/wgpu_mc-2612.0.8-android.1-all.jar
```

桌面构建完全不受影响：没有 Android 库时 jar 照旧是桌面形态，有桌面库时照旧平铺打包。

## 已知边界

- 启动器 `Surface` 的查找走的是框架反射（`ActivityThread` → `Window` → `DecorView`，`WindowManagerGlobal.mViews` 兜底）。这类启动器（FCL/Pojav 系）的游戏视图就是 SurfaceView，正常都能找到；如果某个 fork 的布局不同，日志会明确说“没找到”，扩一个探测策略即可。
- GLES 兜底走的是 wgpu 的 GL 后端（EGL + `ANativeWindow`），能不能用取决于启动器把 `libEGL.so` 指向哪套实现；Vulkan 是主路径。
- `DEPTH_CLIP_CONTROL` 等不可用的 feature 现在是“有才要”，桌面行为不变（桌面驱动全都报告可用）。

## 许可

沿用上游：引擎 Apache-2.0 / MIT（见 wgpu-mc-neo 内各文件），mod MPLv2。
