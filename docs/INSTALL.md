# Installing r1ptt on a stock R1

This takes about an hour of hands-on time once Rabbit has approved developer mode.

**Know the risks before you start:**
- Unlocking permanently voids the R1's warranty.
- A bad flash on a MediaTek device can leave it unbootable. Rabbit's flash tool can always put
  stock rabbitOS back.

## What you need

- A Mac with this repo, plus:
  - `adb` and `fastboot` (`brew install android-platform-tools`)
  - `jq`
  - `uv` (for `tools/fastboot-entry.py`)
  - Google Chrome (for Rabbit's flash tool)
- A USB-C data cable.
- An OpenAI API key, or an OpenClaw or Hermes server (see `server/`).
- Downloads, all saved into `firmware/` (git-ignores it):
  - **Stock rabbitOS v0.8.293**: `rabbit_OS_v0.8.293.zip` from
    https://github.com/rabbit-hmi-oss/firmware/releases. Unzip it to `firmware/stock/`.
  - **LineageOS 21 GSI**: `lineage-21.0-YYYYMMDD-UNOFFICIAL-arm64_bvN.img.gz` from
    https://sourceforge.net/projects/andyyan-gsi/files/ (folder `lineage-21-pre-qpr2-td`; the
    vanilla `arm64_bvN` file, not `bgN`, `a64` or `vndklite`).
  - **Magisk**: `Magisk-v30.x.apk` from https://github.com/topjohnwu/Magisk/releases.

## 0. Check the backend from your Mac

```sh
cp tools/r1ptt.example.json tools/r1ptt.json      # put your OpenAI key in; delete the providers you don't use
tools/build.sh                                     # builds out/r1ptt.apk and out/r1ptt-system.zip
tools/smoke-test.sh --play                         # speech-to-text, chat, memory, text-to-speech
```

If the smoke test fails, fix the config before touching the R1. For example, if the model name is
rejected, change `providers.openai.model`.

## 1. Developer mode and bootloader unlock (Rabbit's official route)

1. In OS3, go to **settings → r1**. Developer mode only appears once the R1 is linked to your
   account. Type the warranty acknowledgment, enter the R1's IMEI (on the R1: settings → about), and
   click **void warranty and enable developer mode**. Each R1 needs its own request.
2. With the R1 on and online, go to **settings → r1 → device modification** and choose **unlock**.
   This isn't an OTA: Rabbit's servers only switch on the R1's permission to be unlocked. Nothing is
   unlocked yet.
3. Enter fastboot mode with the R1 powered off. You can use either route:
   - **Chrome:** open https://rabbit-hmi-oss.github.io/flashing/ and click "Enter Fastboot Mode".
     Plug the R1 in, and select "MT65xx Preloader" within about 1.5 seconds.
   - **Terminal:** run `uv run --with pyserial tools/fastboot-entry.py`, then plug the R1 in.
4. Check that the permission arrived: `fastboot flashing get_unlock_ability` should print `1`.
   - If it prints `0`, reboot the R1 (`fastboot reboot`), let it sit online for a minute, and repeat
     step 2.
5. Run `fastboot flashing unlock`. **This wipes the R1.** If it asks for confirmation, confirm with
   the side button.
6. `fastboot getvar unlocked` should now say `yes`.

Optional, but cheap insurance: back up the partitions that hold the IMEI and radio calibration.
Nothing in this guide writes to them, but MediaTek devices are notorious for losing them.

```sh
uv tool install git+https://github.com/bkerler/mtkclient     # needs libusb: brew install libusb
mtk r nvram,nvdata,nvcfg,protect1,protect2,proinfo nvram.bin,nvdata.bin,nvcfg.bin,protect1.bin,protect2.bin,proinfo.bin
```

## 2. Stock baseline

This gives the kernel, vendor and system a known starting point. Use either route:

- **Preferred:** Rabbit's flash tool → **Flash Stock ROM**.
- **Command line:** with the R1 in fastboot, run `tools/flash-baseline.sh firmware/stock`.

Use Rabbit's tool if the R1 has taken OTA updates. Check with
`fastboot getvar version-bootloader`: if the date is newer than May 2025, the R1 is ahead of
v0.8.293. (One R1 checked on 2026-10-03 reported `k65v1_64_bsp-…-20250905…`.) Rabbit's tool writes
the full matching v0.8.293 set, including the power-management and modem firmware. The script
deliberately leaves the bootloader chain alone, so on an updated R1 it would pair newer firmware
with the older kernel.

Let it boot into rabbitOS once to confirm the base works, then power it off.

## 3. LineageOS GSI

```sh
uv run --with pyserial tools/fastboot-entry.py     # then plug in the powered-off R1
tools/flash-gsi.sh firmware/lineage-21.0-*-arm64_bvN.img.gz firmware/stock
```

The script does four things:
- turns verified boot off;
- removes Rabbit's `product` partition (its launcher and services);
- writes the GSI to `system_a`;
- wipes data and reboots.

The first boot takes a few minutes. Then, on the R1:
1. Go through setup and connect to Wi-Fi.
2. Go to Settings → About phone and tap **Build number** seven times.
3. Go to Settings → System → Developer options and turn on **USB debugging**. Accept the prompt
   when you plug into the Mac.

If everything is too big to tap, run `adb shell wm density 190`.

## 4. Root with Magisk

```sh
adb install firmware/Magisk-v30.*.apk
adb push firmware/stock/*/boot.img /sdcard/Download/boot.img
```

On the R1, open Magisk, go to **Install → Select and Patch a File**, and pick `Download/boot.img`.
Then:

```sh
adb pull "$(adb shell ls /sdcard/Download/magisk_patched-*.img | tr -d '\r')" out/
tools/flash-boot.sh out/magisk_patched-*.img
```

After it reboots, open Magisk; it should show as installed. The first `su` from adb pops a prompt
on the R1; allow it for **Shell**.

## 5. Provision

```sh
tools/provision.sh --config tools/r1ptt.json
```

The script makes these changes:
- Installs the Magisk module (button remap and power defaults).
- Installs the app with its permissions.
- Grants the app root.
- Makes the app the home screen.
- Turns the lock screen off.
- Sets a 15 s screen timeout and low brightness.
- Disables the bloat in `tools/debloat.txt`.
- Pushes your config, including API keys.
- Reboots.

It's safe to re-run, e.g. after editing the config or rebuilding. `--undo-debloat` re-enables the
disabled apps.

## 6. Check that it works

Work through these with the R1 on USB:

1. **Hold the button with the screen on.** The status line should show the listening state, then
   the transcript, then the reply streaming in and being spoken. The screen turns off about 15 s
   later.
2. **The press that wakes the screen.** Let the screen go off and wait a minute (or run
   `adb shell dumpsys deviceidle force-idle`). Then hold the button and immediately say "testing
   one two three". The transcript must start with "testing".
   - To inspect the recording, set `"saveClips": true` and pull it from
     `/sdcard/Android/data/dev.r1ptt/files/clips/`.
3. **Dictation.** Tap the text field so the keyboard opens. Hold, speak and release: the text
   lands at the cursor and nothing is sent. A tap of the button then sends it.
4. **Taps.**
   - A tap with the screen on turns it off right away.
   - A tap while it's speaking stops the speech.
   - A double-tap shows "New conversation".
5. **Memory.** Say "My name is Sam", then ask "What's my name?" and get the right answer.
6. **Wi-Fi cut.** After 3 minutes with the screen off, `adb shell cmd wifi status` shows Wi-Fi
   disabled. The next hold still works: it reconnects while you talk.
7. **Battery.** Unplug the R1 overnight, then plug it in and run `tools/battery-report.sh`. See
   [BATTERY.md](BATTERY.md).

## Troubleshooting

**The button does nothing.**
1. Check that the app's root reader is up: `adb logcat -s r1ptt` should show "side button reader
   up on /dev/input/eventN".
2. Run `adb shell su -c cat /proc/bus/input/devices`. The button should be listed as `mtk-kpd`; if
   it has another name, set `"buttonDevice"` in the config.
3. Run `adb shell dumpsys input | grep -A4 mtk-kpd`. `KeyLayoutFile` should be our `mtk-kpd.kl`.
   The module's own log is `/data/adb/modules/r1ptt_system/service.log`.

**It boot-loops after installing the module.**
There are no volume keys, so Magisk's safe-mode combo is unavailable. Instead, while it loops, run:
```sh
adb wait-for-device shell magisk --remove-modules
```

**Back to stock.** Use Rabbit's flash tool → Flash Stock ROM. It works from any state that can
still reach the preloader.

**Mic or speech errors.** The status line shows the server's reason. "Bad API key" means fix the
key and re-run provision with `--config`.
