package dev.birb.wgpu.gl

import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The program half of the GL seam: shader and program objects, attribute locations and uniform
 * values.
 *
 * There is no GL context to hold any of this, and there cannot be a compiler on this side either -
 * the engine compiles the GLSL, with naga behind it, when a pipeline is first used. So this object
 * is a *record*: it keeps every shader's source, every program's attachment list, the attribute
 * locations the game asked for and the uniform values the game uploaded, and it is the draw path
 * (`GlDraws`) that turns the record into a pipeline, a bind group and a uniform buffer.
 *
 * Two GL behaviours are load-bearing and are reproduced deliberately:
 *
 * - **A location is per program, and asking twice gives the same answer.** `_glGetUniformLocation`
 *   is the only way the game learns where a uniform lives, and `Uniform` in `com.mojang.blaze3d`
 *   stores the answer and uploads through it for the rest of the program's life. GL's answer is a
 *   number assigned at link time; ours is assigned on first ask, in ask order, and never changes.
 * - **`Foo` and `Foo[0]` are one uniform.** The game asks for both spellings - `Uniform` strips the
 *   suffix itself for an array's first element in some paths and does not in others - and GL treats
 *   them as the same location, so interning has to strip it too. Two locations for one uniform
 *   would mean the value uploaded through one is invisible through the other.
 *
 * Nothing here talks to the engine. What the engine needs is in [vertexSource], [fragmentSource],
 * [declarations] and [uniforms].
 */
object GlPrograms {
	const val GL_FRAGMENT_SHADER = 35632
	const val GL_VERTEX_SHADER = 35633
	const val GL_COMPILE_STATUS = 35713
	const val GL_LINK_STATUS = 35714

	/** A `glCreateShader` object: a type and, once `glShaderSource` has run, the source. */
	class Shader(val name: Int, val type: Int) {
		var source: String = ""
		var compiled: Boolean = false

		/** Whether [source] has a `main`, which is the only thing that can be checked here. */
		val hasMain: Boolean get() = source.contains("void main")
	}

	/**
	 * One uniform of one program, as the game treats it: a location to upload through and the
	 * values last uploaded. The GLSL type is read out of the source rather than remembered from the
	 * upload, because the upload says how many floats arrived, not what they mean.
	 */
	class Uniform(val name: String, val location: Int, val declaredType: String) {
		var floats: FloatArray = FloatArray(16)
		var ints: IntArray = IntArray(4)

		/** The value of a `sampler2D` is a texture unit, not a texture. */
		val sampler: Boolean get() = isSampler(declaredType)

		val arrayLength: Int get() = ARRAY_SUFFIX.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

		override fun toString(): String = "$declaredType $name @$location"
	}

	/** A `glCreateProgram` object. */
	class Program(val name: Int) {
		val shaders: MutableList<Int> = ArrayList()

		/**
		 * The source of every shader this program has attached, keyed by shader name.
		 *
		 * Held here instead of read back out of `shaders` when a pipeline is built, because GL lets
		 * a shader object be deleted while the program that has it stays linked and drawable - and
		 * every shader loader does exactly that once linking has succeeded, Minecraft's included.
		 * A program outliving its shader objects is the normal case rather than an edge one, so the
		 * source has to belong to the program and not to the name it arrived under.
		 */
		val sources: MutableMap<Int, String> = LinkedHashMap()

		var vertexShader: Int = 0
		var fragmentShader: Int = 0
		var linked: Boolean = false
		var log: String = ""

		/** Uniforms in location order, so a location indexes straight into this. */
		val byLocation: MutableList<Uniform> = ArrayList()
		val byName: MutableMap<String, Uniform> = LinkedHashMap()

		/** Attribute name to location, in the order the game asked. */
		val attributes: MutableMap<String, Int> = LinkedHashMap()

		/** `glBindAttribLocation`, which the game is allowed to call before linking. */
		val explicitAttributes: MutableMap<String, Int> = LinkedHashMap()
	}

	private val shaders: MutableMap<Int, Shader> = LinkedHashMap()
	private val programs: MutableMap<Int, Program> = LinkedHashMap()
	private var nextShader = 1
	private var nextProgram = 1

	var currentProgram: Int = 0
		private set

	/**
	 * How many uniform uploads have happened, ever.
	 *
	 * A uniform lives in an engine buffer that this side writes, and the engine is told about the
	 * write rather than asked for the value at draw time - so a draw has to know whether anything it
	 * would upload has moved. This counter is that answer: a program's buffer is rewritten when this
	 * is not the number it was last written at, and left alone otherwise. It is one `long` against a
	 * `Queue::write_buffer` per draw.
	 */
	var uniformEpoch: Long = 0
		private set

	// ---------------------------------------------------------------- shaders

	@JvmStatic
	fun genShader(type: Int): Int {
		val name = nextShader++
		shaders[name] = Shader(name, type)
		return name
	}

	@JvmStatic
	fun shaderSource(name: Int, source: List<String>) {
		val shader = shaders[name] ?: return
		// GL takes a list of strings and concatenates them. The game preprocesses a shader into
		// several pieces - `#moj_import` and the preprocessor's own substitutions - and each piece
		// is a line, so joining with a newline is the concatenation GL would have done.
		shader.source = source.joinToString("\n")
		shader.compiled = false
		// A program that already has this shader attached keeps its own copy, and GL allows the
		// source to be set after the attach as well as before it - so it is pushed out here rather
		// than only read at link time, which would lose a source set in the other order.
		for (program in programs.values) {
			if (program.shaders.contains(name)) program.sources[name] = shader.source
		}
	}

	@JvmStatic
	fun compileShader(name: Int) {
		// There is nothing to compile against: the engine compiles the GLSL when the pipeline is
		// built, and its compiler is not reachable from here. Marking the shader compiled is
		// honest in the way that matters - `glGetShaderi(GL_COMPILE_STATUS)` is what the game
		// branches on, and the game's own use of it is to log the info log and continue or throw.
		// A shader that really does not compile will fail at the pipeline, and the engine says so
		// with the driver's own message.
		shaders[name]?.compiled = true
	}

	@JvmStatic
	fun getShaderi(name: Int, parameter: Int): Int = when (parameter) {
		GL_COMPILE_STATUS -> if (shaders[name]?.compiled == true) 1 else 0
		else -> 0
	}

	@JvmStatic
	fun shaderInfoLog(name: Int, maxLength: Int): String = ""

	@JvmStatic
	fun deleteShader(name: Int) {
		shaders.remove(name)
		// A deleted shader stays attached to the program that has it, exactly as GL specifies: the
		// name goes away, the attachment does not. The source goes with the program - see
		// `Program.sources` - and it has to, because this removal is what every shader loader does
		// once linking has succeeded. Forgetting it here is what left the blit unable to build a
		// pipeline: the blit's shader objects were deleted after they linked, `vertexSource` started
		// answering null, and the draw was dropped before it reached the engine.
	}

	// --------------------------------------------------------------- programs

	@JvmStatic
	fun createProgram(): Int {
		val name = nextProgram++
		programs[name] = Program(name)
		return name
	}

	@JvmStatic
	fun attachShader(program: Int, shader: Int) {
		val p = programs[program] ?: return
		if (p.shaders.contains(shader)) return
		p.shaders.add(shader)
		// The source may have been given before the attach, which is the order the game uses, so it
		// is taken across here as well as in `shaderSource`.
		shaders[shader]?.let { p.sources[shader] = it.source }
		when (shaders[shader]?.type) {
			GL_VERTEX_SHADER -> p.vertexShader = shader
			GL_FRAGMENT_SHADER -> p.fragmentShader = shader
		}
	}

	@JvmStatic
	fun linkProgram(program: Int) {
		val p = programs[program] ?: return
		// Linking is the last moment GL guarantees the sources are still reachable, and it is the
		// moment after which a loader deletes them - so both stages are copied across here as well.
		p.sources[p.vertexShader] = shaders[p.vertexShader]?.source ?: p.sources[p.vertexShader] ?: ""
		p.sources[p.fragmentShader] = shaders[p.fragmentShader]?.source ?: p.sources[p.fragmentShader] ?: ""
		// `glBindAttribLocation` before linking is the only way to pin an attribute, and GL keeps
		// those bindings in preference to whatever the linker would have chosen.
		for ((attribute, location) in p.explicitAttributes) p.attributes[attribute] = location
		p.linked = true
	}

	@JvmStatic
	fun getProgrami(program: Int, parameter: Int): Int = when (parameter) {
		GL_LINK_STATUS -> if (programs[program]?.linked == true) 1 else 0
		else -> 0
	}

	@JvmStatic
	fun programInfoLog(program: Int, maxLength: Int): String = programs[program]?.log ?: ""

	@JvmStatic
	fun deleteProgram(program: Int) {
		if (currentProgram == program) currentProgram = 0
		programs.remove(program)
	}

	@JvmStatic
	fun useProgram(program: Int) {
		currentProgram = program
	}

	// ------------------------------------------------------------- attributes

	/**
	 * The location an attribute is bound at.
	 *
	 * The engine has no location field of its own - it numbers the elements of the vertex format it
	 * is given, in order, and the shader has to agree. So the number handed to the game here is
	 * what the draw path will number the vertex format with, which is why it must be dense and
	 * stable: 0, 1, 2 ... in the order the game asks, which is the order of the vertex format's own
	 * elements. [GlDraws] sorts the bound vertex array's attributes by this number and emits one
	 * element per attribute, so the engine's numbering and this one are the same numbering.
	 */
	@JvmStatic
	fun getAttribLocation(program: Int, attribute: CharSequence): Int {
		val p = programs[program] ?: return -1
		val name = attribute.toString()
		p.explicitAttributes[name]?.let { return it }
		p.attributes[name]?.let { return it }
		val location = p.attributes.size
		p.attributes[name] = location
		return location
	}

	@JvmStatic
	fun bindAttribLocation(program: Int, location: Int, attribute: CharSequence) {
		programs[program]?.explicitAttributes?.put(attribute.toString(), location)
	}

	// --------------------------------------------------------------- uniforms

	/**
	 * The location of a uniform, assigned on first ask and never moved.
	 *
	 * GL answers `-1` for a name the program does not have, and the game relies on that in places -
	 * `Uniform` uploads through whatever it was given, and `glUniform*` at `-1` is a documented
	 * no-op, but `ShaderInstance` also tests the location directly. A location is therefore only
	 * handed out for a name the program's own source declares; anything else gets `-1`.
	 */
	@JvmStatic
	fun getUniformLocation(program: Int, name: CharSequence): Int {
		val p = programs[program] ?: return -1
		val asked = name.toString()
		val base = ARRAY_SUFFIX.replace(asked, "")
		p.byName[base]?.let { return it.location }
		val declared = declarations(sourceOf(program))
		val type = declared[base] ?: return -1
		val uniform = Uniform(base, p.byLocation.size, type)
		p.byLocation.add(uniform)
		p.byName[base] = uniform
		return uniform.location
	}

	@JvmStatic
	fun uniformInt(location: Int, value: Int) = upload(location) { it.ints[0] = value }

	@JvmStatic
	fun uniformInts(location: Int, buffer: IntBuffer) = upload(location) { uniform ->
		val values = copyInts(buffer, uniform.ints.size)
		for (i in values.indices) uniform.ints[i] = values[i]
	}

	@JvmStatic
	fun uniformFloats(location: Int, buffer: FloatBuffer) = upload(location) { uniform ->
		val values = copyFloats(buffer, uniform.floats.size)
		for (i in values.indices) uniform.floats[i] = values[i]
	}

	@JvmStatic
	fun uniformMatrix(location: Int, columns: Int, transpose: Boolean, buffer: FloatBuffer) =
		upload(location) { uniform ->
			// GL's `transpose` is only meaningful for a matrix given row by row, and the game never
			// asks for it: `Uniform` uploads with `false` and stores its matrices the way the engine
			// wants them. Kept in the signature so a call that does ask is visible rather than
			// silently dropped.
			val values = copyFloats(buffer, columns * columns)
			for (i in values.indices) uniform.floats[i] = values[i]
		}

	private inline fun upload(location: Int, change: (Uniform) -> Unit) {
		val p = programs[currentProgram] ?: return
		if (location < 0 || location >= p.byLocation.size) return
		change(p.byLocation[location])
		uniformEpoch++
	}

	// ------------------------------------------------------- what the draw path reads

	fun program(name: Int): Program? = programs[name]

	/**
	 * The source of a program's stage, or null when the stage is missing.
	 *
	 * The program's own copy is asked for first: by the time a draw arrives, the shader object the
	 * source came in under is usually already deleted, and a program that answered null here would
	 * be dropped by the draw path without a word.
	 */
	fun vertexSource(name: Int): String? =
		programs[name]?.let { it.sources[it.vertexShader] ?: shaders[it.vertexShader]?.source }

	fun fragmentSource(name: Int): String? =
		programs[name]?.let { it.sources[it.fragmentShader] ?: shaders[it.fragmentShader]?.source }

	/**
	 * Both stages' GLSL, which is where a name is looked for.
	 *
	 * A uniform belongs to the program rather than to one stage, and the game declares its uniforms
	 * on whichever stage reads them - so a name that is not in the vertex source has to be looked
	 * for in the fragment source, and asking only one of them reports a live uniform as missing.
	 */
	private fun sourceOf(program: Int): String =
		listOfNotNull(vertexSource(program), fragmentSource(program)).joinToString("\n")

	/** Every uniform the program has, in location order. */
	fun uniforms(name: Int): List<Uniform> = programs[name]?.byLocation ?: emptyList()

	/**
	 * The GLSL type of every uniform a source declares, by name.
	 *
	 * The regex reads a declaration the way GLSL writes one - the type first, the name second - so
	 * the map is keyed by the *second* group. Keying it by the first would leave every lookup by
	 * name answering nothing, which is a location of `-1` for every uniform the game has and a draw
	 * with no uniforms bound at all.
	 */
	fun declarations(source: String): Map<String, String> {
		val found = LinkedHashMap<String, String>()
		for (match in UNIFORM.findAll(withoutComments(source))) {
			found[match.groupValues[2]] = match.groupValues[1]
		}
		return found
	}

	fun isSampler(type: String): Boolean = type.startsWith("sampler") || type.startsWith("isampler") ||
		type.startsWith("usampler")

	// ------------------------------------------------------------------ helpers

	/**
	 * A source with its comments removed.
	 *
	 * Half of the declarations in a shader are commented out - the game's own shaders carry blocks
	 * of variants behind `//` and `/* */` - and a declaration found inside one would be a uniform
	 * the program does not have, which is a location handed out for something nothing will ever
	 * read.
	 */
	fun withoutComments(source: String): String {
		val out = StringBuilder(source.length)
		var i = 0
		while (i < source.length) {
			val c = source[i]
			val next = if (i + 1 < source.length) source[i + 1] else ' '
			if (c == '/' && next == '/') {
				while (i < source.length && source[i] != '\n') i++
			} else if (c == '/' && next == '*') {
				i += 2
				while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
				i += 2
			} else {
				out.append(c)
				i++
			}
		}
		return out.toString()
	}

	/*
	 * A uniform upload copies at most as much as the buffer holds and as much as the destination
	 * has room for, and copies nothing at all rather than padding when the buffer is of the other
	 * kind. Writing the remainder as zeroes would be a change GL does not make: `glUniform3fv`
	 * leaves the fourth component of a vec4 alone.
	 */
	private fun copyInts(buffer: java.nio.Buffer, size: Int): IntArray {
		val duplicate = (buffer as? IntBuffer)?.duplicate() ?: return IntArray(0)
		val count = minOf(size, duplicate.remaining())
		val out = IntArray(count)
		var i = 0
		while (i < count) {
			out[i] = duplicate.get()
			i++
		}
		return out
	}

	private fun copyFloats(buffer: java.nio.Buffer, size: Int): FloatArray {
		val duplicate = (buffer as? FloatBuffer)?.duplicate() ?: return FloatArray(0)
		val count = minOf(size, duplicate.remaining())
		val out = FloatArray(count)
		var i = 0
		while (i < count) {
			out[i] = duplicate.get()
			i++
		}
		return out
	}

	/** `uniform highp sampler2D Sampler0;` - group 1 is the type, group 2 the name. */
	private val UNIFORM = Regex(
		"""(?:^|[\s;{}])uniform\s+(?:(?:lowp|mediump|highp)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[\s*\d+\s*\])?\s*;""",
		RegexOption.MULTILINE,
	)

	private val ARRAY_SUFFIX = Regex("""\[\s*\d*\s*]\s*$""")

	@JvmStatic
	fun clear() {
		shaders.clear()
		programs.clear()
		nextShader = 1
		nextProgram = 1
		currentProgram = 0
	}
}
