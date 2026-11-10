# ChronOS — guía para IAs (y personas) que trabajan en este repositorio

Lee este archivo **antes** de abrir el código: está hecho para que no tengas que leer las ~27.000 líneas para
orientarte. Responde y comenta siempre **en español** (el dueño del proyecto escribe en español, desde el teléfono).

## Qué es

ChronOS une **dos teléfonos Android como si fueran uno** (unidos por el lado largo en una carcasa impresa en 3D):

| Orientación | Principal | Extendido |
|---|---|---|
| **Horizontal** (uso normal) | **arriba** | abajo |
| Vertical | izquierda | derecha |

- **Principal** (Redmi Note 8): corren las apps, suena el audio, está la accesibilidad.
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
8. **Las dos pantallas son UNA sola.** Lo que se reparte entre los dos teléfonos (lista de notificaciones, gestor
   de archivos) se comporta como un solo elemento: se mueve junto, sin bordes en la unión. La franja blanca de los
   dibujos del dueño es el espacio físico **entre** los teléfonos (fuera de las pantallas).
9. **"Principal disponible"** = el principal muestra ChronOS (`hub.foreground`; en el extendido llega como
   `here` en el mensaje `ticker`). Burbuja, banda y lista de notificaciones van en el principal (o en los dos) si
   está disponible; si el principal está en otra app, **solo en el extendido**.
6. Recursos nuevos: se buscan con `getIdentifier(...)` (el chequeo local usa un `R` mínimo).
7. Textos para el usuario en español de Chile, cortos y claros. Comentarios del código también en español.

## Diseño visual

- **Naranja + cristal + verde** ("como una naranja"): naranjo `#F57C1F`, verde `#2FA866`.
- `Glass.java`: vidrio esmerilado ("cristal intenso") con `Glass.body(...)` y `Glass.shadow(...)`.
- Sombras difuminadas para todo lo que flota sobre un fondo.
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
- **vertical-keyboard** (modo `VERT` de `KeyboardActivity`): arriba un touchpad que solo desliza (el gesto se repite
  en el centro del principal: `vswipe {f, ms}`, `vtap`), abajo el teclado Android con fila de números y teclas
  cuadradas (`KeyboardView.setNumberRow`, `squareHeight`). Sale cuando **la app del principal está en vertical** (pantalla del
  principal más alta que ancha, sin importar cómo estén los teléfonos → `pPortrait {on}`; `Hub.wantVertKb()`, pref
  `vertKbAuto`) y hay que escribir; si la app pasa a horizontal, el teclado pasa al normal. Emojis: todos (`EmojiData`, generado), por categoría y páginas.
- Inicio (botón de navegación o el del sistema): `goDesk` en los dos, cierra el gestor de archivos y el menú inicio.
- Widget de baterías: estilo "Horizontal moderno" o "Vertical retro" (Mega Man), en `Item.sz` (0/1), menú de los 3 puntos.
- Tapa (`LidWatch`): sensores elegibles (imán, proximidad, luz; "todos" o "cualquiera"), prefs `lidSensors`/`lidAll`, mensaje `lidSens`.

**Funciones**
- **DUAL Draw ya no está en ChronOS** (nov. 2026): es la app aparte `cl.dualdraw` (repo `edoloco/dual-draw`, su propia
  conexión en los puertos 47832/47833, ver su AGENTS.md). ChronOS la trata como app de dos pantallas (`Hub.DUAL_APPS`);
  Herramientas › DUAL Draw y el ícono viejo `dd:draw` de la mesa la abren (o llevan a su página de descarga).
- Barra de abajo del extendido (`ChronoBar` NAV): baterías P y E fijas debajo de donde aparece la hora; Ajustes → Apariencia →
  Mostrar baterías: no / retro (Mega Man) / moderna (ícono de celular), de pie o acostadas (`battSkin`, `battUp`, `battPref`) (la barra de tareas deja ese espacio libre). Íconos de apps: giran según la posición del
  PRINCIPAL (`pOrient {q}`), nunca a 180°.
- Separar al alejarlos: `Proximity` (Ajustes → Configuración y pruebas). Por imán (el principal mide µT; calibración
  `sepMagJ` con los dos juntos) o por Bluetooth (el extendido emite BLE con UUID propio, el principal mide RSSI). Separados
  8 s → teléfono normal (`PhoneMode`, preguntando o directo según `sepAsk`); juntos 5 s → vuelve ChronOS. `sepPref`, `sepCal`.
- ENFOQUE: ChronOS asiste y administra al PRINCIPAL. Fotos, archivos y apps están en el principal: lo que se elige
  desde el extendido (fondo de pantalla `wallPick`) se abre en el principal.
- Apps de dos pantallas de otros repos (DUAL Motion `cl.dualmotion`, o cualquiera con meta-data `chronos.dual`):
  si el principal la abre, `MesaAccess` manda `dualOpen` y el extendido abre la misma app (`Hub.openDualCompanion`).
- Abrir pantallas desde segundo plano (Android 15): `Hub.blip()` pone una ventanita de 1 px antes de `startActivity`
  (startOwn/startOrNotify/openKeyboard). En Xiaomi además hace falta "Mostrar ventanas emergentes en segundo plano".
- Apariencia del otro teléfono (`cardTheme`, `bg`, `wallImg`, `wallOff`, `kbWallPref`, `battPref`) la guarda `Hub` aunque
  la mesa esté cerrada; `MainActivity.applyLookIfChanged` la aplica al volver (`lookSig`).
- ChronOS debe funcionar en cualquier teléfono (el principal ahora es un Redmi Note 8): nada fijo a un modelo.
- Teclado sin cuadro de texto: `RemoteIme` guarda lo escrito (no lo manda como atajos: en YouTube el espacio pausaba),
  `MesaAccess.focusTextField` busca el cuadro (o el "Di algo…") y lo toca; al aparecer un cuadro se escribe ahí (20 s).
  El botón de modo del teclado ignora toques justo después de escribir (`KeyboardView.lastTouchAt`).
- Escritura pulcra (`KeyboardActivity` + `RemoteIme` + `Dict`):
  - Orden: si hay letras esperando cuadro de texto, las nuevas van DETRÁS (antes "saludos" llegaba "asludos").
  - Acción `kb` `word {old, s}`: cambia de una vez la palabra antes del cursor (sugerencias y correcciones; antes
    borraba con teclas sueltas y quedaba "cucumpleaño"). El borrar normal usa `deleteSurroundingTextInCodePoints`.
  - `Dict.splitSpace`: palabra desconocida con n/m/b/v/c/ñ en medio y dos palabras conocidas a los lados →
    "tengonsueño" → "tengo sueño" (si la letra calza en una mitad, solo faltó el espacio). "hola.como" → "hola como"
    (punto pegado entre dos palabras conocidas en minúscula, no dominios). Pref `kbSpaceFix` (def. sí).
  - Borrar el espacio recién puesto → correcciones en ámbar en la barra (`Dict.corrections`; si hubo arreglo
    automático, la 1ª opción lo deshace). Se aplican con `word`.
  - Diseño Android: franja libre de 7dp sobre la fila del espacio (cuenta como esa fila en `keyAt`), fila más alta
    (1.2), espacio 6 unidades, coma y punto 0.8 y de tono distinto (no se confunden con la n ni el espacio).
- Ajustes → Configuración → **Teclados automáticos** (`kbAutoMenu`, se copian al otro con `prefSet`): `autoKb`
  (cuadro de texto), `autoKbChat` (apps de chat, `openKb`), `autoVideo` 0 control de video / 1 teclado / 2 nada
  (`mActive` en Hub), `autoAsist` (RetroArch), `vertKbAuto`, `kbSpaceFix`.
- **Grupos (carpetas)** (`Item.GROUP`, título = nombre): mantener presionado un espacio vacío de la mesa
  (`DeskView.emptyLong` → `Host.onEmptyLongPress`) → Crear grupo / Cambiar fondo de mesa. Se ve como app
  (`DeskView.appLike`: mismo cuadrado y tabla), siempre de cristal, con hasta 9 miniaturas (`drawGroup`).
  Arrastrar un elemento sobre un grupo (se agranda al pasar, `hoverGroup`) lo mete: `it.grp = id`, `page = -1`
  (fuera de la mesa; `Item.fromJson` respeta el -1 si tiene `grp`). Tocar el grupo → `onOpenGroup` (cuadrícula:
  carpeta de cristal al centro sobre la pantalla borrosa (`blurredScreen` + `boxBlur`), íconos por nombre en
  cuadrícula de 4: tocar abre, mantener saca a la mesa; ⋯ arriba a la izquierda: Cambiar nombre, Desarmar).
  Borrar un grupo devuelve lo de adentro a la mesa. El menú del espacio vacío también trae Herramientas.
- **Botón Inicio** (`MainActivity.onNewIntent` con CATEGORY_HOME): cierra los teclados (`KeyboardActivity.closeAll`,
  cualquier modo) aquí y en el otro (`homeAll`; allá vuelve la mesa si había teclado). Si la mesa ya se veía
  (`stoppedSinceResume` falso), vuelve a la primera mesa.
- La mesa NO se reacomoda sola: ni al volver (antes `alignApps` en `onResume`, que con un tamaño de pantalla
  pasajero —al actualizar o girar— desordenaba) ni al agregar una app (antes `arrangeApps` las reordenaba todas por
  nombre). La app nueva va a la primera celda libre; ordenar es solo a mano (Escritorios › Ordenar y alinear).
- **Pasar videos y páginas** (`MoveTo`, pref `moveArrow`, en Configuración y pruebas): cada teléfono mira su app
  del frente (`MesaAccess` → `onFront`, en los dos). Con YouTube o un navegador, ahí mismo sale una flecha naranja
  chica sin letras (1,6 teclas de ancho, degradado + brillo + sombra, flota): en el principal abajo a la izquierda
  apuntando abajo; en el extendido arriba a la izquierda apuntando arriba (devolver). Tocar → `request` local
  (YouTube: primero el id de su sesión si lo trae; si no, Compartir › Copiar vínculo, y si no se ve: toque al
  centro para mostrar controles y luego Atrás para salir de pantalla completa; el segundo se toma antes; se pausa.
  Navegador: barra de direcciones) →
  `moveOpen {url, pkg}` al otro, que lo abre en su misma app o en la de siempre; aquí se va al inicio. Lanzarla fuera
  de la pantalla la descarta para esa app. Compartir › "Al otro teléfono" (`MoveTo.ShareIn`) sirve a mano.
  - Leer lo copiado: Android solo deja leer el portapapeles a la app del frente o al teclado; si no se puede,
    `MoveTo.ClipGrab` (ventana invisible) toma el foco, lo lee y se cierra. Respaldo: `lastYtId` (el último video
    que llegó del otro teléfono), para devolverlo.
  - Sonido por el principal (pref `moveSound`, def. sí): al llegar un video/página al extendido, este captura el
    sonido de sus apps (`CaptureService` con `rev`, `CaptureActivity` pide "Iniciar ahora"), se silencia (volumen
    de música a 0, se restaura al terminar) y manda el audio; el principal lo toca (`soundHome` →
    `Boost.listen(…, sync=false)`), así suenan sus audífonos Bluetooth. Se detiene al dejar esa app o devolverla.
- **Zona Wi-Fi de un toque** (`Hotspot`): Android no deja encenderla a una app; la accesibilidad abre su pantalla de
  Ajustes, toca el interruptor de la fila "Zona Wi-Fi…" (o el de arriba) y vuelve atrás. Botón de la barra rápida.
- **Menú Apagar** (único botón del inicio; ya no están "Teléfono normal" ni "Cerrar ChronOS"): Apagar teléfonos
  (apagar/reiniciar/bloquear) o Salir de ChronOS; ambos preguntan Los dos / Principal / Extendido (`pickPhones`).
  Salir (`exitHere`, al otro con `exitNow`): teléfono normal + se detiene la conexión; el principal pregunta si apaga
  la zona Wi-Fi.
- Teclado sin cuadro (`RemoteIme`): se busca el cuadro (450/900 ms) y, si la app no abre el teclado (YouTube después
  de mandar un comentario), se escribe directo con accesibilidad (`MesaAccess.typeIntoField`, Enter = ACTION_IME_ENTER).
- Espacios (`Dict.splitSpace`): solo si la palabra no existe, NO está a una letra de una que existe ("malabra" ≈
  "palabra": no se toca) y todas las partes son conocidas (2 letras: top 300; 3 letras: top 3000); hasta 4 partes
  ("tenamonmarcela" → "te amo marcela" si "marcela" ya se escribió varias veces).
- **Baterías de accesorios** (`Peri`): cada teléfono mira sus accesorios (Bluetooth `getBatteryLevel` por reflexión;
  gamepads/teclados/mouse con `InputDevice.getBatteryState`, Android 12+), se los pasa al otro (`peri {l:[{n,k,p}]}`)
  y `ChronoBar.drawBatteries` los dibuja junto a P y E con un iconito (`periIcon`: h audífonos, g gamepad, k teclado,
  m mouse, w reloj, o runa Bluetooth). Solo los que informan carga; máx. 4; si no caben, se achican todas.
- **Conexión** (botón de cadenas en el inicio, antes "Red"): abre `showConnection`. La unión por Bluetooth
  (BLE + red temporal, nov. 2026) se probó y se **quitó**: perdía mensajes y desincronizaba DUAL-Draw. Solo Wi-Fi.
- **Sonido 3D** (`Sound3D`, perilla en "Nivelar volumen (Boost)", pref `boost3d` 0..100, en vivo con `Sound3D.live`,
  se copia con `prefSet`): ensancha el estéreo (M/S), cancela el cruce (resta del otro lado atrasado ~0,22 ms y sin
  agudos) y realza agudos. Completo en `Boost.Engine` (música y video de ChronOS); en `CaptureService` (apps) solo
  se procesa lo que va al extendido (el principal suena tal cual).
- Extendido sin conexión: `Hub.lostLock` lo bloquea a los `lostLockSec` (30/60/120/0 = no) segundos de perder al
  principal (solo si estuvo conectado y no está en modo teléfono).
- Fondo de pantalla (`Wall`): una imagen repartida entre las dos pantallas (`wallImg`/`wallOff`); el teclado la usa
  si `kbWall`. Mesa en cuadrícula por defecto (`snapGrid`), lo nuevo va al primer lugar libre (`DeskView.freeSpot`).
  La mesa es una TABLA invisible (`appGrid`): celdas iguales; cada elemento ocupa un bloque (`span`) que empieza en una
  casilla (media celda de app: app chica 2×2, grande 3×3, en cualquier casilla); nunca se superponen (`blockTaken`). Soltar/redimensionar → `snapItem` al lugar libre más cercano; al volver a la
  mesa `alignApps` acomoda lo de esa pantalla. Sin opción "Imán": siempre activa.
  App instalada → "¿ponerla en la mesa?" (principal/extendida, último escritorio, o uno nuevo). Íconos de la mesa se
  renuevan cuando la app se actualiza (`refreshDeskIcons`, `ic_<pkg>`). Lista de apps: "Elegir varias".
- Chat en vivo de YouTube (solo versión completa): `LiveChat` (lo llama `MesaAccess` con `typeWindowsChanged`). YouTube en
  pantalla flotante (PiP) → burbuja animada con "?" al lado; al tocarla, ventanita gris oscura (overlay de accesibilidad,
  se arrastra por la cabecera y cambia de tamaño por la esquina) con el chat del directo/estreno. Lee el chat público
  (página `live_chat` + `youtubei/v1/live_chat/get_live_chat`, sin cuenta); el video se busca por título y canal de la
  sesión de medios (o 🔗 pegar enlace). Escribir: YouTube Data API con token de `AccountManager` (`YtLogin`); requiere un
  cliente OAuth Android en Google Cloud (paquete cl.mesadoble + SHA-1 de app/debug.keystore). Ajuste `ytChat`.
- Gamepad (modo de Teclados, sin cambios): `GamepadView`, dedos virtuales en el principal con `GamePalTouch`; al editar pide
  captura `gpShot`/`gpImg`. El control de Brawl Stars (GamePalActivity) se eliminó.
- Game-Asist (modo de Teclados, `KeyboardActivity.ASIST`): `GameAsist` (vista en el extendido) + `RetroLink` (principal,
  comandos de red de RetroArch por UDP 127.0.0.1:55355: GET_STATUS, GET_CONFIG_PARAM, SAVE_STATE…). Ficha (libretro-database
  y libretro-thumbnails), "Acerca del juego" y jugabilidad (Wikipedia es/en), partidas guardadas con miniatura
  (`raStates`/`raStList`) y botones (`ra {c}`). Todo se guarda en caché: nada se agrega a mano por juego.
  Nintendo DS (`isDs`): Ficha/Partidas/Acerca pasan a pestañas en la columna izquierda y el centro muestra la pantalla de
  abajo del DS: `DsMirror` (principal, MediaProjection a media resolución, recorta la zona `dsRect`, JPEG ~18 fps por
  `dsFrame`); toques `dsTouch {ph,u,v}` → `GamePalTouch` (dedo 77). "⛶ Zona" (`dsFull`) manda la pantalla entera para marcarla.
- PALS (apps web acomodadas a horizontal): `Pals` (recetas, catálogo de redes sociales y de Google, íconos) +
  `PalActivity` (WebView con panel en árbol). Google no deja iniciar sesión dentro de un WebView: los PALS de
  Google pueden abrirse en una pestaña de Chrome.
- Archivos: `FilesActivity`, `FilePane`, `FileServer`. El gestor (`FilePane`) es el **cuerpo de la carpeta**
  que se abre desde la action bar; lo normal es que las dos ventanas muestren el disco del principal. En el gestor
  del principal está **"Disco extendido"** (`viewDisk = "e"`): ve, abre y borra archivos del extendido por la
  conexión (`fsList`, `fsCat`, `fsThumb`, `fsOp`; las fotos se ven grandes con `fsThumb` hasta 1600 px).
  **Túnel**: copiar/mover entre discos distintos (`fmPush`) lo hace el teléfono dueño de los archivos, que los
  manda uno por uno al `FileServer` del otro (puerto 47821, con clave; encabezados `put: top/dir/file`).
  Copias dentro del mismo disco: `fmJob` en el principal. Avance en las dos pantallas: `fmAnim`.
  Formas de ver (idea de los dibujos de Kron'OS de 2017): íconos (`GridView`), lista y detalle (tabla; en música
  Artista / Tema / Álbum), pref `fmView`. Carpetas con música = **álbum** (`au` en `FileServer.list`): carátula
  (`FileServer.cover`: cover/folder.jpg o la del primer tema), encabezado con nombre, artista, año, duración y
  botones Reproducir / Aleatorio; datos de cada tema con `fsMeta` (`FileServer.meta`). Fotos/música/video: `MediaHub`, `PhotoActivity`, `PlayerActivity`, `VideoActivity`.
- Sonido repartido: `Boost`, `CaptureService`. Nivel manual por teléfono ("Nivelar volumen", entorno con
  perillas `Knob` que se abre en el principal): prefs `gainP`/`gainE`, mensaje `boostGain {p,e}`, tono de prueba
  `boostTone`. Bajo 100 % se atenúa la pista; sobre 100 % se refuerza con `LoudnessEnhancer`. Toda pista de Boost
  pasa por `Boost.track()`/`liveTrack()` (ahí se aplica el nivel). Llamadas: `CallHub`, `CallActivity`, `Phone`.
- Video/música de otras apps (YouTube, Spotify…): `ExtMedia` usa la sesión multimedia (con el acceso a
  notificaciones): `xState` (título, canal, avance, miniatura) y `xCmd` (±10 s, mover la barra, play, siguiente).
  Las teclas multimedia de adelantar no las respeta YouTube: no volver a usarlas para ±10 s. Volumen: `volNow` al
  cambiar; barritas `VolMeter` (estilo Mega Man) entre los botones de volumen. El reproductor propio salta con
  `SEEK_CLOSEST` (VideoView saltaba al cuadro clave y el +10 s parecía no funcionar).
- Pantallas unidas y tapa: `ScreenSync`, `LidWatch` (imán), `LockArt` (fondo de bloqueo), `SensorTestActivity`.
- Barra y notificaciones: `ChronoBar`, `TickerView`, `NotifService`, `NotifCard`, `RecentsBar`, `TaskbarView`, `QuickToggles`.
  - Las action bars son **CARPETAS (folders)**: CLOCK (principal, arriba, pestaña del reloj colgando) y NAV
    (extendido, abajo, pestaña del logo hacia arriba). Las notificaciones (banda) y el reproductor son **hojas**
    que asoman por detrás del fichero. Al abrir el gestor, la carpeta **no se da vuelta**: mantiene su forma, se
    desliza hasta la unión y el gestor es su cuerpo (mismo relleno: `ChronoBar.paintBody`; hoja interior gris
    oscuro). La del extendido queda pegada al techo de la pantalla. Abrir: arrastrar hacia el centro; cerrar: el
    gesto contrario (`ChronoBar.setOpen`, `MainActivity.layoutChrono`).
  - `QuickToggles`: accesos rápidos en la franja de la barra del reloj (linterna, bluetooth, captura, QR, etc.,
    con lápiz para elegirlos; pref `qtSet`). Lo que Android no deja cambiar abre su ajuste o el panel del sistema.
  - `TaskbarView`: apps abiertas del principal; mantener presionado marca para cerrar; botones rojo "Cerrar N" y
    azul "Cerrar todo" (menos ChronOS). No debe bloquear el arrastre de la barra.
  - Burbuja (`NotifCard`): blanca, 4 s. Lista de notificaciones en las dos pantallas: **una sola lista** con un
    solo desplazamiento compartido en dp (`nlS`, mensajes `nlVp`, `nlS`, `nlOpen`, `nlClose`; filas de alto fijo).
- Forzar vista (`ForceView`): un ajuste para el principal (`forceView`) y otro para el extendido (`forceViewE`);
  cada teléfono aplica el de su papel. Mensaje `forceView {v, w}` (w = 1 principal, 0 extendido).
- Ajustes: Pantalla de inicio, Este teléfono es el…, Forzar vista, Apariencia (colores y bloqueo), Intro,
  **Configuración y pruebas** (Nivelar volumen, Game Pal, tapa, sensores, teclado automático, pantalla unida,
  notificaciones, llamadas, revisar configuración), Conexión, Acerca de.
- Instalado en el sistema (J7 Prime): `SystemSetup` + carpeta `sistema/` (zip para TWRP).

## Cómo publica cada instancia

Cada IA/persona que trabaja en el proyecto sube sus cambios a GitHub por su cuenta (rama + *pull request*, o
directo si el dueño lo pidió). Antes de subir: `git fetch` y `rebase` sobre `main`, porque otra instancia puede
haber publicado mientras tanto. El dueño también aplica parches desde Termux en el teléfono (`git apply` +
`gh pr create --fill` + `gh pr merge --merge`).

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
