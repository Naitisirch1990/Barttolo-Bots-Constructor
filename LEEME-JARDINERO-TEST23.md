# Jardinero test23 — Forge 1.20.1 / 47.4.10

Esta versión coloca el plan directamente desde el servidor. No crea una entidad Jardinero, no necesita que un jugador permanezca cerca y no desplaza un bot por el terreno.

## Uso

1. Con el servidor encendido, ejecutar `/jardinero crear <ancho>` desde el centro del área. El ancho válido es de 32 a 256 bloques; por ejemplo, `/jardinero crear 64` define un área de 64×64.
2. Esperar el mensaje de inspección terminada. El escaneo calcula el plan de farolas, árboles, construcciones pequeñas, flores y pasto.
3. Ejecutar `/jardinero iniciar`. El servidor coloca el plan en el sitio, sin movimiento de entidades. Coloca hasta 16 bloques por tick para reducir las pausas largas y el impacto en TPS.

La planificación intenta añadir un 25 % más de flores y pasto que en test22. Las plantas adicionales pueden quedar junto a árboles y farolas. No ocupan bloques de sus diseños ni reemplazan estructuras existentes; si el área no tiene suficientes posiciones vacías válidas, el aumento puede quedar por debajo del 25 %.

## Comandos

- `/jardinero crear [32..256]`: escanea una nueva área y genera el plan.
- `/jardinero iniciar`: ejecuta el plan directamente.
- `/jardinero pausar`: detiene la colocación y conserva el progreso.
- `/jardinero continuar`: retoma tareas pendientes en la misma área.
- `/jardinero auditar`: vuelve a escanear el área y crea un plan nuevo.
- `/jardinero estado`: muestra progreso, cantidades y estado.
- `/jardinero cancelar`: cancela la asignación; los bloques ya colocados permanecen.

`/constructor` sigue disponible como alias. Los comandos requieren OP. Los chunks del área y su halo se mantienen cargados solo mientras inspecciona o coloca; no se simulan como si hubiera jugadores presentes. El proceso depende de que el servidor esté encendido y procesando ticks.

Reemplazar el JAR anterior con el servidor apagado y no instalar dos versiones a la vez. Se conserva la memoria de estructuras de test22.
