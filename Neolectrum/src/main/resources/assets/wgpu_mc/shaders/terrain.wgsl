struct UV {
    uv1: vec2<f32>,
    uv2: vec2<f32>,
    blend: f32,
    padding: f32
};

struct UVs {
    uvs: array<UV>
};

struct ChunkOffset {
    x: i32,
    z: i32
}


@group(0) @binding(0) var<uniform> mat4_model: mat4x4<f32>;
@group(0) @binding(1) var<uniform> mat4_view: mat4x4<f32>;
@group(0) @binding(2) var<uniform> mat4_persp: mat4x4<f32>;

@group(0) @binding(3) var t_texture: texture_2d<f32>;
@group(0) @binding(4) var t_sampler: sampler;

// The game's own block atlas, and the sampler the game samples it with. It is bound for the faces
// whose sprite the game animates - see `UV_GAME_ATLAS` in `pipeline.rs`: those are baked with the
// game's coordinates and marked in the vertex, and this is the texture they are meant for. The game
// animates it by rendering each due frame into it, so those faces move for free.
@group(0) @binding(5) var t_game_atlas: texture_2d<f32>;
@group(0) @binding(6) var t_game_sampler: sampler;

// **The magnification pair, which is how the game's own crispness comes back without giving up
// anisotropic filtering.**
//
// The game magnifies with `GL_NEAREST` and minifies with `GL_LINEAR_MIPMAP_LINEAR`, and GL lets it choose
// those independently. wgpu does not: any `anisotropy_clamp` above 1 requires the min, mag *and* mipmap
// filters to be linear, so one sampler cannot both magnify the way the game does and filter
// anisotropically. `t_sampler` and `t_game_sampler` are the anisotropic ones, for surfaces being
// *minified*; these two are `Nearest` magnification with no anisotropy, for surfaces being *magnified*.
//
// The split is not a compromise, because the two ranges do not overlap: anisotropic filtering is a
// minification technique, and a magnified surface is at level 0 by definition and has nothing to
// compress. See the fragment stage for the test that picks between them, and `atlas_magnify_sampler` in
// `atlas.rs` for the whole of why there are two of each.
@group(0) @binding(10) var t_game_sampler_magnify: sampler;

// The same-side pair: `t_sampler` is anisotropic, this one magnifies with `Nearest`. Nothing samples this
// side's atlas in a normal session - every sprite is on the game's - so it is here for the fallback path
// and for the two atlases to stay symmetric.
@group(0) @binding(11) var t_sampler_magnify: sampler;

// **A minified animated face gets its own sampler, because neither pair above is right for it.** The
// anisotropic one cannot be used beside a `Nearest` mip filter (a validation error, and the mip filter is
// a player's switch), and `Nearest` magnification of a coarse level is what turns a moving sprite into one
// flat patch of its own running average - the "bright spots magnified into one big bright tile" a player
// described. Same filters as `t_sampler` and `t_sampler_magnify` except for the anisotropy.
@group(0) @binding(12) var t_game_sampler_animated: sampler;
@group(0) @binding(13) var t_sampler_animated: sampler;

// **The same two atlases again, one copy per sampler.**
//
// Vulkan accepts a shader that samples one texture through several samplers - the descriptor set binds
// the image once per sampler, and every backend we ship there is fine with it. GLES is not: its samplers
// are combined `sampler2D` types, one texture unit each, and naga's GLSL writer refuses a module where a
// single image is reached by more than one of them (`A image was used with multiple samplers`). That
// refusal is what blacked the world out on the GLES path: all three terrain pipelines died at
// translation, the graph skipped their draws, and the screen kept everything but the world. The way out
// changes nothing about what is sampled: one texture global per sampler, and the same view bound to each.
// On GLES that is the same texture on several units under different sampler objects, which GLSL ES has
// always allowed; on Vulkan it is the same view in a few more descriptor slots. The fragment stage
// switches between the three pairs as it did between the three samplers - plain, magnify, animated.
@group(0) @binding(14) var t_texture_magnify: texture_2d<f32>;
@group(0) @binding(15) var t_texture_animated: texture_2d<f32>;
@group(0) @binding(16) var t_game_atlas_magnify: texture_2d<f32>;
@group(0) @binding(17) var t_game_atlas_animated: texture_2d<f32>;

// The game's **lightmap**, and the sampler the game samples it with (`Sampler2` for the terrain is
// `getClampToEdge(LINEAR)`). This is the whole of the lighting: the game builds this 16x16 texture from
// the light levels of the world, the gamma and brightness options, the time of day, night vision and
// the darkness effect, and its own terrain shader does nothing more than fetch it per vertex -
// `vertexColor = Color * sample_lightmap(Sampler2, UV2)`. See `GAME_LIGHTMAP`.
@group(0) @binding(7) var t_lightmap: texture_2d<f32>;
@group(0) @binding(8) var t_lightmap_sampler: sampler;

// The game's **fog**: the colour and the four distances out of the frame's `FogData`, which is the same
// block the game's own terrain shader reads (`layout(std140) uniform Fog`). The last three floats are not
// part of the game's block at all - they are the camera's offset inside its own section, which is what
// turns the section-relative position this shader computes into the camera-relative one a fog distance is
// measured from. See `crate::renderer::FOG`.
struct FogEnvironment {
    color: vec4<f32>,
    environmental_start: f32,
    environmental_end: f32,
    render_distance_start: f32,
    render_distance_end: f32,
    camera_offset: vec3<f32>,
    padding: f32,
};

@group(0) @binding(9) var<uniform> fog: FogEnvironment;

@group(1) @binding(0) var<storage, read> chunk_data: array<vec4<u32>>;

/// **What one draw of this pass reads about its section, and the third channel the per-draw data has
/// had.** Two `vec4<u32>`s per record slot in one flat array, written once per bake and never again.
///
/// The channel a draw finds its record through is **the index buffer itself**: every baked index is
/// `(record_slot << 17) | local_vertex`, so `vi >> 17` names the record and `vi & 0x1ffff` names the
/// vertex within the section - and the table is read as two whole-vector loads at
/// `section_records[(vi >> 17) * 2]` and `+ 1`, one memory operation each. The two channels before
/// this one both died on the same Adreno 640: a storage binding indexed by
/// `@builtin(instance_index)` drew a hollow world because the driver accepted a non-zero
/// `firstInstance` and did not carry it into the builtin, and the instance-step attribute that
/// replaced it drew the same hollow world because the driver does not carry `firstInstance` into the
/// fixed-function fetch either. The indices are the one channel the per-section fallback path had
/// already proven on every device this renderer runs on - a draw's `first_index` is what selects its
/// own slice of the arena's index buffer, and that has never been optional.
///
/// **Why vectors and not a struct.** The vertex stage loads this table once per vertex, and the
/// load/store pressure of that stage is the budget everything else is paid from: a `vec4` load is
/// one operation, a four-scalar struct read is four that no compiler is obliged to merge. Vector 0
/// carries the section's `x`, `y`, `z` and `word_base`; vector 1 carries `ao_base` in its first
/// word, with three words of padding so a record is two whole vectors and the two loads stay
/// independent of each other.
///
/// **The position is absolute, and the camera subtraction is `section_pos.camera_section`'s job.** A
/// record written at bake time cannot know where the camera will be, so `x`, `y` and `z` are the
/// section's own coordinates and the vertex stage subtracts the camera's section, which travels in
/// the pass immediate. Three integer subtractions per vertex are what a per-frame record upload used
/// to cost the CPU.
///
/// **`ao_base` names the packed corner words that replaced the four corner loads.** The
/// smooth-lighting blend in the fragment stage needs the occlusion count of all four corners of the
/// quad; the vertex words carry one corner each, so this stage used to load all four corners'
/// third words per vertex - the same four, four times a quad. The bake now packs the four
/// three-bit counts into one word per quad and writes the words into the arena right after the
/// record's vertex data; `ao_base` is where that run starts, in u32 words, so quad `q`'s packed
/// word is the one at `ao_base + q`, read as the `vec4` at `(ao_base + q) / 4` with its component
/// picked by the low two bits. One load a vertex where there were four, and the four vertices of a
/// quad read the same word.
///
/// `chunk_data` above is `array<vec4<u32>>` for the same reason and by the same contract: the
/// arena's ranges are all multiples of four words (`allocate_ranges` rounds them there), so
/// `word_base / 4` is an exact vector index and the vertex the index names is the vector at
/// `word_base / 4 + local_vertex`.

@group(2) @binding(0) var<storage, read> section_records: array<vec4<u32>>;

// **What one draw of this pass reads, and where from.** Nothing per draw arrives through the input
// assembler at all: the pipelines declare no vertex buffers, and the draw's only per-draw inputs are
// its indices, which carry the section's record slot in their high bits - see `SectionRecord` above
// for what that decode reads and how it works.
//
// **Why the per-draw data went through three channels, and what killed the first two.** The batched
// path shipped reading `section_draws[instance_index]` out of a storage binding, and the first device
// it turned on on - an Adreno 640 - reported every feature the path asks for and then drew a world
// whose sections were missing or piled into one place: the signature of a driver that accepts non-zero
// `firstInstance` in an indirect record and does not carry it into the shader's `instance_index`.
// wgpu hit the same class on D3D12, where `SV_InstanceID` by definition ignores it (see the exclusion
// in `wgpu-mc-jni/src/device.rs` and wgpu#2471), and a builtin is a *compiler* path - which is exactly
// where a driver bug hides. The record then moved to an instance-step attribute the fixed-function
// fetch offsets by `firstInstance` - and the same Adreno 640 does not carry it through there either,
// which the four-record probe at device creation proved and android.26-28 drew one section at a time
// because of. The indices are the third channel, and the one nothing can refuse: the fallback path
// had been addressing its sections through `first_index` from the beginning, on every device, which
// is the proof a channel needs.
//
// See `SectionRecord` in `mc/mod.rs` for the Rust side, and `SectionPositionImmediate` in
// `render/graph.rs` for the immediate that carries the camera's section the subtraction needs.

struct VertexResult {
    @builtin(position) pos: vec4<f32>,
    @location(0) tex_coords: vec2<f32>,
    // **There is no `location(1)` or `location(2)` here, and that is the answer to "fill in
    // `tex_coords2` and `blend`" rather than a gap.** This struct used to declare both and the vertex
    // stage used to write `vec2(0.0, 0.0)` and `0.0` into them, which is a varying that is written and
    // never read - so the compiler removes it and the value never means anything. What they came from is
    // the reason they could not be filled in: they are leftovers from the GLSL renderer this one
    // replaced, where `texCoord2` was a second UV set and `blend` chose between the two, and they have
    // no source in the vertex format this side bakes.
    //
    // `Vertex` is `position`, one `uv` pair, a colour, ten animated-texture bits and a lightmap byte -
    // see `render::pipeline`, which is where the thirteen bytes are packed. There is no
    // second UV pair for `tex_coords2` to carry, and no blend weight for `blend` to be. The game's own
    // terrain shader is the same shape and has neither:
    //
    //     in vec3 Position; in vec4 Color; in vec2 UV0; in ivec2 UV2;
    //
    // - and its fragment stage reads `texCoord0` and `vertexColor` and nothing else. The lightmap that
    // `blend` would have selected against is fetched in the vertex stage here, into `light_color` below.
    //
    // So the two are gone rather than kept at zero: a varying that is always zero reads as "something
    // should be filling this in", and the next person to look at it has to do this same search to find
    // out that nothing should.
    // **The quad's four corner occlusion counts, packed.** One flat u32 where four flat f32s used to
    // be: three bits a corner, corner `i` in bits `i * 3..i * 3 + 3`, in the order the fragment
    // stage unpacks into `ao1..ao4` for the blend. The counts were four separate varyings, and a
    // varying is read by every fragment of the quad - a packed word is a quarter of that traffic
    // for the same four numbers, and the unpack is three shifts in a stage that was already doing
    // the multiplies.
    //
    // The word itself is loaded once per vertex out of the packed corner tail the bake wrote (see
    // `section_records`' note) - and the four vertices of a quad load the same word, so three of
    // the four are cache hits.
    @interpolate(flat) @location(12) ao: u32,
    @location(16) light_uv: vec2<f32>,
    // The light at this vertex, fetched from the game's lightmap by the vertex stage - which is where
    // the game's own terrain shader fetches it, so the rasterizer interpolates the *colour*, exactly as
    // it does for the game's terrain.
    @location(5) light_color: vec3<f32>,
    // **Which atlas `tex_coords` is in, whether the game animates this face's sprite, whether the
    // face is a mipped cutout, and the face's level-of-detail floor - one packed flat u32.** Bit 0
    // is `game_atlas`, bit 1 `animated`, bit 2 `mipped`, bits 3..7 the four-bit floor: four
    // varyings of face-level facts in one, unpacked by the fragment stage with the same three
    // shifts. Flat, because every member is a property of the face and not of the corner: every
    // vertex of a quad carries the same answer, and the fragment stage selects with it.
    //
    // The atlas flag is **not** branched on - see the sampling note in the fragment stage, where a
    // `textureSample` under an `if` would be undefined behaviour whatever this varying says.
    @interpolate(flat) @location(19) flags: u32,
    // How far this vertex is from the camera, the two ways the game measures it for fog. Interpolated,
    // as the game's own varyings are: the fog is applied per pixel from a per-vertex distance.
    @location(20) fog_distances: vec2<f32>,
    @location(18) color: vec4<f32>,
};

// What one terrain draw is told about itself **beyond its own draw record**: the alpha cutoff its
// layer asks for, and the five per-frame numbers about the two atlases. Seven `u32`s, twenty-eight
// bytes, and no padding - which is the size the pass declares for them (see `@pc_section_position` in
// `graph.yaml` and the sizes in `RenderGraph::new`).
//
// **The section's x, y and z are not here any more, and that is what the batched path cost.** They were
// the first three members, written per draw, and `multi_draw_indexed_indirect` is one call: everything
// an immediate holds is held for every draw in it, so the position had to move to something the vertex
// stage can fetch per draw - the instance-step attribute described above the `VertexResult`.
//
// The rest stays, because it is not per draw: the bias and the two texel sizes are frame constants, and
// the cutoff is a property of the layer, which is one immediate write per layer.
struct SectionPosition {
    // `0.5` for the cutout layer: Minecraft's own `CUTOUT_TERRAIN`, which declares `ALPHA_CUTOUT`.
    // The solid layer is **not** drawn with this shader - it has its own, with no test at all and no
    // `discard` in it, because a `discard` anywhere costs the whole pipeline its early-Z. See
    // `terrain_solid.wgsl`.
    alpha_cutout: f32,
    // **The level-of-detail bias the two block-atlas fetches are given, per draw.**
    //
    // It was a `const` for a round, and that was a mistake worth recording: **a constant is not
    // observable.** With `const ATLAS_LOD_BIAS: f32 = 0.0` the fetch is spelled `textureSampleBias` but
    // behaves exactly as a plain `textureSample`, so "the bias does nothing" and "the bias is not
    // reaching the GPU" produce the same picture - and changing the number needs the shader copied into
    // the build directory *and* the client restarted, because **nothing watches the shader files**
    // (`mark_pipelines_stale` is called on atlas and lightmap handover only). Two rounds of "I moved it
    // and nothing happened" could not distinguish those cases.
    //
    // As an immediate it is a value the draw hands over, so a setting can move it and the next frame
    // uses it - which is the whole difference between this and the constant.
    //
    // Zero is the honest default and the shader's normal state: it makes the fetch below identical to a
    // plain `textureSample`. A positive value samples a *coarser* level, a negative one a finer level -
    // which is how the two readings are told apart: blur that clears with a negative bias means the
    // chosen level was too coarse, and blur unchanged by any bias means the level was never the problem.
    lod_bias: f32,
    // **Half a texel of this side's own atlas, and half a texel of the game's, under test.**
    //
    // Two fields and not one, because the two atlases are not the same size and the shift has to be
    // measured against the one the sample is going to come out of: the game's stitcher packed
    // `blocks.png` at 2048x2048 on some of the launches this was written against and 1024x1024 on others,
    // while `ATLAS_DIMENSIONS` is fixed. A single number would be half a texel on one of them and a
    // quarter on the other.
    //
    // Zero disables it. See the note at the decode for what it is for and what it cannot do from here.
    half_texel_ours: f32,
    half_texel_game: f32,
    // **One texel of each atlas, as a fraction of it**, which is what the magnification test compares the
    // coordinates' screen-space derivative against.
    //
    // Sent rather than written here for the reason everything else about the atlases is: this side's is
    // `ATLAS_DIMENSIONS` and never moves, while the game's is whatever its stitcher packed - 2048x2048 on
    // some of the launches this was written against and 1024x1024 on others. `1 / 2048` written into a
    // shader would call a surface magnified on one launch and minified on the next.
    texel_ours: f32,
    texel_game: f32,
    // The game's TextureSize, per atlas: one texel as a fraction of it, which is the `pixel_size` the two
    // sampling functions below are given.
    //
    // It is `texel_ours` and `texel_game` again, and it is sent a second time rather than read from those two
    // because the pair has two different jobs - `is_magnified` compares a derivative against one texel, and a
    // sampling function divides by it - and a field that meant two things would be the one place they could
    // drift apart. Two `f32` written consecutively read as one `vec2`.
    // The game's UseRgss: 1 when its textureFiltering option is RGSS, 0 otherwise. See `sample_rgss`.
    use_rgss: u32,
    // WGSL gives a `vec3<i32>` an alignment of sixteen, so the coordinates below start at byte 32 -
    // the four bytes after `use_rgss` are padding, and the Rust struct the pass writes has the same
    // field named in it.
    //
    // **The camera's section, which every draw's record is absolute against.** The records are written
    // once per bake and cannot know where the camera will be; the subtraction that used to happen on
    // the CPU, per frame, per section, happens here instead - three `i32` subtractions, then the same
    // `* 16.0` the record's relative position was multiplied by. See `SectionRecord`.
    camera_section: vec3<i32>,
};

/// Whether a fragment's coordinates are being **stretched** rather than squeezed - which is exactly
/// "magnification", and is the test that picks between the two samplers.
///
/// The two derivatives are compared **separately**, and that is not a detail: `fwidth` is their *sum*, so
/// testing `fwidth < texel` asks for each of them to be under half a texel, not under one - which moves the
/// switch to where an isotropically-magnified surface is already half minified and puts the seam between
/// the two samplers where it is most visible. This test asserts what magnification actually is: neither
/// direction covers a whole texel in one pixel.
///
/// The result is flat across a primitive - a derivative is, because it is computed per 2x2 quad - so this is
/// uniform control flow by construction rather than by luck, and both fetches may be evaluated and selected
/// between.
fn is_magnified(coords: vec2<f32>, texel: f32) -> bool {
    let d = vec2<f32>(dpdx(coords).x, dpdy(coords).y);
    return abs(d.x) < texel && abs(d.y) < texel;
}

// **The game's own two sampling functions, ported.** See `assets/minecraft/shaders/core/terrain.fsh`,
// whose `main` is `UseRgss == 1 ? sampleRGSS(...) : sampleNearest(...)`.
//
// They are here rather than expressed with a sampler because **neither can be**: a sampler has one
// `anisotropy_clamp` and no way to say "four taps on a rotated grid at this exact level", and neither
// exposes the level the hardware would otherwise pick.
//
// `pixel_size` is one texel of the atlas being sampled, as a fraction of it - `1.0 / TextureSize` in the
// game, which is why the two numbers travel in the immediate instead of being written here.

/// **Everything the two sampling methods need that comes from a derivative**, taken once per fragment
/// and - the whole reason this is a struct - *before any branch*.
///
/// `dpdx` and `dpdy` are defined only in uniform control flow, and the fragment stage has two choices
/// ahead of it that are both per-vertex flags: which atlas a face samples, and which of the three
/// samplers its level wants. So the derivatives, the levels and the adjusted coordinates are all taken
/// at the top level of `frag`, and what is left inside the branches is `textureSampleLevel` and
/// arithmetic, neither of which carries a uniformity requirement. See [`sample_at_level`], and
/// `the_terrain_shaders_never_sample_a_texture_under_a_branch` in `graph.rs`, which is the test that
/// holds the shape.
struct SampleGeometry {
    /// The coordinates `sampleNearest` fetches from, with the texel-centre correction applied.
    nearest_uv: vec2<f32>,
    /// The level `sampleNearest` fetches at.
    nearest_level: f32,
    /// The four coordinates `sampleRGSS` taps, on the game's own rotated grid.
    taps: array<vec2<f32>, 4>,
    /// The two levels `sampleRGSS` blends between, and the fraction between them.
    low_level: f32,
    high_level: f32,
    level_blend: f32,
    /// How much of the supersampled fetch survives against the plain one. Below about one texel per
    /// pixel the surface wants the plain fetch's sharpening, which RGSS would only blur.
    rgss_blend: f32,
    /// The raw coordinates - what the taps are centred on, and what the far tier fetches from. The
    /// corrected `nearest_uv` above is the plain fetch's answer, and the correction is a magnified
    /// surface's exactness; at minification the level does the filtering and the raw coordinate is
    /// where the game's own four taps start from.
    uv: vec2<f32>,
    /// **The far tier.** Past `FAR_TEXELS_PER_PIXEL` the rotated grid cannot change the answer to
    /// what a screen can show, and two fetches - one at each of the two levels, mixed by
    /// `level_blend` - answer what its eight did. See the constant, which is where the line comes
    /// from and why it sits where it sits.
    far: bool,
};

/// An array of no taps, for the two tiers that never run the grid. The struct carries the taps
/// because the middle tier reads them; a tier that does not still has to fill the field, and this
/// is the fill - never read, checked by the branch that decides the tier before any fetch.
const NO_TAPS: array<vec2<f32>, 4> = array(
    vec2<f32>(0.0),
    vec2<f32>(0.0),
    vec2<f32>(0.0),
    vec2<f32>(0.0),
);

/// **The tier line between the four-tap grid and a two-fetch trilinear**, in texels per pixel.
///
/// The rotated grid is four sub-texel positions: its taps sit `|(0.125, 0.375)|` = 0.395 of a *fine*
/// texel off the fetch's centre, and that offset is the whole of what it buys - a texel edge falling
/// inside a pixel lands in the average at four rotations rather than one. At `n` texels a pixel, the
/// level the fetch lands on has one of its texels covering `n` fine ones, so the same offset is
/// `0.395 / n` of a *coarse* texel: at eight, under five per cent of one. Past that line the
/// four-tap average at each level is that level's one-tap fetch to within what a screen cannot
/// resolve, and two fetches answer what eight did.
///
/// Below the line the grid is the anti-aliasing, and it stays: a texel edge is a screen-sized thing
/// in the near and middle field, and eight texels a pixel is a few blocks from the camera at the
/// resolutions this renderer runs at - the band where a player sees shimmer is exactly the band the
/// grid keeps. What the line sells is the far field, which at a loaded view is most of the terrain
/// pixels there are: at twelve chunks the far three-quarters of them were paying the grid's eight
/// fetches to have their answer rounded back to a two-fetch trilinear by arithmetic alone.
const FAR_TEXELS_PER_PIXEL: f32 = 8.0;

/// The geometry for one fragment: the coordinates it samples, one texel of the atlas it samples, and
/// the level-of-detail shift the draw handed over.
///
/// **Called once, from the top level of `frag`, and nowhere else.** The derivatives under it are what
/// every level here is computed from, and a derivative taken under a branch is undefined behaviour
/// rather than a slightly wrong number.
///
/// The two levels it produces are the game's own two answers to "which level":
///
///  * `nearest_level` is the hardware's answer for these derivatives - what the game's `textureGrad`
///    would have used - **plus the bias**. The game's fetch has no bias, and the two things this side
///    adds to it are both shifts: the `atlas_lod_bias` setting and the per-sprite floor. At a bias of
///    zero and a floor of none the two agree, which is the state the comparison runs in.
///  * the RGSS pair comes from the **geometric mean** of the two derivative lengths rather than the
///    worse of them, which is the whole of what the game's `sampleRGSS` does differently:
///
///    ```glsl
///    float effectiveDerivative = sqrt(minDerivative * maxDerivative);
///    ```
///
///    The hardware's implicit level is derived from the worst of the two, which at a grazing angle is
///    the compressed one - so a surface seen edge-on is sampled from several levels coarser than its
///    footprint needs, and a *moving* sprite's coarse levels are a running average of its animation
///    rather than a smaller copy of it. That average is the fluid shimmer, and four levels is the order
///    a player measured.
fn sample_geometry(uv: vec2<f32>, pixel_size: vec2<f32>, bias: f32) -> SampleGeometry {
    let du = dpdx(uv);
    let dv = dpdy(uv);

    let derivative_x = length(du);
    let derivative_y = length(dv);
    let min_derivative = min(derivative_x, derivative_y);
    let max_derivative = max(derivative_x, derivative_y);

    let texel_screen_size = sqrt(du * du + dv * dv);
    let max_texel_size = max(texel_screen_size.x, texel_screen_size.y);
    let min_pixel_size = min(pixel_size.x, pixel_size.y);

    let nearest_level = max(0.0, log2(max_derivative / min_pixel_size) + bias);

    // How much of the supersampled fetch survives against the plain one. Below about one texel per
    // pixel the surface wants the plain fetch's sharpening, which RGSS would only blur - and there
    // the blend is exactly zero, which is the test `sample_at_level` returns on.
    let rgss_blend = smoothstep(min_pixel_size, min_pixel_size * 2.0, max_texel_size);

    // **The far tier is decided before anything the grid owns is computed**, because it is the one
    // tier that runs none of it: the levels come from the geometric mean below, but the texel-centre
    // correction and the taps are the plain fetch's and the grid's, and the far tier reads neither.
    // See `FAR_TEXELS_PER_PIXEL` for where the line sits and what sells it.
    if max_texel_size >= min_pixel_size * FAR_TEXELS_PER_PIXEL {
        return SampleGeometry(
            uv,
            nearest_level,
            NO_TAPS,
            0.0,
            0.0,
            0.0,
            rgss_blend,
            uv,
            true,
        );
    }

    // **The texel-centre correction, and it is not a detail.** `uv / pixel_size` is texel coordinates,
    // `round(..) - 0.5` the centre of the nearest texel, and the difference between the two scaled down
    // by how large a texel is on screen - so a magnified surface lands exactly on centres, where the
    // answer is exact, and the correction fades out as the surface minifies, where the level is doing
    // the filtering instead.
    let uv_texel = uv / pixel_size;
    let texel_center = round(uv_texel) - 0.5;
    var texel_offset = uv_texel - texel_center;

    texel_offset = (texel_offset - 0.5) * pixel_size / texel_screen_size + 0.5;
    texel_offset = clamp(texel_offset, vec2<f32>(0.0), vec2<f32>(1.0));

    let nearest_uv = (texel_center + texel_offset) * pixel_size;

    if rgss_blend == 0.0 {
        // **The magnified tier: the plain fetch is the whole answer.** The two levels are not read -
        // `sample_at_level` returns the plain fetch before it would touch them - so the second
        // `log2`, the `sqrt` behind the geometric mean and the taps stay out of the pixels that are
        // closest to the camera. The test is the blend and not `magnified` on purpose: `magnified` is
        // `|dpdx.x| < texel && |dpdy.y| < texel`, which is a different question - it can be true while
        // `max_texel_size`, the length of the whole derivative vector, is past `min_pixel_size`, and
        // there the blend is not zero and the taps do matter. Skipping on `magnified` would be a
        // picture change; skipping on the blend being zero is not.
        return SampleGeometry(
            nearest_uv,
            nearest_level,
            NO_TAPS,
            0.0,
            0.0,
            0.0,
            rgss_blend,
            uv,
            false,
        );
    }

    let mip_exact = max(0.0, log2(sqrt(min_derivative * max_derivative) / min_pixel_size) + bias);
    let low_level = floor(mip_exact);
    let high_level = low_level + 1.0;
    let level_blend = fract(mip_exact);

    // The game's own rotated grid - `vec2(0.125, 0.375)` and its three rotations - scaled by this
    // atlas' texel here, once, rather than per tap and per branch.
    let taps = array<vec2<f32>, 4>(
        uv + vec2<f32>(0.125, 0.375) * pixel_size,
        uv + vec2<f32>(-0.125, -0.375) * pixel_size,
        uv + vec2<f32>(0.375, -0.125) * pixel_size,
        uv + vec2<f32>(-0.375, 0.125) * pixel_size,
    );

    return SampleGeometry(
        nearest_uv,
        nearest_level,
        taps,
        low_level,
        high_level,
        level_blend,
        rgss_blend,
        uv,
        false,
    );
}

/// The game's two sampling functions, chosen by its `UseRgss` flag, **fetching at levels the caller
/// already has**.
///
/// **Every fetch in here is a `textureSampleLevel`, and that is what makes it legal to call from inside
/// a branch.** An explicit level takes no derivative, so WGSL's uniform-control-flow rule - the one that
/// makes a `textureSample` under an `if` undefined behaviour however uniform the condition looks - does
/// not apply to it. Everything that *does* need a derivative is in [`SampleGeometry`], taken before any
/// branch is entered, and this function never calls `dpdx` or `dpdy`.
///
/// The two are ports of the game's own `sampleNearest` and `sampleRGSS`: the plain fetch at the
/// hardware's level with the texel centres corrected, and the four taps on the rotated grid blended
/// between the two levels the geometric mean straddles. Neither can be expressed with a sampler - a
/// sampler has one `anisotropy_clamp` and no way to say "four taps on a rotated grid at this exact
/// level", and nothing exposes the level at all.
fn sample_at_level(
    source: texture_2d<f32>,
    samp: sampler,
    geometry: SampleGeometry,
    use_rgss: u32,
) -> vec4<f32> {
    // **The far tier: two fetches where the grid paid eight.** Past `FAR_TEXELS_PER_PIXEL` the taps
    // are under five per cent of a coarse texel off centre, so the four-tap average at each level is
    // that level's plain fetch to within what a screen cannot resolve - and the game's own answer is
    // a blend of the two levels either way. One bilinear fetch at each, mixed by the same
    // `level_blend`, is that sample for a quarter of the fetches. This is the tier the far field
    // draws in, and the far field is most of a loaded view; the tier it replaced is the shape the
    // branches below still draw the near and middle field with.
    if use_rgss != 0u && geometry.far {
        let low = textureSampleLevel(source, samp, geometry.uv, geometry.low_level);
        let high = textureSampleLevel(source, samp, geometry.uv, geometry.high_level);

        return mix(low, high, geometry.level_blend);
    }

    // **And the four-tap grid is skipped when it cannot change the answer**, which is the magnified case -
    // the surfaces the player is looking at most closely.
    //
    // `rgss_blend` is `smoothstep(min_pixel_size, min_pixel_size * 2.0, max_texel_size)`, so it is
    // *exactly* zero whenever a texel covers at least a pixel - and `mix(plain, rgss, 0.0)` is `plain`,
    // bit for bit. Those eight fetches were being spent to compute a value that was then multiplied by
    // zero: at the game's `Fancy` setting a magnified fragment cost **nine** fetches to return the first
    // one, and a block face sixteen texels across stops being magnified only once it is a few blocks
    // away, so this is most of the near and middle field rather than a corner case.
    //
    // The test is `rgss_blend` and not `magnified` on purpose. `magnified` is `|dpdx.x| < texel &&
    // |dpdy.y| < texel`, which is a different question - it can be true while `max_texel_size`, the
    // length of the whole derivative vector, is past `min_pixel_size`, and there the blend is not zero and
    // the taps do matter. Skipping on `magnified` would be a picture change; skipping on the blend being
    // zero is not.
    if use_rgss == 0u || geometry.rgss_blend == 0.0 {
        return textureSampleLevel(source, samp, geometry.nearest_uv, geometry.nearest_level);
    }

    var low = vec4<f32>(0.0);
    var high = vec4<f32>(0.0);

    for (var i = 0u; i < 4u; i = i + 1u) {
        low = low + textureSampleLevel(source, samp, geometry.taps[i], geometry.low_level);
        high = high + textureSampleLevel(source, samp, geometry.taps[i], geometry.high_level);
    }

    let rgss = mix(low * 0.25, high * 0.25, geometry.level_blend);

    // **The plain fetch only where it has a consumer.** It used to be taken either way, because the
    // game's `sampleRGSS` ends by blending its result against this one rather than choosing between
    // the two - but the blend is `mix(plain, rgss, rgss_blend)`, and `rgss_blend` is exactly 1.0 from
    // two texels a pixel out, where `mix` is `rgss` bit for bit. Between one and two texels a pixel
    // the blend is real and so is the fetch; past it the fetch was computed and multiplied by zero -
    // one more fetch every minified fragment carried for nothing.
    if geometry.rgss_blend >= 1.0 {
        return rgss;
    }

    let plain = textureSampleLevel(source, samp, geometry.nearest_uv, geometry.nearest_level);

    return mix(plain, rgss, geometry.rgss_blend);
}

var<immediate> section_pos: SectionPosition;

/// Everything the fragment stage needs from the derivatives, for the atlas it is going to sample.
///
/// `atlas_texel` is one texel of that atlas as a fraction of it - `section_pos.texel_ours` or
/// `section_pos.texel_game` - and it is the only thing about the two entry points that differs before the
/// sampling itself: the level-of-detail floor, the bias and the geometry are the same arithmetic for both.
///
/// **The `select` over the two texel sizes is not in here.** It is a `select` on a varying, and the whole
/// point of the second entry point is that it does not exist there.
struct FragmentGeometry {
    geometry: SampleGeometry,
    /// Whether this fragment is magnified, which picks the sampler.
    magnified: bool,
};

fn fragment_geometry(in: VertexResult, atlas_texel: f32) -> FragmentGeometry {
    let magnified = is_magnified(in.tex_coords, atlas_texel);

    // The level-of-detail floor, which is what a bias could not express. See the note in `frag` for the
    // whole of it: four bits the bake wrote, and `LOD_FLOOR_IS_LEVEL_ZERO` picking which floor.
    const LOD_FLOOR_IS_LEVEL_ZERO: bool = false;

    // The packed face flags, unpacked here: bit 1 is `animated`, bits 3..7 the floor. See the
    // varying's note on `VertexResult` for the packing.
    let animated = (in.flags >> 1u) & 1u;
    let lod_floor = (in.flags >> 3u) & 0xfu;

    let floor = select(
        0.0,
        select(64.0, f32(lod_floor), !LOD_FLOOR_IS_LEVEL_ZERO),
        animated == 1u && !magnified,
    );

    let bias = section_pos.lod_bias - floor;

    return FragmentGeometry(
        sample_geometry(in.tex_coords, vec2<f32>(atlas_texel), bias),
        magnified,
    );
}

/// Everything the fragment stage does **after** it has a texel: the ambient occlusion, the light, the
/// colour, the cutout test and the fog.
///
/// Shared by both entry points, which is what keeps the second one from being a second copy of the shader:
/// the only thing that differs between them is which texture the texel came out of.
fn shade(in: VertexResult, texel: vec4<f32>) -> vec4<f32> {
    // The ambient-occlusion corner, blended across the quad's four corners - and then the curve.
    //
    // This read `0.6 + 0.4 * corner` for as long as the vertices carried a *brightness* step, and that
    // is where the corners went wrong: Minecraft's own corner value is the average of four
    // `getShadeBrightness` samples, each `0.2` for a block that fills its whole block and `1.0` for
    // everything else - so its range is `0.2 .. 1.0` in five steps, and a shadowed corner in vanilla is
    // five times darker than the brightest one. Against a curve that starts at `0.6`, the darkest a
    // corner could get was 0.6: the shading was there, in the right places, and only about a third as
    // deep as the game's - which reads as "the ambient occlusion is too weak" rather than as anything
    // missing.
    //
    // The vertex carries the *count* - 0 to 4 of the four blocks that darken that corner - and the curve
    // is `1 - 0.2 * count`, which is exactly that average. The four corners are blended here, where
    // Minecraft's are what the rasterizer makes of four per-vertex colours: the curve is the game's, the
    // blend is this renderer's (bilinear over the corners rather than linear across two triangles, which
    // is why a face here has no diagonal seam through its shading).
    //
    // The corners arrive **packed** - one flat u32 varying, three bits a corner, corner `i` in bits
    // `i * 3..i * 3 + 3` - and unpack to the same four numbers the four f32 varyings carried.
    let ao_packed = in.ao;
    let ao1 = f32(ao_packed & 7u);
    let ao2 = f32((ao_packed >> 3u) & 7u);
    let ao3 = f32((ao_packed >> 6u) & 7u);
    let ao4 = f32((ao_packed >> 9u) & 7u);

    var occluders = mix(mix(ao3, ao4, in.light_uv.x), mix(ao2, ao1, in.light_uv.x), in.light_uv.y);
    var ao = 1.0 - 0.2 * occluders;

    // The light is a colour now, not a number: the game's lightmap has a colour in it (the sky light
    // goes blue at night, the darkness effect tints it), and the game's own shader multiplies it in as
    // it stands. Everything else is this renderer's: the vertex colour carries the tint and the face
    // shading, and the corner value is the ambient occlusion above.
    let col = in.color * vec4(in.light_color, 1.0) * vec4(ao, ao, ao, 1.0) * texel;

    // The cutout test, at the cutoff the layer being drawn declares.
    //
    // This read `if (col.a == 0.0f)` for as long as the atlas had one mip level, where "transparent"
    // and "exactly zero alpha" are the same texel. The atlas has a mip chain now (`ATLAS_MIP_LEVELS`,
    // sampled with `mipmap_filter: Linear`), and a hole in a leaf texture is only zero at level 0: at
    // any level above it, the hole is an *average* of leaves and gaps, a small non-zero alpha - so
    // nothing was discarded and the face was painted whole, at full strength, because this pass
    // replaces the target rather than blending into it. Leaves therefore came out solid beyond the
    // distance at which a sprite stops covering enough pixels to stay on level 0, and the boundary
    // between the two moved with the camera's distance, angle and field of view.
    //
    // **This shader is only bound for layers that are cut out** - the cutout layer here, and the
    // translucent one through the pipeline that names it. The solid layer draws with
    // `terrain_solid.wgsl`, which has no test, because the mere presence of the `discard` below is
    // what makes a driver drop early-Z for the entire pipeline. See `terrain_layers` in `graph.rs`.
    // **Minecraft's cutoff, which is two numbers and not one.** `RenderPipelines` declares
    // `pipeline/cutout_terrain` at `ALPHA_CUTOUT 0.5` and the mipped cutout family at `0.1`, and this
    // renderer has one cutout layer for both - so the number comes from the face's own pipeline through
    // the varying rather than from the layer alone. A mip of a leaf or a grass tuft is mostly the
    // low-alpha fringe the game keeps and 0.5 discards, which is why the two renderers' frames differed
    // most on exactly those sprites. See `UV_CUTOUT_MIPPED`. Bit 2 of the packed face flags.
    let cutoff = select(section_pos.alpha_cutout, 0.1, ((in.flags >> 2u) & 1u) != 0u);

    if (col.a < cutoff) {
        discard;
    }

    // And the fog, in the same place the game's own fragment shader applies it: after the alpha test, so
    // what is tested is the texture's own alpha rather than a fogged one. It is applied to the whole
    // terrain pass, which is why the far edge of the loaded world fades into the sky instead of ending.
    return apply_fog(
        col,
        in.fog_distances.x,
        in.fog_distances.y,
        fog.environmental_start,
        fog.environmental_end,
        fog.render_distance_start,
        fog.render_distance_end,
        fog.color,
    );
}

@fragment
fn frag(
    in: VertexResult
) -> @location(0) vec4<f32> {
    // Which atlas this face samples, and the whole of what the flag does.
    //
    // A face whose sprite the game animates was baked with the game's coordinates and draws from the
    // game's atlas, which the game is already animating; everything else draws from this side's copy
    // of its sprite.
    //
    // **One texel of it, and the one `select` that stays.** This picks a *number* the level is computed
    // from rather than a fetch, and it has to be decided before the derivatives are taken - so it cannot
    // be one of the branches below, and there is nothing in it to branch over. Built from the fields
    // that were already here rather than sent a second time: a duplicate of the same number is a second
    // thing that can disagree, and it cost four bytes of padding in an immediate whose size has to match
    // this struct exactly. **wgpu aborts the process when it does not** (`Not all immediate data
    // required by the pipeline has been set ... missing byte ranges: 48..52`), which is how the
    // duplicate was found.
    let game_atlas = in.flags & 1u;
    let animated = (in.flags >> 1u) & 1u;

    let pixel_size = select(
        vec2<f32>(section_pos.texel_ours, section_pos.texel_ours),
        vec2<f32>(section_pos.texel_game, section_pos.texel_game),
        game_atlas == 1u,
    );

    // **Every derivative, every level and every adjusted coordinate, taken once - here, and outside
    // every branch.** `dpdx` and `dpdy` are defined only in uniform control flow, and the choice below is
    // a per-vertex flag. See [`SampleGeometry`] for what this returns and for the two levels the game's
    // own functions are built on.
    let fg = fragment_geometry(in, min(pixel_size.x, pixel_size.y));

    // **Which of the game's three sampling methods is in force**, as real branches.
    //
    // Those are twenty-four combinations - two atlases, three samplers, RGSS or not - that this used to
    // reach by *evaluating all of them* and `select`ing between the results, on the argument that a
    // `textureSample` in a branch is undefined behaviour. **The argument does not apply to a fetch at an
    // explicit level**: `textureSampleLevel` takes no derivative, so it carries no uniformity
    // requirement, and the things that do - `dpdx` and `dpdy` - are all in `sample_geometry` above. So
    // the branches fetch only what they need, and nothing is computed per branch.
    //
    // `the_terrain_shaders_never_sample_a_texture_under_a_branch` in `graph.rs` holds the shape: it
    // counts the *auto-level* fetches - `Auto` and `Bias`, the two that ask the hardware for a level -
    // and requires none of them under a branch. A `textureSampleLevel` under one is not a finding; it is
    // what this is made of.
    var texel: vec4<f32>;

    if game_atlas == 1u {
        if fg.magnified {
            texel = sample_at_level(t_game_atlas_magnify, t_game_sampler_magnify, fg.geometry, section_pos.use_rgss);
        } else if animated == 1u {
            texel = sample_at_level(t_game_atlas_animated, t_game_sampler_animated, fg.geometry, section_pos.use_rgss);
        } else {
            texel = sample_at_level(t_game_atlas, t_game_sampler, fg.geometry, section_pos.use_rgss);
        }
    } else {
        if fg.magnified {
            texel = sample_at_level(t_texture_magnify, t_sampler_magnify, fg.geometry, section_pos.use_rgss);
        } else if animated == 1u {
            texel = sample_at_level(t_texture_animated, t_sampler_animated, fg.geometry, section_pos.use_rgss);
        } else {
            texel = sample_at_level(t_texture, t_sampler, fg.geometry, section_pos.use_rgss);
        }
    }

    return shade(in, texel);
}

/// **The same fragment stage with the atlas decided when the pipeline is built**, which is the one thing
/// that can remove the branch rather than execute it.
///
/// It samples the game's block atlas and nothing else, so `t_texture` and the three samplers that go with
/// it are not referenced here at all (the game's atlas is reached through its three per-sampler copies,
/// `t_game_atlas`/`t_game_atlas_magnify`/`t_game_atlas_animated`, which are one view bound three times) -
/// and this is what the graph builds the terrain pipelines from
/// whenever [`crate::mc::block::faces_are_all_the_games`] says the arena holds no face that fell back.
/// The numbers, from a normal session: **204,878 faces baked against the game's atlas and 0 against this
/// side's**, so the two-atlas shader is the exceptional one and this is the common one.
///
/// **Why an entry point and not a pipeline constant.** With `override atlas: u32` the source would still
/// contain both atlases and both sampler sets; naga substitutes the override and the *backend compiler*
/// would then fold the branch away - reliably, but somewhere this repository cannot check. A second entry
/// point is smaller in the source itself, which is checkable: a test reads both shaders with naga and
/// asserts that this entry point reaches exactly one atlas (through its per-sampler copies) and its
/// three samplers, and that `frag` reaches both atlases. See
/// `the_single_atlas_entry_point_samples_one_atlas` in `graph.rs`.
///
/// The atlas is not the *sampler*, so the three-way choice between magnify, animated and plain is still
/// here - those are properties of the fragment, not of the build.
@fragment
fn frag_game_atlas(
    in: VertexResult
) -> @location(0) vec4<f32> {
    // `texel_game` is one texel of the game's atlas, which is the `pixel_size` this variant would have
    // selected anyway - so this is the same number `frag` computes, without the `select`.
    let animated = (in.flags >> 1u) & 1u;

    let fg = fragment_geometry(in, section_pos.texel_game);

    var texel: vec4<f32>;

    if fg.magnified {
        texel = sample_at_level(t_game_atlas_magnify, t_game_sampler_magnify, fg.geometry, section_pos.use_rgss);
    } else if animated == 1u {
        texel = sample_at_level(t_game_atlas_animated, t_game_sampler_animated, fg.geometry, section_pos.use_rgss);
    } else {
        texel = sample_at_level(t_game_atlas, t_game_sampler, fg.geometry, section_pos.use_rgss);
    }

    return shade(in, texel);
}

@vertex
fn vert(
    @builtin(vertex_index) vi: u32,
) -> VertexResult {
//    var vert1_i = (vi >> 2) << 4;
//    var vert1_i = (vi << 2) & 0xfffffffc;
//    var vert1_i = ((vi >> 2u) << 2u)+base_vertex;

    // **Which section this vertex belongs to, read out of the index itself.** The bake wrote every
    // index as `(record_slot << 17) | local_vertex`, so the high bits name the record and the low
    // bits name the vertex within the section - the decode the input assembler used to do with an
    // instance-step fetch and a `first_instance` per record. See `SectionRecord` above for why the
    // fetch lost to the index.
    //
    // **Four loads a vertex, where there were nine.** The two record vectors are addressed by the
    // slot alone and load in parallel; the vertex's own data is one `vec4` (the four words the old
    // code read as four scalars); and the quad's packed corner word is one more load, the same one
    // for all four vertices of the quad. What used to be four scalar loads of the four corners'
    // third words is gone entirely - the bake packs those counts into one word per quad and this
    // stage reads the pack. The data and the corner word each wait on one record vector and on
    // nothing else, so the stage is two dependent memory rounds deep, the same as it ever was -
    // but with a quarter of the load/store work in each.
    let slot = vi >> 17u;
    let head = section_records[slot * 2u];
    let tail = section_records[slot * 2u + 1u];
    let local_vertex = vi & 0x1ffffu;

    let word_base = head.w;
    let data = chunk_data[(word_base >> 2u) + local_vertex];
    let v1 = data.x;
    let v2 = data.y;
    let v3 = data.z;
    let v4 = data.w;

    // **The ambient-occlusion count of each corner of this quad, one packed word.** The bake wrote
    // one per quad into the arena tail the record's `ao_base` names: corner `i` in bits
    // `i * 3..i * 3 + 3`, straight out of the same third-word bits this stage used to load the four
    // corners for. See the packed varying on `VertexResult` for where they go.
    let ao_word = tail.x + (local_vertex >> 2u);
    let ao_packed = chunk_data[ao_word >> 2u][ao_word & 3u];

    var uv = array<vec2<f32>,4>(
            vec2(1.0,1.0),
            vec2(0.0,1.0),
            vec2(0.0,0.0),
            vec2(1.0,0.0));

    var light_uv = uv[vi & 3];

    var vr: VertexResult;
    vr.ao = ao_packed;

    vr.light_uv = light_uv;

    // **Sixteen bits an axis, at 1/2048 of a block.** That is the scale VulkanMod's compressed terrain
    // vertex uses for the same three numbers (`POSITION_INV = 1.0 / 2048.0`), and it is eight bits an axis
    // finer than what this format held before. The low half of the first word is x and its high half is y;
    // z is the low half of the second, with u above it.
    //
    // There is no "this coordinate is exactly 16" flag to test any more. Eight bits could not name 16, so
    // each axis carried one; sixteen bits reach 32 blocks, and 16.0 is 32768 like any other coordinate.
    var x: f32 = f32(v1 & 0xffffu) * 0.00048828125;
    var y: f32 = f32(v1 >> 16u) * 0.00048828125;
    var z: f32 = f32(v2 & 0xffffu) * 0.00048828125;

    // The colour, in the fourth word's low three bytes - and the light in its top one. Both moved when the
    // position took the space they were in.
    var r: u32 = v4 & 0xffu;
    var g: u32 = (v4 >> 8u) & 0xffu;
    var b: u32 = (v4 >> 16u) & 0xffu;

    vr.color = vec4(f32(r) * 0.003921568627451, f32(g) * 0.003921568627451, f32(b) * 0.003921568627451, 1.0);

    // The quad's first vertex carries a single occlusion count in the same byte the four corners come
    // from, and **it is not read**: the fragment stage blends the four corner counts instead
    // (`ao1..ao4`, by where in the quad the pixel is), which is the whole of the ambient occlusion.
    // There was a varying for it, and the varying was written and never read, so both are gone - see
    // the note on `VertexResult` for why an unused varying is worth removing rather than zeroing.

    // Which atlas these coordinates are in, and therefore what one step of the sixteen bits the
    // vertex holds them in is worth.
    //
    // There are two, and they are not the same size. This side's own atlas is 2048 wide
    // (`ATLAS_DIMENSIONS`), so a step is a texel of it and the decode is a division by 2048. The
    // game's coordinates are the game's own, `0..1` over an atlas of whatever size the game stitched,
    // and are stored as the sixteen bits filled edge to edge (`UV_GAME_SCALE`) - which is also what
    // `Atlas::sprite_rects` measured them in. A face is baked for one of the two and says which, so
    // the scale is picked here by the same flag rather than being one number for both.
    let game_atlas = (v3 >> 16u) & 1u;
    // Bit 1 of the same ten: whether the game animates this face's sprite. See `UV_ANIMATED`.
    let animated = (v3 >> 17u) & 1u;
    // The level-of-detail floor: four bits at bit 18, written by the bake. See `UV_LOD_FLOOR_SHIFT`.
    let lod_floor = (v3 >> 18u) & 0xfu;
    // And the mipped-cutout bit, two bits above the floor's four: bit 6 of the flag half, which is bit
    // 22 of `v3`. See `UV_CUTOUT_MIPPED`.
    let mipped = (v3 >> 22u) & 1u;
    let uv_scale = select(0.00048828125, 1.0 / 65535.0, game_atlas == 1u);

    // **A half-texel shift, under test.** Both are sent from the renderer rather than written here,
    // because a shift is half a texel only once the atlas size is known and the game's atlas is not a
    // fixed size: the stitcher packed `blocks.png` at 2048x2048 on some launches of the runs this was
    // written against and 1024x1024 on others, while this side's own atlas is always 2048.
    //
    // What it is for: a face's coordinates run from one edge of its sprite to the other, so a sample lands
    // *between* texels rather than on one, and at magnification that is two texels blended at every point.
    // Half a texel would put the samples on texel centres instead.
    //
    // **The shift the two atlases need is selected with the same flag that selects the atlas**, and it has
    // to be: the coordinates are normalised by then, so the only thing that says how big a texel is is the
    // atlas the sample is going to come out of.
    let half_texel = select(
        section_pos.half_texel_ours,
        section_pos.half_texel_game,
        game_atlas == 1u,
    );

    var u: f32 = f32((v2 >> 16u) & 0xffffu) * uv_scale + half_texel;
    var v: f32 = f32(v3 & 0xffffu) * uv_scale + half_texel;

    // **`v` is written through with no `1.0 - v`, and that is not an omission.** The flip everybody
    // remembers - `texCoord0.y = 1.0 - texCoord0.y;` - was the OpenGL sampler's convention: GL's texture
    // origin is the *bottom* of the picture, while a Minecraft UV is measured **downward from the top row**.
    // Three things say so, and all three are checkable in this tree:
    //
    //  - the model's own tables put the sprite's `minV` on the *top* corners of a face: the game's
    //    `CuboidFace.UVs.getVertexV` gives vertices 0 and 3 `minV`, and `defaultFaceUV(SOUTH)` is
    //    `(from.x, 16 - to.y, to.x, 16 - from.y)` - `16 - to.y` is the top of the element. `sprite_vertices`
    //    and `default_face_uv` in `mc/block.rs` are those two tables transcribed, and the README records
    //    what turning them produced: every side face of every `defaultFaceUV` model upside down;
    //  - `TextureAtlasSprite#v0 = (y + padding) / atlasHeight`, measured from the *top* of the atlas, so
    //    `minV` is the sprite's first row in the image - and the game's own atlas is built by *rendering*
    //    sprites into it under `ortho2D(0, w, 0, h)` with that same top-down `y`, which lands the sprite's
    //    image row on the texel row `v0` names, the same orientation an upload of the PNG would give;
    //  - this backend's sampler is wgpu's, and its texture origin is the first row of what was uploaded -
    //    which is the PNG's first row. That is the convention above, so sampling it directly is what is
    //    upright; 26.1's own `terrain.vsh` writes `texCoord0 = UV0;` and its `terrain.fsh` samples it with
    //    no flip either, and `GlCommandEncoder#writeToTexture` uploads rows in order - the flip is gone from
    //    vanilla too, because 26.1 samples through the same top-left convention this side does.
    //
    // Adding one here would therefore *introduce* the mirror: the green of a grass block's side at the
    // bottom of the block, on every face of every model.

    // No flag test and no `16.0` here any more: see the decode above for where those three bits went.
    var pos = vec3<f32>(x, y, z);

    // **The record's absolute position, made camera-relative here.** The record cannot know where
    // the camera is - it was written at bake time - so the per-frame record's subtraction moved into
    // the shader: three integer subtractions, then the same `* 16.0`. Everything below this line is
    // exactly what the relative-position generations drew, because the number it names is the same
    // number - a small one, which is the whole point (see `camera_section_pos` in `mc/mod.rs` for
    // why absolute positions in `f32` ground sections into each other).
    // The record's words are unsigned and the camera's section is signed, so the subtraction is
    // i32 - i32: section coordinates fit an i32 by definition, and a u32-i32 mix is a shader that
    // does not validate.
    var section_origin = vec3<f32>(
        f32(i32(head.x) - section_pos.camera_section.x),
        f32(i32(head.y) - section_pos.camera_section.y),
        f32(i32(head.z) - section_pos.camera_section.z),
    ) * 16.0;
    var world_pos = pos + section_origin;

    vr.pos = mat4_persp * mat4_view * mat4_model * vec4(world_pos, 1.0);

    // The two patches every Minecraft GLSL shader gets appended to its `main` by this backend's
    // preprocessing (see `preprocessing.rs`), written out here because this shader is not one of them:
    // `OPENGL_TO_WGPU_MATRIX_AST` and `EMULATE_GL_CLIP_SPACE_AST`.
    //
    // Minecraft's projections are OpenGL's, so they expect a clip space whose `y = +1` is the top of
    // the render target and whose depth runs -1..1, and this backend gives every *its* shaders the
    // same two fixes rather than converting the matrices. Without them the terrain lands mirrored
    // about the horizontal plane - the ground in the sky - which is a picture that says what happened
    // only if you know the patch exists.
    vr.pos.z = 0.5 * vr.pos.z + 0.5 * vr.pos.w;
    vr.pos.y = -vr.pos.y;

    vr.tex_coords = vec2<f32>(u, v);
    // The face flags, packed: bit 0 the atlas, bit 1 animated, bit 2 the mipped cutout, bits 3..7
    // the level-of-detail floor. See the varying's note on `VertexResult`.
    vr.flags = game_atlas | (animated << 1u) | (mipped << 2u) | (lod_floor << 3u);

    // The lighting, fetched the way the game's own terrain shader fetches it: one lightmap texel per
    // vertex, and the rasterizer interpolates the *colour*, which is what `vertexColor = Color *
    // sample_lightmap(Sampler2, UV2)` comes to once the quad is rasterized.
    //
    // The texel is the light pair itself. `UV2` in the game's vertex format is a nibble per light
    // scaled by sixteen - so 0..255 - and `sample_lightmap` divides by 256 and adds half a texel:
    //
    //     vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
    //         return texture(lightMap, clamp((uv / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    //     }
    //
    // which for a nibble is `nibble / 16 + 1/32` - the centre of texel `nibble`. The clamp in the
    // game's line is a formality for a nibble, so there is none here. A vertex shader has no
    // derivatives, so the level is explicit; the lightmap has one level to fetch from.
    var light_pair = vec2<f32>(f32((v4 >> 24u) & 15u), f32((v4 >> 28u) & 15u));
    var light_texel = light_pair * 0.0625 + 0.03125;
    vr.light_color = textureSampleLevel(t_lightmap, t_lightmap_sampler, light_texel, 0.0).rgb;

    // How far this vertex is from the camera, which is what the fog is a function of.
    //
    // `world_pos` is relative to the camera's *section*, so the camera's own offset inside that section
    // has to come off it to get the camera-relative position the game measures from - the same sum the
    // game's `terrain.vsh` writes out in full, `Position + (ChunkPosition - CameraBlockPos) +
    // CameraOffset`. The two distances are the game's own (`fog.glsl`):
    //
    //     float fog_spherical_distance(vec3 pos) { return length(pos); }
    //     float fog_cylindrical_distance(vec3 pos) {
    //         float distXZ = length(pos.xz);
    //         float distY = abs(pos.y);
    //         return max(distXZ, distY);
    //     }
    //
    // and they are why the offset is in the shader's world axes rather than folded into the view matrix:
    // the horizontal distance is a *world* one, so rotating it would measure the wrong thing.
    var camera_relative = world_pos - fog.camera_offset;
    var spherical = length(camera_relative);
    var cylindrical = max(length(camera_relative.xz), abs(camera_relative.y));
    vr.fog_distances = vec2<f32>(spherical, cylindrical);

    return vr;
}

/// The game's fog curve, transliterated from `minecraft:fog.glsl`.
///
/// A line from 0 at `start` to 1 at `end`, and the fog is the *larger* of the two - one for the fog of
/// the environment (water, lava, blindness, darkness, the biome's own) and one for the render distance,
/// which is what keeps the far edge of the loaded world from being a hard line.
fn linear_fog_value(vertex_distance: f32, fog_start: f32, fog_end: f32) -> f32 {
    if (vertex_distance <= fog_start) {
        return 0.0;
    }

    if (vertex_distance >= fog_end) {
        return 1.0;
    }

    return (vertex_distance - fog_start) / (fog_end - fog_start);
}

/// The game's `apply_fog`: the strength is the larger of the two curves, and the colour's own alpha
/// scales how much of it is mixed in - which is how the game fades fog out entirely.
fn apply_fog(
    color: vec4<f32>,
    spherical: f32,
    cylindrical: f32,
    environmental_start: f32,
    environmental_end: f32,
    render_distance_start: f32,
    render_distance_end: f32,
    fog_color: vec4<f32>,
) -> vec4<f32> {
    var fog_value = max(
        linear_fog_value(spherical, environmental_start, environmental_end),
        linear_fog_value(cylindrical, render_distance_start, render_distance_end),
    );

    return vec4<f32>(mix(color.rgb, fog_color.rgb, fog_value * fog_color.a), color.a);
}
