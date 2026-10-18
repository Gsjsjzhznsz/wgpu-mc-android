# 渲染图通道（Iris 预埋点）

android.35 起渲染图内置 **8 个命名插槽**（channel slots）。这是 Iris 适配路线的第一块基石——不是 shader 包加载器（那是后续里程碑，见 `iris-adaptation-research.md` §4.6），而是帧自身的插入点：将来驱动 shader 包 pass 的渲染器不必为“插在哪里”重构帧结构。

## 插槽（按帧序）

| 插槽名 | 时机 |
|---|---|
| `frame_start` | 帧首，深度尚无人认领——别人家的 shadow map / depth pre-pass 挂这里 |
| `before_terrain_solid` / `after_terrain_solid` | solid 层 pass 前后 |
| `before_terrain_cutout` / `after_terrain_cutout` | cutout 层 pass 前后 |
| `before_terrain_translucent` / `after_terrain_translucent` | 半透明 pass 前后；**`after_terrain_translucent` 是 composite 挂点**（此后看到的是成品世界） |
| `frame_end` | 帧尾 |

帧级插槽（`frame_start`/`frame_end`）按**帧**执行一次：start 归属绘制 solid 层的那次 group 调用，end 归属绘制半透明层的那次——两次 group 调用共享同一 encoder，命令序即执行序。

## pass 形态

一个 channel pass = **graph.yaml 已注册的 pipeline**（自带 shader/绑定/状态）+ 一个插槽。绘制为 **6 顶点、无顶点缓冲**（`@geo_channel`，顶点着色器用 `@builtin(vertex_index)` 自定位——与地形 shader 的 vertex-pull 同型，全屏 pass 的标准形态）。附件一律 `Load`：通道 pass 组合画面，不允许认领或清除深度。

## 注册 API

```kotlin
// Kotlin（其他 mod 可用）
RenderChannels.register("after_terrain_translucent", "my_composite")  // 失败=false，原因在日志
RenderChannels.clear()

// Rust（JNI 同名）
WgpuNative.registerChannelPass(slot, pipeline): Boolean
WgpuNative.clearChannelPasses()
```

拒绝发生在注册时而非帧时：未知插槽、未知管线、重复注册都带原因返回。注册表在**图重建（shader 重载）后自动重放**——管线被新图丢弃的 pass 记一条警告，而不是静默消失。

## 尚未覆盖（下一里程碑）

- **per-pass 纹理与离屏目标**：后处理需要“渲染到中间纹理再 blit”的 ping-pong 基础件（调研文档 P0“离屏基础件”），当前通道 pass 只能直接画到 framebuffer（采样 game atlas / uniforms 可用，采样 framebuffer 不可用——反馈环）。
- **游戏 uniform 通道注入**：Iris 的 `gl_ModelViewMatrix` 式内建 uniforms 需要单独的绑定通道。
- **shader 包语义层**：GLSL→WGSL/SPIR-V 翻译、pack 解析、条件/宏——见调研文档路线 B'（shaderc→SPIR-V→naga spv-in）与 Vitrail 实验线。

当前插槽已按 Iris 1.12 backend 抽象的阶段语义命名（shadow → terrain 各层 → composite），后续移植时 pass 类型对号入座即可，帧结构无需再动。
