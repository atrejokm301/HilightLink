package dev.kevtr.hilightlink.system

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where an Xposed framework (Vector, LSPosed) starts the app's code inside the system, when
 * the app is switched on there for the System Framework. Nothing is hooked: being inside the
 * system process is all that is needed, and [Loader] takes it from there.
 */
class XposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        if (!started.compareAndSet(false, true)) return
        Thread(Loader::run, "hilightlink-boot").apply { isDaemon = true }.start()
    }

    private companion object {
        val started = AtomicBoolean()
    }
}
