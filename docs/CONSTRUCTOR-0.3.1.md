# Constructor 0.3.1 — respuesta rápida en tramos rectos

Minecraft 1.20.1 / Forge 47.4.10 / Java 17. Reemplaza el JAR anterior de Barttolo Bots
con el servidor detenido. Esta versión mantiene las pendientes suaves, luces y movimiento
visual de la 0.3.0.

## Correcciones

- Comprueba primero los trayectos rectos, llanos y de hasta 128 bloques. Un tramo despejado
  de 20 bloques debe pasar a diseño tras revisar sus secciones, sin explorar miles de rodeos.
- Reconoce hielo, hielo compacto, hielo azul y hielo escarchado como bloques naturales
  despejables; los bloques de hielo no bloquean la ruta por defecto.
- Añade una prueba Forge de un camino recto de 20 bloques sobre hielo compacto, desde la
  planificación hasta el fin de la construcción.

## Uso

En un mundo descartable, reemplaza el JAR, reinicia y vuelve a marcar A/B. Para la búsqueda
que quedó atascada, ejecuta `/bot constructor detener`, luego vuelve a crear y marcar los
puntos. Usa `/bot constructor planificar`; cuando diga LISTO, revisa con `vista` antes de
`iniciar`.

La integración visual sobre el RingWorld real aún requiere prueba dentro del juego.
