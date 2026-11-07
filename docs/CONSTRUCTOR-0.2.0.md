# Constructor 0.2.0 — rutas adaptadas al terreno (experimental)

## Uso

Minecraft 1.20.1 / Forge 47.4.10 / Java 17. Reemplazar el JAR de Barttolo Bots con el
servidor detenido; conserva Asistente y Jardinero. No instalar dos versiones del mod.

1. `/bot constructor crear`.
2. De pie en origen: `/bot constructor puntoA`.
3. De pie en destino: `/bot constructor puntoB`. Puede tener otra altura y cualquier X/Z.
4. `/bot constructor planificar`. Esperar LISTO; estado muestra el progreso de búsqueda.
5. `/bot constructor vista`. Ver resumen de caminos, puentes, túneles, excavaciones y colocaciones.
   Partículas próximas durante diez segundos: verde camino, blanco puente, naranja túnel,
   humo en bloques a excavar. La vista no carga toda la ruta ni representa cada bloque a la vez.
6. `/bot constructor iniciar` confirma también las excavaciones.

Otros comandos: estado, listar, pausar, continuar, detener, ir, deshacer. Solo administradores.
Pausa y reinicio conservan el avance; continuar seguido de vista/iniciar reanuda la construcción.
Si se interrumpe la búsqueda o el diseño, se recalculan antes de construir.
Obras de la versión 0.1.0: `/bot constructor legado estado` y `legado deshacer`.
Se mantiene separado su historial; la actualización no ejecuta automáticamente las obras viejas.

## Funcionamiento

Búsqueda A* incremental por cuadrícula X/Y/Z. Cada paso mueve un bloque horizontal y
puede subir/bajar uno. Examina una huella local de 5×5 y cinco bloques de altura libre.
Compara pendientes, curvas, excavación y soportes; prefiere operaciones menos costosas.
Impide giros sobre escalones y cumbres de un solo bloque que no puedan recorrerse con escaleras.
Los caminos tienen cinco bloques transitables; esquinas planas ensanchadas para conectar los tramos.
Usa escaleras orientadas en las pendientes. Selecciona pavimento sencillo según bioma y coordenadas.

Excava bloques naturales y vegetación sin botín. Donde el corredor pasa bajo roca, crea un
interior de cinco bloques de ancho y cinco de alto. Conserva el techo y estabiliza arena/grava
antes de excavarlas. No reemplaza inventarios ni bloques de otros mods.

Sobre huecos y agua propone tramos elevados con tablero de cinco bloques, barandillas por fuera
(ancho exterior siete) y pilares cada cinco secciones y en el extremo. Solo acepta soporte sólido
localizable a menos de doce bloques. El tablero queda fuera del agua; puede colocar pilares en agua.
La clasificación puede llamar puente a un pequeño tramo elevado de una rampa.

Máximo 2.000 pasos por ruta, desnivel de búsqueda ±96 respecto de A, 16.000 estados explorados,
48 secciones elevadas consecutivas y 120.000 posiciones de edición. Si excede límites o hay
solapamientos no seguros, rechaza el plan y pide puntos intermedios.
Cuatro candidatos de búsqueda por tick, una sección de diseño por tick, ocho cambios por tick.
Tickets temporales para nueve chunks completos, más dependencias internas de Minecraft;
se liberan al pausar/finalizar. Todos los accesos al mundo ocurren en el hilo del servidor.

## Límites que siguen pendientes

Es una búsqueda local limitada, no un solucionador de toda la geografía del mundo. Puede rechazar
un recorrido para el que exista una solución fuera del presupuesto o con un puente más complejo.
Puentes con curvas complejas y cruces a distintas alturas pueden requerir puntos intermedios.
No hay arcos, puentes de estilos múltiples, farolas, descansos, red reutilizable ni portales decorados.
No implementa la unión circular de RingWorld; evita su muralla de bedrock y el borde vanilla.
Probar primero un trayecto corto en copia descartable y revisar vista antes de iniciar.

El filtro de bloques naturales no distingue piedra o troncos puestos por jugadores de terreno
natural: evitar zonas construidas. Emite BreakEvent para protecciones compatibles, pero GuardStone
no tiene integración comprobada. Revalida el estado anterior de cada bloque y se pausa si cambia,
si aparece líquido junto a la excavación o si una entidad ocupa un bloque a colocar.
Deshacer preserva conflictos posteriores y tolera las conexiones automáticas de paredes/escaleras.
El guardado no es una transacción atómica ante cortes abruptos entre chunks y SavedData.

## Validación

GitHub Actions debe compilar y pasar las pruebas dedicadas de Forge antes de publicar la versión.
La suite nueva comprueba búsqueda fuera de eje entre alturas distintas, excavación y escaleras,
checkpoint, presupuesto por tick, túnel 5×5, cofres, puente con barandillas/pilares y deshacer paredes.
La compatibilidad y el rendimiento dentro del RingWorld real requieren prueba de integración.
