# Cambios

## 1.1.3

- Arregla el error "Se produjo un problema al analizar el paquete" que impedía instalar la 1.1.1 y la 1.1.2 en algunos teléfonos. La APK vuelve a guardar el código y las librerías sin comprimir, así que pesa unos 55 MB.
- Si tenés la 1.1.2, se instala encima sin desinstalar.

## 1.1.2

**Si tenés la 1.1.0 o la 1.1.1, desinstalala antes de instalar esta.** Esas dos versiones salieron firmadas con claves distintas por un error del proceso de compilación, y Android no deja actualizar encima. Desde esta versión la firma es siempre la misma, así que las próximas se instalan encima sin desinstalar.

- Firma fija para todas las APKs de GitHub. La compilación falla si la firma no es la esperada.

## 1.1.1

- La APK pasa de 98 MB a unos 24 MB: trae solo las librerías para teléfonos (ARM), va comprimida y es una compilación de release en vez de debug. La app funciona igual.
- El enlace a la lista de motos compatibles apunta a este repositorio en todos los idiomas.

## 1.1.0

Esta versión cambia el nombre de paquete de la app. **Desinstalá la Open Kove anterior antes de instalar esta**: Android las ve como dos apps distintas y, si quedan las dos, la moto puede conectarse a la equivocada. La app te lo avisa al abrir.

- Nombre de paquete propio (`dev.whoismaag.openkove`). Ya no reemplaza a OpenCfMoto ni choca con ella.
- Configuración de Android Auto automática: con **Iniciar servidor automáticamente** activado, la app activa las opciones de desarrollador de Android Auto y arranca el servidor de unidad principal sola, también después de reiniciar el teléfono. Ya no hay que tocar Versión diez veces.
- Botón **Configurar Android Auto ahora** en el paso 3, para dejarlo listo antes de la primera conexión.
- Pantalla de bienvenida con los cuatro pasos del primer uso (Android Auto, permisos, servidor automático, escanear la moto). La pantalla principal muestra qué falta hasta que todo esté listo. La Configuración completa queda para ajustes avanzados.
- Las opciones de encendido y apagado son interruptores. Desaparece el texto cortado ("En") de la traducción.
- Aviso dentro de la app antes de activar el servicio de accesibilidad, con lo que hace y lo que no.
- Variante `play` sin servicio de accesibilidad ni permiso para cerrar otras apps, y firma de release opcional en el workflow.
- Telemetría desactivada: no se manda nada al servidor de OpenCfMoto ni a ningún otro.
- Aviso de actualización: una vez por día la app consulta las releases de este repositorio y, si hay una nueva, lo muestra al lado de la versión y ofrece descargarla. También hay un botón en Acerca de.
- Textos, créditos y política de privacidad propios de Open Kove.
