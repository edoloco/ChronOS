# ChronOS — guía para IAs (y personas) que trabajan en este repositorio

Lee este archivo **antes** de abrir el código: está hecho para que no tengas que leer las ~27.000 líneas para
orientarte. Responde y comenta siempre **en español** (el dueño del proyecto escribe en español, desde el teléfono).

## Qué es

ChronOS une **dos teléfonos Android como si fueran uno** (unidos por el lado largo en una carcasa impresa en 3D):

| Orientación | Principal | Extendido |
|---|---|---|
| **Horizontal** (uso normal) | **arriba** | abajo |
| Vertical | izquierda | derecha |

- **Principal** (Redmi 9S): corren las apps, suena el audio, está la accesibilidad.
- **Extendido** (Poco M8 Pro): pantalla extra, teclado, controles, herramientas.
- Se comunican por **WiFi local** (sin internet, sin Bluetooth para la conexión).
- En el código: `side == 1` = principal, `side == 0` = extendido. La pantalla 0 del escritorio es siempre el principal.

## Reglas que no se rompen

1. **Paquete `cl.mesadoble`**: no cambiarlo nunca (los teléfonos perderían la app y sus datos).
2. **Java, sin AndroidX ni librerías externas.** `minSdk 28`, `targetSdk 34`, AGP 8.5.2. APIs más nuevas que
   Android 9 → por reflexión o con `Build.VERSION.SDK_INT`.
3. **Dos versiones** (flavors): `completa` (con servicio de accesibilidad `MesaAccess`) y `liviana` (sin él,
   Play Protect no la bloquea). Todo lo que use `MesaAccess` debe funcionar o avisar bien si no está.
4. **No trabajar directo en `main`.** Cada envío a `main` compila y **publica una versión** que los teléfonos
   instalan solos. Trabaja en tu rama y abre un *pull request*; el dueño lo aprueba con "Merge".
5. **Nada de "lanzar/traspasar ventanas" entre pantallas** con una flecha: se probó, trabó el tacto y se quitó
   a pedido. No volver a proponerlo.
6. Recursos nuevos: se buscan con `getIdentifier(...)` (el chequeo local usa un `R` mínimo).
7. Textos para el usuario en español de Chile, cortos y claros. Comentarios del código también en español.

## Diseño visual

- **Naranja + cristal + verde** ("como una naranja"): naranjo `#F57C1F`, verde `#2FA866`.
- `Glass.java`: vidrio esmerilado ("cristal intenso") con `Glass.body(...)` y `Glass.shadow(...)`.
- Sombras difuminadas para todo lo que flota sobre un fondo.
- DUAL-Draw: fondo gris, panel derecho naranjo, demás paneles blancos con verde, botones solo con símbolo.
- PALS: fondo naranja, panel izquierdo de cristal naranjo (árbol), contenido en un cristal naranjo grande.

## Mapa del código (`app/src/main/java/cl/mesadoble/`)

**Base**
- `Link.java` — conexión TCP por WiFi entre los dos teléfonos (latido cada 5 s).
- `Hub.java` — conexión única de toda la app; **router de mensajes JSON** (`{"t":"tipo", …}`) y
  `startOwn/startExternal` para abrir pantallas. Si agregas un mensaje nuevo, su `if ("tipo".equals(t))` va aquí.
- `MainActivity.java` (la más grande) — el escritorio de cada teléfono, menús (Herramientas, Ajustes), intro.
- `DeskView.java` — dibuja y maneja la mitad del escritorio de este teléfono. `Item.java` — elementos de la mesa.
- `Ui.java` — diálogos (`Ui.Sheet`: `title`, `message`, `option`, `view`, `button`, `show`).
- `MesaAccess.java` — accesibilidad del principal (gestos, capturas, cambio de teclado). `Power.java`, `PermCheck.java`.
- `Updater.java` — actualización desde GitHub Releases (repo `edoloco/ChronOS`; los APK se llaman `MesaDoble-*.apk`).

**Teclado y control**: `KeyboardActivity`, `KeyboardView` (PC y tipo Android), `RemoteIme` (teclado que recibe),
`TouchpadView`, `GamepadView`, `BtPad`, `Dict`, `Speller`.

**Funciones**
- DUAL-Draw (dibujo en dos pantallas): `DualDrawActivity` (interfaz) + `DrawDoc` (motor: capas, pinceles,
  selección, transformar). Los dos teléfonos aplican **las mismas órdenes en el mismo orden** (`dw {op…}`).
- Game Pal (control de Brawl Stars en el extendido): `GamePalActivity` + `GamePalTouch` (dedos virtuales en el principal).
  El gamepad de `KeyboardActivity` (`GamepadView`) usa esos mismos dedos (`pad` → `MesaAccess.pad` → `GamePalTouch.touch`); al editar
  pide una captura del principal (`gpShot`/`gpImg`) para ubicar los controles, y tiene botones − / + de tamaño.
- PALS (apps web acomodadas a horizontal): `Pals` (recetas, catálogo de redes sociales y de Google, íconos) +
  `PalActivity` (WebView con panel en árbol). Google no deja iniciar sesión dentro de un WebView: los PALS de
  Google pueden abrirse en una pestaña de Chrome.
- Archivos: `FilesActivity`, `FilePane`, `FileServer`. Fotos/música/video: `MediaHub`, `PhotoActivity`, `PlayerActivity`, `VideoActivity`.
- Sonido repartido: `Boost`, `CaptureService`. Llamadas: `CallHub`, `CallActivity`, `Phone`.
- Pantallas unidas y tapa: `ScreenSync`, `LidWatch` (imán), `LockArt` (fondo de bloqueo), `SensorTestActivity`.
- Barra y notificaciones: `ChronoBar`, `TickerView`, `NotifService`, `NotifCard`, `RecentsBar`.
- Instalado en el sistema (J7 Prime): `SystemSetup` + carpeta `sistema/` (zip para TWRP).

## Compilar y publicar

- GitHub Actions (`.github/workflows/build.yml`) compila con cada envío a `main` y publica:
  `MesaDoble-completa.apk`, `MesaDoble-liviana.apk`, `ChronOS-sistema.zip`.
- Versión automática por hora de compilación: `versionCode` = minutos desde 1970, `versionName` = `1.aaMMdd.HHmm` (hora de Santiago).
- Cambios solo en archivos `.md` no publican versión.
- Chequeo rápido sin Gradle (opcional): `javac` contra `android.jar` (API 28) y un `R` mínimo.

## Antes de entregar un cambio

- Que compile. Que no rompa la versión `liviana` (sin `MesaAccess`).
- Si el cambio toca a los dos teléfonos, piensa en ambos lados (`isPrincipal()`) y en qué pasa sin conexión.
- Describe en el pull request, en español y en pocas líneas, qué cambia para el usuario.
