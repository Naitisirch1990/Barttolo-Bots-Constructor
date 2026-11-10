# Barttolo Asistente + Ayudante

Forge 1.20.1, Forge 47.4.10, Java 17. Server side.

## Separate mods

- **Barttolo Asistente** (`barttolobots`): spawn welcome NPC and clickable `/asistente` help menu. Keeps the previous assistant mod ID and SavedData key so welcome history, NPC placement, and the existing guide config remain in place.
- **Barttolo Ayudante** (`barttoloayudante`): gardener/construction worker and its persistent work plan. Keeps the previous constructor SavedData key and gardening schema.

Remove the old combined `Barttolo-Bots-Forge` JAR before installing both new JARs. Do not leave the old and split versions installed together.

## Help topics

The Asistente adds a clickable `[AYUDA]` to every welcome and return welcome. It opens clickable topics for Eventos, Asaltos, Misiones, Invasiones, Reliquias, RingWorld, Economía, and Premios (R-Coin) en el shop. Topics are also available through `/asistente <tema>`.

## Jardinero / Constructor

Both `/constructor` and `/jardinero` expose the same functions. To set an area and begin autonomously:

1. Stand at one corner and run `/constructor puntoA`.
2. Stand at the opposite corner and run `/constructor puntoB`.

Punto B starts the full inspection and automatically begins the work once inspection is done. The work area is a square that contains both points, with a minimum side of 32 and maximum of 256 blocks. The area is not terrain-leveled, fenced, or filled. Existing commands such as `/constructor crear 64`, `/constructor estado`, `/constructor ir`, `/constructor continuar`, `/constructor pausar`, and `/constructor cancelar` remain available.

The bot runs on server ticks and holds its own non-entity-ticking chunk tickets while working; the player does not need to stay nearby. The plan and placement memory are stored in world SavedData.
