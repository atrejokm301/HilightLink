# The root helper is started by app_process with this exact class name
# (module/service.sh and Leds.startHelper), so it must keep its name and entry point.
-keep class dev.kevtr.hilightlink.daemon.Main {
    public static void main(java.lang.String[]);
}

# Strip log calls that only matter while developing.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
