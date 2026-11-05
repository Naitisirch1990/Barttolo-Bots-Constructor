## 0.4.5 / constructor-test18 — cuatro esquinas y orden de trabajo

1. Colocar exactamente cuatro bloques de bedrock, visibles en superficie, en las esquinas de un rectángulo alineado con X/Z. Pueden estar a distintas alturas. Todos deben quedar dentro del cuadrado de búsqueda de `crear 128`; ejecutar crear desde dentro del rectángulo.
2. `/constructor crear 128` y `/constructor iniciar`. Primero busca los marcadores sin mover ni cambiar bloques. Si hay 1–3, más de 4 o no forman un rectángulo, pausa y explica qué corregir. Con cero conserva el modo de parcela cuadrada.
3. Con cuatro esquinas válidas guarda los límites exactos (incluidas las esquinas) y los aplica al escaneo, movimiento, colocación de bloques y conversión Lootr. No elimina los marcadores. No cambia el exterior aunque un plan anterior contenga trabajos allí.
4. Orden obligatorio: terreno y retirada de vallas propias → caminos → iluminación/construcciones → vallado. Trabaja por sectores dentro de cada etapa. Si quedan pendientes en una etapa, no comienza la siguiente. Revisa el suelo actualizado antes de planificar cada etapa.
5. Dentro del rectángulo rellena depresiones hasta el nivel de las entradas de la aldea (mediana), o hasta el suelo de crear si no hay puertas. No usa una montaña exterior como referencia ni excava elevaciones. Límite de relleno 12 bloques; respeta edificios, troncos, cultivos y construcciones recordadas.
6. Retira las vallas que consten en la memoria del constructor antes del relleno, incluidas las que hayan quedado parcialmente enterradas. Reemplaza por tierra si están bajo el nivel final. Preserva vallas ajenas/no registradas. Planifica el nuevo perímetro sobre el suelo final de las esquinas, con accesos libres.
7. Reconoce senderos de piedra estrechos con bordes de césped a la misma altura para situar iluminación, conservando su pavimento.

Velocidad y distancia de farolas sin cambios. Las pruebas incluyen rectángulo no cuadrado, persistencia y marcadores inválidos, límites Lootr, retirada de vallas propias, protección de vallas ajenas, relleno sólido y orden de etapas, más las regresiones existentes.

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
