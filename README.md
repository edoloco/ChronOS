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
- **Apps**: elige una app para dejar su acceso directo en la mesa. Al tocarlo, se abre en el principal.
- **Girar y cambiar tamaño**: mantén presionado un elemento o usa dos dedos.
- **Teclado automático**: al tocar donde se escribe en el principal (o al abrir una app de chat), el teclado
  aparece en el extendido. El principal cambia solo al teclado Mesa Doble y vuelve al tuyo si se desconecta.
- **Compartir desde otras apps**: *Compartir → Poner en Mesa Doble*.
- **Navegación**: Atrás, Inicio y Recientes en su propia barra (Recientes necesita la versión completa;
  en la liviana muestra un momento la barra del sistema).
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
- **Touchpad**: puntero, clic, desplazar con dos dedos, Atrás e Inicio (versión completa).
  En la versión liviana mueve el cursor del texto.
- **Gamepad**: dos joysticks, cruceta, A/B/X/Y, L1/L2/L3, R1/R2/R3, Select y Start. *Editar* para moverlos y cambiar su tamaño. Cada control
  toca el principal en el mismo lugar relativo (versión completa).
- **Dictado**: escribe con el teclado del teléfono o dicta por voz.

El principal debe tener elegido el teclado Mesa Doble para recibir texto.

Diccionario: lista de frecuencias [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (MIT).
