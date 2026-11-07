# León Dash — relojes OBD2 para Android Auto

App para el SEAT León FR 1.4 TSI ACT 150 CV (2018):
- **En el coche (Android Auto):** relojes de velocidad y revoluciones, y datos en tiempo real repartidos en varias páginas. Botones: página siguiente, consumos, revisiones y conectar o desconectar el OBD.
- **En el móvil:** datos en vivo, elegir qué relojes ver (y ocultar solos los que el coche no envía), historial de consumo (por viaje, por semana, por mes y por año), revisiones con avisos, y la ficha del coche.

## Qué necesitas
1. Un lector **OBD2 Bluetooth "clásico"** (vLinker MC+, OBDLink MX+ o un ELM327 de calidad). Los que solo son BLE o solo Wi-Fi no sirven.
2. Emparejarlo en *Ajustes > Bluetooth* del móvil (el PIN suele ser 1234 o 0000).

## Opción A: compilar en el ordenador (Android Studio)
1. Instala Android Studio (gratis): https://developer.android.com/studio
2. *File > Open* y elige esta carpeta `LeonDash`. Espera a que termine la sincronización de Gradle.
3. Conecta el Xiaomi por USB con la depuración USB activada y pulsa ▶ *Run*.
   También puedes ir a *Build > Build APK(s)* y pasar el APK al móvil.

## Opción B: compilar sin ordenador potente (GitHub, gratis)
1. Crea un repositorio en github.com y sube el contenido de esta carpeta (incluida `.github`).
2. En la pestaña **Actions** se compila sola. Al terminar, descarga el artefacto **LeonDash-apk**.
3. Instala el APK en el móvil (permite "instalar apps desconocidas").

## Para verla en el coche
- Android Auto > Ajustes del desarrollador > **Fuentes desconocidas** activado (ya lo tienes).
- En el Xiaomi: León Dash > Ahorro de batería > **Sin restricciones**.
- Abre la app una vez en el móvil, da permiso de Bluetooth y elige el lector.
- En la pantalla del coche aparece en el menú de apps, con un icono de reloj.

## Notas
- **Presión de aceite:** tu motor no la mide con un valor (solo tiene un interruptor de aviso), así que aparece como "no disponible" y se oculta sola.
- **Consumo:** se calcula con el caudal de aire del motor. Compáralo con el del cuadro; suele desviarse un 5-10 %.
- **Km totales:** el OBD2 no da los km del cuadro. Introdúcelos en *Revisiones* y la app irá sumando los de cada viaje.
- Los intervalos de revisión son orientativos y se pueden editar. Comprueba los de tu libro de mantenimiento.
