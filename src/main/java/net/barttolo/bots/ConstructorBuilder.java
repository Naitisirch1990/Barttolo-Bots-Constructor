package net.barttolo.bots;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.commands.CommandSourceStack;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Visible, deliberate builder: stationary survey first, then grounded travel and work. */
public final class ConstructorBuilder {
    private static final int GRASS=0, TERRACE=1, FLOWER=2, SAPLING=3, LAMP=4,
        PATCH=5, FARM=6, POT=7, TORCH=8, LEAVES=9, FILL=10, WORKSITE=11, EDGE_FLOWER=12, GARDEN=13, CLEAR=14, FENCE=15, PATH=16, RAMP=17, RETAIN=18, LOOKOUT=19;
    private static final int SCAN_BATCH=48, LAMP_SPACING=8, OPEN_LAMP_SPACING=12, WORK_DELAY_TICKS=10;
    private static final double WALK_SPEED=.075;

    private record Placement(BlockPos pos,BlockState state,boolean force) {
        Placement(BlockPos pos,BlockState state){this(pos,state,false);}
    }
    private record LocalContext(int openLawn,int roadTiles,int buildingEdges,int flowers,int trees,int heightRange) {}
    private FakePlayer actor;
    private final Set<UUID> visible=new HashSet<>(),listed=new HashSet<>();
    private final ArrayDeque<Placement> pending=new ArrayDeque<>();
    private final Set<String> planned=new HashSet<>();
    private final ArrayList<BlockPos> route=new ArrayList<>();
    private int routeIndex,placementDelay,taskPlacedStart,taskPlacementFailures;
    private boolean preparing;
    private double visualX,visualY,visualZ;
    private boolean announced,visualInitialized,planLoaded,walking,movementPacketPending;

    public int create(CommandSourceStack source,int width){
        ConstructorData d=ConstructorData.get(source.getServer());
        d.dimension=source.getLevel().dimension().location().toString();
        d.centerX=(int)Math.floor(source.getPosition().x);d.centerY=(int)Math.floor(source.getPosition().y);d.centerZ=(int)Math.floor(source.getPosition().z);
        d.chestCursor=0;d.chestsFound=0;d.chestsConverted=0;d.chestsFailed=0;d.chestsBoundary=0;d.chestsDone=false;d.chestsPaused=false;
        resetReport(d);d.doors.clear();d.roofs.clear();d.villageReport="pendiente";
        d.width=width;d.cursor=0;d.taskCursor=0;d.placed=0;d.skipped=0;d.holesFound=0;d.roadsFound=0;d.stoneFound=0;d.configured=true;d.active=false;d.scanning=true;d.finished=false;d.auditOnly=false;d.tasks.clear();
        ServerLevel level=source.getLevel();BlockPos start=findSafeStart(level,d);
        if(start==null){d.configured=false;source.sendFailure(Component.literal("No encontré suelo natural abierto en esta zona para ubicar al Constructor. Probá desde un punto de terreno despejado."));return 0;}
        visualX=start.getX()+.5;visualY=start.getY()+1;visualZ=start.getZ()+.5;visualInitialized=true;
        pending.clear();route.clear();planned.clear();planLoaded=true;announced=false;walking=false;movementPacketPending=false;resetActor(source.getServer());d.setDirty();
        source.sendSuccess(()->Component.literal("Zona asignada "+width+"×"+width+". El Constructor escaneará quieto y después trabajará caminando por el suelo."),false);return 1;
    }
    public int start(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        if(!d.configured){source.sendFailure(Component.literal("Primero asigná una zona con /constructor crear [32..128]."));return 0;}
        if(d.finished){source.sendFailure(Component.literal("La zona ya está terminada. Asigná otra con /constructor crear."));return 0;}
        d.active=true;d.auditOnly=false;d.chestsPaused=false;d.setDirty();announced=false;
        source.sendSuccess(()->Component.literal(d.scanning?"Constructor quieto: empieza a analizar la zona.":"Constructor retoma el trabajo desde el suelo."),false);return 1;
    }
    /** Rebuilds a read-only task audit for the currently assigned square. */
    public int audit(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        if(!d.configured){source.sendFailure(Component.literal("Primero asigná la parcela con /constructor crear [32..128]."));return 0;}
        if(d.active){source.sendFailure(Component.literal("Pausá el Constructor antes de reiniciar la auditoría."));return 0;}
        resetReport(d);d.doors.clear();d.roofs.clear();d.villageReport="pendiente";
        d.active=true;d.scanning=true;d.finished=false;d.auditOnly=true;d.cursor=0;d.taskCursor=0;d.tasks.clear();
        d.placed=0;d.skipped=0;d.holesFound=0;d.roadsFound=0;d.stoneFound=0;d.setDirty();
        pending.clear();route.clear();planned.clear();planLoaded=true;walking=false;announced=false;
        source.sendSuccess(()->Component.literal("Auditoría iniciada: revisaré la superficie y las columnas vecinas en toda la parcela, sin cambiar bloques."),false);return 1;
    }
    public int pause(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.chestsPaused=true;d.setDirty();
        source.sendSuccess(()->Component.literal("Constructor pausado. La zona y el progreso quedaron guardados."),false);return 1;
    }
    public int cancel(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.configured=false;d.finished=false;d.setDirty();
        pending.clear();route.clear();planned.clear();planLoaded=false;d.auditOnly=false;resetActor(source.getServer());
        source.sendSuccess(()->Component.literal("Asignación cancelada. Los cambios ya hechos se conservan."),false);return 1;
    }
    public int status(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        String state=!d.configured?"sin zona":d.finished?"terminado":d.auditOnly&&!d.active&&!d.scanning?"auditoría lista":!d.active?"pausado":d.scanning?(d.auditOnly?"auditando superficie":"escaneando quieto"):"trabajando";
        int total=d.width*d.width;
        if(d.finished&&!d.lastReport.isEmpty()){source.sendSuccess(()->Component.literal(d.lastReport),false);return 1;}
        String progress=d.configured?(d.scanning?" | columnas "+Math.min(d.cursor,total)+"/"+total:" | tareas "+Math.min(d.taskCursor,d.tasks.size())+"/"+d.tasks.size())+" | aldea "+d.villageReport+" | huecos "+d.holesFound+" | camino "+d.roadsFound+" columnas | piedra aislada "+d.stoneFound+" | huertas "+taskCount(d,FARM)+" | jardines "+taskCount(d,GARDEN)+" | obras "+taskCount(d,WORKSITE)+" | tareas "+d.tasks.size()+" | bloques "+d.placed+" | omitidos "+d.skipped+" | Lootr "+d.chestsConverted+"/"+d.chestsFound+" | cofres fallidos "+d.chestsFailed+" | dobles en límite "+d.chestsBoundary+" | conversión "+(!ConstructorLootr.available()?"Lootr ausente":d.chestsDone?"lista":d.auditOnly||d.chestsPaused?"pausada":"en curso"):"";
        source.sendSuccess(()->Component.literal("Constructor "+state+progress+" | "+(d.configured?d.dimension+" centro "+d.centerX+" "+d.centerY+" "+d.centerZ+" | "+d.width+"×"+d.width:"")),false);return 1;
    }

    public void tick(MinecraftServer server){
        ConstructorData d=ConstructorData.get(server);if(!d.configured)return;
        if(!planLoaded)loadPlan(d);
        ServerLevel level=level(server,d.dimension);if(level==null)return;
        ConstructorLootr.tick(level,d);
        if(!visualInitialized){BlockPos start=findSafeStart(level,d);if(start==null)return;visualX=start.getX()+.5;visualY=start.getY()+1;visualZ=start.getZ()+.5;visualInitialized=true;}
        if(d.active){if(d.scanning)scanBatch(level,d);else workTick(server,level,d);}
        sync(server,level,d);
    }

    /** Survey does not move the visible bot. It only records safe outdoor candidates. */
    private void scanBatch(ServerLevel level,ConstructorData d){
        int total=d.width*d.width,stop=Math.min(total,d.cursor+SCAN_BATCH),minX=d.centerX-d.width/2,minZ=d.centerZ-d.width/2;
        while(d.cursor<stop){
            int row=d.cursor/d.width,col=d.cursor%d.width,x=minX+col,z=minZ+row;d.cursor++;
            level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
            surveyColumn(level,d,x,z);
            BlockPos exposed=rawSurface(level,x,z);if(exposed==null||!isOutdoorGround(level,exposed)||!level.getFluidState(exposed).isEmpty())continue;
            if(isDebris(level,exposed))addTask(d,CLEAR,exposed,0);
            int expectedY=holeTop(level,exposed);
            if(expectedY>exposed.getY()){addTask(d,FILL,exposed,expectedY);d.holesFound++;}
            if(isRoad(level,exposed)){d.roadsFound++;planRoadLamp(level,d,exposed);planRoadFlowers(level,d,exposed);}
            if(isIsolatedStone(level,exposed)){addTask(d,PATCH,exposed,0);d.stoneFound++;}
            BlockPos ground=surface(level,x,z);if(ground==null)continue;
            BlockState floor=level.getBlockState(ground);
            if(!isOutdoorGround(level,ground))continue;
            if(floor.is(Blocks.DIRT))addTask(d,GRASS,ground,0);
            if(canTerrace(level,ground))addTask(d,TERRACE,ground,0);

            planHouseDetails(level,d,ground);
            // Sample only some columns for macro sites; each candidate is scored from its local blocks.
            if(Math.floorMod(x+z-d.centerX-d.centerZ,3)==0)planContextualSite(level,d,ground);

            int rx=Math.floorMod(x-d.centerX,OPEN_LAMP_SPACING),rz=Math.floorMod(z-d.centerZ,OPEN_LAMP_SPACING);
            if(rx==0&&rz==0&&!hasRoadNear(level,x,z,4)){
                int arms=Math.floorMod((x-d.centerX+z-d.centerZ)/OPEN_LAMP_SPACING,2);
                planLampAt(level,d,ground,arms);
            }
            if(!floor.is(Blocks.GRASS_BLOCK)||!level.getFluidState(ground.above()).isEmpty())continue;
            int seed=Math.floorMod(x*73428767 ^ z*912931,1000);
            int distance=nearestHouseDistance(d,ground);int treeChance=distance>24?2:distance>14?4:7;int flowerChance=distance>24?4:distance>14?8:16;
            if(seed<treeChance&&clearForTree(level,ground)){
                addTask(d,SAPLING,ground,0);
            }else if(seed>=24&&seed<24+flowerChance&&clearForPlant(level,ground)){
                addTask(d,FLOWER,ground,0);
            }
        }
        d.setDirty();
        if(d.cursor>=total){planVillage(level,d);planLandscape(level,d);d.scanning=false;d.taskCursor=0;if(d.auditOnly){d.active=false;announce(level.getServer(),level,"Auditoría lista: "+d.holesFound+" huecos, "+d.roadsFound+" columnas de camino y "+d.stoneFound+" bloques de piedra aislados; "+d.tasks.size()+" acciones propuestas. No cambié bloques. Revisá /constructor estado y usa /constructor iniciar para ejecutar el plan.");}d.setDirty();}
    }

    private void planRoadLamp(ServerLevel level,ConstructorData d,BlockPos road){
        boolean alongX=isRoad(level,road.west())||isRoad(level,road.east());
        boolean alongZ=isRoad(level,road.north())||isRoad(level,road.south());
        if(!alongX&&!alongZ)return;
        int arms=alongX&&!alongZ?1:alongZ&&!alongX?0:Math.floorMod(road.getX()+road.getZ(),2);
        int[][] sides=alongX&&!alongZ?new int[][]{{0,1},{0,-1}}:alongZ&&!alongX?new int[][]{{1,0},{-1,0}}:new int[][]{{1,0},{0,1},{-1,0},{0,-1}};
        for(int[] side:sides){BlockPos base=road.offset(side[0]*2,0,side[1]*2);BlockPos actual=surface(level,base.getX(),base.getZ());if(actual!=null&&canBuildLamp(level,d,actual,arms)){addTask(d,LAMP,actual,arms);return;}}
    }
    private void planLampAt(ServerLevel level,ConstructorData d,BlockPos ground,int arms){
        if(canBuildLamp(level,d,ground,arms))addTask(d,LAMP,ground,arms);
    }
    private List<Placement> lampBlueprint(ServerLevel level,BlockPos base){
        List<Placement> blocks=new ArrayList<>();
        blocks.add(new Placement(base.above(),Blocks.POLISHED_ANDESITE.defaultBlockState()));
        blocks.add(new Placement(base.above(2),Blocks.DIORITE_WALL.defaultBlockState()));
        blocks.add(new Placement(base.above(3),villageFence(level,base).defaultBlockState()));
        blocks.add(new Placement(base.above(4),villageFence(level,base).defaultBlockState()));
        blocks.add(new Placement(base.above(5),Blocks.DIORITE_WALL.defaultBlockState()));
        blocks.add(new Placement(base.above(6),Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT,true)));
        blocks.add(new Placement(base.above(7),Blocks.REDSTONE_BLOCK.defaultBlockState()));
        for(Direction dir:Direction.Plane.HORIZONTAL)blocks.add(new Placement(base.above(7).relative(dir),Blocks.POLISHED_ANDESITE.defaultBlockState()));
        blocks.add(new Placement(base.above(8),Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        return blocks;
    }
    private boolean canBuildLamp(ServerLevel level,ConstructorData d,BlockPos base,int ignored){
        if(!inside(d,base)||!level.hasChunkAt(base)||!isOutdoorGround(level,base)||isRoad(level,base))return false;
        if(!(level.getBlockState(base).is(Blocks.GRASS_BLOCK)||level.getBlockState(base).is(Blocks.DIRT)))return false;
        for(Placement p:lampBlueprint(level,base))if(!inside(d,p.pos())||p.pos().getY()>=level.getMaxBuildHeight()||!level.hasChunkAt(p.pos())||!level.getBlockState(p.pos()).canBeReplaced())return false;
        return true;
    }
    private void addTask(ConstructorData d,int type,BlockPos p,int direction){
        String key=type+":"+p.getX()+":"+p.getY()+":"+p.getZ();
        if(!planned.add(key))return;
        if(type==FLOWER||type==EDGE_FLOWER||type==SAPLING||type==LAMP){int limit=type==SAPLING?Math.max(3,d.width*d.width/400):type==LAMP?Math.max(6,d.width*d.width/140):Math.max(12,d.width*d.width/90);int count=0;for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int tt=t.getInt("type");if(tt==type||(type==FLOWER||type==EDGE_FLOWER)&&(tt==FLOWER||tt==EDGE_FLOWER)){count++;int dx=t.getInt("x")-p.getX(),dz=t.getInt("z")-p.getZ();if(dx*dx+dz*dz<(type==SAPLING?49:4)){planned.remove(key);return;}}}if(count>=limit){planned.remove(key);return;}}

        if(remembered(d,type,p)){planned.remove(key);return;}
        if(type==LAMP){
            for(int i=0;i<d.tasks.size();i++){
                CompoundTag prior=d.task(i);if(prior.getInt("type")!=LAMP)continue;
                int dx=prior.getInt("x")-p.getX(),dz=prior.getInt("z")-p.getZ();if(dx*dx+dz*dz<LAMP_SPACING*LAMP_SPACING){planned.remove(key);return;}
            }
        }
        if(type==EDGE_FLOWER){
            for(int i=0;i<d.tasks.size();i++){
                CompoundTag prior=d.task(i);if(prior.getInt("type")!=EDGE_FLOWER||prior.getInt("direction")!=direction)continue;
                int dx=prior.getInt("x")-p.getX(),dz=prior.getInt("z")-p.getZ();if(dx*dx+dz*dz<9){planned.remove(key);return;}
            }
        }
        if(type==WORKSITE||type==GARDEN){
            int max=Math.max(1,d.width/64),count=0;
            for(int i=0;i<d.tasks.size();i++)if(d.task(i).getInt("type")==type){count++;int dx=d.task(i).getInt("x")-p.getX(),dz=d.task(i).getInt("z")-p.getZ();if(dx*dx+dz*dz<32*32){planned.remove(key);return;}}
            if(count>=max){planned.remove(key);return;}
        }
        d.addTask(type,p.getX(),p.getY(),p.getZ(),direction);
    }

    private void workTick(MinecraftServer server,ServerLevel level,ConstructorData d){
        if(!pending.isEmpty()){
            if(placementDelay>0){placementDelay--;return;}
            Placement p=pending.removeFirst();
            if(level.hasChunkAt(p.pos())&&(p.force()||level.getBlockState(p.pos()).canBeReplaced())){
                if(actor!=null){face(actor,p.pos().getX()+.5-visualX,p.pos().getZ()+.5-visualZ);actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(p.state().isAir()?net.minecraft.world.item.Items.IRON_SHOVEL:p.state().getBlock().asItem()));for(ServerPlayer viewer:server.getPlayerList().getPlayers())if(viewer.level()==level&&visible.contains(viewer.getUUID()))viewer.connection.send(new ClientboundSetEquipmentPacket(actor.getId(),List.of(com.mojang.datafixers.util.Pair.of(net.minecraft.world.entity.EquipmentSlot.MAINHAND,actor.getMainHandItem().copy()))));movementPacketPending=true;}
                if(level.setBlock(p.pos(),p.state(),3)){d.placed++;animate(server,level);}else{d.failedPlacements++;taskPlacementFailures++;}
            }else{d.skipped++;d.failedPlacements++;taskPlacementFailures++;}
            placementDelay=WORK_DELAY_TICKS;d.setDirty();if(pending.isEmpty())completeTask(d,false);return;
        }
        if(walking){
            walkOneTick(level);
            if(routeIndex>=route.size()){walking=false;prepareTask(level,d);}
            return;
        }
        if(d.taskCursor>=d.tasks.size()){
            if(!d.chestsDone)return;
            d.active=false;d.finished=true;d.setDirty();d.lastReport=completionReport(d);d.setDirty();announce(server,level,d.lastReport);return;
        }
        if(!beginNearestTask(level,d))return;
        if(!walking)prepareTask(level,d);
    }
    private boolean beginNearestTask(ServerLevel level,ConstructorData d){
        if(d.taskCursor>=d.tasks.size())return false;
        int start=d.taskCursor,best=-1,bestPriority=Integer.MAX_VALUE;double bestDistance=Double.MAX_VALUE;
        for(int i=start;i<d.tasks.size();i++){
            CompoundTag task=d.task(i);BlockPos approach=approachFor(level,d,task);if(approach==null)continue;
            double dx=approach.getX()+.5-visualX,dz=approach.getZ()+.5-visualZ;
            double distance=dx*dx+dz*dz;
            int priority=priority(task.getInt("type"));
            if(priority<bestPriority||(priority==bestPriority&&distance<bestDistance)){best=i;bestPriority=priority;bestDistance=distance;}
        }
        if(best<0){for(int j=d.taskCursor;j<d.tasks.size();j++){d.inaccessibleJobs++;deferJob(d,d.task(j),"sin punto de acceso transitable");}d.skipped+=d.tasks.size()-d.taskCursor;d.taskCursor=d.tasks.size();d.setDirty();return false;}
        if(best!=start){var a=d.tasks.get(start);var b=d.tasks.get(best);d.tasks.set(start,b);d.tasks.set(best,a);}
        CompoundTag task=d.task(d.taskCursor);BlockPos goal=approachFor(level,d,task);
        if(goal==null){d.inaccessibleJobs++;deferJob(d,task,"sin punto de acceso transitable");d.skipped++;d.taskCursor++;d.setDirty();return false;}
        BlockPos current=surface(level,(int)Math.floor(visualX),(int)Math.floor(visualZ));
        if(current==null){current=findSafeStart(level,d);if(current==null){d.inaccessibleJobs++;deferJob(d,task,"posición del Constructor sin suelo transitable");d.skipped++;d.taskCursor++;return false;}}
        List<BlockPos> found=findPath(level,d,current,goal);
        if(found==null){d.inaccessibleJobs++;deferJob(d,task,"sin ruta conectada por suelo");d.skipped++;d.taskCursor++;d.setDirty();return false;}
        route.clear();route.addAll(found);routeIndex=0;
        walking=!route.isEmpty();
        return true;
    }
    private void walkOneTick(ServerLevel level){
        if(routeIndex>=route.size())return;
        BlockPos next=route.get(routeIndex);double tx=next.getX()+.5,ty=next.getY()+1,tz=next.getZ()+.5;
        double dx=tx-visualX,dy=ty-visualY,dz=tz-visualZ,dist=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(dist<=WALK_SPEED){visualX=tx;visualY=ty;visualZ=tz;routeIndex++;if(actor!=null)face(actor,dx,dz);}
        else{double scale=WALK_SPEED/dist;visualX+=dx*scale;visualY+=dy*scale;visualZ+=dz*scale;if(actor!=null)face(actor,dx,dz);}
        movementPacketPending=true;if(actor!=null)actor.setPos(visualX,visualY,visualZ);
    }
    private void prepareTask(ServerLevel level,ConstructorData d){
        if(d.taskCursor>=d.tasks.size())return;
        CompoundTag task=d.task(d.taskCursor);int type=task.getInt("type"),x=task.getInt("x"),y=task.getInt("y"),z=task.getInt("z"),direction=task.getInt("direction");
        BlockPos ground=new BlockPos(x,y,z);
        if(!level.hasChunkAt(ground)||!isOutdoorGround(level,ground)){completeTask(d,true);return;}
        preparing=true;taskPlacedStart=d.placed;taskPlacementFailures=0;
        if(actor!=null)face(actor,x+.5-visualX,z+.5-visualZ);movementPacketPending=true;
        if(type==RAMP||type==RETAIN){if(validEarthColumn(level,d,ground,direction)){for(int rampY=ground.getY()+1;rampY<=direction;rampY++)pending.add(new Placement(new BlockPos(x,rampY,z),type==RETAIN?Blocks.COBBLESTONE.defaultBlockState():rampY==direction?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.DIRT.defaultBlockState()));}}
        else if(type==LOOKOUT){if(canLookout(level,d,ground))planLookout(level,ground,direction);}
        else if(type==CLEAR){if(isDebris(level,ground))pending.add(new Placement(ground,Blocks.AIR.defaultBlockState(),true));}
        else if(type==FENCE){if(canFence(level,d,ground))pending.add(new Placement(ground.above(),villageFence(level,ground).defaultBlockState()));}
        else if(type==PATH){if(canLink(level,d,ground))pending.add(new Placement(ground,Blocks.DIRT_PATH.defaultBlockState(),true));}
        else if(type==GRASS){if(level.getBlockState(ground).is(Blocks.DIRT))pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState(),true));}
        else if(type==TERRACE){if(canTerrace(level,ground))pending.add(new Placement(ground.above(),Blocks.GRASS_BLOCK.defaultBlockState()));}
        else if(type==FLOWER){if(clearForPlant(level,ground))pending.add(new Placement(ground.above(),flowerFor(x,z).defaultBlockState()));}
        else if(type==SAPLING){if(clearForTree(level,ground))pending.add(new Placement(ground.above(),saplingFor(level,ground).defaultBlockState()));}
        else if(type==PATCH){if(isIsolatedStone(level,ground))pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState(),true));}
        else if(type==FILL){int verified=holeTop(level,ground);if(verified==direction&&verified>ground.getY()){
            for(int fillY=ground.getY()+1;fillY<=verified;fillY++)pending.add(new Placement(new BlockPos(x,fillY,z),fillY==verified?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.DIRT.defaultBlockState(),true));
        }}
        else if(type==EDGE_FLOWER){if(canEdgeFlower(level,ground))pending.add(new Placement(ground.above(),flowerFor(x,z).defaultBlockState()));}
        else if(type==WORKSITE){if(canWorksite(level,ground,d))planWorksite(level,ground);}
        else if(type==GARDEN){if(canGarden(level,ground,d))planGarden(level,ground);}
        else if(type==FARM){if(canFarm(level,ground,d))planFarm(ground);}
        else if(type==POT){if(canPlacePot(level,ground))pending.add(new Placement(ground.above(),Blocks.POTTED_POPPY.defaultBlockState()));}
        else if(type==TORCH){if(canPlaceTorch(level,ground))pending.add(new Placement(ground.above(),Blocks.TORCH.defaultBlockState()));}
        else if(type==LEAVES){if(canPlaceLeaves(level,ground)){
            pending.add(new Placement(ground.above(),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true)));
            if(level.getBlockState(ground.above(2)).isAir())pending.add(new Placement(ground.above(2),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true)));
        }}
        else if(type==LAMP){
            if(!canBuildLamp(level,d,ground,direction)){completeTask(d,true);return;}
            planLampBlocks(level,ground,direction);
        }
        if(type==GARDEN||type==WORKSITE)orientSite(level,ground);
        preparing=false;
        if(pending.isEmpty())completeTask(d,true);
    }
    private void planLampBlocks(ServerLevel level,BlockPos base,int ignored){pending.addAll(lampBlueprint(level,base));}
    private void planFarm(BlockPos ground){
        pending.add(new Placement(ground,Blocks.WATER.defaultBlockState(),true));
        Block crop=switch(Math.floorMod(ground.getX()*31+ground.getZ()*17,4)){
            case 0->Blocks.WHEAT;case 1->Blocks.CARROTS;case 2->Blocks.POTATOES;default->Blocks.BEETROOTS;
        };
        BlockState cropState=crop.defaultBlockState();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(dx==0&&dz==0)continue;
            BlockPos soil=ground.offset(dx,0,dz);
            pending.add(new Placement(soil,Blocks.FARMLAND.defaultBlockState(),true));
            pending.add(new Placement(soil.above(),cropState,true));
        }
    }
    private void planRoadFlowers(ServerLevel level,ConstructorData d,BlockPos road){
        boolean alongX=isRoad(level,road.west())||isRoad(level,road.east());
        boolean alongZ=isRoad(level,road.north())||isRoad(level,road.south());
        if(alongX==alongZ)return; // Skip isolated tiles and intersections; keep borders aligned to a segment.
        int[][] sides=alongX?new int[][]{{0,-1},{0,1}}:new int[][]{{-1,0},{1,0}};
        for(int[] side:sides){
            BlockPos edge=surface(level,road.getX()+side[0],road.getZ()+side[1]);if(edge==null)continue;
            if(!canEdgeFlower(level,edge))continue;
            int facing=side[0]<0?0:side[0]>0?1:side[1]<0?2:3;
            addTask(d,EDGE_FLOWER,edge,facing);
        }
    }
    private boolean canEdgeFlower(ServerLevel level,BlockPos ground){
        if(!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)||!isOutdoorGround(level,ground)||!level.getBlockState(ground.above()).isAir())return false;
        if(isRoad(level,ground)||isNearBuilding(level,ground))return false;
        return isRoad(level,ground.north())||isRoad(level,ground.south())||isRoad(level,ground.east())||isRoad(level,ground.west());
    }
    /** Score nearby use cases from the blocks already present; this is a small local planner, not a remote model. */
    private void planContextualSite(ServerLevel level,ConstructorData d,BlockPos ground){
        if(!level.getBlockState(ground).is(Blocks.GRASS_BLOCK))return;
        LocalContext c=inspectLocalContext(level,ground);
        if(c.openLawn()<17||c.heightRange()>1)return;
        int bestScore=29,bestType=-1;
        int siteLimit=Math.max(1,d.width/64);
        if(d.width>=64&&taskCount(d,WORKSITE)<siteLimit&&canWorksite(level,ground,d)){
            int score=38+c.openLawn()-8*c.roadTiles()-5*c.buildingEdges()-2*c.flowers()-2*c.trees()-6*c.heightRange();
            if(score>bestScore){bestScore=score;bestType=WORKSITE;}
        }
        if(taskCount(d,GARDEN)<siteLimit&&canGarden(level,ground,d)){
            int score=32+c.openLawn()+Math.min(4,c.buildingEdges())*2-10*c.roadTiles()-3*c.flowers()-c.trees()-6*c.heightRange();
            if(score>bestScore){bestScore=score;bestType=GARDEN;}
        }
        if(taskCount(d,FARM)<siteLimit&&canFarm(level,ground,d)){
            int score=27+c.openLawn()-12*c.roadTiles()-5*c.buildingEdges()-2*c.trees()-7*c.heightRange();
            if(score>bestScore){bestScore=score;bestType=FARM;}
        }
        if(bestType>=0)addTask(d,bestType,ground,0);
    }
    private int taskCount(ConstructorData d,int type){
        int count=0;for(int i=0;i<d.tasks.size();i++)if(!(preparing&&i==d.taskCursor)&&d.task(i).getInt("type")==type)count++;return count;
    }
    private LocalContext inspectLocalContext(ServerLevel level,BlockPos center){
        int open=0,roads=0,walls=0,flowers=0,trees=0,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos p=surface(level,center.getX()+dx,center.getZ()+dz);if(p==null)continue;
            minY=Math.min(minY,p.getY());maxY=Math.max(maxY,p.getY());
            if(isRoad(level,p))roads++;
            BlockState floor=level.getBlockState(p),above=level.getBlockState(p.above());
            if(floor.is(Blocks.GRASS_BLOCK)&&above.isAir()&&level.getBlockState(p.above(2)).isAir())open++;
            if(above.getBlock() instanceof FlowerBlock||above.is(Blocks.SWEET_BERRY_BUSH))flowers++;
            if(above.is(BlockTags.LOGS)||above.is(BlockTags.LEAVES)||level.getBlockState(p.above(2)).is(BlockTags.LOGS)||level.getBlockState(p.above(2)).is(BlockTags.LEAVES))trees++;
            for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos side=p.relative(dir).above();BlockState wall=level.getBlockState(side);
                if(wall.getBlock() instanceof DoorBlock||wall.isCollisionShapeFullBlock(level,side))walls++;}
        }
        int roughness=minY==Integer.MAX_VALUE?4:maxY-minY;
        return new LocalContext(open,roads,walls,flowers,trees,roughness);
    }
    private boolean canGarden(ServerLevel level,BlockPos center,ConstructorData d){
        if(!level.getBlockState(center).is(Blocks.GRASS_BLOCK)||!inside(d,center))return false;
        if(Math.abs(center.getX()-d.centerX)>d.width/4||Math.abs(center.getZ()-d.centerZ)>d.width/4)return false;
        if(hasRoadNear(level,center.getX(),center.getZ(),2))return false;
        for(int i=0;i<d.tasks.size();i++){
            if(preparing&&i==d.taskCursor)continue;CompoundTag t=d.task(i);int type=t.getInt("type");
            if(type==FARM||type==WORKSITE||type==GARDEN){int dx=t.getInt("x")-center.getX(),dz=t.getInt("z")-center.getZ();if(dx*dx+dz*dz<100)return false;}
        }
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos p=center.offset(dx,0,dz),top=surface(level,p.getX(),p.getZ());
            if(!inside(d,p)||top==null||top.getY()!=center.getY()||!level.getBlockState(top).is(Blocks.GRASS_BLOCK)
                ||!isOutdoorGround(level,top)||!level.getBlockState(top.above()).isAir()||isRoad(level,top)||isNearBuilding(level,top))return false;
        }
        return true;
    }
    private void planGarden(ServerLevel level,BlockPos c){
        int[][] path={{0,-2},{0,-1},{1,0},{0,1},{0,2}};
        for(int i=0;i<path.length;i++){
            Block block=(i==2)?Blocks.GRAVEL:Blocks.DIRT_PATH;
            pending.add(new Placement(c.offset(path[i][0],0,path[i][1]),block.defaultBlockState(),true));
        }
        for(int[] p:new int[][]{{-1,-2},{1,-2},{-1,0},{2,0},{-1,2},{1,2}})
            pending.add(new Placement(c.offset(p[0],0,p[1]),Blocks.STONE_BRICK_SLAB.defaultBlockState(),true));
        for(int[] p:new int[][]{{-2,-2},{-2,-1},{-2,0},{-2,1},{-2,2},{2,-2},{2,-1},{2,1},{2,2},{-1,1},{1,-1}})
            pending.add(new Placement(c.offset(p[0],1,p[1]),flowerFor(p[0]*7+p[1],p[1]*13).defaultBlockState()));
        for(int[] p:new int[][]{{-3,0},{3,0},{0,-3},{0,3}})
            pending.add(new Placement(c.offset(p[0],1,p[1]),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true)));
        pending.add(new Placement(c.offset(-1,1,-1),villageFence(level,c).defaultBlockState()));
        pending.add(new Placement(c.offset(-1,2,-1),Blocks.LANTERN.defaultBlockState()));
        pending.add(new Placement(c.offset(1,1,1),villageWood(level,c,"stairs").defaultBlockState()));
    }
    private boolean canWorksite(ServerLevel level,BlockPos center,ConstructorData d){
        if(d.width<64||Math.abs(center.getX()-d.centerX)>d.width/4||Math.abs(center.getZ()-d.centerZ)>d.width/4)return false;
        if(!level.getBlockState(center).is(Blocks.GRASS_BLOCK)||!inside(d,center))return false;
        int max=Math.max(1,d.width/64),count=0;
        if(hasRoadNear(level,center.getX(),center.getZ(),3))return false;
        for(int i=0;i<d.tasks.size();i++){
            if(preparing&&i==d.taskCursor)continue;CompoundTag task=d.task(i);int type=task.getInt("type");
            if(type==WORKSITE)count++;
            if(type==FARM||type==GARDEN||type==SAPLING||type==FLOWER||type==EDGE_FLOWER||type==LAMP||type==POT||type==TORCH||type==LEAVES){
                int dx=task.getInt("x")-center.getX(),dz=task.getInt("z")-center.getZ();if(dx*dx+dz*dz<36)return false;
            }
        }
        if(count>=max)return false;
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos p=center.offset(dx,0,dz),top=surface(level,p.getX(),p.getZ());
            if(!inside(d,p)||top==null||top.getY()!=center.getY()||!level.getBlockState(top).is(Blocks.GRASS_BLOCK))return false;
            if(isRoad(level,top)||isNearBuilding(level,top))return false;
            if(!level.getBlockState(top.above()).isAir()||!level.getBlockState(top.above(2)).isAir())return false;
        }
        return true;
    }
    /** A compact open-air carpentry and forge corner inspired by the supplied village decor. */
    private void planWorksite(ServerLevel level,BlockPos c){
        BlockState stone=Blocks.STONE_BRICKS.defaultBlockState(),weathered=Blocks.COBBLESTONE.defaultBlockState();
        BlockState spruce=villageWood(level,c,"log").defaultBlockState(),slab=villageWood(level,c,"slab").defaultBlockState();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            BlockState tile=(Math.abs(dx)+Math.abs(dz))%2==0?stone:weathered;
            pending.add(new Placement(c.offset(dx,0,dz),tile,true));
        }
        for(int dx:new int[]{-2,2})for(int dz:new int[]{-2,2})for(int y=1;y<=3;y++)
            pending.add(new Placement(c.offset(dx,y,dz),spruce));
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(Math.abs(dx)==2||Math.abs(dz)==2)
            pending.add(new Placement(c.offset(dx,4,dz),slab));
        pending.add(new Placement(c.offset(0,1,0),Blocks.CAMPFIRE.defaultBlockState()));
        pending.add(new Placement(c.offset(1,1,0),Blocks.BLAST_FURNACE.defaultBlockState()));
        pending.add(new Placement(c.offset(-1,1,0),Blocks.CRAFTING_TABLE.defaultBlockState()));
        pending.add(new Placement(c.offset(0,1,1),Blocks.ANVIL.defaultBlockState()));
        pending.add(new Placement(c.offset(0,1,-1),Blocks.BARREL.defaultBlockState()));
        pending.add(new Placement(c.offset(3,0,-1),Blocks.STONE_BRICKS.defaultBlockState(),true));
        for(int y=1;y<=3;y++)pending.add(new Placement(c.offset(3,y,-1),stone));
        pending.add(new Placement(c.offset(3,4,-1),Blocks.CAMPFIRE.defaultBlockState()));
        pending.add(new Placement(c.offset(-3,0,1),villageWood(level,c,"planks").defaultBlockState(),true));
        pending.add(new Placement(c.offset(-3,0,2),villageWood(level,c,"planks").defaultBlockState(),true));
        pending.add(new Placement(c.offset(-3,1,1),spruce));
        pending.add(new Placement(c.offset(-3,1,2),spruce));
        pending.add(new Placement(c.offset(1,0,3),Blocks.SCAFFOLDING.defaultBlockState(),true));
        pending.add(new Placement(c.offset(1,1,3),Blocks.SCAFFOLDING.defaultBlockState()));
        pending.add(new Placement(c.offset(2,0,3),Blocks.BARREL.defaultBlockState(),true));
    }
    private void completeTask(ConstructorData d,boolean skipped){preparing=false;if(d.taskCursor<d.tasks.size()){CompoundTag job=d.task(d.taskCursor);if(skipped){d.invalidJobs++;deferJob(d,job,"sitio cambió o dejó de cumplir condiciones");}else if(taskPlacementFailures>0){d.partialJobs++;deferJob(d,job,"colocación parcial: bloques ocupados o rechazados");}else if(d.placed>taskPlacedStart){String key=Integer.toString(job.getInt("type"));d.completed.putInt(key,d.completed.getInt(key)+1);}}if(!skipped&&taskPlacementFailures==0&&d.placed>taskPlacedStart&&d.taskCursor<d.tasks.size()){CompoundTag t=d.task(d.taskCursor);int type=t.getInt("type");if(type==LAMP||type==FARM||type==WORKSITE||type==GARDEN||type==LOOKOUT||type==FENCE){CompoundTag m=t.copy();m.putString("dimension",d.dimension);d.memory.add(m);}}if(skipped)d.skipped++;d.taskCursor++;placementDelay=0;pending.clear();route.clear();routeIndex=0;walking=false;d.setDirty();}

    private List<BlockPos> findPath(ServerLevel level,ConstructorData d,BlockPos start,BlockPos goal){
        int minX=d.centerX-d.width/2,minZ=d.centerZ-d.width/2,maxX=minX+d.width-1,maxZ=minZ+d.width-1;
        long startKey=key(start),goalKey=key(goal);if(startKey==goalKey)return List.of();
        ArrayDeque<BlockPos> open=new ArrayDeque<>();Map<Long,Long> parent=new HashMap<>();Map<Long,BlockPos> nodes=new HashMap<>();
        open.add(start);parent.put(startKey,startKey);nodes.put(startKey,start);
        int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
        while(!open.isEmpty()){
            BlockPos at=open.removeFirst();long atKey=key(at);
            for(int[] dir:dirs){
                int x=at.getX()+dir[0],z=at.getZ()+dir[1];if(x<minX||x>maxX||z<minZ||z>maxZ)continue;
                long k=key(x,z);if(parent.containsKey(k))continue;
                BlockPos next=surface(level,x,z);if(next==null||Math.abs(next.getY()-at.getY())>1)continue;
                parent.put(k,atKey);nodes.put(k,next);
                if(k==goalKey){ArrayList<BlockPos> result=new ArrayList<>();long cursor=k;while(cursor!=startKey){result.add(nodes.get(cursor));cursor=parent.get(cursor);}Collections.reverse(result);return result;}
                open.addLast(next);
            }
        }return null;
    }
    private BlockPos approachFor(ServerLevel level,ConstructorData d,CompoundTag task){
        BlockPos target=new BlockPos(task.getInt("x"),task.getInt("y"),task.getInt("z"));
        int type=task.getInt("type"),direction=task.getInt("direction");
        int[][] dirs=(type==RAMP||type==RETAIN)?new int[][]{{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}}:type==LAMP?new int[][]{{0,2},{0,-2},{2,0},{-2,0},{0,3},{0,-3},{3,0},{-3,0}}:new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
        BlockPos best=null;double bestDistance=Double.MAX_VALUE;
        for(int[] dir:dirs){BlockPos p=surface(level,target.getX()+dir[0],target.getZ()+dir[1]);if(p==null)continue;
            double dx=p.getX()+.5-visualX,dz=p.getZ()+.5-visualZ,dist=dx*dx+dz*dz;
            if(dist<bestDistance){best=p;bestDistance=dist;}}
        return best;
    }

    private BlockPos surface(ServerLevel level,int x,int z){
        BlockPos probe=new BlockPos(x,level.getMinBuildHeight(),z);if(!level.hasChunkAt(probe))return null;
        BlockPos ground=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,probe).below();
        return isWalkableGround(level,ground)?ground:null;
    }
    private BlockPos findSafeStart(ServerLevel level,ConstructorData d){
        for(int radius=0;radius<=8;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            if(Math.abs(dx)+Math.abs(dz)!=radius)continue;
            int x=d.centerX+dx,z=d.centerZ+dz;
            if(x<d.centerX-d.width/2||x>=d.centerX+(d.width+1)/2||z<d.centerZ-d.width/2||z>=d.centerZ+(d.width+1)/2)continue;
            BlockPos p=surface(level,x,z);if(p!=null)return p;
        }return null;
    }
    private boolean isWalkableGround(ServerLevel level,BlockPos ground){
        if(!isOutdoorGround(level,ground)||!level.getFluidState(ground.above()).isEmpty())return false;
        BlockState floor=level.getBlockState(ground);
        return floor.is(Blocks.GRASS_BLOCK)||floor.is(Blocks.DIRT)||floor.is(Blocks.COARSE_DIRT)||floor.is(Blocks.PODZOL)
            ||floor.is(Blocks.ROOTED_DIRT)||floor.is(Blocks.MYCELIUM)||floor.is(Blocks.DIRT_PATH)||floor.is(Blocks.GRAVEL)
            ||floor.is(Blocks.MOSS_BLOCK)||floor.is(Blocks.SAND)||floor.is(Blocks.RED_SAND);
    }
    private boolean isOutdoorGround(ServerLevel level,BlockPos ground){
        return level.canSeeSky(ground.above())&&level.getBlockState(ground.above()).isAir()&&level.getBlockState(ground.above(2)).isAir();
    }
    private boolean canTerrace(ServerLevel level,BlockPos ground){
        BlockState floor=level.getBlockState(ground);if(!(floor.is(Blocks.GRASS_BLOCK)||floor.is(Blocks.DIRT)))return false;
        if(!level.getBlockState(ground.above()).isAir()||!level.getFluidState(ground.above()).isEmpty())return false;
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos neighbor=surface(level,ground.getX()+dir[0],ground.getZ()+dir[1]);
            if(neighbor!=null&&neighbor.getY()-ground.getY()>=1&&neighbor.getY()-ground.getY()<=3&&level.getBlockState(neighbor).is(Blocks.GRASS_BLOCK))return true;
        }return false;
    }
    /** Returns the matching lawn height around a shallow hole, or the ground Y if none is found. */
    private int holeTop(ServerLevel level,BlockPos floor){
        BlockState base=level.getBlockState(floor);
        if(!(base.is(Blocks.GRASS_BLOCK)||base.is(Blocks.DIRT)||base.is(Blocks.COARSE_DIRT)||base.is(Blocks.GRAVEL)
            ||base.is(Blocks.STONE)||base.is(Blocks.COBBLESTONE)||base.is(Blocks.ANDESITE)))return floor.getY();
        for(int y=1;y<=3;y++)if(!level.getBlockState(floor.above(y)).isAir()||!level.getFluidState(floor.above(y)).isEmpty())return floor.getY();
        Map<Integer,Integer> heights=new HashMap<>();
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos n=rawSurface(level,floor.getX()+dir[0],floor.getZ()+dir[1]);if(n==null)continue;
            BlockState state=level.getBlockState(n);
            if(state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT_PATH)||state.is(Blocks.GRAVEL))heights.merge(n.getY(),1,Integer::sum);
        }
        return heights.entrySet().stream().filter(e->e.getValue()>=3&&e.getKey()>floor.getY()&&e.getKey()-floor.getY()<=3)
            .mapToInt(Map.Entry::getKey).findFirst().orElse(floor.getY());
    }
    private BlockPos rawSurface(ServerLevel level,int x,int z){
        BlockPos probe=new BlockPos(x,level.getMinBuildHeight(),z);if(!level.hasChunkAt(probe))return null;
        BlockPos top=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,probe).below();
        return level.getBlockState(top).isAir()?null:top;
    }
    private boolean isIsolatedStone(ServerLevel level,BlockPos p){
        BlockState state=level.getBlockState(p);
        if(!(state.is(Blocks.STONE)||state.is(Blocks.COBBLESTONE)||state.is(Blocks.ANDESITE)||state.is(Blocks.DIORITE)||state.is(Blocks.GRANITE)))return false;
        if(level.getBlockEntity(p)!=null||!level.getBlockState(p.above()).isAir())return false;
        int lawn=0;
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos n=rawSurface(level,p.getX()+dir[0],p.getZ()+dir[1]);
            if(n!=null&&n.getY()==p.getY()&&level.getBlockState(n).is(Blocks.GRASS_BLOCK))lawn++;
        }
        return lawn>=3;
    }
    private void planHouseDetails(ServerLevel level,ConstructorData d,BlockPos ground){
        if(!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)||!isOutdoorGround(level,ground))return;
        // House corner greenery: a lawn tile beside two solid sides, including a log corner.
        if(canPlaceLeaves(level,ground))addTask(d,LEAVES,ground,0);
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos scan=ground.offset(dx,0,dz);
            for(int dy=1;dy<=3;dy++){
                BlockPos doorPos=scan.above(dy);BlockState door=level.getBlockState(doorPos);
                if(!(door.getBlock() instanceof DoorBlock)||door.getValue(DoorBlock.HALF)!=DoubleBlockHalf.LOWER)continue;
                Direction facing=door.getValue(DoorBlock.FACING),side=facing.getClockWise();
                for(int sign:new int[]{-1,1}){
                    BlockPos sideGround=new BlockPos(doorPos.getX()+side.getStepX()*sign,ground.getY(),doorPos.getZ()+side.getStepZ()*sign);
                    BlockPos top=surface(level,sideGround.getX(),sideGround.getZ());if(top==null||top.getY()!=ground.getY())continue;
                    if(!level.getBlockState(top).is(Blocks.GRASS_BLOCK)||isRoad(level,top)||hasRoadNear(level,top.getX(),top.getZ(),1))continue;
                    if(canPlacePot(level,top))addTask(d,POT,top,0);
                    if(level.getMaxLocalRawBrightness(top.above())<8&&canPlaceTorch(level,top))addTask(d,TORCH,top,0);
                }
            }
        }
    }
    private boolean canPlacePot(ServerLevel level,BlockPos ground){
        if(!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)||!isOutdoorGround(level,ground)||!level.getBlockState(ground.above()).canBeReplaced())return false;
        if(isRoad(level,ground)||hasRoadNear(level,ground.getX(),ground.getZ(),1))return false;
        // Keep the planter snug to a building wall, but never in the doorway's forward lane.
        for(Direction dir:Direction.Plane.HORIZONTAL){BlockState neighbor=level.getBlockState(ground.relative(dir).above());
            if(!neighbor.isAir()&&neighbor.isCollisionShapeFullBlock(level,ground.relative(dir).above()))return true;}
        return false;
    }
    private boolean canPlaceTorch(ServerLevel level,BlockPos ground){
        return level.getBlockState(ground).is(Blocks.GRASS_BLOCK)&&isOutdoorGround(level,ground)
            &&level.getBlockState(ground.above()).canBeReplaced()&&!isRoad(level,ground)&&!hasRoadNear(level,ground.getX(),ground.getZ(),1);
    }
    private boolean canPlaceLeaves(ServerLevel level,BlockPos ground){
        if(!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)||!isOutdoorGround(level,ground)
            ||!level.getBlockState(ground.above()).canBeReplaced()||isRoad(level,ground)||hasRoadNear(level,ground.getX(),ground.getZ(),1))return false;
        int walls=0;boolean log=false;
        for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos side=ground.relative(dir).above();BlockState s=level.getBlockState(side);
            if(s.isCollisionShapeFullBlock(level,side)){walls++;if(s.is(Blocks.OAK_LOG)||s.is(Blocks.SPRUCE_LOG)||s.is(Blocks.BIRCH_LOG)||s.is(Blocks.DARK_OAK_LOG)||s.is(Blocks.ACACIA_LOG))log=true;}}
        return walls>=2&&log;
    }
    private boolean canFarm(ServerLevel level,BlockPos center,ConstructorData d){
        if(!level.getBlockState(center).is(Blocks.GRASS_BLOCK)||!isOutdoorGround(level,center)||!insideFarmMargin(center,d))return false;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos p=center.offset(dx,0,dz),top=surface(level,p.getX(),p.getZ());
            if(top==null||top.getY()!=center.getY()||!level.getBlockState(top).is(Blocks.GRASS_BLOCK)||!level.getBlockState(top.above()).isAir())return false;
            if(isRoad(level,top)||hasRoadNear(level,top.getX(),top.getZ(),1))return false;
            if(dx*dx+dz*dz>=4&&isNearBuilding(level,top))return false;
        }
        return true;
    }
    private boolean insideFarmMargin(BlockPos p,ConstructorData d){
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(!inside(d,p.offset(dx,0,dz)))return false;
        for(int i=0;i<d.tasks.size();i++)if(!(preparing&&i==d.taskCursor)&&d.task(i).getInt("type")==FARM){
            int dx=d.task(i).getInt("x")-p.getX(),dz=d.task(i).getInt("z")-p.getZ();if(dx*dx+dz*dz<100)return false;
        }
        int farmCount=taskCount(d,FARM);
        return farmCount<Math.max(1,d.width/64);
    }
    private boolean isNearBuilding(ServerLevel level,BlockPos p){
        for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos q=p.relative(dir).above();BlockState s=level.getBlockState(q);
            if(s.isCollisionShapeFullBlock(level,q)&&!s.is(Blocks.GRASS_BLOCK)&&!s.is(Blocks.DIRT))return true;}
        return false;
    }
    private boolean clearForPlant(ServerLevel level,BlockPos ground){
        if(!isOutdoorGround(level,ground)||!level.getBlockState(ground.above()).isAir())return false;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(isRoad(level,ground.offset(dx,0,dz)))return false;
        return true;
    }
    private boolean clearForTree(ServerLevel level,BlockPos ground){
        if(!clearForPlant(level,ground)||!level.getBlockState(ground.above(2)).isAir())return false;
        for(BlockPos p:BlockPos.betweenClosed(ground.offset(-4,0,-4),ground.offset(4,5,4))){
            Block b=level.getBlockState(p).getBlock();if(b instanceof SaplingBlock||b==Blocks.OAK_LOG||b==Blocks.SPRUCE_LOG||b==Blocks.BIRCH_LOG||b==Blocks.OAK_LEAVES||b==Blocks.SPRUCE_LEAVES||b==Blocks.BIRCH_LEAVES)return false;
        }return true;
    }
    private boolean isRoad(ServerLevel level,BlockPos p){
        BlockPos top=rawSurface(level,p.getX(),p.getZ());if(top==null||!isOutdoorGround(level,top))return false;
        BlockState s=level.getBlockState(top);if(s.is(Blocks.DIRT_PATH))return true;
        if(!isRoadMaterial(s))return false;
        int connected=0;boolean east=false,west=false,north=false,south=false;
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos n=rawSurface(level,p.getX()+dir[0],p.getZ()+dir[1]);
            if(n!=null&&Math.abs(n.getY()-top.getY())<=1&&isRoadMaterial(level.getBlockState(n))){
                connected++;if(dir[0]==1)east=true;else if(dir[0]==-1)west=true;else if(dir[1]==1)south=true;else north=true;
            }
        }
        return (east&&west)||(north&&south)||connected>=3;
    }
    private boolean isRoadMaterial(BlockState s){
        return s.is(Blocks.GRAVEL)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.ROOTED_DIRT)||s.is(Blocks.PACKED_MUD)
            ||s.is(Blocks.MUD_BRICKS)||s.is(Blocks.COBBLESTONE)||s.is(Blocks.MOSSY_COBBLESTONE)
            ||s.is(Blocks.STONE_BRICKS)||s.is(Blocks.MOSSY_STONE_BRICKS)||s.is(Blocks.ANDESITE)
            ||s.is(Blocks.POLISHED_ANDESITE)||s.is(Blocks.DIRT_PATH);
    }
    private boolean hasRoadNear(ServerLevel level,int x,int z,int radius){
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)if(isRoad(level,new BlockPos(x+dx,level.getMinBuildHeight(),z+dz)))return true;
        return false;
    }
    private boolean inside(ConstructorData d,BlockPos p){
        int minX=d.centerX-d.width/2,minZ=d.centerZ-d.width/2;
        return p.getX()>=minX&&p.getX()<minX+d.width&&p.getZ()>=minZ&&p.getZ()<minZ+d.width;
    }
    private Block saplingFor(ServerLevel level,BlockPos ground){
        String biome=level.getBiome(ground).unwrapKey().map(k->k.location().getPath()).orElse("");
        if(biome.contains("taiga")||biome.contains("pine")||biome.contains("grove"))return Blocks.SPRUCE_SAPLING;
        if(biome.contains("birch"))return Blocks.BIRCH_SAPLING;return Blocks.OAK_SAPLING;
    }
    private Block flowerFor(int x,int z){
        int n=Math.floorMod(x*19+z*73,5);
        if(n==0)return Blocks.POPPY;if(n==1)return Blocks.DANDELION;if(n==2)return Blocks.AZURE_BLUET;
        if(n==3)return Blocks.OXEYE_DAISY;return Blocks.CORNFLOWER;
    }

    private void ensureActor(ServerLevel level){
        if(actor!=null)return;
        GameProfile profile=new GameProfile(UUID.nameUUIDFromBytes("barttolobots:constructor:v1".getBytes(StandardCharsets.UTF_8)),"Constructor");
        actor=new FakePlayer(level,profile){ {getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION,(byte)127);} };
        actor.setNoGravity(true);actor.setInvulnerable(true);actor.setPos(visualX,visualY,visualZ);
    }
    private void sync(MinecraftServer server,ServerLevel level,ConstructorData d){
        ensureActor(level);actor.setPos(visualX,visualY,visualZ);
        for(ServerPlayer p:server.getPlayerList().getPlayers()){
            boolean close=p.level()==level&&p.distanceToSqr(actor)<=128*128&&p.serverLevel().hasChunkAt(actor.blockPosition());UUID id=p.getUUID();
            if(close&&listed.add(id))p.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(actor)));
            if(close&&visible.add(id)){
                p.connection.send(new ClientboundAddPlayerPacket(actor));var data=actor.getEntityData().getNonDefaultValues();
                if(data!=null)p.connection.send(new ClientboundSetEntityDataPacket(actor.getId(),data));
                p.connection.send(new ClientboundRotateHeadPacket(actor,(byte)(actor.getYRot()*256/360)));
            }else if(close&&(movementPacketPending||server.getTickCount()%5==0)){
                p.connection.send(new ClientboundTeleportEntityPacket(actor));
                p.connection.send(new ClientboundRotateHeadPacket(actor,(byte)(actor.getYHeadRot()*256/360)));
            }
            else if(!close&&visible.remove(id))p.connection.send(new ClientboundRemoveEntitiesPacket(actor.getId()));
            if(!close&&listed.remove(id))p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(actor.getUUID())));
        }
        movementPacketPending=false;
    }
    private void face(FakePlayer player,double dx,double dz){
        if(Math.abs(dx)+Math.abs(dz)<.0001)return;float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));player.setYRot(yaw);player.setYHeadRot(yaw);
    }
    private void animate(MinecraftServer server,ServerLevel level){
        if(actor==null)return;for(ServerPlayer p:server.getPlayerList().getPlayers())if(p.level()==level&&visible.contains(p.getUUID()))p.connection.send(new ClientboundAnimatePacket(actor,0));
    }
    private void announce(MinecraftServer server,ServerLevel level,String message){
        if(announced)return;announced=true;
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(p.level()==level)p.sendSystemMessage(Component.literal("Constructor: ").withStyle(ChatFormatting.GOLD).append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
    }
    private ServerLevel level(MinecraftServer server,String key){
        ResourceLocation loc=ResourceLocation.tryParse(key);return loc==null?null:server.getLevel(ResourceKey.create(Registries.DIMENSION,loc));
    }
    private void loadPlan(ConstructorData d){
        planned.clear();for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);planned.add(t.getInt("type")+":"+t.getInt("x")+":"+t.getInt("y")+":"+t.getInt("z"));}planLoaded=true;
    }
    private void resetActor(MinecraftServer server){
        if(actor!=null)for(ServerPlayer p:server.getPlayerList().getPlayers()){
            if(visible.contains(p.getUUID()))p.connection.send(new ClientboundRemoveEntitiesPacket(actor.getId()));
            if(listed.contains(p.getUUID()))p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(actor.getUUID())));
        }
        actor=null;visible.clear();listed.clear();
    }

    private Block villageWood(ServerLevel level,BlockPos p,String part){String name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(villageFence(level,p)).getPath().replace("_fence", "_"+part);return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft",name));}
    private void orientSite(ServerLevel level,BlockPos center){
        BlockPos nearest=null;int distance=1000;
        for(int dx=-12;dx<=12;dx++)for(int dz=-12;dz<=12;dz++){int dist=Math.abs(dx)+Math.abs(dz);if(dist<distance&&isRoad(level,center.offset(dx,0,dz))){distance=dist;nearest=center.offset(dx,0,dz);}}
        if(nearest==null)return;int dx=nearest.getX()-center.getX(),dz=nearest.getZ()-center.getZ();
        net.minecraft.world.level.block.Rotation rot=Math.abs(dx)>Math.abs(dz)?(dx>0?net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90:net.minecraft.world.level.block.Rotation.CLOCKWISE_90):(dz<0?net.minecraft.world.level.block.Rotation.CLOCKWISE_180:net.minecraft.world.level.block.Rotation.NONE);
        ArrayDeque<Placement> rotated=new ArrayDeque<>();for(Placement p:pending){BlockPos relative=p.pos().subtract(center).rotate(rot);rotated.add(new Placement(center.offset(relative),p.state().rotate(rot),p.force()));}pending.clear();pending.addAll(rotated);
    }
    private int priority(int type){return switch(type){case CLEAR,FILL,TERRACE,PATCH,GRASS,RAMP,RETAIN->0;case PATH->1;case LAMP,TORCH->2;case FARM,WORKSITE,GARDEN,LOOKOUT->3;case FENCE->5;default->4;};}
    private boolean remembered(ConstructorData d,int type,BlockPos p){
        for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);if(!d.dimension.equals(m.getString("dimension")))continue;
            int dx=m.getInt("x")-p.getX(),dz=m.getInt("z")-p.getZ(),mt=m.getInt("type");
            if((mt==GARDEN||mt==WORKSITE||mt==FARM||mt==LOOKOUT)&&dx*dx+dz*dz<49)return true;
            if(mt==type&&dx==0&&dz==0)return true;
        }return false;
    }
    private Block villageFence(ServerLevel level,BlockPos p){String b=level.getBiome(p).unwrapKey().map(k->k.location().getPath()).orElse("");
        if(b.contains("savanna"))return Blocks.ACACIA_FENCE;if(b.contains("taiga")||b.contains("snow")||b.contains("grove"))return Blocks.SPRUCE_FENCE;
        if(b.contains("birch"))return Blocks.BIRCH_FENCE;if(b.contains("jungle"))return Blocks.JUNGLE_FENCE;return Blocks.OAK_FENCE;
    }
    private boolean isDebris(ServerLevel level,BlockPos p){BlockState s=level.getBlockState(p);
        if(!(s.is(Blocks.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.STONE)||s.is(Blocks.COBBLESTONE)||s.is(Blocks.SAND)||s.is(Blocks.GRAVEL)))return false;
        if(level.getBlockEntity(p)!=null||!isOutdoorGround(level,p))return false;
        for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos n=rawSurface(level,p.getX()+dir.getStepX(),p.getZ()+dir.getStepZ());
            if(n==null||n.getY()!=p.getY()-1||!level.getBlockState(n).is(Blocks.GRASS_BLOCK))return false;
        }
        // Never trim a foundation or an object touching a building.
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){BlockPos q=p.offset(dx,0,dz);if(level.getBlockState(q).getBlock() instanceof DoorBlock||isNearBuilding(level,q))return false;}
        return true;
    }
    private void surveyColumn(ServerLevel level,ConstructorData d,int x,int z){
        BlockPos top=rawSurface(level,x,z);if(top==null)return;
        BlockState roof=level.getBlockState(top);
        if(roof.getBlock() instanceof net.minecraft.world.level.block.StairBlock||roof.getBlock() instanceof net.minecraft.world.level.block.SlabBlock){CompoundTag t=new CompoundTag();t.putInt("x",x);t.putInt("y",top.getY());t.putInt("z",z);d.roofs.add(t);}
        int bottom=Math.max(level.getMinBuildHeight(),Math.min(d.centerY-20,top.getY()-20));
        for(int y=top.getY();y>=bottom;y--){BlockState state=level.getBlockState(new BlockPos(x,y,z));
            if(state.getBlock() instanceof DoorBlock&&state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER){CompoundTag t=new CompoundTag();t.putInt("x",x);t.putInt("y",y);t.putInt("z",z);t.putInt("facing",state.getValue(DoorBlock.FACING).get2DDataValue());d.doors.add(t);}
        }
    }
    private boolean reservedCrop(ConstructorData d,BlockPos p){for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(t.getInt("type")==FARM&&Math.abs(t.getInt("x")-p.getX())<=1&&Math.abs(t.getInt("z")-p.getZ())<=1)return true;}return false;}
    private boolean canLink(ServerLevel level,ConstructorData d,BlockPos p){return !reservedCrop(d,p)&&inside(d,p)&&isWalkableGround(level,p)&&!isNearBuilding(level,p)&&
        (level.getBlockState(p).is(Blocks.GRASS_BLOCK)||level.getBlockState(p).is(Blocks.DIRT));}
    private boolean canFence(ServerLevel level,ConstructorData d,BlockPos p){
        if(!inside(d,p)||!isWalkableGround(level,p)||hasRoadNear(level,p.getX(),p.getZ(),1)||isNearBuilding(level,p))return false;
        for(int i=0;i<d.doors.size();i++){CompoundTag t=d.doors.getCompound(i);int dx=t.getInt("x")-p.getX(),dz=t.getInt("z")-p.getZ();if(dx*dx+dz*dz<16)return false;}
        return true;
    }
    private void planVillage(ServerLevel level,ConstructorData d){
        if(d.doors.size()<2){d.villageReport="sin suficientes casas; sin perímetro";return;}
        int left=Integer.MAX_VALUE,right=Integer.MIN_VALUE,north=Integer.MAX_VALUE,south=Integer.MIN_VALUE;
        for(int i=0;i<d.doors.size();i++){CompoundTag t=d.doors.getCompound(i);int x=t.getInt("x"),z=t.getInt("z");left=Math.min(left,x);right=Math.max(right,x);north=Math.min(north,z);south=Math.max(south,z);planEntranceLink(level,d,t);}
        for(int i=0;i<d.roofs.size();i++){CompoundTag r=d.roofs.getCompound(i);boolean near=false;for(int j=0;j<d.doors.size();j++){CompoundTag h=d.doors.getCompound(j);int dx=r.getInt("x")-h.getInt("x"),dz=r.getInt("z")-h.getInt("z");if(dx*dx+dz*dz<=144){near=true;break;}}
            if(near){left=Math.min(left,r.getInt("x"));right=Math.max(right,r.getInt("x"));north=Math.min(north,r.getInt("z"));south=Math.max(south,r.getInt("z"));}}
        left-=3;right+=3;north-=3;south+=3;int cx=(left+right)/2,cz=(north+south)/2;
        d.villageReport=d.doors.size()+" accesos; centro "+cx+","+cz;
        if(!inside(d,new BlockPos(left,0,north))||!inside(d,new BlockPos(right,0,south))){d.villageReport+="; perímetro fuera de parcela: ampliar zona";return;}
        int count=0;for(int x=left;x<=right;x++)for(int z=north;z<=south;z++){
            if(x!=left&&x!=right&&z!=north&&z!=south)continue;
            if(((z==north||z==south)&&Math.abs(x-cx)<=1)||((x==left||x==right)&&Math.abs(z-cz)<=1))continue;
            BlockPos p=surface(level,x,z);if(p==null||!canFence(level,d,p))continue;
            boolean steep=false;for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos n=surface(level,x+dir.getStepX(),z+dir.getStepZ());if(n!=null&&Math.abs(n.getY()-p.getY())>1)steep=true;}
            if(!steep){addTask(d,FENCE,p,0);count++;}
        }d.villageReport+="; vallado propuesto "+count+" tramos con accesos";
    }
    private void planEntranceLink(ServerLevel level,ConstructorData d,CompoundTag door){
        Direction facing=Direction.from2DDataValue(door.getInt("facing"));int x=door.getInt("x")+facing.getStepX(),z=door.getInt("z")+facing.getStepZ();
        BlockPos start=surface(level,x,z);if(start==null||Math.abs(start.getY()+1-door.getInt("y"))>1)return;
        BlockPos road=null;int best=11;for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++){int dist=Math.abs(dx)+Math.abs(dz);if(dist>=best)continue;BlockPos p=surface(level,x+dx,z+dz);if(p!=null&&inside(d,p)&&isRoad(level,p)){best=dist;road=p;}}
        if(road==null)return;List<BlockPos> link=findPath(level,d,start,road);if(link==null||link.size()>12)return;
        for(BlockPos p:link)if(!isRoad(level,p)&&!canLink(level,d,p))return;
        for(BlockPos p:link)if(!isRoad(level,p))addTask(d,PATH,p,0);
    }



    private int nearestHouseDistance(ConstructorData d,BlockPos p){int best=1000;for(int i=0;i<d.doors.size();i++){CompoundTag t=d.doors.getCompound(i);best=Math.min(best,Math.max(Math.abs(t.getInt("x")-p.getX()),Math.abs(t.getInt("z")-p.getZ())));}return best;}
    private boolean earth(BlockState s){return s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.DIRT)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.STONE)||s.is(Blocks.ANDESITE)||s.is(Blocks.GRANITE)||s.is(Blocks.DIORITE);}
    private boolean validEarthColumn(ServerLevel level,ConstructorData d,BlockPos p,int target){
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int type=t.getInt("type");if(type==FARM||type==GARDEN||type==WORKSITE){int dx=t.getInt("x")-p.getX(),dz=t.getInt("z")-p.getZ();if(dx*dx+dz*dz<36)return false;}}
        if(!inside(d,p)||target<=p.getY()||target-p.getY()>10||target>=level.getMaxBuildHeight()-2||!earth(level.getBlockState(p))||level.getBlockEntity(p)!=null)return false;
        if(!level.canSeeSky(p.above())||!level.getFluidState(p).isEmpty())return false;
        for(int y=p.getY()+1;y<=target+2;y++){BlockPos q=new BlockPos(p.getX(),y,p.getZ());if(!level.getBlockState(q).isAir()||!level.getFluidState(q).isEmpty()||level.getBlockEntity(q)!=null)return false;}
        // No modifications alongside a doorway or an existing foundation.
        for(int i=0;i<d.doors.size();i++){CompoundTag door=d.doors.getCompound(i);if(Math.abs(door.getInt("x")-p.getX())+Math.abs(door.getInt("z")-p.getZ())<3)return false;}
        for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos q=p.relative(dir);for(int y=p.getY()+1;y<=target;y++){BlockState state=level.getBlockState(new BlockPos(q.getX(),y,q.getZ()));if(!state.isAir()&&!earth(state))return false;}}
        return !remembered(d,RAMP,p);
    }
    private void planLandscape(ServerLevel level,ConstructorData d){
        for(int i=d.tasks.size()-1;i>=0;i--){CompoundTag t=d.task(i);int type=t.getInt("type");if(type==FLOWER||type==SAPLING){int distance=nearestHouseDistance(d,new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z")));int divisor=distance>24?4:distance>14?2:1;if(Math.floorMod(t.getInt("x")*31+t.getInt("z")*17,divisor)!=0)d.tasks.remove(i);}}
        if(d.doors.size()<2){d.landscapeReport="sin aldea suficiente para modificar bordes";return;}
        int minX=d.centerX-d.width/2,minZ=d.centerZ-d.width/2,ramps=0,walls=0,rejected=0,lookouts=0;
        List<BlockPos> anchors=new ArrayList<>();int max=Math.max(1,d.width/32);
        for(int x=minX+3;x<minX+d.width-3;x++)for(int z=minZ+3;z<minZ+d.width-3;z++){
            BlockPos top=surface(level,x,z);if(top==null||!level.getBlockState(top).is(Blocks.GRASS_BLOCK))continue;
            int distance=nearestHouseDistance(d,top);if(distance<6||distance>28)continue;
            boolean near=false;for(BlockPos a:anchors)if(Math.abs(a.getX()-x)+Math.abs(a.getZ()-z)<14)near=true;if(near)continue;
            for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos low=rawSurface(level,x+dir.getStepX(),z+dir.getStepZ());if(low==null)continue;int drop=top.getY()-low.getY();if(drop<4||drop>10)continue;
                if(ramps<max&&planRamp(level,d,top,dir,drop)){anchors.add(top);ramps++;break;}
                rejected++;
                if(walls<max&&drop<=6&&distance<=10&&validEarthColumn(level,d,low,top.getY()-1)){addTask(d,RETAIN,low,top.getY()-1);walls++;anchors.add(top);break;}
                BlockPos lookout=surface(level,top.getX()-dir.getStepX(),top.getZ()-dir.getStepZ());
                if(lookouts<1&&lookout!=null&&canLookout(level,d,lookout)){addTask(d,LOOKOUT,lookout,dir.get2DDataValue());lookouts++;anchors.add(top);break;}
            }
        }
        int farmLinks=0;for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(t.getInt("type")!=FARM)continue;int before=d.tasks.size();for(Direction dir:Direction.Plane.HORIZONTAL){CompoundTag entrance=new CompoundTag();entrance.putInt("x",t.getInt("x")+dir.getStepX()*2);entrance.putInt("y",t.getInt("y")+1);entrance.putInt("z",t.getInt("z")+dir.getStepZ()*2);entrance.putInt("facing",dir.get2DDataValue());planEntranceLink(level,d,entrance);if(d.tasks.size()>before){farmLinks++;break;}}}
        d.landscapeReport=ramps+" pendientes, "+walls+" apoyos de contención, "+lookouts+" miradores, "+farmLinks+" accesos a cultivos; "+rejected+" candidatos de pendiente descartados por espacio/obstáculos. Relleno máximo 10 bloques, sin excavar laderas";
    }
    private boolean planRamp(ServerLevel level,ConstructorData d,BlockPos top,Direction forward,int drop){
        Direction side=forward.getClockWise();List<BlockPos> bases=new ArrayList<>();List<Integer> heights=new ArrayList<>();
        for(int step=1;step<=drop*2;step++)for(int lane=-1;lane<=1;lane++){
            int target=TerrainRampProfile.height(top.getY(),drop,step,lane);BlockPos probe=top.relative(forward,step).relative(side,lane),base=rawSurface(level,probe.getX(),probe.getZ());
            if(base==null||!inside(d,base)||!earth(level.getBlockState(base))||!isOutdoorGround(level,base))return false;
            if(base.getY()>target)return false;
            if(base.getY()==target)continue;
            if(!validEarthColumn(level,d,base,target))return false;
            bases.add(base);heights.add(target);
        }
        if(bases.isEmpty())return false;
        for(int i=0;i<bases.size();i++)addTask(d,RAMP,bases.get(i),heights.get(i));return true;
    }
    private boolean canLookout(ServerLevel level,ConstructorData d,BlockPos center){
        if(remembered(d,LOOKOUT,center))return false;
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int type=t.getInt("type");if(type==RAMP||type==RETAIN||type==GARDEN||type==WORKSITE||type==FARM){int dx=t.getInt("x")-center.getX(),dz=t.getInt("z")-center.getZ();if(dx*dx+dz*dz<64)return false;}}
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){BlockPos p=surface(level,center.getX()+dx,center.getZ()+dz);if(p==null||!inside(d,p)||p.getY()!=center.getY()||!level.getBlockState(p).is(Blocks.GRASS_BLOCK)||isNearBuilding(level,p)||isRoad(level,p))return false;}
        return true;
    }
    private void planLookout(ServerLevel level,BlockPos center,int facing){
        Direction view=Direction.from2DDataValue(facing),side=view.getClockWise();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)pending.add(new Placement(center.offset(dx,0,dz),villageWood(level,center,"planks").defaultBlockState(),true));
        for(int lane=-1;lane<=1;lane++)pending.add(new Placement(center.relative(view).relative(side,lane).above(),villageFence(level,center).defaultBlockState()));
        pending.add(new Placement(center.above(),villageWood(level,center,"stairs").defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,view)));
        pending.add(new Placement(center.relative(view).relative(side).above(2),Blocks.LANTERN.defaultBlockState()));
    }

    private void resetReport(ConstructorData d){d.landscapeReport="pendiente";d.lastReport="";d.completed=new CompoundTag();d.deferred=new CompoundTag();d.pendingExamples.clear();d.inaccessibleJobs=0;d.invalidJobs=0;d.failedPlacements=0;d.partialJobs=0;}
    private String jobName(int type){return switch(type){case RAMP->"pendientes";case RETAIN->"contención";case LOOKOUT->"miradores";case CLEAR->"limpieza";case FILL,TERRACE->"relleno";case PATCH,GRASS->"césped";case PATH->"caminos";case LAMP,TORCH->"iluminación";case FARM->"huertas";case GARDEN->"jardines";case WORKSITE->"talleres";case FENCE->"vallado";default->"plantas/decoración";};}
    private void deferJob(ConstructorData d,CompoundTag job,String reason){String key=Integer.toString(job.getInt("type"));d.deferred.putInt(key,d.deferred.getInt(key)+1);if(d.pendingExamples.size()<4){CompoundTag example=job.copy();example.putString("reason",reason);d.pendingExamples.add(example);}}
    private String completionReport(ConstructorData d){
        Map<String,Integer> done=new LinkedHashMap<>(),left=new LinkedHashMap<>();
        for(String key:d.completed.getAllKeys())done.merge(jobName(Integer.parseInt(key)),d.completed.getInt(key),Integer::sum);
        for(String key:d.deferred.getAllKeys())left.merge(jobName(Integer.parseInt(key)),d.deferred.getInt(key),Integer::sum);
        StringBuilder report=new StringBuilder("Trabajo finalizado en ").append(d.width).append("×").append(d.width).append(": ").append(d.placed).append(" cambios de bloque.\nCompletado (tareas): ").append(done.isEmpty()?"sin cambios elegibles":done).append(". Lootr: ").append(d.chestsConverted).append(" cofres.\nPendiente: ").append(left.isEmpty()?"ninguna tarea propuesta quedó pendiente":left).append(". Sin acceso: ").append(d.inaccessibleJobs).append("; condiciones cambiadas: ").append(d.invalidJobs).append("; trabajos parciales: ").append(d.partialJobs).append("; colocaciones fallidas: ").append(d.failedPlacements).append('.');
        report.append("\nAldea: ").append(d.villageReport).append('.').append(" Paisaje: ").append(d.landscapeReport).append('.');
        if(d.roadsFound==0)report.append(" No reconocí caminos: revisar los materiales del sendero.");
        if(d.doors.size()<2)report.append(" No hubo suficientes accesos para proponer un perímetro.");
        if(d.villageReport.contains("fuera de parcela"))report.append(" Ampliar la próxima parcela para incluir todas las casas.");
        if(d.chestsFailed>0||d.chestsBoundary>0)report.append(" Cofres pendientes: fallidos ").append(d.chestsFailed).append(", dobles que cruzan el límite ").append(d.chestsBoundary).append('.');
        if(!ConstructorLootr.available())report.append(" Lootr no está instalado: cofres conservados.");
        for(int i=0;i<d.pendingExamples.size();i++){CompoundTag e=d.pendingExamples.getCompound(i);report.append("\nEjemplo pendiente: ").append(jobName(e.getInt("type"))).append(" en ").append(e.getInt("x")).append(' ').append(e.getInt("y")).append(' ').append(e.getInt("z")).append(": ").append(e.getString("reason")).append('.');}
        if(left.isEmpty())report.append(" No detecté más trabajos dentro de las reglas actuales; esto no garantiza que toda la aldea esté completa.");
        return report.toString();
    }

    private long key(BlockPos p){return key(p.getX(),p.getZ());}
    private long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
