# Open Kove: instalación desde cero

Muestra Android Auto en el tablero de motos Kove con pantalla Carbit/EasyConn (modelo `21318`).
Probado en una moto con un POCO F8 Pro, Android 16 y Android Auto 17.9.

## Una sola vez

1. **Instalá Open Kove** y aceptá todos los permisos.
2. **Cerrá la app china.** Si tenés EasyRide (`net.easyconn.carman.easyride`), forzá su cierre o desinstalala. Ocupa los puertos que usa Open Kove, y la moto no conecta.
3. **Activá el modo desarrollador de Android Auto:**
   1. Abrí Open Kove → **Configuración** → paso 3, botón de Android Auto. Android Auto no tiene ícono propio.
   2. Bajá hasta **Versión** y tocala unas 10 veces.
   3. Aceptá el aviso.
4. **Xiaomi, Redmi y POCO:** en los ajustes de Open Kove, activá **Inicio automático** y poné la batería en **Sin restricciones**.

## Inicio automático del servidor (recomendado)

En **Configuración → paso 3 → Iniciar servidor automáticamente**, activá Open Kove en Accesibilidad. Solo puede ver Android Auto y actúa únicamente si el servidor está apagado. En Xiaomi puede que primero tengas que habilitar **Permitir ajustes restringidos** en la información de la app.

## Sin inicio automático, cada vez que reinicies el teléfono

En Android Auto: **⋮ → Iniciar servidor de unidad principal**. Si está apagado, Open Kove te abre ese menú y te avisa.

## Conectar

1. Prendé la moto, entrá a **Connection → WiFi Connection** y **quedate en esa pantalla**. La moto solo muestra la imagen si está ahí al conectar.
2. **La primera vez:** en Open Kove tocá **Escanear** y apuntá al QR.
3. **Las siguientes:** abrí Open Kove. Conecta sola.
4. Esperá entre 10 y 40 segundos. La moto puede entrar y salir de la red varias veces antes de quedarse.

## Si algo falla

| Qué ves | Qué hacer |
|---|---|
| "Reiniciá el servidor" | En Android Auto: **⋮ → Detener servidor** y volver a **Iniciar** |
| "Fuerza el cierre de la app oficial…" | Forzá el cierre de EasyRide o de la app oficial |
| Queda en "Reconectando" | Revisá que la moto esté prendida y cerca. Sigue intentando sola |
| Dice "proyectando" y Wi-Fi en moto, sin imagen | Andá a **Connection → WiFi Connection** y tocá **Detener** y **Conectar** |
| Se corta al bloquear | Debería reconectar sola. Si no, revisá el paso 4 |

Mientras proyecta, la pantalla del teléfono queda encendida y atenuada para evitar el bloqueo automático. **Detener** lo desactiva.
