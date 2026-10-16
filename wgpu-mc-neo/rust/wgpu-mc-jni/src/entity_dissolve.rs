//! **Task 15 diagnostics: the Adreno 830 device loss on `pipeline/entity_cutout_dissolve`.**
//!
//! A Snapdragon 8 Elite (Adreno 830, driver build 7853d15211) running android.11 lost the
//! device the moment `minecraft:pipeline/entity_cutout_dissolve` - the first
//! `PER_FACE_LIGHTING` pipeline that run's pipeline order reached - was handed to the
//! driver, while sixteen earlier pipelines (including `entity_solid_offset_forward`,
//! which carries the same `NEW_ENTITY` vertex format, and neoforge's
//! `entity_translucent_cull`) compiled fine. The Task 14 fix is in that build - the
//! fragment stage's `BuiltIn FrontFacing` ships without `Flat` - so either this driver
//! rejects something *else* in the same family, or something unique to `DISSOLVE`.
//!
//! What the bytecode of `RenderPipelines.<clinit>` says this pipeline is, read straight
//! out of the 26.1.2 client jar: the `core/entity` shaders with
//! `ALPHA_CUTOUT` 0.1, `PER_FACE_LIGHTING` and `DISSOLVE`, the `NEW_ENTITY` vertex
//! format (`Sampler1` for the overlay), plus a second sampler the define adds,
//! `DissolveMaskSampler`. The two pipelines this module builds against each other are
//! the one that died and the one that provably compiled on the same device one slot
//! earlier - `entity_translucent_cull`: `ALPHA_CUTOUT` 0.1 and `Sampler1`, no
//! `PER_FACE_LIGHTING`, no `DISSOLVE` - so the diff of their SPIR-V is the shortest
//! possible list of suspects.
//!
//! The `#[ignore]`d test writes both modules (both stages) out as `.spv` for the
//! offline disassembler (`scripts/spvdiff.py`); the checked-in test pins the shapes
//! Task 14's fix promised plus the one `DISSOLVE`-specific hazard this module is here
//! to keep an eye on.

use crate::adreno_front_facing::{
    decorations, entity_locations, entity_vertex_layout, spirv_words,
};
use crate::preprocessing::process_shaders;
use std::collections::{HashMap, HashSet};

const ENTITY_VERT: &str = include_str!("testdata/entity_dissolve/vert.glsl");
const ENTITY_FRAG: &str = include_str!("testdata/entity_dissolve/frag.glsl");

/// What `RenderPipelines`' `<clinit>` builds for `pipeline/entity_cutout_dissolve`:
/// the `core/entity` shaders with `ALPHA_CUTOUT` 0.1, `PER_FACE_LIGHTING` and
/// `DISSOLVE`. `WM_CHUNK_PAD` is along for the ride because the preprocessor's
/// directive is global; no entity pipeline declares a per-draw block, so the value
/// does not reach these shaders (Task 14's log pinned the entity family before the
/// first pad line fired).
const DISSOLVE_DIRECTIVES: &str = "#version 440\n\
#define ALPHA_CUTOUT 0.1\n\
#define PER_FACE_LIGHTING true\n\
#define DISSOLVE true\n\
\n#define WM_CHUNK_PAD 2\n";

/// `entity_translucent_cull`, the pipeline that compiled right before the loss on the
/// same device: `ALPHA_CUTOUT` 0.1 and the overlay sampler, nothing else.
const TRANSLUCENT_CULL_DIRECTIVES: &str = "#version 440\n\
#define ALPHA_CUTOUT 0.1\n\
\n#define WM_CHUNK_PAD 2\n";

/// The per-pipeline sampler plan. `DISSOLVE` adds `DissolveMaskSampler` behind
/// `Sampler0`; the harness gives the shim pair the next free bindings, which need not
/// match the device's numbering - what decides driver acceptance is the module's
/// types and decorations, not which slot a binding sits in.
fn entity_locations_with_dissolve_mask() -> HashMap<String, (u32, u32)> {
    let mut locations = entity_locations(true);
    let next = locations.values().map(|(_, b)| b + 1).max().unwrap_or(0);
    locations.insert("DissolveMaskSampler_wm_texshim".into(), (0, next));
    locations.insert("DissolveMaskSampler_wm_sampler".into(), (0, next + 1));
    locations
}

fn final_glsl(directives: &str, with_dissolve_mask: bool) -> (String, String) {
    let locations = if with_dissolve_mask {
        entity_locations_with_dissolve_mask()
    } else {
        entity_locations(true)
    };

    let result = process_shaders(
        ENTITY_VERT,
        ENTITY_FRAG,
        directives,
        &locations,
        entity_vertex_layout(),
        &HashSet::new(),
    )
    .expect("the entity shaders parse");

    (result.vert, result.frag)
}

/// The regression that keeps Task 14's promise on the `DISSOLVE` variant: its fragment
/// stage still exercises `gl_FrontFacing` (via `PER_FACE_LIGHTING`), so the same shape
/// the Adreno 640 died over must stay absent here too - and the `DISSOLVE` texture
/// fetch that guards the extra `discard` must not have dragged a decoration problem in.
#[test]
fn entity_dissolve_front_facing_still_ships_without_flat() {
    let (_, frag) = final_glsl(DISSOLVE_DIRECTIVES, true);
    let spv = spirv_words(&frag, wgpu_mc::wgpu::naga::ShaderStage::Fragment);
    let (builtins, flats) = decorations(&spv);

    let front_facing: Vec<u32> = builtins
        .iter()
        .filter(|&(_, &builtin)| builtin == crate::adreno_front_facing::BUILT_IN_FRONT_FACING)
        .map(|(&id, _)| id)
        .collect();
    assert!(
        !front_facing.is_empty(),
        "the DISSOLVE fragment shader stopped exercising gl_FrontFacing, so this check \
         went vacuous"
    );
    for id in &front_facing {
        assert!(
            !flats.contains(id),
            "the FrontFacing builtin (id {id}) in the DISSOLVE fragment shader is decorated \
             Flat again - the naga fork note in the workspace Cargo.toml covers why that is \
             a lost device on Adreno"
        );
    }
}

/// Writes the four modules the offline diff needs. `--ignored --nocapture`.
#[test]
#[ignore]
fn entity_dissolve_vs_translucent_cull_modules() {
    let out = std::env::var("WM_SPIRV_DUMP_DIR").unwrap_or_else(|_| "/tmp/wm-spirv-dump".into());
    std::fs::create_dir_all(&out).expect("create the dump dir");

    let cases: [(&str, &str); 2] = [
        ("entity_cutout_dissolve", DISSOLVE_DIRECTIVES),
        ("entity_translucent_cull", TRANSLUCENT_CULL_DIRECTIVES),
    ];

    for (name, directives) in cases {
        let (vert, frag) = final_glsl(directives, name == "entity_cutout_dissolve");
        std::fs::write(format!("{out}/{name}.vert.glsl"), &vert).unwrap();
        std::fs::write(format!("{out}/{name}.frag.glsl"), &frag).unwrap();

        let vert_spv = spirv_words(&vert, wgpu_mc::wgpu::naga::ShaderStage::Vertex);
        let frag_spv = spirv_words(&frag, wgpu_mc::wgpu::naga::ShaderStage::Fragment);
        std::fs::write(
            format!("{out}/{name}.vert.spv"),
            bytemuck::cast_slice::<u32, u8>(&vert_spv),
        )
        .unwrap();
        std::fs::write(
            format!("{out}/{name}.frag.spv"),
            bytemuck::cast_slice::<u32, u8>(&frag_spv),
        )
        .unwrap();
        println!(
            "{name}: {} vert words, {} frag words -> {out}",
            vert_spv.len(),
            frag_spv.len()
        );
    }
}
