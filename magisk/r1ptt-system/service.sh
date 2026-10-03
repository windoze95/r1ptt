#!/system/bin/sh
# r1ptt-system: power and UI defaults for a push-to-talk R1, re-applied after every boot.
# Output goes to service.log next to this file.
MODDIR=${0%/*}

(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 5
  {
    echo "== boot $(date)"

    # Give the 2.9" screen back: drop the keyboard's own back / IME-switch strip.
    cmd overlay fabricate --target android --name R1PttImeNavBar android:bool/config_imeDrawsImeNavBar 0x12 0x0
    cmd overlay enable com.android.shell:R1PttImeNavBar

    # Airplane mode never touches Wi-Fi; the app owns Wi-Fi's idle cut and keeps the modem off.
    settings put global airplane_mode_radios cell,bluetooth,nfc,wimax,uwb

    # No background scanning, no location, no Bluetooth.
    settings put global wifi_scan_always_enabled 0
    settings put global ble_scan_always_enabled 0
    settings put global wifi_wakeup_enabled 0
    settings put global network_recommendations_enabled 0
    settings put global mobile_data_always_on 0
    settings put secure location_mode 0
    cmd bluetooth_manager disable

    # Nothing lights the screen except the button.
    settings put secure doze_enabled 0
    settings put secure doze_always_on 0
    settings put secure doze_pick_up_gesture 0
    settings put secure doze_tap_gesture 0
    settings put secure wake_gesture_enabled 0
    settings put global stay_on_while_plugged_in 0
    settings put system screen_brightness_mode 0

    # Doze sooner: light idle right after screen-off, deep idle 30 s later. Android 12+ reads
    # these from DeviceConfig; the global setting covers older builds.
    for kv in light_after_inactive_to=0 inactive_to=30000 sensing_to=0 locating_to=0 \
              motion_inactive_to=0 idle_after_inactive_to=0; do
      device_config put device_idle "${kv%%=*}" "${kv#*=}"
    done
    settings put global device_idle_constants \
      light_after_inactive_to=0,inactive_to=30000,sensing_to=0,locating_to=0,motion_inactive_to=0,idle_after_inactive_to=0

    # Shorter animations: less GPU time per screen change on the A53 cores.
    settings put global window_animation_scale 0.5
    settings put global transition_animation_scale 0.5
    settings put global animator_duration_scale 0.5

    # Record which keylayout won, to debug the button remap.
    dumpsys input | grep -A4 'mtk-kpd' | grep -i 'KeyLayoutFile'
  } >>"$MODDIR/service.log" 2>&1
) &
