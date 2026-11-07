package net.barttolo.bots;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.*;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkRegistry;
import org.slf4j.Logger;
import java.util.*;

@Mod(Bots.ID)
public final class Bots {
    public static final String ID="barttolobots";
    private static final Logger LOG=LogUtils.getLogger();
    public static Guide guide=Guide.defaults();
    public static final HostVisual visual=new HostVisual();
    public static final ConstructorBuilder constructor=new ConstructorBuilder();
    private static final Map<UUID,Integer> welcomeAt=new HashMap<>(),replyAt=new HashMap<>();
    public Bots(){
        // An optional channel with no payloads explicitly permits clients without this mod.
        NetworkRegistry.newSimpleChannel(new net.minecraft.resources.ResourceLocation(ID,"optional"),()->"1",
            version->true,version->true);
        MinecraftForge.EVENT_BUS.register(this);
    }
    @SubscribeEvent public void started(ServerStartedEvent e){
        welcomeAt.clear();replyAt.clear();
        try{guide=GuideConfig.load();}catch(Exception ex){guide=Guide.defaults();LOG.error("No se pudo cargar la guía; se usan valores de referencia. No se sobrescribió el archivo.",ex);}
        HostData.get(e.getServer());
        ConstructorData.get(e.getServer());
    }
    @SubscribeEvent public void stopping(ServerStoppingEvent e){visual.clear(e.getServer());constructor.shutdown(e.getServer());welcomeAt.clear();replyAt.clear();}
    @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent e){
        if(e.getEntity() instanceof ServerPlayer p&&!(p instanceof FakePlayer)){
            visual.forget(p.getUUID());welcomeAt.put(p.getUUID(),p.server.getTickCount()+guide.welcomeDelaySeconds*20);
        }
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent e){
        UUID id=e.getEntity().getUUID();visual.forget(id);welcomeAt.remove(id);replyAt.remove(id);
    }
    @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent e){visual.forget(e.getEntity().getUUID());}
    @SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent e){visual.forget(e.getEntity().getUUID());}
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;
        MinecraftServer s=e.getServer();int now=s.getTickCount();
        constructor.tick(s);
        if(now%20!=0)return;
        HostData d=HostData.get(s);
        visual.sync(s,d,guide);
        var iterator=welcomeAt.entrySet().iterator();
        while(iterator.hasNext()){
            var job=iterator.next();if(now<job.getValue())continue;
            ServerPlayer p=s.getPlayerList().getPlayer(job.getKey());iterator.remove();
            if(p==null||!d.enabled)continue;
            boolean first=d.welcomed.add(p.getUUID());if(first)d.setDirty();
            tell(p,guide.expand(first?guide.firstWelcome:guide.returnWelcome,p.getGameProfile().getName()));
            if(first)menu(p);
        }
    }
    @SubscribeEvent public void chat(ServerChatEvent e){
        String text=e.getRawText();if(!guide.addressed(text))return;
        ServerPlayer p=e.getPlayer();MinecraftServer s=p.server;
        s.execute(()->{
            HostData d=HostData.get(s);
            if(d.enabled&&p.level().dimension().location().toString().equals(d.dimension)
                &&p.distanceToSqr(d.x,d.y,d.z)<=guide.chatRadius*guide.chatRadius)answer(p,text);
        });
    }
    public static void tell(ServerPlayer p,String text){
        p.sendSystemMessage(Component.literal(guide.name+": ").withStyle(ChatFormatting.AQUA)
            .append(linkify(text)));
    }
    public static MutableComponent linkify(String text){
        MutableComponent result=Component.empty();
        var matcher=java.util.regex.Pattern.compile("https://discord\\.gg/[A-Za-z0-9]+\\b").matcher(text);
        int start=0;
        while(matcher.find()){
            result.append(Component.literal(text.substring(start,matcher.start())).withStyle(ChatFormatting.WHITE));
            String url=matcher.group();
            result.append(Component.literal(url).withStyle(style->style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal("Abrir Discord de RingWorld")))));
            start=matcher.end();
        }
        result.append(Component.literal(text.substring(start)).withStyle(ChatFormatting.WHITE));return result;
    }
    private static void menu(ServerPlayer p){
        MutableComponent line=Component.literal(guide.name+" · Temas: ").withStyle(ChatFormatting.AQUA);
        for(String topic:guide.topics.keySet()){
            if(topic.equals("saludo")||topic.equals("identidad"))continue;
            line.append(Component.literal("["+topic+"] ").withStyle(style->style.withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/asistente "+topic))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal("Consultar "+topic)))));
        }
        p.sendSystemMessage(line);
    }
    public static boolean answer(ServerPlayer p,String question){
        int now=p.server.getTickCount();
        if(replyAt.getOrDefault(p.getUUID(),Integer.MIN_VALUE)>now){
            p.sendSystemMessage(Component.literal("Esperá unos segundos antes de consultar otra vez.").withStyle(ChatFormatting.GRAY));return false;
        }
        replyAt.put(p.getUUID(),now+guide.cooldownSeconds*20);
        tell(p,guide.expand(new HostRole(guide).reply(question),p.getGameProfile().getName()));return true;
    }
    @SubscribeEvent public void commands(RegisterCommandsEvent e){
        var dispatcher=e.getDispatcher();
        dispatcher.register(Commands.literal("asistente")
            .executes(ctx->{menu(ctx.getSource().getPlayerOrException());return 1;})
            .then(Commands.argument("pregunta",StringArgumentType.greedyString())
                .suggests((ctx,b)->{for(String key:guide.topics.keySet())if(key.startsWith(b.getRemainingLowerCase()))b.suggest(key);return b.buildFuture();})
                .executes(ctx->answer(ctx.getSource().getPlayerOrException(),StringArgumentType.getString(ctx,"pregunta"))?1:0)));
        dispatcher.register(Commands.literal("bots").requires(src->src.hasPermission(2))
            .then(Commands.literal("anfitrion")
                .then(Commands.literal("colocar").executes(ctx->place(ctx.getSource(),ctx.getSource().getPosition()))
                    .then(Commands.argument("pos",Vec3Argument.vec3()).executes(ctx->place(ctx.getSource(),Vec3Argument.getVec3(ctx,"pos")))))
                .then(Commands.literal("retirar").executes(ctx->{var s=ctx.getSource().getServer();var d=HostData.get(s);d.enabled=false;d.setDirty();visual.clear(s);ctx.getSource().sendSuccess(()->Component.literal("Anfitrión retirado. Se conserva el historial de bienvenidas."),false);return 1;}))
                .then(Commands.literal("estado").executes(ctx->{var d=HostData.get(ctx.getSource().getServer());ctx.getSource().sendSuccess(()->Component.literal("Anfitrión "+(d.enabled?"activo":"desactivado")+" | "+d.dimension+" "+d.x+", "+d.y+", "+d.z+" | radio chat "+guide.chatRadius+" | visual "+visual.exists()+" | rol anfitrion | sin IA externa"),false);return 1;}))
                .then(Commands.literal("piel").then(Commands.argument("jugador",EntityArgument.player()).executes(ctx->{
                    var source=ctx.getSource();var p=EntityArgument.getPlayer(ctx,"jugador");var properties=p.getGameProfile().getProperties().get("textures");
                    if(properties.isEmpty()){source.sendFailure(Component.literal("Ese jugador no tiene una textura de skin disponible."));return 0;}
                    var property=properties.iterator().next();var d=HostData.get(source.getServer());d.skinVersion=2;d.texture=property.getValue();d.signature=property.hasSignature()?property.getSignature():"";d.setDirty();visual.clear(source.getServer());source.sendSuccess(()->Component.literal("Apariencia copiada; mantiene su nombre Asistente y su presentación como ayudante automático."),false);return 1;
                })))
                .then(Commands.literal("recargar").executes(ctx->{
                    try{Guide next=GuideConfig.load();guide=next;visual.clear(ctx.getSource().getServer());ctx.getSource().sendSuccess(()->Component.literal("Guía recargada correctamente."),false);return 1;}
                    catch(Exception ex){LOG.warn("No se recargó la guía",ex);ctx.getSource().sendFailure(Component.literal("No se recargó: "+ex.getMessage()+". Se conserva la guía anterior."));return 0;}
                }))));
        var gardener=dispatcher.register(Commands.literal("jardinero").requires(src->src.hasPermission(2))
            .then(Commands.literal("crear")
                .executes(ctx->constructor.create(ctx.getSource(),64))
                .then(Commands.argument("ancho",IntegerArgumentType.integer(32,256))
                    .executes(ctx->constructor.create(ctx.getSource(),IntegerArgumentType.getInteger(ctx,"ancho")))))
            .then(Commands.literal("continuar").executes(ctx->constructor.resume(ctx.getSource())))
            .then(Commands.literal("iniciar").executes(ctx->constructor.start(ctx.getSource())))
            .then(Commands.literal("pausar").executes(ctx->constructor.pause(ctx.getSource())))
            .then(Commands.literal("auditar").executes(ctx->constructor.audit(ctx.getSource())))
            .then(Commands.literal("estado").executes(ctx->constructor.status(ctx.getSource())))
            .then(Commands.literal("cancelar").executes(ctx->constructor.cancel(ctx.getSource()))));
        dispatcher.register(Commands.literal("constructor").requires(src->src.hasPermission(2)).redirect(gardener));
    }
    private static int place(net.minecraft.commands.CommandSourceStack source,Vec3 pos){
        if(!Double.isFinite(pos.x)||!Double.isFinite(pos.y)||!Double.isFinite(pos.z)
            ||Math.abs(pos.x)>29999900||Math.abs(pos.z)>29999900||pos.y<source.getLevel().getMinBuildHeight()||pos.y>=source.getLevel().getMaxBuildHeight()){
            source.sendFailure(Component.literal("Posición fuera de los límites del mundo."));return 0;
        }
        var d=HostData.get(source.getServer());visual.clear(source.getServer());d.enabled=true;d.dimension=source.getLevel().dimension().location().toString();
        d.x=pos.x;d.y=pos.y;d.z=pos.z;d.yaw=source.getRotation().y;d.setDirty();
        source.sendSuccess(()->Component.literal("Anfitrión colocado. Es fijo, visual y sin colisión. Usá /asistente o mencioná Asistente cerca de él."),false);return 1;
    }
}
