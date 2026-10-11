# Mesa Doble

App de Android que une dos teléfonos en un solo escritorio: arrastras notas, imágenes y enlaces de una pantalla a la otra.

## Instalar

1. En cada teléfono, abre la última versión: `Releases → MesaDoble.apk`.
2. Descarga e instala el APK (Android pedirá permitir "instalar apps de origen desconocido" para el navegador).
3. Conecta ambos teléfonos al mismo WiFi (o uno al hotspot del otro).
4. Abre la app en ambos y elige **Izquierda** en uno y **Derecha** en el otro.

## Uso

- **+ Nota**: crea una nota. Tócala para editarla.
- **Imagen**: elige una foto de la galería.
- **Pasar al otro teléfono**: arrastra hacia el borde interior y suelta, lánzala con el dedo hacia ese borde, o toca la flecha del elemento.
- **Compartir desde otras apps**: en Chrome, Fotos, WhatsApp, etc., usa *Compartir → Poner en Mesa Doble*.
- **Teclado**: escribe en el otro teléfono desde este. Ver abajo.
- **Red**: si el teléfono derecho no encuentra al izquierdo solo, escribe la IP que muestra el izquierdo.

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
