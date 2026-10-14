package dev.birb.wgpu

import java.io.File

/**
 * The Android half of the port, and the one place that knows how the game is actually running.
 *
 * On a phone the "window system" belongs to the launcher: FCL, Pojav and their forks are Android
 * apps that draw the game into their own view hierarchy, and the GLFW the game sees is a stub on
 * top of that. The stub reports `x11` as its platform, does not implement the native accessors
 * `GLFWNativeX11`/`GLFWNativeWin32`, and its window pointer is the stub's own structure - so
 * nothing desktop-shaped can produce a presentable handle from it.
 *
 * What a launcher *does* have is what every Android app has: an `android.view.Surface` at the
 * bottom of its view hierarchy. That is the one thing both wgpu backends can present through,
 * once Rust has turned it into an `ANativeWindow` with `ANativeWindow_fromSurface`. Finding that
 * Surface from inside a mod - which cannot name a single launcher class without breaking on the
 * next fork - is [findGameSurface]'s whole job, and it is written entirely against the Android
 * framework API: reflection only, no `android.jar` on the compile classpath, no launcher classes
 * by name.
 *
 * Everything here is best-effort with the failures logged rather than thrown, because the caller
 * (the device creation in `WgpuBackend`) is the one that turns "no surface" into an error screen
 * with a message that says what to look for in the log.
 */
object AndroidRuntime {

    /**
     * Whether this JVM runs on Android.
     *
     * Three independent signals, any one of which settles it, because no single one is present
     * on every launcher: FCL's OpenJDK 25 game JVM carries **no framework classes at all** (the
     * boot classpath holds only the Cacio AWT jars), so the `ActivityThread` probe fails there
     * even though the process is as Android as it gets - but the device's `/system/build.prop`
     * is on disk regardless of the JVM, and FCL's launch arguments pin `os.version` to
     * `Android-<release>`. A desktop JVM answers no to all three. Evaluated once; the answer
     * cannot change mid-process.
     */
    val isAndroid: Boolean by lazy {
        val frameworkClass = runCatching {
            Class.forName("android.app.ActivityThread") != null
        }.getOrDefault(false)
        val systemImage = File("/system/build.prop").isFile
        val osVersion = System.getProperty("os.version").orEmpty()
        val osVersionAndroid = osVersion.lowercase().contains("android")

        val found = frameworkClass || systemImage || osVersionAndroid
        WgpuMcMod.LOGGER.info(
            "wgpu: Android runtime detection: framework classes {}, /system/build.prop {}, " +
                "os.version '{}' -> {}",
            frameworkClass,
            systemImage,
            osVersion.ifBlank { "<unset>" },
            found,
        )
        found
    }

    /**
     * The launcher's game `Surface`, or `null` when the view hierarchy would not give one up.
     *
     * Three walks of the same hierarchy, tried in the order a launcher is most likely to keep
     * its game view:
     *
     *  1. **The `SurfaceView` any live activity has in its window** - the way FCL and Pojav draw
     *     the game, and the cleanest source: a SurfaceView's holder owns the exact surface the
     *     game is rendered into, and the holder's `getSurface` is public API.
     *  2. **Every view `WindowManagerGlobal` tracks, for the same SurfaceView test** - the same
     *     view without having to find the activity first; `mViews` is what the framework's own
     *     debug and screenshot code reads.
     *  3. **`ViewRootImpl.mSurface` for any of those views** - the surface the window system
     *     itself draws through, valid even where the game view is not a SurfaceView.
     *
     * Each walk is wrapped so that a class or field the running Android version renamed costs
     * that walk, not the process. The `Surface` handed back is the launcher's own object: the
     * JVM holds it for as long as the renderer presents, which is what the reference Rust takes
     * with `ANativeWindow_fromSurface` assumes.
     */
    fun findGameSurface(): Any? {
        if (!isAndroid) return null

        // Walk 1 and 3: through the decor view of every activity ActivityThread still holds.
        for (decorView in activityDecorViews()) {
            findSurfaceFromView(decorView)?.let { return it }
        }

        // Walk 2 and 3 across everything the window manager tracks, whatever view tree they
        // belong to.
        for (view in trackedViews()) {
            findSurfaceFromView(view)?.let { return it }
        }

        WgpuMcMod.LOGGER.warn(
            "wgpu: no android.view.Surface in this process - looked through the decor view of " +
                "every live activity ({}), every view WindowManagerGlobal tracks ({}), each " +
                "for a SurfaceView holder surface first and a ViewRootImpl surface second",
            activityDecorViews().size,
            trackedViews().size,
        )
        return null
    }

    /** The decor views of the activities `ActivityThread.mActivities` still holds. */
    private fun activityDecorViews(): List<Any> =
        liveActivities().mapNotNull { activity ->
            runCatching {
                val window = activity.javaClass.getMethod("getWindow").invoke(activity)
                    ?: return@mapNotNull null
                window.javaClass.getMethod("getDecorView").invoke(window)
            }.getOrNull()
        }

    /** The activities `ActivityThread.mActivities` still holds, empty when reflection loses. */
    private fun liveActivities(): List<Any> = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val records = thread.javaClass.getDeclaredField("mActivities")
            .apply { isAccessible = true }
            .get(thread) as? Map<Any?, Any?> ?: return emptyList()

        records.values.mapNotNull { record ->
            record?.javaClass?.getDeclaredField("activity")
                ?.apply { isAccessible = true }
                ?.get(record)
        }
    }.getOrDefault(emptyList())

    /** The views `WindowManagerGlobal.mViews` tracks, empty when reflection loses. */
    private fun trackedViews(): List<Any> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getInstance")
            .invoke(null) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        global.javaClass.getDeclaredField("mViews")
            .apply { isAccessible = true }
            .get(global) as? List<Any> ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * The presentable surface one view tree offers, if any.
     *
     * A `SurfaceView` is asked first - the launcher's game view, whose holder surface is the
     * surface the game presents into - and the view root's own surface is the fallback. Both
     * are checked with the surface's own `isValid` before being offered.
     */
    private fun findSurfaceFromView(view: Any): Any? {
        val surfaceView = asSurfaceView(view)
        if (surfaceView != null) {
            holderSurface(surfaceView)?.let { return it }
        }

        viewRootSurface(view)?.let { return it }
        return null
    }

    /** [view] as a `SurfaceView`, or `null` when it is not one - by class name, not by cast. */
    private fun asSurfaceView(view: Any): Any? {
        if (view.javaClass.name != "android.view.SurfaceView" &&
            !isSubclassOf(view.javaClass, "android.view.SurfaceView")
        ) {
            return null
        }
        return view
    }

    private fun isSubclassOf(clazz: Class<*>, name: String): Boolean {
        var current: Class<*>? = clazz
        while (current != null) {
            if (current.name == name) return true
            current = current.superclass
        }
        return false
    }

    /** The surface a `SurfaceView`'s holder owns, checked for validity. */
    private fun holderSurface(surfaceView: Any): Any? = runCatching {
        val holder = surfaceView.javaClass.getMethod("getHolder").invoke(surfaceView)
        val surface = holder.javaClass.getMethod("getSurface").invoke(holder)
        surface.takeIf { isValidSurface(it) }
    }.getOrNull()

    /** The surface the view root renders through, checked for validity. */
    private fun viewRootSurface(view: Any): Any? = runCatching {
        val root = view.javaClass.getMethod("getViewRootImpl").invoke(view) ?: return null
        val surface = root.javaClass.getDeclaredField("mSurface")
            .apply { isAccessible = true }
            .get(root)
        surface.takeIf { isValidSurface(it) }
    }.getOrNull()

    /** `Surface.isValid`, by reflection - an invalid surface is one wgpu would refuse anyway. */
    private fun isValidSurface(surface: Any): Boolean = runCatching {
        surface.javaClass.getMethod("isValid").invoke(surface) as Boolean
    }.getOrDefault(false)

    /**
     * The jar directory the device's ABI has its native library under.
     *
     * `SUPPORTED_ABIS` is what the device says about itself; `os.arch` is the fallback a JVM
     * that hides `Build` from us still answers. `null` means "cannot tell", and the loader
     * responds by probing the flat directories a jar with one library per *platform* uses -
     * which is also exactly what a desktop JVM does.
     */
    fun abiResourceDirectory(base: String): String? {
        val primary = runCatching {
            @Suppress("UNCHECKED_CAST")
            val abis = Class.forName("android.os.Build")
                .getField("SUPPORTED_ABIS")
                .get(null) as? Array<String>
            abis?.firstOrNull()
        }.getOrNull() ?: System.getProperty("os.arch")?.lowercase()

        val directory = when {
            primary == null -> return null
            primary.startsWith("arm64") || primary == "aarch64" -> "android-arm64-v8a"
            primary.startsWith("arm") || primary == "arm" -> "android-armeabi-v7a"
            primary.startsWith("x86_64") || primary == "amd64" -> "android-x86_64"
            primary.startsWith("x86") || primary == "i386" || primary == "i686" -> "android-x86"
            else -> {
                WgpuMcMod.LOGGER.warn(
                    "wgpu: Android architecture '{}' has no directory naming rule; probing " +
                        "the platform-flat native directories instead",
                    primary,
                )
                return null
            }
        }
        return "$base/$directory/"
    }

    /**
     * A directory this process may write an extracted library into and `dlopen` it from.
     *
     * The launcher's own unpack directories (which the loader's properties name) are the right
     * answer and are tried first by [dev.birb.wgpu.rust.WgpuNative]; this is the Android fallback
     * when none of them exists - the JVM's own temporary directory, which FCL and friends point
     * at a cache directory they own. `natives/` under the working directory, the desktop
     * fallback, stays the last resort: the game directory on these launchers is writable.
     */
    fun extractionDirectory(): File? {
        if (!isAndroid) return null

        val temporary = System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }
        if (temporary != null) {
            val directory = File(temporary)
            if (directory.isDirectory || directory.mkdirs()) return directory
        }
        return null
    }
}
