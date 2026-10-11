# Mesa Doble

App de Android que une dos teléfonos en un solo escritorio: arrastras notas, imágenes y enlaces de una pantalla a la otra.

## Forma

En *Ajustes → Forma de la mesa* se elige con dibujos:

- **Lado a lado, en horizontal** (recomendada): extendido a la izquierda, principal a la derecha.
- **Uno sobre otro, en horizontal**: como un notebook, principal arriba y teclado abajo.
- **Lado a lado, en vertical**: como un libro abierto.

El principal define la forma y el extendido la copia. *Vista bloqueada* evita que la pantalla gire.
Los elementos quedan donde los sueltas (incluso montados sobre la unión) y pasan al otro teléfono
cuando cruzas más de la mitad o los lanzas.

## Instalar

1. En cada teléfono, abre la última versión: `Releases → MesaDoble.apk`, descárgala e instálala.
2. Conecta ambos al mismo WiFi (o uno al hotspot del otro).
3. Abre Mesa Doble y elige **Principal** o **Extendido**. La app te guía con lo que falta:
   - Principal: activar el teclado Mesa Doble, el cambio automático de teclado (accesibilidad) y
     "Mostrar sobre otras apps" (para abrir apps pedidas desde el extendido).
   - Extendido: "Mostrar sobre otras apps" (para que el teclado aparezca solo).

## Uso

- **Nota / Imagen**: agrégalas y arrástralas de una pantalla a la otra (cruzan por el borde que se toca).
- **Apps**: elige una app para dejar su acceso directo en la mesa. Al tocarlo, se abre en el principal.
- **Girar y cambiar tamaño**: mantén presionado un elemento o usa dos dedos.
- **Teclado automático**: al tocar donde se escribe en el principal (o al abrir una app de chat), el teclado
  aparece en el extendido. El principal cambia solo al teclado Mesa Doble y vuelve al tuyo si se desconecta.
- **Compartir desde otras apps**: *Compartir → Poner en Mesa Doble*.

Los teléfonos se hablan directo por la red local (puerto 47820); no se usa internet.

## Compilar

Cada `push` a `main` compila el APK con GitHub Actions y lo publica en *Releases*.
También se puede abrir la carpeta en Android Studio y presionar *Run*.

## Teclado, touchpad y gamepad

Desde *Teclado → Usar este teléfono como teclado*, con cinco modos:

- **Teclado**: con sugerencias en español (completa palabras, agrega tildes, corrige errores).
- **Teclado + touchpad**.
- **Touchpad**: puntero, clic, desplazar con dos dedos, Atrás e Inicio (versión completa).
  En la versión liviana mueve el cursor del texto.
- **Gamepad**: joystick, cruceta y botones. *Editar* para moverlos y cambiar su tamaño. Cada control
  toca el principal en el mismo lugar relativo (versión completa).
- **Dictado**: escribe con el teclado del teléfono o dicta por voz.

El principal debe tener elegido el teclado Mesa Doble para recibir texto.

Diccionario: lista de frecuencias [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (MIT).
