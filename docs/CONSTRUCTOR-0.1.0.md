# Constructor 0.1.0 — prototipo pendiente de verificación

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

Compilación local bloqueada al descargar Gradle por restricciones de red.
GitHub Actions deberá confirmar compilación; la compilación por sí sola no demuestra jugabilidad.

La ejecución de las pruebas unitarias locales también está bloqueada: hay Java 17 JRE,
pero no está instalado `javac`. Se añadió RoadCorridorChecks al flujo de CI; todavía no se ejecutó.
La revisión automática impidió publicar esta rama en el repositorio público porque el
pedido original requiere un destino privado. No se ejecutó CI para estos cambios.
