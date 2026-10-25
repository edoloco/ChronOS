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
- **vertical-keyboard** (modo `VERT` de `KeyboardActivity`): arriba un touchpad que solo desliza (el gesto se repite
  en el centro del principal: `vswipe {f, ms}`, `vtap`), abajo el teclado Android con fila de números y teclas
  cuadradas (`KeyboardView.setNumberRow`, `squareHeight`). Sale cuando **la app del principal está en vertical** (pantalla del
  principal más alta que ancha, sin importar cómo estén los teléfonos → `pPortrait {on}`; `Hub.wantVertKb()`, pref
  `vertKbAuto`) y hay que escribir; si la app pasa a horizontal, el teclado pasa al normal. Emojis: todos (`EmojiData`, generado), por categoría y páginas.
- Inicio (botón de navegación o el del sistema): `goDesk` en los dos, cierra el gestor de archivos y el menú inicio.
- Widget de baterías: estilo "Horizontal moderno" o "Vertical retro" (Mega Man), en `Item.sz` (0/1), menú de los 3 puntos.
- Tapa (`LidWatch`): sensores elegibles (imán, proximidad, luz; "todos" o "cualquiera"), prefs `lidSensors`/`lidAll`, mensaje `lidSens`.

**Funciones**
- DUAL-Draw (dibujo en dos pantallas): `DualDrawActivity` (interfaz) + `DrawDoc` (motor: capas, pinceles,
  selección, transformar). Los dos teléfonos aplican **las mismas órdenes en el mismo orden** (`dw {op…}`).
  Tableta de dibujo (lápiz USB/Bluetooth) en el **principal**: `dispatchTouchEvent`/`dispatchGenericMotionEvent` la
  capturan (no toca los paneles del principal) y la mandan como `dwPen {a, pts:[x,y,p…], er}`; el extendido la repite
  en su lienzo (lo que se ve, con su zoom) con presión real y circulito al flotar (del porte real de la punta:
  `DrawDoc.radiusFor`). La lista de capas se desliza.
  Pluma (T_PEN, como FreeHand MX): formas vectoriales `DrawDoc.VShape` en `Layer.shapes` (nodos x,y + 2 manillas), orden
  `vec {a:set|del, l, i, s}` con deshacer (`Step.vb/va`). Toca = punto recto, arrastra = curva, primer punto = cerrar,
  último punto = terminar. Las formas se pasan a pintura solo al combinar capas. Balde: `fill {tol, gap, all}` (mira solo
  la capa salvo `all`; `gap` cierra huecos). Combinar: con la de abajo, `mergeVis` o `mergeSet {ids}`.
- Game Pal (control de Brawl Stars en el extendido): `GamePalActivity` + `GamePalTouch` (dedos virtuales en el principal).
  El gamepad de `KeyboardActivity` (`GamepadView`) usa esos mismos dedos (`pad` → `MesaAccess.pad` → `GamePalTouch.touch`); al editar
  pide una captura del principal (`gpShot`/`gpImg`) para ubicar los controles, y tiene botones − / + de tamaño.
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
