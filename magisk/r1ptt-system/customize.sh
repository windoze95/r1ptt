# Android reads keylayouts from /product, /system_ext, /odm and /vendor before /system. If this
# device ships its own copy of one of ours in one of those, overlay that copy too, or ours is ignored.
for kl in mtk-kpd.kl och1970_holl_key.kl; do
  for part in product system_ext vendor odm; do
    if [ -f "/$part/usr/keylayout/$kl" ]; then
      mkdir -p "$MODPATH/system/$part/usr/keylayout"
      cp "$MODPATH/system/usr/keylayout/$kl" "$MODPATH/system/$part/usr/keylayout/"
      ui_print "- also overriding /$part/usr/keylayout/$kl"
    fi
  done
done
