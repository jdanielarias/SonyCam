package com.sonycam.monitor.sony

/**
 * Where a given body hides the PC Remote switch.
 *
 * Sony has moved this setting three times, and the paths are not variations on a
 * theme — on the a7C generation the USB Connection menu has **no** PC Remote
 * entry at all, so the classic instructions send that owner hunting for an
 * option their camera does not have. Getting this wrong is the single most
 * common reason a supported body looks unsupported.
 *
 * Every path below was transcribed from Sony's own help guide for a body in
 * that group; see the doc comment on each entry for which one.
 */
enum class PcRemoteMenu {
    /**
     * 2017–2019 bodies: a7 III, a7R III, a9, a6100/a6400/a6600.
     * Verified on the ILCE-7M3 help guide, which also requires
     * [Ctrl w/ Smartphone] to be Off before [USB Connection] takes effect.
     */
    CLASSIC_USB,

    /**
     * 2019–2020 bodies: a7R IV, a9 II, a7C.
     * Verified on the ILCE-7RM4 and ILCE-7C help guides. The a7C's
     * [USB Connection] menu offers only Auto / Mass Storage / MTP — PC Remote
     * lives solely under Network.
     */
    NETWORK_PC_REMOTE,

    /**
     * 2021+ bodies: a7 IV, a7R V, a1, a9 III, a6700, a7C II, ZV, FX30…
     * Verified on the ILCE-6700, ILCE-7M4 and ILCE-7SM3 help guides. Setting it
     * here also turns on the Network-side PC Remote for you.
     *
     * The option's own name differs between bodies — [PC Remote] on the a6700,
     * [Remote Shooting] on the a7 IV and a7S III — so both are named.
     */
    MODERN_USB_MODE,

    /** No match. The user gets every known path, most recent first. */
    UNKNOWN,
}

/**
 * Bodies whose PC Remote switch moved to Setup → USB → USB Connection Mode.
 *
 * Matched on the model string PTP already gave us. This file is the one place
 * in the app that branches on a model name, and deliberately so: the camera is
 * not in PC Remote yet, so it exposes no vendor operations to negotiate a
 * profile from. The name is the only signal there is, and an unrecognised name
 * still gets [PcRemoteMenu.UNKNOWN], never a wrong instruction.
 */
private val MODERN_USB_MODE_MODELS = setOf(
    // Full-frame, 2021 onward
    "ILCE-1", "ILCE-1M2", "ILCE-9M3",
    "ILCE-7M4", "ILCE-7M5", "ILCE-7RM5", "ILCE-7RM6",
    "ILCE-7CM2", "ILCE-7CR",
    // a7S III and FX3 land here from firmware 3.00 / 2.00 on; older firmware is
    // covered by the legacy line in the message rather than a separate group.
    "ILCE-7SM3", "ILME-FX3", "ILME-FX3A",
    // APS-C and cinema
    "ILCE-6700", "ILME-FX30", "ILME-FX2", "ILME-FX6V", "ILME-FX6T", "ILX-LR1",
    // Vlog compacts
    "ZV-E1", "ZV-E10", "ZV-E10M2", "ZV-1", "ZV-1A", "ZV-1F", "ZV-1M2",
)

/** Bodies that keep PC Remote under Network only. */
private val NETWORK_PC_REMOTE_MODELS = setOf(
    "ILCE-7RM4", "ILCE-7RM4A", "ILCE-9M2", "ILCE-7C",
)

/** Bodies with the plain Setup → USB Connection → PC Remote entry. */
private val CLASSIC_USB_MODELS = setOf(
    "ILCE-7M3", "ILCE-7RM3", "ILCE-7RM3A", "ILCE-9",
    "ILCE-6100", "ILCE-6400", "ILCE-6600",
    "ILCE-6300", "ILCE-6500", "ILCE-7M2", "ILCE-7RM2", "ILCE-7SM2",
)

/** Which menu holds PC Remote on [model]. Case- and whitespace-tolerant. */
fun pcRemoteMenuFor(model: String): PcRemoteMenu {
    val key = model.trim().uppercase()
    return when (key) {
        in MODERN_USB_MODE_MODELS -> PcRemoteMenu.MODERN_USB_MODE
        in NETWORK_PC_REMOTE_MODELS -> PcRemoteMenu.NETWORK_PC_REMOTE
        in CLASSIC_USB_MODELS -> PcRemoteMenu.CLASSIC_USB
        else -> PcRemoteMenu.UNKNOWN
    }
}

private const val MENU_MODERN =
    "  MENÚ → Ajustes (Setup) → USB → Modo conexión USB\n" +
        "         → [PC Remote]  (en algunos cuerpos se llama [Disparo remoto])\n" +
        "     También sirve [Sel. al conectar] y elegirlo al enchufar el cable."

private const val MENU_NETWORK =
    "  MENÚ → Red (Network) → Función PC Remoto\n" +
        "         → PC Remoto: On\n" +
        "         → Método cnx. PC Remoto: USB"

private const val MENU_CLASSIC =
    "  MENÚ → Ajustes (Setup) → Conexión USB → [PC Remote]"

private const val PREREQ_SMARTPHONE =
    "  Antes: MENÚ → Red (Network) → Ctrl con smartphone → Ctrl con smartphone: Off\n" +
        "     Mientras esté en On, Conexión USB no cambia de modo."

/**
 * Sony reorganised this menu in a firmware update, but which version did it for
 * which body is not stated in any Sony document I could find — the version
 * numbers that circulate come from a third-party app's help pages. The route is
 * verified; the boundary is not, so no numbers are quoted.
 */
private const val LEGACY_FIRMWARE_NOTE =
    "  Con firmware antiguo esa entrada puede no existir todavía (a7S III,\n" +
        "  FX3, a7 IV). En ese caso la ruta es:\n" +
        "     MENÚ → Red → Transfer./Remoto → Función PC Remoto → PC Remoto: On"

private const val RECONNECT =
    "Después desconecta y vuelve a conectar el cable."

/**
 * Sony's help guide documents what the setting does — "power is supplied to the
 * product via a USB cable when the product is connected to a computer" — so the
 * camera drawing from the phone is a fact. That this is what drops the link is
 * reported by the Monitor+ people and appears nowhere in Sony's documentation,
 * so it is offered as something to try, not asserted as the cause.
 */
private const val POWER_SUPPLY_NOTE =
    "Si la sesión se cae sola al poco de conectar, prueba a apagar\n" +
        "Ajustes → USB → Alimentación USB. Con ese ajuste la cámara se\n" +
        "alimenta del teléfono, y hay teléfonos que no dan bastante."

/**
 * The exact menu path for this body, or every known path when the model is not
 * recognised. [firmware] comes from GetDeviceInfo and is shown because Sony
 * requires an up-to-date body for remote control: an old firmware can advertise
 * fewer operations and look like an incompatible camera.
 */
fun pcRemoteInstructions(model: String, firmware: String = ""): String {
    val name = model.trim().ifEmpty { "La cámara" }
    val fw = firmware.trim().takeIf { it.isNotEmpty() }?.let { " (firmware $it)" } ?: ""
    val body = when (pcRemoteMenuFor(model)) {
        PcRemoteMenu.MODERN_USB_MODE -> "$MENU_MODERN\n\n$LEGACY_FIRMWARE_NOTE"
        PcRemoteMenu.NETWORK_PC_REMOTE ->
            "$MENU_NETWORK\n\n  En este cuerpo, Conexión USB sólo ofrece Auto,\n" +
                "  Mass Storage y MTP: PC Remoto está únicamente en Red."
        PcRemoteMenu.CLASSIC_USB -> "$MENU_CLASSIC\n\n$PREREQ_SMARTPHONE"
        PcRemoteMenu.UNKNOWN ->
            "No conozco este modelo, así que aquí están las tres rutas que usa\n" +
                "Sony, de la más reciente a la más antigua:\n\n" +
                "$MENU_MODERN\n\n$MENU_NETWORK\n\n$MENU_CLASSIC\n\n$PREREQ_SMARTPHONE"
    }
    return "$name$fw no está en modo PC Remote.\n\n" +
        "En la cámara:\n$body\n\n$RECONNECT\n\n$POWER_SUPPLY_NOTE"
}
