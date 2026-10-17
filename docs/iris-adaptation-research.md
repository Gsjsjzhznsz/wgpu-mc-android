# wgpu-mc 适配 Iris 着色器包：可行性研究与路线图

> 状态：研究文档（2026-10-18，android.32 会话产出；同日补充 Beryl 市场实证）。
> 结论先行：**直接安装 Iris 不可行**；**复用 Iris 的 Java 前端 + wgpu 后端**是长期可行路线；
> 短期建议做 P0+P1 原型验证三件硬风险（GLSL 变换层、内存预算、Adreno 性能）后再决定投入。
> **2026-10-18 市场实证**：VulkanMod 官方指路的光影方案 Beryl 也不加载 Iris 包，而是
> 自带内置光影——"Vulkan 上能装 Iris 包"的成品至今不存在，见 §4.5。

## 1. 背景与用户诉求

用户希望在 wgpu-mc-android（Rust JNI 渲染核心 + Neolectrum NeoForge mod，目标设备
Redmi K20 Pro / Adreno 640）上获得与 Iris（Fabric）/Iris-NeoForge 相同的着色器包生态，
即加载 BSL、Complementary、Bliss 这类 OptiFine/Iris 格式 shaderpack。

## 2. Iris 的管线结构（适配目标的完整清单）

Iris 是 OptiFine ShadersMod 管线的开源复刻，一个 shaderpack 定义的是**多 pass 渲染管线**，
不是单看着色器：

- **Pass 序**：`begin/setup`（Iris 扩展）→ `shadow`/`shadowcomp`（从太阳方向重绘整个世界的
  阴影贴图）→ `prepare` → gbuffers（terrain / terrain_solid / terrain_cutout / water / entities /
  block entities / particles / sky / clouds / hand，按 RenderLayer 分发）→（deferred 包：
  `deferred`）→ `composite`/`composite1..N`（全屏后处理链，可互相读写）→ `final`。
- **渲染目标**：`colortex0..15`（格式在 shaders.properties 里逐个声明，常用 RGBA16F/32F）、
  `depthtex0..2`、`shadowtex0..1`、`shadowcolor0..1`、自定义纹理、Iris 扩展的 SSBO 与 custom images。
  每个程序用 `RENDERTARGETS`（Iris）/`DRAWBUFFERS`（legacy）声明写哪些目标。
- **每帧 uniform 体系**：`sunPosition/moonPosition/shadowLightPosition/upPosition`、
  `cameraPosition/previousCameraPosition`（做 TAA/运动向量）、`frameTimeCounter`、
  `worldTime/worldDay`、`eyeBrightnessSmooth`、`centerDepthSmooth`、`isEyeInWater`、
  `viewWidth/viewHeight/aspectRatio`、`near/far`、`rainStrength/wetness` 等约 60 个。
- **pack 侧配置**：`shaders.properties`（buffer/开关/滑条）、`block.properties`/`entity.properties`/
  `item.properties`（把方块/实体 ID 映射到 `mc_Entity.x` 的材质 ID 与 render stage）、
  `world0`/`world-1`/`world1`（按维度换程序）、`settings.glsl`（选项 UI）。
- **顶点约定**：gbuffers 期望原版顶点属性——`vaPosition/vaColor/vaUV0/vaUV1/vaNormal`、
  `mc_Entity`（方块 ID）、`mc_midTexCoord`、`at_tangent`、`at_midBlock`（Iris）。
  **这是适配的核心难点**：wgpu-mc 的地形几何是 Rust arena 里的压缩格式
  （16bit/轴 + record 表 + 打包色/光），不是原版顶点流。
- **着色器语言**：pack 程序是 GLSL 120~330 + 大量 legacy builtins（`ftransform`、`gl_MultiTexCoord0`、
  `gl_ModelViewMatrix`、`gl_Color`…）。Iris 用 **glsl-transformer**（ANTLR AST 变换，Java）
  把 pack 程序和原版 core shader 互译（legacy→core、注入 RENDERTARGETS 输出重定向、
  注入自定义属性/uniform）。

## 3. 为什么 Iris 不能直接装在 wgpu-mc 上

1. **机制互斥**。Iris 的实现是 mixin 接管原版 GL 渲染路径（`LevelRenderer`/`RenderType`/
   core shader 编译），产物仍是 GL 调用。Iris 官方 FAQ 明确：与 Vulkan 类渲染 mod 互斥、会崩溃。
   wgpu-mc 正是"Vulkan 类渲染 mod"：地形根本不走原版 GL 管线，Iris 无从接管。
2. **世界几何不对 GL 暴露**。wgpu-mc 的地形在 Rust arena（SSBO）+ index 内嵌 record 的
   mega-index 格式里，由 WGSL terrain 管线绘制。Iris 的 gbuffers 顶点流（原版 vaPosition…）
   与它没有交集；把 arena 数据喂给 pack 着色器需要一个新的顶点翻译层。
3. **版本错位**。Iris NeoForge 从 1.21.1 起官方支持，本 mod 的 MC 是 26.1.2——即便抛开
   渲染互斥，Iris 本体在 26.1 的可用性也未经验证。
4. **目标是手机**。Iris 假设桌面 GL + 桌面 GPU；Adreno 640 是 GLES3.2/Vulkan1.1 的移动 GPU。

## 4. 三条路线评估

### 路线 A：让 Iris 本体跑起来（mixin 接管原版路径）
不可行。除了上面的互斥论证，还要把 wgpu-mc 的地形退回原版 GL 路径——等于放弃本项目的全部意义。

### 路线 B：复用 Iris 的 Java 前端 + wgpu 后端（长期正解）
Iris 的代码是分层的，其中**不碰 GL 的部分可以复用**（LGPL）：
- pack 发现/解压/`shaders.properties` 解析、选项系统；
- `block.properties`→材质 ID 映射（Neolectrum 已有块数据管道可接）；
- **glsl-transformer**（独立库，纯 AST 变换）——把 pack 的 legacy GLSL 变换成"core 风格 +
  显式输出"的 GLSL，这步与后端无关；
- uniform 值计算（每帧那 60 个的语义）。

需要新写的"wgpu 后端适配层"：
1. **GLSL→wgpu 管线**：变换后的 GLSL 交给 naga 的 GLSL 前端。⚠️ **naga 只收 Vulkan profile
   440+/450/460**（wgpu 30 文档核实），不吃 330 core 更不吃 legacy——所以 Iris 式变换在这里
   不止是"重定向输出"，必须**升格到 460 core**（legacy builtins 全部换成显式 uniform/attribute）。
   这是全项目最大的一块定制工作，需要给 glsl-transformer（或 Rust 侧重写件）写一套
   "升格 pass"：`ftransform→`显式矩阵乘、`gl_*`→命名 attribute、`texture2D→texture`、
   varying→in/out、`gl_FragData[i]→`显式 out。
2. **顶点翻译层**：为每个 gbuffers 生成一个"prologue"：从 arena 的 record/压缩顶点解码出
   `vaPosition/vaColor/vaUV0/vaUV1/vaNormal/mc_Entity/at_tangent`。好消息是解码逻辑
   terrain.wgsl 里已全部存在（AO、色、光、UV、动画旗标），工作是把它们包成可复用的
   WGSL include，与 pack 的 vert 拼接。
3. **离屏管线**：colortex/depthtex/shadowtex 的创建、绑定、clear、mipmap、格式管理
   （wgpu 完全支持，纯工作量）。wgpu-mc 当前完全没有离屏后处理链，这是绿地。
4. **render graph 扩展**：graph.yaml 现有的 pass/pipeline 声明体系可以承载 gbuffers/composite
   的编排——这是本项目相对"从零写"的最大优势。
5. **shadow pass**：太阳相机 + 用同一 arena 重绘地形（同样走 record 表，第二套
   camera immediate 即可；阴影里实体/方块实体后续补）。

### 路线 C：自研 "wgpu-mc shaderpack"（Iris 风格子集 + 自有约定）
短期能做出效果（bloom/阴影/水面波动的 composite 链），但**生态零兼容**——pack 不是
 interchangeable 的（Iris 作者原话），用户要的是"装 BSL"，这条路线满足不了。仅作为
 P0 阶段的管线试验场有价值。

### §4.5 市场实证：VulkanMod + Beryl（2026-10-18 补充，回应"市面上已有 Vulkan 的 Iris 项目"）

用户指出市面已有 Vulkan 光影项目，核实结果如下：

- **VulkanMod**（xCollateral，Fabric/Quilt，LGPL，Modrinth 520 万下载，支持 1.18.2~26.1.x）
  是与本 mod 同类的"Vulkan 渲染引擎"mod。其官方页对光影的答复只有一句：
  "If you want to use shaders with VulkanMod, then check out **Beryl**"。
- **Beryl**（Modrinth `rYeGeBaO`，需 VulkanMod 前置，Fabric，1.21~26.1.x）自述为
  "an efficient shader pipeline for Vulkan"。关键架构选择在它自己的描述里：
  **"beryl has an integrated shader pack"——内置自带光影，不加载 Iris/OptiFine 包**，
  理由是 "full control over the rendering pipeline and to better optimize it"。
- Iris 官方的三次表态一致：#1515（2022，"无计划支持 Vulkan 替代 mod，需要重写 Iris 管线"）、
  #1722（2022，"不会加 Vulkan 支持"）、#3416（2026-10，"Vulkan 兼容着色方案即使自带
  shaders，也不等于能加载现有 Iris shaderpack"）。

**这组事实说明三件事**：

1. **"Vulkan 上装 Iris 包"的成品至今不存在**——不是没人做，而是 legacy GLSL→Vulkan
   升格层就是所有项目都绕开的硬骨头。本方案对路线 B 难度的判断不变。
2. **Beryl = 路线 C 的成功先例**："Vulkan 原生管线 + 内置光影"能出成品、能拿下载量
   （VulkanMod 生态 520 万下载证明需求真实存在）。路线 C 从"仅管线试验场"升格为
   **可交付的近期方案**：把 P0 的离屏基础件 + 一个 BSL 风格内置包（软阴影/泛光/水面
   波光/色调映射）做成"实验开关"后面的一档，先让用户看到效果。
3. **包生态兼容（路线 B）仍然是唯一能满足"装 BSL"诉求的已知路径**，投入决策不变：
   先 P0+P1 原型验证，过不了退回 C 并明示。

行动调整：路线图不变，P0 之后**插入 P0.5：内置光影最小可见集**（复用 P0 的
composite 框架，不走 pack 解析），作为路线 C 的第一块交付物。

## 5. 三大硬风险（决定要不要做）

1. **GLSL 升格层**（路线 B-1）。glsl-transformer 只做语法级变换；升格到 460 需要语义级
   重写大量 legacy 惯用法。pack 质量参差（#extension 滥天、依赖驱动宽容度），失败模式是
   "每个 pack 都要单独修"。**缓解**：先只支持 GLSL 150~330 core 写法的现代 pack
   （Bliss/Complementary 新版都是），legacy 兼容列表后置。
2. **内存预算**。1080p 下 16 张 RGBA16F colortex ≈ 533MB，加 shadow 双目标 + depthtex×3
   ≈ 620MB——而本机 32 视距的教训是 512MB 就把整台手机推进 swap。**必须做移动预设**：
   colortex 减半分辨率（×0.5 = 133MB）、格式降级（RGBA8/RGB10A2 优先）、shadowmap
   1024/2048 上限、composite 半分辨率。这些要进 shaders.properties 的覆盖层，默认即省。
3. **Adreno 640 性能**。pack 的 composite 链普遍按桌面带宽写（8~20 个全屏 pass）。
   1080p×16pass×RGBA16F 在 Adreno 640（~13GB/s）上理论帧预算已超 8ms，实际全开预计
   20~40fps。**预期管理**：手机上 shaderpack = 拍照/观影模式，不是日常 200fps 模式；
   UI 上明示。

## 6. 分阶段路线图

| 阶段 | 内容 | 验收标准 | 粗估工作量 |
|---|---|---|---|
| P0 | 离屏目标基础件：colortex/depthtex 分配器、全屏三角形 pass 框架、图内资源声明扩展（graph.yaml 加"target"资源类型） | 一个 demo pass 把地形画到 colortex0 再 blit 回屏，关掉后零开销 | 1~2 会话 |
| P1 | pack 解析 + terrain gbuffers：shaders.properties/block.properties、GLSL 升格层原型（先支持 330 core）、arena→vaPosition prologue、terrain/terrain_solid/water 三个 gbuffers | 一至两个现代 pack 的地形部分正确着色（无阴影无后处理） | 2~4 会话 |
| P2 | composite/final 链 + RENDERTARGETS 路由 + uniform 体系（前 60 个里先做 30 个高频的） | pack 的后处理可见生效；TAA 类 pass 有正确的前帧数据 | 2~3 会话 |
| P3 | shadow pass：shadowtex/shadowcolor + shadow.gbuffers + `shadowDistance` 裁剪 | pack 的阴影/软阴影生效 | 2 会话 |
| P4 | 实体/方块实体/粒子/天空/手持 gbuffers（走游戏自绘路径的目标重定向） | pack 全管线闭环，主流包可日常开 | 3~4 会话 |
| P5 | 生态打磨：settings.glsl 选项 UI、legacy GLSL 兼容、world0/-1/1 维度切换、Iris 专属特性（SSBO/custom images）按需 | 兼容列表发布 | 持续 |

**建议的第一步**：P0 全做 + P1 的"升格层原型"只针对一个最简单的现代 pack
（如 Complementary 的无扩展子集）做端到端验证。P1 原型过了再谈全量投入；过不了，
退回路线 C（自有子集）并向用户明示。

## 7. 与现有发版链的衔接

- P0/P1 不动默认路径：shaderpack 支持整体放"实验开关"后面（settings 加 `shaderpack` 枚举 +
  off 默认），保证 android 主线零回归。
- Neolectrum 侧需要：pack 加载 UI（Iris 的 pack 列表屏可以简化成一个下拉）、
  `block.properties` 的块 ID 喂给 Rust（沿用现有 palette 管道）。
- 选项页文案（en_us/zh_cn）在 P1 落地时一并进 lang 文件。

## 8. 参考

- Iris 官方文档（programs/buffers/constants/macros）：shaders.properties/current/reference
- 管线总览：shaderlabs.org wiki "Rendering Pipeline (OptiFine, ShadersMod)"
- Iris 特性清单：IrisShaders/ShaderDoc `iris-features.md`
- glsl-transformer（ANTLR，Java 16，语法级 AST 变换）：IrisShaders/glsl-transformer
- naga GLSL 前端能力（Vulkan 440+/450/460）：wgpu.rs/doc/naga/front/glsl（wgpu 30）
- Iris-NeoForge 官方支持起点 1.21.1（modrinth 1.8.0-beta.1+1.21-neoforge）
