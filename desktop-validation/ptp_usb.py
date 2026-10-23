"""
Minimal PTP-over-USB implementation for validating Sony Alpha cameras.

This is a *desktop* validation harness (Windows). It talks raw PTP to the
camera over libusb/pyusb so we can confirm the handshake and opcodes against a
real body (A7 III) before porting the logic to the Android app.

Protocol reference: PTP / ISO 15740, USB still-image class.
Container layout (all little-endian):

    offset  size  field
    0       4     ContainerLength (total bytes, incl. header)
    4       2     ContainerType   (1=Command 2=Data 3=Response 4=Event)
    6       2     Code            (opcode / response code / event code)
    8       4     TransactionID
    12      ...   Payload (params for command, raw bytes for data)
"""

from __future__ import annotations

import struct
import time
from dataclasses import dataclass, field

import usb.core
import usb.util
import libusb_package

# --- Container types --------------------------------------------------------
TYPE_COMMAND = 1
TYPE_DATA = 2
TYPE_RESPONSE = 3
TYPE_EVENT = 4

# --- Standard PTP operation codes ------------------------------------------
OC_GET_DEVICE_INFO = 0x1001
OC_OPEN_SESSION = 0x1002
OC_CLOSE_SESSION = 0x1003
OC_GET_DEVICE_PROP_DESC = 0x1014
OC_GET_DEVICE_PROP_VALUE = 0x1015
OC_SET_DEVICE_PROP_VALUE = 0x1016
OC_GET_OBJECT_INFO = 0x1008
OC_GET_OBJECT = 0x1009

# Sony special object handles (source: SonyAlphaUSB GetImageRequest).
OH_SONY_LIVE_VIEW = 0xFFFFC002   # live-view frame ("02 C0 FF FF")
OH_SONY_LAST_PHOTO = 0xFFFFC001  # last captured still  ("01 C0 FF FF")

# --- Sony SDIO vendor operation codes (source: libgphoto2 ptp.h) ------------
OC_SONY_SDIO_CONNECT = 0x9201                    # ptp_sony_sdioconnect
OC_SONY_SDIO_GET_EXT_DEVICE_INFO = 0x9202        # vendor prop/control code list
OC_SONY_SDIO_SET_EXT_DEVICE_PROP = 0x9205        # SetControlDeviceA (set a prop)
OC_SONY_SDIO_CONTROL_DEVICE = 0x9207             # button controls (shutter, REC)
OC_SONY_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO = 0x9209  # blob of all prop descs+values

# Sony protocol versions requested via SDIO_GetExtDeviceInfo (from ptp.c):
SONY_PROTOCOL_2_00 = 0x0c8   # pre-2020 bodies (A7 III etc.)
SONY_PROTOCOL_3_00 = 0x12c   # 2020+ bodies

# --- PTP data-type codes (source: libgphoto2 ptp.h) ------------------------
DTC_INT8, DTC_UINT8 = 0x0001, 0x0002
DTC_INT16, DTC_UINT16 = 0x0003, 0x0004
DTC_INT32, DTC_UINT32 = 0x0005, 0x0006
DTC_INT64, DTC_UINT64 = 0x0007, 0x0008
DTC_STR = 0xFFFF
DTC_ARRAY_MASK = 0x4000  # array types are base | 0x4000

# struct format + byte width per scalar data type
_DTC_SCALAR = {
    DTC_INT8: ("<b", 1), DTC_UINT8: ("<B", 1),
    DTC_INT16: ("<h", 2), DTC_UINT16: ("<H", 2),
    DTC_INT32: ("<i", 4), DTC_UINT32: ("<I", 4),
    DTC_INT64: ("<q", 8), DTC_UINT64: ("<Q", 8),
}

# --- Form flags for a device property descriptor ---------------------------
DPFF_NONE, DPFF_RANGE, DPFF_ENUM = 0x00, 0x01, 0x02
DPGS_GET, DPGS_GETSET = 0x00, 0x01

# --- A few well-known device property codes (sources: libgphoto2, SonyAlphaUSB)
DPC_WHITE_BALANCE = 0x5005
DPC_FNUMBER = 0x5007
DPC_FOCUS_MODE = 0x500A
DPC_EXPOSURE_PROGRAM_MODE = 0x500E
DPC_EXPOSURE_BIAS = 0x5010          # EV compensation, int16, value/1000
DPC_SONY_SHUTTER_SPEED = 0xD20D     # uint32: (numerator<<16)|denominator
DPC_SONY_ISO = 0xD21E               # uint32: base=raw&0xFFFFFF, 0xFFFFFF=Auto

# Sony "control" (button) property codes — driven via 0x9207. value 2=press,
# 1=release (source: libgphoto2 library.c camera_sony_capture).
DPC_SONY_SHUTTER_HALF_RELEASE = 0xD2C1
DPC_SONY_SHUTTER_RELEASE = 0xD2C2
DPC_SONY_MOVIE_REC_BUTTON = 0xD2C8
DPC_SONY_MOVIE_RECORDING_STATE = 0xD21D

# --- Response codes ---------------------------------------------------------
RC_OK = 0x2001

RESPONSE_NAMES = {
    0x2001: "OK",
    0x2002: "General_Error",
    0x2003: "Session_Not_Open",
    0x2005: "Operation_Not_Supported",
    0x2006: "Parameter_Not_Supported",
    0x2009: "Invalid_TransactionID",
    0x200A: "Specification_By_Format_Unsupported",
    0x201D: "Invalid_Parameter",
    0x201E: "Session_Already_Open",
}

SONY_VID = 0x054C


class PtpError(Exception):
    def __init__(self, code: int, message: str = ""):
        name = RESPONSE_NAMES.get(code, f"0x{code:04X}")
        super().__init__(f"PTP response {name}" + (f" — {message}" if message else ""))
        self.code = code


@dataclass
class DeviceInfo:
    standard_version: int = 0
    vendor_extension_id: int = 0
    vendor_extension_version: int = 0
    vendor_extension_desc: str = ""
    functional_mode: int = 0
    operations_supported: list[int] = field(default_factory=list)
    events_supported: list[int] = field(default_factory=list)
    device_properties_supported: list[int] = field(default_factory=list)
    capture_formats: list[int] = field(default_factory=list)
    image_formats: list[int] = field(default_factory=list)
    manufacturer: str = ""
    model: str = ""
    device_version: str = ""
    serial_number: str = ""


# --- Little parser for the GetDeviceInfo dataset ----------------------------
class _Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def u16(self) -> int:
        v = struct.unpack_from("<H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def u32(self) -> int:
        v = struct.unpack_from("<I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def u16_array(self) -> list[int]:
        count = self.u32()
        vals = list(struct.unpack_from(f"<{count}H", self.data, self.pos))
        self.pos += 2 * count
        return vals

    def string(self) -> str:
        num_chars = self.data[self.pos]
        self.pos += 1
        if num_chars == 0:
            return ""
        raw = self.data[self.pos : self.pos + 2 * num_chars]
        self.pos += 2 * num_chars
        # UTF-16LE, drop trailing null char
        return raw.decode("utf-16-le").rstrip("\x00")


def parse_device_info(data: bytes) -> DeviceInfo:
    r = _Reader(data)
    di = DeviceInfo()
    di.standard_version = r.u16()
    di.vendor_extension_id = r.u32()
    di.vendor_extension_version = r.u16()
    di.vendor_extension_desc = r.string()
    di.functional_mode = r.u16()
    di.operations_supported = r.u16_array()
    di.events_supported = r.u16_array()
    di.device_properties_supported = r.u16_array()
    di.capture_formats = r.u16_array()
    di.image_formats = r.u16_array()
    di.manufacturer = r.string()
    di.model = r.string()
    di.device_version = r.string()
    di.serial_number = r.string()
    return di


# --- Sony device-property descriptor ---------------------------------------
@dataclass
class SonyDeviceProp:
    code: int = 0
    data_type: int = 0
    get_set: int = 0
    is_enabled: int = 0
    default_value: object = None
    current_value: object = None
    form_flag: int = DPFF_NONE
    range: tuple | None = None          # (min, max, step)
    enum: list | None = None            # list of supported values


def _read_dpv(data: bytes, off: int, total: int, datatype: int):
    """Read one typed property value. Mirrors libgphoto2 ptp_unpack_DPV.

    Returns (value, new_offset). On an unsupported/short type returns
    (None, off) so the caller can stop cleanly instead of desyncing.
    """
    if off >= total:
        return None, off
    if datatype in _DTC_SCALAR:
        fmt, width = _DTC_SCALAR[datatype]
        if total - off < width:
            return None, off
        val = struct.unpack_from(fmt, data, off)[0]
        return val, off + width
    if datatype == DTC_STR:
        if off >= total:
            return None, off
        num = data[off]
        off += 1
        if num == 0:
            return "", off
        raw = data[off:off + 2 * num]
        off += 2 * num
        return raw.decode("utf-16-le").rstrip("\x00"), off
    if datatype & DTC_ARRAY_MASK:
        base = datatype & ~DTC_ARRAY_MASK
        if base not in _DTC_SCALAR or total - off < 4:
            return None, off
        count = struct.unpack_from("<I", data, off)[0]
        off += 4
        fmt, width = _DTC_SCALAR[base]
        if total - off < count * width:
            return None, off
        vals = [struct.unpack_from(fmt, data, off + i * width)[0] for i in range(count)]
        return vals, off + count * width
    return None, off


def _pack_dpv(value: int, datatype: int) -> bytes:
    """Pack a scalar property value as little-endian bytes (no length prefix).
    Mirrors libgphoto2 ptp_pack_DPV for scalar types.
    """
    if datatype not in _DTC_SCALAR:
        raise ValueError(f"unsupported datatype for pack: 0x{datatype:04X}")
    fmt, _width = _DTC_SCALAR[datatype]
    return struct.pack(fmt, value)


def parse_sony_dpd(data: bytes, off: int, total: int):
    """Parse one Sony device-property descriptor.

    Port of libgphoto2 ptp_unpack_Sony_DPD. Layout:
        u16 code, u16 dataType, u8 getSet, u8 isEnabled,
        <default value>, <current value>, u8 formFlag, <form>
    Returns (SonyDeviceProp, new_offset) or (None, off) on failure.
    """
    if total - off < 6:
        return None, off
    dpd = SonyDeviceProp()
    dpd.code = struct.unpack_from("<H", data, off)[0]
    dpd.data_type = struct.unpack_from("<H", data, off + 2)[0]
    dpd.get_set = data[off + 4]
    dpd.is_enabled = data[off + 5]
    o = off + 6

    dpd.default_value, o2 = _read_dpv(data, o, total, dpd.data_type)
    if o2 == o:                       # type not understood -> stop
        return None, off
    o = o2
    if dpd.data_type == DTC_STR and o == total:
        return dpd, o
    dpd.current_value, o = _read_dpv(data, o, total, dpd.data_type)

    if o + 1 > total:
        return dpd, o
    dpd.form_flag = data[o]
    o += 1
    if dpd.form_flag == DPFF_RANGE:
        mn, o = _read_dpv(data, o, total, dpd.data_type)
        mx, o = _read_dpv(data, o, total, dpd.data_type)
        st, o = _read_dpv(data, o, total, dpd.data_type)
        dpd.range = (mn, mx, st)
    elif dpd.form_flag == DPFF_ENUM:
        if o + 2 > total:
            return dpd, o
        n = struct.unpack_from("<H", data, o)[0]
        o += 2
        vals = []
        for _ in range(n):
            v, o = _read_dpv(data, o, total, dpd.data_type)
            if v is None:
                break
            vals.append(v)
        dpd.enum = vals
    return dpd, o


def parse_sony_all_props(data: bytes) -> dict:
    """Parse the SDIO_GetAllExtDevicePropInfo blob into {code: SonyDeviceProp}.

    Header is 8 bytes (u32 count, u32 zero), then a stream of descriptors.
    """
    props: dict[int, SonyDeviceProp] = {}
    total = len(data)
    if total <= 8:
        return props
    off = 8
    while off < total:
        dpd, new_off = parse_sony_dpd(data, off, total)
        if dpd is None or new_off <= off:
            break
        props[dpd.code] = dpd
        off = new_off
    return props


class PtpUsb:
    """Raw PTP transport over a single USB still-image / vendor interface."""

    def __init__(self, verbose: bool = False):
        self.dev: usb.core.Device | None = None
        self.intf = None
        self.ep_in = None
        self.ep_out = None
        self.ep_int = None
        self.transaction_id = 0
        self.session_open = False
        self.verbose = verbose
        self.sony_mode_ver = 0

    # -- lifecycle ----------------------------------------------------------
    def open(self, vid: int = SONY_VID, pid: int | None = None):
        backend = libusb_package.get_libusb1_backend()
        if pid is None:
            dev = usb.core.find(idVendor=vid, backend=backend)
        else:
            dev = usb.core.find(idVendor=vid, idProduct=pid, backend=backend)
        if dev is None:
            raise RuntimeError(
                f"No USB device found for VID=0x{vid:04X}"
                + (f" PID=0x{pid:04X}" % pid if pid else "")
                + ".\nIs the camera connected and in 'PC Remote' mode, and has "
                "WinUSB been bound to it with Zadig?"
            )
        self.dev = dev

        # On the WinUSB backend, calling set_configuration() when the device is
        # already configured can leave the bulk pipes stalled and hang the first
        # transfer. Only configure if the device isn't configured yet.
        try:
            cfg = dev.get_active_configuration()
            if self.verbose:
                print("  device already configured; skipping set_configuration")
        except usb.core.USBError:
            dev.set_configuration()
            cfg = dev.get_active_configuration()
        # Prefer the still-image class interface (class 6); fall back to first
        # interface exposing bulk-in + bulk-out (Sony PC Remote is vendor class).
        chosen = None
        for intf in cfg:
            eps = list(intf)
            bulk_in = [e for e in eps if usb.util.endpoint_type(e.bmAttributes) == usb.util.ENDPOINT_TYPE_BULK and usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN]
            bulk_out = [e for e in eps if usb.util.endpoint_type(e.bmAttributes) == usb.util.ENDPOINT_TYPE_BULK and usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT]
            if bulk_in and bulk_out:
                if intf.bInterfaceClass == 6 or chosen is None:
                    chosen = intf
                if intf.bInterfaceClass == 6:
                    break
        if chosen is None:
            raise RuntimeError("No interface with bulk IN+OUT endpoints found.")

        self.intf = chosen
        eps = list(chosen)
        self.ep_in = next(e for e in eps if usb.util.endpoint_type(e.bmAttributes) == usb.util.ENDPOINT_TYPE_BULK and usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN)
        self.ep_out = next(e for e in eps if usb.util.endpoint_type(e.bmAttributes) == usb.util.ENDPOINT_TYPE_BULK and usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT)
        self.ep_int = next((e for e in eps if usb.util.endpoint_type(e.bmAttributes) == usb.util.ENDPOINT_TYPE_INTR), None)

        # Explicitly claim the interface. Without this, the first bulk-OUT
        # write can hang/time out on freshly WinUSB-bound Sony devices.
        try:
            usb.util.claim_interface(dev, chosen.bInterfaceNumber)
        except usb.core.USBError as e:
            if self.verbose:
                print(f"  claim_interface note: {e}")

        # Clear any stale halt/stall on the bulk endpoints left over from a
        # previous session or the driver swap.
        for ep in (self.ep_out, self.ep_in):
            try:
                self.dev.clear_halt(ep.bEndpointAddress)
            except usb.core.USBError as e:
                if self.verbose:
                    print(f"  clear_halt 0x{ep.bEndpointAddress:02X} note: {e}")

        # Drain any leftover data the camera was still sending from a previously
        # aborted transaction (e.g. an interrupted GetObject). Without this the
        # next command reads stale bytes and the pipe stays desynced.
        for _ in range(8):
            try:
                stale = self.ep_in.read(16384, timeout=120)
                if not len(stale):
                    break
            except usb.core.USBError:
                break

        if self.verbose:
            print(f"  Interface #{chosen.bInterfaceNumber} class=0x{chosen.bInterfaceClass:02X}")
            print(f"  ep_in=0x{self.ep_in.bEndpointAddress:02X} ep_out=0x{self.ep_out.bEndpointAddress:02X}"
                  + (f" ep_int=0x{self.ep_int.bEndpointAddress:02X}" if self.ep_int else " (no interrupt ep)"))

    def close(self):
        if self.dev is not None:
            usb.util.dispose_resources(self.dev)
            self.dev = None

    # -- low-level container I/O -------------------------------------------
    def _next_tid(self) -> int:
        self.transaction_id += 1
        return self.transaction_id

    def _send_command(self, opcode: int, params: list[int], tid: int):
        payload = struct.pack("<" + "I" * len(params), *params)
        length = 12 + len(payload)
        header = struct.pack("<IHHI", length, TYPE_COMMAND, opcode, tid)
        self.ep_out.write(header + payload, timeout=5000)
        if self.verbose:
            print(f"  -> CMD 0x{opcode:04X} tid={tid} params={[hex(p) for p in params]}")

    def _send_data(self, opcode: int, tid: int, data: bytes):
        length = 12 + len(data)
        header = struct.pack("<IHHI", length, TYPE_DATA, opcode, tid)
        self.ep_out.write(header + data, timeout=5000)
        if self.verbose:
            print(f"  -> DATA 0x{opcode:04X} tid={tid} ({len(data)} bytes)")

    def _read_container(self, timeout: int = 8000) -> tuple[int, int, int, bytes]:
        """Read one full PTP container. Returns (type, code, tid, payload)."""
        # A zero-length packet (ZLP) can precede the real container; retry a few
        # times before giving up so a stray ZLP doesn't abort the transaction.
        chunk = b""
        for _ in range(6):
            chunk = self.ep_in.read(512, timeout=timeout).tobytes()
            if len(chunk) >= 12:
                break
        if len(chunk) < 12:
            raise RuntimeError(f"Short PTP container ({len(chunk)} bytes)")
        length, ctype, code, tid = struct.unpack_from("<IHHI", chunk, 0)
        buf = bytearray(chunk)
        while len(buf) < length:
            more = self.ep_in.read(min(16384, length - len(buf)), timeout=timeout).tobytes()
            if not more:
                break
            buf.extend(more)
        payload = bytes(buf[12:length])
        if self.verbose:
            tname = {1: "CMD", 2: "DATA", 3: "RESP", 4: "EVENT"}.get(ctype, str(ctype))
            print(f"  <- {tname} 0x{code:04X} tid={tid} ({len(payload)} bytes)")
        return ctype, code, tid, payload

    # -- transaction helpers ------------------------------------------------
    def transact(self, opcode: int, params: list[int] | None = None,
                 data_out: bytes | None = None, receives_data: bool = False
                 ) -> tuple[bytes, int, list[int]]:
        """Run a full PTP transaction.

        Returns (data_payload, response_code, response_params).
        Raises PtpError if the response code is not OK.
        """
        params = params or []
        tid = self._next_tid()
        self._send_command(opcode, params, tid)

        if data_out is not None:
            self._send_data(opcode, tid, data_out)

        data_payload = b""
        while True:
            ctype, code, _rtid, payload = self._read_container()
            if ctype == TYPE_DATA:
                data_payload = payload
                continue
            if ctype == TYPE_RESPONSE:
                resp_params = list(struct.unpack("<" + "I" * (len(payload) // 4), payload)) if payload else []
                if code != RC_OK:
                    raise PtpError(code)
                return data_payload, code, resp_params
            if ctype == TYPE_EVENT:
                # ignore async events during a transaction
                continue
            raise RuntimeError(f"Unexpected container type {ctype}")

    # -- standard operations ------------------------------------------------
    def get_device_info(self) -> DeviceInfo:
        data, _, _ = self.transact(OC_GET_DEVICE_INFO, receives_data=True)
        return parse_device_info(data)

    def open_session(self, session_id: int = 1):
        try:
            self.transact(OC_OPEN_SESSION, [session_id])
        except PtpError as e:
            # The camera keeps a session open across USB re-opens until it is
            # reset; a leftover session from a previous run is harmless — we are
            # already in a session, so just proceed.
            if e.code != 0x201E:  # Session_Already_Open
                raise
        self.session_open = True

    def close_session(self):
        if self.session_open:
            try:
                self.transact(OC_CLOSE_SESSION)
            except Exception:
                pass
            self.session_open = False

    # -- Sony SDIO handshake / properties ----------------------------------
    def sdio_connect(self, phase: int, p2: int = 0, p3: int = 0):
        """SDIO_Connect (0x9201). Data-in op; libgphoto2 discards the data."""
        self.transact(OC_SONY_SDIO_CONNECT, [phase, p2, p3], receives_data=True)

    def sony_get_ext_device_info(self, version: int = SONY_PROTOCOL_3_00):
        """SDIO_GetExtDeviceInfo (0x9202): returns (version, propcodes, controls).

        Sets self.sony_mode_ver (2 or 3) from the version the camera reports.
        """
        data, _, _ = self.transact(OC_SONY_SDIO_GET_EXT_DEVICE_INFO,
                                   [version, 1], receives_data=True)
        if not data:
            self.sony_mode_ver = 2
            return 0, [], []
        r = _Reader(data)
        cam_version = r.u16()
        self.sony_mode_ver = 3 if cam_version == SONY_PROTOCOL_3_00 else 2
        prop_codes = r.u16_array()
        control_codes = r.u16_array()
        return cam_version, prop_codes, control_codes

    def sony_get_all_props(self) -> dict:
        """SDIO_GetAllExtDevicePropInfo (0x9209): all prop descriptors+values."""
        data, _, _ = self.transact(
            OC_SONY_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO, receives_data=True)
        return parse_sony_all_props(data)

    def sony_handshake(self) -> dict:
        """Full Sony init sequence (order per libgphoto2 library.c):
        SDIO_Connect 1 -> 2 -> GetExtDeviceInfo -> SDIO_Connect 3, then read
        all device properties. Requires an already-open PTP session.
        Returns {propcode: SonyDeviceProp}.
        """
        self.sdio_connect(1)
        self.sdio_connect(2)
        cam_version, prop_codes, control_codes = self.sony_get_ext_device_info()
        if self.verbose:
            print(f"  Sony ext device info: version=0x{cam_version:X} "
                  f"mode_ver={self.sony_mode_ver} "
                  f"props={len(prop_codes)} controls={len(control_codes)}")
        self.sdio_connect(3)
        return self.sony_get_all_props()

    # -- Sony control / writing --------------------------------------------
    def sony_set_property(self, propcode: int, value: int, datatype: int = DTC_UINT32):
        """SDIO_SetExtDevicePropValue (0x9205) — change a setting (ISO, aperture,
        exposure comp, ...). Port of ptp_sony_setdevicecontrolvaluea.
        """
        data = _pack_dpv(value, datatype)
        self.transact(OC_SONY_SDIO_SET_EXT_DEVICE_PROP, [propcode], data_out=data)

    def sony_control(self, propcode: int, value: int, datatype: int = DTC_UINT16):
        """SDIO_ControlDevice (0x9207). Port of ptp_sony_setdevicecontrolvalueb.

        Two uses:
          * button controls — shutter half/full press, movie REC (UINT16, 2=press,
            1=release);
          * "notch" settings on Sony *mode 2* bodies (A7 III etc.) — ISO / f-stop
            / shutter / EV are changed by a *relative signed step* here, not by an
            absolute value. e.g. sony_control(DPC_SONY_ISO, +1, DTC_INT32) nudges
            ISO one step up, -1 one step down. (Mode-3 bodies set absolutes via
            sony_set_property / 0x9205 instead.)
        """
        data = _pack_dpv(value, datatype)
        self.transact(OC_SONY_SDIO_CONTROL_DEVICE, [propcode], data_out=data)

    def capture_photo(self, hold_seconds: float = 1.0):
        """Take a still: half-press -> full-press -> hold -> release full ->
        release half. Sequence and values (2=press, 1=release) match
        libgphoto2 camera_sony_capture.
        """
        self.sony_control(DPC_SONY_SHUTTER_HALF_RELEASE, 2)   # half-press down
        self.sony_control(DPC_SONY_SHUTTER_RELEASE, 2)        # full-press down
        time.sleep(hold_seconds)                              # let AF/exposure settle
        self.sony_control(DPC_SONY_SHUTTER_RELEASE, 1)        # full-press up
        self.sony_control(DPC_SONY_SHUTTER_HALF_RELEASE, 1)   # half-press up

    def movie_rec_start(self):
        """Start video recording. Port of SonyAlphaUSB StartRecordingVideo
        (MovieRecButton 0xD2C8 = 2 via 0x9207)."""
        self.sony_control(DPC_SONY_MOVIE_REC_BUTTON, 2)

    def movie_rec_stop(self):
        """Stop video recording. Port of SonyAlphaUSB StopRecordingVideo
        (MovieRecButton 0xD2C8 = 1 via 0x9207)."""
        self.sony_control(DPC_SONY_MOVIE_REC_BUTTON, 1)

    def read_movie_state(self):
        """Return MovieRecordingState (0xD21D): 0=Stopped, 1=Recording,
        2=UnableToRecord, or None if the property isn't present."""
        dpd = self.sony_get_all_props().get(DPC_SONY_MOVIE_RECORDING_STATE)
        return None if dpd is None else dpd.current_value

    # -- objects / live view -----------------------------------------------
    def get_object(self, handle: int) -> bytes:
        """GetObject (0x1009): download the raw bytes of a PTP object."""
        data, _, _ = self.transact(OC_GET_OBJECT, [handle], receives_data=True)
        return data

    def get_live_view_frame(self, tries: int = 40) -> bytes | None:
        """Fetch one live-view JPEG frame from the Sony live-view object.

        Follows libgphoto2's Sony flow: GetObjectInfo(0xffffc002) to prime the
        frame (retry while InvalidObjectHandle), then GetObject (retry while
        AccessDenied/DeviceBusy — those mean we polled too fast). The payload is
        a Sony wrapper: the first u32 is the offset to the JPEG SOI.

        Returns the JPEG bytes, or None if no frame becomes available.
        """
        RC_INVALID_OBJECT_HANDLE = 0x2009
        RC_ACCESS_DENIED = 0x200F   # PTP "Access_Denied" — Sony returns it when polled too fast
        RC_DEVICE_BUSY = 0x2019

        data = None
        for _ in range(tries):
            try:
                self.transact(OC_GET_OBJECT_INFO, [OH_SONY_LIVE_VIEW],
                              receives_data=True)
            except PtpError as e:
                if e.code == RC_INVALID_OBJECT_HANDLE:
                    time.sleep(0.05)   # frame not ready yet
                    continue
                # other info errors: still try to grab the object
            try:
                data = self.get_object(OH_SONY_LIVE_VIEW)
                break
            except PtpError as e:
                if e.code in (RC_ACCESS_DENIED, RC_DEVICE_BUSY):
                    time.sleep(0.02)   # polled too fast; back off
                    continue
                raise
        if not data or len(data) < 4:
            return None

        # First u32 is the offset into the blob where the JPEG (0xFFD8) starts.
        offset = struct.unpack_from("<I", data, 0)[0]
        if offset + 1 < len(data) and data[offset] == 0xFF and data[offset + 1] == 0xD8:
            jpeg = data[offset:]
        else:
            soi = data.find(b"\xff\xd8")
            if soi < 0:
                return None
            jpeg = data[soi:]
        eoi = jpeg.rfind(b"\xff\xd9")
        return jpeg[:eoi + 2] if eoi > 0 else jpeg
