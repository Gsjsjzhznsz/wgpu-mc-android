package dev.birb.wgpu.mixin.core;

import com.mojang.blaze3d.systems.TimerQuery;
import dev.birb.wgpu.render.GlSeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one GL question F3 asks, which is the one thing on that screen that could not be answered.
 *
 * <p>{@code TimerQuery} measures a frame on the GPU with {@code GL_ARB_timer_query}, and it decides
 * whether it may do so by asking {@link org.lwjgl.opengl.GL#getCapabilities()} - an lwjgl call that
 * reads the current context's function table. On a window created with {@code GLFW_NO_API} there is
 * no such table, so it does not answer "no timer queries here"; it throws:
 *
 * <pre>
 * java.lang.ExceptionInInitializerError
 *     at com.mojang.blaze3d.systems.TimerQuery.getInstance(TimerQuery.java:16)
 *     at net.minecraft.client.Minecraft.runTick(Minecraft.java:1180)
 * Caused by: java.lang.IllegalStateException: No GLCapabilities instance set for the current thread.
 *     at org.lwjgl.opengl.GL.checkCapabilities(GL.java:231)
 *     at com.mojang.blaze3d.systems.TimerQuery$TimerQueryLazyLoader.instantiate(TimerQuery.java:93)
 * </pre>
 *
 * <p>The failure is worse than an exception thrown once. {@code INSTANCE} is a {@code static final}
 * of a holder class, so the throw happens inside the holder's <em>class initialisation</em>: the
 * class is left erroneous, and the exception arrives wrapped as an {@code ExceptionInInitializerError}
 * that nothing catches. {@code Minecraft.runTick} does not guard the call, so it reaches the render
 * thread's own handler, which reports it and exits the game.
 *
 * <h2>Why this is reached by pressing F3 and by nothing else</h2>
 *
 * {@code Minecraft.runTick} asks for the query only inside the frame-time profiler:
 *
 * <pre>
 * if (!this.getDebugOverlay().showDebugScreen() &amp;&amp; !this.metricsRecorder.isRecording()) {
 *     flag = false;
 *     this.gpuUtilization = 0.0;
 * } else {
 *     ...
 *     TimerQuery.getInstance().ifPresent(TimerQuery::beginProfile);
 * }
 * </pre>
 *
 * <p>So the game runs for as long as it likes with the query unbuilt, and dies the first time the
 * debug screen is opened. That is what the crash looked like: a hundred and forty seconds in a world,
 * then a keypress, then {@code exit value -1} with no {@code hs_err} file, because this is a Java
 * exception at the top of the render thread rather than a native fault.
 *
 * <h2>What is answered instead</h2>
 *
 * <p>Returning null from the holder's factory leaves {@code INSTANCE} as {@code Optional.empty()},
 * which is exactly what the class itself produces on a driver without the extension - the call sites
 * are written for it, and {@code ifPresent} then does nothing. The profiler's frame-time chart is
 * empty and {@code gpuUtilization} stays zero, which is the truth on this backend: a wgpu surface
 * has no GL query objects, and the renderer measures GPU time its own way, through the
 * {@code GPU timestamps} setting, which writes timestamps into the command stream rather than asking
 * a driver for them.
 *
 * <p>The GL path is untouched: with a real context the call still reaches lwjgl, and a driver that
 * has {@code GL_ARB_timer_query} still gets its query.
 */
@Mixin(targets = "com.mojang.blaze3d.systems.TimerQuery$TimerQueryLazyLoader")
public class TimerQueryMixin {

	@Inject(method = "instantiate", at = @At("HEAD"), cancellable = true)
	private static void wgpuNoTimerQueries(CallbackInfoReturnable<TimerQuery> cir) {
		if (GlSeam.isGlWindowDisabled()) {
			cir.setReturnValue(null);
		}
	}
}
