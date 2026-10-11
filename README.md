# Mesa Doble

App de Android que une dos teléfonos en un solo escritorio: arrastras notas, imágenes y enlaces de una pantalla a la otra.

## Regla de disposición (fija para todas las versiones)

Los teléfonos se unen por el **lado largo**. Es un solo armado físico; la orientación decide cómo se ve:

| Orientación | Principal | Extendido | Los elementos cruzan |
|---|---|---|---|
| **Horizontal** (uso principal) | **Arriba** | Abajo | de arriba hacia abajo |
| Vertical | **Izquierda** | Derecha | de izquierda a derecha |

- El **principal** es donde corren las apps; el **extendido** es pantalla extra y teclado.
- El principal define la orientación y el extendido la copia.
- *Vista bloqueada* (por defecto): queda fija en la orientación elegida, horizontal por defecto.
  *Vista libre*: al girar los teléfonos cambia sola entre horizontal y vertical.
- En el código la pantalla 0 es siempre el principal (arriba o izquierda) — ver `MainActivity` y `DeskView`.

Los elementos quedan donde los sueltas (incluso montados sobre la unión) y pasan al otro teléfono
cuando cruzas más de la mitad o los lanzas.

## Instalar

1. En cada teléfono, abre la última versión en *Releases*, descarga el APK e instálalo.
2. Conecta ambos al mismo WiFi (o uno al hotspot del otro).
3. Abre Mesa Doble y elige **Principal** o **Extendido**. La app te guía con lo que falta:
   - Principal: activar el teclado Mesa Doble. Con la versión completa, además el cambio automático de
     teclado (accesibilidad) y "Mostrar sobre otras apps".
   - Extendido: nada obligatorio.
   - Usa **MesaDoble-liviana.apk**: Play Protect puede bloquear la completa (pide accesibilidad).

## Uso

- **Nota / Imagen**: agrégalas y arrástralas de una pantalla a la otra (cruzan por el borde que se toca).
- **Apps**: siempre muestra las apps del **principal** (en el extendido se piden por la conexión). Toca una para
  dejar su acceso directo en la mesa o abrirla en el principal; **mantén presionada** para desinstalarla
  (Android pide confirmar en el principal).
- **Herramientas** (primer botón de la barra): Nota, Imagen, Reloj, Widget y Archivos.
- **Reloj**: hora, fecha y próxima alarma en la mesa; al tocarlo abre las alarmas del principal.
- **Archivos**: a pantalla completa en los dos teléfonos a la vez (al abrirlo en uno se abre en el otro):
  cada teléfono maneja sus propios archivos, como las dos ventanas de Total Commander. Mantén presionado para
  elegir; aparecen botones flotantes **Copiar**, **Mover**, **Eliminar** (y Renombrar con uno solo). Lo copiado
  o movido va a la carpeta abierta en el otro teléfono. También se puede **arrastrar o lanzar** lo elegido hacia
  el otro teléfono para moverlo, con una animación de hojas que vuelan de una pantalla a la otra.
  Necesita *Acceso a todos los archivos* en ambos. El contenido viaja por un canal aparte (puerto 47821)
  protegido con una clave que solo conocen los dos teléfonos.
- **Widget propio "Mesa Doble · Reloj"**: hora, fecha y próxima alarma; al tocarlo abre las alarmas. Está en
  *Herramientas → Widget* (primero de la lista) y también en el inicio normal del teléfono.
- **Girar y cambiar tamaño**: mantén presionado un elemento o usa dos dedos.
- **Teclado automático**: al tocar donde se escribe en el principal (o al abrir una app de chat), el teclado
  aparece en el extendido. El principal cambia solo al teclado Mesa Doble y vuelve al tuyo si se desconecta.
- **Compartir desde otras apps**: *Compartir → Poner en Mesa Doble*.
- **Navegación del principal**: Atrás, Inicio y Recientes controlan el **principal**. Con la versión liviana: Atrás funciona cuando hay un campo de texto activo
  (pasa por el teclado Mesa Doble), Inicio abre el inicio (o deja un aviso de un toque) y Recientes necesita
  la versión completa.
- **Barra plegable**: el botón *Menú* despliega o esconde los demás botones (recuerda cómo la dejaste).
  En horizontal se abre como fila; en vertical, como columna hacia arriba.
- **Escritorios a los lados** (botón *Mesas*): crea más escritorios y desliza a los lados sobre un espacio
  vacío para cambiar; ambos teléfonos muestran siempre el mismo. Para llevar un elemento a otro escritorio,
  arrástralo y déjalo un momento contra el borde izquierdo o derecho (si no existe, se crea). Los puntos
  abajo indican en cuál estás.
- **Apps ordenadas o fijas** (botón *Mesas*): *Ordenar apps ahora* las pone en cuadrícula por nombre;
  *Orden automático* las reacomoda al agregarlas o soltarlas; *Apps bloqueadas* las deja fijas (no se mueven
  ni se borran, pero se abren al tocarlas). Se aplica en ambos teléfonos.
- **Widgets** (botón *Widget*): agrega widgets de Android (reloj, clima, calendario, música...). Se mueven
  tomándolos por su franja superior; mantén presionada esa franja para girarlos o cambiar su ancho y alto.
  Cada teléfono muestra su propia copia: si pasas un widget al otro teléfono, tócalo una vez ahí para activarlo
  (la app tiene que estar instalada en ese teléfono).
- **Navegación del principal en la mesa**: en el extendido, al lado de la barra de opciones, están Atrás, Inicio
  y Recientes del principal. En la pantalla de teclado van arriba, junto a "Escribiendo en el principal".
- **Pantalla unida** (*Ajustes*, activada por defecto): cuando el principal se apaga o bloquea, el extendido
  también; cuando el principal se enciende o desbloquea, el extendido igual. En el extendido, *Ajustes →
  Revisar configuración* pide lo necesario: permiso para bloquear la pantalla (administrador del dispositivo,
  solo "bloquear"), notificaciones de pantalla completa y batería *Sin restricciones*. En Xiaomi activa también
  *Otros permisos → Mostrar en pantalla de bloqueo*. Si el extendido tiene PIN, pedirá el PIN al desbloquear;
  sin PIN se desbloquea solo.
- **Color de fondo**: *Ajustes → Color de fondo*, se aplica en ambos teléfonos.
- **Pantalla de inicio**: *Ajustes → Pantalla de inicio* convierte Mesa Doble en el launcher del teléfono
  (no necesita permisos; se desactiva igual).

Los teléfonos se hablan directo por la red local (puerto 47820); no se usa internet.

## Compilar

Cada `push` a `main` compila el APK con GitHub Actions y lo publica en *Releases*.
También se puede abrir la carpeta en Android Studio y presionar *Run*.

## Teclado, touchpad y gamepad

Desde *Teclado → Usar este teléfono como teclado*, con cinco modos:

- **Teclado**: con sugerencias en español (completa palabras, agrega tildes, corrige errores) y tecla
  **Enviar** (en WhatsApp conviene activar *Ajustes → Chats → Enter para enviar*).
- **Teclado + touchpad**.
- **Touchpad**: por **Bluetooth** es un **mouse real** (aparece el puntero en el principal, clic, desplazar
  con dos dedos). Con *Salida: WiFi* usa el modo anterior (puntero con la versión completa; cursor del texto
  con la liviana).
- **Gamepad**: por **Bluetooth** es un **control de juego real**, reconocido por emuladores como
  Nostalgia.NES, RetroArch, etc. Dos joysticks, cruceta, A/B/X/Y, L1/L2/L3, R1/R2/R3, Select y Start.
  *Editar* para moverlos y cambiar su tamaño.

### Control y mouse Bluetooth

El Bluetooth se usa **solo** en los modos Gamepad y Touchpad; el teclado y la mesa siguen por WiFi.

1. En el extendido abre *Teclado → Gamepad* (o Touchpad) y toca **Vincular**.
2. En el **principal**: *Ajustes → Bluetooth → Vincular nuevo dispositivo* → "Mesa Doble Control".
3. Desde ahí se reconecta solo. El botón **Salida** cambia entre Bluetooth y WiFi.
4. En el emulador (Nostalgia.NES → Ajustes → Controles) asigna los botones si no los toma solos.

Requiere que el extendido permita funcionar como dispositivo Bluetooth (perfil HID); si no lo permite,
la app lo avisa y se puede usar *Salida: WiFi*.
- **Dictado**: escribe con el teclado del teléfono o dicta por voz.

El principal debe tener elegido el teclado Mesa Doble para recibir texto.

Diccionario: lista de frecuencias [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (MIT).
