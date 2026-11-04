package net.barttolo.bots;

public final class HostRole implements BotRole {
    private final Guide guide;
    public HostRole(Guide guide){this.guide=guide;}
    @Override public String id(){return "anfitrion";}
    @Override public String reply(String question){return guide.answer(question);}
}
