# Mesa Doble

App de Android que une dos teléfonos en un solo escritorio: arrastras notas, imágenes y enlaces de una pantalla a la otra.

## Forma

Los dos teléfonos van **parados, uno al lado del otro**, unidos por el lado largo: juntos forman una pantalla apaisada.

- **Principal (derecha):** aquí corren las apps.
- **Extendido (izquierda):** pantalla extra y teclado.

Al abrir la app se elige qué es cada teléfono.

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

## Modo teclado (por WiFi, sin Bluetooth)

Usa la misma conexión de la mesa: si la mesa está conectada, el teclado también.

**En el teléfono que recibe (una sola vez):** Mesa Doble → *Teclado* → *Este teléfono recibe*.
Activa el teclado "Mesa Doble" y elígelo. Al tocar un campo de texto en cualquier app aparece una barra
delgada en vez del teclado grande. Para volver al teclado normal: *Cambiar teclado* en esa barra.

**En el teléfono que escribe:** Mesa Doble → *Teclado* → *Este teléfono es el teclado*.

- *Teclado grande*: teclado en pantalla completa (ideal en horizontal) con ñ, tildes (´ y luego la vocal),
  AltGr (@ = AltGr + q), copiar/pegar, deshacer, flechas, reproducir/pausa y volumen.
- *Teclado del teléfono / dictado*: escribe con Gboard (autocorrector, deslizar) o dicta por voz.

La reproducción y el volumen funcionan aunque el teclado Mesa Doble no esté elegido en el otro teléfono.
