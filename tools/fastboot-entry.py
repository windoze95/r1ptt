#!/usr/bin/env python3
"""Put a powered-off Rabbit R1 into fastboot mode through the MediaTek preloader.

The R1 has no volume keys, so the usual button combo doesn't exist. For a moment while it starts,
the preloader exposes a USB serial port (VID:PID 0E8D:2000) and prints READY; answering FASTBOOT
makes the bootloader stop in fastboot mode. (Same handshake as r1_escape's mtkbootcmd.py.)

    uv run --with pyserial tools/fastboot-entry.py      # then plug in the powered-off R1

Rabbit's WebUSB flash tool (https://rabbit-hmi-oss.github.io/flashing/) does the same thing from
Chrome via its "Enter Fastboot Mode" button.
"""
import sys
import time

import serial
from serial.tools import list_ports

PRELOADER = "0E8D:2000"


def preloader_port():
    for p in list_ports.comports():
        if PRELOADER in (p.hwid or "").upper():
            return p.device
    return None


def main() -> int:
    mode = (sys.argv[1] if len(sys.argv) > 1 else "FASTBOOT").encode()
    print("Waiting for the R1's preloader. Plug in the powered-off R1 now (Ctrl-C to quit).")
    deadline = time.time() + 600  # time enough to power off and replug
    while time.time() < deadline:
        port = preloader_port()
        if port:
            try:
                with serial.Serial(port, 115200, timeout=2) as s:
                    if s.read(5) == b"READY":
                        s.write(mode)
                        print(f"Sent {mode.decode()} on {port}. `fastboot devices` should list the R1 shortly.")
                        return 0
            except (serial.SerialException, OSError):
                pass  # the port vanishes fast if we missed the window; keep watching
        time.sleep(0.05)  # the window is about a second, so poll quickly
    print("Timed out waiting for the preloader.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
