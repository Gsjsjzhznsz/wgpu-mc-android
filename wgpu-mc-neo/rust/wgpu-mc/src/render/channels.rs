//! **The channel: named points in the frame where another renderer's passes can be driven.**
//!
//! This is the pre-embedded half of the Iris story - not a shader-pack loader, which is a later
//! milestone and a much bigger one (see `docs/iris-adaptation-research.md`), but the frame's own
//! insertion points, built now so that the renderer that eventually drives shader-pack passes does
//! not have to restructure the frame to have somewhere to put them. A pass registered here is a
//! pipeline the graph already compiled - named in `graph.yaml`, holding its own shader, bind groups
//! and vertex layout - driven at one of the slots below, drawn as six vertices with no vertex buffer
//! bound (`@geo_channel`), which is the same vertex-pull shape the terrain shaders use and the shape
//! a fullscreen pass wants.
//!
//! The registry is deliberately dumb: an ordered list of pipeline names per slot. Anything smarter -
//! per-pass textures, framebuffer ping-ponging, the game's uniform channels - is the next milestone,
//! and the slot names it will hang from are these.

/// **Where in the frame a channel pass runs.**
///
/// The frame this renderer draws is: the opaque terrain group (solid, then cutout), then the game's
/// own entities and weather, then the translucent terrain group - and the two group boundaries plus
/// the pass boundaries inside the opaque one are where a shader pack's work fits. The names are the
/// contract with whatever drives this side (the eventual Iris port, a debug overlay, a capture
/// tool); they are strings across the JNI and stay stable in the face of everything else moving.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum ChannelSlot {
    /// The first thing the frame does - before the frame's depth is claimed, before anything draws.
    /// A shadow-map pass or a depth pre-pass of someone else's belongs here.
    FrameStart,
    BeforeTerrainSolid,
    AfterTerrainSolid,
    BeforeTerrainCutout,
    AfterTerrainCutout,
    BeforeTerrainTranslucent,
    /// The last thing the terrain draws. **The composite hook**: everything after this point sees a
    /// finished world, which is where a shader pack's post-processing wants to sit.
    AfterTerrainTranslucent,
    FrameEnd,
}

impl ChannelSlot {
    /// Every slot, in frame order - which is the order a driver walks them in, and the order the
    /// documentation lists them in.
    pub const ALL: [ChannelSlot; 8] = [
        ChannelSlot::FrameStart,
        ChannelSlot::BeforeTerrainSolid,
        ChannelSlot::AfterTerrainSolid,
        ChannelSlot::BeforeTerrainCutout,
        ChannelSlot::AfterTerrainCutout,
        ChannelSlot::BeforeTerrainTranslucent,
        ChannelSlot::AfterTerrainTranslucent,
        ChannelSlot::FrameEnd,
    ];

    /// The name this slot answers to across the JNI - stable, lowercase, and the same name the
    /// documentation uses.
    pub const fn name(self) -> &'static str {
        match self {
            ChannelSlot::FrameStart => "frame_start",
            ChannelSlot::BeforeTerrainSolid => "before_terrain_solid",
            ChannelSlot::AfterTerrainSolid => "after_terrain_solid",
            ChannelSlot::BeforeTerrainCutout => "before_terrain_cutout",
            ChannelSlot::AfterTerrainCutout => "after_terrain_cutout",
            ChannelSlot::BeforeTerrainTranslucent => "before_terrain_translucent",
            ChannelSlot::AfterTerrainTranslucent => "after_terrain_translucent",
            ChannelSlot::FrameEnd => "frame_end",
        }
    }

    /// The slot a name names, or `None` for a name nothing answers to - which is a caller's typo,
    /// and worth an error rather than a silent slot of one's own.
    pub fn from_name(name: &str) -> Option<ChannelSlot> {
        Self::ALL.iter().copied().find(|slot| slot.name() == name)
    }
}

/// **The registered passes: an ordered list of pipeline names per slot.**
///
/// A pipeline name that is not in the graph is refused at registration, so a channel pass that does
/// nothing at frame time is a bug on this side rather than a name that quietly matched nothing. The
/// order within a slot is registration order, and it is the order the passes draw in - a shader
/// pack's composite chain is an ordered thing, and the registry does not second-guess it.
#[derive(Debug, Default)]
pub struct ChannelRegistry {
    passes: Vec<(ChannelSlot, Vec<String>)>,
}

impl ChannelRegistry {
    /// Registers one pass: the pipeline `pipeline` draws at `slot`, after whatever the slot already
    /// holds.
    ///
    /// Both halves are named with strings - the slot because the JNI speaks strings, the pipeline
    /// because `graph.yaml` does - and both are checked here rather than at frame time: a slot name
    /// that matches nothing and a pipeline the graph never compiled are both refused with the reason
    /// in the answer, because the caller that can still fix a typo is the caller holding this
    /// result.
    pub fn register(
        &mut self,
        slot: &str,
        pipeline: &str,
        known_pipelines: &[String],
    ) -> Result<(), String> {
        let Some(slot) = ChannelSlot::from_name(slot) else {
            return Err(format!(
                "unknown channel slot \"{slot}\" - the slots are {}",
                ChannelSlot::ALL
                    .iter()
                    .map(|slot| slot.name())
                    .collect::<Vec<_>>()
                    .join(", ")
            ));
        };

        if !known_pipelines.iter().any(|known| known == pipeline) {
            return Err(format!(
                "unknown pipeline \"{pipeline}\" - a channel pass is a pipeline the graph compiled, \
                 and the graph holds {}",
                if known_pipelines.is_empty() {
                    "none".to_string()
                } else {
                    known_pipelines.join(", ")
                }
            ));
        }

        let entry = match self
            .passes
            .iter_mut()
            .find(|(registered, _)| *registered == slot)
        {
            Some((_, passes)) => passes,
            None => {
                self.passes.push((slot, Vec::with_capacity(2)));
                &mut self.passes.last_mut().expect("just pushed").1
            }
        };

        if entry.iter().any(|registered| registered == pipeline) {
            return Err(format!(
                "pipeline \"{pipeline}\" is already registered at \"{}\" - the same pipeline twice \
                 in one slot draws the same picture twice",
                slot.name()
            ));
        }

        entry.push(pipeline.to_string());
        Ok(())
    }

    /// Removes every registered pass, whichever slot they sit in.
    ///
    /// The caller is a resource reload or a graph rebuild - moments when the pipelines the passes
    /// name stop existing, and a registry that kept them would name ghosts.
    pub fn clear(&mut self) {
        self.passes.clear();
    }

    /// The pipeline names registered at one slot, in the order they draw.
    pub fn passes(&self, slot: ChannelSlot) -> &[String] {
        self.passes
            .iter()
            .find(|(registered, _)| *registered == slot)
            .map(|(_, passes)| passes.as_slice())
            .unwrap_or(&[])
    }

    /// Whether anything at all is registered - the fast answer the frame asks once per pass it
    /// would have driven.
    pub fn is_empty(&self) -> bool {
        self.passes.iter().all(|(_, passes)| passes.is_empty())
    }

    /// **How many passes are registered across every slot** - the number the log names when they
    /// are all let go.
    pub fn total(&self) -> usize {
        self.passes.iter().map(|(_, passes)| passes.len()).sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn graph_pipelines() -> Vec<String> {
        ["terrain", "terrain_solid", "translucent_terrain"]
            .iter()
            .map(|name| name.to_string())
            .collect()
    }

    /// **The names are the contract**, and the round trip through them is the whole of it: every
    /// slot answers to the name the JNI sends, and no two slots share one.
    #[test]
    fn every_slot_answers_to_its_name_and_names_are_unique() {
        let mut seen = std::collections::HashSet::new();

        for slot in ChannelSlot::ALL {
            let name = slot.name();
            assert!(
                seen.insert(name),
                "{name} is the name of two slots, and the JNI cannot tell them apart"
            );
            assert_eq!(
                ChannelSlot::from_name(name),
                Some(slot),
                "{name} does not round-trip"
            );
        }

        assert_eq!(
            ChannelSlot::from_name("frame_begin"),
            None,
            "a name nothing answers to must not find a slot"
        );
    }

    /// **A pass registers into its slot, in order, and nowhere else.** The composite chain a shader
    /// pack drives is an ordered thing; the registry keeps the order it was given and keeps the
    /// slots from bleeding into each other.
    #[test]
    fn a_pass_registers_into_its_slot_in_registration_order() {
        let mut registry = ChannelRegistry::default();
        let pipelines = graph_pipelines();

        registry
            .register(
                "after_terrain_translucent",
                "translucent_terrain",
                &pipelines,
            )
            .expect("the pipeline is the graph's own");
        registry
            .register("frame_start", "terrain_solid", &pipelines)
            .expect("a channel pass may be any pipeline the graph holds");
        registry
            .register("after_terrain_translucent", "terrain", &pipelines)
            .expect("the slot holds more than one");

        assert_eq!(
            registry.passes(ChannelSlot::AfterTerrainTranslucent),
            ["translucent_terrain", "terrain"],
            "the slot lost the order it was given"
        );
        assert_eq!(
            registry.passes(ChannelSlot::FrameStart),
            ["terrain_solid"],
            "a pass leaked into a slot it was not registered for"
        );
        assert!(
            registry.passes(ChannelSlot::FrameEnd).is_empty(),
            "a slot nobody registered into answered with something"
        );
        assert!(!registry.is_empty());
    }

    /// **Both halves are checked at registration**, because the caller that can fix a typo is the
    /// one holding the result: a slot name nothing answers to and a pipeline the graph never
    /// compiled are errors with the reason in them, not silent slots of one's own.
    #[test]
    fn a_typo_is_refused_with_the_reason_in_the_answer() {
        let mut registry = ChannelRegistry::default();
        let pipelines = graph_pipelines();

        let slot_err = registry
            .register("before_terrain_opaque", "terrain", &pipelines)
            .expect_err("the slot name is not one of the eight");
        assert!(
            slot_err.contains("unknown channel slot") && slot_err.contains("before_terrain_solid"),
            "the error should name the problem and the slots that exist: {slot_err}"
        );

        let pipeline_err = registry
            .register("frame_start", "sky_pipeline", &pipelines)
            .expect_err("the graph holds no such pipeline");
        assert!(
            pipeline_err.contains("unknown pipeline") && pipeline_err.contains("sky_pipeline"),
            "the error should name the problem and what the graph holds: {pipeline_err}"
        );

        registry
            .register("frame_start", "terrain", &pipelines)
            .expect("this one is real");
        let duplicate_err = registry
            .register("frame_start", "terrain", &pipelines)
            .expect_err("the same pipeline twice in one slot");
        assert!(
            duplicate_err.contains("already registered"),
            "the error should say the registration duplicates: {duplicate_err}"
        );

        // The same pipeline at *another* slot is fine - a blur at two points in the frame is a
        // legitimate ask.
        registry
            .register("frame_end", "terrain", &pipelines)
            .expect("a different slot is a different registration");
    }

    /// **A cleared registry answers empty**, which is what a graph rebuild leaves behind: the
    /// pipelines the passes named stop existing, and a registry that kept them would name ghosts.
    #[test]
    fn clearing_removes_every_slot() {
        let mut registry = ChannelRegistry::default();
        let pipelines = graph_pipelines();

        registry
            .register("frame_start", "terrain", &pipelines)
            .expect("real");
        registry
            .register("frame_end", "terrain_solid", &pipelines)
            .expect("real");
        assert!(!registry.is_empty());

        registry.clear();

        assert!(registry.is_empty());
        for slot in ChannelSlot::ALL {
            assert!(
                registry.passes(slot).is_empty(),
                "{} held a pass after the clear",
                slot.name()
            );
        }
    }
}
