package net.barttolo.ayudante;

import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Automatic, bounded A-to-B construction, independent of any player's loaded chunks. */
public final class BridgeBuilder {
    private record Cell(BlockPos pos,int section){}
    private static ServerLevel level(MinecraftServer s,BridgeData d){return s.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation(d.dimension)));}
    private static void say(CommandSourceStack s,String text){s.sendSuccess(()->Component.literal(text),false);}
    public int pointA(CommandSourceStack s) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BridgeData d=BridgeData.get(s.getServer());
        if(d.active||d.undoing||(d.configured&&!d.finished)){s.sendFailure(Component.literal("Cancelá la obra pendiente antes de seleccionar otra; /constructor continuar conserva su avance."));return 0;}
        ServerPlayer p=s.getPlayerOrException();BlockPos a=p.blockPosition().below();
        d.ax=a.getX();d.ay=a.getY();d.az=a.getZ();d.dimension=p.level().dimension().location().toString();d.hasA=true;d.plan.clear();d.history.clear();d.configured=false;d.finished=false;d.cursor=0;d.changed=0;d.reason="";d.setDirty();
        say(s,"Punto A guardado. Andá al otro extremo y usá /constructor puntoB; la obra empezará automáticamente.");return 1;
    }
    public int pointB(CommandSourceStack s) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BridgeData d=BridgeData.get(s.getServer());ServerPlayer player=s.getPlayerOrException();
        if(!d.hasA){s.sendFailure(Component.literal("Primero usá /constructor puntoA sobre el piso del extremo inicial."));return 0;}
        if(d.active||d.undoing){s.sendFailure(Component.literal("Ya hay una obra activa."));return 0;}
        if(!d.dimension.equals(player.level().dimension().location().toString())){s.sendFailure(Component.literal("Los dos puntos deben estar en la misma dimensión."));return 0;}
        BlockPos b=player.blockPosition().below();int dx=b.getX()-d.ax,dz=b.getZ()-d.az,steps=Math.max(Math.abs(dx),Math.abs(dz));
        if(steps<2||steps>2048||Math.abs(b.getY()-d.ay)>steps){s.sendFailure(Component.literal("Distancia admitida: 2 a 2048 bloques; pendiente máxima: un bloque por tramo."));return 0;}
        d.bx=b.getX();d.by=b.getY();d.bz=b.getZ();
        ServerLevel world=level(s.getServer(),d);
        if(world==null)return 0;
        List<BlockPos> centers=new ArrayList<>();Map<Long,Cell> deck=new LinkedHashMap<>();boolean alongX=Math.abs(dx)>=Math.abs(dz);
        for(int i=0;i<=steps;i++){
            BlockPos c=new BlockPos(d.ax+(int)Math.round((double)dx*i/steps),d.ay+(int)Math.round((double)(d.by-d.ay)*i/steps),d.az+(int)Math.round((double)dz*i/steps));centers.add(c);
            for(int side=-3;side<=3;side++){
                BlockPos p=c.offset(alongX?0:side,0,alongX?side:0);
                deck.put(net.minecraft.core.BlockPos.asLong(p.getX(),0,p.getZ()),new Cell(p,i));
            }
        }
        for(Cell c:deck.values())if(c.pos.getY()<world.getMinBuildHeight()+1||c.pos.getY()+4>=world.getMaxBuildHeight()||!world.getWorldBorder().isWithinBounds(c.pos)){
            s.sendFailure(Component.literal("La obra sale de los límites del mundo."));return 0;
        }
        d.plan.clear();d.history.clear();d.cursor=0;d.changed=0;d.reason="";d.undoing=false;d.finished=false;
        // Full clearance first: later sections cannot erase railings or previously placed stairs.
        for(Cell cell:deck.values())for(int h=1;h<=4;h++)add(d,cell.pos.above(h),Blocks.AIR.defaultBlockState());
        Direction uphill=Direction.fromDelta(alongX?Integer.signum(dx):0,0,alongX?0:Integer.signum(dz));
        if(d.by<d.ay)uphill=uphill.getOpposite();
        for(Cell cell:deck.values()){
            BlockPos p=cell.pos,c=centers.get(cell.section);int side=alongX?p.getZ()-c.getZ():p.getX()-c.getX();
            BlockState floor=Blocks.REINFORCED_DEEPSLATE.defaultBlockState();
            boolean rise=cell.section<steps&&centers.get(cell.section+1).getY()>p.getY();
            boolean fall=cell.section>0&&centers.get(cell.section-1).getY()>p.getY();
            if(Math.abs(side)<3&&(rise||fall))floor=Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,uphill);
            else if(Math.abs(side)==2&&cell.section%4==0)floor=Blocks.SEA_LANTERN.defaultBlockState();
            add(d,p,floor);
        }
        for(Cell cell:deck.values()){
            BlockPos c=centers.get(cell.section);int side=alongX?cell.pos.getZ()-c.getZ():cell.pos.getX()-c.getX();
            if(Math.abs(side)==3)add(d,cell.pos.above(),Blocks.DEEPSLATE_BRICK_WALL.defaultBlockState());
        }
        for (int i = 15; i < steps; i += 15) {
            planPier(world, d, centers, i, alongX);
        }
        d.hasA=false;d.configured=true;d.active=true;d.setDirty();say(s,"Constructor: obra de "+(steps+1)+" tramos iniciada. Piso de 5 bloques útiles, luces laterales cada 4 y barandillas exteriores. /constructor estado muestra el avance.");return 1;
    }
    private static void planPier(ServerLevel world, BridgeData data, List<BlockPos> centers,
                                 int section, boolean alongX) {
        // The shaft is three sections long and three blocks wide. The two upper
        // courses flare out to meet the deck, following its grade and direction.
        for (int longitudinal = -3; longitudinal <= 3; longitudinal++) {
            int index = section + longitudinal;
            if (index < 0 || index >= centers.size()) continue;
            BlockPos center = centers.get(index);
            for (int side = -3; side <= 3; side++) {
                BlockPos top = center.offset(alongX ? 0 : side, 0, alongX ? side : 0);
                int depthLimit = Math.abs(longitudinal) <= 1 && Math.abs(side) <= 1 ? 64
                    : Math.abs(longitudinal) <= 2 && Math.abs(side) <= 2 ? 2 : 1;
                world.getChunk(top.getX() >> 4, top.getZ() >> 4);
                for (int depth = 1; depth <= depthLimit; depth++) {
                    BlockPos pos = top.below(depth);
                    if (pos.getY() <= world.getMinBuildHeight()) break;
                    BlockState old = world.getBlockState(pos);
                    if (world.getBlockEntity(pos) != null || old.getDestroySpeed(world, pos) < 0
                        || old.is(Blocks.NETHER_PORTAL) || old.is(Blocks.END_PORTAL)) break;
                    if (old.isCollisionShapeFullBlock(world, pos) && world.getFluidState(pos).isEmpty()
                        && !old.is(net.minecraft.tags.BlockTags.LEAVES)) break;
                    BlockState material = depth <= 2 || (longitudinal == 0 && Math.abs(side) == 1)
                        ? Blocks.REINFORCED_DEEPSLATE.defaultBlockState()
                        : Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                    add(data, pos, material);
                }
            }
        }
    }

    private static void add(BridgeData d,BlockPos p,BlockState state){CompoundTag n=new CompoundTag();n.putLong("pos",p.asLong());n.put("state",NbtUtils.writeBlockState(state));d.plan.add(n);}
    public void tick(MinecraftServer s){
        BridgeData d=BridgeData.get(s);if(!d.active)return;ServerLevel world=level(s,d);if(world==null)return;
        for(int i=0;i<8&&d.active;i++){
            if(d.undoing){
                if(d.history.isEmpty()){d.active=false;d.undoing=false;d.configured=false;d.plan.clear();d.cursor=0;d.setDirty();announce(s,"Constructor: deshacer completado.");break;}
                CompoundTag n=d.history.getCompound(d.history.size()-1);BlockPos p=BlockPos.of(n.getLong("pos"));world.getChunk(p.getX()>>4,p.getZ()>>4);
                BlockState before=NbtUtils.readBlockState(world.holderLookup(Registries.BLOCK),n.getCompound("before"));
                BlockState after=NbtUtils.readBlockState(world.holderLookup(Registries.BLOCK),n.getCompound("after"));
                if(world.getBlockEntity(p)!=null||!world.getBlockState(p).is(after.getBlock())){d.active=false;d.reason="Deshacer encontró un cambio posterior en "+p.toShortString();d.setDirty();announce(s,d.reason);break;}
                world.setBlock(p,before,3);d.history.remove(d.history.size()-1);d.setDirty();continue;
            }
            if(d.cursor>=d.plan.size()){d.active=false;d.finished=true;d.reason="";d.setDirty();announce(s,"Constructor: camino/puente terminado. "+d.changed+" cambios. /constructor deshacer permite revertirlo.");break;}
            CompoundTag n=d.plan.getCompound(d.cursor);BlockPos p=BlockPos.of(n.getLong("pos"));world.getChunk(p.getX()>>4,p.getZ()>>4);
            BlockState wanted=NbtUtils.readBlockState(world.holderLookup(Registries.BLOCK),n.getCompound("state")),old=world.getBlockState(p);
            if(old.equals(wanted)){d.cursor++;d.setDirty();continue;}
            if(world.getBlockEntity(p)!=null||old.getDestroySpeed(world,p)<0||old.is(Blocks.NETHER_PORTAL)||old.is(Blocks.END_PORTAL)){
                d.active=false;d.reason="Bloque protegido o contenedor en "+p.toShortString()+". Retiralo y usá /constructor continuar.";d.setDirty();announce(s,d.reason);break;
            }
            wanted=net.minecraft.world.level.block.Block.updateFromNeighbourShapes(wanted,world,p);
            if(!world.setBlock(p,wanted,3)){d.active=false;d.reason="No se pudo colocar en "+p.toShortString()+". Usá /constructor continuar.";d.setDirty();announce(s,d.reason);break;}
            CompoundTag undo=new CompoundTag();undo.putLong("pos",p.asLong());undo.put("before",NbtUtils.writeBlockState(old));undo.put("after",NbtUtils.writeBlockState(world.getBlockState(p)));d.history.add(undo);d.changed++;d.cursor++;d.setDirty();
        }
    }
    private static void announce(MinecraftServer s,String text){s.getPlayerList().broadcastSystemMessage(Component.literal(text),false);}
    public int status(CommandSourceStack s){BridgeData d=BridgeData.get(s.getServer());say(s,"Constructor: "+(d.undoing?"deshaciendo":d.active?"construyendo":d.finished?"terminado":d.configured?"pausado":d.hasA?"esperando Punto B":"sin obra")+" | "+d.cursor+"/"+d.plan.size()+" | cambios "+d.changed+(d.reason.isEmpty()?"":" | "+d.reason)+". Selección: /constructor puntoA y /constructor puntoB.");return 1;}
    public int pause(CommandSourceStack s){BridgeData d=BridgeData.get(s.getServer());d.active=false;d.setDirty();say(s,"Constructor pausado; progreso guardado.");return 1;}
    public int resume(CommandSourceStack s){BridgeData d=BridgeData.get(s.getServer());if(!d.configured||d.finished){s.sendFailure(Component.literal("No hay obra pendiente. Marcá Punto A y Punto B."));return 0;}d.active=true;d.reason="";d.setDirty();say(s,"Constructor continúa desde el último bloque pendiente.");return 1;}
    public int cancel(CommandSourceStack s){BridgeData d=BridgeData.get(s.getServer());d.active=false;d.undoing=false;d.configured=false;d.hasA=false;d.reason="";d.setDirty();say(s,"Obra cancelada; bloques conservados. Podés marcar otra obra o usar /constructor deshacer.");return 1;}
    public int undo(CommandSourceStack s){BridgeData d=BridgeData.get(s.getServer());if(d.history.isEmpty()){s.sendFailure(Component.literal("No hay cambios para deshacer."));return 0;}d.active=true;d.undoing=true;d.configured=true;d.finished=false;d.setDirty();say(s,"Deshaciendo la última obra.");return 1;}
    public int teleport(CommandSourceStack s) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BridgeData d=BridgeData.get(s.getServer());if(!d.configured&&!d.hasA){s.sendFailure(Component.literal("No hay obra seleccionada."));return 0;}
        ServerLevel w=level(s.getServer(),d);if(w==null)return 0;ServerPlayer p=s.getPlayerOrException();BlockPos at=d.cursor<d.plan.size()?BlockPos.of(d.plan.getCompound(d.cursor).getLong("pos")):new BlockPos(d.ax,d.ay,d.az);
        // Spawn above the current section; no moving bot is required for construction.
        w.getChunk(at.getX()>>4,at.getZ()>>4);int y=w.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ());
        p.teleportTo(w,at.getX()+.5,Math.max(at.getY()+2,y),at.getZ()+.5,p.getYRot(),p.getXRot());return 1;
    }
}
