# Validación multimodelo sin una cámara de cada referencia

SonyMonitor separa **compatibilidad** de **certificación física**. La primera se
negocia con la cámara y se prueba por replay; la segunda confirma quirks de un
cuerpo/firmware concreto.

## 1. Pruebas sin cámara

Desde PowerShell, en la raíz del proyecto:

```powershell
$env:JAVA_HOME = 'C:\ruta\a\jdk-17'
.\gradlew.bat testDebugUnitTest
```

El banco cubre:

- blobs `0x9209` mode 2 y mode 3;
- la segunda lista enum de firmwares Sony recientes;
- datos truncados, tipos desconocidos y resincronización;
- selección de escritura absoluta/notch/botón;
- normalización de modos y listas ISO/f-stop/shutter/EV;
- captura clásica `D2C1+D2C2` y moderna `D2E6`;
- framing PTP con headers partidos, DATA+RESPONSE juntos, eventos, TID stale,
  longitudes/códigos inválidos y recuperación del stream;
- handshakes completos representativos de A7 III y a6700.

Estos tests son la barrera de regresión diaria. No requieren USB ni cámara.

## 2. Matriz física mínima

| Ruta | Cuerpo representativo | Para qué se usa |
|------|-----------------------|-----------------|
| Sony mode 2 / sesión clásica | A7 III | Evitar regresiones del camino ya probado |
| Sony mode 3 / sesión moderna | a6700 | Certificar `0x9210`, `D2E6`, controles absolutos y preparación de Live View |

No hace falta comprar o conectar todos los modelos. Un cuerpo adicional sólo
entra a la matriz cuando su firmware anuncia una combinación de capacidades no
cubierta o presenta un quirk reproducible.

## 3. Pase físico de 10 minutos

1. Actualizar el firmware y seleccionar `Remote Shoot (PC Remote)`; en cuerpos
   recientes activar también `PC Remote Function`.
2. Conectar por cable de datos directo y aceptar el permiso USB de Android.
3. Confirmar que la app muestra modelo, exposición y una imagen durante al
   menos 30 segundos.
4. En modos M/A/S/P apropiados, cambiar ISO, f-stop, shutter y EV. Un control no
   permitido por la cámara debe aparecer deshabilitado, no fingir éxito.
5. Tomar una foto, esperar su descarga y comprobarla en
   `Pictures/SonyMonitor`.
6. En modo Movie, iniciar y detener REC.
7. Pulsar `DIAG` y conservar el archivo
   `Downloads/SonyMonitor-diag-<modelo>-<fecha>.txt` junto con modelo y versión
   de firmware.

## 4. Criterio de fallo seguro

- Generación Sony no confirmada: ajustes y captura quedan deshabilitados.
- REC no anunciado: botón REC deshabilitado.
- Disparador no anunciado: botón PHOTO deshabilitado.
- Live View no soportado o tres probes fallidos: sólo se deshabilita la imagen;
  lecturas y controles siguen activos.
- USB en MTP/Streaming: la app pide PC Remote en vez de asumir que es otro
  modelo incompatible.
