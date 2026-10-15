//! **Task 14 diagnostics and regression: the Adreno `FrontFacing` + `Flat` device loss.**
//!
//! A Snapdragon 855 (Adreno 640) lost the device the moment the first
//! `PER_FACE_LIGHTING` pipeline - `minecraft:pipeline/armor_decal_cutout_no_cull` -
//! reached the driver, while a Mali device ran the same build fine. The loss line the
//! 2026-10-15 log carries named that pipeline; this module regenerates its final GLSL
//! from the vanilla sources (the same text the device-lost callback wrote into
//! `wgpu_mc_lost_pipeline/` on the phone) and turns the diagnosis into two permanent
//! checks on the SPIR-V the driver is handed:
//!
//! * the fragment stage's `BuiltIn FrontFacing` input ships **without** the `Flat`
//!   decoration - the one naga 30.0.1 emitted and the Adreno compiler answered with
//!   `VK_ERROR_INVALID_SHADER_NV`, which wgpu maps to a lost device (see the
//!   `[patch.crates-io]` note in the workspace `Cargo.toml`);
//! * a `flat in int` user varying keeps its `Flat` - the fork only took the bool off
//!   the list, and the everyday Flat the VUID mandates for real integer inputs is
//!   untouched (the regression itself checks the bool case from both ends).
//!
//! The two `#[ignore]`d tests print the full GLSL of the pipeline and of `entity_solid`
//! (the pipeline whose compilation provably succeeded on the same device) for the next
//! time a driver question needs the exact text; run them with `--ignored --nocapture`.

use crate::preprocessing::process_shaders;
use std::collections::{HashMap, HashSet};

/// The vanilla `core/entity` vertex shader with its `#moj_import`s resolved, exactly
/// as Minecraft's `ShaderSource#get` hands it over.
const ARMOR_VERT: &str = include_str!("testdata/armor_decal/vert.glsl");
const ARMOR_FRAG: &str = include_str!("testdata/armor_decal/frag.glsl");

/// What `RenderPipelines`' `<clinit>` builds for `pipeline/armor_decal_cutout_no_cull`
/// (read straight out of the class file's bytecode): the `core/entity` shaders with
/// `ALPHA_CUTOUT` 0.1, `NO_OVERLAY` and `PER_FACE_LIGHTING`, plus the pad directive the
/// JVM appends - 2 on the Adreno 640 (uniform offset alignment 64), which the log shows.
const ARMOR_DIRECTIVES: &str = "#version 440\n\
#define ALPHA_CUTOUT 0.1\n\
#define NO_OVERLAY true\n\
#define PER_FACE_LIGHTING true\n\
\n#define WM_CHUNK_PAD 2\n";

/// `entity_solid`, for comparison: no shader defines at all (the bytecode shows none),
/// so it is the pipeline whose compilation provably succeeded on the same device.
const ENTITY_DIRECTIVES: &str = "#version 440\n\n#define WM_CHUNK_PAD 2\n";

/// The plan the Kotlin side assembles for an entity pipeline: one bind group, the
/// snippet uniforms in snippet order, then the samplers - of which `NO_OVERLAY` leaves
/// `Sampler0` and `Sampler2`. The numbers themselves are an approximation; what matters
/// for the emitted text is that every block and shim the shaders declare has one.
fn entity_locations(with_overlay: bool) -> HashMap<String, (u32, u32)> {
    let mut locations: HashMap<String, (u32, u32)> = [
        ("DynamicTransforms".to_string(), (0u32, 0u32)),
        ("Projection".to_string(), (0u32, 1u32)),
        ("Fog".to_string(), (0u32, 2u32)),
        ("Lighting".to_string(), (0u32, 3u32)),
    ]
    .into_iter()
    .collect();

    let mut next = 4u32;
    for sampler in [
        "Sampler0".to_string(),
        "Sampler2".to_string(),
        "Sampler1".to_string(),
    ]
    .into_iter()
    .filter(|name| *name != "Sampler1" || with_overlay)
    {
        locations.insert(format!("{sampler}_wm_texshim"), (0, next));
        locations.insert(format!("{sampler}_wm_sampler"), (0, next + 1));
        next += 2;
    }

    locations
}

/// `DefaultVertexFormat.NEW_ENTITY`, in element order.
fn entity_vertex_layout() -> HashMap<String, u32> {
    [
        ("Position".to_string(), 0u32),
        ("Color".to_string(), 1u32),
        ("UV0".to_string(), 2u32),
        ("UV1".to_string(), 3u32),
        ("UV2".to_string(), 4u32),
        ("Normal".to_string(), 5u32),
    ]
    .into_iter()
    .collect()
}

fn final_glsl(directives: &str, with_overlay: bool) -> (String, String) {
    let result = process_shaders(
        ARMOR_VERT,
        ARMOR_FRAG,
        directives,
        &entity_locations(with_overlay),
        entity_vertex_layout(),
        // The log pins this: the per-draw pad line first fires on `translucent_terrain`,
        // long after every entity pipeline - none of them declare a per-draw block.
        &HashSet::new(),
    );

    (result.vert, result.frag)
}

/// `OpDecorate`'s opcode, the `Decoration` numbers, and the `BuiltIn` numbers the
/// checks below walk the module for.
const OP_DECORATE: u32 = 71;
const DECORATION_BUILT_IN: u32 = 11;
const DECORATION_FLAT: u32 = 14;
const BUILT_IN_FRONT_FACING: u32 = 17;

/// The SPIR-V words wgpu-hal would hand the driver for `source`: the same naga frontend
/// the reflection pass uses, then the backend the Vulkan device runs inside
/// `create_shader_module`.
fn spirv_words(source: &str, stage: wgpu_mc::wgpu::naga::ShaderStage) -> Vec<u32> {
    use wgpu_mc::wgpu::naga;

    let mut frontend = naga::front::glsl::Frontend::default();
    let options = naga::front::glsl::Options {
        stage,
        defines: Default::default(),
    };
    let module = frontend
        .parse(&options, source)
        .expect("naga's GLSL frontend refuses the processed shader");

    let mut validator = naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    );
    let module_info = validator
        .validate(&module)
        .expect("naga's validation refuses the processed shader");

    naga::back::spv::write_vec(
        &module,
        &module_info,
        &naga::back::spv::Options::default(),
        Some(&naga::back::spv::PipelineOptions {
            shader_stage: stage,
            entry_point: "main".to_string(),
        }),
    )
    .expect("naga's SPIR-V backend refuses the processed shader")
}

/// Walks the module's `OpDecorate` instructions. Returns `(builtin_ids, flat_ids)`:
/// every id decorated `BuiltIn <literal>` per builtin number, and every id decorated
/// `Flat`.
fn decorations(spv: &[u32]) -> (HashMap<u32, u32>, HashSet<u32>) {
    let mut builtins = HashMap::new();
    let mut flats = HashSet::new();

    let mut p = 5; // the header: magic, version, generator, bound, schema
    while p < spv.len() {
        let word = spv[p];
        let opcode = word & 0xFFFF;
        let word_count = (word >> 16) as usize;
        if word_count == 0 {
            break;
        }

        // `OpDecorate` carries a target and a decoration at minimum; the BuiltIn
        // flavor has one literal more, which only that branch reads.
        if opcode == OP_DECORATE && word_count >= 3 && p + word_count <= spv.len() {
            let target = spv[p + 1];
            let decoration = spv[p + 2];
            if decoration == DECORATION_BUILT_IN {
                if word_count >= 4 {
                    builtins.insert(target, spv[p + 3]);
                }
            } else if decoration == DECORATION_FLAT {
                flats.insert(target);
            }
        }

        p += word_count;
    }

    (builtins, flats)
}

/// The regression itself. Before the naga fork, the same id carried both decorations -
/// `OpDecorate %x BuiltIn FrontFacing` and `OpDecorate %x Flat` - and the Adreno 640
/// compiler killed the device over it. It must come back with the BuiltIn and without
/// the Flat.
#[test]
fn the_front_facing_builtin_ships_without_flat() {
    let (_, frag) = final_glsl(ARMOR_DIRECTIVES, false);
    let spv = spirv_words(&frag, wgpu_mc::wgpu::naga::ShaderStage::Fragment);
    let (builtins, flats) = decorations(&spv);

    // The shader really exercises the path: without a FrontFacing input the assertion
    // below would be vacuous, and the same test would pass on a build whose shaders
    // never mention gl_FrontFacing at all.
    let front_facing = builtins
        .iter()
        .filter(|&(_, &builtin)| builtin == BUILT_IN_FRONT_FACING)
        .map(|(&id, _)| id)
        .collect::<Vec<_>>();
    assert!(
        !front_facing.is_empty(),
        "the PER_FACE_LIGHTING fragment shader has no FrontFacing builtin, so this check \
         stopped covering the construct that killed the Adreno device"
    );

    for id in &front_facing {
        assert!(
            !flats.contains(id),
            "the FrontFacing builtin (id {id}) is decorated Flat again - on an Adreno A6xx \
             that is a VK_ERROR_INVALID_SHADER_NV and a lost device; see the naga fork note \
             in the workspace Cargo.toml"
        );
    }
}

/// The control half: `Flat` itself is not the enemy. The fork's change only takes the
/// bool off the list; an input the VUID genuinely covers keeps its decoration. A
/// `flat in int` user varying is the everyday case - naga's user-varying path pushes
/// the Flat from the interpolation, a path the fork never touched - and if it ever
/// lost the Flat, the validation layers would be the ones saying so.
#[test]
fn a_flat_integer_varying_keeps_its_flat() {
    let probe = "#version 440\n\
flat in int wmProbe;\n\
layout (location = 0) out vec4 fragColor;\n\
void main() { fragColor = vec4(float(wmProbe), 0.0, 0.0, 1.0); }\n";

    let spv = spirv_words(probe, wgpu_mc::wgpu::naga::ShaderStage::Fragment);
    let (_, flats) = decorations(&spv);

    assert!(
        !flats.is_empty(),
        "a `flat in int` fragment input lost its Flat - the user-varying path in naga's \
         SPIR-V backend must keep pushing the decoration the VUID and the validation \
         layers expect"
    );
}

/// Prints the full GLSL of the pipeline the Adreno 640 died on, plus the SPIR-V word
/// count of each stage - the text the device-lost callback would have written into
/// `wgpu_mc_lost_pipeline/`. `--ignored --nocapture`.
#[test]
#[ignore]
fn armor_decal_glsl() {
    let (vert, frag) = final_glsl(ARMOR_DIRECTIVES, false);

    println!();
    println!("================ armor_decal_cutout_no_cull: VERTEX ================");
    println!("{vert}");
    println!("================ armor_decal_cutout_no_cull: FRAGMENT ================");
    println!("{frag}");

    let vert_spv = spirv_words(&vert, wgpu_mc::wgpu::naga::ShaderStage::Vertex);
    let frag_spv = spirv_words(&frag, wgpu_mc::wgpu::naga::ShaderStage::Fragment);
    println!(
        "SPIR-V: {} vertex words, {} fragment words",
        vert_spv.len(),
        frag_spv.len()
    );
}

/// Prints `entity_solid` - the same vanilla shader pair with no defines - whose
/// compilation provably succeeded on the same Adreno 640 the decal pipeline killed.
/// The delta between the two dumps is the two-sided-lighting half of the shader.
#[test]
#[ignore]
fn entity_solid_glsl() {
    let (vert, frag) = final_glsl(ENTITY_DIRECTIVES, true);

    println!();
    println!("================ entity_solid: VERTEX ================");
    println!("{vert}");
    println!("================ entity_solid: FRAGMENT ================");
    println!("{frag}");
}
