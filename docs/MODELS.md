# Model × capability × quirks matrix

Record here what each validated Sony body actually does.

There are three deliberately separate support levels:

1. **Negotiated** — the connected body advertises the required operations and
   controls, so the app enables only that surface.
2. **Replay-tested** — a recorded/synthetic PTP transcript exercises the same
   parser, profile and framing code without a camera attached.
3. **Hardware-validated** — the complete path was run on the named body and
   firmware. This is evidence, not a model allow-list.

**This table does not drive the code.** The app negotiates a [`SonyProfile`]
(../app/src/main/java/com/sonycam/monitor/sony/SonyProfile.kt) at connect time
from the operations and descriptors the camera itself reports, so an untested
model works without an entry here — see [COMPATIBILITY.md](COMPATIBILITY.md).
What belongs below is only what the protocol cannot tell us: firmware behaviours
we measured, and camera menu settings the user has to get right.

## ILCE-7M3 (A7 III) — validated 2026-07-13

| Aspect | Value |
|--------|-------|
| USB PID (PC Remote) | 054C:0C34 (control/PTP). Also seen: 0C02 (MTP/MSC), 0994 (transient after capture) |
| PTP interface | #0, class 0x06 still-image, bulk-IN 0x81 / bulk-OUT 0x02 / interrupt 0x83 |
| Vendor ext | 0x11 "Sony PTP Extensions", version 0x0064 |
| **Sony protocol mode** | **2** (v0x0c8) — pre-2020 body |
| Read settings | ✅ SDIO handshake + 0x9209 GetAllExtDevicePropInfo (60 props) |
| Change ISO/f-stop/EV | ✅ **relative steps** via 0x9207 (INT32/INT16). Absolute writes are ignored (mode 2). |
| Take photo | ✅ 0x9207 shutter half/full press (0xD2C1 / 0xD2C2, UINT16 2/1) |

### Quirks confirmed on this body
1. **ISO/aperture/EV are notch-only** (relative steps via 0x9207); absolute
   `SetExtDevicePropValue` (0x9205) returns OK but is silently ignored.
   See docs/OPCODES.md and memory `sony-mode2-relative-steps`.
2. **`StillImageStoreDestination` (0xD222) is read-only over PTP.** It reports
   the current save target (1 = PC Only, 17 = PC+Camera) but writes via 0x9205
   *and* 0x9207 are ignored — Sony locks it to the camera menu during PC Remote
   (`MENU → Setup → PC Remote Settings → Still Img. Save Dest.`). If it is set to
   "PC Only", captured frames are NOT written to the card; the host must
   download them (0xD215 ObjectInMemory + GetObject) or the shot is lost.
3. First `GetAllExtDevicePropInfo` right after handshake may return placeholder
   values (0 / 0xFFFFFFFF); read again after ~1s (warmup).
4. Camera keeps the PTP session open across USB re-opens (OpenSession then
   returns Session_Already_Open 0x201E — treat as success).
5. Zadig/WinUSB must not be held open by another process or bulk writes hang.

## ILCE-6700 (a6700) — replay-tested; hardware validation pending

| Aspect | Negotiated route |
|--------|------------------|
| USB PID in PC Remote | 054C:0E78 (reference only; code does not route by PID) |
| Sony protocol | mode 3 / `0x12C` |
| Session | `0x9210(sessionId, Remote+Transfer=2)` when advertised; otherwise standard `0x1002` |
| Property format | 2024 descriptor layout, including the secondary selectable enum |
| ISO/f-stop/shutter/EV | Absolute writes; value and wire width selected by the negotiated descriptor/control rules |
| Take photo | Combined S1/S2 `0xD2E6` only when advertised; `0xD2C7` is not used as a normal shutter |
| Save destination | Read D222 exactly: 1=host, 16=card, 17=host+card. v0.5 requests 17 on mode 3 and fires only after readback confirms a card destination. |
| Movie REC | `0xD2C8` only when advertised; the A7 III-only dial restriction is not imposed on mode-3 bodies |
| Live view | Enable `0xD313` and post-view `0xD312` when advertised, then probe `0xFFFFC002`; disable only Live View after conclusive/repeated failure |
| Automated evidence | Full mode-3 handshake/profile replay plus parser and PTP byte-stream tests |
| Physical evidence in SonyMonitor | **Pending** |

Camera setup for the physical pass:

```text
MENU → Setup → USB → USB Connection Mode → Remote Shoot (PC Remote)
MENU → Network → Cnct./PC Remote → PC Remote Function → PC Remote: On
MENU → Network → Cnct./PC Remote → Remote Shoot Setting → Still Img. Save Dest. → Dest.+Camera
```

Use current camera firmware. One complete a6700 pass is still required before
marking this row hardware-validated: connect, 30 seconds of Live View, change
each exposure control allowed by the current mode, take and download a still,
and start/stop REC in a movie mode. The app's `DIAG` export records the runtime
capability profile and raw `0x9209` property blob if any step differs.

## Why this does not require one of every Sony

SonyMonitor routes by advertised PTP operations, controls, data types,
`GetSet`, `IsEnabled`, and enum/range data — never by the model string. The A7
III covers the classic mode-2 path and the a6700 covers the modern mode-3 path.
Other models sharing either protocol family use the same tested code paths;
additional bodies are useful for certification and firmware quirks, not for
adding another model-specific driver.
