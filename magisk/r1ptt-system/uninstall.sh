#!/system/bin/sh
# Undo service.sh's settings when the module is removed. Output goes to /dev/null for the same
# reason as in service.sh (system services can't be handed files under /data/adb).
q() { "$@" </dev/null >/dev/null 2>&1; }
q cmd overlay disable com.android.shell:R1PttImeNavBar
q settings delete global airplane_mode_radios
q settings delete global device_idle_constants
for k in light_after_inactive_to inactive_to sensing_to locating_to motion_inactive_to idle_after_inactive_to; do
  q device_config delete device_idle "$k"
done
q settings put global window_animation_scale 1
q settings put global transition_animation_scale 1
q settings put global animator_duration_scale 1
