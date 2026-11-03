# Open Kove: instalación desde cero

Muestra Android Auto en el tablero de motos Kove con pantalla Carbit/EasyConn (modelo `21318`).
Probado en dos motos: una con un POCO F8 Pro, Android 16 y Android Auto 17.9, y una Kove 450R Naked.

## Una sola vez

1. **Instalá Open Kove** y aceptá todos los permisos.
2. **Cerrá la app china.** Si tenés EasyRide (`net.easyconn.carman.easyride`), forzá su cierre o desinstalala. Ocupa los puertos que usa Open Kove, y la moto no conecta.
3. **Activá "Iniciar servidor automáticamente":** en Open Kove → **Configuración** → paso 3, prendé el interruptor. Te lleva a Accesibilidad: buscá Open Kove, activalo y volvé. Al volver, la app abre Android Auto, activa sus opciones de desarrollador y arranca el servidor sola. Ese servicio solo puede ver Android Auto. En Xiaomi puede que primero tengas que habilitar **Permitir ajustes restringidos** en la información de la app.
4. **Xiaomi, Redmi y POCO:** en los ajustes de Open Kove, activá **Inicio automático** y poné la batería en **Sin restricciones**.

Si venís de una Open Kove anterior a la 1.1.0, desinstalala. Tiene otro nombre de paquete y las dos pelean por los mismos puertos.

## Sin inicio automático

Si no querés usar Accesibilidad, en Android Auto tocá **Versión** unas 10 veces, aceptá el aviso y después **⋮ → Iniciar servidor de unidad principal**. Hay que repetir el último paso cada vez que reinicies el teléfono. Si el servidor está apagado, Open Kove te abre ese menú y te avisa.

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
