# Procedencia: qué está verificado y con qué

Auditoría de cada afirmación que ha entrado en el código a partir de la
investigación. Existe porque en este proyecto una suposición cómoda ya costó
tres versiones, y porque "lo leí en algún sitio" no es lo mismo que "lo dice
Sony".

Tres niveles:

- **Sony** — leído en documentación oficial (referencia PTP o guía de ayuda del
  modelo). La cita indica página o URL.
- **Cable** — medido en una cámara física de este proyecto.
- **Terceros** — Monitor+, libgphoto2, alpha-fairy, foros. Útil, pero no es la
  especificación.

---

## Verificado con Sony

| Afirmación | Dónde | Usado en |
|---|---|---|
| Obturación: `0x00000000` = BULB, `0xFFFFFFFF` = *nothing to display*, resto numerador/denominador | PTP 3 pág. 446 | `shutterReading` (v0.9) |
| F-Number: `0xFFFD` Iris Close, `0xFFFE` `--`, `0xFFFF` nada, resto ×100 | PTP 3 pág. 175 | `decodeFNumber` (v0.9) |
| Compensación EV: INT16 × 1000 | PTP 3 pág. 180 | `decodeEv` — sin cambios, ya era correcto |
| ISO extendido: sumar desplazamiento `0x10000000` | PTP 3 pág. 460 | confirma la rueda ISO de la v0.6 |
| ISO multifotograma: byte alto `0x01` y `0x02` | PTP 3 págs. 457-458 | `isSonyIsoMfnr` |
| `0x9209` param 1: 0 = todo, 1 = sólo diferencias | PTP 3 págs. 120-122 | sondeo diferencial (v0.11) |
| `0x9209` en PTP 2 **no lleva parámetros** | PTP 2 pág. 34 | guardia mode 2 (v0.11) |
| `0x9251` devuelve **una** propiedad | PTP 3 págs. 172-173 | razón para descartarlo |
| `0x9215`: sus 28 tipos de lista, ninguno de exposición | PTP 3 págs. 127-129 | razón para descartarlo |
| Matriz de compatibilidad código × modelo | PTP 3 págs. 87-109, PTP 2 págs. 25-27 | `SonyDocumentedCodes` (v0.10) |
| a7 III: Ajustes → Conexión USB → PC Remote | guía ILCE-7M3 | mensaje por generación (v0.8) |
| a7 III: requiere *Ctrl con smartphone* = Off | guía ILCE-7M3 | ídem |
| a7C: Conexión USB **no** ofrece PC Remote; va por Red | guía ILCE-7C | ídem |
| a7R IV: Red → Función PC Remoto + método USB | guía ILCE-7RM4 | ídem |
| a6700: Ajustes → USB → Modo conexión USB → `[PC Remote]` | guía ILCE-6700 | ídem |
| a7 IV / a7S III: misma ruta, opción `[Disparo remoto]` | guías ILCE-7M4, ILCE-7SM3 | ídem |
| *USB Power Supply*: la cámara **se alimenta** del equipo conectado | guía ILCE-6700 | aviso (v0.8) |

## Verificado en el cable

| Afirmación | Cómo |
|---|---|
| BULB en la a7 III es `0x00000000` | leído con el dial en BULB |
| 15" es `0x0096000A`, 30" es `0x012C000A` | leídos del cuerpo |
| f/7.1 es `0x02C6` | leído del cuerpo |
| `0xFFFFFFFF` llega de verdad en D20D al arrancar | visto en el log |
| La cámara marca `isEnabled` 1→0 mientras expone y vuelve a 1 al acabar | medido en un disparo de 15" |
| La a7 III graba desde modo M por PTP | probado: pasó a `Movie-M` |
| Dos `MainActivity` provocan el fallo de `bulk write` | dos hilos con `open:` en el log |
| El endpoint bulk-IN se atasca (26 ms, no timeout) al pedir live view apagado | medido |
| Cuatro resets USB se aplican y no reviven un cuerpo colgado | probados uno a uno |
| Nada del sistema disputa la interfaz de la a7 III: `claimInterface(force=false)` basta | v0.19, conexión limpia sin forzar |
| Tras matar el proceso en caliente, lo que recupera la sesión es vaciar la entrada (156 556 bytes rancios), no el reset 0x66 ni el claim forzado | v0.19, `drainInput` seguido de handshake completo |
| La a7 III presenta `vendorExt=0x11` con `9201 9202 9205 9207 9209` | informe de compatibilidad, v0.19 |
| Desenchufar y volver a enchufar reasigna **la misma ruta usbfs** (`001/002`) | v0.19, medido en el ciclo completo |
| Tras ese ciclo, con el mismo proceso vivo, la app reconecta sin claim forzado ni reset | v0.19: 0 líneas de `forcing`, 0 de `PTP device reset` |
| Sin claim forzado ni reset 0x66 **en el código**, la a7 III sigue conectando: handshake completo en 1,3 s | v0.22, tras eliminarlos |
| El objetivo montado abre a **f/4.5**, y el cuerpo lo demuestra negándose a mover la muesca | v0.20, `seek STALL` a f/4.5 |
| El auto-arranque depende de la casilla *"Abrir siempre …"* del diálogo de permiso, que estaba **sin marcar** | v0.22, visto en el diálogo |
| En modo **S** la a7 III marca `0x5007` como `en=2 write=READ_ONLY` y `0xD20D` como `en=1 write=NOTCH` | v0.22, informe con `shooting raw=0x04` |
| Con el diafragma en READ_ONLY la casilla queda atenuada (*"A / M only"*) y no abre rueda: pulsarla no genera ni una línea de `seek` | v0.22, medido pulsando y arrastrando |
| `0x5005` **sí** es enumeración real (15 valores, `max=0x8030`), a diferencia de los rangos de relleno de diafragma y obturación | v0.23 |
| `0xD20F` pasa de `en=0 READ_ONLY` a habilitado **cuando el balance entra en T. color**; el acoplamiento lo hace la cámara | v0.23, visto en pantalla |
| WB y temperatura se escriben como **UINT16** (su tipo declarado), no como el UINT32 del cuarteto de exposición | v0.23: `data=1180`→0x8011 y `data=A816`→5800, ambos `resp=0x2001` |
| El desplazamiento de WB va de 164 a 220 con centro 192, y **4 unidades = 1 paso** | v0.25: 57 y 29 valores anunciados = los "57-step / 29-step" de la ficha de la a7 III |
| **`0xD210` a 220 pone la imagen verde y `0xD21C` a 220 la pone ámbar** — la primera letra del nombre de Sony es el extremo *alto* | v0.25, cada eje movido por separado y leída la vista en vivo |
| En la ILCE-7M3 probada, el signo **positivo aleja el foco y el negativo lo acerca** en `0xD2D1` | Corregido en v0.40 con un barrido grabado: las órdenes negativas pasan del fondo nítido a la caja cercana nítida y después a un plano más cercano. La conclusión de v0.26 tenía los sentidos invertidos. |
| `0xD2D1` se escribe como **INT16** (`data=FFFF` = −1), no con el byte único de `stepControl` | v0.26, `resp=0x2001` en las 39 órdenes |
| La a7 III marca `0x500A` como *display only* (`en=2`) y acepta escrituras igualmente | v0.26, casilla FOCUS activa y funcionando |
| `0xD2D1` acepta el rango entero de velocidad: un arrastre manda `+1…+7` y `−1…−7` seguidos | v0.27, 18 órdenes en 2,5 s, todas aceptadas |

## Terceros, y marcado como tal

| Afirmación | Fuente | Estado |
|---|---|---|
| *USB Power Supply* tumba el enlace | Monitor+ | **No está en Sony.** El mensaje de la app lo ofrece como algo que probar, no como la causa |
| Qué firmware movió el menú (a7S III 3.00, FX3 2.00) | Monitor+ | **No confirmado.** Se quitaron los números del mensaje; queda la ruta alternativa sin versión |
| Live view por PTP desde 2016; la a6000 no | informes de libgphoto2 | Sólo en el documento de investigación, no dirige código |
| Firmware mínimo (a7 III ≥ 3.00) | resumen de búsqueda | Sólo en el documento; no se comprueba en la app |
| ~~Sony no entrega eventos por el endpoint de interrupción~~ | libgphoto2 sondea 0x9209 | **Medido y refutado**: la a7 III sí entrega `0xC203`. Ver abajo |
| Cambiar AF/MF por PTP puede dejar muerto el dial de enfoque | alpha-fairy | Anotado como aviso; no se toca ese control |
| Recorte APS-C desactiva comandos PTP | alpha-fairy | Anotado |
| Lista de modelos de Monitor+ | Monitor+ | Es su propia app; se cita como tal |

## Medido, y contradice a la fuente de terceros

`libgphoto2` no se fía del endpoint de interrupción para Sony y sondea `0x9209`;
de ahí deduje que los eventos probablemente no llegaban. **Llegan.** Con la
a7 III conectada:

```
interrupt: 0xC203 tid=-1 params=0xD21E     (ISO)
interrupt: 0xC203 tid=-1 params=0xD21D     (estado de película)
```

`0xC203` es *DevicePropChanged*, con el código de la propiedad como parámetro y
`tid = -1` (0xFFFFFFFF), la convención de los eventos asíncronos.

**Pero la medición está sesgada y no zanja la pregunta de diseño.** La sonda vive
dentro del bucle de sondeo, y ese bucle **se salta durante un seek o una
captura** — exactamente cuando más propiedades cambian. En el cambio de ISO de
400 a 800 no se leyó el endpoint en ningún momento del seek; el único evento
llegó después de terminar.

Así que lo establecido es: **el endpoint entrega eventos**. Lo que sigue sin
saberse es si los entrega todos, y eso requiere leerlo también durante seek y
captura antes de plantearse sustituir el sondeo por eventos.

## Sin verificar de ninguna forma

- **El sondeo diferencial en hardware** (v0.11). Sólo la a6700 puede ejercitarlo.
- ~~**Los centinelas de exposición** (v0.9) en funcionamiento~~ — **visto en
  pantalla** (v0.19): con el dial en BULB la lectura pone `BULB` y el botón
  `BULB ABRIR` queda activo, que es justo lo que `ShutterReading` separa.
- **La tabla de compatibilidad** contra un cuerpo *mode 3*. Contra la a7 III ya
  se ejercitó: `sonyDocs: la cámara ofrece todo lo que Sony documenta para
  ILCE-7M3` y los cuatro códigos de almacenamiento fuera de la referencia,
  exactamente lo que predecía la prueba JVM.
- **UVC junto a PC Remote** (§11 de la investigación): no se sabe si conviven.
- *(nada pendiente del arrendamiento: el ciclo detach/attach quedó medido)*
