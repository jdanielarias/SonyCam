"""
Phase 5 validation: LIVE VIEW over USB/PTP.

Fetches JPEG frames from the Sony live-view object (handle 0xFFFFC002) via
GetObject (0x1009) and either saves them to disk or shows them in a live window.

The camera must have live view active (LiveViewState 0xD221). On the A7 III in
PC Remote this is normally on; if a frame can't be fetched, wake the camera /
half-press so the live feed is running.

Usage:
  python phase5_liveview.py                 # save one frame to live_frame.jpg
  python phase5_liveview.py --frames 30     # save 30 frames, report FPS
  python phase5_liveview.py --view          # live window (needs Pillow: pip install pillow)
  python phase5_liveview.py -v              # verbose PTP containers
"""

import argparse
import sys
import time

from ptp_usb import PtpUsb, SONY_VID, DPC_SONY_ISO  # noqa: F401

LIVE_VIEW_STATE = 0xD221


def grab(ptp):
    try:
        return ptp.get_live_view_frame()
    except Exception as e:
        print(f"  (frame fetch error: {e})")
        return None


def run_view(ptp):
    """Simple Tkinter live window. Requires Pillow for JPEG decoding."""
    try:
        import tkinter as tk
        from PIL import Image, ImageTk
        import io
    except Exception as e:
        print(f"Live window needs Tkinter + Pillow ({e}).")
        print("Install Pillow with:  pip install pillow")
        print("Falling back to saving frames to disk instead.\n")
        return False

    root = tk.Tk()
    root.title("Sony A7 III — Live View (PTP)")
    label = tk.Label(root)
    label.pack()
    state = {"running": True, "n": 0, "t0": time.time()}

    def on_close():
        state["running"] = False
        root.destroy()
    root.protocol("WM_DELETE_WINDOW", on_close)

    def tick():
        if not state["running"]:
            return
        jpeg = grab(ptp)
        if jpeg:
            try:
                img = Image.open(io.BytesIO(jpeg))
                photo = ImageTk.PhotoImage(img)
                label.config(image=photo)
                label.image = photo
                state["n"] += 1
                if state["n"] % 30 == 0:
                    fps = state["n"] / (time.time() - state["t0"])
                    root.title(f"Sony A7 III — Live View (PTP)  ~{fps:.1f} fps")
            except Exception as e:
                print(f"  decode error: {e}")
        root.after(1, tick)

    print("Live window open. Close it to stop.")
    root.after(1, tick)
    root.mainloop()
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vid", type=lambda x: int(x, 0), default=SONY_VID)
    ap.add_argument("--pid", type=lambda x: int(x, 0), default=None)
    ap.add_argument("--frames", type=int, default=1,
                    help="number of frames to grab and save")
    ap.add_argument("--out", default="live_frame.jpg", help="output JPEG path")
    ap.add_argument("--view", action="store_true", help="open a live window")
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
        props = ptp.sony_handshake()

        lv = props.get(LIVE_VIEW_STATE)
        print(f"LiveViewState (0x{LIVE_VIEW_STATE:04X}): "
              f"{lv.current_value if lv else 'n/a'}")

        # First frame is the proof of life.
        print("\nFetching a live-view frame...")
        jpeg = grab(ptp)
        if not jpeg:
            print("No live-view frame available. Make sure the camera is awake "
                  "and showing live view, then re-run.")
            sys.exit(1)
        with open(args.out, "wb") as f:
            f.write(jpeg)
        valid = jpeg.startswith(b"\xff\xd8") and jpeg.rstrip(b"\x00").endswith(b"\xff\xd9")
        print(f"  Saved {len(jpeg)} bytes -> {args.out}  "
              f"(JPEG {'valid' if valid else 'header?'})")
        print("RESULT:", "PASS" if valid else "PARTIAL",
              "- got a live-view JPEG from the camera.")

        if args.view:
            run_view(ptp)
        elif args.frames > 1:
            print(f"\nGrabbing {args.frames} frames to measure throughput...")
            t0 = time.time()
            got = 0
            for i in range(args.frames):
                jpeg = grab(ptp)
                if jpeg:
                    got += 1
                    with open(args.out, "wb") as f:
                        f.write(jpeg)
            dt = time.time() - t0
            print(f"  {got}/{args.frames} frames in {dt:.1f}s "
                  f"= {got / dt:.1f} fps (last saved to {args.out})")

    finally:
        ptp.close_session()
        ptp.close()


if __name__ == "__main__":
    main()
