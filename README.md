# Barttolo Constructor — Jardinero 0.5.0 / test20

Rediseño de jardinería para acompañar BWE. El constructor ya no reforma terrenos ni caminos, no crea vallados ni estructuras del catálogo anterior y no convierte cofres a Lootr.

## Uso

`/constructor crear 256` asigna un cuadrado de 256×256 centrado en el punto de creación y comienza la inspección quieto, sin colocar bloques. Se aceptan lados entre 32 y 256; sin número usa 64.

`/constructor iniciar` ejecuta la jardinería después de terminar la inspección. Se mantienen pausar, estado, auditar y cancelar.

## Reglas

- Solo planta sobre bloques existentes `minecraft:grass_block`. Pasto significa vegetación baja encima del césped; no sustituye tierra, piedra, madera, cultivos ni otros materiales.
- Pasto y flores variados en franjas a 1 y 3 bloques de columnas de otros materiales. Si la posición está ocupada o su base no es césped, la omite.
- Varios árboles pequeños completos (roble, abedul o abeto) en lugares a más de 5 bloques de otros materiales. Reserva espacio para sus copas, conserva una separación de 8 bloques entre candidatos y evita las farolas. Omite suelo inclinado o sin espacio; no lo nivela.
- Farolas simétricas en una cuadrícula fija X/Z múltiplos de 16. Exige 3 bloques de separación de columnas no cubiertas por césped, incluidos caminos de piedra, agua y edificios. Los puntos ocupados se omiten; no se desplazan fuera de la cuadrícula.
- El escaneo lee un margen exterior de 6 bloques para respetar construcciones junto al borde; todas las colocaciones quedan dentro del área asignada.
- El terreno y los cofres permanecen intactos. No tapa pozos, rellena barrancos, limpia obstáculos ni retira vallas anteriores.
- Trabaja por sectores y puede caminar sobre pavimento y cultivos sin cambiarlos. Conserva la velocidad de test19.

Las asignaciones anteriores se pausan al actualizar y sus tareas viejas se descartan antes de trabajar. Usar crear de nuevo para elegir el centro y el tamaño del nuevo jardín. Lo construido anteriormente no se deshace.

## Validación

Pruebas en servidor Forge: franjas 1/3, árboles a más de 5, cuadrícula 16 y protección de caminos, inspección sin cambios, colocación real de árboles y farolas, conservación de suelo/pozos/agua/cultivos/cofres, navegación, migración y comandos hasta 256.
