# Android app — build & run

The `app/` module is the Android port of the validated desktop harness
(`desktop-validation/`). Same PTP/Sony protocol, different transport: Android's
USB Host API instead of pyusb.

## Layers (mirror the desktop harness)

| Package | Role | Desktop equivalent |
|---------|------|--------------------|
| `usb/UsbTransport.kt` | USB Host: claim interface, bulk IN/OUT, drain-on-open | `ptp_usb.py` transport |
| `ptp/PtpSession.kt` | Generic PTP: buffered framing, validated transactions, recovery and replayable transport | `ptp_usb.py` PtpUsb |
| `ptp/DeviceInfo.kt` | Little-endian reader + GetDeviceInfo parser | `_Reader` / `parse_device_info` |
| `sony/` | Capability-negotiated SDIO/QX/legacy handshake, property parsing and control | Sony methods in `ptp_usb.py` |
| `ui/` | Jetpack Compose screen + USB permission handling | — |

## Status (v0.2)

- USB permission, PTP session, Sony capability negotiation, exposure reads and
  writes, photo capture/download, movie REC and Live View are implemented.
- The A7 III mode-2 route is retained. The a6700-like mode-3 route has full JVM
  handshake/parser/framing replay and awaits its first physical pass.
- Unsupported controls fail closed; Live View can disable independently while
  readings and controls remain connected.
- See `VALIDACION_MULTIMODELO.md` for the automated and two-camera matrix.

## One-time setup

1. Install **Android Studio**, Android SDK and a **JDK 17**. Do not select a
   bundled JBR 25 for this pinned Gradle/Kotlin build; in Android Studio choose
   JDK 17 under *Settings → Build Tools → Gradle → Gradle JDK*.
2. **Open this folder** (`D:\Escritorio\SonyCam`) in Android Studio: *File → Open* → pick
   the repo root. It will sync Gradle (uses the pinned wrapper, Gradle 8.9).
   - If a CLI build is wanted, Studio writes `local.properties` with `sdk.dir`;
     or create it manually: `sdk.dir=C\:\\Users\\Hola\\AppData\\Local\\Android\\Sdk`.

## Build / run

- In Android Studio: pick your device, press **Run** (▶).
- CLI PowerShell (once `local.properties` exists):

  ```powershell
  $env:JAVA_HOME = 'C:\ruta\a\jdk-17'
  .\gradlew.bat testDebugUnitTest assembleDebug
  ```

  The APK is written to `app/build/outputs/apk/debug/`.

## The single-USB-port problem (important)

The phone's USB-C port is needed for the **camera**, but `adb` normally deploys
over that same port. Use **Wireless debugging** so USB stays free:

1. Phone: *Settings → About phone →* tap *Build number* 7× to unlock Developer
   options.
2. *Settings → Developer options → Wireless debugging → On →* "Pair device with
   pairing code".
3. On the PC: `adb pair <ip>:<port>` (the code), then `adb connect <ip>:<port>`.
4. Now deploy over Wi-Fi; plug the **camera** into the phone's USB-C port
   (camera in PC Remote mode). Android will prompt to open "Sony Monitor".

   That prompt comes from `res/xml/device_filter.xml`, and it only works with a
   `<usb-device>` element — the only one Android's parser reads. A spell with
   `<usb-interface>` matched nothing and silently cost the app both the prompt
   and the permission grant, leaving only the in-app Connect button working.

## Permissions

No runtime permission dialog is needed for USB host beyond the per-device grant:
attaching a matching Sony camera (vendor 0x054C, see `res/xml/device_filter.xml`)
triggers the system permission prompt; the app also requests it on launch if a
camera is already attached.
