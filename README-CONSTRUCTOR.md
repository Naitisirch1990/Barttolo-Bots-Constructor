# Barttolo Bots — Constructor

Mod de Minecraft Java 1.20.1 / Forge 47.4.10. El Constructor analiza una parcela y luego la mejora poco a poco. Se ejecuta en el servidor junto al Asistente.

## Comandos (operador)

- `/constructor crear` asigna 32×32 bloques alrededor de tu posición.
- `/constructor crear 64` asigna una zona 64×64. El ancho permitido es 32–128.
- `/constructor iniciar`, `/constructor pausar`, `/constructor estado`, `/constructor cancelar`.

La parcela se centra donde se ejecuta `crear`.

## Comportamiento de esta prueba

1. El Constructor permanece quieto mientras analiza la superficie.
2. Al terminar el análisis, camina a paso lento entre puntos de trabajo. La ruta solo usa suelo natural abierto y caminos de tierra/grava; no sube a tejados ni salta desniveles de más de un bloque.
3. Cubre tierra expuesta y rellena depresiones de un bloque con césped. Puede reemplazar piedra aislada que sobresalga en un césped uniforme, sin tocar paredes ni bloques con entidad.
4. Añade flores y árboles jóvenes, macetas junto a paredes a los lados de puertas, antorchas en esas zonas cuando falta luz y hojas persistentes en esquinas de madera.
5. En una parcela despejada puede construir una huerta pequeña de 3×3 con agua central y ocho cultivos. La obra se hace bloque a bloque, visible y sin recoger ni soltar objetos.
6. La farola usa base de ladrillos de piedra, poste y brazos de valla de roble, con dos faroles colgantes. Sigue una retícula de ocho bloques y deja dos bloques de separación del camino para no ocuparlo.
5. No recoge ni suelta objetos. Guarda el análisis, las tareas y el progreso en los datos del mundo.

Las huertas se limitan a una por parcela hasta 64×64 y dos en 128×128. Empieza con 32×32 en una copia del mundo. La ruta evita tejados y superficies bajo techo; si no encuentra suelo abierto y conectado hasta una tarea, la omite y la cuenta en `/constructor estado`.

## Compilar

Requiere Java 17. Ejecuta `./gradlew build -x test`; el JAR aparece en `build/libs/`. GitHub Actions compila el proyecto al subir cambios y publica el JAR como artefacto de la ejecución.
