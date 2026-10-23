# Cobertura de modelos: qué hace Monitor+ y qué falta en esta app

Investigación de fuentes públicas (agosto 2026) para ampliar el número de
cuerpos Sony que Sony Monitor puede manejar. Cada afirmación lleva su origen.
Lo que no está verificado va marcado como tal — este proyecto ya pagó tres
versiones por dar por buena una suposición.

---

## 1. Aclaración: hay dos apps con nombre parecido

| | Quién | Qué es |
|---|---|---|
| **Monitor & Control** | Sony (`jp.co.sony.playmemoriesmobile.proremote`) | Oficial, sólo cuerpos recientes |
| **Monitor+** | Terceros (`monitorplus.cc`) | **No es de Sony.** Cobertura mucho más amplia, incluida la a7 III |

La referencia útil es la segunda: es la que llega a cuerpos antiguos, que es
justo el problema de esta app.

## 2. Monitor+ usa exactamente la misma ruta que nosotros

Su propia documentación de conexión con cable en Android lo confirma: depende
del **modo PC Remote**, se reconoce porque aparece el icono `-PC-` en la
pantalla de la cámara, y Android pide el permiso USB del dispositivo. No hay
truco: es PTP sobre USB host, igual que aquí.

Dos consecuencias:

- La arquitectura de esta app es la correcta. No hay un atajo que estemos
  ignorando para los cuerpos clásicos.
- Todo lo que Monitor+ soporta de más es cuestión de **detalles**, no de
  enfoque.

Modelos que Monitor+ declara por cable: a7 V, a7 IV, **a7 III**, a7R V, a7R IVa,
a7R IV, **a7R III**, a7S III, **a7S II**, a7CR, a7C II, a7C, a1 II, a1, a9 III,
a9 II, FX5, FX3, FX30, FX2, ZV-E1, ZV-E10 II, ZV-E10, ZV-1 II, ZV-1, **a6700,
a6600, a6500, a6400, a6300, a6100**.

## 3. La causa nº 1 de "no conecta" no es protocolo: es el menú

Monitor+ documenta **cuatro rutas de menú distintas** según generación. Esta app
tiene un único mensaje genérico (`SonyNotInPcRemoteMode`), y para la mitad de
esos cuerpos ese texto manda al usuario al sitio equivocado:

| Generación | Ruta correcta |
|---|---|
| a7 IV, ZV-E1, a6700 y posteriores | Ajustes → USB → Modo conexión USB → **Sel. al conectar** (luego elegir *PC Remote* en el diálogo) |
| a7S III (fw < 3.0), FX3 (fw < 2.0) | Red → Transfer./Remoto → **Función PC Remote → PC Remote**, y *Método cnx. PC Remote* = **USB** |
| a7C, a7R IV | Red → **Función PC Remote → On** |
| **a7 III, a6600** y similares | Ajustes → **Conexión USB → PC Remote** |

**Mejora inmediata:** elegir el texto según lo que ya sabemos del cuerpo. En el
momento en que lanzamos `SonyNotInPcRemoteMode` ya tenemos `di.model` de
`GetDeviceInfo` — basta un mapeo por familia. Es el cambio con mejor relación
esfuerzo/cobertura de toda esta lista.

## 4. "USB Power Supply" tumba la sesión

De la documentación de Monitor+: hay que **desactivar la alimentación USB de la
cámara** antes de conectar. Si el teléfono no da carga inversa, el consumo
excesivo provoca **desconexiones**.

Esto encaja con caídas que hemos visto en esta sesión. Merece ser un aviso en la
app cuando la sesión se cae sin causa PTP, no sólo una nota.

## 5. El mapa de protocolos (coincide con lo que ya implementamos)

| Dialecto | Versión | Cuerpos | Estado aquí |
|---|---|---|---|
| SDIO mode 3 | `0x012C` | 2020+ (a7 IV, a6700, a1, FX3/FX30, ZV) | Implementado |
| SDIO mode 2 | `0x00C8` | ~2013–2019 (a7 III, a6x00, a7R III) | Implementado |
| Legacy per-prop `0x9203` | — | A-mount antiguas (A900, A700, SLT) | Implementado |
| QX `0x96Fx` | — | DSC-QX10/QX30, ILCE-QX1 | Implementado |

`pysonycam` describe la diferencia crítica entre v2 y v3 igual que nuestro
parser: **v3 añade un segundo bloque de enumeración (aunque venga vacío) que v2
no tiene, y tratarlo mal desalinea el flujo de bytes.** Ya lo manejamos.

## 6. La frontera del live view está en 2016

Según los informes de libgphoto2: **los cuerpos de 2016 en adelante tienen live
view por PTP; los de 2013–2015 en general no.** La a6000 (2014) no; la a6300
(2016) sí; la a5100 se reporta funcionando.

Nuestra máquina de estados (`UNVERIFIED → AVAILABLE / DISABLED`) ya degrada bien
sola, así que un cuerpo sin live view sigue siendo usable como control. No hace
falta una tabla por modelo.

## 7. Firmware mínimo

Sony exige **firmware actualizado** para el control remoto, y en varios modelos
es un mínimo concreto (la a7 III necesita **≥ 3.00**). Un cuerpo con firmware
viejo puede anunciar menos operaciones y parecer "incompatible" cuando sólo está
desactualizado.

`GetDeviceInfo` ya nos da la versión (`fw=4.0` en el informe). Vale la pena
mostrarla en el error de compatibilidad.

## 8. Sony publica la especificación oficial, gratis y sin NDA

El `README.Sony` de libgphoto2 ya no documenta nada: **remite a Sony**. Desde
2024 Sony publica *Camera Remote Command*, la especificación de su extensión
propietaria de PTP, con referencia de comandos y código de ejemplo, sin NDA.

Modelos que cubre oficialmente:

> ILX-LR1, ILCE-1M2, ILCE-1, ILCE-9M3, ILCE-9M2, ILCE-7RM6, ILCE-7RM5,
> ILCE-7RM4A, ILCE-7RM4, ILCE-7CR, ILCE-7SM3, ILCE-7M5, ILCE-7M4, ILCE-7CM2,
> ILCE-7C, ILCE-7RM3A, **ILCE-7M3**, ILCE-7M2, **ILCE-6700**, ILCE-6600,
> ILCE-6400A, ILCE-6400, ILCE-6100A, ILCE-6100, BURANO(MPC-2610), ILME-FX6V/T,
> ILME-FX3A, ILME-FX3, ILME-FX2, ILME-FX30, PXW-Z300, PXW-Z380, PXW-Z200,
> HXR-NX800, BRC-AM7, ILME-FR7, ZV-E1, ZV-E10, ZV-E10M2, ZV-1M2, ZV-1F, ZV-1A,
> ZV-1, DSC-RX1RM3, DSC-RX0M2, DSC-RX100M7A, DSC-RX100M7, DSC-RX100M5A,
> DSC-RX10M4, DSC-HX99

**Incluye tablas de compatibilidad por modelo dentro de la propia referencia.**
Es el recurso más valioso encontrado: sustituye adivinar por leer. Nótese que
Monitor+ soporta cuerpos que Sony **no** lista (a7R III no-A, a7S II, a6300,
a6500) — esos siguen siendo territorio de libgphoto2 e ingeniería inversa.

## 9. Tres capacidades que ya detectamos y no usamos

Salen en el informe de compatibilidad y no se usan en ningún sitio:

### ~~`0x9215 GetDisplayStringList`~~ — **descartado tras verificarlo**

Esto era la recomendación estrella de la primera versión de este documento, y
**era falsa**. Comprobado en la referencia oficial (págs. 127-129): la operación
acepta un *Display String List Type*, y los **28 tipos definidos** son
BaseLook, SceneFile, Camera.Gain.BaseISO, Video.EIGain, Button.Assign,
FTP.ServerName/UpLoadDirectory/JobStatus, Exposure.Index.Preset1,
Movie.Transfer.*, Creative Look, IPTC, Subject Recognition AF,
Camera.Button/Lever/Dial.Function.capability y CustomGridLineFile.Name.

**No existe ningún tipo para ISO, obturación, diafragma ni compensación.** Los
dos que suenan a exposición (`Camera.Gain.BaseISO`, `Exposure.Index.Preset1`)
son de cuerpos de cine. Implementarlo no habría aportado nada a la rueda.

Lo que sí resolvió el problema fue el punto siguiente: contrastar nuestros
decodificadores contra la referencia. Ahí aparecieron dos bugs reales.

### `0x9211 GetPartialLargeObject` — live view por trozos

Los cuerpos mode 3 lo anuncian. Un fotograma de live view de un cuerpo moderno
puede ser grande, y ahora lo pedimos entero con `GetObject`. La lectura por
trozos encaja además con el tope de 16 KB de usbfs que ya respetamos en el
transporte.

### El endpoint de interrupción — leído, y entrega eventos (v0.12)

`epInt = 0x83` en la a7 III. Los eventos Sony existen:

| Código | Evento |
|---|---|
| `0xC201` | ObjectAdded |
| `0xC202` | ObjectRemoved |
| `0xC203` | DevicePropChanged |
| `0xC206` | **CapturedEvent** |
| `0xC222` | OperationResult |
| `0xC223` | AFStatus |
| `0xC224` | **MovieRecOperationsResult** |

`0xC206` sería la confirmación de disparo que hoy deducimos de la señal de
ocupado, y `0xC224` la de REC que hoy comprobamos con D21D.

**Medido en la a7 III (agosto 2026): los eventos llegan.** `interrupt: 0xC203
tid=-1 params=0xD21E` y `params=0xD21D`. Eso refuta la deducción de abajo, que
se dejó escrita porque explica por qué había que medirlo.

La medición está sesgada, eso sí: la sonda vive dentro del bucle de sondeo, que
se salta durante seek y captura — justo cuando más cosas cambian. Sabemos que el
endpoint entrega; no sabemos si entrega **todo**.

**La deducción anterior, que resultó equivocada:** libgphoto2 **no** confía en el
endpoint de interrupción para Sony — sondea `0x9209`, igual que nosotros. Hay
informes de que los cuerpos Sony no entregan eventos PTP estándar de forma
fiable. La a7 III sólo anuncia C201/C202/C203, ni siquiera `CapturedEvent`.

Conclusión honesta: **no migrar a eventos sin medirlo antes.** Lo que procede es
un experimento acotado — leer `epInt` durante un disparo y ver si llega algo —
no un rediseño. Nuestro sondeo actual coincide con la implementación de
referencia.

## 9-bis. La referencia trae tablas de compatibilidad por modelo

La página 88 de la referencia PTP 3 es una tabla `Código × Modelo` con las 34
cámaras oficiales en columnas, indicando qué propiedad soporta cada una. Es la
fuente exacta para saber, antes de conectar, qué esperar de un cuerpo concreto.
Sin explotar todavía.

## 10. Trampas por modelo ya documentadas por otros

De la ingeniería inversa del proyecto `alpha-fairy`, útil para no repetir daños:

- **Cambiar AF/MF por PTP puede dejar el dial físico de enfoque muerto hasta
  reiniciar la cámara** si la conexión se cae después. Evitar tocarlo.
- **El modo recorte APS-C en cuerpos full-frame desactiva muchos comandos PTP.**
- **RAW+JPEG**: la cámara avisa de un objeto pero crea dos ficheros. El contador
  de buffer cuenta uno.
- **Zoom (`0xD25D`)** sólo responde con ópticas Sony de zoom motorizado.
- **a7R IV**: con destino "cámara + PC" el indicador de ocupado puede no
  limpiarse nunca. Solución: destino "sólo cámara".
- El formato de propiedades **cambió entre generaciones** y no hay indicador
  automático: hay que detectarlo escaneando los límites de los descriptores.
  Nosotros ya lo hacemos por versión de protocolo.

## 11. La vía UVC (aparte, y excluyente)

Los cuerpos de 2020+ (a6700, a7 IV, ZV-E10, ZV-E10 II, FX30, a7R V…) hacen
**USB Streaming**: se presentan como webcam UVC estándar, con vídeo a
resolución y cadencia reales, muy por encima de los JPEG de live view por PTP.

En Android sin root es posible: `saki4510t/UVCCamera` y `AndroidUSBCamera`
implementan UVC en espacio de usuario sobre la API USB host.

**Advertencia:** *USB Streaming* y *PC Remote* son modos de conexión USB
distintos en el menú de la cámara. Que se puedan usar a la vez **no está
verificado** y probablemente no. Es decir: vídeo bueno **o** control remoto, no
las dos cosas — a menos que se demuestre lo contrario. No merece la pena
construir sobre esto sin comprobarlo primero en la a6700.

## 12. Estado del plan

| # | Punto | Estado |
|---|---|---|
| 1 | Mensajes de PC Remote por generación (§3) | **Hecho** — v0.8 |
| 2 | Aviso de alimentación USB (§4) | **Hecho** — v0.8, dentro del mismo mensaje |
| 3 | Mostrar el firmware al fallar (§7) | **Hecho** — v0.8 |
| 4 | ~~`0x9215` para las etiquetas~~ | **Descartado** con pruebas — §9 |
| 5 | Contrastar decodificadores con la referencia | **Hecho** — v0.9, dos bugs reales |
| 5b | Tabla de compatibilidad por modelo (§9-bis) | **Hecho** — v0.10 |
| 5c | Sondeo diferencial `0x9209` param 1 | **Hecho** — v0.11, *sin probar en hardware* |
| 6 | `0x9211` para live view en mode 3 (§9) | **Pendiente** |
| 7 | Experimento con el endpoint de interrupción (§9) | **Hecho** — v0.12, medido: los eventos llegan |
| 8 | UVC (§11) | **Pendiente**, y bloqueado: hay que confirmar antes que convive con PC Remote |

Los puntos 5b y 5c no estaban en la lista original: salieron de hacer el 5.

### Lo que bloquea lo que queda

- **6 y 5c** necesitan una **a6700** delante. Ambos son ruta mode 3 y la a7 III
  no puede ejercitarlos.
- **7** ya se midió: el endpoint entrega `0xC203`. Lo que queda es leerlo
  también durante seek y captura antes de plantearse sustituir el sondeo.
- **8** requiere comprobar primero, en la a6700, si *USB Streaming* y *PC Remote*
  pueden convivir. Si no, no hay nada que construir.

---

## Fuentes

- [Monitor+ — compatibilidad de cámaras](https://monitorplus.cc/docs/general/camera-compatibility-and-capability/)
- [Monitor+ — conexión con cable en Android](https://monitorplus.cc/docs/general/connecting-to-your-camera/wired-connection-android/)
- [Sony — Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
- [Sony — Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)
- [libgphoto2 — README.Sony](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/README.Sony)
- [libgphoto2 — ptp.h (códigos Sony)](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)
- [libgphoto2 — live view en a6000 (#1150)](https://github.com/gphoto/libgphoto2/issues/1150)
- [libgphoto2 — Sony PTP Extensions (#978)](https://github.com/gphoto/libgphoto2/issues/978)
- [alpha-fairy — Camera Reverse Engineering](https://github.com/frank26080115/alpha-fairy/blob/main/doc/Camera-Reverse-Engineering.md)
- [pysonycam](https://github.com/olkham/pysonycam)
- [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE)
- [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera)
- [Sony — USB Streaming (a6700)](https://helpguide.sony.net/ilc/2320/v1/en/contents/211h_usb_streaming.html)
- [Sony — Monitor & Control, cámaras compatibles](https://helpguide.sony.net/promobile/mc/v1/en/contents/supported_camera.html)
