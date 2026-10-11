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
- **Conexión**: si el teléfono derecho no encuentra al izquierdo solo, escribe la IP que muestra el izquierdo.

Los teléfonos se hablan directo por la red local (puerto 47820); no se usa internet.

## Compilar

Cada `push` a `main` compila el APK con GitHub Actions y lo publica en *Releases*.
También se puede abrir la carpeta en Android Studio y presionar *Run*.
