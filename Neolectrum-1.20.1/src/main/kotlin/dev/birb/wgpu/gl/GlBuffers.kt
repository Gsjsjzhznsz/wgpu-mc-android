package dev.birb.wgpu.gl

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.rust.WmJni
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * The buffers and vertex arrays GL thinks it has.
 *
 * Minecraft names a buffer with a number it got from `glGenBuffers`, gives it storage with
 * `glBufferData` and draws out of it. wgpu has the same two ideas but not the same lifecycle, and
 * three of the differences are the whole of this file:
 *
 * - **A name is not an object.** `glGenBuffers` only mints a number; a GL buffer object exists from
 *   the first `glBindBuffer` that names it, and has no store until `glBufferData`. So a name may be
 *   held here with no engine buffer behind it, which is what [Entry.exists] answers.
 * - **`glBufferData` replaces the store.** GL says any existing data store is deleted and the size
 *   may change, and Minecraft does exactly that when a `VertexBuffer` is re-uploaded with a
 *   different vertex count. The engine buffer is dropped and made again, which is also the cheap
 *   path: the engine pools buffers by size and flags, so a re-upload of the same size hands the same
 *   allocation back rather than making a driver allocation per upload.
 * - **A vertex array holds the attribute state.** `glVertexAttribPointer` and
 *   `glEnableVertexAttribArray` describe the *currently bound* vertex array, and a draw is
 *   described by that description. Nothing here is sent to the engine yet - a draw is what turns a
 *   vertex array and a bound buffer into a wgpu pipeline and a `draw_call`, and that group is not
 *   implemented - but the state is recorded faithfully so that it can be.
 *
 * The engine's usage mask is not GL's. `rust/wgpu-mc-jni/src/device.rs`'s `wgpu_buffer_usages` reads
 * these bits and is the only description of them: bit 32 is `VERTEX`, 64 `INDEX`, 128 `UNIFORM`, 8
 * `COPY_DST`, 16 `COPY_SRC`, 256 `STORAGE`, bits 1 and 2 the two mappings. Every buffer here gains
 * `COPY_DST` because this side is what fills it, and the engine adds `COPY_SRC` to anything that is
 * not mappable so it can still be read back for a dump.
 *
 * Nothing here is on a hot path: buffers are created at resource reload and at section rebuild, and
 * the per-draw question - which buffer a draw reads - is one lookup in a small map.
 */
object GlBuffers {
	// ---- GL enums --------------------------------------------------------------------------------
	const val GL_ARRAY_BUFFER = 34962
	const val GL_ELEMENT_ARRAY_BUFFER = 34963
	const val GL_UNIFORM_BUFFER = 35345
	const val GL_TEXTURE_BUFFER = 35882
	const val GL_PIXEL_PACK_BUFFER = 35051
	const val GL_PIXEL_UNPACK_BUFFER = 35052
	const val GL_COPY_READ_BUFFER = 36662
	const val GL_COPY_WRITE_BUFFER = 36663
	const val GL_DRAW_INDIRECT_BUFFER = 36671

	const val GL_READ_ONLY = 35000
	const val GL_WRITE_ONLY = 35001
	const val GL_READ_WRITE = 35002

	// ---- the usage bits the engine's `create_buffer` reads ---------------------------------------
	const val USAGE_MAP_READ = 1
	const val USAGE_MAP_WRITE = 2
	const val USAGE_COPY_DST = 8
	const val USAGE_COPY_SRC = 16
	const val USAGE_VERTEX = 32
	const val USAGE_INDEX = 64
	const val USAGE_UNIFORM = 128
	const val USAGE_STORAGE = 256

	/** A buffer as GL sees it: a name, and optionally a store behind it. */
	class Entry(val name: Int) {
		/** The target this name was first bound to, which is what decides its wgpu usage flags. */
		var target: Int = 0

		/** The engine buffer, or 0 for a name that was generated and never given storage. */
		var handle: Long = 0L

		/** How many bytes the engine buffer behind [handle] actually holds. See [storage]. */
		var allocated: Long = 0L

		var size: Long = 0L
		var usage: Int = 0

		/** The scratch a `glMapBuffer` handed out, which `glUnmapBuffer` uploads and frees. */
		var mapping: ByteBuffer? = null

		val exists: Boolean get() = handle != 0L
	}

	/** One `glVertexAttribPointer`/`glVertexAttribIPointer`, as the vertex array recorded it. */
	class Attribute(
		val index: Int,
		val size: Int,
		val type: Int,
		val normalized: Boolean,
		val stride: Int,
		val pointer: Long,
		/** `true` for the integer form, which reads the attribute as integers rather than floats. */
		val integer: Boolean,
	) {
		/**
		 * The array buffer that was bound when this pointer was set.
		 *
		 * GL binds the *buffer* to the attribute rather than a pointer into whatever happens to be
		 * bound at draw time, and the engine needs a buffer handle to put in the draw call's vertex
		 * slot. Reading the binding at draw time instead would name whichever buffer Minecraft bound
		 * last, which is not necessarily the one this attribute reads.
		 */
		var buffer: Int = 0

		/**
		 * The GLSL name of the attribute, which is what the engine writes `layout(location = N)` from.
		 *
		 * GL never names an attribute here: the game asks for a *location* by name and points the slot
		 * at a buffer, so the two halves are only brought together at draw time. The fallback is a
		 * name that identifies the slot, which is what a program that never asked would get.
		 */
		var name: String = "attribute$index"

		/** The same attribute under a name a draw has resolved, since the name is per program. */
		fun withName(name: String): Attribute {
			val copy = Attribute(index, size, type, normalized, stride, pointer, integer)
			copy.buffer = buffer
			copy.name = name
			return copy
		}
	}

	/** A vertex array: the attribute state a draw is described by, and its element buffer. */
	class VertexArray {
		val attributes = HashMap<Int, Attribute>()
		val enabled = HashSet<Int>()
		var elementBuffer: Int = 0
	}

	private val entries = HashMap<Int, Entry>()
	private val arrays = HashMap<Int, VertexArray>()

	/** Bound buffer per target, and the bound vertex array - the two pieces of state a draw needs. */
	private val bound = HashMap<Int, Int>()
	private var boundArray: Int = 0

	private var nextBuffer = 1
	private var nextArray = 1

	// ---- names -----------------------------------------------------------------------------------

	@JvmStatic
	fun genBuffer(): Int {
		val name = nextBuffer++
		entries[name] = Entry(name)
		return name
	}

	@JvmStatic
	fun deleteBuffer(name: Int) {
		val entry = entries.remove(name) ?: return
		release(entry)
		bound.entries.removeIf { it.value == name }
		for (array in arrays.values) {
			if (array.elementBuffer == name) {
				array.elementBuffer = 0
			}
		}
	}

	@JvmStatic
	fun byName(name: Int): Entry? = entries[name]

	// ---- binding and storage ---------------------------------------------------------------------

	@JvmStatic
	fun bindBuffer(target: Int, name: Int) {
		if (name == 0) {
			bound.remove(target)
			if (target == GL_ELEMENT_ARRAY_BUFFER) {
				arrays[boundArray]?.elementBuffer = 0
			}
			return
		}

		val entry = entries[name] ?: Entry(name).also { entries[name] = it }
		if (entry.target == 0) {
			entry.target = target
		}
		bound[target] = name

		// GL keeps the element buffer binding *in the vertex array*, which is why a draw can read it
		// without the game re-binding it between the upload and the draw.
		if (target == GL_ELEMENT_ARRAY_BUFFER) {
			arrays.getOrPut(boundArray) { VertexArray() }.elementBuffer = name
		}
	}

	@JvmStatic
	fun boundBuffer(target: Int): Entry? = entries[bound[target] ?: return null]

	@JvmStatic
	fun bufferData(target: Int, data: ByteBuffer?, usage: Int) {
		val entry = boundBuffer(target) ?: return
		storage(entry, if (data == null) 0L else data.remaining().toLong(), data)
	}

	@JvmStatic
	fun bufferData(target: Int, size: Long, usage: Int) {
		val entry = boundBuffer(target) ?: return
		storage(entry, size, null)
	}

	/**
	 * Give a name its store, replacing whatever it had.
	 *
	 * A zero-length store is a name with no engine buffer rather than a zero-byte one: an empty
	 * `VertexBuffer` is uploaded before it is drawn from, and a `wgpu::Buffer` of length zero is a
	 * resource with nothing to say.
	 */
	private fun storage(entry: Entry, size: Long, data: ByteBuffer?) {
		entry.usage = usageFor(entry.target)
		if (size <= 0L) {
			release(entry)
			entry.size = 0L
			return
		}

		val wm = WgpuRenderer.handle()

		// **No renderer yet means no engine buffer, and it is a state rather than a race.**
		// `LevelRenderer` uploads its star buffer from `Minecraft`'s own constructor, and the native
		// renderer is created by work the client setup event queues for *after* that constructor - so
		// vanilla uploads into this seam before there is anything behind it, and before the native
		// library is loaded at all. Reaching JNI there is `UnsatisfiedLinkError: 'long
		// dev.birb.wgpu.rust.WmJni.createBuffer(long, java.lang.String, int, long)'`, reported as a
		// crash during "Initializing game". The store is simply not made; every buffer the renderer
		// actually draws out of is uploaded after it exists.
		if (wm == 0L) {
			entry.size = size
			return
		}

		// **A store that is already big enough is kept, not replaced.** GL reads `glBufferData` as
		// orphaning the old store, but the *name* keeps its identity, and Minecraft calls it again and
		// again on one name: the immediate-mode buffers it uploads each GUI batch into, the terrain
		// arena, every `VertexBuffer` it re-uploads. Handing each of those a fresh engine buffer is what
		// a driver answers by reserving device memory faster than it gives it back. Measured in a world,
		// 300 frames created 14,157 buffers and the process grew by ~170 MB a second with every one of
		// the renderer's own counters flat, because the buffers it had dropped were counted and the
		// driver's blocks behind them were not.
		//
		// Reusing the store is the same bytes in the same place. What it is not is a buffer that is
		// zeroed: bytes past `size` are whatever the last upload left there. Nothing reads them - a draw
		// takes its count from the game, not from the store - which is the same guarantee GL gives an
		// orphaned store, and it is why `allocated` and `size` are two fields.
		if (entry.handle == 0L || entry.allocated < size) {
			release(entry)
			entry.allocated = size
			entry.handle = WmJni.createBuffer(wm, "gl buffer " + entry.name, entry.usage, size)
		}
		entry.size = size

		// `createBufferInit` would do this in one call, but the engine's own JNI bridge does not
		// answer it on this branch - so the store is made empty and filled, which is the same
		// buffer and the same bytes. The address is the *position's*, because that is where GL
		// starts reading: `glBufferData` uploads `remaining()`, not the whole capacity.
		if (data != null && data.remaining() > 0) {
			WmJni.writeToBuffer(
				wm,
				entry.handle,
				0L,
				data.remaining().toLong(),
				// `memAddress` is `memAddress0 + position()`, so this is already where GL starts
				// reading. Adding `position()` to it again - which is what this did - makes the
				// upload read from twice the offset, and the bytes past the end are whatever the
				// allocator left there.
				MemoryUtil.memAddress(data),
			)
		}
	}

	/**
	 * The wgpu flags a GL target implies.
	 *
	 * GL's draw hints (`GL_STATIC_DRAW` and the rest) say nothing wgpu acts on - it has no notion of
	 * a usage hint, and the engine reads a buffer's usages from the mask alone - so the *target* is
	 * what this reads, and every buffer gains `COPY_DST` because this side is what fills it.
	 */
	private fun usageFor(target: Int): Int {
		val semantic = when (target) {
			GL_ARRAY_BUFFER -> USAGE_VERTEX
			GL_ELEMENT_ARRAY_BUFFER -> USAGE_INDEX
			GL_UNIFORM_BUFFER -> USAGE_UNIFORM
			GL_COPY_READ_BUFFER, GL_PIXEL_PACK_BUFFER -> USAGE_COPY_SRC
			else -> USAGE_COPY_DST
		}
		return semantic or USAGE_COPY_DST
	}

	private fun release(entry: Entry) {
		if (entry.handle != 0L) {
			WmJni.dropBuffer(entry.handle)
			entry.handle = 0L
		}
		entry.allocated = 0L
		entry.mapping?.let { MemoryUtil.memFree(it) }
		entry.mapping = null
	}

	// ---- mapping ---------------------------------------------------------------------------------

	/**
	 * `glMapBuffer`, as the one thing Minecraft does with it: write into a range and unmap.
	 *
	 * GL hands back a pointer into the driver's own store and makes the write visible on
	 * `glUnmapBuffer`. There is no such pointer here, so the mapping is a scratch of the buffer's
	 * size which [unmapBuffer] uploads. That is faithful for the write direction and *not* for the
	 * read direction - `GL_READ_ONLY` would hand back zeroes rather than the buffer's contents - so
	 * that case says so once rather than looking like it worked.
	 */
	@JvmStatic
	fun mapBuffer(target: Int, access: Int): ByteBuffer? {
		val entry = boundBuffer(target) ?: return null
		if (!entry.exists) {
			return null
		}
		if (access == GL_READ_ONLY || access == GL_READ_WRITE) {
			warnOnce("glMapBuffer(GL_READ_ONLY) cannot read a buffer back from here, so the range reads as zeroes")
		}
		val mapping = entry.mapping ?: MemoryUtil.memAlloc(entry.size.toInt()).also { entry.mapping = it }
		mapping.clear()
		return mapping
	}

	/**
	 * `glUnmapBuffer`, which answers GL's "the store is still valid" - always true here, because the
	 * upload happens before this returns and a failure would already have been a wgpu error.
	 */
	@JvmStatic
	fun unmapBuffer(target: Int): Boolean {
		val entry = boundBuffer(target) ?: return true
		val mapping = entry.mapping ?: return true
		entry.mapping = null
		try {
			if (entry.exists && mapping.position() > 0) {
				WmJni.writeToBuffer(
					WgpuRenderer.handle(),
					entry.handle,
					0L,
					mapping.position().toLong(),
					// From the *start* of the scratch: the length is `position()`, so the bytes
					// meant are the first `position()` of them. `memAddress` would add `position()`
					// a second time and read the whole range past the end of the allocation - a
					// `memcpy` fault inside the engine, which is an access violation and takes the
					// process with it rather than raising anything catchable.
					MemoryUtil.memAddress0(mapping),
				)
			}
		} finally {
			MemoryUtil.memFree(mapping)
		}
		return true
	}

	// ---- vertex arrays ---------------------------------------------------------------------------

	@JvmStatic
	fun genVertexArray(): Int {
		val name = nextArray++
		arrays[name] = VertexArray()
		return name
	}

	@JvmStatic
	fun deleteVertexArray(name: Int) {
		arrays.remove(name)
		if (boundArray == name) {
			boundArray = 0
		}
	}

	@JvmStatic
	fun bindVertexArray(name: Int) {
		boundArray = name
	}

	/** The vertex array a draw is described by, or `null` when none is bound. */
	@JvmStatic
	fun boundVertexArray(): VertexArray? = arrays[boundArray]

	@JvmStatic
	fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: Long) {
		val attribute = Attribute(index, size, type, normalized, stride, pointer, false)
		attribute.buffer = bound[GL_ARRAY_BUFFER] ?: 0
		attributes()?.put(index, attribute)
	}

	@JvmStatic
	fun vertexAttribIPointer(index: Int, size: Int, type: Int, stride: Int, pointer: Long) {
		val attribute = Attribute(index, size, type, false, stride, pointer, true)
		attribute.buffer = bound[GL_ARRAY_BUFFER] ?: 0
		attributes()?.put(index, attribute)
	}

	@JvmStatic
	fun enableVertexAttribArray(index: Int) {
		boundVertexArray()?.enabled?.add(index)
	}

	@JvmStatic
	fun disableVertexAttribArray(index: Int) {
		boundVertexArray()?.enabled?.remove(index)
	}

	/**
	 * A pointer may be set with no vertex array bound, which GL allows and stores in the default
	 * array. Minecraft always has one bound by the time it describes a vertex format, so a stray one
	 * is dropped rather than kept in a default array nothing draws out of.
	 */
	private fun attributes(): HashMap<Int, Attribute>? = boundVertexArray()?.attributes

	// ---- teardown --------------------------------------------------------------------------------

	@JvmStatic
	fun clear() {
		for (entry in entries.values) {
			release(entry)
		}
		entries.clear()
		arrays.clear()
		bound.clear()
		boundArray = 0
	}

	private var warned = false

	private fun warnOnce(message: String) {
		if (!warned) {
			warned = true
			WgpuMcMod.LOGGER.warn("wgpu-mc: {}", message)
		}
	}
}
