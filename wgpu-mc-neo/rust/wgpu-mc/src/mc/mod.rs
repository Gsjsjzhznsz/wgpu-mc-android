//! Rust implementations of minecraft concepts that are important to us.

use std::collections::HashMap;
use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, AtomicU64};

use arc_swap::ArcSwap;
use chunk::SectionStorage;
use glam::{IVec2, IVec3, ivec2};
use indexmap::map::IndexMap;
use minecraft_assets::schemas;
use minecraft_assets::schemas::blockstates::multipart::StateValue;
use parking_lot::{Mutex, RwLock};

use crate::mc::entity::{BundledEntityInstances, Entity};
use crate::mc::resource::ResourceProvider;
use crate::render::atlas::{Atlas, TextureManager};
use crate::render::pipeline::BLOCK_ATLAS;
use crate::util::BindableBuffer;
use crate::{Gpu, WmRenderer};

use self::block::ModelMesh;
use self::resource::ResourcePath;

pub mod block;
pub mod chunk;
pub mod direction;
pub mod entity;
pub mod resource;
pub mod visibility;
pub mod world_extent;
/// Take in a block name (not a [ResourcePath]!) and optionally a variant state key, e.g. "facing=north" and format it some way
/// for example, `minecraft:anvil[facing=north]` or `Block{minecraft:anvil}[facing=north]`
pub type BlockVariantFormatter = dyn Fn(&str, Option<&str>) -> String;

pub struct BlockManager {
    /// This maps block state keys to either a [VariantMesh] or a [Multipart] struct. How the keys are formatted
    /// is defined by the user of wgpu-mc. For example `Block{minecraft:anvil}[facing=west]` or `minecraft:anvil#facing=west`
    pub blocks: IndexMap<String, Block>,
    /// What every block *state* the JVM described says about the faces around it, keyed by the packed
    /// [`BlockstateKey`](block::BlockstateKey) - which is the only thing a baked section carries about
    /// a block, so it is the only key this table can use.
    ///
    /// It is filled when the block registry is built (`cacheBlockStates`, on the JNI side) from the
    /// masks `RegistryMixin` computes for each state, and it is what the terrain baker's face test
    /// reads: a neighbour's *state* decides whether a face is drawn, not its model - see
    /// `chunk::face_is_hidden`.
    pub face_flags: HashMap<u32, block::FaceFlags>,

    /// `(block index, slot in the watched list)` for each name in [`chunk::WATCHED_BLOCKS`] this
    /// registry has, resolved once when it is built.
    ///
    /// The baker counts a watched block's faces as it draws them - see [`chunk::WATCHED_BLOCKS`] for
    /// the four answers the three numbers tell apart - and this is how a block *index*, which is all a
    /// baked section carries, is recognised as one of the names somebody asked about.
    pub watched: Vec<(u16, u8)>,
}

impl BlockManager {
    pub fn new() -> Self {
        Self {
            blocks: IndexMap::new(),
            face_flags: HashMap::new(),
            watched: Vec::new(),
        }
    }
}

impl Default for BlockManager {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug)]
pub enum Block {
    Multipart(Multipart),
    Variants(IndexMap<Vec<(String, StateValue)>, Vec<Arc<ModelMesh>>>),
}

impl Block {
    /// The mesh a variant key's list holds at `variant`, or the list's first entry.
    ///
    /// **`variant` is the game's choice, and it is asked for per position.** A blockstate whose variant
    /// is a list of models is `WeightedVariants` at runtime, and the entry drawn for a given block is
    /// `WeightedList#getRandomOrThrow` on a `RandomSource` seeded with `blockState.getSeed(pos)`. The
    /// JVM has both, so it sends the *index* with the section rather than this side reproducing a
    /// random number generator and a weight table - see the `variants` field of
    /// `section::SectionBlocks`.
    ///
    /// **The list is the same order the JVM counted in**: both are the blockstate's own list, and a
    /// key whose models all failed to bake is dropped whole rather than shortened, so an index cannot
    /// point at a different model than the one the game picked. An index past the end is answered with
    /// the first entry: a list the two sides disagree about the length of is a model drawn at the
    /// variant it would have had without this channel, which is a wrong angle rather than a hole.
    pub fn get_model(&self, key: u16, variant: u8) -> Option<Arc<ModelMesh>> {
        Some(match &self {
            Block::Multipart(multipart) => multipart.keys.read().get_index(key as usize)?.1.clone(),
            Block::Variants(variants) => {
                let meshes = &variants.get_index(key as usize)?.1;

                meshes
                    .get(variant as usize)
                    .or_else(|| meshes.first())?
                    .clone()
            }
        })
    }

    pub fn get_model_by_key<'a>(
        &self,
        key: impl IntoIterator<Item = (&'a str, &'a StateValue)> + Clone,
        resource_provider: &dyn ResourceProvider,
        block_atlas: &Atlas,
        //TODO use this
        _seed: u8,
    ) -> Option<(Arc<ModelMesh>, u16)> {
        let key_map: HashMap<&str, &StateValue> = key.clone().into_iter().collect();

        let key_string = key
            .clone()
            .into_iter()
            .map(|(key, value)| {
                format!(
                    "{}={}",
                    key,
                    match value {
                        StateValue::Bool(bool) =>
                            if *bool {
                                "true"
                            } else {
                                "false"
                            },
                        StateValue::String(string) => string,
                    }
                )
            })
            .collect::<Vec<String>>()
            .join(",");

        match &self {
            Block::Multipart(multipart) => {
                {
                    if let Some(full) = multipart.keys.read().get_full(&key_string) {
                        return Some((full.2.clone(), full.0 as u16));
                    }
                }

                let mesh = multipart.generate_mesh(key, resource_provider, block_atlas)?;

                let mut multipart_write = multipart.keys.write();
                multipart_write.insert(key_string, mesh.clone());

                Some((mesh, multipart_write.len() as u16 - 1))
            }
            Block::Variants(variants) => {
                // A variant whose models all failed to bake is skipped rather than indexed into:
                // `variants` can hold an empty mesh list now that a bad model is dropped instead of
                // taking the registry with it (see `bake_blocks`).
                let full =
                    variants
                        .iter()
                        .enumerate()
                        .find(|(_, (variant_key, model_meshes))| {
                            !model_meshes.is_empty()
                                && variant_key.iter().all(
                                    |(variant_property_key, variant_property_value)| {
                                        key_map
                                            .get(&variant_property_key[..])
                                            .is_some_and(|v| v == &variant_property_value)
                                    },
                                )
                        })?;

                Some((full.1.1.first()?.clone(), full.0 as u16))
            }
        }
    }
}

#[derive(Debug)]
pub struct Multipart {
    pub cases: Vec<schemas::blockstates::multipart::Case>,
    pub keys: RwLock<IndexMap<String, Arc<ModelMesh>>>,
}

impl Multipart {
    /// The mesh a multipart block's cases add up to, or `None` if one of them cannot be baked.
    ///
    /// A multipart mesh is generated on demand - once per state a player actually looks at - so a
    /// model that fails here fails in the middle of the game rather than during the block cache, and
    /// `None` lets the caller fall back to the block it uses for a state with no model.
    pub fn generate_mesh<'a>(
        &self,
        key: impl IntoIterator<Item = (&'a str, &'a schemas::blockstates::multipart::StateValue)>
        + Clone,
        resource_provider: &dyn ResourceProvider,
        block_atlas: &Atlas,
    ) -> Option<Arc<ModelMesh>> {
        let apply_variants = self.cases.iter().filter_map(|case| {
            if case.applies(key.clone()) {
                Some(case.apply.models())
            } else {
                None
            }
        });

        match ModelMesh::bake(
            apply_variants.into_iter().flatten(),
            resource_provider,
            block_atlas,
        ) {
            Ok(mesh) => Some(Arc::new(mesh)),
            Err(err) => {
                log::warn!(
                    "wgpu-mc: a multipart model could not be baked ({err:?}); the state is drawn as \
                     bedrock, the same as one with no model at all"
                );
                None
            }
        }
    }
}

pub enum MultipartOrMesh {
    Multipart(Arc<Multipart>),
    Mesh(Arc<ModelMesh>),
}

/// Multipart models are generated dynamically as they can be too complex
pub struct BlockInstance {
    pub render_settings: block::RenderSettings,
    pub block: MultipartOrMesh,
}

#[derive(Default, Clone)]
pub struct SkyState {
    pub color: [f32; 3],
    pub angle: f32,
    pub brightness: f32,
    pub star_shimmer: f32,
    pub moon_phase: i32,
}

#[derive(Default, Clone)]
pub struct RenderEffectsData {
    pub fog_start: f32,
    pub fog_end: f32,
    pub fog_shape: f32,
    pub fog_color: [f32; 4],
    pub color_modulator: [f32; 4],
    pub dimension_fog_color: [f32; 4],
}

/// What the arena's buffer is used for: written by the section feed, read as vertices and indices by
/// the graph's draws, bound as a storage buffer by the shader that fetches a section's quads, and -
/// since the compacted draw (`terrain_mega`) - **a copy source**: the frame's own index ranges are
/// blitted out of an arena into the frame's compacted index buffer before the terrain pass opens,
/// and a `copy_buffer_to_buffer` asks its source for `COPY_SRC`. Without the flag every one of those
/// copies fails the encoder's validation, the frame's submission is refused whole, and the world
/// draws nothing while the game's own encoder keeps the menus up - the shape the android.35 report
/// captured as two validation errors a frame and a black screen.
///
/// One constant rather than the same flags at every creation site - the pool and the buffers are made
/// together by [`Scene::set_arena_slots`] and `WmRenderer::grow_arena`, and a flag missing from one of
/// those sites is a validation error at the first draw.
///
/// Growth appends another buffer rather than allocating a bigger one and copying the old contents
/// over, so nothing copies an arena *whole* - the compacted draw's per-frame slices are the only
/// copies out of it. See [`ARENA_BUFFERS`].
pub(crate) const ARENA_USAGE: wgpu::BufferUsages = wgpu::BufferUsages::COPY_DST
    .union(wgpu::BufferUsages::COPY_SRC)
    .union(wgpu::BufferUsages::VERTEX)
    .union(wgpu::BufferUsages::STORAGE)
    .union(wgpu::BufferUsages::INDEX);

/// **The ceiling on what the arena may hold in total**, as policy rather than as a measurement.
///
/// A per-buffer limit is not a memory bound: `ARENA_BUFFERS` of them is four times whatever the device
/// will make in one, and with the arena appending rather than refusing, a player flying forward grew it
/// without an upper limit. That stopped being a bug about holes and became one about memory, so there is a
/// bound stated here and enforced where growth happens (`WmRenderer::grow_arena_if_asked`).
///
/// **It bounds what the pool holds, and that is not the same as what it asks for.** A run died in
/// `create_buffer` with this budget nowhere near being reached: the pool held 312 MB of the 5 GB, and the
/// *request* was `arena_cap_slots` slots - **seventeen gigabytes**, because that ceiling is
/// `max_buffer_size / 4` clamped to a `u32` and this device's `max_buffer_size` is past 16 GB. A budget on
/// the total cannot catch a single oversized allocation; sizing the allocation can, and that is what
/// `grow_arena_if_asked` does now. The other numbers are in [`Scene::arena_memory_budget`].
///
/// The number that actually binds is `Scene::arena_memory_budget` - this, or
/// `max_buffer_size` x [`ARENA_BUFFERS`], whichever is smaller - because this constant alone is a budget
/// for the machine it was written for. On this one the policy is the smaller and prints as:
///
/// ```text
/// wgpu-mc: the section arena: up to 4 buffer(s) of 4294967295 slot(s) (16383 MB each, the device's own
/// `max_buffer_size`), a budget of 4768 MB, of which the device's limits allow 65535 MB
/// ```
///
/// **And no total can be read off a Vulkan device.** `MemoryBudgetThresholds` - the one API for "start
/// returning OOM at a percentage of the native budget" - is implemented for **DX12 only**
/// (`wgpu-hal/src/dx12/device.rs`, reading `DXGI_MEMORY_SEGMENT_GROUP_LOCAL`); the Vulkan backend has no
/// equivalent and neither exposes the budget as a number. That is why `grow_arena_if_asked` also *catches*
/// a refused allocation instead of trusting any of this.
///
/// The number to read in the log is this one being reached:
///
/// ```text
/// Rebuilds: ... N left to Minecraft because the arena is full, ...
/// ```
///
/// That count climbing is the budget, and it is the one number that says whether the arena is holding the
/// view the player asked for.
pub const ARENA_MEMORY_BUDGET: u64 = 5_000_000_000;

/// **The largest pool a view is ever worth: what the record table can name, with room to spare.**
///
/// A slot of arena that no draw can reach is not capacity, it is pressure. Every (section, layer) the
/// arena holds names itself through one [`SECTION_DRAW_CAPACITY`]-slot record table, and the table is the
/// binding ceiling on what the terrain path can serve - past it a section is refused and drawn by
/// Minecraft's own meshes no matter how much arena sits behind it. A view that asks the arena for more
/// slots than the records can name at plausible density is asking for memory the draws can never touch.
///
/// The density this is written at is four thousand slots per record, a quarter over what the sessions
/// measured: the phone's 12-chunk world held 24.3M slots across ~7,100 records (3.4k slots each), and
/// the 32-chunk run that found this constant held 22M across ~11,400 (1.9k each - the far view is
/// shallower). At 4k the table's whole reach is 512 MB of arena; a world dense enough to exhaust that
/// before the records (an ocean at 32 chunks, where one section's water surface runs large) is refused
/// into Minecraft's own meshes - the ordinary, graceful answer - rather than into a device that cannot
/// breathe.
///
/// **Why this is a policy constant and not arithmetic**, when it is derived: it rides on
/// [`SECTION_DRAW_CAPACITY`], so a future index encoding that widens the table widens the ceiling with
/// it, and the two move as one. What it does *not* ride on is the device: the memory it saves is exactly
/// the memory a phone was dying of, and the memory a desktop wastes is a rounding error. The phone run
/// that demanded it: a switch to 32 chunks asked [`crate::mc::chunk::arena_slots`] for 285,660,000 slots
/// and was granted all of it - ten buffers, **1,216 MB** - held 22M slots (6%, 83 MB), and the device
/// spent the session at a frame a second with the system UI crawling under the swap the arena's reach
/// caused. The view's own ask was thirteen times the memory its draws could name.
pub const ARENA_SLOTS_CEILING: u32 = (SECTION_DRAW_CAPACITY * 4_096) as u32;

/// The arena's budget **for this device**, from the two numbers a device actually gives.
///
/// `max_buffer_size` is a *per-buffer* limit, and [`ARENA_BUFFERS`] of them is the most the device's own
/// limits would let the arena hold; [`ARENA_MEMORY_BUDGET`] is the policy ceiling on top of that, so a
/// device that will make a very large buffer does not get an arena several times that by arithmetic alone.
/// On the machine this was written on the two agreed; here the policy is the smaller and is what binds.
///
/// Separate from the constant and a function of one argument so that the arithmetic can be tested - the
/// interesting cases are the two ends, where either the device or the policy decides.
pub fn arena_memory_budget(max_buffer_size: u64) -> u64 {
    max_buffer_size
        .saturating_mul(ARENA_BUFFERS as u64)
        .min(ARENA_MEMORY_BUDGET)
}

/// **How large an appended arena should be: what the view asks for, not what the device allows.**
///
/// This is the function that decides whether a full arena asks the driver for something it can have. It
/// used to be `Scene::arena_cap_slots` - the device's own `max_buffer_size` over four, clamped to a `u32` -
/// and on the machine this was found on that ceiling is `u32::MAX`, so every growth asked for **17 GB in
/// one buffer**. The driver refused, wgpu treats a refused `create_buffer` as fatal, and the process died
/// with `wgpu error: Out of Memory` and a terrain line as the last thing in the log. The same code on the
/// machine it was written for asked for 2 GB (`536870911` slots, from the historical log line) and worked,
/// which is exactly why the ceiling looked like a reasonable thing to ask for.
///
/// So the size comes from the world instead, in four numbers, each of which is a bound that can be
/// explained on its own:
///
///  * **`target`** - what [`chunk::arena_slots`] says the current render distance wants. The pool should
///    reach this and not more, and it is the number the growth exists for. This is the *size* now rather
///    than the ceiling. Callers are expected to pass it through [`chunk::arena_slots_for_view`], which
///    holds it under [`ARENA_SLOTS_CEILING`] - and the min chain below applies the ceiling again, so a
///    caller that forgot inherits the policy rather than the memory bill.
///  * **`largest_section`** - a floor. An arena smaller than the largest section this session has meshed
///    cannot hold one, and holding sections is the whole point.
///  * **`cap_slots`** - the device's per-buffer ceiling, which is still a real limit and the reason the
///    request is clamped at all. On this machine it is `u32::MAX` and therefore not the binding one.
///  * **`budget`** - [`Scene::arena_memory_budget`], less what the pool already holds. The appended arena
///    is what tips the pool over it otherwise, and the budget is the only bound on the *total*.
///
/// `0` means "do not append": nothing is wanted and nothing has been meshed, so there is no size to ask
/// for and the caller should treat the refusal as one growth cannot answer.
pub fn arena_growth_slots(
    target: u32,
    pool_slots: u32,
    largest_section: u32,
    cap_slots: u32,
    budget: u64,
) -> u32 {
    // What the budget still allows, in slots. `u32::MAX` when it allows more than a `u32` can count.
    let room = budget.saturating_sub(pool_slots as u64 * 4) / 4;
    let room = room.min(u32::MAX as u64) as u32;

    target
        .max(largest_section)
        .min(cap_slots)
        .min(room)
        .min(ARENA_SLOTS_CEILING)
}

/// The most u32 slots **one** arena buffer may hold, from the two limits its two uses have to satisfy.
///
/// The two limits are not the same limit. `max_buffer_size` is what `create_buffer` may make; a bind
/// group that binds the buffer *entire* - which is how the arena is bound, see `BindableBuffer` - is
/// refused past `max_storage_buffer_binding_size`, and wgpu's default limits put that at **128 MiB**
/// while the per-buffer ceiling is a GiB or more. An arena buffer sized between the two is a buffer the
/// device is happy to make and the bind group cannot bind: `create_bind_group` does not answer a
/// `Result`, the refusal goes to the uncaptured error handler, and what it leaves behind is an *invalid*
/// bind group whose every `set_bind_group` drops the whole submit - the world black, with the terrain
/// lines reporting the very sections that could not draw.
///
/// Found on Adreno 640: a 12-chunk view reported `arena_slots(12)` = 50,460,000 slots, `set_arena_slots`
/// made one buffer of 192 MB out of them, and the world was black from the first frame in it - while an
/// 8-chunk view (105 MB) had always worked, which is why the other devices never saw it.
///
/// The smaller of the two byte limits, in whole slots (a slot is four bytes; both limits are byte counts
/// and a slot cannot be split).
pub fn arena_buffer_slot_cap(max_buffer_size: u64, max_binding_size: u64) -> u32 {
    (max_buffer_size.min(max_binding_size) / 4).min(u32::MAX as u64) as u32
}

/// Splits a pool of `slots` into **per-buffer sizes** no larger than `per_arena` slots each.
///
/// The one place the split is decided, so that the two things that have to agree - the allocators in
/// `SectionStorage` and the buffers in `Scene::chunk_buffers` - are built from the same list rather than
/// from two computations of the same arithmetic. `Scene::set_arena_slots` and `Scene::new` both feed
/// their list to both sides.
///
/// The last buffer carries the remainder, so a 192 MB pool on a 128 MiB device is a 128 MiB buffer and a
/// 64 MB one - and never a trailing zero, which wgpu refuses in `create_buffer`. The list is never
/// empty: a caller that asks for zero slots is answered with one buffer of zero slots is *not*
/// answered at all - zero-sized buffers are refused - so the floor is one slot.
pub fn split_arena_slots(slots: u32, per_arena: u32) -> Vec<u32> {
    let per_arena = per_arena.max(1);
    let mut split = Vec::new();
    let mut left = slots.max(1);

    while left > 0 {
        let take = left.min(per_arena);
        split.push(take);
        left -= take;
    }

    split
}

/// How many arena buffers the arena may grow to.
///
/// Each is capped at the device's `max_buffer_size`, so this is the arena's ceiling in buffers - and
/// [`ARENA_MEMORY_BUDGET`] is the ceiling that actually matters, because four buffers of 1.31 GB is more
/// than a 32-chunk view needs and more than this renderer should be holding. This one exists so that a
/// device with an unusually large `max_buffer_size` cannot reach the byte budget with a single buffer
/// and then stop growing before a second would have fit.
///
/// **Thirty-two, where four was enough for years**, because the cap on one buffer changed. The binding
/// limit (`arena_buffer_slot_cap`) is the real per-buffer ceiling now - 128 MiB on wgpu's default
/// limits, against a `max_buffer_size` of a GiB or more - so four buffers held 512 MB, and a 12-chunk
/// view (192 MB) plus a growth or two was most of it. Thirty-two of them is 4 GiB of *reach*; the byte
/// budget is what actually binds, and buffers are made only as the world asks for them.
pub const ARENA_BUFFERS: usize = 32;

/// One terrain pass's own draw buffers, and why one pair for the whole frame is not enough.
///
/// The terrain pass draws its sections with `multi_draw_indexed_indirect`, and a multi-drawn section
/// cannot be handed anything per draw except through a buffer or the indirect record itself - an
/// immediate is set once for the whole call. So each draw's section position and its slot in the arena
/// travel in [`SectionDraw`], the vertex stage reads them by `instance_index`, and the pass writes that
/// buffer once per pass.
///
/// **One pair of buffers per pass rather than one for the frame**, and the reason is where
/// `Queue::write_buffer` lands:
///
/// ```text
/// Calls to `write_buffer()` do *not* submit the transfer to the GPU immediately. They begin GPU
/// execution only on the next call to `Queue::submit()`, just before the explicitly submitted
/// commands.
/// ```
///
/// So every write a frame makes is applied at the head of that frame's submission, before every draw
/// recorded in it. A single buffer written twice in one frame - which is what the opaque group is, two
/// terrain pipelines recorded into one encoder by one `render` call - has the *second* pipeline's
/// records in it when the *first* pipeline's draws execute. They would both draw the last pass's
/// sections, out of the last pass's arena slots. Two buffers are the whole fix: pass A's writes land in
/// A's buffer and pass B's in B's, and the submission's ordering stops mattering.
///
/// The cost is the memory, which is about a megabyte per pass across the two buffers - see
/// [`SECTION_DRAW_CAPACITY`].
pub const SECTION_DRAW_SLOTS: usize = 3;

/// How many draws one pass may hand to the GPU *indirectly*, which is the capacity of every slot's
/// `DrawIndexedIndirectArgs` buffer: 24,000 records of five `u32`s.
///
/// Above this the pass falls back to one `draw_indexed` per section rather than dropping anything - the
/// indirect buffer is a batching device, not a limit on what can be drawn.
///
/// It was 10,000 for as long as the batching sat behind a switch, because nobody had measured a pass
/// against it. The port's phones did: a 12-chunk view measured solid and cutout passes at 7,600-8,200
/// calls a piece, which is the fallback two-thirds of the way up the ladder - and a 16-chunk view
/// climbs past it, silently losing the batching while looking exactly like a device that cannot batch.
/// 24,000 keeps a 16-chunk view inside the indirect path with room to spare, at the cost of half a
/// megabyte per slot.
pub const INDIRECT_DRAW_CAPACITY: usize = 24_000;

/// How many slots the section-record table holds: one [`SectionRecord`] per **(section, layer)**
/// whose geometry is in the arena.
///
/// A record slot is what a section's indices name, so this is the number of (section, layer) pairs
/// that may hold geometry at once. It kept its name - `SECTION_DRAW_CAPACITY` - from the two
/// generations when it capped a per-frame draw list instead, and the number survived the change of
/// meaning because it was sized generously enough to be the same bound in practice: a 16-chunk view
/// holds about six thousand sections, three layers each is under half of this table, and the old
/// per-frame ceiling this number used to be was sized off the same view. A world that exhausts it is
/// refused like an arena-full, which is a log line rather than a hole. See [`SectionRecord`].
pub const SECTION_DRAW_CAPACITY: usize = 1 << 15;

/// One terrain draw's arguments, read by the vertex stage out of a storage table the section's
/// record slot names: sixteen bytes, written once at bake and never again.
///
/// Four `u32`s. A struct with a `vec3` in it was the other shape this could have had and it is the
/// one to avoid: `vec3<i32>` is sixteen bytes of storage for twelve bytes of data, and three scalars
/// say what the four offsets are without anyone having to know the alignment rule.
///
/// **How a draw finds its record, and why it is not `first_instance`.** The record used to travel
/// three ways, one per generation of this path: a storage binding indexed by
/// `@builtin(instance_index)` (the first device the batching turned on on accepted the feature and
/// then did not propagate the number into the builtin, and the world drew hollow), then an
/// instance-step vertex attribute offset by the record's `first_instance` - and the same Adreno 640
/// driver does not carry `firstInstance` into the fixed-function fetch either, which the
/// four-record probe proved at device creation and the terrain pass fell back to one draw per
/// section for the whole of android.26-28. The channel that survives on every driver is the one the
/// per-section fallback always used: **the index buffer itself**. Each section's indices are baked
/// with its record slot in the bits above the local vertex id - see [`RECORD_ID_SHIFT`] - so the
/// shader reads `section_records[vi >> RECORD_ID_SHIFT]` and the indirect record needs no per-draw
/// channel at all: `first_instance` and `base_vertex` are zero in every record, on every path.
///
/// **The position is absolute now, and the camera subtraction is the shader's.** A record written
/// once at bake cannot carry the camera-relative offset the per-frame record carried, so `x`, `y`
/// and `z` are the section's own coordinates and the shader subtracts the camera's section, which
/// travels in the pass' immediate. Three integer subtractions per vertex buy a record upload that
/// happens per bake instead of per frame.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, bytemuck::Pod, bytemuck::Zeroable)]
pub struct SectionRecord {
    /// The section's position **in the world, in sections** - what the bake was asked for, and what
    /// the shader subtracts the camera's section from. See [`Scene::camera_section_pos`].
    pub x: i32,
    pub y: i32,
    pub z: i32,
    /// The u32 slot in the arena the section's vertices start at, which is what `chunk_data` is
    /// indexed by in the shader: the vertex the index names is at `word_base + local_vertex * 4`.
    pub word_base: u32,
}

/// The bits of an index value above the local vertex id, which carry the section's record slot.
///
/// A baked index is `(record_slot << RECORD_ID_SHIFT) | local_vertex`, and the two halves divide a
/// `u32` as `15 + 17`. **The local half is sized by the geometry, not by taste**: a 16x16x16 section
/// has 4 096 blocks and a face per block per direction at the worst, which is 24 576 quads and
/// 98 304 vertices - under the 131 072 (`1 << 17`) the local half holds, with the worst section the
/// mesher can possibly produce still a third under the cliff. The slot half is sized by
/// [`SECTION_DRAW_CAPACITY`], which is `1 << 15` - the two halves sum to exactly one `u32`, and a
/// capacity that grew past that would need this shift to move with it.
pub const RECORD_ID_SHIFT: u32 = 17;

/// The mask of the local vertex id inside a baked index: the bits below [`RECORD_ID_SHIFT`].
pub const LOCAL_VERTEX_MASK: u32 = (1 << RECORD_ID_SHIFT) - 1;

/// Puts a record slot into an index value whose low bits are the local vertex id.
#[inline]
pub fn index_with_record(slot: u32, local_vertex: u32) -> u32 {
    debug_assert!(
        local_vertex <= LOCAL_VERTEX_MASK,
        "a 16x16x16 section cannot bake {} vertices - the index format holds {}",
        local_vertex,
        LOCAL_VERTEX_MASK + 1
    );
    debug_assert!(
        slot < SECTION_DRAW_CAPACITY as u32,
        "record slot {} past the table's {} slots",
        slot,
        SECTION_DRAW_CAPACITY
    );

    (slot << RECORD_ID_SHIFT) | local_vertex
}

pub struct Scene {
    pub section_storage: RwLock<SectionStorage>,
    /// The section the camera is in, sent by the JVM once per frame with the matrices it belongs to.
    ///
    /// All three axes, and two things read it:
    ///
    ///  - the arena's **trim**, which only needs `x` and `z` - a vertical slice of the world is loaded
    ///    all at once, so nothing is trimmed by height;
    ///  - the terrain pass's **transform**, which is where the precision of the whole renderer is
    ///    decided. A section is drawn at `(section - camera_section) * 16 + a local position`, which is
    ///    a small number, and the view matrix carries the camera's offset *within its own section*. The
    ///    absolute position never appears, and that is the point: `x + 30000` in `f32` has a step of
    ///    0.004 blocks, which is orders of magnitude more than the depth buffer can forgive - and it is
    ///    what made the ground and the shadow lying on it fight over which of them is in front. See
    ///    `TerrainPass.sendCameraMatrices` for the other half, and `@geo_terrain` in
    ///    [`crate::render::graph`] for what is done with it.
    pub camera_section_pos: RwLock<IVec3>,
    /// The camera section the arena was last trimmed against.
    ///
    /// Trimming walks every section the arena holds, so it happens when the camera crosses into
    /// another section rather than once per frame: a frame where the camera stayed put has nothing to
    /// free, and the walk would cost more than the frames it ran on.
    pub trimmed_section_pos: RwLock<IVec2>,
    /// **The arena's buffers, one entry per pool in `SectionStorage`.**
    ///
    /// A list because one buffer is not enough: each is capped at the device's `max_buffer_size` - 1.31 GB
    /// on the machine this was measured on - while a 32-chunk view wants about 2.6 GB of meshed sections.
    /// With one buffer the arena was half the size it needed to be, and every section it could not hold
    /// was one neither renderer drew. See [`ARENA_BUFFERS`] for the limit.
    ///
    /// Index `i` here is `SectionRanges::buffer == i`, and the two lists are kept in step by
    /// `WmRenderer::grow_arena` and `SectionStorage::grow_pool` - the only things that add either. The
    /// list is replaced whole rather than mutated, so a frame still drawing from it keeps its own
    /// reference; index 0 is the buffer a world starts with.
    pub chunk_buffers: ArcSwap<Vec<Arc<BindableBuffer>>>,

    /// The pool size a section did not fit into asked to be grown to, or zero.
    ///
    /// A request rather than a growth, because the two happen in different places: the refusal is
    /// noticed while a frame's bakes are being written into the buffer that is bound right now, and
    /// growing has to happen before that load - a drain that swapped buffers halfway through would
    /// write the rest of its sections into the wrong one. See `WmRenderer::grow_arena`.
    pub pending_arena_growth: AtomicU32,

    /// The largest pool this device can have in **one** buffer, in u32 slots: `max_buffer_size / 4`,
    /// clamped to what a `u32` can count.
    ///
    /// Read once, from the device the arena's buffer is created on. What it caps is a single *appended*
    /// arena - growth is sized by the shortfall and never asks for more than this - and a ceiling rather
    /// than a policy: growth only happens because a section did not fit, so a session that never refuses
    /// never reaches it.
    ///
    /// **`u32::MAX` is a real value here and it was a real bug.** `max_buffer_size` is past 16 GB on the
    /// device this was found on, so the clamp decides and the ceiling is 17 GB - which every growth used to
    /// request in one buffer, because an appended arena was created at this size "because there is no
    /// reason for one to be smaller". The driver refused, and wgpu treats a refused `create_buffer` as
    /// fatal. See `WmRenderer::grow_arena_if_asked`: it is a *cap* now, not a size.
    pub arena_cap_slots: u32,

    /// **How much video memory this arena is allowed to use, on this device.** See
    /// [`arena_memory_budget`] for the arithmetic and [`ARENA_MEMORY_BUDGET`] for why a total cannot be
    /// read off a Vulkan device at all.
    ///
    /// Read beside `max_buffer_size` rather than derived where it is used, because growth happens on the
    /// render thread and the plan should not be recomputed - or drift - per refusal burst. What enforces
    /// it is `WmRenderer::grow_arena_if_asked`.
    pub arena_memory_budget: u64,

    /// **The terrain indirect arguments, one buffer per terrain pass.** See [`SECTION_DRAW_SLOTS`]
    /// for why there is one per pass rather than one for the frame.
    ///
    /// `indirect_buffers[i]` is the `DrawIndexedIndirectArgs` array the batched path draws from.
    /// Index `i` is a *slot*, not a pipeline: `BoundPipeline::draw_slot` says which pass owns which,
    /// and two passes may not share one - which is the whole of what the slot assignment has to get
    /// right. The draw *records* are not here: they live in [`Scene::section_records`], one table
    /// every pass reads through the indices' record slots.
    pub indirect_buffers: Vec<Arc<wgpu::Buffer>>,

    /// **The table the terrain shaders read a section's record out of**, one [`SectionRecord`] per
    /// record slot, written per bake and never per frame.
    ///
    /// It is bound at group 2 of the terrain pipelines (`@bg_section_draws`, the name it has carried
    /// through two generations of this path), and the record a draw reads is named by the slot the
    /// draw's own indices carry in their high bits - see [`RECORD_ID_SHIFT`]. One table rather than
    /// one per pass, because a record describes a section rather than a draw: three passes read the
    /// same entry, and a per-pass copy would be three writes per bake agreeing to stay equal.
    pub section_records: BindableBuffer,

    pub entity_instances: Mutex<HashMap<String, BundledEntityInstances>>,
    pub sky_state: ArcSwap<SkyState>,

    /// The sky's geometry, built once per scene and reused every frame: the sun, the domes and the
    /// fog sphere are constants and the moon is one buffer per phase, and rebuilding them every
    /// frame was eight GPU allocations a frame for nothing. See
    /// [`crate::render::sky::SkyGeometry`].
    pub sky_geometry: std::sync::OnceLock<crate::render::sky::SkyGeometry>,

    pub render_effects: ArcSwap<RenderEffectsData>,

    pub depth_texture: RwLock<wgpu::Texture>,

    /// The sections Minecraft's own occlusion culling says are visible this frame, or `None` before the
    /// JVM has ever sent a list.
    ///
    /// **The reason this exists is that a frustum is not occlusion culling.** The terrain pass culled
    /// its sections against the camera's frustum, which is what the game's own renderer does *first* and
    /// what its [`SectionOcclusionGraph`] then throws most of away: the graph walks outward from the
    /// camera through the sections it can actually see - a section is reached only through a neighbour
    /// whose face toward it is not fully opaque - so the ~2,000 sections inside the frustum of a normal
    /// view become the few hundred that are not behind a hill. Drawing the frustum's set means submitting
    /// every section in a cave system, a ravine or a forest floor, and the vertex stage then transforms
    /// geometry the depth test discards. `visibleSections` is that graph's answer, once per frame.
    ///
    /// `None` and `Some(empty)` are **different**, which is why this is an `Option`: `None` is "the JVM
    /// has not told this side anything", where a frustum is the best answer there is, and `Some(empty)`
    /// is "the game looked and saw nothing", where drawing the arena would be drawing exactly what the
    /// game decided not to. A session with the terrain switch off never sends either.
    ///
    /// The positions are absolute section coordinates, the same space [`Scene::camera_section_pos`] and
    /// the arena's keys are in. See `set_visible_sections`.
    ///
    /// [`SectionOcclusionGraph`]: https://minecraft.wiki/w/Occlusion_culling
    pub visible_sections: RwLock<Option<HashSet<IVec3>>>,

    /// How many times the list above has been replaced. See [`Scene::set_visible_sections`].
    ///
    /// **The terrain frame cache keys on this**, because the list is one of the things the gather reads:
    /// a new list is a gather that has to run again. A counter rather than the list itself, because
    /// comparing two `HashSet`s is the same walk as the gather it would be deciding about - see
    /// `TerrainFrameKey` in `render/graph.rs`.
    ///
    /// It is a revision and not a "dirty" flag, so a cache that is several revisions behind cannot be
    /// mistaken for a current one; and it is bumped by the only writer rather than by the reader, so a
    /// list that is set twice in one frame invalidates twice.
    pub visible_sections_revision: AtomicU64,

    /// **The sections the arena has taken over since the list above was last replaced.**
    ///
    /// This is the only thing that tells a *stale* answer apart from an *occluded* one, and without it
    /// the gather has to choose between two wrong answers. `visibleSections` is refilled only when the
    /// camera has turned by more than two degrees or the game's occlusion graph reports a change
    /// (`LevelRenderer#applyFrustum`), so between refills it is a snapshot - and a section this side has
    /// just taken over is exactly what it has not caught up with. Minecraft's mesh for that section has
    /// already been dropped (`SectionCompilerMixin` does that when the bake is taken), so reading "not
    /// named" as "occluded" for it leaves a section neither renderer draws: a 16x16x16 hole, appearing
    /// while terrain streams in. See the README's "the occlusion list is a snapshot".
    ///
    /// A position lands here when it enters the arena - the publish in the section drain, which is where
    /// this side's claim on it becomes true - and the set is **emptied whenever a list arrives**, which
    /// is exactly "the game's answer has caught up". A section in it that the list does not name is
    /// answered by the frustum instead; every other unnamed section is still culled, so the occlusion
    /// culling survives for the steady state, where all of its value is.
    pub sections_since_the_list: RwLock<HashSet<IVec3>>,

    /// **The camera section the list above was built at**, captured when the list arrives.
    ///
    /// This is the third and last thing that tells a *stale* answer apart from an *occluded* one, and
    /// it exists because the game refills its list on a two-degree turn or a graph update - **never on
    /// movement**. A camera that flies a straight line over terrain the graph has already judged never
    /// triggers either: the list keeps describing the camera's first position, and every section the
    /// movement has since exposed is unnamed - and, not being "too new" either (it entered the arena
    /// before the list did), it is culled while Minecraft's mesh for it stays suppressed. That is the
    /// 16x16x16 hole a straight flight leaves behind it, the one `sections_since_the_list` cannot catch
    /// because the list is older than the section, not newer.
    ///
    /// The gather compares this against the frame's own camera section: a crossing the list did not
    /// ride through is a list that has not seen what the camera sees, and that frame asks the frustum
    /// about everything until a refill arrives. Two-degree turns refill far more often than a section
    /// is crossed, so the windows this opens are short - and a frame drawn from the frustum is
    /// over-drawn by exactly the culling's savings, not a hole in it.
    pub visible_sections_camera: RwLock<IVec3>,

    /// **The sections whose publish the JVM has not been told of yet**, as positions, drained by
    /// `WgpuNative#publishedSections` once a client tick.
    ///
    /// This is the other half of the same fact [`Scene::sections_since_the_list`] carries, told to the
    /// other renderer: the gather needs to know a section is drawable *this frame*, and the JVM needs to
    /// know a section's bake has *landed* so it can retire Minecraft's own mesh for it. Both read the
    /// same publish - [`Scene::note_section_taken`] - and neither can read the other's copy: the JVM's
    /// is a drain (a section is announced once), this one is a snapshot reset by the next list.
    ///
    /// **Why the JVM has to be told at all.** The mesh suppression is gated on the publish now, not on
    /// the offer: a section whose bake is still in the queue keeps Minecraft's mesh, which is what
    /// draws it while it waits. The day the bake lands, the game's mesh has to go - or the section is
    /// drawn twice, at the cost of both renderers and with the two meshes disagreeing about shading in
    /// the meantime. The drain is the announcement; the JVM answers it with one redirty rebuild that
    /// compiles the section with the geometry skipped, because the publish now says it is Rust's.
    ///
    /// A `Vec` behind a `Mutex`, and capped: the drain runs once a tick and a frame's publishes are a
    /// burst the queue drains in one go, so the steady state is an empty vector - the cap exists so a
    /// JVM that stops ticking (a paused world) cannot collect a session of publishes. Past the cap a
    /// publish is counted and dropped, and the failure mode is the old one - the game's mesh stays for
    /// that section, which is a picture drawn twice rather than a picture missing.
    pub published_pending: std::sync::Mutex<Vec<IVec3>>,
}

/// The most publishes [`Scene::published_pending`] will hold between drains.
///
/// A frame's publishes are one burst and a tick drains it, so this is sized an order past the
/// largest burst a session is known to have produced: the 32-chunk session that named the hole this
/// channel closes published at about thirty sections a second, and a tick is a fiftieth of that - the
/// cap is a pause-proof bound, not a working-set size.
pub const PUBLISHED_PENDING_CAP: usize = 8192;

/// How many publishes the cap above has dropped, since the last drain. For the report only.
pub static PUBLISHED_DROPPED: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

impl Scene {
    /// Replaces the list of sections the game's own occlusion graph says are visible.
    ///
    /// **The one writer of [`Scene::visible_sections`]**, so that the revision beside it cannot fall out
    /// of step with the list: the two are one fact - what the gather reads - and a second place that
    /// wrote the list without bumping the revision would be a frame drawn from a stale one.
    ///
    /// **The set is kept, and the positions arrive as they are read.** The JVM sends a whole list every
    /// time the game rebuilds it, so the set that holds it is cleared and refilled rather than replaced -
    /// `HashSet::clear` keeps the table's capacity and its control bytes, which is the allocation and the
    /// growth this does not pay twice - and the section positions are taken as an iterator, so the only
    /// thing that happens to a key between the JVM's array and the set is the hashing that a set costs
    /// anyway. Building a set on the JVM's side of this call only to move it in here was one allocation
    /// and one extra pass over the list per rebuild.
    pub fn set_visible_sections<I>(&self, visible: I)
    where
        I: IntoIterator<Item = IVec3>,
    {
        // **And the list has caught up**, so nothing is "too new to have been seen" any more: a section
        // this side took over before this call is the list's business like any other, and if the game's
        // graph still does not name it, it is occluded rather than unseen. See
        // [`Scene::sections_since_the_list`].
        self.sections_since_the_list.write().clear();

        let mut visible = visible.into_iter();

        // The guard is dropped before the revision moves, which is the order that carries the guarantee: a
        // reader decides whether to use its cached gather from the revision and reads the list afterwards, so
        // the new list has to be *visible* before the revision that says it is there. Dropping the guard
        // first makes exactly that ordering true - the list is published, and only then does the count move.
        {
            let mut slot = self.visible_sections.write();

            match slot.as_mut() {
                Some(set) => {
                    set.clear();
                    set.extend(&mut visible);
                }
                // The first list of a session, and the only one that has to build the set.
                None => *slot = Some(visible.collect()),
            }
        }

        self.visible_sections_revision
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);

        // **And the camera the list describes, captured here.** The caller is on the render thread of
        // the frame whose `applyFrustum` just refilled the list, and this side's `camera_section_pos`
        // is that same frame's - sent beside the matrices before the gather runs - so the value read
        // here is the position the game's octree walk started from. See
        // [`Scene::visible_sections_camera`] for what the gather does with it.
        *self.visible_sections_camera.write() = *self.camera_section_pos.read();
    }

    /// Records that the arena has taken one section over. See [`Scene::sections_since_the_list`].
    ///
    /// Called from the section drain at the publish, which is the moment the claim becomes true: before
    /// it the section is not in the arena and the gather cannot reach it, and after it the section is
    /// this side's while the game's list may not have heard of it yet.
    pub fn note_section_taken(&self, pos: IVec3) {
        self.sections_since_the_list.write().insert(pos);

        // **And the JVM's half of the same fact.** A publish the queue has no room for is dropped
        // rather than blocking the drain it is called from: the drain is the render path, and a
        // contended mutex or a growing allocation there is a frame paid for one announcement. The
        // JVM still converges - the section's next rebuild asks the same question the drain would
        // have answered, and the game's mesh it kept is replaced then. See
        // [`Scene::published_pending`] for the cap's shape.
        let mut pending = self
            .published_pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);

        if pending.len() < PUBLISHED_PENDING_CAP {
            pending.push(pos);
        } else {
            PUBLISHED_DROPPED.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
    }

    /// Hands over the sections whose publish the JVM has not been told of, and forgets them.
    ///
    /// The mirror of [`crate::mc::chunk::SectionStorage::refused`] on the happy side of the same
    /// fact: a refusal says "this renderer will not draw it", and this says "this renderer now
    /// does". Drained, once per client tick - see [`Scene::published_pending`].
    pub fn published_sections(&self) -> Vec<IVec3> {
        let mut pending = self
            .published_pending
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);

        std::mem::take(&mut *pending)
    }

    /// The revision [`Scene::set_visible_sections`] last left behind.
    pub fn visible_sections_revision(&self) -> u64 {
        self.visible_sections_revision
            .load(std::sync::atomic::Ordering::Relaxed)
    }
    pub fn new(wm: &WmRenderer, framebuffer_size: wgpu::Extent3d) -> Self {
        // Sized for a large render distance up front, so that a session which never reports one still
        // works, and resized to the one the game reports with [`Scene::set_arena_slots`] - which
        // happens when a world is joined, before anything has been baked. A buffer that is too large
        // is video memory; one that is too small is sections that cannot be baked.
        //
        // The indirect arguments, one buffer per terrain pass, allocated for every slot up front: a
        // slot is claimed when the graph builds its pipelines and a buffer created then would have to
        // be created on a rebuild, which is a resource the frame in flight may still be reading. See
        // `SECTION_DRAW_SLOTS`.
        //
        // `min_binding_size` is left open in the layout, so the whole of each is bound; the arena's
        // storage buffer is declared the same way.
        let indirect_buffers = (0..SECTION_DRAW_SLOTS)
            .map(|_| {
                Arc::new(wm.gpu.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("terrain indirect draw arguments"),
                    size: (INDIRECT_DRAW_CAPACITY
                        * std::mem::size_of::<wgpu::util::DrawIndexedIndirectArgs>())
                        as u64,
                    usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::INDIRECT,
                    mapped_at_creation: false,
                }))
            })
            .collect::<Vec<_>>();

        // **The arena starts split across as many buffers as the binding limit asks for.** The
        // placeholder is `arena_slots(8)` = 105 MB, which fits one 128 MiB binding on the default
        // limits - but nothing here should depend on that staying true, and a device whose binding
        // limit is lower would have its world black from the same `create_bind_group` refusal. One
        // split, one list, both sides built from it: see `split_arena_slots`.
        let arena_split = split_arena_slots(
            crate::mc::chunk::ARENA_SLOTS,
            arena_buffer_slot_cap(
                wm.gpu.device.limits().max_buffer_size,
                wm.gpu.device.limits().max_storage_buffer_binding_size,
            ),
        );

        Self {
            section_storage: RwLock::new(SectionStorage::with_arenas(arena_split.clone())),
            camera_section_pos: RwLock::new(IVec3::ZERO),
            trimmed_section_pos: RwLock::new(ivec2(i32::MAX, i32::MAX)),
            chunk_buffers: ArcSwap::from_pointee(
                arena_split
                    .into_iter()
                    .map(|arena_slots| {
                        Arc::new(BindableBuffer::new_deferred(
                            wm,
                            arena_slots as u64 * 4,
                            ARENA_USAGE,
                            "ssbo",
                        ))
                    })
                    .collect::<Vec<_>>(),
            ),
            pending_arena_growth: AtomicU32::new(0),
            // The cap on one arena buffer is the binding limit now, not `max_buffer_size`: a buffer
            // past `max_storage_buffer_binding_size` is one whose bind group cannot be created, and
            // growth sized by `max_buffer_size` alone would append exactly such buffers. See
            // `arena_buffer_slot_cap`.
            arena_cap_slots: arena_buffer_slot_cap(
                wm.gpu.device.limits().max_buffer_size,
                wm.gpu.device.limits().max_storage_buffer_binding_size,
            ),
            arena_memory_budget: arena_memory_budget(wm.gpu.device.limits().max_buffer_size),
            indirect_buffers,
            // One table for every pass and every arena; a slot's entry is written when the bake that
            // claimed the slot is uploaded, and never touched again until the slot is reused. See the
            // field's note.
            section_records: BindableBuffer::new_deferred(
                wm,
                (SECTION_DRAW_CAPACITY * std::mem::size_of::<SectionRecord>()) as u64,
                wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::STORAGE,
                "section_draws",
            ),

            entity_instances: Default::default(),
            sky_state: Default::default(),
            sky_geometry: Default::default(),
            render_effects: Default::default(),
            depth_texture: wm
                .gpu
                .device
                .create_texture(&wgpu::TextureDescriptor {
                    label: None,
                    size: framebuffer_size,
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: wgpu::TextureFormat::Depth32Float,
                    usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
                    view_formats: &[],
                })
                .into(),

            // `None` rather than an empty set: nothing has been sent yet, and an empty set means "the
            // game looked and saw nothing", which would draw no terrain at all. See the field.
            visible_sections: RwLock::new(None),
            visible_sections_revision: AtomicU64::new(0),
            sections_since_the_list: RwLock::new(HashSet::new()),
            visible_sections_camera: RwLock::new(IVec3::ZERO),
            published_pending: std::sync::Mutex::new(Vec::new()),
        }
    }

    /// Sizes the arena to a pool of `slots` u32 slots, and answers whether it took.
    ///
    /// The pool and the buffer are one thing and are therefore set in one place: the pool is what the
    /// allocator hands ranges out of, the buffer is where those ranges live, and a pool larger than
    /// the buffer is a write past the end of it. Sizing them apart - which is what this replaced - is
    /// how the buffer came to be fixed at the largest render distance while the pool followed the
    /// game's: the memory was spent either way, and only the *capacity* moved.
    ///
    /// It only takes while the arena is empty, for the reason `SectionStorage::set_pool` gives: a
    /// range allocator cannot be resized under live allocations, and the bytes in the buffer are the
    /// only copy of the geometry that is on screen. `false` is a report that arrived after the world
    /// had been meshed - the arena keeps the size it was built with, and the caller says so.
    pub fn set_arena_slots(&self, wm: &WmRenderer, slots: u32) -> bool {
        // **Split first, then build both sides from the one list.** `slots` is the whole pool the view
        // wants, and one buffer of it past `max_storage_buffer_binding_size` is a buffer whose bind
        // group `create_bind_group` refuses - not as a `Result`, but as an invalid bind group and an
        // uncaptured error, which then drops every terrain submit for the life of the world. The world
        // went black on Adreno 640 exactly this way at a 12-chunk view (192 MB against a 128 MiB
        // binding). See `split_arena_slots` and `arena_buffer_slot_cap`.
        let split = split_arena_slots(
            slots,
            arena_buffer_slot_cap(
                wm.gpu.device.limits().max_buffer_size,
                wm.gpu.device.limits().max_storage_buffer_binding_size,
            ),
        );

        if !self.section_storage.write().set_pool_arenas(split.clone()) {
            return false;
        }

        // The old buffers go when the last frame that recorded a draw from them is done with them: the
        // pass holds its own reference, and this drops ours.
        self.chunk_buffers.store(Arc::new(
            split
                .into_iter()
                .map(|arena_slots| {
                    Arc::new(BindableBuffer::new_deferred(
                        wm,
                        arena_slots as u64 * 4,
                        ARENA_USAGE,
                        "ssbo",
                    ))
                })
                .collect::<Vec<_>>(),
        ));

        true
    }

    pub fn resize_depth_texture(&self, wm: &WmRenderer, width: u32, height: u32) {
        self.depth_texture.read().destroy();
        *self.depth_texture.write() = wm.gpu.device.create_texture(&wgpu::TextureDescriptor {
            label: None,
            size: wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Depth32Float,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            view_formats: &[],
        })
    }
}

/// Minecraft-specific state and data structures go in here
pub struct MinecraftState {
    pub block_manager: RwLock<BlockManager>,

    pub entity_models: RwLock<HashMap<String, Arc<Entity>>>,

    pub resource_provider: Arc<dyn ResourceProvider>,
    pub texture_manager: TextureManager,

    pub animated_block_buffer: ArcSwap<Option<wgpu::Buffer>>,
    pub animated_block_bind_group: ArcSwap<Option<wgpu::BindGroup>>,
}

impl MinecraftState {
    #[must_use]
    pub fn new(wgpu_state: &Gpu, resource_provider: Arc<dyn ResourceProvider>) -> Self {
        MinecraftState {
            entity_models: RwLock::new(HashMap::new()),

            texture_manager: TextureManager::new(wgpu_state),

            block_manager: RwLock::new(BlockManager::new()),
            resource_provider,

            animated_block_buffer: ArcSwap::new(Arc::new(None)),
            animated_block_bind_group: ArcSwap::new(Arc::new(None)),
        }
    }

    /// Bake blocks from their blockstates
    ///
    /// # Example
    ///
    ///```ignore
    /// # use wgpu_mc::mc::MinecraftState;
    /// # use wgpu_mc::mc::resource::ResourcePath;
    /// # use wgpu_mc::WmRenderer;
    ///
    /// # let minecraft_state: MinecraftState;
    /// # let wm: WmRenderer;
    ///
    /// minecraft_state.bake_blocks(
    ///     &wm,
    ///     [("minecraft:anvil", &ResourcePath("minecraft:blockstates/anvil.json".into()))]
    /// );
    /// ```
    pub fn bake_blocks<'a>(
        &self,
        wm: &WmRenderer,
        block_states: impl IntoIterator<Item = (impl AsRef<str>, &'a ResourcePath)>,
    ) {
        let mut block_manager = self.block_manager.write();
        let atlases = self.texture_manager.atlases.read();
        // Not a panic: nothing registers a block atlas in this build yet - the Fabric module's atlas
        // loader was never ported - and a `#[jni_fn]` frame cannot unwind, so a missing atlas used to
        // be the JVM aborting on the block cache thread. Say what is missing and leave the registry
        // empty; everything that needs block models (the Rust terrain baker) will find it empty and
        // do nothing.
        let Some(block_atlas) = atlases.get(BLOCK_ATLAS) else {
            log::error!(
                "wgpu-mc: no block atlas is registered, so block models cannot be baked - the Rust \
                 terrain path needs one (see `bake_blocks`)"
            );
            return;
        };

        //Figure out which block models there are
        block_states
            .into_iter()
            .for_each(|(block_name, block_state)| {
                // One missing or malformed blockstate file must not take the game down: this runs on
                // a background thread whose panics abort the JVM.
                let Some(json) = self.resource_provider.get_string(block_state) else {
                    log::warn!(
                        "wgpu-mc: {} has no blockstate file; skipping it",
                        block_state.0
                    );
                    return;
                };

                let blockstates: schemas::BlockStates = match serde_json::from_str(&json) {
                    Ok(blockstates) => blockstates,
                    Err(err) => {
                        log::warn!("wgpu-mc: {} could not be read: {err}", block_state.0);
                        return;
                    }
                };

                let block = match &blockstates {
                    schemas::BlockStates::Variants { variants } => {
                        let meshes: IndexMap<Vec<(String, StateValue)>, Vec<Arc<ModelMesh>>> =
                            variants
                                .iter()
                                .filter_map(|(variant_id, variant)| {
                                    let key_iter = if !variant_id.is_empty() {
                                        variant_id
                                            .split(',')
                                            .filter_map(|kv_pair| {
                                                let mut split = kv_pair.split('=');
                                                if kv_pair.is_empty() {
                                                    return None;
                                                }

                                                // A pair without its `=` is a pack's typo, and it
                                                // used to be an `unwrap` on the block-cache
                                                // thread: the pair is skipped, the rest kept.
                                                let name = split.next()?;
                                                let value = split.next()?;

                                                Some((
                                                    name.to_string(),
                                                    match value {
                                                        "true" => StateValue::Bool(true),
                                                        "false" => StateValue::Bool(false),
                                                        other => StateValue::String(other.into()),
                                                    },
                                                ))
                                            })
                                            .collect::<Vec<_>>()
                                    } else {
                                        vec![]
                                    };

                                    // A variant whose model cannot be baked is dropped rather than
                                    // unwrapped: one bad model in one blockstate file used to abort
                                    // the whole registry, which is the difference between a block
                                    // that does not draw and no terrain at all. The block itself
                                    // stays registered, with the variants that did bake.
                                    let mut meshes = Vec::with_capacity(variant.models().len());
                                    for variation in variant.models() {
                                        match ModelMesh::bake(
                                            std::slice::from_ref(variation),
                                            &*self.resource_provider,
                                            block_atlas,
                                        ) {
                                            Ok(mesh) => meshes.push(Arc::new(mesh)),
                                            Err(err) => {
                                                log::warn!(
                                                    "wgpu-mc: {} variant {variant_id} could not be \
                                                     baked ({err:?}); skipping it",
                                                    block_name.as_ref()
                                                );
                                                return None;
                                            }
                                        }
                                    }

                                    Some((key_iter, meshes))
                                })
                                .collect();

                        Block::Variants(meshes)
                    }
                    schemas::BlockStates::Multipart { cases } => Block::Multipart(Multipart {
                        cases: cases.clone(),
                        keys: RwLock::new(IndexMap::new()),
                    }),
                };

                block_manager
                    .blocks
                    .insert(String::from(block_name.as_ref()), block);
            });

        // The fluids' own textures. Lava and water have no block model to name them, so this is the
        // only place they can enter the atlas - and the fluid mesher draws them, so without this the
        // fluids would be baked untextured. Allocated before the upload, which is what puts them in
        // the texture the pass samples.
        for name in chunk::FLUID_TEXTURES {
            // Two names for the same texture, and they are not interchangeable: the atlas is keyed by
            // the name a *model* would use (`minecraft:block/lava_still`), and the resource provider by
            // the *file* (`minecraft:textures/block/lava_still.png`). Asking the provider for the first
            // is a `None` on every machine - which is what both fluids reported, "cannot be read",
            // while the files sat in the jar the whole time - and a fluid whose sprite never reaches the
            // atlas is a fluid the mesher leaves out. `block.rs` makes the same conversion for the
            // textures its models name.
            let path = ResourcePath(format!("minecraft:block/{name}"));

            if block_atlas.uv_map.read().contains_key(&path) {
                continue;
            }

            let texture_path = path.prepend("textures/").append(".png");

            let Some(bytes) = self.resource_provider.get_bytes(&texture_path) else {
                log::warn!("wgpu-mc: {texture_path} cannot be read, so that fluid is not drawn");
                continue;
            };

            block_atlas.allocate([(&path, &bytes)], &*self.resource_provider);
        }

        block_atlas.upload(wm);
    }
}

/// The one thing a blockstate's variant *list* is picked with. See [`Block::get_model`] for why the
/// index comes from the JVM rather than from a random number generator on this side.
#[cfg(test)]
mod variant_pick_tests {
    use super::*;
    use crate::mc::block::ModelMesh;

    fn mesh(cull: u8) -> ModelMesh {
        ModelMesh {
            north: vec![],
            south: vec![],
            west: vec![],
            east: vec![],
            up: vec![],
            down: vec![],
            any: vec![],
            cull,
            ambient_occlusion: None,
        }
    }

    #[test]
    fn a_list_is_picked_by_the_index_the_jvm_counted() {
        let mut variants: IndexMap<Vec<(String, StateValue)>, Vec<Arc<ModelMesh>>> =
            IndexMap::new();
        variants.insert(
            vec![],
            vec![Arc::new(mesh(1)), Arc::new(mesh(2)), Arc::new(mesh(3))],
        );

        let block = Block::Variants(variants);

        for (index, expected) in [(0u8, 1u8), (1, 2), (2, 3)] {
            assert_eq!(
                block.get_model(0, index).expect("a model").cull,
                expected,
                "variant {index} is entry {expected} of the list"
            );
        }

        // **Past the end is the first entry, not a hole.** A list the two sides disagree about the
        // length of - a blockstate this loader dropped an entry of, a mod's list - would otherwise be a
        // face that is not drawn at all, and a wrong angle is the smaller failure of the two.
        assert_eq!(block.get_model(0, 9).expect("the first entry").cull, 1);

        // And a key no state selected has no model, which is the same answer as before this existed.
        assert!(block.get_model(4, 0).is_none());
    }
}

/// The arena's mask has to stay a superset of what every reader asks of it. The compacted terrain
/// draw reads it as a copy source; the shaders read it as storage; the draws read it as vertices and
/// indices. Each of those failed differently when a flag went missing - the copy source one took the
/// whole frame's submission with it - so the mask carries its contract as a test.
#[cfg(test)]
mod arena_usage_tests {
    use super::*;

    #[test]
    fn the_arena_can_be_a_copy_source() {
        // The compacted draw (`terrain_mega`) blits the frame's index ranges out of an arena every
        // frame, before the terrain pass opens. A missing `COPY_SRC` made every one of those copies
        // a validation error, invalidated the frame's encoder, and had `Queue::submit` refuse the
        // whole submission - the world went black while the game's own encoder kept the menus up.
        assert!(
            ARENA_USAGE.contains(wgpu::BufferUsages::COPY_SRC),
            "the compacted terrain draw copies index ranges out of the arena every frame"
        );
    }

    #[test]
    fn the_arena_still_carries_every_reader_it_always_had() {
        let expected = wgpu::BufferUsages::COPY_DST
            .union(wgpu::BufferUsages::COPY_SRC)
            .union(wgpu::BufferUsages::VERTEX)
            .union(wgpu::BufferUsages::STORAGE)
            .union(wgpu::BufferUsages::INDEX);

        assert_eq!(ARENA_USAGE, expected);
    }
}
