# Runs under Magisk, KernelSU and APatch. Installs the bundled app so the module and the
# helper inside the APK always match.

if [ "$BOOTMODE" != "true" ]; then
  abort "! Install from the Magisk, KernelSU or APatch app, not from recovery"
fi
if [ "$API" -lt 33 ]; then
  abort "! Needs Android 13 or newer"
fi
if ! service check android.hardware.light.ILights/default 2>/dev/null | grep -q ": found"; then
  ui_print "! This phone has no lights service, so the Hilight LEDs may not work here"
fi

ui_print "- Installing the Hilight Link app"
cp "$MODPATH/HilightLink.apk" /data/local/tmp/HilightLink.apk
if pm install -r /data/local/tmp/HilightLink.apk >/dev/null 2>&1; then
  ui_print "- App installed"
  # Compile the whole app ahead of time so it starts fast and never spends battery on the JIT.
  cmd package compile -m speed -f dev.kevtr.hilightlink >/dev/null 2>&1 && ui_print "- App compiled ahead of time"
  # The app only sees calls and notifications while its listener is connected, so connect it now
  # and keep battery optimisation from putting it to sleep. Both can be undone in Settings.
  cmd notification allow_listener dev.kevtr.hilightlink/dev.kevtr.hilightlink.LedListenerService >/dev/null 2>&1 &&
    ui_print "- Notification access granted"
  cmd deviceidle whitelist +dev.kevtr.hilightlink >/dev/null 2>&1 &&
    ui_print "- Exempted from battery optimisation"
else
  ui_print "! Could not install the app; install HilightLink.apk by hand"
fi
rm -f /data/local/tmp/HilightLink.apk
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

if [ "$KSU" = "true" ]; then
  ROOT_APP="the KernelSU manager"
elif [ "$APATCH" = "true" ]; then
  ROOT_APP="the APatch manager"
else
  ROOT_APP="Magisk (it asks the first time)"
fi
ui_print "- Reboot, open Hilight Link and allow root for it in $ROOT_APP"
