## 0.4.6 / constructor-test19 — parcela cuadrada sin marcadores

- Eliminada la búsqueda y selección de esquinas de bedrock. `/constructor crear 128` define directamente una parcela de 128×128 centrada en el punto de creación.
- Se mantienen la auditoría inicial, el recorrido por sectores y las etapas: terreno → caminos → iluminación/construcciones → vallado final.
- Las asignaciones antiguas que utilizaban marcadores se pausan al cargar. Sus límites y tareas anteriores se descartan para una nueva auditoría cuadrada; se conserva la memoria de construcciones y vallas propias. Los bloques ya colocados no se revierten.
- Las tareas de césped pueden realizarse desde un sitio cercano, incluso cuando haya hojas sobre la tierra. No necesitan entrar debajo del follaje ni retirarlo.
- Bedrock sigue siendo un bloque protegido del mundo; no lo elimina ni lo utiliza como marcador.

Tras reemplazar el JAR y reiniciar, asignar la parcela desde el centro deseado con `/constructor crear 128` y ejecutar `/constructor iniciar`. Las demás funciones conservan su comportamiento.

## 0.4.4 / constructor-test17

- `/constructor iniciar` realiza una nueva auditoría completa y quieta de la parcela antes de caminar. Conserva los trabajos pendientes y su estado si el servidor se reinicia durante el análisis. Si ya está trabajando, no interrumpe el recorrido.
- Completa los trabajos alcanzables de cada sector de 16×16 antes de elegir otro por longitud de ruta. Dentro del sector prioriza el terreno y los caminos. Los obstáculos reales siguen figurando como pendientes.
- Repara los bloques de camino ya auditados que encuentra a sus pies y a un bloque al costado durante un traslado, sin inventar nuevos senderos.
- Los pozos y taludes reconocen el fondo firme bajo agua/lava, pétalos, flores y hojas. Retira únicamente la cobertura que intercepta el relleno y el espacio de trabajo. No utiliza el líquido o las hojas como cimentación; rellena desde abajo con tierra y termina con césped.
- Mantiene protegidos troncos, cultivos, bloques con inventario, construcciones y estanques recordados del catálogo. Conserva los límites de parcela y de profundidad (pozos 6, taludes 12); no rellena ríos enteros fuera del talud planificado.
- Velocidad y separación de farolas sin cambios respecto de test16.
- Pruebas Forge: borde con agua, arcilla, pétalos y hojas; protección de troncos y estanques; auditoría sin movimiento ni cambios, persistencia de pendientes, trabajo por sectores y camino durante el recorrido, más regresiones existentes.

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
