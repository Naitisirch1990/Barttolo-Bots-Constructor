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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.commands.CommandSourceStack;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Visible, deliberate builder: stationary survey first, then grounded travel and work. */
public final class ConstructorBuilder {
    private static final int GRASS=0, TERRACE=1, FLOWER=2, SAPLING=3, LAMP=4,
        PATCH=5, FARM=6, POT=7, TORCH=8, LEAVES=9;
    private static final int SCAN_BATCH=48, LAMP_SPACING=8, WORK_DELAY_TICKS=10;
    private static final double WALK_SPEED=.075;

    private record Placement(BlockPos pos,BlockState state,boolean force) {
        Placement(BlockPos pos,BlockState state){this(pos,state,false);}
    }
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
        d.width=width;d.cursor=0;d.taskCursor=0;d.placed=0;d.skipped=0;d.configured=true;d.active=false;d.scanning=true;d.finished=false;d.tasks.clear();
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
        d.active=true;d.setDirty();announced=false;
        source.sendSuccess(()->Component.literal(d.scanning?"Constructor quieto: empieza a analizar la zona.":"Constructor retoma el trabajo desde el suelo."),false);return 1;
    }
    public int pause(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.setDirty();
        source.sendSuccess(()->Component.literal("Constructor pausado. La zona y el progreso quedaron guardados."),false);return 1;
    }
    public int cancel(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.configured=false;d.finished=false;d.setDirty();
        pending.clear();route.clear();planned.clear();planLoaded=false;resetActor(source.getServer());
        source.sendSuccess(()->Component.literal("Asignación cancelada. Los cambios ya hechos se conservan."),false);return 1;
    }
    public int status(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        String state=!d.configured?"sin zona":d.finished?"terminado":!d.active?"pausado":d.scanning?"escaneando quieto":"trabajando";
        int total=d.width*d.width;
        String progress=d.configured?(d.scanning?" | análisis "+Math.min(d.cursor,total)+"/"+total:" | tareas "+Math.min(d.taskCursor,d.tasks.size())+"/"+d.tasks.size())+" | bloques "+d.placed+" | omitidos "+d.skipped:"";
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
            BlockPos ground=surface(level,x,z);if(ground==null)continue;
            BlockState floor=level.getBlockState(ground);
            if(!isOutdoorGround(level,ground))continue;
            if(floor.is(Blocks.DIRT))addTask(d,GRASS,ground,0);
            if(canTerrace(level,ground))addTask(d,TERRACE,ground,0);
            BlockPos exposed=rawSurface(level,x,z);
            if(exposed!=null&&isOutdoorGround(level,exposed)&&isIsolatedStone(level,exposed))addTask(d,PATCH,exposed,0);
            if(isRoad(level,ground))planRoadLamp(level,d,ground);

            planHouseDetails(level,d,ground);
            if(canFarm(level,ground,d))addTask(d,FARM,ground,0);

            int rx=Math.floorMod(x-d.centerX,LAMP_SPACING),rz=Math.floorMod(z-d.centerZ,LAMP_SPACING);
            if(rx==0&&rz==0&&!hasRoadNear(level,x,z,3)){
                int arms=Math.floorMod((x-d.centerX+z-d.centerZ)/LAMP_SPACING,2);
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
        if(d.cursor>=total){d.scanning=false;d.taskCursor=0;d.setDirty();}
    }

    private void planRoadLamp(ServerLevel level,ConstructorData d,BlockPos road){
        boolean alongX=isRoad(level,road.west())||isRoad(level,road.east());
        boolean alongZ=isRoad(level,road.north())||isRoad(level,road.south());
        if(!alongX&&!alongZ)return;
        if(alongX&&Math.floorMod(road.getX()-d.centerX,LAMP_SPACING)!=0)return;
        if(alongZ&&Math.floorMod(road.getZ()-d.centerZ,LAMP_SPACING)!=0)return;
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
                int dx=prior.getInt("x")-p.getX(),dz=prior.getInt("z")-p.getZ();if(dx*dx+dz*dz<36){planned.remove(key);return;}
            }
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
        else if(type==FLOWER){if(clearForPlant(level,ground))pending.add(new Placement(ground.above(),flowerFor(level,x,z).defaultBlockState()));}
        else if(type==SAPLING){if(clearForTree(level,ground))pending.add(new Placement(ground.above(),saplingFor(level,ground).defaultBlockState()));}
        else if(type==PATCH){if(isIsolatedStone(level,ground))pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState(),true));}
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
        BlockPos top=surface(level,p.getX(),p.getZ());if(top==null)return false;
        BlockState s=level.getBlockState(top);if(s.is(Blocks.DIRT_PATH))return true;
        if(!s.is(Blocks.GRAVEL))return false;int neighbors=0;
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos n=surface(level,p.getX()+dir[0],p.getZ()+dir[1]);
            if(n!=null&&(level.getBlockState(n).is(Blocks.DIRT_PATH)||level.getBlockState(n).is(Blocks.GRAVEL)))neighbors++;
        }
        return neighbors>=2;
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
    private Block flowerFor(ServerLevel level,int x,int z){
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
            }else if(close&&(movementPacketPending||server.getTickCount()%5==0))p.connection.send(new ClientboundTeleportEntityPacket(actor));
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
