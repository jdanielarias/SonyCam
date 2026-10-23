"""
Phase 4 validation: start/stop VIDEO recording from the PC.

Uses the Sony movie-record button control (0xD2C8) via SDIO_ControlDevice
(0x9207), value 2 = start, 1 = stop — ported from SonyAlphaUSB
StartRecordingVideo / StopRecordingVideo. Recording state is read back from
MovieRecordingState (0xD21D): 0=Stopped, 1=Recording, 2=UnableToRecord.

The A7 III must be in MOVIE mode on the physical mode dial for recording to be
possible; otherwise the state reads 2 (UnableToRecord).

Usage:
  python phase4_video.py               # record ~5 s then stop
  python phase4_video.py --seconds 10  # record for a custom duration
  python phase4_video.py -v            # verbose PTP containers
"""

import argparse
import sys
import time

from ptp_usb import PtpUsb, SONY_VID, DPC_SONY_MOVIE_RECORDING_STATE

STATE = {0: "Stopped", 1: "Recording", 2: "UnableToRecord"}


def state_str(v):
    return STATE.get(v, f"?({v})") if v is not None else "--"


def wait_state(ptp, target, tries=12, delay=0.3):
    last = None
    for _ in range(tries):
        time.sleep(delay)
        last = ptp.read_movie_state()
        if last == target:
            return True, last
    return False, last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vid", type=lambda x: int(x, 0), default=SONY_VID)
    ap.add_argument("--pid", type=lambda x: int(x, 0), default=None)
    ap.add_argument("--seconds", type=float, default=5.0,
                    help="how long to record before stopping")
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

        state = ptp.read_movie_state()
        print(f"\nMovie recording state: {state_str(state)}")
        if state is None:
            print("MovieRecordingState (0xD21D) not found; cannot run demo.")
            sys.exit(1)
        if state == 2:
            print("\nCamera reports UnableToRecord — put the physical mode dial "
                  "in MOVIE mode, then re-run.")
            sys.exit(1)

        # ---- START --------------------------------------------------------
        print("\n>> Sending REC start ...")
        ptp.movie_rec_start()
        ok, now = wait_state(ptp, 1)
        print(f"   State: {state_str(now)}  -> "
              + ("RECORDING" if ok else "did NOT enter recording"))

        if ok:
            print(f"\n   Recording for {args.seconds:g} s ...")
            time.sleep(args.seconds)

        # ---- STOP ---------------------------------------------------------
        print("\n>> Sending REC stop ...")
        ptp.movie_rec_stop()
        stopped, now = wait_state(ptp, 0)
        print(f"   State: {state_str(now)}  -> "
              + ("STOPPED" if stopped else "did NOT return to stopped"))

        started_ok = ok
        print("\nRESULT:", "PASS" if (started_ok and stopped) else "PARTIAL",
              "- REC start/stop driven from the PC"
              + ("" if (started_ok and stopped) else "; check state transitions above"))

    finally:
        # safety: make sure we don't leave the camera recording
        try:
            if ptp.read_movie_state() == 1:
                print("\n(Safety) still recording — sending stop.")
                ptp.movie_rec_stop()
        except Exception:
            pass
        ptp.close_session()
        ptp.close()


if __name__ == "__main__":
    main()
