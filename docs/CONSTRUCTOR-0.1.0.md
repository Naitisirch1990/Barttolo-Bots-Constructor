# Constructor 0.1.0 — primera versión de prueba

Integrado en Barttolo Bots conservando Asistente, Jardinero y el alias histórico `/constructor`.
El nuevo oficio usa `/bot constructor` para evitar conflictos.

## Prueba inicial

1. `/bot constructor crear`
2. Desde el origen, de pie en el suelo: `/bot constructor puntoA`.
3. Desde el destino: `/bot constructor puntoB`.
4. `/bot constructor planificar`, esperar estado LISTO.
5. `/bot constructor vista`: resumen textual del trazado y materiales.
6. `/bot constructor iniciar`: confirma y comienza.

Otros comandos: `estado`, `listar`, `pausar`, `continuar`, `detener`, `ir`, `deshacer`.
Todos requieren nivel de permisos 2. Un proyecto y un historial a la vez.

## Alcance implementado

Puntos alineados en X o Z, misma altura, máximo 2000 bloques de distancia.
Corredor de cinco bloques, suelo natural seco con soporte inmediato y tres bloques de aire.
Pavimento de ladrillos de piedra, hasta ocho cambios por tick, reducción de trabajo si MSPT supera 55.
Inspección incremental y revalidación al construir. No excava ni elimina vegetación.
NPC aldeano visual sin IA; avanza por secciones. Tickets temporales con caducidad de 40 ticks,
hasta nueve chunks solicitados a nivel completo, más los vecinos que Minecraft necesita internamente.
Liberación al pausar, finalizar o detener servidor. No utiliza tickets Forge persistentes.
Guardado SavedData de puntos, avance, estados y cambios con estados de bloques anteriores/posteriores.
Reinicio pausa la obra; continuar revalida y requiere vista/iniciar nuevamente.
Deshacer compara el estado actual con el colocado y se pausa al encontrar conflictos.
Una obra nueva no elimina el historial de una anterior con cambios.

## Límites de seguridad y pruebas pendientes

Esto no cumple todavía la especificación completa. No incluye curvas, pendientes, puentes,
túneles, farolas, plazoletas, materiales por bioma ni red de caminos.
Vista es textual, no una previsualización con partículas. No interpreta geometría RingWorld;
solo respeta el borde de mundo vanilla. Probar lejos de las murallas y de la unión del anillo.
El evento Forge BreakEvent permite que protecciones compatibles cancelen el cambio;
no hay integración verificada con GuardStone ni todas las protecciones de mods.
No puede distinguir piedra natural de una construcción de jugadores hecha con piedra.
El historial respeta el guardado normal del servidor: no garantiza transacciones atómicas
entre archivos de chunks y SavedData ante un corte abrupto. Una edición posterior idéntica
al bloque colocado no puede distinguirse de la colocación original.
Deshacer no puede detectar todas las protecciones externas y requiere prueba de integración.
No ejecutar en el mundo principal hasta superar pruebas en copia descartable.

## Instalación

Minecraft Java 1.20.1, Forge 47.4.10, Java 17. Mod de servidor.
Detener el servidor y reemplazar el JAR anterior de Barttolo Bots por este JAR integrado.
No instalar ambos: comparten el identificador barttolobots. Los comandos anteriores de
Asistente y Jardinero se conservan; la nueva obra usa su propio archivo de datos.
Probar primero en una copia del mundo, en un corredor plano de unos 10–20 bloques.
No requiere recursos del inventario. No genera botín ni entrega los materiales al NPC.

## Validación

El flujo de GitHub Actions compila el mod y ejecuta pruebas en un servidor Forge dedicado
antes de permitir publicar una versión de prueba. Incluye comprobaciones de geometría,
ancho, cuatro direcciones, límites, construcción por presupuesto, pausa/recarga del avance,
restauración del terreno, rechazo de agua y conflictos al deshacer. También ejecuta las
pruebas ya existentes del Asistente y Jardinero.
Una publicación automatizada confirma que ese commit superó esos controles; no confirma
rendimiento ni compatibilidad en tu mundo RingWorld. Esa prueba de integración sigue pendiente.
La compilación local está limitada por acceso a Gradle y ausencia de javac; se utiliza CI.
