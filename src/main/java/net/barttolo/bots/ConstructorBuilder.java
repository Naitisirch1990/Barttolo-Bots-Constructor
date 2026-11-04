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
        PATCH=5, FARM=6, POT=7, TORCH=8, LEAVES=9, FILL=10, WORKSITE=11, EDGE_FLOWER=12, GARDEN=13;
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
    private int routeIndex,placementDelay;
    private double visualX,visualY,visualZ;
    private boolean announced,visualInitialized,planLoaded,walking,movementPacketPending;

    public int create(CommandSourceStack source,int width){
        ConstructorData d=ConstructorData.get(source.getServer());
        d.dimension=source.getLevel().dimension().location().toString();
        d.centerX=(int)Math.floor(source.getPosition().x);d.centerY=(int)Math.floor(source.getPosition().y);d.centerZ=(int)Math.floor(source.getPosition().z);
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
        d.active=true;d.auditOnly=false;d.setDirty();announced=false;
        source.sendSuccess(()->Component.literal(d.scanning?"Constructor quieto: empieza a analizar la zona.":"Constructor retoma el trabajo desde el suelo."),false);return 1;
    }
    /** Rebuilds a read-only task audit for the currently assigned square. */
    public int audit(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        if(!d.configured){source.sendFailure(Component.literal("Primero asigná la parcela con /constructor crear [32..128]."));return 0;}
        if(d.active){source.sendFailure(Component.literal("Pausá el Constructor antes de reiniciar la auditoría."));return 0;}
        d.active=true;d.scanning=true;d.finished=false;d.auditOnly=true;d.cursor=0;d.taskCursor=0;d.tasks.clear();
        d.placed=0;d.skipped=0;d.holesFound=0;d.roadsFound=0;d.stoneFound=0;d.setDirty();
        pending.clear();route.clear();planned.clear();planLoaded=true;walking=false;announced=false;
        source.sendSuccess(()->Component.literal("Auditoría iniciada: revisaré la superficie y las columnas vecinas en toda la parcela, sin cambiar bloques."),false);return 1;
    }
    public int pause(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.setDirty();
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
        String progress=d.configured?(d.scanning?" | columnas "+Math.min(d.cursor,total)+"/"+total:" | tareas "+Math.min(d.taskCursor,d.tasks.size())+"/"+d.tasks.size())+" | huecos "+d.holesFound+" | camino "+d.roadsFound+" columnas | piedra aislada "+d.stoneFound+" | huertas "+taskCount(d,FARM)+" | jardines "+taskCount(d,GARDEN)+" | obras "+taskCount(d,WORKSITE)+" | tareas "+d.tasks.size()+" | bloques "+d.placed+" | omitidos "+d.skipped:"";
        source.sendSuccess(()->Component.literal("Constructor "+state+progress+" | "+(d.configured?d.dimension+" centro "+d.centerX+" "+d.centerY+" "+d.centerZ+" | "+d.width+"×"+d.width:"")),false);return 1;
    }

    public void tick(MinecraftServer server){
        ConstructorData d=ConstructorData.get(server);if(!d.configured)return;
        if(!planLoaded)loadPlan(d);
        ServerLevel level=level(server,d.dimension);if(level==null)return;
        if(!visualInitialized){BlockPos start=findSafeStart(level,d);if(start==null)return;visualX=start.getX()+.5;visualY=start.getY()+1;visualZ=start.getZ()+.5;visualInitialized=true;}
        if(d.active){if(d.scanning)scanBatch(level,d);else workTick(server,level,d);}
        sync(server,level,d);
    }

    /** Survey does not move the visible bot. It only records safe outdoor candidates. */
    private void scanBatch(ServerLevel level,ConstructorData d){
        int total=d.width*d.width,stop=Math.min(total,d.cursor+SCAN_BATCH),minX=d.centerX-d.width/2,minZ=d.centerZ-d.width/2;
        while(d.cursor<stop){
            int row=d.cursor/d.width,col=d.cursor%d.width,x=minX+col,z=minZ+row;d.cursor++;
            if(!level.hasChunkAt(new BlockPos(x,d.centerY,z)))continue;
            BlockPos exposed=rawSurface(level,x,z);if(exposed==null||!isOutdoorGround(level,exposed)||!level.getFluidState(exposed).isEmpty())continue;
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
            if(seed<7&&clearForTree(level,ground)){
                addTask(d,SAPLING,ground,0);
            }else if(seed>=24&&seed<40&&clearForPlant(level,ground)){
                addTask(d,FLOWER,ground,0);
            }
        }
        d.setDirty();
        if(d.cursor>=total){d.scanning=false;d.taskCursor=0;if(d.auditOnly){d.active=false;announce(level.getServer(),level,"Auditoría lista: "+d.holesFound+" huecos, "+d.roadsFound+" columnas de camino y "+d.stoneFound+" bloques de piedra aislados; "+d.tasks.size()+" acciones propuestas. No cambié bloques. Revisá /constructor estado y usa /constructor iniciar para ejecutar el plan.");}d.setDirty();}
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
    private boolean canBuildLamp(ServerLevel level,ConstructorData d,BlockPos base,int arms){
        if(!level.hasChunkAt(base)||!isOutdoorGround(level,base))return false;
        if(!(level.getBlockState(base).is(Blocks.GRASS_BLOCK)||level.getBlockState(base).is(Blocks.DIRT)))return false;
        for(int y=1;y<=4;y++)if(!level.getBlockState(base.above(y)).canBeReplaced())return false;
        int dx=arms==0?1:0,dz=arms==1?1:0;
        for(int sign:new int[]{-1,1}){
            BlockPos arm=base.offset(dx*sign,4,dz*sign),lantern=base.offset(dx*sign,3,dz*sign);
            if(!inside(d,arm)||!inside(d,lantern)||!level.getBlockState(arm).canBeReplaced()||!level.getBlockState(lantern).canBeReplaced())return false;
        }
        return true;
    }
    private void addTask(ConstructorData d,int type,BlockPos p,int direction){
        String key=type+":"+p.getX()+":"+p.getY()+":"+p.getZ();
        if(!planned.add(key))return;
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
                level.setBlock(p.pos(),p.state(),3);d.placed++;animate(server,level);
            }else d.skipped++;
            placementDelay=WORK_DELAY_TICKS;d.setDirty();if(pending.isEmpty())completeTask(d,false);return;
        }
        if(walking){
            walkOneTick(level);
            if(routeIndex>=route.size()){walking=false;prepareTask(level,d);}
            return;
        }
        if(d.taskCursor>=d.tasks.size()){
            d.active=false;d.finished=true;d.setDirty();announce(server,level,"Terminé: analicé "+(d.width*d.width)+" columnas e hice "+d.placed+" cambios. Podés asignarme otra zona con /constructor crear.");return;
        }
        if(!beginNearestTask(level,d))return;
        if(!walking)prepareTask(level,d);
    }
    private boolean beginNearestTask(ServerLevel level,ConstructorData d){
        if(d.taskCursor>=d.tasks.size())return false;
        int start=d.taskCursor,best=-1;double bestDistance=Double.MAX_VALUE;
        for(int i=start;i<d.tasks.size();i++){
            CompoundTag task=d.task(i);BlockPos approach=approachFor(level,d,task);if(approach==null)continue;
            double dx=approach.getX()+.5-visualX,dz=approach.getZ()+.5-visualZ;
            double distance=dx*dx+dz*dz;
            if(distance<bestDistance){best=i;bestDistance=distance;}
        }
        if(best<0){d.skipped+=d.tasks.size()-d.taskCursor;d.taskCursor=d.tasks.size();d.setDirty();return false;}
        if(best!=start){var a=d.tasks.get(start);var b=d.tasks.get(best);d.tasks.set(start,b);d.tasks.set(best,a);}
        CompoundTag task=d.task(d.taskCursor);BlockPos goal=approachFor(level,d,task);
        if(goal==null){d.skipped++;d.taskCursor++;d.setDirty();return false;}
        BlockPos current=surface(level,(int)Math.floor(visualX),(int)Math.floor(visualZ));
        if(current==null){current=findSafeStart(level,d);if(current==null){d.skipped++;d.taskCursor++;return false;}}
        List<BlockPos> found=findPath(level,d,current,goal);
        if(found==null){d.skipped++;d.taskCursor++;d.setDirty();return false;}
        route.clear();route.addAll(found);routeIndex=0;
        walking=!route.isEmpty();
        if(!walking)prepareTask(level,d);
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
        if(type==GRASS){if(level.getBlockState(ground).is(Blocks.DIRT))pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState()));}
        else if(type==TERRACE){if(canTerrace(level,ground))pending.add(new Placement(ground.above(),Blocks.GRASS_BLOCK.defaultBlockState()));}
        else if(type==FLOWER){if(clearForPlant(level,ground))pending.add(new Placement(ground.above(),flowerFor(x,z).defaultBlockState()));}
        else if(type==SAPLING){if(clearForTree(level,ground))pending.add(new Placement(ground.above(),saplingFor(level,ground).defaultBlockState()));}
        else if(type==PATCH){if(isIsolatedStone(level,ground))pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState(),true));}
        else if(type==FILL){int verified=holeTop(level,ground);if(verified==direction&&verified>ground.getY()){
            for(int fillY=ground.getY()+1;fillY<=verified;fillY++)pending.add(new Placement(new BlockPos(x,fillY,z),fillY==verified?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.DIRT.defaultBlockState(),true));
        }}
        else if(type==EDGE_FLOWER){if(canEdgeFlower(level,ground))pending.add(new Placement(ground.above(),flowerFor(x,z).defaultBlockState()));}
        else if(type==WORKSITE){if(canWorksite(level,ground,d))planWorksite(ground);}
        else if(type==GARDEN){if(canGarden(level,ground,d))planGarden(ground);}
        else if(type==FARM){if(canFarm(level,ground,d))planFarm(ground);}
        else if(type==POT){if(canPlacePot(level,ground))pending.add(new Placement(ground.above(),Blocks.POTTED_POPPY.defaultBlockState()));}
        else if(type==TORCH){if(canPlaceTorch(level,ground))pending.add(new Placement(ground.above(),Blocks.TORCH.defaultBlockState()));}
        else if(type==LEAVES){if(canPlaceLeaves(level,ground)){
            pending.add(new Placement(ground.above(),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true)));
            if(level.getBlockState(ground.above(2)).isAir())pending.add(new Placement(ground.above(2),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true)));
        }}
        else if(type==LAMP){
            if(!canBuildLamp(level,d,ground,direction)){completeTask(d,true);return;}
            planLampBlocks(ground,direction);
        }
        if(pending.isEmpty())completeTask(d,true);
    }
    private void planLampBlocks(BlockPos base,int arms){
        pending.add(new Placement(base.above(),Blocks.STONE_BRICKS.defaultBlockState()));
        pending.add(new Placement(base.above(2),Blocks.OAK_FENCE.defaultBlockState()));
        pending.add(new Placement(base.above(3),Blocks.OAK_FENCE.defaultBlockState()));
        pending.add(new Placement(base.above(4),Blocks.OAK_FENCE.defaultBlockState()));
        int dx=arms==0?1:0,dz=arms==1?1:0;
        for(int sign:new int[]{-1,1}){
            BlockPos arm=base.offset(dx*sign,4,dz*sign);
            pending.add(new Placement(arm,Blocks.OAK_FENCE.defaultBlockState()));
            pending.add(new Placement(base.offset(dx*sign,3,dz*sign),Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING,true)));
        }
    }
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
        int count=0;for(int i=0;i<d.tasks.size();i++)if(d.task(i).getInt("type")==type)count++;return count;
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
            CompoundTag t=d.task(i);int type=t.getInt("type");
            if(type==FARM||type==WORKSITE||type==GARDEN){int dx=t.getInt("x")-center.getX(),dz=t.getInt("z")-center.getZ();if(dx*dx+dz*dz<100)return false;}
        }
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos p=center.offset(dx,0,dz),top=surface(level,p.getX(),p.getZ());
            if(!inside(d,p)||top==null||top.getY()!=center.getY()||!level.getBlockState(top).is(Blocks.GRASS_BLOCK)
                ||!isOutdoorGround(level,top)||!level.getBlockState(top.above()).isAir()||isRoad(level,top)||isNearBuilding(level,top))return false;
        }
        return true;
    }
    private void planGarden(BlockPos c){
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
        pending.add(new Placement(c.offset(-1,1,-1),Blocks.OAK_FENCE.defaultBlockState()));
        pending.add(new Placement(c.offset(-1,2,-1),Blocks.LANTERN.defaultBlockState()));
        pending.add(new Placement(c.offset(1,1,1),Blocks.OAK_STAIRS.defaultBlockState()));
    }
    private boolean canWorksite(ServerLevel level,BlockPos center,ConstructorData d){
        if(d.width<64||Math.abs(center.getX()-d.centerX)>d.width/4||Math.abs(center.getZ()-d.centerZ)>d.width/4)return false;
        if(!level.getBlockState(center).is(Blocks.GRASS_BLOCK)||!inside(d,center))return false;
        int max=Math.max(1,d.width/64),count=0;
        if(hasRoadNear(level,center.getX(),center.getZ(),3))return false;
        for(int i=0;i<d.tasks.size();i++){
            CompoundTag task=d.task(i);int type=task.getInt("type");
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
    private void planWorksite(BlockPos c){
        BlockState stone=Blocks.STONE_BRICKS.defaultBlockState(),weathered=Blocks.COBBLESTONE.defaultBlockState();
        BlockState spruce=Blocks.SPRUCE_LOG.defaultBlockState(),slab=Blocks.SPRUCE_SLAB.defaultBlockState();
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
        pending.add(new Placement(c.offset(-3,0,1),Blocks.SPRUCE_PLANKS.defaultBlockState(),true));
        pending.add(new Placement(c.offset(-3,0,2),Blocks.SPRUCE_PLANKS.defaultBlockState(),true));
        pending.add(new Placement(c.offset(-3,1,1),spruce));
        pending.add(new Placement(c.offset(-3,1,2),spruce));
        pending.add(new Placement(c.offset(1,0,3),Blocks.SCAFFOLDING.defaultBlockState(),true));
        pending.add(new Placement(c.offset(1,1,3),Blocks.SCAFFOLDING.defaultBlockState()));
        pending.add(new Placement(c.offset(2,0,3),Blocks.BARREL.defaultBlockState(),true));
    }
    private void completeTask(ConstructorData d,boolean skipped){if(skipped)d.skipped++;d.taskCursor++;placementDelay=0;pending.clear();route.clear();routeIndex=0;walking=false;d.setDirty();}

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
        int[][] dirs=type==LAMP?(direction==0?new int[][]{{0,2},{0,-2},{0,3},{0,-3}}:new int[][]{{2,0},{-2,0},{3,0},{-3,0}}):new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
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
        for(int i=0;i<d.tasks.size();i++)if(d.task(i).getInt("type")==FARM){
            int dx=d.task(i).getInt("x")-p.getX(),dz=d.task(i).getInt("z")-p.getZ();if(dx*dx+dz*dz<100)return false;
        }
        int farmCount=0;for(int i=0;i<d.tasks.size();i++)if(d.task(i).getInt("type")==FARM)farmCount++;
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
    private long key(BlockPos p){return key(p.getX(),p.getZ());}
    private long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
