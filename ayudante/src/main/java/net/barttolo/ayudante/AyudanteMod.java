package net.barttolo.ayudante;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(AyudanteMod.ID)
public final class AyudanteMod {
    public static final String ID = "barttoloayudante";
    private static final ConstructorBuilder JARDINERO = new ConstructorBuilder();

    public AyudanteMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        ConstructorData.get(event.getServer());
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        JARDINERO.shutdown(event.getServer());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) { JARDINERO.tick(event.getServer()); BRIDGES.tick(event.getServer()); }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(constructorRoot());
        event.getDispatcher().register(gardenerAlias());
    }

    private static final BridgeBuilder BRIDGES = new BridgeBuilder();
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> constructorRoot() {
        return Commands.literal("constructor").requires(source -> source.hasPermission(2))
            .executes(ctx -> BRIDGES.status(ctx.getSource()))
            .then(Commands.literal("crear").executes(ctx -> BRIDGES.pointA(ctx.getSource())))
            .then(Commands.literal("puntoA").executes(ctx -> BRIDGES.pointA(ctx.getSource())))
            .then(Commands.literal("puntoB").executes(ctx -> BRIDGES.pointB(ctx.getSource())))
            .then(Commands.literal("estado").executes(ctx -> BRIDGES.status(ctx.getSource())))
            .then(Commands.literal("iniciar").executes(ctx -> BRIDGES.resume(ctx.getSource())))
            .then(Commands.literal("continuar").executes(ctx -> BRIDGES.resume(ctx.getSource())))
            .then(Commands.literal("pausar").executes(ctx -> BRIDGES.pause(ctx.getSource())))
            .then(Commands.literal("cancelar").executes(ctx -> BRIDGES.cancel(ctx.getSource())))
            .then(Commands.literal("deshacer").executes(ctx -> BRIDGES.undo(ctx.getSource())))
            .then(Commands.literal("ir").executes(ctx -> BRIDGES.teleport(ctx.getSource())));
    }

    /** Gardening selection and execution are independent from bridge jobs. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> gardenerAlias() {
        return Commands.literal("jardinero").requires(source -> source.hasPermission(2))
            .executes(ctx -> JARDINERO.status(ctx.getSource()))
            .then(Commands.literal("puntoA").executes(ctx -> markPointA(ctx.getSource())))
            .then(Commands.literal("puntoB").executes(ctx -> markPointB(ctx.getSource())))
            .then(Commands.literal("crear")
                .executes(ctx -> JARDINERO.create(ctx.getSource(), 64))
                .then(Commands.argument("ancho", IntegerArgumentType.integer(32, 256))
                    .executes(ctx -> JARDINERO.create(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ancho")))))
            .then(Commands.literal("ir").executes(ctx -> JARDINERO.teleportTo(ctx.getSource())))
            .then(Commands.literal("continuar").executes(ctx -> JARDINERO.resume(ctx.getSource())))
            .then(Commands.literal("piel").then(Commands.argument("jugador", net.minecraft.commands.arguments.EntityArgument.player())
                .executes(ctx -> JARDINERO.skin(ctx.getSource(), net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "jugador")))))
            .then(Commands.literal("iniciar").executes(ctx -> JARDINERO.start(ctx.getSource())))
            .then(Commands.literal("pausar").executes(ctx -> JARDINERO.pause(ctx.getSource())))
            .then(Commands.literal("auditar").executes(ctx -> JARDINERO.audit(ctx.getSource())))
            .then(Commands.literal("estado").executes(ctx -> JARDINERO.status(ctx.getSource())))
            .then(Commands.literal("cancelar").executes(ctx -> JARDINERO.cancel(ctx.getSource())));
    }

    private static int markPointA(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ConstructorData data = ConstructorData.get(source.getServer());
        BlockPos pos = player.blockPosition();
        data.pointAX = pos.getX();
        data.pointAY = pos.getY();
        data.pointAZ = pos.getZ();
        data.pointDimension = player.level().dimension().location().toString();
        data.hasPointA = true;
        data.setDirty();
        source.sendSuccess(() -> Component.literal("Punto A guardado en " + data.pointAX + " " + data.pointAY + " " + data.pointAZ
            + " (" + data.pointDimension + "). Ve a la esquina opuesta y usa /jardinero puntoB."), false);
        return 1;
    }

    private static int markPointB(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ConstructorData data = ConstructorData.get(source.getServer());
        if (!data.hasPointA) {
            source.sendFailure(Component.literal("Primero marca la primera esquina con /jardinero puntoA."));
            return 0;
        }
        String dimension = player.level().dimension().location().toString();
        if (!data.pointDimension.equals(dimension)) {
            source.sendFailure(Component.literal("Punto B debe marcarse en la misma dimensión que Punto A."));
            return 0;
        }
        BlockPos b = player.blockPosition();
        int span = Math.max(Math.abs(b.getX() - data.pointAX), Math.abs(b.getZ() - data.pointAZ)) + 1;
        int width = Math.max(32, span);
        if (width > 256) {
            source.sendFailure(Component.literal("Las esquinas abarcan " + width + " bloques; el máximo admitido es 256. Punto A sigue guardado."));
            return 0;
        }
        int centerX = Math.min(b.getX(), data.pointAX) + width / 2;
        int centerZ = Math.min(b.getZ(), data.pointAZ) + width / 2;
        int result = JARDINERO.createAt(source, width, centerX, centerZ, true);
        if (result > 0) {
            data.hasPointA = false;
            data.setDirty();
            source.sendSuccess(() -> Component.literal("Auditoría iniciada para el área delimitada por las esquinas. Al finalizar, el Jardinero empezará sin otro comando."), false);
        }
        return result;
    }
}
