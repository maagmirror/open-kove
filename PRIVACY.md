# Open Kove — Privacidad y permisos

_Última actualización: 2026-11-03_

Open Kove funciona en local. Conecta el teléfono con el tablero Carbit/EasyConn de la moto por la red
Wi-Fi de la moto y le manda la imagen de Android Auto. No tiene cuentas, no tiene publicidad y no
manda datos a ningún servidor del proyecto.

## Resumen

- **Sin cuenta ni registro.**
- **Sin telemetría.** Las compilaciones oficiales de Open Kove no suben ningún dato de uso ni de
  fallos. El código hereda de OpenCfMoto una opción de "informes anónimos", pero en Open Kove está
  desactivada y sin servidor configurado, así que no hace nada.
- **Los datos de la moto y de los viajes quedan en el teléfono.**
- Tráfico que sí sale del teléfono, sin identificarte:
  - **Mapas** de la pantalla de viajes, descargados de servidores de OpenStreetMap.
  - **Android Auto**: Maps, Waze y el resto corren dentro de la app de Google, bajo sus condiciones.
    Open Kove solo reenvía su video y audio al tablero.
  - **Buscar actualizaciones**: consulta la API pública de GitHub del repositorio
    `maagmirror/open-kove` para ver si hay una versión nueva.

## Permisos

| Permiso | Cuándo | Para qué |
| --- | --- | --- |
| **Cámara** | Al escanear el QR del tablero | Leer el QR de emparejamiento. No se graba ni se sube nada |
| **Ubicación** | Al conectar y mientras se graba un viaje | Android la exige para buscar y unirse a redes Wi-Fi. El registro de viajes usa el GPS para guardar la ruta, solo en el teléfono |
| **Dispositivos cercanos / Wi-Fi** | Al conectar | Armar la red con la moto |
| **Bluetooth** | Al conectar y en la ayuda de Bluetooth | Despertar el tablero y ver si el teléfono está emparejado con la moto. Declarado como "no para ubicación" |
| **Micrófono** | Solo al tocar el control de voz | Mandar tu voz al Asistente de Android Auto |
| **Notificaciones** | Al proyectar | Mostrar el estado de la sesión con un botón para detenerla |
| **Mostrar sobre otras apps** (opcional) | Si activás la reanudación automática | Relanzar Android Auto solo cuando la moto vuelve |
| **Accesibilidad** (opcional) | Si activás "Iniciar servidor automáticamente" | Un servicio que solo puede ver la app Android Auto. Activa sus opciones de desarrollador e inicia el servidor de unidad principal cuando Open Kove lo necesita. No lee ninguna otra app |

También usa permisos técnicos sin diálogo: internet, estado de red, servicios en primer plano,
wake lock, y el permiso para cerrar en segundo plano la app oficial del fabricante cuando ocupa los
puertos que usa la moto.

## Datos guardados en el teléfono

| Dato | Dónde | Notas |
| --- | --- | --- |
| Perfil de la moto: SSID, contraseña Wi-Fi, datos del QR, dirección BLE | Almacenamiento privado de la app | No se transmite. Está en texto plano dentro de la carpeta privada de la app |
| Viajes: ruta GPS, distancia, duración, velocidades | Almacenamiento privado de la app | Solo si está activado el registro de viajes. Se pueden borrar uno por uno |
| Registro de diagnóstico | Memoria y almacenamiento privado | Se comparte únicamente cuando tocás **Registros → Compartir** y elegís vos el destino. Las contraseñas y seriales se tachan salvo que lo desactives |

## Contacto

Open Kove es un proyecto independiente, sin relación con Kove, Carbit ni Google. Preguntas sobre
privacidad o licencia: <https://whoismaag.dev> o los issues de
<https://github.com/maagmirror/open-kove>.
