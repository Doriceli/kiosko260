# Kiosko Video QR (Android)

App para un celular/tablet "kiosko" que:

1. **Recibe videos por Quick Share** (los detecta solo en cuanto llegan).
2. **Los edita**: marco (`marco.png`) sobre todo el video, logo (`logo.png`) mediano arriba a la izquierda, **cámara lenta** (x0.5) y **música** en lugar del audio original.
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

### a) El marco y el logo
Sustituye estos archivos (ya traen un ejemplo para que puedas probar):

- `app/src/main/assets/marco.png` → **PNG con el centro transparente**. Diséñalo en **1080 × 1920** (vertical) si los videos son verticales; se estira al tamaño del video.
- `app/src/main/assets/logo.png` → PNG (con fondo transparente si quieres). Se coloca arriba a la izquierda ocupando el 30 % del ancho.

### b) La música
Copia tus canciones (`.mp3`, `.m4a`…) en `app/src/main/assets/music/`.

- En la app eliges la canción o "🎲 Aleatoria".
- Truco: si el archivo se llama `cancion@52.mp3`, la música empieza en el segundo 52 (directo al coro).
- La canción se repite o se corta para durar exactamente lo mismo que el video.

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
| `LOGO_WIDTH_FRACTION` | `0.30f` | Tamaño del logo (30 % del ancho). |
| `LOGO_MARGIN_FRACTION` | `0.04f` | Separación del logo respecto a los bordes. |
| `QUICK_SHARE_FOLDER_LIKE` | `%Quick Share%` | Carpeta que se vigila. Samsung usa `Download/Quick Share/`. **En Pixel/Motorola/Xiaomi, Quick Share guarda en `Download/`: cámbialo a `Download/%`.** |
| `MAX_SHORT_SIDE_PX` | `1080` | Resolución máxima (Full HD). |
| `KEEP_OUTPUT_HOURS` | `48` | Los videos editados se borran pasado este tiempo. |

### d) Cambiar marco/logo/música sin recompilar
La app también busca los archivos en
`Android/data/com.kiosko260.videoqr/files/personalizacion/` (`marco.png`, `logo.png`, `music/*.mp3`).
Copia ahí los archivos por USB desde la PC y tienen prioridad sobre los del APK. La ruta exacta se muestra al pie de la pantalla de la app.

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
| Sin música | No hay archivos en `assets/music/` ni en la carpeta de personalización. |

## 7. Ideas para después

- **Descarga desde cualquier lugar (sin compartir Wi-Fi)**: subir el video a Firebase Storage / Google Drive / S3 y poner en el QR el enlace público. Requiere internet y una cuenta en la nube.
- Galería en el kiosko con los últimos videos y su QR.
- Varios marcos para elegir según el evento.

## Estructura del código

| Archivo | Qué hace |
|---|---|
| `Config.kt` | Todos los ajustes. |
| `QuickShareWatcher.kt` | Vigila MediaStore y avisa cuando llega un video nuevo a la carpeta de Quick Share. |
| `VideoProcessor.kt` | Edición con Media3: `SpeedChangeEffect` (cámara lenta), `Presentation` (tamaño), `OverlayEffect` (marco + logo) y una secuencia de audio en bucle con la canción. |
| `Customization.kt` | Carga marco, logo y canciones (assets o carpeta de personalización). |
| `VideoServer.kt` | Servidor web local (página de descarga y archivo MP4 con soporte de *Range* para iPhone). |
| `QrCode.kt` / `NetworkUtils.kt` | Genera el QR y averigua la IP local del kiosko. |
| `KioskoApp.kt` | Cola de edición (un video a la vez) y arranque del servidor. |
| `MainActivity.kt` | Pantalla del kiosko: estado, progreso, QR, selector de canción. |
