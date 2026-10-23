"""
Phase 3 validation: CONTROL the camera from the PC.

Two capabilities, both over the Sony SDIO protocol (ported from libgphoto2
camera_sony_capture / ptp_sony_setdevicecontrolvalueb and SonyAlphaUSB
ModifyISO / capture):

  1. Change a setting -> SDIO_ControlDevice (0x9207). Demo nudges ISO.
  2. Take a photo      -> SDIO_ControlDevice (0x9207) half/full shutter press.

IMPORTANT: the A7 III is a Sony *mode 2* body, where ISO / f-stop / shutter are
changed by RELATIVE STEPS (notch up/down), not by writing an absolute value.
So this demo nudges ISO a few steps, confirms the camera followed, then nudges
it back. Pass --photo to also fire the shutter.

Usage:
  python phase3_control.py               # nudge ISO up a few steps, then restore
  python phase3_control.py --steps 3     # how many steps to nudge (sign = dir)
  python phase3_control.py --keep        # don't restore ISO afterwards
  python phase3_control.py --photo       # ALSO take a real photo (shutter fires!)
  python phase3_control.py -v            # verbose PTP containers
"""

import argparse
import sys
import time

from ptp_usb import (
    PtpUsb, SONY_VID, DTC_INT32,
    DPC_SONY_ISO,
)

SONY_ISO_MASK = 0x00FFFFFF


def iso_str(raw):
    if raw is None:
        return "--"
    base = raw & SONY_ISO_MASK
    return "Auto" if base == SONY_ISO_MASK else str(base)


def read_iso_raw(ptp):
    props = ptp.sony_get_all_props()
    dpd = props.get(DPC_SONY_ISO)
    return None if dpd is None else dpd.current_value


def nudge_iso(ptp, steps, original, tries=12, delay=0.3):
    """Send a relative ISO step and wait until the reported ISO changes.
    Returns the new raw ISO (or the last read if it never moved)."""
    ptp.sony_control(DPC_SONY_ISO, steps, DTC_INT32)
    last = original
    for _ in range(tries):
        time.sleep(delay)
        last = read_iso_raw(ptp)
        if last != original:
            return last
    return last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vid", type=lambda x: int(x, 0), default=SONY_VID)
    ap.add_argument("--pid", type=lambda x: int(x, 0), default=None)
    ap.add_argument("--steps", type=int, default=3,
                    help="relative ISO steps to nudge (sign chooses direction)")
    ap.add_argument("--keep", action="store_true",
                    help="do not restore the original ISO afterwards")
    ap.add_argument("--photo", action="store_true",
                    help="ALSO take a real photo (the shutter WILL fire)")
    ap.add_argument("-v", "--verbose", action="store_true")
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
        ptp.sony_handshake()

        original = read_iso_raw(ptp)
        if original is None:
            print("ISO property not found; cannot run the control demo.")
            sys.exit(1)
        print(f"\nCurrent ISO: {iso_str(original)}")

        # ---- NUDGE ISO ----------------------------------------------------
        steps = args.steps
        print(f"\n>> Nudging ISO by {steps:+d} step(s) ...")
        new_raw = nudge_iso(ptp, steps, original)
        if new_raw == original:
            # maybe we're at a limit in that direction; try the other way
            print("   No change; trying the opposite direction ...")
            steps = -steps
            new_raw = nudge_iso(ptp, steps, original)

        changed = new_raw != original
        print(f"   Camera now reports ISO: {iso_str(new_raw)}")
        print("   -> ISO CHANGE " + ("CONFIRMED" if changed else "NOT confirmed"))
        print("   (watch the camera screen — the ISO value should have moved)")

        # ---- restore ------------------------------------------------------
        if changed and not args.keep:
            print(f"\n>> Restoring ISO by {-steps:+d} step(s) ...")
            back = nudge_iso(ptp, -steps, new_raw)
            print(f"   ISO back to: {iso_str(back)}"
                  + ("  (restored)" if back == original else "  (approx)"))

        # ---- optional photo ----------------------------------------------
        photo_sent = False
        if args.photo:
            print("\n>> Taking a photo (shutter firing NOW) ...")
            ptp.capture_photo(hold_seconds=1.0)
            print("   Shutter sequence sent — the camera should have clicked.")
            photo_sent = True

        # ---- verdict ------------------------------------------------------
        parts = ["ISO control " + ("PASS" if changed else "FAIL")]
        if photo_sent:
            parts.append("photo trigger sent")
        print("\nRESULT:", "; ".join(parts))

    finally:
        ptp.close_session()
        ptp.close()


if __name__ == "__main__":
    main()
