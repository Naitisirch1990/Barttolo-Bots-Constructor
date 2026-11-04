# Barttolo Bots — Constructor

Mod de Minecraft Java 1.20.1 / Forge 47.4.10. Conserva el Asistente y añade un Constructor visible para mejorar gradualmente una aldea.

## Estado

Prototipo experimental. El Constructor primero analiza la zona inmóvil; cuando termina, camina lentamente entre trabajos sobre terreno abierto. La compilación automática sube un JAR como artefacto de cada ejecución correcta. Falta verificar el comportamiento dentro del servidor.

## Comandos de operador

- `/constructor crear`: asigna una parcela de 32×32 centrada en tu posición.
- `/constructor crear 64`: ancho configurable de 32 a 128 bloques.
- `/constructor iniciar`, `/constructor pausar`, `/constructor estado`, `/constructor cancelar`.

## Cambios de esta versión

- El análisis no desplaza al personaje.
- El trabajo recorre suelo natural abierto y caminos de tierra/grava, con pasos suaves y sin subir a tejados.
- Planta flores y árboles, cubre tierra expuesta, rellena desniveles secos pequeños e instala farolas.
- Las farolas tienen base de ladrillos de piedra, poste y brazos de valla de roble con dos faroles colgantes. Se alinean con caminos y siguen una retícula uniforme de ocho bloques donde no hay caminos.
- El bot modifica bloques como en creativo: no recoge ni suelta objetos. Guarda análisis y progreso en los datos del mundo.

Empieza en una copia del mundo con 32×32. Para compilar: Java 17 y `./gradlew build -x test`. El JAR se genera en `build/libs/`; GitHub Actions también lo publica como artefacto.
