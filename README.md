# Barttolo Bots — Constructor 0.2.0 — terreno adaptativo

El nuevo oficio se utiliza con `/bot constructor`. Asistente y Jardinero conservan sus comandos.

Versión experimental para copias de prueba. Los JAR se publican en Releases únicamente después de superar la compilación y las pruebas automatizadas de servidor de GitHub Actions.
Consultar [alcance, comandos y límites](docs/CONSTRUCTOR-0.2.0.md).

---

# Barttolo Jardinero 0.5.1 / test21

- Farolas cada 12 bloques. La inspección prueba las 144 alineaciones posibles y elige la cuadrícula de 12 con más sitios válidos, también en parcelas pequeñas. Omite posiciones junto a caminos o construcciones.
- Árboles en una cuadrícula de aproximadamente 12 bloques, sin el filtro aleatorio que podía dejar áreas pequeñas sin árboles. Conserva la separación de edificios/caminos y deja espacio para las copas y las farolas propias.
- Recupera cultivos, plazoletas pequeñas, bancos/jardineras, pérgolas, pozos decorativos, puestos, merenderos y estanques. Solo propone los que caben en césped plano y libre. Reserva su espacio antes de plantar y ajusta las farolas para evitar colisiones.
- Cultivos y construcciones usan plataformas elevadas con acceso, sin excavar ni reemplazar el suelo existente. El agua se coloca después de cerrar el recipiente y las plantas acuáticas/cultivos al final.
- Mantiene las franjas de pasto y flores a 1 y 3 bloques y el máximo de 256×256. No rellena barrancos ni pozos, no repara caminos, no añade vallados perimetrales y no toca cofres existentes.
- La tierra que aparece naturalmente bajo una base propia no bloquea la continuación de una construcción iniciada. No permite usar tierra desnuda para una construcción nueva.

Reemplazar el JAR anterior y reiniciar. Desde el centro deseado: `/constructor crear 32` (o 40, 48, 64, 128, 256) y `/constructor iniciar`. Las construcciones ya colocadas permanecen. Las asignaciones anteriores se pausan y se vuelven a inspeccionar al actualizar.
