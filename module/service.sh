#!/system/bin/sh
# Keeps the Hilight Link root helper running. The helper lives inside the app's APK.
PKG=dev.kevtr.hilightlink

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done

while true; do
  APK=$(pm path "$PKG" 2>/dev/null | head -n 1 | cut -d: -f2)
  if [ -z "$APK" ]; then
    sleep 60
    continue
  fi
  # Mask 1 pins the helper to CPU 0, an efficiency core, so it never wakes a big core.
  taskset 1 app_process -Djava.class.path="$APK" /system/bin --nice-name=hilightlinkd \
    "$PKG.daemon.Main" "$PKG" >/dev/null 2>&1
  # Exit code 3: a helper started by the app already holds the port.
  if [ $? -eq 3 ]; then sleep 60; else sleep 3; fi
done
