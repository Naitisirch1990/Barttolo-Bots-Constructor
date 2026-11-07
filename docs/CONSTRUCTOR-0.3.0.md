# Constructor 0.3.0 — pendientes suaves, luces y movimiento visual

Minecraft 1.20.1 / Forge 47.4.10 / Java 17. Reemplazar el JAR anterior de Barttolo Bots con
el servidor detenido. Integra Asistente, Constructor y el Jardinero automático test23
(sin NPC y con un 25% más de flores/pasto); no instalar dos JAR con el modId barttolobots.

## Qué cambia

- Pendiente máxima de trazado: un bloque de desnivel por cada cuatro pasos horizontales,
  con al menos tres secciones llanas entre escalones. Se aplica también a los descensos.
  Si A y B están demasiado cerca para ese desnivel, busca un rodeo dentro del presupuesto.
- Luces aproximadamente cada diez secciones, y al final cuando queda una distancia considerable.
  Farolas con pared de piedra y farol fuera de los cinco bloques transitables; reutilizan la
  base/barandilla del puente cuando encaja. En túneles coloca glowstone empotrada sobre los
  cinco bloques libres. Si no cabe una farola, intenta luz a ras del pavimento en un tramo plano.
  Cada luz queda en el plan, en la vista previa y en el historial de deshacer.
- El albañil se acerca con pasos de hasta 0,18 bloques por tick, orientado hacia el trabajo.
  El movimiento comprueba espacio y suelo. La construcción espera brevemente a su aproximación;
  conserva un máximo de ocho cambios por tick. Si se atasca durante unos tres segundos, se
  recupera en una posición segura próxima. No es un jugador simulado ni usa navegación libre.

## Prueba

Desde origen y destino: `/bot constructor crear`, `puntoA`, `puntoB`, `planificar`.
Cuando indique LISTO, ejecutar `vista`; revisar trazado, excavaciones y luces.
`iniciar` confirma la obra. Los demás comandos se conservan: estado, pausar, continuar,
detener, ir, deshacer. Los planes anteriores que aún no construyeron se recalculan para
incorporar estas reglas. Obras anteriores ya iniciadas conservan su historial y plan.

## Comprobación

La compilación y pruebas dedicadas Forge verifican distancia entre escalones, iluminación,
interior libre 5×5, deshacer luces/excavaciones, avance visual con pasos cortos, búsqueda
entre alturas, elección de puentes y túneles, checkpoints y Jardinero sin NPC.
La prueba visual y de rendimiento dentro del RingWorld real sigue pendiente.

Se mantienen los límites de búsqueda y seguridad descritos en CONSTRUCTOR-0.2.0.md:
2.000 pasos, 16.000 estados, pilares de menos de doce bloques, tramos elevados limitados,
protecciones por BreakEvent, revalidación de bloques y pausa ante conflictos. No integra
la unión circular de RingWorld ni reconoce la autoría de bloques naturales colocados por jugadores.
La colocación de luces es aproximada; no constituye un análisis fotométrico de toda la ruta.
