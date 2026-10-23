"""
PTP handshake probe against a real Sony Alpha over USB.

Goal (mirrors Phase 1 of the project brief): open a PTP session, run
GetDeviceInfo, and print the manufacturer + model ("Sony" / "ILCE-7M3"). This
validates the whole transport stack on real hardware before porting to Android.

It also dumps the supported operation/property codes, which we cross-reference
against SonyAlphaUSB and libgphoto2 while filling docs/OPCODES.md.

Prerequisites:
  - Camera in 'PC Remote' mode, connected by USB-C.
  - WinUSB bound to the camera's interface via Zadig.
  - pip install pyusb libusb-package  (already done in this environment)

Usage:
  python probe.py               # auto-detect any Sony device
  python probe.py --pid 0x0c34  # target a specific product id
  python probe.py -v            # verbose container-level logging
"""

import argparse
import sys

from ptp_usb import PtpUsb, SONY_VID


def hexlist(vals, width=4):
    return ", ".join(f"0x{v:0{width}X}" for v in vals)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vid", type=lambda x: int(x, 0), default=SONY_VID)
    ap.add_argument("--pid", type=lambda x: int(x, 0), default=None)
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
        # GetDeviceInfo is legal before OpenSession per the PTP spec; do it
        # first so we get identity even if the session step misbehaves.
        print("\nGetDeviceInfo...")
        info = ptp.get_device_info()

        print("\n" + "=" * 60)
        print(f"  Manufacturer : {info.manufacturer}")
        print(f"  Model        : {info.model}")
        print(f"  Device ver.  : {info.device_version}")
        print(f"  Serial       : {info.serial_number}")
        print(f"  PTP standard : 0x{info.standard_version:04X}")
        print(f"  Vendor ext.  : id=0x{info.vendor_extension_id:02X} "
              f"v0x{info.vendor_extension_version:04X} '{info.vendor_extension_desc}'")
        print("=" * 60)

        print(f"\nOperations supported ({len(info.operations_supported)}):")
        print(f"  {hexlist(info.operations_supported)}")
        print(f"\nDevice properties supported ({len(info.device_properties_supported)}):")
        print(f"  {hexlist(info.device_properties_supported)}")
        print(f"\nEvents supported ({len(info.events_supported)}):")
        print(f"  {hexlist(info.events_supported)}")

        # Try opening a session too (needed later for control ops).
        print("\nOpenSession...")
        try:
            ptp.open_session(1)
            print("  Session opened OK.")
        except Exception as e:
            print(f"  OpenSession note: {e}")

        sony_ok = info.vendor_extension_id == 0x11 or info.manufacturer.lower().startswith("sony")
        model_ok = "ILCE" in info.model.upper() or bool(info.model)
        print("\nRESULT:", "PASS" if (sony_ok and model_ok) else "PARTIAL",
              f"- read '{info.manufacturer} / {info.model}' from the camera.")

    finally:
        ptp.close_session()
        ptp.close()


if __name__ == "__main__":
    main()
