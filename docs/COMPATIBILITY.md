# Compatibilidad multi-modelo Sony — investigación y plan

Investigación inicial: 2026-07-28. Implementación multimodelo: 2026-08-24.
Objetivo: entender por qué la **a6700 (ILCE-6700)** se
reconoce pero no responde a los controles, y definir cómo cubrir el máximo de
cuerpos Sony **sin tenerlos físicamente**.

Fuentes primarias: `reference/libgphoto2` (copia local ≈ 2.5.34, incluye ILCE-1M2),
`reference/SonyAlphaUSB`, el hilo gphoto-devel de la a6700, y la documentación
oficial que Sony liberó en 2024 (ver §4).

---

## 0. Resumen ejecutivo

**Actualización 2026-08-24:** la aplicación ya no da por hecho que todo cuerpo
es una A7 III. Negocia sesión estándar `0x1002` o moderna `0x9210`, conserva las
listas de propiedades/controles, deriva captura y REC de lo anunciado, prepara
Live View moderno con `D313/D312`, y deja una generación no confirmada en modo
seguro de sólo lectura. El transporte PTP conserva bytes entre lecturas y se
valida con replay. La ruta mode 2 mantiene la A7 III; la ruta mode 3 tiene tests
de handshake/parser y queda pendiente de un único pase físico con la a6700.

No se necesita el diagnóstico perdido del 28 de julio para esta arquitectura.
Los diagnósticos nuevos siguen siendo valiosos cuando un firmware concreto
anuncia algo y después lo rechaza, pero ya no son necesarios para añadir un
`if (modelo)`.

El problema **no es la a6700**. Es que `SonyCamera.kt` codifica el modelo mental de
un único cuerpo (A7 III, Sony *mode 2*, 2018) en constantes fijas:

* asume `0x500E` = UINT16 con codificación de 16 bits,
* asume que si `0x9202` no contesta a la primera, el cuerpo es *mode 2*,
* parsea el descriptor de propiedad con el formato pre-2024,
* ignora los dos campos (`GetSet`, `IsEnabled`) y las dos listas que la propia
  cámara envía para decirte **qué se puede escribir y cómo**.

La a6700 es *mode 3* con el formato de propiedad nuevo. Con esas cuatro
suposiciones rotas a la vez, el resultado exacto es el que describes: enumera,
conecta, y los controles quedan muertos o grises.

La solución no es una tabla `if (model == "ILCE-6700")`. Es **negociación de
capacidades**: cada cuerpo Sony ya te dice en el handshake qué propiedades
soporta, de qué tipo son, si son escribibles y por qué mecanismo. libgphoto2
soporta ~120 cuerpos Sony y en su despacho de configuración **no consulta el
nombre del modelo casi nunca** — despacha por `(propcode, DataType, vendor)`
(`config.c:11738-11739`). Ese es el patrón a copiar.

---

## 1. Las causas concretas, en orden de probabilidad

### Causa 0 — Ajuste de la cámara (probar esto primero, cuesta 30 segundos)

La a6700 tiene **cuatro** modos USB, no dos:
`Live Stream (USB Streaming)` / `Image Transfer (MSC)` / `Image Transfer (MTP)` /
**`Remote Shoot (PC Remote)`**. Y en los cuerpos 2022+ hay además un segundo
interruptor en otro menú:

```
MENU → (Setup / Configuración) → USB → USB Connection Mode → Remote Shoot (PC Remote)
MENU → (Network / Red) → Cnct./PC Remote → PC Remote Function → PC Remote: On
```

Si `USB Connection Mode` está en `Auto` o `MTP`, la cámara enumera con otro PID,
como dispositivo MTP, **sin los opcodes `0x92xx`**. La app la "reconoce" (VID
`054C` + una interfaz con bulk IN/OUT) pero el handshake Sony no existe.

**La app no comprueba esto.** `SonyCamera.handshake()` (`SonyCamera.kt:52`) dispara
`0x9201` a ciegas. Antes de nada hay que mirar `DeviceInfo.operationsSupported`:
si no contiene `0x9201`/`0x9202`/`0x9209`, el mensaje al usuario debe ser
*"pon la cámara en PC Remote"*, no un error de PTP.

### Causa 1 — Detección de generación sin reintento → a6700 clasificada como *mode 2*

`SonyCamera.kt:38-45`:

```kotlin
val data = transact(OC_SDIO_GET_EXT_DEVICE_INFO, intArrayOf(version, 1)).data
if (data.isEmpty()) { sonyModeVer = 2; return }   // ← una sola oportunidad
```

libgphoto2 hace lo mismo **hasta 20 veces** porque los cuerpos nuevos devuelven
vacío en los primeros intentos mientras arrancan el subsistema PC Remote
(`library.c:718-726`):

```c
unsigned int tries = 20;
while ((xsize == 0) && (tries--))
    C_PTP (ptp_sony_get_vendorpropcodes (&camera->pl->params, &xprops, &xsize));
```

Si caes en `sonyModeVer = 2` con una a6700, el `seek` toma la rama de pasos
relativos (`MainViewModel.kt:406`) y manda notches por `0x9207` a props que en
*mode 3* sólo aceptan escritura absoluta por `0x9205`. La cámara responde
`0x2001 OK` y no hace nada. Síntoma: **la rueda gira y el valor no se mueve**.

### Causa 2 — Formato de propiedad nuevo (2024): la **segunda lista de enum**

Ésta es la que rompe todo de golpe. `ptp-pack.c:884-919`:

```c
if (dpdlen >= *poffset + 2) {
    uint16_t val = dtoh16a(data + *poffset);
    /* check if we have a secondary list of items, this is for newer Sonys (2024) */
    if (val < 0x200) {   /* si no hubiera 2ª lista, esto sería el siguiente
                            propcode: 0x5xxx o 0xDxxx */
        if (dpd->FormFlag == PTP_DPFF_Enumeration) {
            /* descarta la primera lista y vuelve a leer N + N valores */
        }
    }
}
```

Los cuerpos nuevos emiten **dos** listas de enumeración por propiedad: la primera
son todos los valores que el cuerpo conoce, la segunda los realmente
seleccionables ahora. libgphoto2 se queda con la **segunda**.

`SonyDpd.kt:130-136` sólo lee la primera y sigue. A partir de la primera
propiedad con lista doble, el offset del lector queda **dentro** de la segunda
lista: cada propiedad posterior se parsea como basura (códigos inventados, tipos
inválidos), hasta que `readTyped` devuelve null y el bucle de
`parseSonyAllProps` (`SonyDpd.kt:147-153`) hace `break`.

Resultado: un mapa con unas pocas props correctas y luego nada. `0xD21E`,
`0x5007`, `0xD20D`, `0x500E` probablemente ni aparecen → `decodeReadings`
devuelve `"--"` y `buildPickList` devuelve `null` → **controles muertos**.

Es, casi con certeza, el fallo principal de la a6700.

### Causa 3 — `0x500E` en *mode 3* es UINT32 con **otra codificación**

`config.c:4163-4228`, tabla `sony_exposure_program_32_modes`:

| Modo | mode 2 (u16) | mode 3 (u32) |
|------|--------------|--------------|
| M    | `0x0001`     | `0x00000001` |
| P    | `0x0002`     | `0x0001` **`0002`** |
| A    | `0x0003`     | `0x0002` **`0003`** |
| S    | `0x0004`     | `0x0003` **`0004`** |
| Movie (M) | `0x8053` | `0x0007` **`8053`** |
| S&Q (M)   | `0x8087` | `0x0008` **`8087`** |
| iAuto     | `0x8000` | `0x0004` **`8000`** |

La palabra baja coincide con el código de 16 bits; la palabra alta es un **grupo**
(0 = PASM foto, 4 = auto, 5 = escena, 7 = vídeo, 8 = HFR/S&Q, 9 = S&Q, 0xA =
selector foto/vídeo, 0xB = modo F, 0xC = interval REC).

`decodeExposureMode` (`SonyCamera.kt:271`) hace `when (raw.toInt())` sobre el valor
completo → en A obtiene `0x20003`, no `0x0003` → `"0x20003"` → `modeRaw` no está
en `APERTURE_MODES`/`SHUTTER_MODES`/… → `editableIn()` devuelve false →
**todos los controles grises**. Esto explica literalmente "los controles y eso no".

Arreglo correcto y agnóstico: **enmascarar con `0xFFFF`** y reutilizar la misma
tabla, guardando la palabra alta como pista de grupo. Y elegir la interpretación
por el `DataType` que reporta la cámara, no por `sonyModeVer`.

### Causa 4 — Se ignora `GetSet`, que es exactamente el mapa de "cómo se escribe"

`ptp-pack.c:774-811`:

```c
if (dpd->GetSet & 0x80) {
    /* This is a control value and can be set */
    /* 0x81 - button, 0x83 - lock, 0x82 - notch, 0x84 - variable */
```

| `GetSet` | Significado | Cómo se escribe |
|----------|-------------|-----------------|
| `0x00`/`0x01` | propiedad normal | `0x9205` absoluto (mode 3) |
| `0x81` | botón | `0x9207` con 2 = pulsar, 1 = soltar |
| `0x82` | **notch** | `0x9207` con UINT8 `+1`/`0xFF` |
| `0x83` | lock | `0x9207` |
| `0x84` | variable | `0x9207` con valor |

Y `IsEnabled`: `0` = gris (no editable *ahora*), `1` = editable, `2` = sólo
lectura.

`SonyDeviceProp` (`SonyDpd.kt:35-44`) **guarda ambos campos y nunca los usa**. Todo
el gating de UI se hace con las tablas hardcodeadas `APERTURE_MODES` /
`SHUTTER_MODES` / `FULLY_AUTO_MODES` derivadas de la A7 III.

Esto es el regalo: **`IsEnabled` ya es el "gris o no" que la app calcula a mano**,
y funciona en todos los cuerpos. Y `GetSet & 0x80` te dice, por propiedad y por
cuerpo, si va por `0x9205` o por `0x9207` — sin adivinar por generación.

### Causa 4b — Falta `PriorityMode = application` tras el handshake

`library.c:758-759`, justo después de `sdioconnect(3)` y **sin condición de
modelo ni de generación**:

```c
propval.i8 = 1; /* application */
LOG_ON_PTP_E (ptp_sony_setdevicecontrolvaluea (params, PTP_DPC_SONY_PriorityMode, &propval, PTP_DTC_INT8));
```

`0xD25A PriorityMode` decide quién manda: la cámara o el host. Sin ponerlo en
`1 = application`, los cuerpos nuevos conservan prioridad de dial y **aceptan las
escrituras con `0x2001 OK` sin aplicarlas**. Es el mismo síntoma que la Causa 1 y
se sumaba a ella. El error se ignora a propósito (`LOG_ON_PTP_E`): los cuerpos
viejos no tienen la propiedad.

### Causa 5 — Se descartan las dos listas de `0x9202`

`SonyCamera.kt:44`:

```kotlin
// (the two u16 arrays that follow — props, controls — aren't needed here)
```

Sí hacen falta. `ptp.c:4353-4373` lee **dos** arrays u16 (propiedades y controles)
y `library.c:728-754` los clasifica por `code & 0x7000`:

* `0x1000` → opcodes soportados
* `0x4000` → eventos
* `0x5000` → device properties

y los **fusiona** en el `DeviceInfo`, porque `GetDeviceInfo` estándar devuelve
0 propiedades en Sony (ya lo tienes documentado en `OPCODES.md`).

Peor: en *mode 3* los **controles no salen en `0x9209`**. `ptp.c:4696-4713`:

```c
if (params->sony_mode_ver==3) {
    // Sony's GetAllExtDevicePropInfo API doesn't return some properties in mode 3.
    // These are referred to as 'Controls' in the Sony documentation ...
```

y sintetiza descriptores para 13 controles (`sony_mode3_controls`, `ptp.c:4630-4648`):
`0xD2C1` half, `0xD2C2` release, `0xD2C3` AEL, `0xD2C4` AFL, `0xD2C7`
RequestOneShooting, `0xD2C8` MovieRec, `0xD2C9` FEL, `0xD2D1` ManualFocusAdjust,
`0xD2D9` AWBL, `0xD2DC` AFAreaPosition, `0xD2DD` ZoomOperation, `0xD2E9`/`0xD2EA`
Save/LoadZoomAndFocus.

Por eso en el hilo de gphoto-devel la a6700 devuelve **error 2002 al *consultar***
`0xD2C1`, `0xD2C2`, `0xD2C7`, `0xD2C8`: son write-only. No es un fallo — es lo
esperado, y hay que tratarlo así en vez de asumir que no existen.

### Causa 6 — Lecturas USB de tamaño no alineado al `maxPacketSize`

`PtpSession.kt:55` lee el primer paquete con buffer de **512 bytes** fijos:

```kotlin
var chunk = transport.read(512, timeoutMs)
```

512 es correcto para USB 2.0 High-Speed. En **SuperSpeed (USB 3.x)** el
`wMaxPacketSize` de bulk es **1024**, y los cuerpos modernos (a6700, a7 IV,
a7R V, a1, a9 III, FX3/FX30) tienen puerto USB 10 Gbps. Un `bulkTransfer` con
buffer menor que el paquete entrante da error de overflow o trunca.

Y en `PtpSession.kt:72`:

```kotlin
val more = transport.read(minOf(16384, length - out.size()), timeoutMs)
```

`length - out.size()` casi nunca es múltiplo de `maxPacketSize`; el último paquete
del dispositivo puede desbordar el buffer. Con blobs `0x9209` pequeños (A7 III,
~4 KB) cuela; con los de *mode 3* (muchas más propiedades, listas dobles) es
frágil.

Arreglo: usar `epIn.maxPacketSize` y **redondear hacia arriba** todas las lecturas
a un múltiplo de ese valor.

### Causa 7 — Tablas canónicas hardcodeadas que en *mode 3* sobran

`STD_FNUMBERS` / `STD_SHUTTERS` / `STD_ISOS` / `STD_EVS` (`SonyCamera.kt:366-411`)
existen porque la A7 III no enumera diafragma ni obturador. Los cuerpos *mode 3*
**sí** los enumeran (`config.c:5647` "new style has an ENUM again"). Usar las
tablas fijas ahí produce valores que el cuerpo rechaza. La lógica ya prioriza el
enum de la cámara — pero sólo si el parser (Causa 2) llegó a leerlo.

---

## 1b. El universo real: cinco familias, no dos

"Todos los modelos posibles" no es un eje (mode 2 / mode 3). Son **cinco rutas de
protocolo distintas**, y libgphoto2 las resuelve con una cadena de *fallbacks* en
`ptp_generic_getdevicepropdesc` (`ptp.c:4688-4782`) y
`ptp_generic_setdevicepropvalue` (`ptp.c:4803-4824`), decidiendo siempre por
**opcode soportado**, nunca por modelo:

| Familia | Cuerpos | Detección | Lectura | Escritura |
|---------|---------|-----------|---------|-----------|
| **SDIO mode 2** | A7/A7 II/A7 III, a6000-a6600, RX100 III-VII, A7R II/III… (2013-2019) | `0x9202` presente, versión `0x0C8` | `0x9209` en bloque | `0x9205` absoluto; ISO/f/obturador/EV por *notch* `0x9207` |
| **SDIO mode 3** | a7S III, a1, a7 IV, a7R V, ZV-E1, FX3/FX30, **a6700**, a9 III, a1 II (2020+) | `0x9202` presente, versión `0x12C` | `0x9209` en bloque, formato 2024 con lista doble | `0x9205` absoluto para todo; controles write-only por `0x9207` |
| **QX** | DSC-QX10/QX30, ILCE-QX1 | `0x96FE` presente | `0x96F6` | `0x96FA` / `0x96F8` |
| **Legacy per-prop** | A900, A700, SLT-A58/A99 | `0x9203` presente, sin `0x9209` | `0x9203` una propiedad cada vez | `0x9205` |
| **PTP genérico** | DSC compactas viejas, o **cualquier cuerpo en MTP/MSC** | ningún `0x92xx` | `0x1014` | `0x1016` (sólo lectura en la práctica) |

La quinta fila es la que produce el falso diagnóstico: un cuerpo perfectamente
capaz en modo MTP cae ahí y parece "no soportado". Por eso el caso se detecta y
se reporta como ajuste de cámara, no como error de protocolo.

## 2. El cambio de arquitectura: perfil negociado, no tabla por modelo

En lugar de `SonyPtpProfile` estático por modelo (lo que anticipa `MODELS.md`),
derivar un perfil **en tiempo de conexión** desde lo que la propia cámara declara:

```
DeviceInfo.operationsSupported     → ¿hay 0x9201/0x9202/0x9209? ¿0x9211? ¿0x9215?
0x9202 → version                   → generación (2 / 3), con reintentos
0x9202 → lista props + lista ctrls → universo real de códigos de este cuerpo
0x9209 → por propiedad:
           DataType                → cómo decodificar y cómo empaquetar la escritura
           GetSet & 0x80 + nibble  → 0x9205 absoluto vs 0x9207 botón/notch/variable
           IsEnabled               → gris o no, AHORA (sustituye a APERTURE_MODES etc.)
           FormFlag + enum/range   → la lista de valores seleccionables
```

Reglas de despacho, en orden:

1. **Nunca** ramificar por `model`. Ramificar por `(propcode, DataType)` como hace
   `config.c:11738-11739`.
2. `sonyModeVer` sólo decide el *default* de escritura. La decisión real la da
   `GetSet & 0x80`.
3. Propiedad desconocida → mostrarla en una lista "Avanzado" con su valor crudo,
   **nunca** romper la conexión ni el parser.
4. Fallo blando: si no se entiende una propiedad, esa propiedad se degrada a
   sólo-lectura; el resto de la app sigue viva.

Con eso, un cuerpo nuevo que Sony saque en 2027 funciona el día 1 en todo lo que
comparta códigos con la familia, sin tocar código.

### Extras que sólo tienen los cuerpos nuevos (detectables por opcode)

De `ptp.h:717-722` — comprobar presencia en `operationsSupported` y usar si están:

| Opcode | Nombre | Para qué sirve aquí |
|--------|--------|---------------------|
| `0x9210` | `SDIO_OpenSession` | sesión Sony explícita en cuerpos nuevos |
| `0x9211` | `SDIO_GetPartialLargeObject` | descarga por trozos: fotos grandes y live view sin bloquear el bus |
| `0x9212` | `SDIO_SetContentsTransferMode` | modo de transferencia en background |
| **`0x9215`** | **`SDIO_GetDisplayStringList`** | **la cámara devuelve las etiquetas legibles de cada valor de enum** |
| `0x9223` | `SDIO_GetLensInformation` | datos del objetivo montado |
| `0x922F` | `SDIO_OperationsResultsSupported` | qué operaciones devuelven resultado |

`0x9215` es la pieza más valiosa para tu objetivo: **elimina la necesidad de
tablas de strings por modelo**. libgphoto2 lo tiene declarado pero no
implementado (`ptp.c:9361` sólo lo nombra); el formato está en la documentación
oficial de Sony (§4).

---

## 3. Cómo funciona Monitor+ (y qué copiar)

Monitor+ (`monitorplus.cc`, Google Play `com.uhi.monitorplusflutter.gstore`) es
una app Flutter, sin relación oficial con Sony, ~19 USD el plan pro. Convierte el
teléfono en monitor con live view, control remoto (ISO / diafragma / obturador /
WB), touch AF, false color, waveform, vectorscopio, focus peaking, LUTs.

Lo relevante para nosotros es su **matriz de compatibilidad**, porque revela la
arquitectura:

* **Inalámbrico**: sólo a7R V, a7R IV, a9 III, a9 II, a7C, a7C II, a7CR, a7S III,
  a1, FX3, FX30, ZV-1, ZV-E10, ZV-E1, a7 IV *y posteriores*.
* **Cable USB**: todo lo anterior **más** cuerpos viejos como la a7 III, a6100,
  a6300, a6400, a6500, a6600.

Esa asimetría es exactamente la frontera *mode 2* / *mode 3*: el camino
inalámbrico es el stack moderno de Sony (PTP/IP, sólo cuerpos 2019+ con la pila
nueva), y el camino USB es PTP+SDIO genérico, que también cubre los viejos. Es
decir: **Monitor+ no tiene un driver por modelo; tiene un driver por generación**,
igual que libgphoto2, y por eso su lista wired crece "gratis".

Su documentación de soporte tampoco lista quirks por modelo salvo para AF táctil
("a6x00 y a7 III: apagar Center Lock-on AF"; "a6600: Func. of Touch Operation →
Touch Focus"). O sea: los únicos quirks que documentan son de **ajustes de
cámara**, no de protocolo. Confirma la tesis — el protocolo se negocia, los
quirks reales viven en el menú de la cámara.

**Qué copiar:** (a) driver por generación, no por modelo; (b) una pantalla de
"requisitos de conexión" que verifique y explique los ajustes de la cámara antes
de culpar al protocolo.

---

## 4. La fuente autoritativa: Sony publicó la especificación PTP

El 5 de julio de 2024 Sony escribió a Marcus Meissner (mantenedor de libgphoto2).
El correo está en el repo: `camlibs/ptp2/README.Sony`. Anuncia soporte oficial
para *"low level control of our cameras using USB-PTP protocol"* y el compromiso
de **publicar la documentación de comandos PTP sin NDA**.

Eso se materializó en **Camera Remote Command**
(`support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html`):

* Extensión propietaria de Sony sobre ISO-PTP — **es exactamente el protocolo
  `0x92xx` que ya hablas**.
* El paquete contiene **Command Reference + código de ejemplo**.
* Interfaces: USB, LAN cableada, Wi-Fi.
* **Gratuito**, versión 2.02.00. Se solicita por un formulario regional
  (portales separados USA/Canadá, EU, Asia-Pacífico, China, AU/NZ). Dirigido a
  "corporate customers".
* Sony ofrece además contacto interno y préstamo de equipo a desarrolladores
  cualificados (según el `README.Sony`).
* Cubre ILCE-1M2, ILCE-9M3, ILCE-7RM6, ILCE-7RM5, ILCE-7M5, ILCE-7M4, ILCE-7CM2,
  ILCE-7CR, ILCE-7SM3, **ILCE-6700**, ZV-E1, ZV-E10, BURANO, ILME-FX3A/FX6V,
  PXW-Z300, serie DSC-RX…
* Aviso de Sony de 2026-06-10: algunos comandos Camera Control PTP 2 pueden
  dejar de estar disponibles en ciertos modelos desde 2027. Por eso PTP/IP/PTP3
  queda como línea de evolución; no se debe prometer compatibilidad futura sólo
  por funcionar hoy sobre PTP2.

Distíntelo del **Camera Remote SDK** (`support.d-imaging.sony.co.jp/app/sdk/`):
ése es una librería binaria de alto nivel para Windows / macOS / Linux x64 y ARM.
**No tiene build de Android** — por eso no te sirve directamente, y por eso
Monitor+ tampoco lo usa por USB. Pero su *API Reference* (pública, v1.12+) sí
documenta los códigos y semánticas de propiedad, que son los mismos valores
`0xD2xx`.

**Acción recomendada #1 de todo este informe: solicitar Camera Remote Command.**
Es gratis, sin NDA, y contiene la matriz oficial modelo × propiedad — literalmente
lo que pides ("abarcar el máximo de modelos sin conectarlas acá").

Nota de estado: incluso libgphoto2 tiene abierto el issue
[#1140](https://github.com/gphoto/libgphoto2/issues/1140) *"A6700: added support
but unable to retrieve its options"* (abierto 18/07/2025). No estamos resolviendo
un problema ya resuelto río arriba; con los arreglos de §1 podemos ir por delante.

---

## 5. Cómo probar modelos que no tienes

### 5.1 Grabar y reproducir (lo más rentable)

La app ya tiene el botón DIAG y un transcript (`MainViewModel.kt:100-141`). Falta
un paso: **volcar el binario crudo, no sólo el texto decodificado**.

1. En `PtpSession.transact`, con un flag `record`, añadir cada transacción a un
   log estructurado: `op`, `params`, `dataOut` (hex), `dataIn` (hex completo),
   `rc`.
2. Escribir un `ReplayTransport` que implemente la misma superficie que
   `UsbTransport` y sirva respuestas desde ese log.
3. Tests JVM puros (`app/src/test/`) que corran `SonyCamera.handshake()` +
   `parseSonyAllProps` + `buildPickList` contra cada captura.

Cada dump de un tester se convierte en un **test de regresión permanente**. Es la
única forma realista de no romper la A7 III cada vez que arregles la a6700.

### 5.2 Cosechar blobs reales que ya son públicos

Los issues de `gphoto/libgphoto2` y `gphoto/gphoto2` contienen logs de
`gphoto2 --debug` con volcados hex completos de `0x9209` de a6700, a7 IV, a7R V,
ZV-E1, a9 III… Un log de gphoto2 con `GP_LOG_DATA` es convertible a fixture de
replay con un script. Eso da corpus de arranque **sin pedirle nada a nadie**.

Comando que hay que pedirle a un tester con Linux/macOS:
```
gphoto2 --debug --debug-logfile=a6700.log --list-all-config
```

### 5.3 Crowdsourcing dentro de la app

Convertir DIAG en un "Informe de compatibilidad" de un toque:
`DeviceInfo` completo + las dos listas de `0x9202` + el blob crudo de `0x9209` en
hex + resultado de un probe de escritura seguro (leer, escribir el mismo valor,
releer). Con eso puedes añadir soporte a un cuerpo **sin tocarlo nunca**.

### 5.4 Emulación

`libgphoto2_port/vusb` (vcamera) emula una cámara PTP para los tests de gphoto2,
pero sólo modela Canon. Extenderla a Sony es posible y es la única vía de probar
casos de error (timeouts, `Device_Busy`, desincronización) de forma determinista.
Coste medio; hacerlo sólo si §5.1 se queda corto.

### 5.5 Probe de escritura seguro (para descubrir el mecanismo sin romper nada)

Para una propiedad con `GetSet & 0x80 == 0`: leer valor → `0x9205` con el
**mismo** valor → releer. Si responde `0x2001` y el valor sobrevive, el cuerpo
acepta absolutos. Si `0x2005`/`0x2006`, es de sólo lectura. No cambia nada de la
configuración del usuario y clasifica la propiedad.

---

## 6. Generaciones y PIDs (referencia)

`sonyModeVer` sale de la versión que devuelve `0x9202` (`ptp.c:4303-4348`):

* `0x0C8` (200) = **mode 2** — pre-2020. Diafragma/obturador/ISO por *notch*
  relativo (`0x9207`, UINT8 `0x01`/`0xFF`), captura de 16 bits, sin enum de
  diafragma/obturador.
* `0x12C` (300) = **mode 3** — "For 2020 Models or Later" según los docs de Sony.
  Obturador/ISO/diafragma **directamente asignables**, captura de 32 bits, muchas
  propiedades nuevas, `0x500E` en UINT32.
* La petición se hace **siempre pidiendo 300**; los cuerpos que no lo soportan
  contestan con su máximo. Por eso no hace falta saber el modelo de antemano.

PIDs "PC Control" modernos de `library.c` (útiles para el mensaje de ayuda, **no**
para decidir el driver):

| PID | Modelo | | PID | Modelo |
|-----|--------|-|-----|--------|
| `0x0C34` | ILCE-7M3 (el tuyo) | | `0x0DA7` | ILCE-7M4 |
| `0x0C33` | ILCE-7RM3 | | `0x0E0C` | ILCE-7RM5 |
| `0x0CAA` | ILCE-6400 | | **`0x0E78`** | **ILCE-6700** |
| `0x0D10` | ILCE-6600 | | `0x0E80` | ILCE-9M3 |
| `0x0D14` | ILCE-6100 | | `0x0E90` | ILX-LR1 |
| `0x0D18` | ILCE-7SM3 | | `0x0EED` | ILCE-1M2 |
| `0x0D1C` | ILCE-1 | | `0x0D97` | ZV-E10 |
| `0x0D2B` | ILCE-7C | | `0x0DA3` | ILME-FX3 |

Cada cuerpo tiene un PID distinto en MTP y en PC Remote (p. ej. A7 III: `0x0C03`
MTP vs `0x0C34` PC Remote). **Detectar por PID es una trampa** — detecta por
`operationsSupported`.

---

## 7. Plan de implementación

Estado a 2026-08-24: **fases 1-6 implementadas, compilando y cubiertas por tests
JVM/replay**. La A7 III sigue siendo la referencia física mode 2; la validación
física de mode 3 con la a6700 continúa pendiente. Las extensiones no necesarias
para el monitor actual (`0x9211`, `0x9215`, PTP/IP/PTP3) permanecen futuras.

| Fase | Qué | Estado |
|------|-----|--------|
| 1 | Parser: lista secundaria 2024, resincronización, diagnóstico | ✅ `SonyDpd.kt` |
| 2 | Handshake: dialecto, reintentos, listas `0x9202`, PriorityMode, controles sintéticos | ✅ `SonyCamera.kt`, `SonyProfile.kt` |
| 3 | Decodificación agnóstica de `0x500E` (u16/u32) | ✅ `SonyCamera.kt` |
| 4 | Escritura dirigida por `GetSet`/`IsEnabled` | ✅ `SonyDpd.kt`, `MainViewModel.kt`, `MonitorApp.kt` |
| 5 | Transporte USB alineado a `maxPacketSize` | ✅ `UsbTransport.kt`, `PtpSession.kt` |
| 6 | Camera Remote Command, record/replay, informe de compatibilidad | ✅ parser/framing/full-handshake replay + DIAG crudo |
| 7 | `0x9211`, `0x9215`, PTP/IP/PTP3, ancho de `0xD215` | ⬜ (`0xD215` enmascarado ✅) |

Lo implementado, en concreto:

* `sony/SonyProfile.kt` (nuevo) — `SonyDialect` (SDIO / QX / LEGACY_PER_PROP /
  GENERIC), `SonyProfile` con capacidades derivadas, tabla
  `SONY_MODE3_CONTROLS`, y `SonyNotInPcRemoteMode` con la ruta de menú exacta.
* `sony/SonyDpd.kt` — lista secundaria 2024, resincronización tras entrada
  corrupta, `SonyWriteMode` derivado de `GetSet`/`IsEnabled`, parser de
  descriptor PTP estándar para la familia genérica.
* `sony/SonyCamera.kt` — handshake por dialecto con warm-up acotado,
  clasificación `& 0x7000` de las dos listas, `PriorityMode = application`,
  sesión moderna `0x9210`, controles mode-3 sintetizados, captura `D2E6`,
  preparación `D313/D312`, lectura y escritura despachadas por perfil,
  `normalizeExposureMode` y `ExposureControl.editable`.
* `usb/UsbTransport.kt` + `ptp/PtpSession.kt` — lecturas alineadas al
  `maxPacketSize` real, carry-over entre containers, validación de TID/código,
  límites y recuperación del stream.
* `ui/MainViewModel.kt` — informe de compatibilidad en buffer propio (incluye el
  blob crudo de `0x9209` en hex, listo para replay), `seek` dirigido por
  `writeMode`, probes de Live View con backoff y mensaje de PC Remote.
* `app/src/test` — fixtures mode 2/mode 3 y replay de parser, transporte y
  handshake completo sin hardware.

### Fase 1 — Parser correcto (arregla la Causa 2, la crítica)

`SonyDpd.kt`
* En `parseSonyDpd`, tras leer el FORM: mirar los 2 bytes siguientes; si el u16 es
  `< 0x200`, es el contador de una **segunda** lista de enum → descartar la
  primera y leer la segunda. Réplica exacta de `ptp-pack.c:884-919`.
* Cuando el parser aborte, **registrar el offset y volcar 64 bytes de contexto en
  hex** al diag. Hoy un `break` silencioso pierde toda la señal.
* No abortar el mapa entero ante una propiedad ilegible: intentar resincronizar
  buscando el siguiente u16 en rango `0x5000-0x5FFF` / `0xD000-0xDFFF`.

### Fase 2 — Handshake robusto (Causas 0, 1, 5)

`SonyCamera.kt`
* Antes del handshake, validar `operationsSupported`; si faltan los `0x92xx`,
  lanzar un error tipado `NotInPcRemoteMode` con las instrucciones de menú.
* `getExtDeviceInfo`: reintentar hasta 20 veces mientras la respuesta venga vacía.
* Parsear y **conservar** las dos listas u16 (props + controls); clasificarlas por
  `code & 0x7000` y guardarlas en el perfil.
* En *mode 3*, sintetizar descriptores para los controles que están en la lista
  pero no en `0x9209`, con la tabla de `ptp.c:4630-4648`.
* Registrar la versión cruda de `0x9202` en el diag (no sólo el 2/3 derivado).

### Fase 3 — Decodificación agnóstica (Causa 3)

`SonyCamera.kt`
* `decodeExposureMode`: si `DataType == UINT32`, decodificar `raw and 0xFFFF` con
  la tabla existente y exponer `raw ushr 16` como grupo. Si es UINT16, como ahora.
* `SonyReadings.modeRaw` pasa a ser el código de 16 bits normalizado → las tablas
  `APERTURE_MODES` / `SHUTTER_MODES` / `MOVIE_MODES` siguen valiendo tal cual.
* Elegir el ancho de escritura por el `DataType` del descriptor, no por constante.

### Fase 4 — Escritura dirigida por `GetSet` (Causa 4)

`SonyCamera.kt` / `MainViewModel.kt`
* Añadir a `SonyDeviceProp` un `writeMode` derivado:
  `Absolute` (GetSet sin bit 0x80) / `Button` (0x81) / `Notch` (0x82) /
  `Lock` (0x83) / `Variable` (0x84) / `ReadOnly`.
* `seek()` deja de mirar `modeVer` y mira `writeMode`.
* El gris de la UI pasa a usar `IsEnabled` de la cámara, con las tablas por modo
  actuales sólo como *fallback* cuando `IsEnabled` no sea fiable (mode 2).

### Fase 5 — Transporte USB (Causa 6)

`UsbTransport.kt` / `PtpSession.kt`
* Exponer `epIn.maxPacketSize`; leer el primer contenedor con ese tamaño (no 512).
* Redondear toda lectura hacia arriba a múltiplo de `maxPacketSize`.
* Recortar al `length` real del contenedor tras leer.

### Fase 6 — Cobertura y datos

* Solicitar **Camera Remote Command** (§4).
* Montar el harness record/replay (§5.1) y cosechar fixtures (§5.2).
* Convertir DIAG en informe de compatibilidad de un toque (§5.3).
* Probar `0x9215 GetDisplayStringList` cuando esté en la lista de opcodes: elimina
  las tablas de strings por modelo.
* Pantalla de "requisitos de conexión" al estilo Monitor+ (§3).

### Fase 7 — Superficie extendida

* `0x9211 GetPartialLargeObject` para fotos grandes / live view en cuerpos nuevos.
* `0xD215 ObjectInMemory`: verificar ancho (u16 vs u32) en *mode 3* antes de
  comparar con `0x8000` — el memo `sony-capture-d215` asume u16.
* Mantener `0xD2C7 RequestOneShooting` separado del disparo normal. Los cuerpos
  modernos que lo anuncian usan `0xD2E6 S1S2Button` para la secuencia normal;
  `D2C7` queda reservado para su semántica específica (por ejemplo, flujos bulb).

---

## 8. Qué pedir en el próximo test con la a6700

Con la app actual, antes de tocar código, un solo DIAG contestaría casi todo:

1. `USB Connection Mode` = `Remote Shoot (PC Remote)` y `PC Remote Function` = On.
2. Conectar, exportar DIAG, y mirar en el fichero:
   * la línea `connected:` → `modeVer=?` y `props=?`
   * si `props` es un número bajo (< 20) y las props listadas tienen códigos
     absurdos → **confirmada la Causa 2**.
   * si `modeVer=2` en una a6700 → **confirmada la Causa 1**.
   * si aparece `0x500E` con `type=0x0006` (UINT32) → **confirmada la Causa 3**.
3. Añadir temporalmente el volcado hex crudo de la respuesta de `0x9209` — con eso
   se puede reproducir el parseo entero en un test sin la cámara delante.

---

## Fuentes

* [libgphoto2 `README.Sony` — carta de Sony del 5/7/2024](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/README.Sony)
* [Sony Camera Remote Command (spec PTP oficial, gratis, sin NDA)](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
* [Sony Camera Remote SDK (alto nivel, sin build Android)](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)
* [Sony press — control remoto vía extensión propietaria de ISO-PTP](https://www.sony.co.uk/presscentre/sony-enables-remote-control-of-a-wide-camera-range-through-iso-ptp-protocol-through-proprietary-extension)
* [libgphoto2 issue #1140 — A6700 no devuelve opciones](https://github.com/gphoto/libgphoto2/issues/1140)
* [libgphoto2 issue #978 — Sony PTP Extensions](https://github.com/gphoto/libgphoto2/issues/978)
* [gphoto-devel — soporte Sony a6700](https://www.mail-archive.com/gphoto-devel@lists.sourceforge.net/msg01130.html)
* [libgphoto2 NEWS (2.5.32 "support newer sony property format", 2.5.34 "Sony generation 2 vs 3 handling enhanced")](https://github.com/gphoto/libgphoto2/blob/master/NEWS)
* [Monitor+ — compatibilidad y setup](https://monitorplus.cc/)
* [Monitor+ en Google Play](https://play.google.com/store/apps/details?id=com.uhi.monitorplusflutter.gstore)
* [ILCE-6700 Help Guide — USB Connection Mode](https://helpguide.sony.net/ilc/2320/v1/en/contents/0601_usb_connection.html)
* [ILCE-6700 Help Guide — PC Remote Function](https://helpguide.sony.net/ilc/2320/v1/en/contents/0903_pc_remote_function.html)
