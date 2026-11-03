# Publicar Open Kove

Tres canales, de más fácil a más difícil. GitHub Releases ya funciona y es el que recomiendo mantener siempre.

## 1. GitHub Releases (ya está)

Cada tag `v*` dispara el workflow, que compila la variante `github` y adjunta la APK a la release.

```bash
git tag v1.1.0
git push origin v1.1.0
```

## 2. IzzyOnDroid

Repositorio F-Droid que toma la APK directo de GitHub Releases. Acepta AGPL, no compila él mismo y da actualizaciones automáticas en el cliente F-Droid.

Qué hacer:

1. Tener al menos una release con tag y la APK adjunta (punto 1).
2. Abrir un issue en <https://gitlab.com/IzzyOnDroid/repo/-/issues> con el título "Add open-kove" y el enlace `https://github.com/maagmirror/open-kove`. Suelen responder en pocos días.
3. Mantener la APK por debajo de 30 MB. Si crece, hay que pedir excepción.

Lo que revisan: licencia libre, sin trackers (ya no hay telemetría), permisos justificados en el README. El servicio de accesibilidad no es problema ahí, pero conviene que el README lo explique, y ya lo hace.

## 3. Google Play

### Lo que tenés que hacer vos

1. **Cuenta de desarrollador** en <https://play.google.com/console>. Pago único. Google pide verificación de identidad y, para cuentas personales nuevas, una prueba cerrada con 12 probadores durante 14 días antes de poder publicar.
2. **Generar la clave de firma** y guardarla en un lugar seguro. Si se pierde, no se puede actualizar la app nunca más.

   ```bash
   keytool -genkeypair -v -keystore open-kove-release.jks -alias openkove -keyalg RSA -keysize 4096 -validity 10000
   ```

3. **Cargar cuatro secretos en GitHub** (Settings → Secrets and variables → Actions):

   | Secreto | Valor |
   | --- | --- |
   | `RELEASE_KEYSTORE_BASE64` | salida de `base64 -w0 open-kove-release.jks` |
   | `RELEASE_STORE_PASSWORD` | contraseña del keystore |
   | `RELEASE_KEY_ALIAS` | `openkove` |
   | `RELEASE_KEY_PASSWORD` | contraseña de la clave |

   Con eso, cada compilación en GitHub deja un artefacto `open-kove-play-bundle` con el AAB. Se sube a mano en Play Console. No se publica en la release de GitHub.

4. **Crear la app en Play Console** con el nombre "Open Kove", categoría Mapas y navegación, gratis.
5. **Subir el AAB** en Pruebas → Prueba cerrada, y después Producción.
6. **Completar la ficha** con los textos de abajo, el ícono, y capturas de pantalla (mínimo dos del teléfono; las de `docs/screenshots` sirven si las actualizás con la UI nueva).
7. **Política de privacidad:** pegar `https://github.com/maagmirror/open-kove/blob/main/PRIVACY.md`.
8. **Formulario Data Safety** con las respuestas de abajo.
9. **Declaraciones de permisos**, cada una con un video corto subido a YouTube como no listado. Lista más abajo.
10. **Grabar los videos** con el teléfono: con `adb shell screenrecord` o la grabación de pantalla del sistema.

### Textos para la ficha

**Nombre:** Open Kove

**Descripción corta (máx. 80):** Android Auto en el tablero de tu Kove. App no oficial y de código abierto.

**Descripción larga:**

> Open Kove muestra Android Auto en el tablero de las motos Kove con pantalla Carbit/EasyConn (modelo 21318): Google Maps, Waze, música y llamadas en la pantalla de la moto, sin root y sin PC.
>
> Cómo funciona: la app se conecta a la red Wi-Fi del tablero, le manda la imagen de Android Auto y le pasa los toques. Escaneás el QR del tablero una vez; después, al abrir la app conecta sola y se reconecta si se corta.
>
> Requisitos: Android 10 o más nuevo, Android Auto instalado y una Kove con pantalla EasyConn.
>
> Esta app es un proyecto independiente de código abierto (AGPL-3.0). No está afiliada a Kove, Carbit ni Google. Kove, Android Auto y las demás marcas pertenecen a sus dueños. Configurá la ruta antes de salir.
>
> Código fuente: https://github.com/maagmirror/open-kove

### Data Safety

| Pregunta | Respuesta |
| --- | --- |
| ¿Recopila o comparte datos del usuario? | No |
| ¿Los datos se cifran en tránsito? | No aplica, no se envían datos |
| ¿El usuario puede pedir que se borren? | No aplica |
| Ubicación | Se usa en el dispositivo, no se recopila ni comparte |
| Micrófono, cámara | Se usan en el dispositivo, no se recopila nada |

Con la telemetría apagada en el código, la respuesta "no recopila" es cierta. Si algún día la prendés con tu propio servidor, hay que cambiar el formulario y PRIVACY.md.

### Declaraciones en Play Console

Todas en Política → Contenido de la app. Cada una pide un video que muestre cómo el usuario llega a la función.

| Declaración | Qué poner | Qué mostrar en el video |
| --- | --- | --- |
| Servicio en primer plano `connectedDevice` | Mantiene la conexión con el tablero de la moto mientras proyecta | Tocar Conectar y ver la notificación de sesión |
| Servicio en primer plano `location` | Android exige ubicación para unirse a la red Wi-Fi de la moto, y el registro de viajes graba la ruta | Conectar; opcionalmente iniciar un viaje |
| Servicio en primer plano `microphone` | Manda la voz al Asistente de Android Auto cuando el usuario toca el control de voz | Tocar el micrófono en Controles |
| Servicio en primer plano `mediaProjection` | Función de espejo de pantalla heredada | Está oculta en la app Kove; si Play la pide, declarar "no se usa" y, si insisten, sacar el permiso |
| Mostrar sobre otras apps | Opcional, para reanudar Android Auto con la pantalla apagada | Activar Reanudación automática en Configuración |
| Ubicación en segundo plano | No se pide | — |

La variante `play` no incluye el servicio de accesibilidad ni el permiso para cerrar otras apps, así que esas dos declaraciones no aparecen. Si querés subir la variante `github` a Play, hay que declarar la API de accesibilidad como "no es herramienta de accesibilidad", mostrar el aviso que ya tiene la app y adjuntar video; es la declaración que más rechazan.

### Riesgos que quedan

- **Clave de head unit y FreeReflection.** La app lleva una clave de "Google Automotive Link" para que Android Auto la acepte, y carga un `.dex` para saltar restricciones de API ocultas. Son parte de la conexión y no se pueden sacar. Pueden marcar la revisión. Si la rechazan por eso, no hay arreglo técnico: queda GitHub e IzzyOnDroid.
- **Marca.** Si Kove o Carbit reclaman el nombre, Play la baja. Tener un nombre alternativo pensado.
- **Sin servicio de accesibilidad**, la variante `play` obliga a iniciar el servidor a mano después de cada reinicio. La app lo explica en el paso 3.

## 4. F-Droid oficial

Compila todo desde el código y rechaza binarios precompilados. El `.dex` de FreeReflection y la clave de head unit los frenan. Dejarlo para más adelante.
