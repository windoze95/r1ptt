#!/system/bin/sh
# Undo service.sh's settings when the module is removed.
cmd overlay disable com.android.shell:R1PttImeNavBar
settings delete global airplane_mode_radios
settings delete global device_idle_constants
for k in light_after_inactive_to inactive_to sensing_to locating_to motion_inactive_to idle_after_inactive_to; do
  device_config delete device_idle "$k"
done
settings put global window_animation_scale 1
settings put global transition_animation_scale 1
settings put global animator_duration_scale 1
