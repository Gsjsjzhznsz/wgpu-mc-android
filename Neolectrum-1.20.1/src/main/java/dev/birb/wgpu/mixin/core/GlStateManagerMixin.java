package dev.birb.wgpu.mixin.core;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.birb.wgpu.backend.WgpuRenderer;
import dev.birb.wgpu.gl.GlBuffers;
import dev.birb.wgpu.gl.GlDraws;
import dev.birb.wgpu.gl.GlFramebuffers;
import dev.birb.wgpu.gl.GlState;
import dev.birb.wgpu.gl.GlPrograms;
import dev.birb.wgpu.gl.GlTextures;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The GL seam: every driver call Minecraft makes, answered by this renderer instead.
 *
 * <h2>Why this is a redirect and not a rewrite of {@code GlStateManager}</h2>
 *
 * <p>1.21.1's Blaze3D is OpenGL - there is no backend interface to implement, so the renderer has to
 * take GL away from the game and answer it. The obvious shape for that is to overwrite
 * {@code GlStateManager}, and it is the wrong one: most of its methods are not driver calls but
 * <em>bookkeeping around</em> them. {@code _bindTexture} skips the bind when the unit already has
 * that texture; {@code _deleteTexture} clears its own binding table afterwards; {@code _viewport}
 * records the rectangle it set. Overwriting those means reimplementing Minecraft's own caching, and
 * every one of them is a place a rewrite can drift from the original in a way nothing reports.
 *
 * <p>Redirecting the call inside them keeps all of it. What is replaced is exactly the driver, and
 * the game goes on believing it is running on GL.
 *
 * <h2>What is here</h2>
 *
 * <p>Every group of driver calls this game makes: the fixed-function state, buffers and vertex
 * arrays, textures, programs and uniforms, framebuffers and renderbuffers, and the draws themselves.
 * A group is here when the renderer can answer it and nothing is stubbed, so a call that is missing
 * stops the game at the first one and says which it was rather than drawing a picture that is
 * quietly wrong.
 *
 * <p><b>These redirects are not conditional, and the window is.</b> {@code WindowMixin} only takes
 * the GL context away when {@code wgpu_mc.no_gl_window} is set, so a launch without it has a real
 * context and these redirects fire anyway - sending the game's GL calls to a renderer that was
 * never created. That is a launch this branch does not support yet, and it is why the property is
 * how the client is started rather than an option within it.
 *
 * <p>Every redirect is {@code remap = false} on its lwjgl target: {@code GL11} and friends are not
 * game classes and appear in no mapping set.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

	// =============================================================================================
	// Fixed-function state
	//
	// None of this reaches the engine. GL state is a set of values a draw reads, and a wgpu pipeline
	// is where this renderer keeps them, so what happens here is a record - see GlState, which the
	// draw path reads when it goes looking for a pipeline that matches.
	// =============================================================================================

	@Redirect(
		method = "_scissorBox",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glScissor(IIII)V", remap = false)
	)
	private static void wgpuScissor(int x, int y, int width, int height) {
		GlState.scissorX = x;
		GlState.scissorY = y;
		GlState.scissorWidth = width;
		GlState.scissorHeight = height;
	}

	@Redirect(
		method = "_depthFunc",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthFunc(I)V", remap = false)
	)
	private static void wgpuDepthFunc(int func) {
		GlState.depthFunc = func;
	}

	@Redirect(
		method = "_depthMask",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthMask(Z)V", remap = false)
	)
	private static void wgpuDepthMask(boolean mask) {
		GlState.depthMask = mask;
	}

	@Redirect(
		method = "_blendFunc",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V", remap = false)
	)
	private static void wgpuBlendFunc(int source, int destination) {
		GlState.blendSrcRgb = source;
		GlState.blendDstRgb = destination;
		GlState.blendSrcAlpha = source;
		GlState.blendDstAlpha = destination;
	}

	@Redirect(
		method = "glBlendFuncSeparate",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL14;glBlendFuncSeparate(IIII)V", remap = false)
	)
	private static void wgpuBlendFuncSeparate(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
		GlState.blendSrcRgb = sourceRgb;
		GlState.blendDstRgb = destinationRgb;
		GlState.blendSrcAlpha = sourceAlpha;
		GlState.blendDstAlpha = destinationAlpha;
	}

	/**
	 * Blending in GL separates the RGB and alpha equations; a wgpu blend state carries exactly the
	 * same pair, so this is one field for one field.
	 */
	@Redirect(
		method = "_blendEquation",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL14;glBlendEquation(I)V", remap = false)
	)
	private static void wgpuBlendEquation(int mode) {
		GlState.blendEquationRgb = mode;
		GlState.blendEquationAlpha = mode;
	}

	@Redirect(
		method = "_polygonMode",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPolygonMode(II)V", remap = false)
	)
	private static void wgpuPolygonMode(int face, int mode) {
		// wgpu has no polygon mode: a pipeline either draws triangles filled or not at all, and the
		// only caller in this game is the wireframe debug path, which is off.
	}

	@Redirect(
		method = "_polygonOffset",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPolygonOffset(FF)V", remap = false)
	)
	private static void wgpuPolygonOffset(float factor, float units) {
		// Kept for the same reason a depth bias is kept anywhere: the pipeline that wants it is
		// built from it. The record is the depth-bias pair itself.
		GlState.polygonOffsetFactor = factor;
		GlState.polygonOffsetUnits = units;
	}

	@Redirect(
		method = "_logicOp",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glLogicOp(I)V", remap = false)
	)
	private static void wgpuLogicOp(int op) {
		// wgpu has no logic op. Minecraft enables it for exactly one thing - the "invert" blend of
		// the debug overlay - and that path also sets a full-screen quad's colour, so it is left as
		// a record rather than a wrong picture.
		GlState.logicOp = op;
	}

	@Redirect(
		method = "_colorMask",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColorMask(ZZZZ)V", remap = false)
	)
	private static void wgpuColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
		GlState.colorMask = (red ? 1 : 0) | (green ? 2 : 0) | (blue ? 4 : 0) | (alpha ? 8 : 0);
	}

	@Redirect(
		method = "_stencilFunc",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glStencilFunc(III)V", remap = false)
	)
	private static void wgpuStencilFunc(int func, int reference, int mask) {
		GlState.stencilFunc = func;
		GlState.stencilReference = reference;
		GlState.stencilCompareMask = mask;
	}

	@Redirect(
		method = "_stencilMask",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glStencilMask(I)V", remap = false)
	)
	private static void wgpuStencilMask(int mask) {
		GlState.stencilWriteMask = mask;
	}

	@Redirect(
		method = "_stencilOp",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glStencilOp(III)V", remap = false)
	)
	private static void wgpuStencilOp(int fail, int passDepthFail, int passDepthPass) {
		GlState.stencilFailOp = fail;
		GlState.stencilDepthFailOp = passDepthFail;
		GlState.stencilPassOp = passDepthPass;
	}

	@Redirect(
		method = "_clearDepth",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClearDepth(D)V", remap = false)
	)
	private static void wgpuClearDepth(double depth) {
		GlState.clearDepth = depth;
	}

	@Redirect(
		method = "_clearColor",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClearColor(FFFF)V", remap = false)
	)
	private static void wgpuClearColor(float red, float green, float blue, float alpha) {
		GlState.clearRed = red;
		GlState.clearGreen = green;
		GlState.clearBlue = blue;
		GlState.clearAlpha = alpha;
	}

	@Redirect(
		method = "_clearStencil",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClearStencil(I)V", remap = false)
	)
	private static void wgpuClearStencil(int value) {
		GlState.clearStencil = value;
	}

	@Redirect(
		method = "_clear",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClear(I)V", remap = false)
	)
	private static void wgpuClear(int mask) {
		GlFramebuffers.clearCurrent(mask);
	}

	@Redirect(
		method = "_viewport",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V", remap = false)
	)
	private static void wgpuViewport(int x, int y, int width, int height) {
		GlState.viewportX = x;
		GlState.viewportY = y;
		GlState.viewportWidth = width;
		GlState.viewportHeight = height;
	}

	@Redirect(
		method = "_pixelStore",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPixelStorei(II)V", remap = false)
	)
	private static void wgpuPixelStore(int name, int value) {
		GlTextures.pixelStore(name, value);
	}

	/**
	 * {@code glGetError} is asked after every risky call by parts of the game, and there is no error
	 * to report: every call this seam answers either worked or threw.
	 */
	@Redirect(
		method = "_getError",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGetError()I", remap = false)
	)
	private static int wgpuGetError() {
		return 0;
	}

	@Redirect(
		method = "_getString",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGetString(I)Ljava/lang/String;", remap = false)
	)
	private static String wgpuGetString(int name) {
		switch (name) {
			case 7936:
				return "wgpu-mc";
			case 7937:
				return "wgpu-mc";
			case 7938:
				// The version the seam answers to. It is GL 3.3 because that is the API whose shape
				// this renderer is standing in for, and because the game reads it to decide which
				// of its own paths it may take.
				return "3.3.0 wgpu-mc";
			case 35724:
				return "3.30";
			case 7939:
				// An extension string is a list of things this renderer does not implement; naming
				// any of them would be a claim the draw path cannot keep.
				return "";
			default:
				return "";
		}
	}

	@Redirect(
		method = "_getInteger",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGetInteger(I)I", remap = false)
	)
	private static int wgpuGetInteger(int name) {
		switch (name) {
			case 3379:
				return WgpuRenderer.maxTextureSize();
			case 34076:
				return WgpuRenderer.maxTextureSize();
			case 34930:
				return GlState.MAX_TEXTURE_UNITS;
			case 34921:
				return 16;
			case 35071:
				return 256;
			case 35380:
				return WgpuRenderer.uniformOffsetAlignment();
			case 33001:
				return 0;
			case 33307:
				return 0;
			default:
				return 0;
		}
	}

	@Redirect(
		method = "glActiveTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL13;glActiveTexture(I)V", remap = false)
	)
	private static void wgpuActiveTexture(int texture) {
		GlState.activeTexture = texture - 33984;
	}

	// =============================================================================================
	// Textures
	// =============================================================================================

	@Redirect(
		method = "_texParameter(IIF)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexParameterf(IIF)V", remap = false)
	)
	private static void wgpuTexParameterf(int target, int name, float value) {
		GlTextures.texParameterf(target, name, value);
	}

	@Redirect(
		method = "_texParameter(III)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexParameteri(III)V", remap = false)
	)
	private static void wgpuTexParameteri(int target, int name, int value) {
		GlTextures.texParameteri(target, name, value);
	}

	@Redirect(
		method = "_getTexLevelParameter",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGetTexLevelParameteri(III)I", remap = false)
	)
	private static int wgpuGetTexLevelParameter(int target, int level, int name) {
		return GlTextures.getTexLevelParameter(target, level, name);
	}

	@Redirect(
		method = "_genTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGenTextures()I", remap = false)
	)
	private static int wgpuGenTexture() {
		return GlTextures.genTexture();
	}

	@Redirect(
		method = "_genTextures",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGenTextures([I)V", remap = false)
	)
	private static void wgpuGenTextures(int[] textures) {
		GlTextures.genTextures(textures);
	}

	@Redirect(
		method = "_deleteTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDeleteTextures(I)V", remap = false)
	)
	private static void wgpuDeleteTexture(int texture) {
		GlTextures.deleteTexture(texture);
	}

	@Redirect(
		method = "_deleteTextures",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDeleteTextures([I)V", remap = false)
	)
	private static void wgpuDeleteTextures(int[] textures) {
		GlTextures.deleteTextures(textures);
	}

	/**
	 * The bind is where this side's own record is written, and Minecraft's is written by the method
	 * around it: {@code _bindTexture} updates its binding table and then calls GL, so the redirect
	 * runs with both tables about to agree.
	 */
	@Redirect(
		method = "_bindTexture",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBindTexture(II)V", remap = false)
	)
	private static void wgpuBindTexture(int target, int texture) {
		GlState.bindTexture(texture);
	}

	@Redirect(
		method = "_texImage2D",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexImage2D(IIIIIIIILjava/nio/IntBuffer;)V", remap = false)
	)
	private static void wgpuTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, IntBuffer pixels) {
		GlTextures.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
	}

	@Redirect(
		method = "_texSubImage2D",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexSubImage2D(IIIIIIIIJ)V", remap = false)
	)
	private static void wgpuTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
		GlTextures.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
	}

	@Redirect(
		method = "_upload",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexSubImage2D(IIIIIIIILjava/nio/IntBuffer;)V", remap = false)
	)
	private static void wgpuUpload(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, IntBuffer pixels) {
		GlTextures.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
	}

	/**
	 * `glGetTexImage`, which is the one readback the game makes and the one call that used to end the
	 * process.
	 *
	 * {@code GameRenderer#tryTakeScreenshotIfNeeded} asks for it a second after a singleplayer player
	 * logs in, so that the world list can show the level's icon. Without this redirect it reached
	 * LWJGL's unbound GL entry point - a window created with {@code GLFW_NO_API} has no context to have
	 * bound one - and the client died with an access violation inside
	 * {@code GL11C.nglGetTexImage(IIIIJ)V+0} the moment a world was entered. Nothing about that crash
	 * named the call: the top of the Java stack was {@code NativeImage.downloadTexture}, and the frame
	 * itself was an anonymous address.
	 */
	@Redirect(
		method = "_getTexImage",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGetTexImage(IIIIJ)V", remap = false)
	)
	private static void wgpuGetTexImage(int target, int level, int format, int type, long pixels) {
		GlTextures.getTexImage(level, pixels);
	}

	// =============================================================================================
	// Framebuffers and renderbuffers
	// =============================================================================================

	@Redirect(
		method = "glGenFramebuffers",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glGenFramebuffers()I", remap = false)
	)
	private static int wgpuGenFramebuffer() {
		return GlFramebuffers.genFramebuffer();
	}

	@Redirect(
		method = "_glBindFramebuffer",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V", remap = false)
	)
	private static void wgpuBindFramebuffer(int target, int framebuffer) {
		GlFramebuffers.bindFramebuffer(target, framebuffer);
	}

	@Redirect(
		method = "_glFramebufferTexture2D",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glFramebufferTexture2D(IIIII)V", remap = false)
	)
	private static void wgpuFramebufferTexture2D(int target, int attachment, int textureTarget, int texture, int level) {
		GlFramebuffers.framebufferTexture2D(target, attachment, textureTarget, texture, level);
	}

	@Redirect(
		method = "glCheckFramebufferStatus",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glCheckFramebufferStatus(I)I", remap = false)
	)
	private static int wgpuCheckFramebufferStatus(int target) {
		return GlFramebuffers.checkFramebufferStatus(target);
	}

	@Redirect(
		method = "glGenRenderbuffers",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glGenRenderbuffers()I", remap = false)
	)
	private static int wgpuGenRenderbuffer() {
		return GlFramebuffers.genRenderbuffer();
	}

	@Redirect(
		method = "_glBindRenderbuffer",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBindRenderbuffer(II)V", remap = false)
	)
	private static void wgpuBindRenderbuffer(int target, int renderbuffer) {
		GlFramebuffers.bindRenderbuffer(target, renderbuffer);
	}

	@Redirect(
		method = "_glRenderbufferStorage",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glRenderbufferStorage(IIII)V", remap = false)
	)
	private static void wgpuRenderbufferStorage(int target, int internalFormat, int width, int height) {
		GlFramebuffers.renderbufferStorage(target, internalFormat, width, height);
	}

	@Redirect(
		method = "_glFramebufferRenderbuffer",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glFramebufferRenderbuffer(IIII)V", remap = false)
	)
	private static void wgpuFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer) {
		GlFramebuffers.framebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer);
	}

	@Redirect(
		method = "_glDeleteFramebuffers",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glDeleteFramebuffers(I)V", remap = false)
	)
	private static void wgpuDeleteFramebuffer(int framebuffer) {
		GlFramebuffers.deleteFramebuffer(framebuffer);
	}

	@Redirect(
		method = "_glDeleteRenderbuffers",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glDeleteRenderbuffers(I)V", remap = false)
	)
	private static void wgpuDeleteRenderbuffer(int renderbuffer) {
		GlFramebuffers.deleteRenderbuffer(renderbuffer);
	}

	@Redirect(
		method = "_glBlitFrameBuffer",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBlitFramebuffer(IIIIIIIIII)V", remap = false)
	)
	private static void wgpuBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
		GlFramebuffers.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
	}

	// ---- buffers and vertex arrays ---------------------------------------------------------------

	@Redirect(
		method = "_glGenBuffers()I",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glGenBuffers()I", remap = false)
	)
	private static int wgpuGenBuffer() {
		return GlBuffers.genBuffer();
	}

	@Redirect(
		method = "_glGenVertexArrays()I",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glGenVertexArrays()I", remap = false)
	)
	private static int wgpuGenVertexArray() {
		return GlBuffers.genVertexArray();
	}

	@Redirect(
		method = "_glBindBuffer(II)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glBindBuffer(II)V", remap = false)
	)
	private static void wgpuBindBuffer(int target, int buffer) {
		GlBuffers.bindBuffer(target, buffer);
	}

	@Redirect(
		method = "_glBindVertexArray(I)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBindVertexArray(I)V", remap = false)
	)
	private static void wgpuBindVertexArray(int array) {
		GlBuffers.bindVertexArray(array);
	}

	@Redirect(
		method = "_glBufferData(ILjava/nio/ByteBuffer;I)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glBufferData(ILjava/nio/ByteBuffer;I)V", remap = false)
	)
	private static void wgpuBufferData(int target, ByteBuffer data, int usage) {
		GlBuffers.bufferData(target, data, usage);
	}

	@Redirect(
		method = "_glBufferData(IJI)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glBufferData(IJI)V", remap = false)
	)
	private static void wgpuBufferDataAllocated(int target, long size, int usage) {
		GlBuffers.bufferData(target, size, usage);
	}

	@Redirect(
		method = "_glMapBuffer(II)Ljava/nio/ByteBuffer;",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glMapBuffer(II)Ljava/nio/ByteBuffer;", remap = false)
	)
	private static ByteBuffer wgpuMapBuffer(int target, int access) {
		return GlBuffers.mapBuffer(target, access);
	}

	// `glUnmapBuffer` answers a boolean - GL_TRUE when the store survived the mapping - and
	// `_glUnmapBuffer` throws it away, so the descriptor that matters is the *call's*, not the
	// method's: `(I)Z`. Targeting `(I)V` matches nothing and Mixin reports it as "Scanned 0
	// target(s)", which is what a descriptor that describes the enclosing method looks like.
	@Redirect(
		method = "_glUnmapBuffer(I)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glUnmapBuffer(I)Z", remap = false)
	)
	private static boolean wgpuUnmapBuffer(int target) {
		return GlBuffers.unmapBuffer(target);
	}

	@Redirect(
		method = "_glDeleteBuffers(I)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glDeleteBuffers(I)V", remap = false)
	)
	private static void wgpuDeleteBuffer(int buffer) {
		GlBuffers.deleteBuffer(buffer);
	}

	@Redirect(
		method = "_glDeleteVertexArrays(I)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glDeleteVertexArrays(I)V", remap = false)
	)
	private static void wgpuDeleteVertexArray(int array) {
		GlBuffers.deleteVertexArray(array);
	}

	// ---- vertex attributes -----------------------------------------------------------------------
	//
	// These describe the *bound* vertex array rather than a buffer, which is the difference between
	// GL and wgpu that costs the most to keep: a wgpu vertex layout is part of the pipeline, and GL's
	// belongs to the vertex array. Recording them here is what lets a later group build the pipeline
	// a draw needs from the state the game has already set.

	@Redirect(
		method = "_vertexAttribPointer(IIIZIJ)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glVertexAttribPointer(IIIZIJ)V", remap = false)
	)
	private static void wgpuVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
		GlBuffers.vertexAttribPointer(index, size, type, normalized, stride, pointer);
	}

	@Redirect(
		method = "_vertexAttribIPointer(IIIIJ)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glVertexAttribIPointer(IIIIJ)V", remap = false)
	)
	private static void wgpuVertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
		GlBuffers.vertexAttribIPointer(index, size, type, stride, pointer);
	}

	@Redirect(
		method = "_enableVertexAttribArray(I)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glEnableVertexAttribArray(I)V", remap = false)
	)
	private static void wgpuEnableVertexAttribArray(int index) {
		GlBuffers.enableVertexAttribArray(index);
	}

	@Redirect(
		method = "_disableVertexAttribArray(I)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glDisableVertexAttribArray(I)V", remap = false)
	)
	private static void wgpuDisableVertexAttribArray(int index) {
		GlBuffers.disableVertexAttribArray(index);
	}
	// ------------------------------------------------------------------ programs
	//
	// This group is injected at the head of each method and cancels the body, rather than
	// redirecting the GL call inside it, for two reasons. `glShaderSource` flattens a
	// `List<String>` into a UTF-8 buffer and calls `GL20C.nglShaderSource` through a pointer, so
	// there is no single call to redirect and nothing to recover afterwards; and the two info-log
	// methods exist to return the driver's message, which this side does not have. Cancelling is
	// also what GL means here: each of these leaves is a wrapper around the driver and nothing
	// else, so there is no bookkeeping in the body that has to run.
	//
	// `glCompileShader` marks the shader compiled, and `glGetShaderi(GL_COMPILE_STATUS)` says so.
	// That is not a claim to have checked: there is no compiler on this side, the engine compiles
	// the GLSL when the pipeline is built, and the game's own use of the answer is to log the info
	// log and either continue or throw. A shader that really does not compile fails at the
	// pipeline, with the engine's own message.

	@Inject(method = "glCreateShader(I)I", at = @At("HEAD"), cancellable = true)
	private static void wgpuCreateShader(int type, CallbackInfoReturnable<Integer> ci) {
		ci.setReturnValue(GlPrograms.genShader(type));
	}

	@Inject(method = "glShaderSource(ILjava/util/List;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuShaderSource(int shader, List<String> source, CallbackInfo ci) {
		GlPrograms.shaderSource(shader, source);
		ci.cancel();
	}

	@Inject(method = "glCompileShader(I)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuCompileShader(int shader, CallbackInfo ci) {
		GlPrograms.compileShader(shader);
		ci.cancel();
	}

	@Inject(method = "glGetShaderi(II)I", at = @At("HEAD"), cancellable = true)
	private static void wgpuGetShaderi(int shader, int parameter, CallbackInfoReturnable<Integer> ci) {
		ci.setReturnValue(GlPrograms.getShaderi(shader, parameter));
	}

	@Inject(method = "glGetShaderInfoLog(II)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private static void wgpuGetShaderInfoLog(int shader, int maxLength, CallbackInfoReturnable<String> ci) {
		ci.setReturnValue(GlPrograms.shaderInfoLog(shader, maxLength));
	}

	@Inject(method = "glDeleteShader(I)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuDeleteShader(int shader, CallbackInfo ci) {
		GlPrograms.deleteShader(shader);
		ci.cancel();
	}

	@Inject(method = "glCreateProgram()I", at = @At("HEAD"), cancellable = true)
	private static void wgpuCreateProgram(CallbackInfoReturnable<Integer> ci) {
		ci.setReturnValue(GlPrograms.createProgram());
	}

	@Inject(method = "glAttachShader(II)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuAttachShader(int program, int shader, CallbackInfo ci) {
		GlPrograms.attachShader(program, shader);
		ci.cancel();
	}

	@Inject(method = "glLinkProgram(I)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuLinkProgram(int program, CallbackInfo ci) {
		GlPrograms.linkProgram(program);
		ci.cancel();
	}

	@Inject(method = "glGetProgrami(II)I", at = @At("HEAD"), cancellable = true)
	private static void wgpuGetProgrami(int program, int parameter, CallbackInfoReturnable<Integer> ci) {
		ci.setReturnValue(GlPrograms.getProgrami(program, parameter));
	}

	@Inject(method = "glGetProgramInfoLog(II)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private static void wgpuGetProgramInfoLog(int program, int maxLength, CallbackInfoReturnable<String> ci) {
		ci.setReturnValue(GlPrograms.programInfoLog(program, maxLength));
	}

	@Inject(method = "glDeleteProgram(I)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuDeleteProgram(int program, CallbackInfo ci) {
		GlPrograms.deleteProgram(program);
		ci.cancel();
	}

	@Inject(method = "_glUseProgram(I)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUseProgram(int program, CallbackInfo ci) {
		GlPrograms.useProgram(program);
		ci.cancel();
	}

	@Inject(method = "_glGetAttribLocation(ILjava/lang/CharSequence;)I", at = @At("HEAD"), cancellable = true)
	private static void wgpuGetAttribLocation(int program, CharSequence attribute, CallbackInfoReturnable<Integer> ci) {
		ci.setReturnValue(GlPrograms.getAttribLocation(program, attribute));
	}

	@Inject(method = "_glBindAttribLocation(IILjava/lang/CharSequence;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuBindAttribLocation(int program, int location, CharSequence attribute, CallbackInfo ci) {
		GlPrograms.bindAttribLocation(program, location, attribute);
		ci.cancel();
	}

	@Inject(method = "_glGetUniformLocation(ILjava/lang/CharSequence;)I", at = @At("HEAD"), cancellable = true)
	private static void wgpuGetUniformLocation(int program, CharSequence name, CallbackInfoReturnable<Integer> ci) {
		ci.setReturnValue(GlPrograms.getUniformLocation(program, name));
	}

	@Inject(method = "_glUniform1i(II)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform1i(int location, int value, CallbackInfo ci) {
		GlPrograms.uniformInt(location, value);
		ci.cancel();
	}

	@Inject(method = "_glUniform1(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform1iv(int location, IntBuffer values, CallbackInfo ci) {
		GlPrograms.uniformInts(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniform1(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform1fv(int location, FloatBuffer values, CallbackInfo ci) {
		GlPrograms.uniformFloats(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniform2(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform2iv(int location, IntBuffer values, CallbackInfo ci) {
		GlPrograms.uniformInts(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniform2(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform2fv(int location, FloatBuffer values, CallbackInfo ci) {
		GlPrograms.uniformFloats(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniform3(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform3iv(int location, IntBuffer values, CallbackInfo ci) {
		GlPrograms.uniformInts(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniform3(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform3fv(int location, FloatBuffer values, CallbackInfo ci) {
		GlPrograms.uniformFloats(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniform4(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform4iv(int location, IntBuffer values, CallbackInfo ci) {
		GlPrograms.uniformInts(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniform4(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniform4fv(int location, FloatBuffer values, CallbackInfo ci) {
		GlPrograms.uniformFloats(location, values);
		ci.cancel();
	}

	@Inject(method = "_glUniformMatrix2(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniformMatrix2(int location, boolean transpose, FloatBuffer values, CallbackInfo ci) {
		GlPrograms.uniformMatrix(location, 2, transpose, values);
		ci.cancel();
	}

	@Inject(method = "_glUniformMatrix3(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniformMatrix3(int location, boolean transpose, FloatBuffer values, CallbackInfo ci) {
		GlPrograms.uniformMatrix(location, 3, transpose, values);
		ci.cancel();
	}

	@Inject(method = "_glUniformMatrix4(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuUniformMatrix4(int location, boolean transpose, FloatBuffer values, CallbackInfo ci) {
		GlPrograms.uniformMatrix(location, 4, transpose, values);
		ci.cancel();
	}

	@Inject(method = "_drawElements(IIIJ)V", at = @At("HEAD"), cancellable = true)
	private static void wgpuDrawElements(int mode, int count, int type, long indices, CallbackInfo ci) {
		GlDraws.drawElements(mode, count, type, indices);
		ci.cancel();
	}
}
