package net.barttolo.bots;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.commands.CommandSourceStack;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Slow, visible, server-side village beautification pass. No drops or item inventory. */
public final class ConstructorBuilder {
    private record Placement(BlockPos pos, BlockState state) {}

    private FakePlayer actor;
    private final Set<UUID> visible=new HashSet<>(),listed=new HashSet<>();
    private final ArrayDeque<Placement> pending=new ArrayDeque<>();
    private int visualX,visualY,visualZ;
    private boolean announced,visualInitialized;

    public int create(CommandSourceStack source,int width){
        ConstructorData d=ConstructorData.get(source.getServer());
        d.dimension=source.getLevel().dimension().location().toString();
        d.centerX=(int)Math.floor(source.getPosition().x);d.centerY=(int)Math.floor(source.getPosition().y);d.centerZ=(int)Math.floor(source.getPosition().z);
        d.width=width;d.cursor=0;d.placed=0;d.skipped=0;d.configured=true;d.active=false;d.finished=false;d.setDirty();
        visualX=d.centerX;visualY=d.centerY;visualZ=d.centerZ;visualInitialized=true;
        pending.clear();announced=false;resetActor(source.getServer());
        source.sendSuccess(()->Component.literal("Constructor asignado: "+width+"×"+width+" bloques, centrado en "+d.centerX+" "+d.centerY+" "+d.centerZ+". Usá /constructor iniciar para comenzar la mejora gradual de la aldea."),false);return 1;
    }
    public int start(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        if(!d.configured){source.sendFailure(Component.literal("Primero asigná una zona con /constructor crear [32..128]."));return 0;}
        if(d.finished){source.sendFailure(Component.literal("La zona ya fue revisada. Asigná otra con /constructor crear."));return 0;}
        d.active=true;d.setDirty();announced=false;
        source.sendSuccess(()->Component.literal("Constructor en marcha. Revisará la zona y trabajará bloque por bloque; puede tardar bastante."),false);return 1;
    }
    public int pause(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.setDirty();
        source.sendSuccess(()->Component.literal("Constructor pausado. El progreso de la zona quedó guardado."),false);return 1;
    }
    public int cancel(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.configured=false;d.finished=false;d.setDirty();pending.clear();resetActor(source.getServer());
        source.sendSuccess(()->Component.literal("Asignación cancelada. Los bloques ya colocados se conservan."),false);return 1;
    }
    public int status(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        String state=!d.configured?"sin zona":d.active?"trabajando":d.finished?"terminado":"pausado";
        int total=d.configured?d.width*d.width:0;
        String progress=d.configured?" | revisión "+Math.min(d.cursor,total)+"/"+total+" | cambios "+d.placed+" | sin cambios "+d.skipped:"";
        source.sendSuccess(()->Component.literal("Constructor "+state+progress+" | "+(d.configured?d.dimension+" centro "+d.centerX+" "+d.centerY+" "+d.centerZ+" | zona "+d.width+"×"+d.width:"")),false);return 1;
    }

    public void tick(MinecraftServer server){
        ConstructorData d=ConstructorData.get(server);
        if(!d.configured)return;
        if(!visualInitialized){visualX=d.centerX;visualY=d.centerY;visualZ=d.centerZ;visualInitialized=true;}
        ServerLevel level=level(server,d.dimension);if(level==null)return;
        if(d.active&&server.getTickCount()%5==0)step(server,level,d);
        sync(server,level,d);
    }
    private void step(MinecraftServer server,ServerLevel level,ConstructorData d){
        if(!pending.isEmpty()){
            Placement action=pending.removeFirst();
            if(level.hasChunkAt(action.pos())&&level.getBlockState(action.pos()).canBeReplaced()){
                moveActor(level,action.pos().getX()+.5,action.pos().getY(),action.pos().getZ()+.5);
                level.setBlock(action.pos(),action.state(),3);d.placed++;d.setDirty();animate(server,level);
            }else d.skipped++;
            return;
        }
        int total=d.width*d.width;
        if(d.cursor>=total){d.active=false;d.finished=true;d.setDirty();announce(server,level,"Terminé: revisé "+total+" columnas y realicé "+d.placed+" cambios. Podés asignarme otra zona con /constructor crear.");return;}

        int row=d.cursor/d.width,col=d.cursor%d.width;
        if((row&1)==1)col=d.width-1-col; // serpentine route so the avatar visibly traverses the plot
        int minX=d.centerX-d.width/2,minZ=d.centerZ-d.width/2;
        int x=minX+col,z=minZ+row;
        d.cursor++;
        BlockPos ground=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,0,z)).below();
        moveActor(level,x+.5,ground.getY()+1,z+.5);
        if(!level.hasChunkAt(ground)||!level.canSeeSky(ground.above())){d.skipped++;return;}
        BlockState floor=level.getBlockState(ground);
        if(planSmallTerrace(level,ground))return;
        if(floor.is(Blocks.DIRT)){
            pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState()));return;
        }
        // A small, deterministic lantern network anchored to existing village paths.
        if(floor.is(Blocks.DIRT_PATH)&&Math.floorMod(x*31+z*17,13)==0){
            planLamp(level,ground);if(!pending.isEmpty())return;
        }
        if(!floor.is(Blocks.GRASS_BLOCK)||!level.getFluidState(ground.above()).isEmpty())return;
        // Sparse trees and flowers use only vanilla blocks and never replace existing blocks.
        int seed=Math.floorMod(x*73428767 ^ z*912931,1000);
        if(seed<9&&clearForTree(level,ground)){
            Block sapling=saplingFor(level,ground);
            pending.add(new Placement(ground.above(),sapling.defaultBlockState()));return;
        }
        if(seed>=20&&seed<46&&clearForPlant(level,ground)){
            Block flower=flowerFor(level,x,z);
            pending.add(new Placement(ground.above(),flower.defaultBlockState()));
        }
    }
    private void planLamp(ServerLevel level,BlockPos path){
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos base=path.offset(dir[0],0,dir[1]);
            BlockState soil=level.getBlockState(base);
            if(!(soil.is(Blocks.GRASS_BLOCK)||soil.is(Blocks.DIRT))||!level.canSeeSky(base.above()))continue;
            if(!level.getBlockState(base.above()).canBeReplaced()||!level.getBlockState(base.above(2)).canBeReplaced()||!level.getBlockState(base.above(3)).canBeReplaced())continue;
            pending.add(new Placement(base.above(),Blocks.OAK_FENCE.defaultBlockState()));
            pending.add(new Placement(base.above(2),Blocks.OAK_FENCE.defaultBlockState()));
            pending.add(new Placement(base.above(3),Blocks.LANTERN.defaultBlockState()));return;
        }
    }
    /** Fills only exposed, dry, one-block air gaps beside a surface two blocks higher. */
    private boolean planSmallTerrace(ServerLevel level,BlockPos ground){
        if(!(level.getBlockState(ground).is(Blocks.GRASS_BLOCK)||level.getBlockState(ground).is(Blocks.DIRT)))return false;
        BlockPos target=ground.above();if(!level.getBlockState(target).isAir()||!level.getFluidState(target).isEmpty())return false;
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            int nx=ground.getX()+dir[0],nz=ground.getZ()+dir[1];
            BlockPos neighbor=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(nx,0,nz)).below();
            int difference=neighbor.getY()-ground.getY();
            if(difference>=2&&difference<=3&&level.getBlockState(neighbor).is(Blocks.GRASS_BLOCK)&&level.canSeeSky(target)){
                pending.add(new Placement(target,Blocks.DIRT.defaultBlockState()));return true;
            }
        }return false;
    }
    private boolean clearForPlant(ServerLevel level,BlockPos ground){
        BlockPos p=ground.above();if(!level.getBlockState(p).canBeReplaced())return false;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(level.getBlockState(p.offset(dx,0,dz)).is(Blocks.DIRT_PATH))return false;
        return true;
    }
    private boolean clearForTree(ServerLevel level,BlockPos ground){
        if(!clearForPlant(level,ground)||!level.getBlockState(ground.above(2)).isAir())return false;
        for(BlockPos p:BlockPos.betweenClosed(ground.offset(-4,0,-4),ground.offset(4,5,4))){
            Block b=level.getBlockState(p).getBlock();if(b instanceof SaplingBlock||b==Blocks.OAK_LOG||b==Blocks.SPRUCE_LOG||b==Blocks.BIRCH_LOG||b==Blocks.OAK_LEAVES||b==Blocks.SPRUCE_LEAVES||b==Blocks.BIRCH_LEAVES)return false;
        }return true;
    }
    private Block saplingFor(ServerLevel level,BlockPos ground){
        String biome=level.getBiome(ground).unwrapKey().map(k->k.location().getPath()).orElse("");
        if(biome.contains("taiga")||biome.contains("pine")||biome.contains("grove"))return Blocks.SPRUCE_SAPLING;
        if(biome.contains("birch"))return Blocks.BIRCH_SAPLING;return Blocks.OAK_SAPLING;
    }
    private Block flowerFor(ServerLevel level,int x,int z){
        int n=Math.floorMod(x*19+z*73,5);
        if(n==0)return Blocks.POPPY;if(n==1)return Blocks.DANDELION;if(n==2)return Blocks.AZURE_BLUET;
        if(n==3)return Blocks.OXEYE_DAISY;return Blocks.CORNFLOWER;
    }

    private void ensureActor(ServerLevel level){
        if(actor!=null)return;
        GameProfile profile=new GameProfile(UUID.nameUUIDFromBytes("barttolobots:constructor:v1".getBytes(StandardCharsets.UTF_8)),"Constructor");
        actor=new FakePlayer(level,profile){ {getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION,(byte)127);} };
        actor.setNoGravity(true);actor.setInvulnerable(true);actor.setPos(visualX+.5,visualY,visualZ+.5);
    }
    private void moveActor(ServerLevel level,double x,double y,double z){
        ensureActor(level);visualX=(int)Math.floor(x);visualY=(int)Math.floor(y);visualZ=(int)Math.floor(z);
        double dx=x-actor.getX(),dz=z-actor.getZ();
        if(Math.abs(dx)+Math.abs(dz)>.01){float yaw=(float)(Math.toDegrees(Math.atan2(-dx,dz)));actor.setYRot(yaw);actor.setYHeadRot(yaw);}
        actor.setPos(x,y,z);
    }
    private void sync(MinecraftServer server,ServerLevel level,ConstructorData d){
        ensureActor(level);actor.setPos(visualX+.5,visualY,visualZ+.5);
        for(ServerPlayer p:server.getPlayerList().getPlayers()){
            boolean close=p.level()==level&&p.distanceToSqr(actor)<=128*128&&p.serverLevel().hasChunkAt(actor.blockPosition());
            UUID id=p.getUUID();
            if(close&&listed.add(id))p.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(actor)));
            if(close&&visible.add(id)){
                p.connection.send(new ClientboundAddPlayerPacket(actor));var data=actor.getEntityData().getNonDefaultValues();
                if(data!=null)p.connection.send(new ClientboundSetEntityDataPacket(actor.getId(),data));
            }else if(close&&server.getTickCount()%5==0)p.connection.send(new ClientboundTeleportEntityPacket(actor));
            else if(!close&&visible.remove(id))p.connection.send(new ClientboundRemoveEntitiesPacket(actor.getId()));
            if(!close&&listed.remove(id))p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(actor.getUUID())));
        }
    }
    private void animate(MinecraftServer server,ServerLevel level){
        if(actor==null)return;
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(p.level()==level&&visible.contains(p.getUUID()))p.connection.send(new ClientboundAnimatePacket(actor,(byte)0));
    }
    private void announce(MinecraftServer server,ServerLevel level,String message){
        if(announced)return;announced=true;
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(p.level()==level)p.sendSystemMessage(Component.literal("Constructor: ").withStyle(ChatFormatting.GOLD).append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
    }
    private ServerLevel level(MinecraftServer server,String key){
        ResourceLocation loc=ResourceLocation.tryParse(key);if(loc==null)return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION,loc));
    }
    private void resetActor(MinecraftServer server){
        if(actor!=null)for(ServerPlayer p:server.getPlayerList().getPlayers()){
            if(visible.contains(p.getUUID()))p.connection.send(new ClientboundRemoveEntitiesPacket(actor.getId()));
            if(listed.contains(p.getUUID()))p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(actor.getUUID())));
        }
        actor=null;visible.clear();listed.clear();pending.clear();
    }
}
