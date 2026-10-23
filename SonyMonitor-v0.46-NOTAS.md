# Sony Monitor 0.46 — cadencia regular del live view al grabar

Instalada y probada el 25 de septiembre de 2026 en el Pixel 10 con la Sony
ILCE-7M3 conectada por USB, grabando en 4K.

## Problema

Al moverse con la cámara mientras grababa, el live view del teléfono temblaba
mucho más que la pantalla de la cámara.

Medido: mientras graba en 4K, el A7 III sirve el live view por USB a solo
~15 fps (30 sin grabar), con JPEG más pequeños (~75 KB frente a ~125 KB) y
huecos muy irregulares entre fotogramas, de 40 a 170 ms. La pantalla de la
cámara sigue a 60 fps. Con la cámara en movimiento, esos saltos desiguales se
perciben como temblor.

Espaciar las consultas de la app (cada 30 ms en lugar de 8) no cambió nada: la
cámara siguió dando ~15 fps. El límite es de la cámara, y el A7 III no ofrece
por PTP ningún ajuste de calidad o velocidad del live view (0xD26A solo existe
en cuerpos más nuevos). Para más fotogramas mientras graba haría falta la
salida HDMI con una capturadora.

## Cambio

Mientras la cámara graba, cada fotograma se muestra a un intervalo regular en
lugar de en cuanto llega (`FramePacer`):

- El intervalo es la media de los huecos de llegada: una media simple de los
  primeros fotogramas y después una media móvil.
- Un fotograma nunca se muestra antes de llegar, ni se retiene más de 1,5
  intervalos (~100 ms a 15 fps).
- Todos los fotogramas pasan por una sola cola, en orden. Al empezar o
  terminar de grabar nunca aparece uno viejo después de uno nuevo.

Sin grabar, los fotogramas se muestran en cuanto llegan, como antes, sin
retraso añadido. La app anota en el diagnóstico cada cambio de modo.

## Verificación en el teléfono (grabando en 4K)

| | Huecos entre fotogramas en pantalla |
|---|---|
| Según llegan (0.45) | 40–146 ms |
| 0.46, primeros ~2 s tras REC | 32–200 ms (la cámara se detiene al arrancar la grabación) |
| 0.46, después | 63–72 ms, casi todos entre 65 y 75 |

Sin grabar: ~30 fps sin cambios (huecos de 16–62 ms, sin retraso añadido).

Los registros de depuración `LiveViewPerf` (llegada) y `LiveViewShown`
(pantalla) guardan estos histogramas y cuentan los fotogramas repetidos
(ninguno medido).

Compilación y Android Lint correctos. 256 pruebas JVM sin fallos; seis nuevas
cubren el reparto de fotogramas: huecos más regulares, nunca antes de llegar,
retraso acotado, flujo regular sin tocar, resincronización tras una parada y
reinicio.

APK: `SonyMonitor-v0.46-cadencia-al-grabar-debug.apk`.

SHA-256: `E7258696A7911960522C7C40A22EF039507D3A9DF1EB2100290C3E6367ECC0ED`.
