# Android reads keylayouts from /product, /system_ext, /odm and /vendor before /system. If this
# device ships its own mtk-kpd.kl in one of those, overlay that copy too, or ours is ignored.
KL=system/usr/keylayout/mtk-kpd.kl
for part in product system_ext vendor odm; do
  if [ -f "/$part/usr/keylayout/mtk-kpd.kl" ]; then
    mkdir -p "$MODPATH/system/$part/usr/keylayout"
    cp "$MODPATH/$KL" "$MODPATH/system/$part/usr/keylayout/"
    ui_print "- also overriding /$part/usr/keylayout/mtk-kpd.kl"
  fi
done
