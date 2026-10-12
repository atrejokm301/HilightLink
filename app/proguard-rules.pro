# The root helper is started by app_process with this exact class name
# (module/service.sh and Leds.startHelper), so it must keep its name and entry point.
-keep class dev.kevtr.hilightlink.daemon.Main {
    public static void main(java.lang.String[]);
}

# Running inside the system (see system/Engine.kt): the Xposed framework starts XposedEntry by
# the name in assets/xposed_init, and the loader it starts finds Engine by name in a newer APK.
-keep class dev.kevtr.hilightlink.system.XposedEntry { *; }
-keep class dev.kevtr.hilightlink.system.Engine {
    public static void start(android.content.Context);
    public static void stop();
}
-dontwarn de.robv.android.xposed.**

# Strip log calls that only matter while developing.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
