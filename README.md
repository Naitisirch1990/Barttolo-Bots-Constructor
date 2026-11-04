# Barttolo Bots — Constructor

Mod de Minecraft Java 1.20.1 / Forge 47.4.10 que conserva el Asistente y añade un Constructor visible para mejorar gradualmente una aldea.

## Estado

Prototipo de fuentes en desarrollo. El JAR aún no está publicado. GitHub Actions compilará el mod y guardará el JAR como artefacto cuando la compilación pase.

## Comandos de operador

- `/constructor crear`: asigna 32×32 bloques alrededor de tu posición.
- `/constructor crear 64`: asigna una parcela 64×64; se acepta de 32 a 128.
- `/constructor iniciar`, `/constructor pausar`, `/constructor estado`, `/constructor cancelar`.

La parcela se centra donde se ejecuta `crear`. El bot recorre las columnas en zigzag a ritmo lento, conserva el progreso del mundo y no recoge ni suelta objetos.

## Primera prueba

El Constructor cubre tierra expuesta con césped, rellena algunos huecos secos pequeños, planta árboles jóvenes y flores, y añade farolas junto a caminos vanilla. Por ahora no hace navegación física, no construye muros y no remodela acantilados grandes. Empieza con 32×32 en una copia del mundo para revisar el resultado.

## Compilar

Necesita Java 17. Ejecuta `./gradlew build -x test`; el JAR se genera en `build/libs/`. El workflow de GitHub Actions también compila el proyecto y publica el JAR como artefacto de ejecución.

Más detalles: [LEEME-CONSTRUCTOR.md](LEEME-CONSTRUCTOR.md).
