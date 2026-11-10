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
    private static final ConstructorBuilder CONSTRUCTOR = new ConstructorBuilder();

    public AyudanteMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        ConstructorData.get(event.getServer());
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        CONSTRUCTOR.shutdown(event.getServer());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) CONSTRUCTOR.tick(event.getServer());
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(constructorRoot());
        event.getDispatcher().register(gardenerAlias());
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> constructorRoot() {
        return Commands.literal("constructor").requires(source -> source.hasPermission(2))
            .executes(ctx -> CONSTRUCTOR.status(ctx.getSource()))
            .then(Commands.literal("puntoA").executes(ctx -> markPointA(ctx.getSource())))
            .then(Commands.literal("puntoB").executes(ctx -> markPointB(ctx.getSource())))
            .then(Commands.literal("puntoa").executes(ctx -> markPointA(ctx.getSource())))
            .then(Commands.literal("puntob").executes(ctx -> markPointB(ctx.getSource())))
            .then(Commands.literal("crear")
                .executes(ctx -> CONSTRUCTOR.create(ctx.getSource(), 64))
                .then(Commands.argument("ancho", IntegerArgumentType.integer(32, 256))
                    .executes(ctx -> CONSTRUCTOR.create(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ancho")))))
            .then(Commands.literal("ir").executes(ctx -> CONSTRUCTOR.teleportTo(ctx.getSource())))
            .then(Commands.literal("continuar").executes(ctx -> CONSTRUCTOR.resume(ctx.getSource())))
            .then(Commands.literal("piel").then(Commands.argument("jugador", net.minecraft.commands.arguments.EntityArgument.player())
                .executes(ctx -> CONSTRUCTOR.skin(ctx.getSource(), net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "jugador")))))
            .then(Commands.literal("iniciar").executes(ctx -> CONSTRUCTOR.start(ctx.getSource())))
            .then(Commands.literal("pausar").executes(ctx -> CONSTRUCTOR.pause(ctx.getSource())))
            .then(Commands.literal("auditar").executes(ctx -> CONSTRUCTOR.audit(ctx.getSource())))
            .then(Commands.literal("estado").executes(ctx -> CONSTRUCTOR.status(ctx.getSource())))
            .then(Commands.literal("cancelar").executes(ctx -> CONSTRUCTOR.cancel(ctx.getSource())));
    }

    /** Compatibility root: both public command names directly expose the same actions. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> gardenerAlias() {
        return Commands.literal("jardinero").requires(source -> source.hasPermission(2))
            .executes(ctx -> CONSTRUCTOR.status(ctx.getSource()))
            .then(Commands.literal("puntoA").executes(ctx -> markPointA(ctx.getSource())))
            .then(Commands.literal("puntoB").executes(ctx -> markPointB(ctx.getSource())))
            .then(Commands.literal("puntoa").executes(ctx -> markPointA(ctx.getSource())))
            .then(Commands.literal("puntob").executes(ctx -> markPointB(ctx.getSource())))
            .then(Commands.literal("crear")
                .executes(ctx -> CONSTRUCTOR.create(ctx.getSource(), 64))
                .then(Commands.argument("ancho", IntegerArgumentType.integer(32, 256))
                    .executes(ctx -> CONSTRUCTOR.create(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ancho")))))
            .then(Commands.literal("ir").executes(ctx -> CONSTRUCTOR.teleportTo(ctx.getSource())))
            .then(Commands.literal("continuar").executes(ctx -> CONSTRUCTOR.resume(ctx.getSource())))
            .then(Commands.literal("piel").then(Commands.argument("jugador", net.minecraft.commands.arguments.EntityArgument.player())
                .executes(ctx -> CONSTRUCTOR.skin(ctx.getSource(), net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "jugador")))))
            .then(Commands.literal("iniciar").executes(ctx -> CONSTRUCTOR.start(ctx.getSource())))
            .then(Commands.literal("pausar").executes(ctx -> CONSTRUCTOR.pause(ctx.getSource())))
            .then(Commands.literal("auditar").executes(ctx -> CONSTRUCTOR.audit(ctx.getSource())))
            .then(Commands.literal("estado").executes(ctx -> CONSTRUCTOR.status(ctx.getSource())))
            .then(Commands.literal("cancelar").executes(ctx -> CONSTRUCTOR.cancel(ctx.getSource())));
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
            + " (" + data.pointDimension + "). Ve a la esquina opuesta y usa /constructor puntoB."), false);
        return 1;
    }

    private static int markPointB(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ConstructorData data = ConstructorData.get(source.getServer());
        if (!data.hasPointA) {
            source.sendFailure(Component.literal("Primero marca la primera esquina con /constructor puntoA."));
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
        int result = CONSTRUCTOR.createAt(source, width, centerX, centerZ, true);
        if (result > 0) {
            data.hasPointA = false;
            data.setDirty();
            source.sendSuccess(() -> Component.literal("Auditoría iniciada para el área delimitada por las esquinas. Al finalizar, el Jardinero empezará sin otro comando."), false);
        }
        return result;
    }
}
