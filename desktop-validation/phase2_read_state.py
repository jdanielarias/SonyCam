"""
Phase 2 validation: read live camera state (ISO, aperture, shutter speed, ...)
from a real Sony Alpha over USB/PTP.

Runs the standard Sony SDIO handshake (SDIO_Connect 1/2, GetExtDeviceInfo,
SDIO_Connect 3) then SDIO_GetAllExtDevicePropInfo, and prints the decoded
current values. This is the Phase 2 "done" criterion from the project brief:
show ISO / aperture / shutter speed read from the camera.

Value decoders are ported from libgphoto2 (config.c) and SonyAlphaUSB
(CameraSetting.cs) — see docs/OPCODES.md for citations.

Usage:
  python phase2_read_state.py            # one snapshot
  python phase2_read_state.py -v         # verbose PTP containers
  python phase2_read_state.py --watch    # refresh ~2x/second until Ctrl+C
"""

import argparse
import sys
import time

from ptp_usb import (
    PtpUsb, SONY_VID,
    DPC_FNUMBER, DPC_EXPOSURE_PROGRAM_MODE, DPC_EXPOSURE_BIAS,
    DPC_WHITE_BALANCE, DPC_FOCUS_MODE,
    DPC_SONY_SHUTTER_SPEED, DPC_SONY_ISO,
)

SONY_ISO_MASK = 0x00FFFFFF


def decode_iso(raw: int) -> str:
    base = raw & SONY_ISO_MASK
    s = "Auto ISO" if base == SONY_ISO_MASK else str(base)
    nr = raw >> 24
    if nr:
        s += " +MFNR" + ("+" if nr == 2 else "")
    return s


def decode_fnumber(raw: int) -> str:
    return f"f/{raw / 100:g}"


def decode_shutter(raw: int) -> str:
    # uint32: numerator in the high 16 bits, denominator in the low 16 bits.
    num = (raw >> 16) & 0xFFFF
    den = raw & 0xFFFF
    if num == 0 or den == 0:
        return "BULB"
    if num == 1:
        return f"1/{den}"
    return f'{num / den:g}"'


def decode_ev(raw: int) -> str:
    if raw == 0:
        return "+/-0.0"
    return f"{raw / 1000:+g}"


def _row(label: str, code: int, props: dict, decoder, raw_fmt="{}"):
    p = props.get(code)
    if p is None or p.current_value is None:
        print(f"  {label:<14}: --")
        return
    raw = p.current_value
    print(f"  {label:<14}: {decoder(raw):<12}  (raw {raw_fmt.format(raw)})")


def show(props: dict):
    _row("ISO", DPC_SONY_ISO, props, decode_iso, "0x{:08X}")
    _row("Aperture", DPC_FNUMBER, props, decode_fnumber, "{}")
    _row("Shutter speed", DPC_SONY_SHUTTER_SPEED, props, decode_shutter, "0x{:08X}")
    _row("Exposure comp", DPC_EXPOSURE_BIAS, props, decode_ev, "{}")
    _row("Exposure mode", DPC_EXPOSURE_PROGRAM_MODE, props, lambda v: f"0x{v:X}", "{}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vid", type=lambda x: int(x, 0), default=SONY_VID)
    ap.add_argument("--pid", type=lambda x: int(x, 0), default=None)
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("--watch", action="store_true",
                    help="continuously refresh until Ctrl+C")
    args = ap.parse_args()

    ptp = PtpUsb(verbose=args.verbose)
    print("Opening USB device...")
    try:
        ptp.open(vid=args.vid, pid=args.pid)
    except Exception as e:
        print(f"\nFAILED to open device:\n  {e}")
        sys.exit(1)

    try:
        info = ptp.get_device_info()
        print(f"Connected: {info.manufacturer} / {info.model}")

        ptp.open_session(1)
        print("Running Sony SDIO handshake...")
        props = ptp.sony_handshake()
        print(f"Handshake OK. {len(props)} device properties reported "
              f"(sony_mode_ver={ptp.sony_mode_ver}).\n")

        print("=" * 48)
        show(props)
        print("=" * 48)

        got = [c for c in (DPC_SONY_ISO, DPC_FNUMBER, DPC_SONY_SHUTTER_SPEED)
               if c in props]
        print("\nRESULT:", "PASS" if len(got) == 3 else "PARTIAL",
              f"- read {len(got)}/3 of ISO/aperture/shutter from the camera.")

        if args.watch:
            print("\nWatching (Ctrl+C to stop). Change a dial on the camera...")
            try:
                while True:
                    time.sleep(0.5)
                    props = ptp.sony_get_all_props()
                    print("\033[H\033[J", end="")  # clear screen
                    print("=" * 48)
                    show(props)
                    print("=" * 48)
            except KeyboardInterrupt:
                print("\nStopped.")

    finally:
        ptp.close_session()
        ptp.close()


if __name__ == "__main__":
    main()
