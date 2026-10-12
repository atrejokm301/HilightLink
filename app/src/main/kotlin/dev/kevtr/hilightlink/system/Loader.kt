package dev.kevtr.hilightlink.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.PatternMatcher
import android.util.Log
import dalvik.system.InMemoryDexClassLoader
import dev.kevtr.hilightlink.Bridge
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Runs [Engine] inside the system process, from whichever APK of the app is installed right
 * now. The code the Xposed framework loaded is the app as it was when the phone started, so
 * the engine is loaded apart from it and again after every update of the app: updating takes
 * no reboot, only a change to this loader does.
 *
 * Nothing here may throw into the system process.
 */
internal object Loader {
    private const val TAG = "HilightLink"
    private const val ENGINE = "dev.kevtr.hilightlink.system.Engine"
    /** `setprop persist.sys.hilightlink.off 1` as root keeps the engine from being loaded. */
    private const val OFF_SWITCH = "persist.sys.hilightlink.off"

    private lateinit var context: Context
    private var engine: Class<*>? = null

    fun run() {
        try {
            while (property("sys.boot_completed") != "1") Thread.sleep(2000)
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            context = activityThread.getMethod("getSystemContext").invoke(current) as Context

            val thread = HandlerThread("hilightlink-loader").apply { start() }
            val changes = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
                addDataSchemeSpecificPart(Bridge.PKG, PatternMatcher.PATTERN_LITERAL)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_PACKAGE_ADDED) reload() else unload()
                }
            }
            context.registerReceiver(receiver, changes, null, Handler(thread.looper))
            reload()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start inside the system", t)
        }
    }

    @Synchronized
    private fun reload() {
        unload()
        try {
            if (property(OFF_SWITCH) == "1") {
                Log.i(TAG, "Switched off by $OFF_SWITCH")
                return
            }
            val apk = context.packageManager.getApplicationInfo(Bridge.PKG, 0).sourceDir
            // From memory: the system process may read an app's APK but not run code mapped from it.
            val dex = ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { DEX.matches(it.name) }
                    .map { ByteBuffer.wrap(zip.getInputStream(it).use { input -> input.readBytes() }) }
                    .toList()
            }
            // The parent is the platform's own loader, so the engine's classes are the new APK's
            // and never the ones the Xposed framework loaded at boot.
            val loader = InMemoryDexClassLoader(dex.toTypedArray(), Context::class.java.classLoader)
            val loaded = loader.loadClass(ENGINE)
            loaded.getMethod("start", Context::class.java).invoke(null, context)
            engine = loaded
            Log.i(TAG, "Engine loaded from $apk")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load the engine", t)
        }
    }

    @Synchronized
    private fun unload() {
        val loaded = engine ?: return
        engine = null
        try {
            loaded.getMethod("stop").invoke(null)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not stop the engine", t)
        }
    }

    private fun property(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    } catch (t: Throwable) {
        ""
    }

    private val DEX = Regex("""classes\d*\.dex""")
}
