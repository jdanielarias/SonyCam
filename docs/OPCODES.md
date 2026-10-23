# Sony PTP opcodes — confirmed against hardware

Table of PTP operation codes, events, and device properties as we confirm them.
Each row cites its source: either a **live probe** of a real camera (most
trustworthy) or a reference repo (`libgphoto2`, `SonyAlphaUSB`).

Vendor Extension ID: `0x11` ("Sony PTP Extensions"), confirmed on ILCE-7M3.

---

## Standard PTP operations (ISO 15740)

| Opcode  | Name               | Confirmed on | Notes |
|---------|--------------------|--------------|-------|
| 0x1001  | GetDeviceInfo      | A7 III       | Works before OpenSession. |
| 0x1002  | OpenSession        | A7 III       | Session id 1 OK. |
| 0x1003  | CloseSession       | A7 III       | |
| 0x1004  | GetStorageIDs      | A7 III (listed) | |
| 0x1005  | GetStorageInfo     | A7 III (listed) | |
| 0x1006  | GetNumObjects      | A7 III (listed) | |
| 0x1007  | GetObjectHandles   | A7 III (listed) | |
| 0x1008  | GetObjectInfo      | A7 III (listed) | |
| 0x1009  | GetObject          | A7 III (listed) | Needed to release card after capture (quirk #4). |
| 0x100A  | GetThumb           | A7 III (listed) | |
| 0x101B  | GetPartialObject   | A7 III (listed) | |

## Sony vendor operations (SDIO family — the one to build on)

All confirmed **present in the A7 III `OperationsSupported` list** by live probe
(2026-07-13). Semantics below cross-referenced from SonyAlphaUSB / libgphoto2;
mark each "verified" once we actually exercise it.

| Opcode  | Name (community)              | Purpose                                   | Status |
|---------|-------------------------------|-------------------------------------------|--------|
| 0x9201  | SDIO_Connect                  | Sony handshake step; call 1/2/3 to init.  | listed |
| 0x9202  | SDIO_GetExtDeviceInfo         | Get Sony ext device info + real prop list.| listed |
| 0x9205  | SDIO_SetExtDevicePropValue / SetControlDeviceA | Escritura absoluta de una propiedad. | listed |
| 0x9207  | SDIO_ControlDevice / SetControlDeviceB | Botones, controles variables y pasos relativos. | listed |
| 0x9209  | SDIO_GetAllExtDevicePropInfo  | Dump de propiedades, descriptores y valores actuales. | listed |
| 0x9210  | SDIO_OpenSession             | Sesión Sony con FunctionMode; usar si el cuerpo la anuncia. | negotiated |
| 0x9211  | SDIO_GetPartialLargeObject   | Lectura parcial de objetos grandes. | capability only |
| 0x9212  | SDIO_SetContentsTransferMode | Transferencia de contenidos en background. | capability only |
| 0x9215  | SDIO_GetDisplayStringList    | Etiquetas que la cámara asigna a valores. | capability only |

> **Key quirk (confirmed):** the standard `GetDeviceInfo` returns
> **0 device properties** on the A7 III. Sony exposes properties only *after*
> the SDIO handshake (0x9201 → 0x9202), then via 0x9209. This is expected, not
> a bug — Phase 2 implements this handshake.

## Sony events

| Event   | Name (community)     | Confirmed on | Notes |
|---------|----------------------|--------------|-------|
| 0xC201  | Sony_ObjectAdded     | A7 III       | |
| 0xC202  | Sony_ObjectRemoved   | A7 III       | |
| 0xC203  | Sony_PropertyChanged | A7 III       | Fires when a device prop value changes. |

## Device properties — read & decoded (Phase 2, verified on A7 III)

Read via the SDIO handshake (0x9201 phases 1/2/3 → 0x9202 → 0x9209), then
parsed with the Sony DPD layout below.

| Code   | Name              | DataType     | Decode | Verified value |
|--------|-------------------|--------------|--------|----------------|
| 0x5007 | FNumber           | UINT16 (4)   | `f/(raw/100)` | 560 → f/5.6 |
| 0xD20D | Sony ShutterSpeed | UINT32 (6)   | num=raw>>16, den=raw&0xFFFF → `num/den`; 0 → BULB | 0x00010028 → 1/40 |
| 0xD21E | Sony ISO          | UINT32 (6)   | base=raw&0xFFFFFF (0xFFFFFF=Auto); raw>>24 = MFNR | 3200 → ISO 3200 |
| 0x5010 | ExposureBias (EV) | INT16 (3)    | `raw/1000` | 0 → ±0.0 |
| 0x500E | ExposureProgram   | UINT16 (4)   | (mode enum, TBD) | 1 |

Sources: opcodes/handshake order = libgphoto2 `ptp.c` (`ptp_sony_sdioconnect`,
`ptp_sony_get_vendorpropcodes`) + `library.c` (init sequence, lines ~721-756);
DPD layout = `ptp-pack.c` `ptp_unpack_Sony_DPD`; ISO decode = `config.c`
`_stringify_Sony_ISO`; FNumber/Shutter decode = SonyAlphaUSB `CameraSetting.cs`.

**Sony DPD descriptor layout** (`ptp_unpack_Sony_DPD`):
```
u16 DevicePropCode | u16 DataType | u8 GetSet | u8 IsEnabled |
<DefaultValue: typed> | <CurrentValue: typed> | u8 FormFlag |
  FormFlag 1 (Range): min,max,step (each typed)
  FormFlag 2 (Enum) : u16 count, then count typed values,
                      optionally a second u16 count + selectable values
```
The 0x9209 blob starts with an 8-byte header (u32 entry-count, u32 zero).

**Confirmed quirk:** the A7 III is Sony protocol **mode 2** (v0x0c8). The first
`GetAllExtDevicePropInfo` right after the handshake can return placeholder
values (0 / 0xFFFFFFFF); a second read returns correct live values (brief
quirk #3 — camera warmup).

## Control / writing (Phase 3, verified on A7 III)

| Opcode | Name | Command param | Data phase (SENDDATA) |
|--------|------|---------------|-----------------------|
| 0x9205 | SDIO_SetExtDevicePropValue (control value A) | `propcode`; `[propcode,1]` in extended session | packed value (absolute — **mode 3 only**) |
| 0x9207 | SDIO_ControlDevice (control value B) | `propcode`; `[propcode,1]` in extended session | packed value (button / mode-2 notch) |

**CRITICAL — mode-2 bodies (A7 III) change settings by RELATIVE STEPS, not
absolute values.** Writing an absolute ISO via 0x9205 returns PTP OK (0x2001)
but is silently ignored. Instead send a *signed step* via **0x9207**:

| Setting | Code | Opcode | Datatype | Value |
|---------|------|--------|----------|-------|
| ISO     | 0xD21E | 0x9207 | INT32 | +N / -N steps (verified: 4000 → 5000 with +3) |
| FNumber | 0x5007 | 0x9207 | INT16 | +N / -N steps |
| EV comp | 0x5010 | 0x9207 | INT16 | +N / -N steps |

Source: SonyAlphaUSB `ModifyISO`/`ModifyFNumber`/`ModifyEV` (all via
`DoMainSetting` = opcode 0x9207); libgphoto2 comment "Old mode … notch up/down
shutterspeed, iso and f-stop". Mode-3 (2020+) bodies instead set absolutes via
0x9205.

**Shutter (photo capture)** — button controls via 0x9207, UINT16, 2=press,
1=release. Sequence (libgphoto2 `camera_sony_capture` + SonyAlphaUSB):

| Step | Code | Name | Value |
|------|------|------|-------|
| 1 | 0xD2C1 | ShutterHalfRelease | 2 (half-press down) |
| 2 | 0xD2C2 | ShutterRelease | 2 (full-press down) |
| 3 | — | hold ~1s (let AF/exposure settle) | — |
| 4 | 0xD2C2 | ShutterRelease | 1 (full-press up) |
| 5 | 0xD2C1 | ShutterHalfRelease | 1 (half-press up) |

On current mode-3 bodies, use combined `S1S2Button` **0xD2E6** (2=down,
1=up) when it appears in the camera's controls list. Do not substitute
`0xD2C7 RequestOneShooting` for a normal shutter sequence.

**Video recording** — movie button control via 0x9207, UINT16 (source:
SonyAlphaUSB StartRecordingVideo/StopRecordingVideo). Camera must be in MOVIE
mode on the physical dial.

| Action | Code | Name | Value |
|--------|------|------|-------|
| Start REC | 0xD2C8 | MovieRecButtonHold | 2 |
| Stop REC  | 0xD2C8 | MovieRecButtonHold | 1 |
| Read state| 0xD21D | MovieRecordingState | 0=Stopped, 1=Recording, 2=UnableToRecord |

## Live view (Phase 5, verified on A7 III — 30 fps)

The live-view frame is a special PTP object, handle **0xFFFFC002** (last still =
0xFFFFC001). Flow (source: libgphoto2 `camera_capture_preview` Sony branch +
SonyAlphaUSB GetImage):

In an extended `0x9210` Remote+Transfer session, first enable advertised
controls `0xD313 SetLiveViewEnable` and `0xD312 SetPostViewEnable` via `0x9207`,
params `[code,1]`, UINT16 value 2. Older bodies omit these controls and go
straight to the object probe.

1. `GetObjectInfo` (0x1008) on 0xFFFFC002 to prime the frame — retry while it
   returns `Invalid_ObjectHandle` (0x2009), the frame isn't ready yet.
2. `GetObject` (0x1009) on 0xFFFFC002 — retry while `Access_Denied` (**0x200F**)
   or `Device_Busy` (0x2019); those mean we polled too fast.
3. Payload = a Sony wrapper: the **first u32 is the byte offset to the JPEG SOI**
   (0xFFD8). Slice from there to the EOI (0xFFD9).

`LiveViewState` (0xD221) = 1 when live view is active. Frames are ~1024×576 JPEG,
~65 KB. Sustained ~30 fps over USB with no dropped frames on the A7 III (the
brief's quirk #2 instability was not observed here).

**Gotcha learned:** an aborted GetObject desyncs the bulk pipe (next command
write times out). `PtpUsb.open()` now drains the IN endpoint on connect to
recover without a replug; `_read_container` tolerates zero-length packets.

---

## Probe log — ILCE-7M3 (A7 III), 2026-07-13

- USB PID in PC Remote mode: **054C:0C34**, interface class 0x06 (still-image/PTP).
- Endpoints: bulk-IN 0x81, bulk-OUT 0x02, interrupt-IN 0x83.
- Manufacturer: `Sony Corporation`  Model: `ILCE-7M3`  DeviceVersion: `4.0`
- PTP standard version: 0x0064. Vendor ext: id=0x11 v0x0064 'Sony PTP Extensions'.
- OperationsSupported (16): 1002 1003 1001 1004 1005 1006 1007 1008 1009 100A
  101B 9201 9202 9205 9207 9209
- DevicePropertiesSupported: 0 (see quirk above).
- EventsSupported (3): C201 C202 C203.
