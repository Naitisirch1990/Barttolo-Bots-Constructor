# Barttolo Bots — Constructor

Mod de Minecraft Java 1.20.1 / Forge 47.4.10. El Constructor analiza una parcela y luego la mejora poco a poco. Se ejecuta en el servidor junto al Asistente.

## Comandos (operador)

- `/constructor crear` asigna 64×64 bloques alrededor de tu posición.
- `/constructor crear 64` asigna una zona 64×64. El ancho permitido es 32–128.
- `/constructor auditar` revisa la parcela asignada sin cambiar ningún bloque; `/constructor estado` muestra los hallazgos. Luego usa `/constructor iniciar` para ejecutar las tareas detectadas.
- También están `/constructor iniciar`, `/constructor pausar`, `/constructor estado`, `/constructor cancelar`.

La parcela se centra donde se ejecuta `crear`.

## Comportamiento de esta prueba

1. El Constructor permanece quieto mientras analiza la superficie.
2. Al terminar el análisis, camina a paso lento entre puntos de trabajo. La ruta solo usa suelo natural abierto y caminos de tierra/grava; no sube a tejados ni salta desniveles de más de un bloque.
3. Un planificador local puntúa el césped abierto, la distancia al camino, paredes/puertas, desnivel, árboles y flores cercanos. Usa esas señales para escoger los puntos de obra, jardín o huerta; no depende de servicios externos.
4. Audita cada columna de la parcela y compara su superficie con las cuatro columnas vecinas. Detecta hoyos abiertos poco profundos de hasta tres bloques con al menos tres lados de césped a la misma altura, manchas aisladas de piedra y tramos conectados de bloques típicos de caminos.
5. Cubre tierra expuesta y rellena los huecos detectados desde abajo, con tierra y una capa superior de césped. Puede reemplazar piedra aislada en un césped uniforme; no modifica paredes ni bloques con entidad.
6. Añade flores y árboles jóvenes, macetas junto a paredes a los lados de puertas, antorchas en esas zonas cuando falta luz y hojas persistentes en esquinas de madera.
7. En parcelas despejadas puede elegir una huerta 3×3, un jardín con sendero y flores o una esquina de obra, evitando superponerlos.
8. Decora los bordes despejados de los caminos con flores espaciadas a intervalos regulares; deja libre el paso.
9. La farola usa base de ladrillos de piedra, poste y brazos de valla de roble, con dos faroles colgantes. Sigue tramos de camino conectados y separa las farolas al menos ocho bloques; en zonas sin caminos usa una retícula regular de doce bloques.
10. En parcelas de 64×64 o mayores puede crear una esquina de obra: cobertizo abierto, banco de trabajo, yunque, alto horno, barriles, andamios y acopio de troncos.
11. Guarda el análisis, las tareas y el progreso en los datos del mundo.

Las huertas se limitan a una por parcela hasta 64×64 y dos en 128×128. Para revisar lo que encontró antes de cambiar bloques, ejecuta `/constructor auditar` y consulta `/constructor estado`: muestra columnas de camino, huecos, piedra aislada y acciones propuestas. Al terminar, `/constructor iniciar` ejecuta las tareas. Prueba primero en una copia del mundo. La ruta evita tejados y superficies bajo techo; si no encuentra suelo abierto y conectado hasta una tarea, la omite y la cuenta en `/constructor estado`.

## Compilar

Requiere Java 17. Ejecuta `./gradlew build -x test`; el JAR aparece en `build/libs/`. GitHub Actions compila el proyecto al subir cambios y publica el JAR como artefacto de la ejecución.

## Conversión automática a Lootr (0.2.6)

Al asignar `/constructor crear`, se convierten los cofres vanilla normales y trampa dentro de la parcela, en todas las alturas e interiores. No requiere iniciar el movimiento. Los objetos anteriores se eliminan sin dropear; se conserva una tabla original válida o se elige una tabla vanilla de aldea. Cada mitad de un cofre doble pasa a ser un cofre individual Lootr. Los dobles que cruzan el límite de la parcela se omiten completos, para no alterar el exterior. Se mantienen orientación y agua. No se convierten barriles ni cofres de otros mods; los Lootr existentes no se reinician.

El escaneo procesa un chunk por tick y guarda su progreso. `/constructor estado` muestra convertidos, fallidos y dobles del límite. `/constructor auditar` pausa las conversiones pendientes y sigue siendo de solo lectura; `/constructor iniciar` las reanuda. Cancelar detiene las conversiones pendientes. Sin Lootr instalado no se borran cofres. La compatibilidad se basa en Lootr Forge 1.20 0.7.35.94.

## Plan de aldea 0.3.0

El escaneo busca puertas y cubiertas en las columnas de la parcela, propone un centro aproximado por los extremos de las casas y conecta accesos a caminos próximos cuando encuentra una ruta corta despejada. Orden de trabajo: reparación de superficie, conexiones, iluminación, puntos de interés/decoración y vallado. Retira bloques aislados de tierra/piedra/arena/grava solo si los cuatro vecinos son césped un bloque más abajo y no hay construcciones inmediatas. No limpia montañas ni cimentaciones.

Con al menos dos puertas propone un perímetro rectangular tres bloques por fuera de puertas y tejados próximos. Solo lo construye si cabe completo dentro de la parcela; deja accesos en los cuatro lados y huecos en caminos, edificios, agua y desniveles fuertes. Es un vallado decorativo que puede quedar interrumpido, no una defensa hermética ni un detector perfecto de límites de aldea. `/constructor estado` informa centro aproximado y propuesta de vallado. Si la aldea sale de la parcela, ampliar a 96 o 128.

La madera de farolas, vallado y componentes de jardín/taller sigue el bioma. Una memoria persistente por dimensión conserva posiciones de puntos de interés, huertas, farolas y vallados construidos por el bot y evita duplicar sus huellas al reasignar zonas. Las construcciones siguen siendo plantillas pequeñas, no planos libres ni puestos con NPC. El Constructor orienta la cabeza al trabajar y muestra el bloque o herramienta en la mano. No se agregó deshacer.

## Uso sencillo y farola central (0.3.1)

Solo son necesarios `/constructor crear` (64×64 por defecto, incluye conversión Lootr) y `/constructor iniciar` (escaneo, planificación y ejecución automática). No hace falta auditar ni ejecutar comandos intermedios. Los comandos de diagnóstico/pausa existentes quedan como opcionales; no se añadieron comandos nuevos.

La farola nueva es simétrica: base de andesita pulida, pared de diorita, poste de madera, cuello de diorita y una lámpara de redstone central siempre encendida mediante un bloque de redstone dentro de la cubierta de piedra. La cubierta ocupa una cruz de 3×3 y llega a ocho bloques sobre el suelo. Se valida toda su huella; no necesita orientarse a un lado del camino. Las farolas antiguas no se reemplazan.

Al terminar entrega automáticamente un informe con tareas completadas por categoría, bloques modificados, conversiones Lootr, trabajos inaccesibles, condiciones cambiadas, colocaciones parciales y hasta cuatro ejemplos con coordenadas. Incluye sugerencias concretas si faltaron caminos, accesos o espacio para el perímetro. El último informe queda persistido y puede consultarse con el `estado` existente. El informe cuenta resultados ejecutados, no todas las oportunidades posibles ni una garantía de aldea terminada.

## Catálogo 0.4.0 / test13

- Otro 20% de velocidad de movimiento: 0,1296 bloques/tick. La construcción sigue colocando un bloque por turno.
- Seis familias nuevas en parcelas despejadas: bancos con jardineras, pérgolas floridas, pozos cubiertos, puestos de mercado, merenderos con sombrilla y estanques. Dos variantes por familia; madera adaptada al bioma y orientación hacia el camino próximo.
- Tres huertas de 7×7 con lecho cultivado de 5×5, dos cultivos por diseño, riego central, acceso despejado, bordes de madera o piedra, compostador, barril/fardo y faroles. El fondo del riego, pozos y estanques queda sellado.
- Selección automática después del censo de caminos y puertas; reserva de 7×7×5, separación de 14 bloques entre centros de obras y puesto de trabajo exterior a cuatro bloques. Si falta espacio, omite esa propuesta.
- Máximo dos construcciones nuevas en 64×64 y hasta seis familias en 128×128, siempre sujeto al espacio disponible. No repite automáticamente obras ya recordadas ni ocupa rutas o entradas reservadas.
- Pruebas Forge: las quince recetas en cuatro orientaciones, soporte y supervivencia de bloques, aproximación libre y prueba de huerta completa seguida de otro trabajo. Las recetas son adaptaciones con bloques vanilla de las referencias, no copias de los recursos visuales de los autores.
- Sin comandos adicionales: crear e iniciar regeneran el plan y emplean el catálogo automáticamente.

## Correcciones 0.3.4 / test12

- Velocidad de desplazamiento 0,108 bloques/tick: otro 20% sobre test11 (0,09). Se mantiene el ritmo de colocación de bloques.
- Navegación con tierra de cultivo y plantas sin colisión, incluidas zanahorias maduras. La posición actual se comprueba a la altura de los pies, también sobre el pavimento de una obra.
- El constructor trabaja desde fuera de las huertas, talleres y jardines. Si su celda queda ocupada, intenta un paso local a suelo libre antes de pausar; no destruye cultivos para salir. Una pausa real informa coordenadas y bloque.
- Los caminos se reparan antes de las tareas de césped. Se excluyen de estas tareas los senderos, su franja próxima y las reservas del plan. Los parches de tierra con dos vecinos de camino también se incorporan al trazado. Rellenos en un corredor reservado terminan en dirt_path.
- Se añade una prueba en servidor Forge con cultivos maduros, bloqueo sólido, tareas antiguas de césped, puntos de trabajo fuera de las estructuras y la construcción de una huerta completa seguida por otro trabajo.
- Se conserva el área elegida: 128×128 si se crea con ese tamaño. No hace falta ningún comando adicional.

## Correcciones 0.3.3 / test11

- Censo completo de puertas antes de reservar caminos y elegir farolas. Reconoce dirt_path y parches conectados de tierra estéril, tierra enraizada y grava.
- Reconstruye el eje del sendero y propone tres bloques de ancho, sin ensanchar una vía existente de tres a cinco. Repara interrupciones de una columna. Conserva cimientos, cultivos, agua y límites de parcela; el ancho puede quedar incompleto donde hay obstáculos.
- Reserva el camino completo frente a decoración. Farolas laterales con ritmo de 16 bloques, exclusión entre farolas y detección de lámparas de redstone existentes. Sin cuadrícula adicional de farolas en el césped. No retira farolas de versiones anteriores.
- Reserva corredores de acceso a ambos lados de las puertas. Movimiento 20% más rápido; revisa puntos de aproximación en el componente de suelo alcanzable y permite caminar bajo aleros sobre suelo natural, sin usar tejados como suelo.
- Setos bajos solo en esquinas con espacio libre suficiente. El césped que ya creció naturalmente cuenta como tarea resuelta y no como fallo.
- No requiere comandos adicionales. Para regenerar el plan de una prueba anterior: crear e iniciar. Los planes ya guardados mantienen sus tareas hasta crear otra asignación.
- CI comprueba ancho 1/2/3, cruces, interrupciones, coordenadas negativas, accesos y determinismo, además de los perfiles de pendiente y la compilación Forge. Falta validación visual dentro de Minecraft.

## Paisaje 0.3.2

Sin comandos nuevos: al finalizar el escaneo se buscan cortes exteriores cerca de las casas. Las pendientes propuestas rellenan cortes de 4–10 bloques con un descenso por cada dos bloques horizontales y hombros de césped. Necesitan una franja seca y libre de tres bloques de ancho y hasta 20 de largo, completamente dentro de la parcela; no excavan montañas, cuevas ni construcciones. Se limitan a 2 pendientes por 64×64 (4 por 128×128), separadas, y se ejecutan columna por columna. No todos los precipicios serán corregibles.

En cortes menores de hasta seis bloques se pueden proponer apoyos cortos de contención de piedra, por fuera de la ladera. Si la rampa no cabe, se evalúa un mirador pequeño en terreno plano elevado, con banco, baranda y luz, máximo uno por parcela. Los accesos cortos a las huertas propuestas se buscan desde fuera de sus cultivos; no se sustituyen bloques de cultivo por camino.

La vegetación se reduce al alejarse de los accesos de las casas y se limita el número de flores, árboles y farolas por superficie. Esto distribuye los detalles y conserva claros. El informe automático incluye pendientes, contención, miradores, accesos a cultivos y candidatos descartados. La accesibilidad puede impedir tareas y se informa al terminar.

Verificación: las pruebas de perfil comprueban pendientes, hombros, llegada al nivel inferior y rechazo de alturas fuera de los límites. La compilación de Forge comprueba integración; la construcción visual en un mundo real requiere prueba en el juego.
