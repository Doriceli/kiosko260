# Kiosko Video QR (Android)

App para un celular/tablet "kiosko" que:

1. **Recibe videos por Quick Share** (los detecta solo en cuanto llegan).
2. **Los edita**: marco azul/dorado con **#etu2026**, logo (`marco.png` de `Download/INSUMOS`) mediano arriba a la izquierda, **cámara lenta** (x0.5) y **música** en lugar del audio original.
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

### b) El marco (#etu2026)
El marco ya no es una imagen: la app lo dibuja en **azul, dorado y amarillo oro**, con la franja inferior y el hashtag **#etu2026**. Para cambiar el texto o los colores edita `HASHTAG`, `AZUL`, `DORADO` y `AMARILLO_ORO` en `Config.kt`.

### Pantalla del kiosko
- Arriba: el hashtag y el **contador de videos de hoy** (se reinicia solo cada día).
- Al terminar cada video: **vista previa** (fotograma del video, con su número "Video #N · hora") **al lado del QR**, para que cada invitado identifique el suyo.
- La página que abre el QR usa los mismos colores y muestra la miniatura como portada.

> ⚠️ **Importante sobre la música "viral" de YouTube (Elton John, Taylor Swift…)**
> - Descargar audio de YouTube **va contra sus términos de uso**, y esas canciones tienen derechos de autor. Si tu kiosko es un negocio (eventos, bodas, fiestas), usarlas sin licencia puede traerte problemas.
> - Además, **Instagram, TikTok y Facebook detectan la música con derechos** y muchas veces silencian o bloquean el video subido con la canción "pegada".
> - Opciones recomendadas:
>   1. Usar música libre de derechos o con licencia comercial (YouTube Audio Library, Pixabay Music, Epidemic Sound, Artlist…).
>   2. Comprar las canciones (iTunes, Amazon Music) y conseguir la licencia correspondiente si es un uso comercial.
>   3. Entregar el video **sin música** o con una base libre, y que el invitado añada la canción viral desde la propia app de TikTok/Instagram (esas plataformas sí tienen licencia para su catálogo, y así el video además "posiciona" con el audio en tendencia).

### c) Ajustes (opcional) — `app/src/main/java/com/kiosko260/videoqr/Config.kt`

| Ajuste | Por defecto | Significado |
|---|---|---|
| `SLOW_MOTION_SPEED` | `0.5f` | 0.5 = la mitad de rápido; 0.25 = 4 veces más lento. |
| `HASHTAG` | `#etu2026` | Texto de la franja inferior del marco. |
| `INSUMOS_FOLDER` | `Download/INSUMOS` | Carpeta con el logo (`marco.png`) y las canciones. |
| `LOGO_WIDTH_FRACTION` | `0.30f` | Tamaño del logo (30 % del ancho). |
| `LOGO_MARGIN_FRACTION` | `0.06f` | Separación del logo respecto a los bordes. |
| `QUICK_SHARE_FOLDER_LIKE` | `%Quick Share%` | Carpeta que se vigila. Samsung usa `Download/Quick Share/`. **En Pixel/Motorola/Xiaomi, Quick Share guarda en `Download/`: cámbialo a `Download/%`.** |
| `MAX_SHORT_SIDE_PX` | `1080` | Resolución máxima (Full HD). |
| `KEEP_OUTPUT_HOURS` | `48` | Los videos editados se borran pasado este tiempo. |

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
              cámara lenta + marco + logo + canción
                                    │
                                    ▼
          files/editados/<id>.mp4  ──▶  servidor local :8080
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
| Error al editar | Videos 4K/HDR muy largos pueden fallar en equipos de gama baja; graba en 1080p. Revisa el Logcat (filtro `KioskoApp`). |
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
| `VideoProcessor.kt` | Edición con Media3: `SpeedChangeEffect` (cámara lenta), `Presentation` (tamaño), `OverlayEffect` (marco + logo), la canción recortada a la duración final y la miniatura. |
| `DailyCounter.kt` | Contador de videos por día. |
| `Customization.kt` | Carga el logo y las canciones de `Download/INSUMOS` (o carpetas de respaldo). |
| `VideoServer.kt` | Servidor web local (página de descarga y archivo MP4 con soporte de *Range* para iPhone). |
| `QrCode.kt` / `NetworkUtils.kt` | Genera el QR y averigua la IP local del kiosko. |
| `KioskoApp.kt` | Cola de edición (un video a la vez) y arranque del servidor. |
| `MainActivity.kt` | Pantalla del kiosko: estado, progreso, QR, selector de canción. |
