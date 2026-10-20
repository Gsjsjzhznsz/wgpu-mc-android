package dev.birb.wgpu.gl

/**
 * The GL state Minecraft believes it has set.
 *
 * On 1.21.1 Blaze3D *is* OpenGL, so the only way a wgpu renderer can answer Minecraft's draw path
 * is to take GL away from it and replay what it asked for. This object is the half of that replay
 * which is pure bookkeeping: Minecraft has already cached most of its own state in
 * `GlStateManager`'s inner classes, and what it has *not* cached - the blend equation, the depth
 * function, the bound objects - is recorded here as the calls arrive.
 *
 * Nothing in here talks to the engine. A draw reads it, which is the point: GL's setters are cheap
 * and happen thousands of times a frame, while a wgpu pipeline is a thing to be found or built, so
 * the two are kept apart and only the draw pays for the state it actually uses.
 *
 * The values are GL enums (`GL_LEQUAL`, `GL_SRC_ALPHA`, ...) rather than anything of this project's,
 * because every one of them is a number Minecraft passed in and their meaning is whatever `GL11`
 * says it is.
 */
object GlState {
    // ---- GL enums this layer has to know by name -------------------------------------------------
    const val GL_TEXTURE_2D = 3553
    const val GL_FRAMEBUFFER = 36160
    const val GL_RENDERBUFFER = 36161
    const val GL_ARRAY_BUFFER = 34962
    const val GL_ELEMENT_ARRAY_BUFFER = 34963

    const val GL_DEPTH_TEST = 2929
    const val GL_BLEND = 3042
    const val GL_CULL_FACE = 2884
    const val GL_SCISSOR_TEST = 3089
    const val GL_POLYGON_OFFSET_FILL = 32823
    const val GL_COLOR_LOGIC_OP = 3058
    const val GL_STENCIL_TEST = 2960

    const val GL_NEVER = 512
    const val GL_LESS = 513
    const val GL_EQUAL = 514
    const val GL_LEQUAL = 515
    const val GL_GREATER = 516
    const val GL_NOTEQUAL = 517
    const val GL_GEQUAL = 518
    const val GL_ALWAYS = 519

    const val GL_ZERO = 0
    const val GL_ONE = 1
    const val GL_SRC_COLOR = 768
    const val GL_ONE_MINUS_SRC_COLOR = 769
    const val GL_SRC_ALPHA = 770
    const val GL_ONE_MINUS_SRC_ALPHA = 771
    const val GL_DST_ALPHA = 772
    const val GL_ONE_MINUS_DST_ALPHA = 773
    const val GL_DST_COLOR = 774
    const val GL_ONE_MINUS_DST_COLOR = 775

    const val GL_FUNC_ADD = 32774
    const val GL_FUNC_SUBTRACT = 32778
    const val GL_FUNC_REVERSE_SUBTRACT = 32779
    const val GL_MIN = 32775
    const val GL_MAX = 32776

    const val GL_BACK = 1029
    const val GL_FRONT = 1028
    const val GL_FRONT_AND_BACK = 1032
    const val GL_CW = 2304
    const val GL_CCW = 2305

    const val GL_VIEWPORT = 2978

    // ---- the state itself ------------------------------------------------------------------------

    /** Which texture unit is selected, as a zero-based index (GL's `GL_TEXTURE0` is 0 here). */
    @JvmField var activeTexture: Int = 0

    /**
     * The texture bound to `GL_TEXTURE_2D` on each unit, by GL name. -1 is "nothing".
     *
     * Filled rather than built by a lambda, and that is worth the two extra characters on the front
     * of it: `IntArray(n) { -1 }` compiles the fill into a *loop inside this object's static
     * initialiser*, and a loop in a `<clinit>` is the one construct here that a stale or half-written
     * class file can be verifier-broken in while every other method still checks out. It cost an
     * afternoon: `java.lang.VerifyError: Bad local variable type ... GlState.<clinit>()V @15`, thrown
     * while the frame's first texture was deleted, from a class file that `javap` read back as
     * correct. `Arrays.fill` is one call and nothing to get wrong.
     */
    @JvmField val boundTextures = IntArray(MAX_TEXTURE_UNITS).also { it.fill(-1) }

    // There is deliberately no `boundBuffers` here. There was one, and nothing ever wrote it while
    // `glVertexAttribPointer` read it - so every attribute was recorded against buffer 0, the draw
    // carried no vertex buffer at all, and the pipeline (which does not need the buffer) compiled
    // happily. The live binding map is `GlBuffers`' own `bound`, written by `GlBuffers.bindBuffer`
    // and read through `GlBuffers.boundBuffer(target)`; a second copy of GL state is a second answer.

    /** The vertex array object in use, by GL name; 0 is the default one. */
    @JvmField var boundVertexArray: Int = 0

    /** The framebuffer bound for reading and for drawing, by GL name; 0 is the window. */
    @JvmField var boundDrawFramebuffer: Int = 0
    @JvmField var boundReadFramebuffer: Int = 0

    /** The renderbuffer bound to `GL_RENDERBUFFER`, by GL name. */
    @JvmField var boundRenderbuffer: Int = 0

    /** The program in use, by GL name; 0 is "none". */
    @JvmField var currentProgram: Int = 0

    @JvmField val enabled = HashSet<Int>()

    @JvmField var depthFunc: Int = GL_LEQUAL
    @JvmField var depthMask: Boolean = true
    @JvmField var depthNear: Double = 0.0
    @JvmField var depthFar: Double = 1.0

    @JvmField var blendSrcRgb: Int = GL_ONE
    @JvmField var blendDstRgb: Int = GL_ZERO
    @JvmField var blendSrcAlpha: Int = GL_ONE
    @JvmField var blendDstAlpha: Int = GL_ZERO
    @JvmField var blendEquationRgb: Int = GL_FUNC_ADD
    @JvmField var blendEquationAlpha: Int = GL_FUNC_ADD

    @JvmField var cullFace: Int = GL_BACK
    @JvmField var frontFace: Int = GL_CCW

    @JvmField var colorMask: Int = 0xF

    @JvmField var clearRed: Float = 0f
    @JvmField var clearGreen: Float = 0f
    @JvmField var clearBlue: Float = 0f
    @JvmField var clearAlpha: Float = 0f
    @JvmField var clearDepth: Double = 1.0
    @JvmField var clearStencil: Int = 0

    @JvmField var scissorX: Int = 0
    @JvmField var scissorY: Int = 0
    @JvmField var scissorWidth: Int = 0
    @JvmField var scissorHeight: Int = 0

    @JvmField var viewportX: Int = 0
    @JvmField var viewportY: Int = 0
    @JvmField var viewportWidth: Int = 0
    @JvmField var viewportHeight: Int = 0

    @JvmField var polygonOffsetFactor: Float = 0f
    @JvmField var polygonOffsetUnits: Float = 0f
    @JvmField var logicOp: Int = 5379

    @JvmField var stencilFunc: Int = GL_ALWAYS
    @JvmField var stencilReference: Int = 0
    @JvmField var stencilCompareMask: Int = -1
    @JvmField var stencilWriteMask: Int = -1
    @JvmField var stencilFailOp: Int = 7680
    @JvmField var stencilDepthFailOp: Int = 7680
    @JvmField var stencilPassOp: Int = 7680
    /** `glPixelStorei`'s unpack state, which this side has to honour itself when it uploads. */
    @JvmField var unpackRowLength: Int = 0
    @JvmField var unpackSkipPixels: Int = 0
    @JvmField var unpackSkipRows: Int = 0
    @JvmField var unpackAlignment: Int = 4

    /**
     * The units GL is allowed to have here.
     *
     * It is GL 3.3's guaranteed minimum and not a guess about the driver: Minecraft's own
     * `GlStateManager` keeps a `TextureState` per unit in a fixed array, and this side has to be
     * able to hold every unit that array can name.
     */
    const val MAX_TEXTURE_UNITS = 32

    @JvmStatic fun isEnabled(capability: Int): Boolean = enabled.contains(capability)

    @JvmStatic fun setEnabled(capability: Int, value: Boolean) {
        if (value) enabled.add(capability) else enabled.remove(capability)
    }

    /** The texture bound to the unit Minecraft has selected - the one a draw would sample. */
    @JvmStatic fun boundTexture(): Int {
        if (activeTexture < 0 || activeTexture >= boundTextures.size) return -1
        return boundTextures[activeTexture]
    }

    @JvmStatic fun bindTexture(name: Int) {
        if (activeTexture in 0 until boundTextures.size) {
            boundTextures[activeTexture] = name
        }
    }

    /**
     * Forgets a texture this side had bound on every unit.
     *
     * It is the counterpart of a delete, and it exists because the two binding tables are separate:
     * `GlStateManager._deleteTexture` clears *its* copy of the bindings after the GL call, and a
     * unit here still naming a deleted texture would make the next `_bindTexture` of that name a
     * silent no-op.
     */
    @JvmStatic fun forgetTexture(name: Int) {
        for (unit in boundTextures.indices) {
            if (boundTextures[unit] == name) boundTextures[unit] = -1
        }
    }
}