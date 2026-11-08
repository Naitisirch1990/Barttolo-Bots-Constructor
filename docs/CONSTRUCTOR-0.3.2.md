# Constructor 0.3.2 — puentes profundos y búsqueda acotada

Minecraft 1.20.1 / Forge 47.4.10 / Java 17. Reemplaza el JAR anterior de Barttolo Bots
con el servidor detenido. Mantiene los cambios de rutas rectas y terreno helado de 0.3.1.

## Correcciones

- Amplía hasta 32 bloques la búsqueda de apoyo bajo secciones elevadas, para poder diseñar
  pilares en barrancos más profundos. Los apoyos del puente se revisan y colocan cada cinco
  secciones y en los extremos.
- Limita a 512 estados la búsqueda de rutas de hasta 64 bloques. Si no hay un trazado seguro,
  termina con una indicación para mover los puntos hacia los bordes del obstáculo, en vez de
  quedarse explorando indefinidamente.
- Añade una prueba Forge de un puente recto de 20 bloques sobre una quebrada de 23 bloques
  de profundidad, además del camino recto sobre hielo compacto.

## Uso

En un mundo descartable, reemplaza el JAR y reinicia. Ejecuta `/bot constructor crear`, marca
`puntoA` y `puntoB`, y usa `planificar`. Cuando indique LISTO, revisa con `vista` antes de
`iniciar`. Para reiniciar una planificación vieja, crea una obra nueva y vuelve a marcar A/B.

La prueba automatizada no sustituye la inspección visual en tu mapa; si el plan se detiene,
envíame captura del estado y de los dos extremos para ajustar el siguiente caso.
