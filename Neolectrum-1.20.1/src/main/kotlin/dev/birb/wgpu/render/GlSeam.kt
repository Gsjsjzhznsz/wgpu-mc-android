package dev.birb.wgpu.render

/**
 * The one switch that decides whether this branch's window has a GL context.
 *
 * It lives in its own object because two mixins have to agree about it - the window one, which
 * decides how the window is created, and the [dev.birb.wgpu.mixin.core.RenderSystemMixin] one, which
 * decides whether the game's GL calls are allowed to reach a driver at all - and a branch where those
 * two disagree is a branch that fails in a way neither of them can explain.
 *
 * The property is `-Dwgpu_mc.no_gl_window`, which the build script sets from
 * `-Pwgpu_mc_no_gl_window`; see `WindowMixin` for why the window has to lose its context before a
 * wgpu surface can exist on it.
 *
 * **It defaults to on, because a window that has a GL context is one this renderer can never present
 * to** - the wgpu surface is created against that same window, and it needs the window to have no GL
 * context for that to be possible. Off is therefore not a mode the renderer runs in: it is a window
 * where every redirect in the seam still fires, but nothing can ever be drawn - and a launch that
 * reaches the seam before the native library is loaded dies with an `UnsatisfiedLinkError` during
 * "Initializing game". The default is the mode that works; the property stays as an escape hatch for
 * anyone bisecting the seam itself.
 */
object GlSeam {

    const val NO_GL_WINDOW_PROPERTY = "wgpu_mc.no_gl_window"

    /** True when the game's window is created with `GLFW_NO_API` and GL is therefore not available. */
    @JvmStatic
    val isGlWindowDisabled: Boolean
        get() = java.lang.Boolean.parseBoolean(System.getProperty(NO_GL_WINDOW_PROPERTY, "true"))
}