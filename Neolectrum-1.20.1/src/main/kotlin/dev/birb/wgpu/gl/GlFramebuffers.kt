package dev.birb.wgpu.gl

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.rust.WmJni

/**
 * The framebuffers and renderbuffers GL thinks it has.
 *
 * wgpu has no framebuffer *object*: a render pass begins with the views it will draw into, and that
 * set is what a GL framebuffer actually is. So a name here is not an engine handle - it is a
 * description of a render pass's attachments, resolved when a draw or a clear needs one.
 *
 * A renderbuffer is different, and is a real engine object: in GL it is storage with no texture
 * identity, used for the depth buffer, and in wgpu that is a texture used only as an attachment. It
 * is created here with `RENDER_ATTACHMENT` alone, which is exactly the GL meaning.
 *
 * Name 0 is the window. It is not stored as a framebuffer because there is nothing to store: a draw
 * into it resolves to the swapchain image the frame is presenting, which is the frame's own
 * business rather than this table's.
 */
object GlFramebuffers {
    const val GL_FRAMEBUFFER = 36160
    const val GL_DRAW_FRAMEBUFFER = 36009
    const val GL_READ_FRAMEBUFFER = 36008
    const val GL_RENDERBUFFER = 36161

    const val GL_COLOR_ATTACHMENT0 = 36064
    const val GL_DEPTH_ATTACHMENT = 36096
    const val GL_STENCIL_ATTACHMENT = 36128
    const val GL_DEPTH_STENCIL_ATTACHMENT = 33306

    const val GL_FRAMEBUFFER_COMPLETE = 36053
    const val GL_FRAMEBUFFER_UNDEFINED = 36057

    /** The attachment a colour draw goes to, which is the only one Minecraft ever reads back. */
    class Framebuffer(@JvmField val name: Int) {
        /** GL attachment point -> GL texture name, from `glFramebufferTexture2D`. */
        @JvmField val textures = HashMap<Int, Int>()

        /** GL attachment point -> renderbuffer name, from `glFramebufferRenderbuffer`. */
        @JvmField val renderbuffers = HashMap<Int, Int>()

        /** `glDrawBuffers`, which Minecraft sets once per target and never changes. */
        @JvmField var drawBuffer: Int = GL_COLOR_ATTACHMENT0
    }

    /** A renderbuffer's storage, which is an engine texture that is only ever an attachment. */
    class Renderbuffer(@JvmField val name: Int) {
        @JvmField var texture: Long = 0L
        @JvmField var view: Long = 0L
        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
        @JvmField var gpuFormat: Int = GpuFormats.D24_UNORM_S8_UINT
        @JvmField var internalFormat: Int = 0
        @JvmField var defined: Boolean = false

        val exists: Boolean get() = texture != 0L
    }

    private val framebuffers = HashMap<Int, Framebuffer>()
    private val renderbuffers = HashMap<Int, Renderbuffer>()
    private var nextFramebuffer = 1
    private var nextRenderbuffer = 1

    @JvmStatic
    fun genFramebuffer(): Int {
        val name = nextFramebuffer++
        framebuffers[name] = Framebuffer(name)
        return name
    }

    @JvmStatic
    fun deleteFramebuffer(name: Int) {
        framebuffers.remove(name)
        if (GlState.boundDrawFramebuffer == name) GlState.boundDrawFramebuffer = 0
        if (GlState.boundReadFramebuffer == name) GlState.boundReadFramebuffer = 0
    }

    @JvmStatic
    fun bindFramebuffer(target: Int, name: Int) {
        when (target) {
            GL_FRAMEBUFFER -> {
                GlState.boundDrawFramebuffer = name
                GlState.boundReadFramebuffer = name
            }
            GL_DRAW_FRAMEBUFFER -> GlState.boundDrawFramebuffer = name
            GL_READ_FRAMEBUFFER -> GlState.boundReadFramebuffer = name
        }
    }

    @JvmStatic
    fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) {
        val framebuffer = framebuffers[GlState.boundDrawFramebuffer] ?: return
        if (texture == 0) {
            framebuffer.textures.remove(attachment)
        } else {
            framebuffer.textures[attachment] = texture
        }
    }

    @JvmStatic
    fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) {
        val framebuffer = framebuffers[GlState.boundDrawFramebuffer] ?: return
        if (renderbuffer == 0) {
            framebuffer.renderbuffers.remove(attachment)
        } else {
            framebuffer.renderbuffers[attachment] = renderbuffer
        }
    }

    /**
     * `glCheckFramebufferStatus`.
     *
     * Minecraft treats anything but `GL_FRAMEBUFFER_COMPLETE` as a fatal error, and this side cannot
     * fail the way GL can - the attachments are real engine textures with real usage flags - so the
     * answer is complete. It is not a stub: there is nothing left that could be incomplete.
     */
    @JvmStatic
    fun checkFramebufferStatus(target: Int): Int = GL_FRAMEBUFFER_COMPLETE

    // ---- renderbuffers ---------------------------------------------------------------------------

    @JvmStatic
    fun genRenderbuffer(): Int {
        val name = nextRenderbuffer++
        renderbuffers[name] = Renderbuffer(name)
        return name
    }

    @JvmStatic
    fun deleteRenderbuffer(name: Int) {
        val renderbuffer = renderbuffers.remove(name) ?: return
        releaseRenderbuffer(renderbuffer)
    }

    @JvmStatic
    fun bindRenderbuffer(target: Int, name: Int) {
        GlState.boundRenderbuffer = name
    }

    /**
     * `glRenderbufferStorage`: give the bound renderbuffer its storage.
     *
     * Re-storing at a different size replaces the engine texture, which is what a window resize does
     * to the main target's depth buffer.
     */
    @JvmStatic
    fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int) {
        val renderbuffer = renderbuffers[GlState.boundRenderbuffer] ?: return
        val wm = WgpuRenderer.handle()
        if (wm == 0L) return

        val gpuFormat = GpuFormats.fromGl(internalFormat, 0, 0)
        if (renderbuffer.defined && (renderbuffer.width != width || renderbuffer.height != height)) {
            releaseRenderbuffer(renderbuffer)
        }
        if (!renderbuffer.defined) {
            renderbuffer.texture = WmJni.createTexture(
                wm, gpuFormat, width, height, 1,
                GlTextures.USAGE_RENDER_ATTACHMENT, 1,
                "gl renderbuffer " + renderbuffer.name,
            )
            renderbuffer.view = WmJni.createTextureView(wm, renderbuffer.texture, 0, 0, 1)
            renderbuffer.width = width
            renderbuffer.height = height
            renderbuffer.gpuFormat = gpuFormat
            renderbuffer.defined = true
        }
        renderbuffer.internalFormat = internalFormat
    }

    private fun releaseRenderbuffer(renderbuffer: Renderbuffer) {
        if (renderbuffer.view != 0L) {
            WmJni.dropTextureView(renderbuffer.view)
            renderbuffer.view = 0L
        }
        if (renderbuffer.texture != 0L) {
            WmJni.dropTexture(renderbuffer.texture)
            renderbuffer.texture = 0L
        }
        renderbuffer.defined = false
    }

    // ---- what the current draw would render into -------------------------------------------------

    /** The texture name behind an attachment of the bound draw framebuffer, or -1 for none. */
    private fun attachmentTexture(attachment: Int): Int {
        val framebuffer = framebuffers[GlState.boundDrawFramebuffer] ?: return -1
        val texture = framebuffer.textures[attachment]
        if (texture != null && texture != 0) return texture
        val renderbuffer = framebuffer.renderbuffers[attachment]
        if (renderbuffer != null) {
            // A renderbuffer is not a name in `GlTextures`, so it is reported as itself: -1 means
            // "nothing", and a renderbuffer is never nothing.
            return -renderbuffer
        }
        return -1
    }

    /**
     * The view a colour draw into the bound framebuffer goes to, or 0 for the frame's own target.
     *
     * Zero is the window, and every caller has to treat it as "the swapchain image", because that
     * view only exists once a frame and is the presenter's to make.
     */
    @JvmStatic
    /**
     * The `GpuFormat` of the bound framebuffer's colour attachment.
     *
     * A pipeline names the format of the target it draws into, and this is the only place that knows
     * which texture that is. `GlDraws` asks for it rather than reaching into the framebuffer itself.
     */
    fun colorFormat(): Int {
        val name = attachmentTexture(GL_COLOR_ATTACHMENT0)
        if (name < 0) return GpuFormats.NONE
        return GlTextures.byName(name)?.gpuFormat ?: GpuFormats.NONE
    }

    fun colorView(): Long {
        if (GlState.boundDrawFramebuffer == 0) return windowView()
        val name = attachmentTexture(GL_COLOR_ATTACHMENT0)
        if (name > 0) return GlTextures.attachmentView(name)
        if (name < 0) return renderbuffers[-name]?.view ?: 0L
        return 0L
    }


    // ---- the window, which is framebuffer 0 ------------------------------------------------------
    //
    // 1.21.1 does **not** end a frame with a `glBlitFramebuffer`. `RenderTarget._blitToScreen` binds
    // the blit shader, points `DiffuseSampler` at the main target's colour texture and *draws* a
    // screen-sized quad with framebuffer 0 bound. So the window is a render target like any other,
    // and the frame it receives is a frame's worth of drawing that has to have somewhere to land
    // before it can be presented. This is that somewhere: one engine texture the size of the window,
    // remade when the window's size changes.

    private var windowTexture: Long = 0L
    private var windowTextureView: Long = 0L
    private var windowTextureWidth: Int = 0
    private var windowTextureHeight: Int = 0

    /**
     * The view framebuffer 0 draws into, made on first use.
     *
     * The size comes from the viewport, which is the one thing that always states the window's size
     * at the moment of a draw: `_blitToScreen` sets it from the window immediately before the quad,
     * and it is what the game's own full-window passes set everywhere else.
     */
    @JvmStatic
    fun windowView(): Long {
        val wm = WgpuRenderer.handle()
        val width = GlState.viewportWidth
        val height = GlState.viewportHeight
        if (wm == 0L || width <= 0 || height <= 0) return 0L

        if (windowTextureView == 0L || width != windowTextureWidth || height != windowTextureHeight) {
            dropWindowTarget()
            windowTexture = WmJni.createTexture(
                wm, GpuFormats.RGBA8_UNORM, width, height, 1,
                GlTextures.USAGE_TEXTURE_BINDING or GlTextures.USAGE_RENDER_ATTACHMENT, 1,
                "wgpu-mc window",
            )
            if (windowTexture == 0L) return 0L
            windowTextureView = WmJni.createTextureView(wm, windowTexture, 0, 0, 1)
            windowTextureWidth = width
            windowTextureHeight = height
            WgpuMcMod.LOGGER.info("wgpu-mc: the window target is now {}x{}", width, height)
        }

        return windowTextureView
    }

    /** Writes the window target out as RGBA8 rows, for a window that shows something unexpected. */
    @JvmStatic
    fun dumpWindowTarget(path: String): Boolean {
        val texture = windowTexture
        if (texture == 0L) return false
        return WmJni.dumpTextureRgba(WgpuRenderer.handle(), texture, path, true)
    }

    /** The window target's size, which is what a scissor and a viewport are clamped to. */
    @JvmStatic
    fun windowTargetWidth(): Int = windowTextureWidth

    @JvmStatic
    fun windowTargetHeight(): Int = windowTextureHeight

    /** Gives the window target back, view first: the view holds a handle to the texture it was made from. */
    @JvmStatic
    fun dropWindowTarget() {
        if (windowTextureView != 0L) {
            WmJni.dropTextureView(windowTextureView)
            windowTextureView = 0L
        }
        if (windowTexture != 0L) {
            WmJni.dropTexture(windowTexture)
            windowTexture = 0L
        }
        windowTextureWidth = 0
        windowTextureHeight = 0
    }

    /**
     * The view a depth test in the bound framebuffer reads, or 0 when there is none.
     *
     * Zero here is not the window: on 1.21.1 Minecraft's main target always has a depth attachment,
     * and a window with no depth buffer is a target whose depth test cannot work at all. The caller
     * decides what to do about that; this only answers where the depth is.
     */
    @JvmStatic
    fun depthView(): Long {
        if (GlState.boundDrawFramebuffer == 0) return 0L
        var name = attachmentTexture(GL_DEPTH_ATTACHMENT)
        if (name == -1) name = attachmentTexture(GL_DEPTH_STENCIL_ATTACHMENT)
        if (name > 0) return GlTextures.attachmentView(name)
        if (name < 0) return renderbuffers[-name]?.view ?: 0L
        return 0L
    }

    /** The size of what is being drawn into, for a viewport or a scissor rectangle. */
    @JvmStatic
    fun targetWidth(): Int {
        if (GlState.boundDrawFramebuffer == 0) return if (windowTextureWidth != 0) windowTextureWidth else GlState.viewportWidth
        val name = attachmentTexture(GL_COLOR_ATTACHMENT0)
        if (name > 0) return GlTextures.byName(name)?.width ?: 0
        if (name < 0) return renderbuffers[-name]?.width ?: 0
        return 0
    }

    @JvmStatic
    fun targetHeight(): Int {
        if (GlState.boundDrawFramebuffer == 0) return if (windowTextureHeight != 0) windowTextureHeight else GlState.viewportHeight
        val name = attachmentTexture(GL_COLOR_ATTACHMENT0)
        if (name > 0) return GlTextures.byName(name)?.height ?: 0
        if (name < 0) return renderbuffers[-name]?.height ?: 0
        return 0
    }

    // ---- what a clear and a blit do ------------------------------------------------------------------

    /** GL's `GL_COLOR_BUFFER_BIT`, `GL_DEPTH_BUFFER_BIT` and `GL_STENCIL_BUFFER_BIT`. */
    const val GL_COLOR_BUFFER_BIT = 16384
    const val GL_DEPTH_BUFFER_BIT = 256
    const val GL_STENCIL_BUFFER_BIT = 1024

    private var windowClears = 0

    /** How many times `glBlitFramebuffer` has been called at all, which is what the frame boundary is. */
    private var blitCalls = 0L

    /** Blits into the window whose source could not be resolved, so a frame that never arrived can say why. */
    private var blitMisses = 0L

    private fun colourTexture(framebuffer: Framebuffer): Long {
        val texture = framebuffer.textures[GL_COLOR_ATTACHMENT0]
        if (texture != null && texture != 0) return GlTextures.byName(texture)?.texture ?: 0L
        val renderbuffer = framebuffer.renderbuffers[GL_COLOR_ATTACHMENT0]
        if (renderbuffer != null) return renderbuffers[renderbuffer]?.texture ?: 0L
        return 0L
    }

    private fun depthTexture(framebuffer: Framebuffer): Long {
        var renderbuffer = framebuffer.renderbuffers[GL_DEPTH_ATTACHMENT]
        if (renderbuffer == null) renderbuffer = framebuffer.renderbuffers[GL_DEPTH_STENCIL_ATTACHMENT]
        if (renderbuffer != null) return renderbuffers[renderbuffer]?.texture ?: 0L
        var texture = framebuffer.textures[GL_DEPTH_ATTACHMENT]
        if (texture == null) texture = framebuffer.textures[GL_DEPTH_STENCIL_ATTACHMENT]
        if (texture != null && texture != 0) return GlTextures.byName(texture)?.texture ?: 0L
        return 0L
    }

    /** The clear colour packed the way the engine reads it: alpha in the high byte, then red. */
    private fun packedClearColour(): Int {
        fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        return (channel(GlState.clearAlpha) shl 24) or (channel(GlState.clearRed) shl 16) or
            (channel(GlState.clearGreen) shl 8) or channel(GlState.clearBlue)
    }

    /**
     * `glClear`, aimed at whatever is bound.
     *
     * A clear into a framebuffer is a real engine clear: the attachment is a texture this side made,
     * and the engine encodes the load op itself. A clear into the window is not, and cannot be -
     * the window's target is the swapchain image the frame acquires when it presents, so there is
     * nothing to clear at this point in the frame. It is counted instead, and the frame's own pass
     * is where it would be honoured.
     */
    @JvmStatic
    fun clearCurrent(mask: Int) {
        val wm = WgpuRenderer.handle()
        if (wm == 0L) return
        if (GlState.boundDrawFramebuffer == 0) {
            windowClears++
            if (windowClears == 1) {
                dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                    "wgpu-mc: the game cleared the window itself (mask {}); the frame's own pass owns that target, so it is counted rather than encoded",
                    mask,
                )
            }
            return
        }
        val framebuffer = framebuffers[GlState.boundDrawFramebuffer] ?: return

        // The clear goes into the frame's own encoder, and is not submitted from here. A world clears
        // its render targets many times a frame, and each of these used to be its own encoder and its
        // own submission - dozens a frame, each with a command buffer and a fence the engine builds
        // and this side never sees. `GlDraws.endFrame` submits the frame once, at the present.
        val encoder = GlDraws.frameEncoder(wm)
        if (encoder == 0L) return

        if (mask and GL_COLOR_BUFFER_BIT != 0) {
            val colour = colourTexture(framebuffer)
            if (colour != 0L) {
                WmJni.clearColorTexture(encoder, colour, packedClearColour())
            }
        }
        if (mask and GL_DEPTH_BUFFER_BIT != 0) {
            val depth = depthTexture(framebuffer)
            if (depth != 0L) {
                WmJni.clearDepthTexture(encoder, depth, GlState.clearDepth)
            }
        }
    }

    /**
     * `glBlitFramebuffer`: copy one framebuffer's colour into another's.
     *
     * Minecraft uses it to put its main target on the screen, and that is the one case that cannot be
     * a texture-to-texture copy - the destination is the swapchain image the frame has not acquired
     * yet. So the two are different operations and both live here: a blit between two engine textures
     * is a copy, and a blit into framebuffer 0 is the frame's present, which `GlPresent` owns.
     */
    @JvmStatic
    fun blitFramebuffer(
        srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int,
        dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int,
        mask: Int, filter: Int,
    ) {
        val wm = WgpuRenderer.handle()
        if (wm == 0L) return

        // This call is the frame boundary, so the first one is worth naming: which framebuffer a frame is
        // read from, and whether it is going to the window (0) or to another render target, is the whole
        // difference between a present and a copy, and nothing else says it.
        if (blitCalls++ == 0L) {
            dev.birb.wgpu.WgpuMcMod.LOGGER.info(
                "wgpu-mc: the first blit reads framebuffer {} and draws into {} (0 is the window), mask 0x{}",
                GlState.boundReadFramebuffer,
                GlState.boundDrawFramebuffer,
                mask.toString(16),
            )
        }

        // A blit reads a texture that the draws before it wrote. Those draws must therefore be
        // submitted before the window is presented, and that is the one place a flush is needed here:
        // the present below reads the colour attachment, and `GlPresent` presents it in a submission
        // of its own.
        //
        // **A framebuffer-to-framebuffer copy is not a reason to submit, and used to be one.** It
        // went into an encoder of its own and was submitted from here, which made every copy a
        // submission - and a world copies between its render targets dozens of times a frame, so a
        // frame left as dozens of submissions, each with a command buffer and a fence that no
        // `live resources` counter can see. The encoder is a command stream: a copy recorded into it
        // is ordered after the draws already in it, which is the whole of what the separate encoder
        // was bought for. It costs one submission a frame instead, at the present - measured as ~82
        // submissions a frame before this and the clear change beside it, against a design whose
        // own doc says the frame's boundary is one submit.
        val width = (srcX1 - srcX0).coerceAtLeast(0)
        val height = (srcY1 - srcY0).coerceAtLeast(0)
        if (width == 0 || height == 0) return

        if (GlState.boundDrawFramebuffer == 0) {
            GlDraws.endFrame(wm, "blit-to-window")
            // Only the colour attachment reaches the window: `blitToScreen` is a colour copy, and a
            // swapchain image has no depth to copy into. This is the frame's present.
            val presentedFrom = framebuffers[GlState.boundReadFramebuffer]
            val presentedName = presentedFrom?.textures?.get(GL_COLOR_ATTACHMENT0)
            val presented = presentedName?.let { GlTextures.byName(it) }
            if (presented == null) {
                // A present that does not happen has to say so. The frame is finished either way, and
                // "the window never changed" is what all three of these misses look like from outside.
                if (blitMisses++ == 0L) {
                    dev.birb.wgpu.WgpuMcMod.LOGGER.warn(
                        "wgpu-mc: a blit into the window could not be presented - framebuffer {} is {}, its " +
                            "colour attachment is {}, and that texture is {}. All three have to name something " +
                            "this side holds, or the frame never reaches the screen.",
                        GlState.boundReadFramebuffer,
                        if (presentedFrom == null) "not one this side made" else "known",
                        presentedName?.toString() ?: "not recorded",
                        if (presentedName == null) "-" else "not one this side holds",
                    )
                }
                return
            }

            GlPresent.present(presented.view, presented.width, presented.height)
            return
        }

        val source = framebuffers[GlState.boundReadFramebuffer] ?: return
        val destination = framebuffers[GlState.boundDrawFramebuffer] ?: return
        val from = colourTexture(source)
        val to = colourTexture(destination)
        if (from == 0L || to == 0L) return

        // Recorded into the frame's own encoder, not an encoder of its own. The draws that wrote
        // `from` are already in that stream, so the copy is ordered after them by construction - which
        // is the whole of what the separate encoder was bought for - and it costs no submission.
        // A world copies between its render targets dozens of times a frame, and each of those was a
        // full submission with a command buffer and a fence the engine builds and no counter here can
        // see. See `GlDraws.frameEncoder`.
        val encoder = GlDraws.frameEncoder(wm)
        if (encoder == 0L) return
        WmJni.copyTextureToTexture(encoder, from, to, 0, dstX0, dstY0, srcX0, srcY0, width, height)
    }

    /** The framebuffer a draw would go into, or null when the draw goes to the window. */
    @JvmStatic
    fun boundDraw(): Framebuffer? = framebuffers[GlState.boundDrawFramebuffer]
    @JvmStatic
    fun clear() {
        for (renderbuffer in renderbuffers.values) releaseRenderbuffer(renderbuffer)
        renderbuffers.clear()
        framebuffers.clear()
    }
}
