"""
List USB devices and highlight any Sony camera (VID 0x054C).

Run this FIRST, with the camera connected and set to 'PC Remote' mode. It shows
the VID/PID, interface classes and endpoints so we know:
  1. that the camera enumerates at all,
  2. which product ID this specific body/firmware exposes in PC Remote mode,
  3. which interface Zadig must bind WinUSB to.

Enumeration works even before Zadig; opening the device for transfers does not.
"""

import usb.core
import usb.util
import libusb_package

SONY_VID = 0x054C

CLASS_NAMES = {
    0x00: "per-interface", 0x01: "audio", 0x03: "HID", 0x06: "still-image/PTP",
    0x07: "printer", 0x08: "mass-storage", 0x09: "hub", 0x0A: "CDC-data",
    0x0E: "video/UVC", 0xEF: "misc", 0xFF: "vendor-specific",
}


def cls(n: int) -> str:
    return f"0x{n:02X} ({CLASS_NAMES.get(n, '?')})"


def describe(dev) -> None:
    is_sony = dev.idVendor == SONY_VID
    mark = "  <<< SONY" if is_sony else ""
    print(f"\nUSB {dev.idVendor:04X}:{dev.idProduct:04X}  "
          f"bus={dev.bus} addr={dev.address}  devClass={cls(dev.bDeviceClass)}{mark}")

    # Product/manufacturer strings often require the device to be openable.
    for label, idx in (("Manufacturer", dev.iManufacturer),
                       ("Product", dev.iProduct),
                       ("Serial", dev.iSerialNumber)):
        if idx:
            try:
                print(f"    {label}: {usb.util.get_string(dev, idx)}")
            except Exception:
                pass  # needs an accessible driver

    try:
        cfg = dev.get_active_configuration()
    except Exception as e:
        print(f"    (could not read config descriptor: {e})")
        return

    for intf in cfg:
        eps = []
        for ep in intf:
            direction = "IN" if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN else "OUT"
            etype = {0: "control", 1: "iso", 2: "bulk", 3: "interrupt"}[usb.util.endpoint_type(ep.bmAttributes)]
            eps.append(f"0x{ep.bEndpointAddress:02X}/{etype}-{direction}")
        print(f"    intf #{intf.bInterfaceNumber} alt {intf.bAlternateSetting}  "
              f"class={cls(intf.bInterfaceClass)} sub=0x{intf.bInterfaceSubClass:02X} "
              f"proto=0x{intf.bInterfaceProtocol:02X}")
        if eps:
            print(f"        endpoints: {', '.join(eps)}")


def main():
    backend = libusb_package.get_libusb1_backend()
    devices = list(usb.core.find(find_all=True, backend=backend))
    if not devices:
        print("No USB devices enumerated. Is libusb working?")
        return

    print(f"Found {len(devices)} USB device(s):")
    sony = []
    for dev in devices:
        describe(dev)
        if dev.idVendor == SONY_VID:
            sony.append(dev)

    print("\n" + "=" * 60)
    if sony:
        print(f"Detected {len(sony)} Sony device(s):")
        for d in sony:
            print(f"  - VID:PID = 054C:{d.idProduct:04X}")
        print("\nNext step: run Zadig, select this Sony device (the still-image /")
        print("vendor interface with the bulk endpoints), and install the 'WinUSB'")
        print("driver on it. Then run:  python probe.py")
    else:
        print("No Sony device found. Checklist:")
        print("  1. Camera powered on and connected by USB-C.")
        print("  2. Menu -> USB -> USB Connection Mode -> PC Remote.")
        print("  3. Try a different cable/port (must be a data cable).")


if __name__ == "__main__":
    main()
