# Desktop PTP validation harness

Validates the Sony PTP handshake and opcodes against a **real camera connected
to this Windows laptop**, before porting the logic to the Android app.

Everything software-side is already installed in this environment:
`python`, `pyusb`, `libusb-package`. `zadig.exe` is bundled in this folder.

## One-time setup

1. **Put the camera in PC Remote mode.**
   On the A7 III: `MENU → (Setup) → USB Connection Mode → PC Remote`.
   (Also check `USB LUN Setting = Single` if PC Remote misbehaves.)
   Connect it to the laptop with a **USB-C data cable**.

2. **Confirm it enumerates.**
   ```
   python scan_usb.py
   ```
   You should see a line marked `<<< SONY` with `054C:xxxx`. Note the PID.
   In PC Remote mode the A7 III typically shows as `054C:0C34` (or `096F`).
   If you instead see `054C:0C02`, the camera is probably still in **MTP /
   Mass Storage** mode — fix the USB mode in step 1 and re-run.

3. **Bind the WinUSB driver with Zadig** (so libusb can open the device).
   - Run `zadig.exe` **as Administrator**.
   - Menu `Options → List All Devices`.
   - In the dropdown pick the Sony camera (the `054C:xxxx` entry — the
     still-image / vendor interface, the one with bulk endpoints).
   - Set the target driver to **WinUSB** and click **Replace Driver**.

   > ⚠️ This replaces the camera's Windows driver. While WinUSB is bound,
   > Windows apps (Imaging Edge, Explorer/MTP) won't see the camera. To revert:
   > Device Manager → find the device → **Uninstall device** (tick "delete
   > driver software") or **Roll Back Driver**, then unplug/replug. This is
   > fully reversible and does nothing to the camera itself.

## Run the probe

```
python probe.py          # auto-detect the Sony device
python probe.py -v        # verbose, shows every PTP container
python probe.py --pid 0x0c34
```

Expected result (Phase 1 "done" criterion): it prints
`Sony / ILCE-7M3`, plus the lists of supported operation/property codes, which
we then cross-reference into `../docs/OPCODES.md`.

## Files

| File          | Purpose                                                    |
|---------------|------------------------------------------------------------|
| `ptp_usb.py`  | PTP-over-USB transport + Sony SDIO handshake/control layer |
| `scan_usb.py` | Enumerate USB, highlight Sony, show interfaces/endpoints   |
| `probe.py`    | Full handshake + GetDeviceInfo; prints model/manufacturer  |
| `zadig.exe`   | WinUSB driver installer (run once, see above)              |

## Validation scripts by phase

Run each from this folder with the camera in PC Remote mode and **Zadig closed**.

| Phase | Script | What it proves |
|-------|--------|----------------|
| 1 | `python probe.py` | Handshake + reads `Sony / ILCE-7M3` |
| 2 | `python phase2_read_state.py [--watch]` | Reads live ISO / aperture / shutter |
| 3 | `python phase3_control.py [--photo]` | Changes ISO (relative steps) + fires shutter |
| 4 | `python phase4_video.py [--seconds N]` | Starts/stops video recording |
| 5 | `python phase5_liveview.py [--view / --frames N]` | Live view: save a JPEG, measure fps, or open a live window |

Handy launchers (double-click, they keep the console open): `run_phase2.bat`
(live state text) and `run_liveview.bat` (live-view window). `--view` needs
Pillow (`pip install pillow`).

### Notes / gotchas (see ../docs/MODELS.md for the full list)
- **Zadig must be fully closed** or the first bulk write hangs (timeout).
- The A7 III is Sony **mode 2**: ISO/aperture/EV change by *relative steps*, not
  absolute values.
- To save stills to the card, set `Still Img. Save Dest. = PC+Camera` in the
  camera menu (read-only over PTP on this body).
- Phase 4 needs the physical mode dial in **MOVIE** (else state = UnableToRecord).
