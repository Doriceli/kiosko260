# Kiosko Video QR (Android)

App para un celular/tablet "kiosko" que:

1. **Recibe videos por Quick Share** (los detecta solo en cuanto llegan).
2. **Los edita** en formato vertical para Reels/TikTok: video a pantalla completa (sin margen) con sticker **#etu2026**, logo (`marco.png` de `Download/INSUMOS`) mediano arriba a la izquierda, **speed ramp / cámara lenta progresiva** y **música** en lugar del audio original.
3. **Muestra un QR**: el invitado lo escanea con su celular, ve el video, lo descarga y lo sube a Instagram, TikTok, WhatsApp, etc.

Todo pasa dentro del celular (edición con [Media3 Transformer](https://developer.android.com/media/media3/transformer), servidor web local con NanoHTTPD y QR con ZXing). No hace falta pagar servidores ni tener internet: basta con una red Wi-Fi local.

---

## 1. Qué instalar

| Qué | Dónde | Para qué |
|---|---|---|
| **Android Studio** (última versión estable) | https://developer.android.com/studio | Compilar e instalar la app. Ya incluye el JDK y el Android SDK. |
| Un celular **Android 10 o superior** | — | Será el kiosko. Mejor gama media/alta: la edición de video usa el procesador gráfico. |
| Cable USB | — | Para instalar la app desde la PC. |

No necesitas instalar nada más: las librerías (Media3, NanoHTTPD, ZXing) las descarga Gradle solo.

## 2. Abrir, compilar e instalar

1. En Android Studio: **File → Open** y elige esta carpeta (`kiosko260`). Espera a que termine "Gradle sync" (la primera vez tarda varios minutos).
2. En el celular kiosko activa las **Opciones de desarrollador** (Ajustes → Acerca del teléfono → toca 7 veces "Número de compilación") y luego **Depuración USB**.
3. Conecta el celular por USB, acepta el aviso de depuración, elígelo arriba en Android Studio y pulsa **▶ Run**.
4. Al abrir la app por primera vez, acepta el permiso de **videos** y elige **"Permitir todo"** (si eliges acceso limitado la app no verá los videos nuevos).

Para generar un APK e instalarlo en otros equipos: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

## 3. Qué tienes que editar

### a) Logo y canciones → carpeta `Download/INSUMOS` del celular
En el celular kiosko, en **Almacenamiento interno > Download > INSUMOS** coloca:

- `marco.png` → se usa como **logo** arriba a la izquierda (30 % del ancho del video).
- Tus archivos **.mp3** (los 3 que quieras) → son las canciones que aparecen en la app (o "🎲 Aleatoria").
  - Truco: si el archivo se llama `cancion@52.mp3`, la música empieza en el segundo 52 (directo al coro).
  - La canción se recorta para durar exactamente lo mismo que el video en cámara lenta (si es más corta, se repite).

La app pide permiso de **Videos**, **Música y audio** y **Fotos e imágenes**: acepta los tres (elige "Permitir todo"); sin ellos no puede leer la carpeta INSUMOS y el video queda sin música.

### b) Estilo para Instagram y TikTok
- **Formato vertical 9:16** (Reels/TikTok) **respetando la calidad original**:
  - Videos verticales: salen con **su misma resolución** (1080×1920, 4K…), sin reescalar.
  - Videos horizontales: se recortan al centro a 9:16 con su altura original (sin agrandarlos).
  - **Bitrate igual o mayor que el del video original** (mínimo ≈12 Mbps en 1080p, ≈50 Mbps en 4K).
  - Videos **HDR** (Samsung/iPhone): se convierten a SDR con mapeo de tonos para que no se vean lavados en WhatsApp/Instagram.
  - Colores originales: el retoque de contraste/saturación viene **desactivado** (se puede activar en `Config.kt`).
- **Sin margen**: el video ocupa toda la pantalla (el borde con degradado se puede reactivar con `SHOW_BORDER = true`). Encima va un **sticker #etu2026** pequeño y semitransparente en la **esquina inferior derecha** (tamaño y opacidad: `HASHTAG_SIZE`, `HASHTAG_ALPHA`).
- El logo queda debajo de las pestañas de arriba de TikTok/Reels. Ojo: en TikTok el disco de música giratorio aparece en la esquina inferior derecha y puede tapar el sticker en la vista del feed (en el video descargado siempre se ve).
- Paleta usada en la app y en la página de descarga: `#F68425 #FB3A4B #FF7034 #F54945 #FCAB14 #F178AF #E47493`.

### Efecto de velocidad (se elige en pantalla)
| Opción | Qué hace |
|---|---|
| ⚡ **Speed ramp** (por defecto) | Rápido (1.6x) → frena suave a cámara lenta (0.3x) en el centro → vuelve a acelerar. El clásico de TikTok/CapCut. |
| 🌊 **Cámara lenta progresiva** | Empieza normal y va frenando hasta 0.25x. |
| ✨ **Transición a cámara lenta** | Normal y, a la mitad, pasa suavemente a cámara lenta hasta el final. |
| 🐢 **Cámara lenta constante** | Todo a 0.5x. |

Las curvas están en `SpeedEffect.kt` (puntos `posición → velocidad`) y se pueden ajustar.
> 💡 Para que la cámara lenta se vea fluida, graba los videos a **60 fps** (o en modo cámara lenta del celular). A 30 fps, una velocidad de 0.3x se ve "a saltos".

### Pantalla del kiosko
- Arriba: el hashtag y el **contador de videos de hoy** (se reinicia solo cada día).
- Pestaña **🎬 En vivo**: al terminar cada video aparece su **vista previa** ("Video #N · fecha y hora") **al lado del QR**.
- Pestaña **📁 Procesados**: todos los videos guardados en **Almacenamiento interno > Download > PROCESADOS**. Al tocar uno se **vuelve a generar su QR** (con la IP actual del kiosko) y se muestra en la pestaña En vivo.
- Los videos de PROCESADOS no se borran solos: elimínalos desde la galería o el gestor de archivos cuando ya no los necesites.

### c) Ajustes (opcional) — `app/src/main/java/com/kiosko260/videoqr/Config.kt`

| Ajuste | Por defecto | Significado |
|---|---|---|
| `DEFAULT_SPEED_EFFECT` | `SPEED_RAMP` | Efecto de velocidad al abrir la app. |
| `MAX_OUTPUT_LONG_SIDE` | `3840` | Se respeta la resolución original; solo se reduce si supera 4K. |
| `MIN_BITS_PER_PIXEL` | `6` | Bitrate mínimo (≈12 Mbps en 1080p, ≈50 Mbps en 4K); si el original tiene más, se usa el del original. |
| `MAX_FRAME_RATE` | `60f` | Límite de fps en los tramos acelerados. |
| `CONTRAST` / `SATURATION` | `0f` | Retoque de color opcional (0 = colores originales). |
| `PROCESADOS_FOLDER` | `PROCESADOS` | Carpeta dentro de Download donde se guardan los videos terminados. |
| `SHOW_BORDER` | `false` | Borde de colores alrededor del video (desactivado = sin margen). |
| `HASHTAG` | `#etu2026` | Texto del sticker del marco. |
| `HASHTAG_SIZE` | `4f` | Tamaño del sticker (% del ancho del video). |
| `HASHTAG_ALPHA` | `215` | Opacidad del sticker (255 = sólido). |
| `INSUMOS_FOLDER` | `Download/INSUMOS` | Carpeta con el logo (`marco.png`) y las canciones. |
| `LOGO_WIDTH_FRACTION` | `0.30f` | Tamaño del logo (30 % del ancho). |
| `QUICK_SHARE_FOLDER_LIKE` | `%Quick Share%` | Carpeta que se vigila. Samsung usa `Download/Quick Share/`. **En Pixel/Motorola/Xiaomi, Quick Share guarda en `Download/`: cámbialo a `Download/%`.** |

### d) Carpeta alternativa
Si no se encuentra nada en INSUMOS, la app también busca en
`Android/data/com.kiosko260.videoqr/files/personalizacion/` (`marco.png`, `music/*.mp3`) y, por último, en los assets del APK.

## 4. Preparar el kiosko el día del evento

1. **Quick Share**: en el kiosko, Ajustes → Google → Dispositivos y uso compartido → Quick Share (o en Samsung: panel rápido → Quick Share) → "Quién puede compartir contigo" = **Todos** (o "Todos durante 10 minutos").
2. **Red**: el kiosko y los celulares de los invitados deben estar en **la misma red**:
   - Opción A: un router Wi-Fi propio (lo más estable).
   - Opción B: activa el **hotspot del celular kiosko** y que los invitados se conecten a él (no necesita internet).
   - ❌ Las redes Wi-Fi "de invitados" de hoteles/salones suelen tener *aislamiento de clientes* y el QR **no funcionará**.
3. Abre la app y déjala en primer plano (la pantalla se mantiene encendida). Abajo verás `Servidor activo en http://192.168.x.x:8080`.
4. Recomendado: fija la app en pantalla (Ajustes → Seguridad → **Fijar aplicación**) para que nadie la cierre.

## 5. Cómo funciona el flujo

```
Cámara / celular ──Quick Share──▶ Kiosko (Download/Quick Share)
                                    │  la app lo detecta (MediaStore)
                                    ▼
                         Media3 Transformer
      speed ramp + 9:16 + marco + logo + canción
                                    │
                                    ▼
      Download/PROCESADOS/<id>.mp4  ──▶  servidor local :8080
                                    │
                                    ▼
                QR con http://<ip-del-kiosko>:8080/v/<id>
                                    │
                    invitado escanea ▼
             página con vista previa + botón "Descargar"
```

Si algún equipo no detecta automáticamente los videos, hay dos alternativas:
- En la notificación de Quick Share / en la Galería: **Compartir → Kiosko Video QR**.
- Botón **"Elegir un video manualmente"** en la app.

## 6. Problemas frecuentes

| Problema | Solución |
|---|---|
| No detecta los videos que llegan | Revisa `QUICK_SHARE_FOLDER_LIKE` (ver tabla) y que diste permiso "Permitir todo" a videos. |
| El QR no abre en el celular del invitado | No están en la misma red, o la red tiene aislamiento de clientes. Usa el hotspot del kiosko. |
| En iPhone no se guarda en la galería | Safari lo guarda en Archivos → Descargas; desde ahí "Compartir → Guardar video". |
| Error al editar o tarda mucho | Editar 4K a 60 fps es pesado para equipos de gama media/baja; si falla, graba en 1080p 60 fps (se ve igual de bien en Reels/TikTok). Revisa el Logcat (filtro `KioskoApp`). |
| Sin música | Revisa que los .mp3 estén en `Download/INSUMOS` y que la app tenga el permiso "Música y audio" (Ajustes → Apps → Kiosko Video QR → Permisos). |

## 7. Ideas para después

- **Descarga desde cualquier lugar (sin compartir Wi-Fi)**: subir el video a Firebase Storage / Google Drive / S3 y poner en el QR el enlace público. Requiere internet y una cuenta en la nube.
- Galería en el kiosko con los últimos videos y su QR.
- Varios marcos para elegir según el evento.

## Estructura del código

| Archivo | Qué hace |
|---|---|
| `Config.kt` | Todos los ajustes. |
| `QuickShareWatcher.kt` | Vigila MediaStore y avisa cuando llega un video nuevo a la carpeta de Quick Share. |
| `VideoProcessor.kt` | Edición con Media3: `SpeedChangeEffect` con curva variable, `Presentation` (9:16), `Contrast`/`HslAdjustment`, `OverlayEffect` (marco + sticker + logo), la canción recortada a la duración final y la miniatura. |
| `SpeedEffect.kt` | Curvas de velocidad (speed ramp, progresiva…) convertidas a `SpeedProvider` de Media3. |
| `VideoLibrary.kt` | Guarda y lista los videos de `Download/PROCESADOS`. |
| `HistoryAdapter.kt` | Cuadrícula de la pestaña Procesados. |
| `DailyCounter.kt` | Contador de videos por día. |
| `Customization.kt` | Carga el logo y las canciones de `Download/INSUMOS` (o carpetas de respaldo). |
| `VideoServer.kt` | Servidor web local (página de descarga y archivo MP4 con soporte de *Range* para iPhone). |
| `QrCode.kt` / `NetworkUtils.kt` | Genera el QR y averigua la IP local del kiosko. |
| `KioskoApp.kt` | Cola de edición (un video a la vez) y arranque del servidor. |
| `MainActivity.kt` | Pantalla del kiosko: pestañas, estado, progreso, QR, selector de efecto y canción. |
