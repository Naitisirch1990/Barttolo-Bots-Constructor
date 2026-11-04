package net.barttolo.bots;

/** Extension point: future roles can implement this without changing the host guide. */
public interface BotRole {
    String id();
    String reply(String question);
}
