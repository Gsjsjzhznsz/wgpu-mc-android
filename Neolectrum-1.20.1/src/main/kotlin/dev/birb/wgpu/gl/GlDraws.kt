package dev.birb.wgpu.gl

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.rust.WmJni
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * The draw itself, and the encoder every draw of the frame goes into.
 *
 * This is the last of the GL groups, and the only one that is not one GL call turned into one engine
 * call. `glDrawElements` carries no program, no vertex layout, no uniforms, no blending and no render
 * target, because GL keeps all of that as state - so this object is in two halves: the state the other
 * `gl` objects recorded, read here, and the engine objects built from it.
 *
 * ## Where the pipeline comes from
 *
 * The engine compiles the GLSL itself, so what it is handed is what the game gave `glShaderSource`,
 * with two things added by the engine from this side's description:
 *
 * - **the uniform layouts.** `preprocessing.rs` writes `layout(set, binding)` onto every `uniform`
 *   declaration it finds, taking the binding from the names this side declared in the pipeline's bind
 *   group layout - and it invents a fresh binding for anything this side did *not* declare, which the
 *   layout then does not have. So the layout has to name every uniform the source declares, not merely
 *   every one the game asked a location for.
 * - **the vertex input locations.** The engine numbers the elements of `vertex_formats` across the
 *   pipeline's slots in order - 0, 1, 2 ... - and writes `layout(location = N) in <type> <name>;` from
 *   each element's *name*, which is why the descriptor has no location field: the location is the
 *   position and the name is what a location is attached to. [GlPrograms.getAttribLocation] therefore
 *   hands out dense numbers in ask order and this object sorts the vertex array's attributes by them:
 *   both sides count the same elements in the same order, so both arrive at the same number.
 *
 * A `sampler2D X` is not one binding on the engine side. `shim_samplers` splits it into a texture
 * `X_wm_texshim` and a sampler `X_wm_sampler` *before* the layout pass runs, so those are the names
 * the layout has to declare. The plan still reports `X` as the `declared_name`, which is how the
 * texture unit the game bound is found again.
 *
 * ## One pass per draw, deliberately
 *
 * The engine has an explicit pass and GL does not. Every draw gets its own, and the encoder holding
 * them is submitted when the frame is presented. Consecutive draws into one target could share a pass
 * - that is the obvious later optimisation - but a merged pass is one whose load behaviour is decided
 * once, and getting that wrong is a frame that is a blend of two draws rather than a frame that fails.
 */
object GlDraws {
	// --------------------------------------------------------------- the `RenderPipeline` layout
	//
	// `bindings.h`'s struct, field for field. Every field is eight bytes wide, which is why the offsets
	// look the way they do.
	private const val PIPELINE_NAME = 0
	private const val PIPELINE_BIND_GROUP_LAYOUTS = 8
	private const val PIPELINE_COLOR_TARGETS = 16
	private const val PIPELINE_DEPTH_STENCIL = 24
	private const val PIPELINE_VERTEX_FORMATS = 32
	private const val PIPELINE_VERTEX_SHADER = 40
	private const val PIPELINE_FRAGMENT_SHADER = 48
	private const val PIPELINE_DIRECTIVES = 56
	private const val PIPELINE_FRAG_STATE = 64
	private const val PIPELINE_TOPOLOGY = 72
	private const val PIPELINE_CULL = 80
	private const val PIPELINE_SIZE = 88

	private const val RAW_ARRAY_CONTENTS = 0
	private const val RAW_ARRAY_COUNT = 8
	private const val RAW_ARRAY_SIZE = 16

	private const val BIND_GROUP_LAYOUT_ENTRIES = 0
	private const val BIND_GROUP_LAYOUT_SIZE = 8

	private const val ENTRY_TYPE = 0
	private const val ENTRY_NAME = 8
	private const val ENTRY_FORMAT = 16
	private const val ENTRY_SIZE = 24

	private const val COLOR_TARGET_BLEND = 0
	private const val COLOR_TARGET_FORMAT = 8
	private const val COLOR_TARGET_WRITE_MASK = 16
	private const val COLOR_TARGET_SIZE = 24

	private const val BLEND_SRC_COLOR = 0
	private const val BLEND_DST_COLOR = 8
	private const val BLEND_SRC_ALPHA = 16
	private const val BLEND_DST_ALPHA = 24
	private const val BLEND_SIZE = 32

	private const val DEPTH_COMPARE = 0
	private const val DEPTH_ACTIVE = 8
	private const val DEPTH_BIAS_CONSTANT = 16
	private const val DEPTH_BIAS_SLOPE = 20
	private const val DEPTH_SIZE = 24

	private const val VERTEX_FORMAT_ELEMENTS = 0
	private const val VERTEX_FORMAT_STRIDE = 8
	private const val VERTEX_FORMAT_SIZE = 16

	private const val VERTEX_ELEMENT_OFFSET = 0
	private const val VERTEX_ELEMENT_FORMAT = 8
	private const val VERTEX_ELEMENT_NAME = 16
	private const val VERTEX_ELEMENT_SIZE = 24

	/** `DrawCall`, from `bindings.h`: 1276 bytes, and the field order is load-bearing. */
	private const val CALL_PIPELINE = 0
	private const val CALL_VERTEX_BUFFER_MASK = 8
	private const val CALL_INDEX_FORMAT = 12
	private const val CALL_INDEXED = 16
	private const val CALL_BINDINGS_LEN = 20
	private const val CALL_FIRST = 24
	private const val CALL_COUNT = 28
	private const val CALL_BASE_VERTEX = 32
	private const val CALL_INSTANCE_COUNT = 36
	private const val CALL_INDEX_BUFFER = 40
	private const val CALL_VERTEX_BUFFERS = 48
	private const val CALL_BINDINGS = 240
	private const val CALL_BINDING_STRIDE = 32
	private const val CALL_COMBO = 1264
	private const val CALL_BINDINGS_PRESENT = 1268
	private const val CALL_FIRST_INSTANCE = 1272
	private const val CALL_SIZE = 1276

	private const val DRAW_BINDING_KIND = 0
	private const val DRAW_BINDING_RESOURCE = 8
	private const val DRAW_BINDING_OFFSET = 16
	private const val DRAW_BINDING_LENGTH = 24

	private const val PLAN_DECLARED_NAME = 8
	private const val PLAN_KIND = 24
	private const val PLAN_STRIDE = 32

	private const val MAX_DRAW_BINDINGS = 32

	/** `DRAW_BINDING_*`: what the plan says has to be bound in a slot. */
	private const val DRAW_BINDING_NONE = 0
	private const val DRAW_BINDING_BUFFER = 1
	private const val DRAW_BINDING_TEXTURE = 2
	private const val DRAW_BINDING_SAMPLER = 3

	/** `BindGroupEntryDescriptor::type_`: how the pipeline layout declares a slot. */
	private const val LAYOUT_TEXTURE = 0
	private const val LAYOUT_UNIFORM = 1
	private const val LAYOUT_SAMPLER = 2

	/** `SHIM_TEXTURE_SUFFIX` / `SHIM_SAMPLER_SUFFIX`, from the engine's `preprocessing.rs`. */
	private const val SHIM_TEXTURE_SUFFIX = "_wm_texshim"
	private const val SHIM_SAMPLER_SUFFIX = "_wm_sampler"

	private const val USAGE_UNIFORM = 128
	private const val USAGE_COPY_DST = 8

	/** `GpuFormat::RGBA8Unorm`, the sample type every sampler in this game is declared with. */
	private const val FORMAT_RGBA8_UNORM = 7

	/** `FilterMode`: 0 is `NEAREST`, 1 is `LINEAR`. */
	private const val FILTER_NEAREST = 0
	private const val FILTER_LINEAR = 1

	/** `AddressMode`: 0 is `REPEAT`, 1 is `CLAMP_TO_EDGE`. */
	private const val ADDRESS_REPEAT = 0
	private const val ADDRESS_CLAMP = 1

	/** `CompareFunction::Always`, which is what a disabled depth test becomes. */
	private const val COMPARE_ALWAYS = 7

	private const val MAX_ANISOTROPY = 16

	// GL filter and wrap enums, spelled out because the engine numbers its own.
	private const val GL_NEAREST = 9728
	private const val GL_LINEAR = 9729
	private const val GL_NEAREST_MIPMAP_NEAREST = 9984
	private const val GL_LINEAR_MIPMAP_NEAREST = 9985
	private const val GL_NEAREST_MIPMAP_LINEAR = 9986
	private const val GL_LINEAR_MIPMAP_LINEAR = 9987
	private const val GL_REPEAT = 10497
	private const val GL_MIRRORED_REPEAT = 33648

	// ------------------------------------------------------------------ what this object owns

	/** The encoder the frame's passes are recorded into; 0 between frames. */
	private var encoder = 0L

	private val samplers = HashMap<Long, Long>()
	private val pipelines = LinkedHashMap<Long, Compiled>()
	private val uniformBuffers = HashMap<Int, Uniforms>()
	private val warned = HashSet<String>()

	private val planBuffer: ByteBuffer = MemoryUtil.memAlloc(PLAN_STRIDE * MAX_DRAW_BINDINGS)

	/**
	 * The `RawArray<PlanBinding>` header the engine's `pipelineBindings` is handed, rather than the
	 * bindings themselves.
	 *
	 * The JNI wrapper on the other side resolves its `out` argument as `RawArray<PlanBinding>`, so a
	 * pointer straight at [planBuffer] is read as a header: its first eight bytes become the
	 * `contents` pointer and its next eight the count. That turns the first binding's `name` into a
	 * string pointer, which is a crash inside `CStr::from_ptr` rather than a wrong answer. This is
	 * the same shape every other array in the descriptor takes - the array is a struct, and the
	 * struct is what travels.
	 */
	private val planArray: ByteBuffer = MemoryUtil.memAlloc(RAW_ARRAY_SIZE).apply {
		putLong(RAW_ARRAY_CONTENTS, MemoryUtil.memAddress(planBuffer))
		putLong(RAW_ARRAY_COUNT, MAX_DRAW_BINDINGS.toLong())
	}
	private val callBuffer: ByteBuffer = MemoryUtil.memAlloc(CALL_SIZE)
	private val depthBuffer: ByteBuffer = MemoryUtil.memAlloc(DEPTH_SIZE)
	private val depthAttachmentBuffer: ByteBuffer = MemoryUtil.memAlloc(16)

	private var draws: Long = 0L
	private var refused: Long = 0L

	/** A program's uniforms, in one engine buffer: one aligned slot per named uniform. */
	private class Uniforms(@JvmField val buffer: Long, @JvmField val alignment: Int) {
		@JvmField val offsets = HashMap<String, Long>()
		@JvmField var writtenAt: Long = -1L

		fun offsetOf(name: String): Long = offsets[name] ?: -1L
	}

	/** A compiled pipeline, with the plan the engine resolved it to. */
	private class Compiled(
		@JvmField val pipeline: Long,
		@JvmField val declared: List<String>,
		@JvmField val kinds: IntArray,
	) {
		val slots: Int get() = declared.size
	}

	/**
	 * Answers `glDrawElements`.
	 *
	 * `indices` is a byte offset into the bound element buffer and is always zero for this game: every
	 * `VertexBuffer` owns its own index buffer, and the shared sequential one is uploaded whole.
	 */
	@JvmStatic
	fun drawElements(mode: Int, count: Int, type: Int, indices: Long) {
		val wm = WgpuRenderer.handle()
		if (wm == 0L || count <= 0) return

		val programName = GlPrograms.currentProgram
		val program = GlPrograms.program(programName)
		if (program == null) {
			warnOnce("no-program", "a draw arrived with program $programName current, which this side has no source for")
			return
		}

		val colour = GlFramebuffers.colorView()
		if (colour == 0L) {
			warnOnce("no-target", "a draw arrived with no colour attachment; the framebuffer bound is ${GlState.boundDrawFramebuffer}")
			return
		}

		val indexFormat = indexFormat(type)
		if (indexFormat == 0) {
			warnOnce("index-type-$type", "a draw arrived with index type $type, which is neither GL_UNSIGNED_SHORT nor GL_UNSIGNED_INT")
			return
		}

		val vao = GlBuffers.boundVertexArray()
		val compiled = pipeline(wm, programName, program, vao, mode) ?: return

		// The uniforms reach the engine here, before the pass opens, and not inside it. `refresh`
		// uploads them with a copy recorded into the frame's own command encoder, and wgpu refuses a
		// recording call while a render pass is open - silently: it *invalidates* the encoder and
		// raises nothing at the call, so the stored error only surfaces at the next flush as "Encoder
		// is locked by a previously created render/compute pass", and it takes the frame with it.
		// That is the whole of what "a leaked render pass" looked like. Uploaded from here, the copy
		// and the draws that read it sit in the same encoder in the same order, which is what a
		// per-draw uniform write wants anyway.
		uniformBuffer(wm, program)

		if (encoder == 0L) {
			encoder = WmJni.createCommandEncoder(wm)
			if (encoder == 0L) {
				warnOnce("no-encoder", "the engine handed over no command encoder, so this frame's draws are dropped")
				return
			}
		}

		val pass = createPass()
		if (pass == 0L) {
			warnOnce("no-pass", "the engine refused a render pass for a draw; the attachment is $colour")
			return
		}

		scissor(pass)
		record(wm, pass, compiled, program, vao, indexFormat, count)
		WmJni.dropRenderPass(pass)
	}

	/**
	 * The frame's own encoder, made on first use.
	 *
	 * Everything a frame records belongs in one encoder and one submission. `clearCurrent` used to make
	 * an encoder of its own and submit it, once per `glClear` into a framebuffer - and a world clears
	 * its render targets many times a frame, so the frame went out as dozens of submissions instead of
	 * one. A submission is not free on the other side of the bridge: the engine builds a command buffer
	 * and a fence for it, none of which any resource counter here can see, and the driver's own memory
	 * grew with them - **23 MB a second, monotonically, with every `live resources` number flat.** That
	 * is the shape a leak has when the thing leaking is the submission rather than the resource.
	 *
	 * A clear recorded here is still in the right place in the frame: the encoder is a command stream,
	 * so a clear appended after the draws that preceded it happens after them, and `endFrame` submits
	 * the lot once at the present.
	 */
	@JvmStatic
	fun frameEncoder(wm: Long): Long {
		if (encoder == 0L) encoder = WmJni.createCommandEncoder(wm)
		return encoder
	}

	/**
	 * Submits everything the frame recorded.
	 *
	 * Called from the present, which is the one moment a frame is known to be finished: the game has
	 * handed its own target to the window and the next thing that happens is another frame's worth of
	 * draws. Submitting here rather than per draw is what makes the encoder worth having at all.
	 *
	 * `reason` names the call site, and it is not decoration: one submission a frame is the design and
	 * the renderer was measured at fifty-seven, which is a number no single site can produce. The
	 * count per frame is checked below and said out loud the first time it is more than one.
	 */
	@JvmStatic
	@JvmOverloads
	fun endFrame(wm: Long, reason: String = "?") {
		if (encoder == 0L) return
		WmJni.flushEncoder(wm, encoder)
		encoder = 0L
		sinceReport++
		flushReasons[reason] = (flushReasons[reason] ?: 0) + 1
		if (sinceReport >= REPORT_EVERY) reportFlushes()
	}

	/** How many submissions to let by before saying where they came from. */
	private const val REPORT_EVERY = 300

	/** Submissions since the last report, and which call site asked for each. See [endFrame]. */
	private val flushReasons = HashMap<String, Int>()
	private var sinceReport = 0

	/**
	 * Says where the submissions go.
	 *
	 * One a frame is the design - the frame's work is recorded into one encoder and submitted at the
	 * present - and this renderer was measured at fifty-seven, which no single call site can produce.
	 * The number alone cannot say which site it is, so the sites are counted rather than guessed at.
	 */
	private fun reportFlushes() {
		val breakdown = flushReasons.entries.sortedByDescending { it.value }
			.joinToString(", ") { "${it.key} x${it.value}" }
		dev.birb.wgpu.WgpuMcMod.LOGGER.info(
			"wgpu: {} submissions since the last report - {}",
			sinceReport, breakdown,
		)
		sinceReport = 0
		flushReasons.clear()
	}

	/**
	 * Records one `DrawCall`.
	 *
	 * The bindings are filled **positionally**, from the plan the engine resolved the pipeline to:
	 * slot `i` of the plan is slot `i` of the draw. Nothing is looked up by name at this point, which
	 * is why the plan's order is the only order that matters.
	 */
	private fun record(
		wm: Long,
		pass: Long,
		compiled: Compiled,
		program: GlPrograms.Program,
		vao: GlBuffers.VertexArray?,
		indexFormat: Int,
		count: Int,
	) {
		val call = callBuffer
		call.putLong(CALL_PIPELINE, compiled.pipeline)
		call.putInt(CALL_VERTEX_BUFFER_MASK, 0)
		call.putInt(CALL_INDEX_FORMAT, indexFormat)
		call.putInt(CALL_INDEXED, 1)
		call.putInt(CALL_BINDINGS_LEN, compiled.slots)
		call.putInt(CALL_FIRST, 0)
		call.putInt(CALL_COUNT, count)
		call.putInt(CALL_BASE_VERTEX, 0)
		call.putInt(CALL_INSTANCE_COUNT, 1)
		call.putInt(CALL_FIRST_INSTANCE, 0)

		// The index buffer is a handle of its own, and the one vertex buffer this game binds is the
		// array buffer its attributes were captured from.
		val element = vao?.elementBuffer?.let { GlBuffers.byName(it) }
		call.putLong(CALL_INDEX_BUFFER, if (element != null && element.exists) element.handle else 0L)

		var mask = 0
		val vertex = firstAttributeBuffer(vao)
		if (vertex != null) {
			call.putLong(CALL_VERTEX_BUFFERS + 0, vertex.handle)
			call.putLong(CALL_VERTEX_BUFFERS + 8, 0L)
			call.putLong(CALL_VERTEX_BUFFERS + 16, vertex.size)
			mask = 1
		}
		call.putInt(CALL_VERTEX_BUFFER_MASK, mask)

		var combo = 17
		for (slot in 0 until compiled.slots) {
			val base = CALL_BINDINGS + slot * CALL_BINDING_STRIDE
			val declared = compiled.declared[slot]
			var kind = DRAW_BINDING_NONE
			var resource = 0L
			var offset = 0L
			var length = 0L

			when (compiled.kinds[slot]) {
				DRAW_BINDING_BUFFER -> {
					val uniforms = uniformBuffer(wm, program)
					val at = uniforms?.offsetOf(declared) ?: -1L
					if (uniforms != null && at >= 0L) {
						kind = DRAW_BINDING_BUFFER
						resource = uniforms.buffer
						offset = at
						length = uniformSize(program, declared).toLong()
					} else {
						warnOnce(
							"unbound-$declared",
							"the plan wants a buffer in the slot it calls '$declared', and this side has " +
								"none: the program's own uniforms are ${program.byLocation.map { it.name }} " +
								"and the buffer is ${if (uniforms == null) "not made" else "made, holding ${uniforms.offsets.keys}"}",
						)
					}
				}

				DRAW_BINDING_TEXTURE -> {
					val entry = textureFor(program, declared)
					if (entry != null && entry.view != 0L) {
						kind = DRAW_BINDING_TEXTURE
						resource = entry.view
					}
				}

				DRAW_BINDING_SAMPLER -> {
					val entry = textureFor(program, declared)
					if (entry != null) {
						val handle = sampler(wm, entry)
						if (handle != 0L) {
							kind = DRAW_BINDING_SAMPLER
							resource = handle
						}
					}
				}
			}

			call.putInt(base + DRAW_BINDING_KIND, kind)
			call.putLong(base + DRAW_BINDING_RESOURCE, resource)
			call.putLong(base + DRAW_BINDING_OFFSET, offset)
			call.putLong(base + DRAW_BINDING_LENGTH, length)

			combo = combo * 31 + kind
			combo = combo * 31 + (resource xor (resource ushr 32)).toInt()
			combo = combo * 31 + length.toInt()
			if (offset != 0L) combo = combo * 31 + offset.toInt()
		}
		call.putInt(CALL_COMBO, combo)
		call.putInt(CALL_BINDINGS_PRESENT, 0)

		val address = MemoryUtil.memAddress(call)
		if (WmJni.drawCall(wm, pass, address)) {
			draws++
			return
		}

		// The engine has not seen this combination of resources, so it wants the offsets baked into
		// the bind group it is about to build. One retry is all there is: a second refusal is a
		// refusal, not a request to try again.
		call.putInt(CALL_BINDINGS_PRESENT, 1)
		if (WmJni.drawCall(wm, pass, address)) {
			draws++
			return
		}

		refused++
		warnOnce(
			"refused",
			"the engine refused a draw for pipeline ${compiled.pipeline} with ${compiled.slots} binding(s); " +
				"$refused draw(s) have been dropped this run",
		)
	}

	// ------------------------------------------------------------------ the pipeline

	/**
	 * Whether to name every number a pipeline is described by, once per compiled pipeline.
	 *
	 * A crash inside the engine's compile is a `wgpu_mc_jni.dll` address in an `hs_err` dump and
	 * nothing else, so while the layout is still being proved this is what says which of the fields
	 * below was wrong. It is a flag rather than a setting because it is a debugging aid for one
	 * group, not something a player should ever see - and it is off now, which is what a pipeline
	 * bug is diagnosed by turning back on.
	 */
	@JvmStatic
	var diagnose: Boolean = true

	/**
	 * The pipeline a draw needs, compiled on first ask and kept.
	 *
	 * The key is everything the engine's translation depends on: the two sources, the binding numbers
	 * the layout assigns, the vertex locations, the colour and depth formats, and the fixed-function
	 * state that is built *into* a pipeline rather than set around it. A draw that asks the same
	 * questions gets the same object, which is what makes a cache possible at all.
	 */
	private fun pipeline(
		wm: Long,
		programName: Int,
		program: GlPrograms.Program,
		vao: GlBuffers.VertexArray?,
		mode: Int,
	): Compiled? {
		// Every way out of here says something. A bare `?: return null` is how the blit stopped being
		// drawn for two sessions without a line in the log to say so: the draw was dropped before
		// `record`, so even the per-draw counters that exist to catch exactly this never saw it.
		val vertex = GlPrograms.vertexSource(programName)
		val fragment = GlPrograms.fragmentSource(programName)
		if (vertex == null || fragment == null) {
			warnOnce(
				"missing-stage-$programName",
				"program $programName is missing its ${if (vertex == null) "vertex" else "fragment"} stage, " +
					"so no pipeline can be built for it and every draw of it is dropped",
			)
			return null
		}
		val vertexSource = clean(vertex)
		val fragmentSource = clean(fragment)
		if (vertexSource.isBlank() || fragmentSource.isBlank()) {
			warnOnce("empty-$programName", "program $programName has an empty vertex or fragment stage, so no pipeline can be built for it")
			return null
		}

		val attributes = attributes(program, vao)
		val uniformTypes = declarations(vertexSource, fragmentSource)
		if (uniformTypes.isEmpty()) {
			warnOnce(
				"no-uniforms-$programName",
				"program $programName declares no uniform this side can see, which is a parsing failure " +
					"rather than a shader without uniforms",
			)
		}

		val colourFormat = GlFramebuffers.colorFormat().let { if (it == GpuFormats.NONE) FORMAT_RGBA8_UNORM else it }
		val hasDepth = GlFramebuffers.depthView() != 0L

		val key = key(programName, attributes, colourFormat, hasDepth, mode)
		pipelines[key]?.let { return it }

		val descriptor = MemoryUtil.memAlloc(PIPELINE_SIZE)
		val local = ArrayList<ByteBuffer>()
		try {
			descriptor.putLong(PIPELINE_NAME, MemoryUtil.memAddress(utf8("gl program $programName", local)))
			descriptor.putLong(PIPELINE_VERTEX_SHADER, MemoryUtil.memAddress(utf8(vertexSource + "\n", local)))
			descriptor.putLong(PIPELINE_FRAGMENT_SHADER, MemoryUtil.memAddress(utf8(fragmentSource + "\n", local)))
			// The engine prepends `#version 440` to both stages itself, so the only thing this side has
			// to contribute here is the `#define` block - and 1.21.1's preprocessor has already folded
			// every define into the source it handed `glShaderSource`.
			descriptor.putLong(PIPELINE_DIRECTIVES, MemoryUtil.memAddress(utf8("", local)))
			descriptor.putLong(PIPELINE_FRAG_STATE, 0L)
			descriptor.putLong(PIPELINE_TOPOLOGY, topology(mode).toLong())
			// Culling is the game's own state, not a guess: `glEnable(GL_CULL_FACE)` and `glCullFace`
			// are redirected into `GlState` and this is what they say at the moment of the compile.
			// Forcing this to zero for every pipeline was tried while the window target was frozen
			// and did not move it - but the blit's pipeline was never being built at all in that run,
			// so the experiment proved nothing about culling either way.
			descriptor.putLong(PIPELINE_CULL, if (GlState.isEnabled(GlState.GL_CULL_FACE)) 1L else 0L)

			descriptor.putLong(PIPELINE_BIND_GROUP_LAYOUTS, bindGroupLayout(uniformTypes, local))
			descriptor.putLong(PIPELINE_COLOR_TARGETS, colourTarget(colourFormat, local))
			descriptor.putLong(PIPELINE_DEPTH_STENCIL, if (hasDepth) depthStencil() else 0L)
			descriptor.putLong(PIPELINE_VERTEX_FORMATS, vertexFormats(attributes, local))

			// One line per pipeline, and only while the layout is unproven: a native crash inside the
			// compile names nothing about what it was handed, and every field below is a number the
			// engine will turn into a format or a binding without a second opinion.
			if (GlDraws.diagnose) {
				WgpuMcMod.LOGGER.info(
					"wgpu-mc: compiling {} -> colour format {}, depth {}, topology {}, cull {}, " +
						"{} attribute(s) {}, uniforms {}",
					programName,
					colourFormat,
					hasDepth,
					topology(mode),
					GlState.isEnabled(GlState.GL_CULL_FACE),
					attributes.size,
					attributes.joinToString { "${it.name}@${it.index}(size ${it.size}, type ${it.type}, " +
						"norm ${it.normalized}, int ${it.integer}, stride ${it.stride}, ptr ${it.pointer}, " +
						"format ${vertexFormat(it)})" },
					uniformTypes.entries.joinToString { "${it.key}:${it.value}" },
				)
			}

			val pipeline = WmJni.compileRenderPipeline(wm, MemoryUtil.memAddress(descriptor))
			if (pipeline == 0L) {
				warnOnce("compile-$programName", "the engine refused to compile a pipeline for program $programName")
				return null
			}

			val slots = WmJni.pipelineBindings(pipeline, MemoryUtil.memAddress(planArray))
			if (slots <= 0 || slots > MAX_DRAW_BINDINGS) {
				warnOnce(
					"plan-$programName",
					"the engine reported $slots binding(s) for program $programName, which this side cannot lay " +
						"out; the pipeline is dropped rather than drawn with the wrong resources",
				)
				WmJni.dropRenderPipeline(pipeline)
				return null
			}

			val declared = ArrayList<String>(slots)
			val kinds = IntArray(slots)
			for (slot in 0 until slots) {
				val base = slot * PLAN_STRIDE
				declared.add(readCString(planBuffer, base + PLAN_DECLARED_NAME))
				kinds[slot] = planBuffer.getInt(base + PLAN_KIND)
			}

			// Make sure the program's uniform buffer exists before the first draw that needs it, so a
			// refusal is reported once here rather than on every draw.
			uniformBuffer(wm, program)?.let { it.writtenAt = -1L }

			val compiled = Compiled(pipeline, declared, kinds)
			pipelines[key] = compiled
			WgpuMcMod.LOGGER.info(
				"wgpu-mc: program {} compiled to pipeline {} with {} binding(s) and {} attribute(s)",
				programName,
				pipeline,
				slots,
				attributes.size,
			)
			return compiled
		} finally {
			MemoryUtil.memFree(descriptor)
			for (buffer in local) MemoryUtil.memFree(buffer)
		}
	}

	/** `RawArray_BlazeBindGroupLayout` holding one layout, which holds one `RawArray` of entries. */
	private fun bindGroupLayout(types: Map<String, String>, local: MutableList<ByteBuffer>): Long {
		val names = ArrayList<String>(types.size)
		val kinds = ArrayList<Int>(types.size)
		for ((name, type) in types) {
			// One entry per uniform the shader declares, under the name the shader declares it with.
			// A sampler is *one* entry of kind `Sampler`: the engine's plan splits it into the
			// texture/sampler pair itself (`BindGroupPlan::number`, `blaze.rs:677`), so writing the
			// two shim names here would have the engine split the second of them again and leave the
			// plan four slots wide where the shader has two.
			names.add(name)
			kinds.add(if (GlPrograms.isSampler(type)) LAYOUT_SAMPLER else LAYOUT_UNIFORM)
		}

		val count = names.size.coerceAtLeast(1)
		val entries = MemoryUtil.memAlloc(ENTRY_SIZE * count)
		val entryArray = MemoryUtil.memAlloc(RAW_ARRAY_SIZE)
		val layout = MemoryUtil.memAlloc(BIND_GROUP_LAYOUT_SIZE)
		val array = MemoryUtil.memAlloc(RAW_ARRAY_SIZE)

		for (i in names.indices) {
			val base = i * ENTRY_SIZE
			entries.putLong(base + ENTRY_TYPE, kinds[i].toLong())
			entries.putLong(base + ENTRY_NAME, MemoryUtil.memAddress(utf8(names[i], local)))
			// A texture binding's format is its sample type, and every texture this game samples is an
			// 8-bit colour one, including the lightmap and the two overlay targets.
			entries.putLong(base + ENTRY_FORMAT, FORMAT_RGBA8_UNORM.toLong())
		}

		entryArray.putLong(RAW_ARRAY_CONTENTS, MemoryUtil.memAddress(entries))
		entryArray.putLong(RAW_ARRAY_COUNT, names.size.toLong())
		layout.putLong(BIND_GROUP_LAYOUT_ENTRIES, MemoryUtil.memAddress(entryArray))
		array.putLong(RAW_ARRAY_CONTENTS, MemoryUtil.memAddress(layout))
		array.putLong(RAW_ARRAY_COUNT, 1L)

		local.add(entries)
		local.add(entryArray)
		local.add(layout)
		local.add(array)
		return MemoryUtil.memAddress(array)
	}

	private fun colourTarget(format: Int, local: MutableList<ByteBuffer>): Long {
		val target = MemoryUtil.memAlloc(COLOR_TARGET_SIZE)
		val blend = MemoryUtil.memAlloc(BLEND_SIZE)
		val array = MemoryUtil.memAlloc(RAW_ARRAY_SIZE)

		if (GlState.isEnabled(GlState.GL_BLEND)) {
			blend.putLong(BLEND_SRC_COLOR, blendFactor(GlState.blendSrcRgb).toLong())
			blend.putLong(BLEND_DST_COLOR, blendFactor(GlState.blendDstRgb).toLong())
			blend.putLong(BLEND_SRC_ALPHA, blendFactor(GlState.blendSrcAlpha).toLong())
			blend.putLong(BLEND_DST_ALPHA, blendFactor(GlState.blendDstAlpha).toLong())
			target.putLong(COLOR_TARGET_BLEND, MemoryUtil.memAddress(blend))
		} else {
			target.putLong(COLOR_TARGET_BLEND, 0L)
		}

		target.putLong(COLOR_TARGET_FORMAT, format.toLong())
		// Four bytes wide, and written as an int: the struct pads after it.
		target.putInt(COLOR_TARGET_WRITE_MASK, GlState.colorMask)
		// The engine's `color_target_states` is one `RawArray<BlazeColorTargetState>`, so its
		// `contents` points straight at the states - the same shape `vertexFormats` uses. An extra
		// RawArray in between reads the *count* of that inner array where the engine expects
		// `format`, which is how a colour target of RGBA8 arrives as `R8_UNORM` and then takes the
		// process down in the blend conversion.
		array.putLong(RAW_ARRAY_CONTENTS, MemoryUtil.memAddress(target))
		array.putLong(RAW_ARRAY_COUNT, 1L)

		local.add(target)
		local.add(blend)
		local.add(array)
		return MemoryUtil.memAddress(array)
	}

	/**
	 * The depth state, which is where GL's two separate questions become one answer.
	 *
	 * The engine's `active` is the *write* flag and nothing else - `depth_compare` is applied whatever
	 * it says - so `glDepthTest(false)` becomes `Always` with no writes rather than a missing state,
	 * and `glDepthMask(false)` becomes `active = 0` with the compare left alone. That is exactly GL's
	 * own meaning for the pair, and it is why the state is always present: a pass with a depth
	 * attachment needs a pipeline that declares one.
	 */
	private fun depthStencil(): Long {
		val buffer = depthBuffer
		val testing = GlState.isEnabled(GlState.GL_DEPTH_TEST)
		buffer.putLong(DEPTH_COMPARE, (if (testing) compare(GlState.depthFunc) else COMPARE_ALWAYS).toLong())
		buffer.putLong(DEPTH_ACTIVE, if (testing && GlState.depthMask) 1L else 0L)
		buffer.putInt(DEPTH_BIAS_CONSTANT, Math.round(GlState.polygonOffsetFactor))
		buffer.putFloat(DEPTH_BIAS_SLOPE, GlState.polygonOffsetUnits)
		return MemoryUtil.memAddress(buffer)
	}

	/** `RawArray_VertexFormat` with one format, whose elements are the vertex array's attributes. */
	private fun vertexFormats(attributes: List<GlBuffers.Attribute>, local: MutableList<ByteBuffer>): Long {
		val format = MemoryUtil.memAlloc(VERTEX_FORMAT_SIZE)
		val array = MemoryUtil.memAlloc(RAW_ARRAY_SIZE)
		val elements = MemoryUtil.memAlloc(VERTEX_ELEMENT_SIZE * attributes.size.coerceAtLeast(1))
		val elementArray = MemoryUtil.memAlloc(RAW_ARRAY_SIZE)

		var stride = 0
		for ((i, attribute) in attributes.withIndex()) {
			val base = i * VERTEX_ELEMENT_SIZE
			elements.putLong(base + VERTEX_ELEMENT_OFFSET, attribute.pointer)
			elements.putLong(base + VERTEX_ELEMENT_FORMAT, vertexFormat(attribute).toLong())
			elements.putLong(base + VERTEX_ELEMENT_NAME, MemoryUtil.memAddress(utf8(attribute.name, local)))
			if (attribute.stride > stride) stride = attribute.stride
		}

		elementArray.putLong(RAW_ARRAY_CONTENTS, MemoryUtil.memAddress(elements))
		elementArray.putLong(RAW_ARRAY_COUNT, attributes.size.toLong())
		format.putLong(VERTEX_FORMAT_ELEMENTS, MemoryUtil.memAddress(elementArray))
		format.putLong(VERTEX_FORMAT_STRIDE, stride.toLong())
		array.putLong(RAW_ARRAY_CONTENTS, MemoryUtil.memAddress(format))
		array.putLong(RAW_ARRAY_COUNT, 1L)

		local.add(elements)
		local.add(elementArray)
		local.add(format)
		local.add(array)
		return MemoryUtil.memAddress(array)
	}

	/**
	 * The attributes of the bound vertex array, in the order the engine will number them.
	 *
	 * That order is the order of `glGetAttribLocation`'s answers, which is the order the game asked -
	 * which is the order of the vertex format's own elements. Sorting by the GL attribute index is
	 * therefore not a convenience: it is the only ordering that agrees with both sides at once.
	 */
	private fun attributes(program: GlPrograms.Program, vao: GlBuffers.VertexArray?): List<GlBuffers.Attribute> {
		if (vao == null) return emptyList()
		val named = ArrayList<GlBuffers.Attribute>(vao.enabled.size)
		for (index in vao.enabled) {
			val attribute = vao.attributes[index] ?: continue
			named.add(attribute.withName(nameOf(program, index)))
		}
		return named.sortedBy { it.index }
	}

	/** The GLSL name the game asked a location for, which is the name the shader declares. */
	private fun nameOf(program: GlPrograms.Program, index: Int): String {
		for ((name, location) in program.attributes) {
			if (location == index) return name
		}
		return "wm_attribute_$index"
	}

	/** The vertex buffer every attribute reads from, which the engine needs as a slot. */
	private fun firstAttributeBuffer(vao: GlBuffers.VertexArray?): GlBuffers.Entry? {
		if (vao == null) return null
		for (index in vao.enabled.sorted()) {
			val attribute = vao.attributes[index] ?: continue
			val entry = GlBuffers.byName(attribute.buffer) ?: continue
			if (entry.exists) return entry
		}
		return null
	}

	// ------------------------------------------------------------------ the encoder and the pass

	/**
	 * Opens a pass on the bound framebuffer.
	 *
	 * Every attachment carries a **null clear value**, which the engine reads as "load what is already
	 * there" rather than "clear to zero". GL clears by an explicit `glClear`, answered by
	 * `GlFramebuffers.clearCurrent`, so a pass must never clear on its own.
	 */
	private fun createPass(): Long {
		val colour = GlFramebuffers.colorView()
		val depth = GlFramebuffers.depthView()

		val descriptor = MemoryUtil.memAlloc(16)
		val attachment = MemoryUtil.memAlloc(16)
		val attachments = MemoryUtil.memAlloc(RAW_ARRAY_SIZE)
		try {
			attachment.putLong(0, colour)
			attachment.putLong(8, 0L)
			attachments.putLong(RAW_ARRAY_CONTENTS, MemoryUtil.memAddress(attachment))
			attachments.putLong(RAW_ARRAY_COUNT, 1L)
			descriptor.putLong(0, MemoryUtil.memAddress(attachments))
			if (depth == 0L) {
				descriptor.putLong(8, 0L)
			} else {
				depthAttachmentBuffer.putLong(0, depth)
				depthAttachmentBuffer.putLong(8, 0L)
				descriptor.putLong(8, MemoryUtil.memAddress(depthAttachmentBuffer))
			}
			return WmJni.createRenderPass(encoder, MemoryUtil.memAddress(descriptor), "gl draw")
		} finally {
			MemoryUtil.memFree(descriptor)
			MemoryUtil.memFree(attachment)
			MemoryUtil.memFree(attachments)
		}
	}

	/**
	 * `glScissor`, in the coordinates the engine wants.
	 *
	 * GL's scissor origin is the lower left of the target and the engine's is its upper left, so the
	 * rectangle is turned over on the way. It is also clamped to the target rather than trusted: the
	 * game's rectangle is in window pixels while the target may be scaled, and a scissor outside the
	 * attachment is a wgpu validation error, which ends the process.
	 */
	private fun scissor(pass: Long) {
		val width = GlFramebuffers.targetWidth()
		val height = GlFramebuffers.targetHeight()
		if (width <= 0 || height <= 0) {
			WmJni.setScissorRect(pass, 0, 0, 0, 0)
			return
		}
		if (!GlState.isEnabled(GlState.GL_SCISSOR_TEST)) {
			WmJni.setScissorRect(pass, 0, 0, width, height)
			return
		}
		val x = GlState.scissorX.coerceIn(0, width)
		val w = GlState.scissorWidth.coerceAtMost(width - x)
		val y = GlState.scissorY.coerceIn(0, height)
		val h = GlState.scissorHeight.coerceAtMost(height - y)
		if (w <= 0 || h <= 0) {
			WmJni.setScissorRect(pass, 0, 0, 0, 0)
			return
		}
		WmJni.setScissorRect(pass, x, height - y - h, w, h)
	}

	// ------------------------------------------------------------------ uniforms

	private fun uniformBuffer(wm: Long, program: GlPrograms.Program): Uniforms? {
		refresh(wm, program)
		return uniformBuffers[program.name]
	}

	/**
	 * Lays a program's uniforms out in one engine buffer, and rewrites them when they have moved.
	 *
	 * A uniform is std140 in the shader, because that is what the engine annotates a loose declaration
	 * into - so each one gets a whole aligned slot of its own rather than being packed. `glUniform*`
	 * uploads arrive one uniform at a time and in any order, which a packed layout could not express.
	 *
	 * The slots are aligned to the device's own minimum uniform offset alignment because the offset
	 * travels with the draw as a *dynamic* offset, and wgpu validates it against exactly that number.
	 */
	private fun refresh(wm: Long, program: GlPrograms.Program) {
		val existing = uniformBuffers[program.name]
		if (existing != null && existing.writtenAt == GlPrograms.uniformEpoch) return

		val declared = program.byLocation
		if (declared.isEmpty()) return

		val alignment = WmJni.minUniformOffsetAlignment(wm).coerceAtLeast(16)
		var uniforms = existing
		if (uniforms == null) {
			var total = 0L
			for (uniform in declared) {
				val size = uniformSize(program, uniform.name).toLong()
				total += (size + alignment - 1) / alignment * alignment
			}
			val buffer = WmJni.createBuffer(
				wm,
				"gl uniforms ${program.name}",
				USAGE_UNIFORM or USAGE_COPY_DST,
				Math.max(total, alignment.toLong()),
			)
			if (buffer == 0L) {
				warnOnce("uniform-buffer-${program.name}", "the engine refused a uniform buffer for program ${program.name}")
				return
			}
			uniforms = Uniforms(buffer, alignment)
			var offset = 0L
			for (uniform in declared) {
				uniforms.offsets[uniform.name] = offset
				val size = uniformSize(program, uniform.name).toLong()
				offset += (size + alignment - 1) / alignment * alignment
			}
			uniformBuffers[program.name] = uniforms
		}

		val slot = Math.max(alignment, 64)
		val scratch = MemoryUtil.memAlloc(slot)
		try {
			for (uniform in declared) {
				val offset = uniforms.offsetOf(uniform.name)
				if (offset < 0L) continue
				val address = MemoryUtil.memAddress(scratch)
				MemoryUtil.memSet(address, 0, slot.toLong())
				writeUniform(scratch, uniform)
				WmJni.writeToBuffer(wm, uniforms.buffer, offset, uniformSize(program, uniform.name).toLong(), address)
			}
		} finally {
			MemoryUtil.memFree(scratch)
		}
		uniforms.writtenAt = GlPrograms.uniformEpoch
	}

	private fun writeUniform(scratch: ByteBuffer, uniform: GlPrograms.Uniform) {
		val type = uniform.declaredType
		if (type.startsWith("int") || type.startsWith("uint") || type == "bool") {
			val values = uniform.ints
			for (i in values.indices) scratch.putInt(i * 4, values[i])
		} else {
			val values = uniform.floats
			for (i in values.indices) scratch.putFloat(i * 4, values[i])
		}
	}

	/** Bytes a uniform needs in the std140 layout the engine annotates a loose declaration into. */
	private fun uniformSize(program: GlPrograms.Program, name: String): Int = when (program.byName[name]?.declaredType) {
		"mat4" -> 64
		"mat3" -> 48
		"mat2" -> 32
		null -> 64
		else -> 16
	}

	/** The texture a sampler uniform selects, through the unit the game put it on. */
	private fun textureFor(program: GlPrograms.Program, declared: String): GlTextures.Entry? {
		val unit = unitOf(program, declared)
		if (unit < 0 || unit >= GlState.boundTextures.size) return null
		val name = GlState.boundTextures[unit]
		if (name < 0) return null
		val entry = GlTextures.byName(name) ?: return null
		return if (entry.exists) entry else null
	}

	/**
	 * Which texture unit a sampler reads.
	 *
	 * 1.21.1 gives its samplers the unit in their *name* - `Sampler0`, `Sampler1`, `Sampler2` - and
	 * `RenderSystem.setShaderTexture` binds that unit without uploading a uniform at all. So the name
	 * is the primary answer and an uploaded `glUniform1i` is preferred when it says something: a
	 * shader that really does set its own sampler unit keeps it.
	 */
	private fun unitOf(program: GlPrograms.Program, declared: String): Int {
		val uniform = program.byName[declared]
		if (uniform != null && uniform.ints[0] != 0) return uniform.ints[0]
		val trailing = declared.takeLastWhile { it.isDigit() }
		return trailing.toIntOrNull() ?: (uniform?.ints?.get(0) ?: 0)
	}

	private fun sampler(wm: Long, entry: GlTextures.Entry): Long {
		val key = entry.samplerKey()
		samplers[key]?.let { return it }
		val mipmapped = entry.minFilter >= GL_NEAREST_MIPMAP_NEAREST
		val handle = WmJni.createSampler(
			wm,
			addressMode(entry.wrapS),
			addressMode(entry.wrapT),
			if (mipmapped) FILTER_LINEAR else filterMode(entry.minFilter),
			filterMode(entry.magFilter),
			entry.maxAnisotropy.coerceIn(0, MAX_ANISOTROPY),
			entry.maxLod.toDouble(),
		)
		if (handle != 0L) samplers[key] = handle
		return handle
	}

	@JvmStatic
	fun clear() {
		for (compiled in pipelines.values) WmJni.dropRenderPipeline(compiled.pipeline)
		pipelines.clear()
		for (uniforms in uniformBuffers.values) WmJni.dropBuffer(uniforms.buffer)
		uniformBuffers.clear()
		for (handle in samplers.values) WmJni.dropSampler(handle)
		samplers.clear()
		encoder = 0L
		draws = 0L
		refused = 0L
	}

	/** How many draws this object has answered, for the one line the renderer logs about it. */
	@JvmStatic
	fun draws(): Long = draws

	// ------------------------------------------------------------------ the small translations

	private fun indexFormat(type: Int): Int = when (type) {
		5123 -> 1
		5125 -> 2
		else -> 0
	}

	/** `PrimitiveTopology`, from the GL mode. */
	private fun topology(mode: Int): Int = when (mode) {
		0 -> 3
		1 -> 1
		3 -> 2
		5 -> 5
		6 -> 6
		7 -> 7
		else -> 4
	}

	/** `CompareFunction`, from `glDepthFunc`. */
	private fun compare(function: Int): Int = when (function) {
		512 -> 0
		513 -> 1
		514 -> 2
		515 -> 3
		516 -> 4
		517 -> 5
		518 -> 6
		else -> COMPARE_ALWAYS
	}

	/** `BlendFactor`, from `glBlendFunc` / `glBlendFuncSeparate`. */
	private fun blendFactor(factor: Int): Int = when (factor) {
		0 -> 0
		1 -> 1
		768 -> 2
		769 -> 3
		774 -> 4
		775 -> 5
		770 -> 6
		771 -> 7
		772 -> 8
		773 -> 9
		776 -> 10
		32769 -> 11
		32771 -> 11
		32770 -> 12
		32772 -> 12
		else -> 1
	}

	private fun addressMode(wrap: Int): Int = when (wrap) {
		GL_REPEAT, GL_MIRRORED_REPEAT -> ADDRESS_REPEAT
		else -> ADDRESS_CLAMP
	}

	private fun filterMode(filter: Int): Int = when (filter) {
		GL_NEAREST, GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST_MIPMAP_LINEAR -> FILTER_NEAREST
		else -> FILTER_LINEAR
	}

	/**
	 * The `GpuFormat` of one vertex element, from the `glVertexAttribPointer` arguments.
	 *
	 * The engine's formats are laid out in blocks of eight - one block per component count, two per
	 * component *type* - so the arithmetic is the same for every type: a normalised small type and its
	 * integer twin are eight apart, and adding `count - 1` moves within the block. 8- and 16-bit
	 * three-component formats read one component past the element, which is why the stride has to be
	 * the vertex size and the element has to fit inside it.
	 */
	private fun vertexFormat(attribute: GlBuffers.Attribute): Int {
		val count = attribute.size.coerceIn(1, 4)
		if (attribute.integer || !attribute.normalized) {
			return when (attribute.type) {
				5121 -> 17 + (count - 1) * 2
				5120 -> 18 + (count - 1) * 2
				5123 -> 25 + (count - 1) * 2
				5122 -> 26 + (count - 1) * 2
				5125 -> 33 + (count - 1) * 2
				5124 -> 34 + (count - 1) * 2
				5131 -> 41 + (count - 1)
				else -> 45 + (count - 1)
			}
		}
		return when (attribute.type) {
			5121 -> 1 + (count - 1) * 2
			5120 -> 2 + (count - 1) * 2
			5123 -> 9 + (count - 1) * 2
			5122 -> 10 + (count - 1) * 2
			5131 -> 41 + (count - 1)
			else -> 45 + (count - 1)
		}
	}

	/**
	 * The uniforms a pair of sources declares, by name.
	 *
	 * [GlPrograms.declarations] already answers name-to-type - the layout wants the names and the
	 * sampler split wants the types - so this is only the two stages joined.
	 */
	private fun declarations(vertexSource: String, fragmentSource: String): Map<String, String> =
		GlPrograms.declarations("$vertexSource\n$fragmentSource")

	/**
	 * A shader source with its `#version` line removed.
	 *
	 * The engine inserts `#version 440` at the front of both stages, and a source that brings its own
	 * would have two. 1.21.1's shaders carry `#version 150`, so this is the normal case rather than a
	 * defensive one.
	 */
	private fun clean(source: String): String =
		source.lineSequence().filterNot { it.trimStart().startsWith("#version") }.joinToString("\n")

	/** A number that stands for everything the pipeline was built from. */
	private fun key(
		programName: Int,
		attributes: List<GlBuffers.Attribute>,
		colourFormat: Int,
		hasDepth: Boolean,
		mode: Int,
	): Long {
		var hash = 1125899906842597L
		hash = hash * 31 + programName
		hash = hash * 31 + colourFormat
		hash = hash * 31 + (if (hasDepth) 1 else 0)
		hash = hash * 31 + mode
		hash = hash * 31 + GlState.colorMask
		hash = hash * 31 + (if (GlState.isEnabled(GlState.GL_BLEND)) 1 else 0)
		hash = hash * 31 + GlState.blendSrcRgb
		hash = hash * 31 + GlState.blendDstRgb
		hash = hash * 31 + GlState.blendSrcAlpha
		hash = hash * 31 + GlState.blendDstAlpha
		hash = hash * 31 + (if (GlState.isEnabled(GlState.GL_DEPTH_TEST)) 1 else 0)
		hash = hash * 31 + (if (GlState.depthMask) 1 else 0)
		hash = hash * 31 + GlState.depthFunc
		hash = hash * 31 + (if (GlState.isEnabled(GlState.GL_CULL_FACE)) 1 else 0)
		for (attribute in attributes) {
			hash = hash * 31 + attribute.index
			hash = hash * 31 + attribute.size
			hash = hash * 31 + attribute.type
			hash = hash * 31 + (if (attribute.normalized) 1 else 0)
			hash = hash * 31 + (if (attribute.integer) 1 else 0)
			hash = hash * 31 + attribute.stride
			hash = hash * 31 + attribute.pointer
			hash = hash * 31 + attribute.name.hashCode()
		}
		return hash
	}

	// ------------------------------------------------------------------ buffers and strings

	private fun utf8(value: String, into: MutableList<ByteBuffer>): ByteBuffer {
		val buffer = MemoryUtil.memUTF8(value)
		into.add(buffer)
		return buffer
	}

	/**
	 * The string a `FfiStr` field names, read back without running past its end.
	 *
	 * `FfiStr` is a **pointer** to a NUL-terminated string the engine leaked on purpose, not the
	 * characters themselves - so the eight bytes at [at] are an address, and the string begins at
	 * that address. Reading the field's own bytes instead yields the pointer's little-endian digits
	 * as text, which is how a plan slot came to be called `'\uFFFDD,$\uFFFD'` while the engine
	 * insisted the same slot was called `ModelViewMat`.
	 */
	private fun readCString(buffer: ByteBuffer, at: Int): String {
		val pointer = MemoryUtil.memGetAddress(MemoryUtil.memAddress(buffer) + at)
		if (pointer == 0L) return ""
		val bytes = ByteArray(96)
		var length = 0
		while (length < bytes.size) {
			val value = MemoryUtil.memGetByte(pointer + length)
			if (value.toInt() == 0) break
			bytes[length] = value
			length++
		}
		return String(bytes, 0, length, Charsets.UTF_8)
	}

	private fun warnOnce(key: String, message: String) {
		if (warned.add(key)) {
			WgpuMcMod.LOGGER.warn("wgpu-mc: {}", message)
		}
	}
}
