# Jardinero test22 — Forge 1.20.1 / 47.4.10

Reemplazar el JAR anterior de Barttolo Bots con el servidor apagado; no instalar dos versiones juntas. La asignación de test21 y su memoria se conservan. No hace falta crear otra área.

- `/jardinero ir`: te lleva a suelo seguro junto al Jardinero, incluso en otra dimensión.
- `/jardinero continuar`: busca suelo seguro a 2–8 bloques, traslada al Jardinero y retoma la lista pendiente. Conserva trabajos completos y parciales; no redibuja la cuadrícula ni crea otra asignación. Si no hay suelo seguro, mantiene todo intacto y avisa.
- `/jardinero estado`: progreso, dimensión y coordenadas.
- `/jardinero pausar`, `/jardinero iniciar`, `/jardinero auditar`, `/jardinero cancelar`, `/jardinero crear [32..256]`.
- `/jardinero piel <jugador>`: copia la skin disponible de un jugador conectado. Por defecto lleva gorra amarilla, ropa verde, botas marrones y azada; es ropa de oficio, no una skin descargada de un tercero.

`/constructor` permanece como alias compatible con los mismos subcomandos. Los comandos requieren OP.

El área y un halo de lectura de seis bloques permanecen cargados durante la inspección o el trabajo, aunque los jugadores se alejen. Se usan tickets privados de solo carga, no se simula toda el área ni se modifica `/forceload`. Se liberan al pausar, cancelar, completar, quedar detenido o apagar el servidor. Máximo 18×18 chunks de solo carga para un área de 256. El servidor debe estar encendido y procesando ticks. No es un sistema de progreso offline.

La posición se guarda junto al plan para recuperarla tras reinicios. `continuar` reintenta tareas inaccesibles desde la nueva posición, pero no elimina edificios o caminos para abrir paso. `auditar` sí realiza una inspección nueva.

Continúa el modo de jardinería: césped existente, árboles/farolas cada 12 bloques si caben, pequeñas construcciones en zonas libres. No rellena pozos ni barrancos, nivela, crea vallados o convierte cofres a Lootr.
