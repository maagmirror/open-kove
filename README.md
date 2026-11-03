# Open Kove

Android Auto en el tablero de motos Kove con pantalla Carbit/EasyConn (modelo `21318`), para dueños que se quedaron sin una app que funcione. No hace falta root ni PC.

![Android Auto con Google Maps en el tablero de una moto Kove](docs/media/kove-android-auto-tablero.jpg)

![Android Auto con Waze en el tablero de una Kove 450R Naked](docs/media/kove-450r-naked-android-auto.png)

> **Estado:** en prueba, en dos motos: una con un POCO F8 Pro (Android 16, Android Auto 17.9) y una Kove 450R Naked. La moto tiene que estar en **Connection → WiFi Connection** al conectar; si no, conecta pero no muestra imagen. Mirá [el estado técnico](docs/08-KOVE-PROJECTION.md).

## Qué hace

- Muestra Android Auto en la pantalla de la moto, con Maps, Waze y lo demás.
- Conecta sola al abrir la app, después de escanear el QR una vez.
- Rearma la conexión si se corta, hasta que toques **Detener**.
- Trae controles en pantalla para manejar Android Auto. Los botones del manillar no envían nada al teléfono, tampoco con la app china.

## Instalación

Bajá la APK desde [Releases](https://github.com/maagmirror/open-kove/releases/latest) y seguí [la guía desde cero](docs/KOVE-SETUP.md).

Para recibir las actualizaciones solas, agregala en [Obtainium](https://obtainium.imranr.dev) con el enlace de este repositorio:

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Obtener con Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/maagmirror/open-kove)

Si tenés una Open Kove anterior a la 1.1.0, desinstalala primero: desde esa versión la app tiene su propio nombre de paquete y Android la instala como una app aparte.

Android Auto tiene que correr su **servidor de unidad principal**. Con **Iniciar servidor automáticamente** activado en Configuración, Open Kove lo deja configurado y lo arranca sola cada vez que hace falta, también después de reiniciar el teléfono. Si preferís hacerlo a mano: en Android Auto, tocá Versión diez veces y después **⋮ → Iniciar servidor de unidad principal**.

Si tenés la app EasyRide instalada, cerrala antes: ocupa los mismos puertos.

### Actualizaciones automáticas con Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) instala y actualiza apps desde GitHub Releases. Abrilo, tocá **Agregar app**, pegá `https://github.com/maagmirror/open-kove` y listo. También podés buscar Open Kove en [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) desde el cliente F-Droid, cuando esté aceptada ahí.

## Compilar

Hace falta JDK 17 o más nuevo y el Android SDK.

```bash
./gradlew assembleGithubDebug
```

La APK queda en `app/build/outputs/apk/github/debug/app-github-debug.apk`. El workflow [`build-apk.yml`](.github/workflows/build-apk.yml) la compila en GitHub y la publica en Releases.

Hay dos variantes: `github` (completa) y `play` (sin el servicio de accesibilidad ni el permiso para cerrar la app del fabricante, para cumplir las políticas de Google Play). `./gradlew bundlePlayRelease` genera el AAB para Play si hay una clave de firma; mirá [docs/PUBLICAR.md](docs/PUBLICAR.md).

## Documentación

- [Guía de instalación](docs/KOVE-SETUP.md)
- [Cómo funciona la conexión con Kove y qué falta resolver](docs/08-KOVE-PROJECTION.md)
- [Script para probar la conexión desde una PC](scripts/kove_pc_session.py)

## Créditos y licencia

Open Kove es obra de [Maag](https://whoismaag.dev), y es una versión modificada de [OpenCfMoto](https://github.com/zanderp/open-cfmoto), de Alexandru y los colaboradores de OpenCfMoto. Su receptor de Android Auto deriva de [headunit-revived](https://github.com/andreknieriem/headunit-revived).

Se distribuye bajo la [GNU AGPL v3 o posterior](LICENSE). Privacidad y permisos: [PRIVACY.md](PRIVACY.md). Cambios por versión: [CHANGELOG.md](CHANGELOG.md). Si distribuís una versión modificada, tenés que ofrecer su código fuente bajo la misma licencia. Autores y componentes de terceros en [NOTICE](NOTICE).

Open Kove es un proyecto independiente y no tiene relación con Kove, Carbit, CFMOTO ni Google. Kove, Android Auto y las demás marcas pertenecen a sus dueños. Usala bajo tu responsabilidad y configurá la ruta antes de salir.
