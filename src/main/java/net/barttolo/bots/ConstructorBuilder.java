package net.barttolo.bots;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Gardener only: every edit is above existing grass. No terrain, roads, fences or chest conversion. */
public final class ConstructorBuilder {
    static final int TUFT=100,FLOWER=101,TREE=102,LAMP=103;
    private static final int HALO=6,SCAN_BATCH=256,LAMP_SPACING=16,WORK_DELAY_TICKS=10;
    private static final double WALK_SPEED=.16848;
    private record Placement(BlockPos pos,BlockState state){}
    private record Search(Map<Long,BlockPos> nodes,Map<Long,Long> parents,Map<Long,Integer> costs){}
    private FakePlayer actor;
    private final Set<UUID> visible=new HashSet<>(),listed=new HashSet<>();
    private final ArrayDeque<Placement> pending=new ArrayDeque<>();
    private final ArrayList<BlockPos> route=new ArrayList<>();
    private final Map<Long,BlockPos> surveyFloors=new HashMap<>();
    private final Set<Long> obstacles=new HashSet<>();
    private int routeIndex,placementDelay,jobFailures,sectorX=Integer.MIN_VALUE,sectorZ=Integer.MIN_VALUE;
    private boolean visualInitialized,planLoaded,walking,recoveringRoute,movementPacketPending,announced;
    private int recoveryDelay,recoveryAttempts;
    private double visualX,visualY,visualZ;
    static boolean gardenTask(int type){return type>=TUFT&&type<=LAMP;}
    private BlockPos pos(CompoundTag t){return new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));}
    private long key(BlockPos p){return key(p.getX(),p.getZ());}
    private long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private boolean inside(ConstructorData d,BlockPos p){return d.contains(p.getX(),p.getZ());}

    public int create(CommandSourceStack source,int width){
        if(width<32||width>256){source.sendFailure(Component.literal("El área debe medir entre 32 y 256 bloques de lado."));return 0;}
        ConstructorData d=ConstructorData.get(source.getServer());d.dimension=source.getLevel().dimension().location().toString();d.width=width;
        d.centerX=(int)Math.floor(source.getPosition().x);d.centerY=(int)Math.floor(source.getPosition().y);d.centerZ=(int)Math.floor(source.getPosition().z);
        d.configured=true;d.placed=0;d.completedJobs=0;d.skipped=0;restartSurvey(d);d.active=false;d.auditOnly=true;
        BlockPos start=findSafeStart(source.getLevel(),d);if(start==null){d.configured=false;source.sendFailure(Component.literal("No encontré suelo transitable cerca del centro."));return 0;}
        d.centerY=start.getY()+1;visualX=start.getX()+.5;visualY=footHeight(source.getLevel(),start);visualZ=start.getZ()+.5;visualInitialized=true;resetActor(source.getServer());d.setDirty();
        source.sendSuccess(()->Component.literal("Jardinero: inspección de "+d.areaLabel()+" iniciada. Revisaré césped, bordes y cuadrícula de farolas; usá /constructor iniciar para trabajar."),false);return 1;
    }
    private void restartSurvey(ConstructorData d){
        d.scanning=true;d.cursor=0;d.taskCursor=0;d.finished=false;d.reviewPending=false;d.lastReport="";d.tasks.clear();d.retryTasks.clear();
        surveyFloors.clear();obstacles.clear();pending.clear();route.clear();routeIndex=0;walking=false;recoveringRoute=false;placementDelay=0;sectorX=Integer.MIN_VALUE;recoveryAttempts=0;recoveryDelay=0;announced=false;planLoaded=true;d.setDirty();
    }
    public int start(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());if(!d.configured){source.sendFailure(Component.literal("Primero usá /constructor crear [32..256]."));return 0;}
        if(d.active)return 1;if(d.finished||d.reviewPending)restartSurvey(d);d.active=true;d.auditOnly=false;announced=false;d.setDirty();
        source.sendSuccess(()->Component.literal(d.scanning?"Jardinero: terminaré la inspección antes de moverme.":"Jardinero: trabajo iniciado sobre el césped."),false);return 1;
    }
    public int audit(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());if(!d.configured){source.sendFailure(Component.literal("Primero asigná un área con crear."));return 0;}
        restartSurvey(d);d.active=false;d.auditOnly=true;source.sendSuccess(()->Component.literal("Inspección de jardinería iniciada."),false);return 1;
    }
    public int pause(CommandSourceStack source){ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.auditOnly=false;d.setDirty();source.sendSuccess(()->Component.literal("Jardinero pausado; progreso conservado."),false);return 1;}
    public int cancel(CommandSourceStack source){ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.configured=false;d.setDirty();pending.clear();route.clear();walking=false;planLoaded=false;resetActor(source.getServer());source.sendSuccess(()->Component.literal("Asignación cancelada; la decoración colocada se conserva."),false);return 1;}
    public int status(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());String state=!d.configured?"sin área":d.scanning?"inspeccionando":d.reviewPending?"con pendientes":d.finished?"terminado":d.active?"trabajando":"pausado";
        String info=!d.lastReport.isEmpty()?d.lastReport:"Jardinero "+state+" | "+d.areaLabel()+" | inspección "+d.cursor+"/"+((d.width+2*HALO)*(d.width+2*HALO))+" columnas | tareas "+d.taskCursor+"/"+d.tasks.size()+" | pasto "+count(d,TUFT)+", flores "+count(d,FLOWER)+", árboles "+count(d,TREE)+", farolas "+count(d,LAMP)+" | colocaciones "+d.placed;
        source.sendSuccess(()->Component.literal(info),false);return 1;
    }
    private int count(ConstructorData d,int type){int n=0;for(int i=0;i<d.tasks.size();i++)if(d.task(i).getInt("type")==type)n++;return n;}
    public void tick(MinecraftServer server){
        ConstructorData d=ConstructorData.get(server);if(!d.configured)return;ServerLevel level=level(server,d.dimension);if(level==null)return;
        if(!planLoaded){pending.clear();route.clear();walking=false;planLoaded=true;if(d.scanning){d.cursor=0;surveyFloors.clear();obstacles.clear();}}
        if(!visualInitialized){BlockPos p=findSafeStart(level,d);if(p==null)return;visualX=p.getX()+.5;visualY=footHeight(level,p);visualZ=p.getZ()+.5;visualInitialized=true;}
        if(d.scanning&&(d.active||d.auditOnly))scanBatch(level,d);else if(d.active&&!d.scanning)workTick(server,level,d);sync(server,level,d);
    }
    private BlockPos rawSurface(ServerLevel level,int x,int z){
        if(!level.hasChunkAt(new BlockPos(x,level.getMinBuildHeight(),z)))return null;
        BlockPos p=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,level.getMinBuildHeight(),z)).below();
        return level.getBlockState(p).isAir()?null:p;
    }
    private boolean naturalPlant(BlockState s){return s.is(Blocks.GRASS)||s.is(Blocks.TALL_GRASS)||s.is(Blocks.FERN)||s.is(Blocks.LARGE_FERN)||s.is(BlockTags.FLOWERS)||s.getBlock() instanceof SaplingBlock;}
    private boolean obstacle(ServerLevel level,BlockPos floor){
        if(floor==null||!level.getBlockState(floor).is(Blocks.GRASS_BLOCK))return true;
        for(int y=1;y<=3;y++){BlockPos p=floor.above(y);BlockState s=level.getBlockState(p);if(!s.isAir()&&!naturalPlant(s))return true;if(!level.getFluidState(p).isEmpty())return true;}
        return false;
    }
    private void scanBatch(ServerLevel level,ConstructorData d){
        int w=d.width+HALO*2,total=w*w,stop=Math.min(total,d.cursor+SCAN_BATCH),minX=d.minX()-HALO,minZ=d.minZ()-HALO;
        while(d.cursor<stop){int i=d.cursor++,x=minX+i%w,z=minZ+i/w;level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));BlockPos p=rawSurface(level,x,z);if(p!=null)surveyFloors.put(key(x,z),p);if(obstacle(level,p))obstacles.add(key(x,z));}
        if(d.cursor>=total){planGarden(level,d);d.scanning=false;d.taskCursor=0;if(d.auditOnly||!d.active)announce(level.getServer(),level,"Inspección lista en "+d.areaLabel()+": "+count(d,TUFT)+" pastos, "+count(d,FLOWER)+" flores, "+count(d,TREE)+" árboles y "+count(d,LAMP)+" farolas. Usá iniciar para trabajar.");surveyFloors.clear();obstacles.clear();}
        d.setDirty();
    }
    /** Chebyshev distance to every non-grass/occupied column, including a six-block outside halo. */
    private int[] distances(ConstructorData d){
        int w=d.width+HALO*2,minX=d.minX()-HALO,minZ=d.minZ()-HALO;int[] distance=new int[w*w];Arrays.fill(distance,99);ArrayDeque<Integer> open=new ArrayDeque<>();
        for(long k:obstacles){int x=(int)(k>>32)-minX,z=(int)k-minZ;if(x>=0&&x<w&&z>=0&&z<w){int i=x+z*w;distance[i]=0;open.add(i);}}
        while(!open.isEmpty()){int i=open.removeFirst(),x=i%w,z=i/w;if(distance[i]>=HALO)continue;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){int nx=x+dx,nz=z+dz;if(nx<0||nx>=w||nz<0||nz>=w)continue;int n=nx+nz*w;if(distance[n]>distance[i]+1){distance[n]=distance[i]+1;open.addLast(n);}}
        }return distance;
    }
    private void planGarden(ServerLevel level,ConstructorData d){
        d.tasks.clear();int w=d.width+HALO*2;int[] dist=distances(d);List<BlockPos> lamps=new ArrayList<>();
        for(int x=d.minX();x<d.minX()+d.width;x++)for(int z=d.minZ();z<d.minZ()+d.width;z++){
            if(Math.floorMod(x,LAMP_SPACING)!=0||Math.floorMod(z,LAMP_SPACING)!=0)continue;BlockPos p=surveyFloors.get(key(x,z));
            int distance=dist[x-d.minX()+HALO+(z-d.minZ()+HALO)*w];if(p!=null&&distance>3&&siteValid(level,d,p,LAMP,false)){d.addTask(LAMP,x,p.getY(),z);lamps.add(p);}
        }
        for(int x=d.minX();x<d.minX()+d.width;x++)for(int z=d.minZ();z<d.minZ()+d.width;z++){
            BlockPos p=surveyFloors.get(key(x,z));if(p==null||!level.getBlockState(p).is(Blocks.GRASS_BLOCK)||!level.getBlockState(p.above()).isAir())continue;
            int distance=dist[x-d.minX()+HALO+(z-d.minZ()+HALO)*w];
            if((distance==1||distance==3)&&Math.floorMod(x+z,2)==0){int type=Math.floorMod(x*31+z*17,3)==0?FLOWER:TUFT;d.addTask(type,x,p.getY(),z);}
            if(distance>5&&Math.floorMod(x,8)==0&&Math.floorMod(z,8)==0&&Math.floorMod(x*31+z*17,3)==0&&lamps.stream().noneMatch(l->Math.max(Math.abs(l.getX()-p.getX()),Math.abs(l.getZ()-p.getZ()))<=6)&&siteValid(level,d,p,TREE,false)){
                d.addTask(TREE,x,p.getY(),z);
            }
        }
    }
    private List<Placement> blueprint(ServerLevel level,BlockPos base,int type){
        if(type==TUFT)return List.of(new Placement(base.above(),Blocks.GRASS.defaultBlockState()));
        if(type==FLOWER)return List.of(new Placement(base.above(),flowerFor(base.getX(),base.getZ()).defaultBlockState()));
        List<Placement> out=new ArrayList<>();
        if(type==LAMP){
            out.add(new Placement(base.above(),Blocks.POLISHED_ANDESITE.defaultBlockState()));out.add(new Placement(base.above(2),Blocks.DIORITE_WALL.defaultBlockState()));
            out.add(new Placement(base.above(3),Blocks.OAK_FENCE.defaultBlockState()));out.add(new Placement(base.above(4),Blocks.OAK_FENCE.defaultBlockState()));out.add(new Placement(base.above(5),Blocks.DIORITE_WALL.defaultBlockState()));
            out.add(new Placement(base.above(6),Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT,true)));out.add(new Placement(base.above(7),Blocks.REDSTONE_BLOCK.defaultBlockState()));
            for(Direction dir:Direction.Plane.HORIZONTAL)out.add(new Placement(base.above(7).relative(dir),Blocks.POLISHED_ANDESITE.defaultBlockState()));out.add(new Placement(base.above(8),Blocks.STONE_BRICK_SLAB.defaultBlockState()));return out;
        }
        int variant=Math.floorMod(base.getX()*19+base.getZ()*73,3),height=variant==1?5:4;
        Block log=variant==1?Blocks.BIRCH_LOG:variant==2?Blocks.SPRUCE_LOG:Blocks.OAK_LOG,leaf=variant==1?Blocks.BIRCH_LEAVES:variant==2?Blocks.SPRUCE_LEAVES:Blocks.OAK_LEAVES;
        for(int y=1;y<=height;y++)out.add(new Placement(base.above(y),log.defaultBlockState()));
        for(int y=height-1;y<=height+1;y++){int radius=y==height+1?1:2;
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
                if(dx==0&&dz==0&&y<=height||Math.abs(dx)==radius&&Math.abs(dz)==radius&&radius==2)continue;
                out.add(new Placement(base.offset(dx,y,dz),leaf.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true)));
            }
        }return out;
    }
    private boolean liveClearance(ServerLevel level,BlockPos base,int radius,Set<Long> own){
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            int x=base.getX()+dx,z=base.getZ()+dz;if(own.contains(key(x,z)))continue;BlockPos p=rawSurface(level,x,z);if(obstacle(level,p))return false;
        }return true;
    }
    private boolean siteValid(ServerLevel level,ConstructorData d,BlockPos base,int type,boolean started){
        if(!gardenTask(type)||!inside(d,base)||!level.hasChunkAt(base)||!level.getBlockState(base).is(Blocks.GRASS_BLOCK))return false;
        List<Placement> parts=blueprint(level,base,type);Set<Long> own=new HashSet<>();if(started)for(Placement p:parts)own.add(key(p.pos()));
        if(type==TREE||type==LAMP){int radius=type==TREE?2:1;
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){BlockPos p=base.offset(dx,0,dz);if(!inside(d,p)||!level.getBlockState(p).is(Blocks.GRASS_BLOCK))return false;}
            if(!liveClearance(level,base,type==TREE?5:3,own))return false;
        }
        for(Placement part:parts){BlockPos p=part.pos();if(!inside(d,p)||p.getY()<=base.getY()||p.getY()>=level.getMaxBuildHeight()||!level.getFluidState(p).isEmpty()||level.getBlockEntity(p)!=null)return false;
            BlockState current=level.getBlockState(p);if(started&&current.is(part.state().getBlock()))continue;
            if(!current.isAir()&&!(type>=TREE&&current.is(Blocks.GRASS)))return false;
        }return true;
    }
    private Block flowerFor(int x,int z){return switch(Math.floorMod(x*19+z*73,5)){case 0->Blocks.POPPY;case 1->Blocks.DANDELION;case 2->Blocks.AZURE_BLUET;case 3->Blocks.OXEYE_DAISY;default->Blocks.CORNFLOWER;};}
    private boolean taskSatisfied(ServerLevel level,ConstructorData d,CompoundTag task){
        BlockPos base=pos(task);if(!level.hasChunkAt(base)||!level.getBlockState(base).is(Blocks.GRASS_BLOCK))return false;
        for(Placement p:blueprint(level,base,task.getInt("type")))if(!level.getBlockState(p.pos()).is(p.state().getBlock()))return false;return true;
    }
    private void complete(ConstructorData d,boolean success){if(success)d.completedJobs++;else d.skipped++;d.taskCursor++;pending.clear();route.clear();routeIndex=0;walking=false;placementDelay=0;d.setDirty();}
    private void defer(ConstructorData d,CompoundTag task,String reason){CompoundTag t=task.copy();t.putString("reason",reason);d.retryTasks.add(t);d.setDirty();}
    private void finish(ConstructorData d){
        d.active=false;d.finished=d.retryTasks.isEmpty();d.reviewPending=!d.finished;
        d.lastReport="Jardinería "+(d.finished?"finalizada":"con pendientes")+" en "+d.areaLabel()+": "+d.completedJobs+" tareas completadas, "+d.placed+" bloques de vegetación/iluminación colocados, "+d.skipped+" omitidas o pendientes. Sin acceso/pendientes: "+d.retryTasks.size()+".";
        for(int i=0;i<Math.min(3,d.retryTasks.size());i++){CompoundTag t=d.retryTasks.getCompound(i);BlockPos p=pos(t);d.lastReport+=" "+p.getX()+" "+p.getY()+" "+p.getZ()+": "+t.getString("reason")+".";}
        d.setDirty();
    }
    private void workTick(MinecraftServer server,ServerLevel level,ConstructorData d){
        if(d.reviewPending)return;
        if(!pending.isEmpty()){
            if(placementDelay>0){placementDelay--;return;}
            CompoundTag task=d.task(d.taskCursor);BlockPos base=pos(task);Placement part=pending.removeFirst();BlockState current=level.getBlockState(part.pos());
            if(!gardenTask(task.getInt("type"))||!inside(d,part.pos())||part.pos().getY()<=base.getY()||!level.getBlockState(base).is(Blocks.GRASS_BLOCK))jobFailures++;
            else if(current.is(part.state().getBlock())){} // resume a partially built tree/lamp without replacing its blocks
            else if((current.isAir()||task.getInt("type")>=TREE&&current.is(Blocks.GRASS))&&level.getFluidState(part.pos()).isEmpty()&&level.getBlockEntity(part.pos())==null){
                if(level.setBlock(part.pos(),part.state(),3)){d.placed++;animate(server,level);}else jobFailures++;
            }else jobFailures++;
            placementDelay=WORK_DELAY_TICKS;d.setDirty();
            if(pending.isEmpty()){
                if(jobFailures>0){defer(d,task,"el sitio cambió durante la colocación");complete(d,false);}
                else{if(task.getInt("type")==TREE||task.getInt("type")==LAMP){CompoundTag m=task.copy();m.putString("dimension",d.dimension);d.memory.add(m);}complete(d,true);}
            }return;
        }
        if(walking){
            BlockPos next=route.get(routeIndex);if(!next.equals(standingGround(level,next.getX(),next.getZ(),next.getY()))){route.clear();routeIndex=0;walking=false;return;}
            walkOneTick(level);if(routeIndex>=route.size()){walking=false;if(recoveringRoute){recoveringRoute=false;route.clear();}else prepareTask(level,d);}return;
        }
        if(d.taskCursor>=d.tasks.size()){finish(d);announced=false;announce(server,level,d.lastReport);return;}
        if(beginNearestTask(level,d)&&!walking)prepareTask(level,d);
    }
    private void prepareTask(ServerLevel level,ConstructorData d){
        if(d.taskCursor>=d.tasks.size())return;CompoundTag task=d.task(d.taskCursor);int type=task.getInt("type");BlockPos base=pos(task);
        if(!gardenTask(type)){complete(d,false);return;}
        if(taskSatisfied(level,d,task)){complete(d,true);return;}
        boolean started=task.getBoolean("started");if(!siteValid(level,d,base,type,started)){
            if(started)defer(d,task,"decoración parcial protegida; sitio ocupado o suelo cambiado");complete(d,false);return;
        }
        task.putBoolean("started",true);pending.addAll(blueprint(level,base,type));jobFailures=0;placementDelay=0;d.setDirty();
        if(actor!=null)face(actor,base.getX()+.5-visualX,base.getZ()+.5-visualZ);
    }
    private Search search(ServerLevel level,ConstructorData d,BlockPos start,int radius){
        Map<Long,BlockPos> nodes=new HashMap<>();Map<Long,Long> parents=new HashMap<>();Map<Long,Integer> costs=new HashMap<>();ArrayDeque<BlockPos> open=new ArrayDeque<>();
        long root=key(start);nodes.put(root,start);parents.put(root,root);costs.put(root,0);open.add(start);
        while(!open.isEmpty()){BlockPos at=open.removeFirst();for(Direction dir:Direction.Plane.HORIZONTAL){int x=at.getX()+dir.getStepX(),z=at.getZ()+dir.getStepZ();long k=key(x,z);
            if(!d.contains(x,z)||Math.abs(x-start.getX())>radius||Math.abs(z-start.getZ())>radius||parents.containsKey(k))continue;BlockPos next=navigationStep(level,d,x,z,at.getY());if(next==null)continue;
            parents.put(k,key(at));nodes.put(k,next);costs.put(k,costs.get(key(at))+1);open.addLast(next);
        }}return new Search(nodes,parents,costs);
    }
    private boolean visibleWork(ServerLevel level,BlockPos from,BlockPos target){
        int steps=Math.max(Math.abs(target.getX()-from.getX()),Math.abs(target.getZ()-from.getZ()));
        for(int i=1;i<steps;i++){BlockPos p=new BlockPos(from.getX()+(int)Math.round((target.getX()-from.getX())*(double)i/steps),from.getY()+1+(int)Math.round((target.getY()-from.getY())*(double)i/steps),from.getZ()+(int)Math.round((target.getZ()-from.getZ())*(double)i/steps));
            if(!level.getBlockState(p).getCollisionShape(level,p).isEmpty())return false;
        }return true;
    }
    private BlockPos approach(ServerLevel level,CompoundTag task,Search search){
        BlockPos target=pos(task),best=null;int cost=Integer.MAX_VALUE,type=task.getInt("type");
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            if(dx*dx+dz*dz>20||type==TREE&&Math.max(Math.abs(dx),Math.abs(dz))<3||type==LAMP&&Math.max(Math.abs(dx),Math.abs(dz))<2)continue;
            BlockPos p=search.nodes().get(key(target.getX()+dx,target.getZ()+dz));if(p==null||Math.abs(p.getY()-target.getY())>2||!visibleWork(level,p,target))continue;
            int c=search.costs().get(key(p));if(c<cost){cost=c;best=p;}
        }return best;
    }
    private boolean beginNearestTask(ServerLevel level,ConstructorData d){
        for(int i=d.taskCursor;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(!gardenTask(t.getInt("type"))||!level.getBlockState(pos(t)).is(Blocks.GRASS_BLOCK)||taskSatisfied(level,d,t)){
            var old=d.tasks.get(d.taskCursor);d.tasks.set(d.taskCursor,d.tasks.get(i));d.tasks.set(i,old);complete(d,gardenTask(t.getInt("type"))&&taskSatisfied(level,d,t));return false;
        }}
        BlockPos current=standingGround(level,(int)Math.floor(visualX),(int)Math.floor(visualZ),(int)Math.floor(visualY-.01));if(current==null){recoverPosition(level,d);return false;}
        recoveryAttempts=0;recoveryDelay=0;int best=-1;BlockPos goal=null;Search result=null;
        for(int radius:new int[]{24,d.width}){
            Search s=search(level,d,current,radius);int bestSector=2,bestType=9,bestCost=Integer.MAX_VALUE;
            for(int i=d.taskCursor;i<d.tasks.size();i++){CompoundTag t=d.task(i);BlockPos p=approach(level,t,s);if(p==null)continue;
                int sx=Math.floorDiv(t.getInt("x")-d.minX(),16),sz=Math.floorDiv(t.getInt("z")-d.minZ(),16),sector=sx==sectorX&&sz==sectorZ?0:1;
                int c=s.costs().get(key(p)),priority=t.getInt("type")==LAMP?0:t.getInt("type")==TREE?2:1;
                if(sector<bestSector||sector==bestSector&&(c<bestCost||c==bestCost&&priority<bestType)){best=i;bestSector=sector;bestCost=c;bestType=priority;goal=p;}
            }
            if(best>=0){result=s;break;}
        }
        if(best<0){for(int i=d.taskCursor;i<d.tasks.size();i++)defer(d,d.task(i),"sin posición de trabajo conectada; vegetación conservada");d.skipped+=d.tasks.size()-d.taskCursor;d.taskCursor=d.tasks.size();return false;}
        var old=d.tasks.get(d.taskCursor);d.tasks.set(d.taskCursor,d.tasks.get(best));d.tasks.set(best,old);CompoundTag t=d.task(d.taskCursor);sectorX=Math.floorDiv(t.getInt("x")-d.minX(),16);sectorZ=Math.floorDiv(t.getInt("z")-d.minZ(),16);
        route.clear();for(long k=key(goal);k!=key(current);k=result.parents().get(k))route.add(result.nodes().get(k));Collections.reverse(route);routeIndex=0;walking=!route.isEmpty();return true;
    }
    private void walkOneTick(ServerLevel level){
        BlockPos next=route.get(routeIndex);double dx=next.getX()+.5-visualX,dy=footHeight(level,next)-visualY,dz=next.getZ()+.5-visualZ,distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(distance<=WALK_SPEED){visualX=next.getX()+.5;visualY=footHeight(level,next);visualZ=next.getZ()+.5;routeIndex++;}else{visualX+=dx*WALK_SPEED/distance;visualY+=dy*WALK_SPEED/distance;visualZ+=dz*WALK_SPEED/distance;}
        movementPacketPending=true;if(actor!=null){face(actor,dx,dz);actor.setPos(visualX,visualY,visualZ);}
    }
    private boolean navigationHeadroom(ServerLevel level,BlockPos ground){
        for(int y=1;y<=2;y++){
            BlockPos p=ground.above(y);BlockState state=level.getBlockState(p);
            if(!level.getFluidState(p).isEmpty()||state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)||state.is(Blocks.SWEET_BERRY_BUSH)||state.is(Blocks.WITHER_ROSE))return false;
            if(!state.getCollisionShape(level,p).isEmpty())return false;
        }return true;
    }
    /** Anchor to the worker's feet, not the highest item in the column or a distant underground cavity. */
    private BlockPos standingGround(ServerLevel level,int x,int z,int expectedY){
        for(int dy:new int[]{0,-1,1}){
            BlockPos p=new BlockPos(x,expectedY+dy,z);if(!level.hasChunkAt(p)||!navigationHeadroom(level,p))continue;
            BlockState floor=level.getBlockState(p);
            if(floor.is(Blocks.FARMLAND)||isWalkableGround(level,p))return p;
            if(!level.getFluidState(p).isEmpty()||floor.is(Blocks.MAGMA_BLOCK)||floor.is(Blocks.CACTUS)||floor.is(Blocks.CAMPFIRE)||floor.is(Blocks.SOUL_CAMPFIRE))continue;
            var shape=floor.getCollisionShape(level,p);
            if(!shape.isEmpty()&&shape.max(Direction.Axis.Y)>=.5&&shape.max(Direction.Axis.Y)<=1&&level.getBlockEntity(p)==null&&!(floor.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock))return p;
        }return null;
    }
    /** A bounded local step out of a newly obstructed worker cell; do not teleport across the plot. */
    private void recoverPosition(ServerLevel level,ConstructorData d){
        if(recoveryDelay>0){recoveryDelay--;return;}
        int x=(int)Math.floor(visualX),z=(int)Math.floor(visualZ),y=(int)Math.floor(visualY-.01);
        for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos next=standingGround(level,x+dir.getStepX(),z+dir.getStepZ(),y);
            if(next==null||!inside(d,next)||Math.abs(next.getY()+1-visualY)>1.1)continue;
            route.clear();route.add(next);routeIndex=0;walking=true;recoveringRoute=true;recoveryAttempts=0;return;
        }
        if(++recoveryAttempts<3){recoveryDelay=20;return;}
        d.active=false;d.setDirty();recoveryAttempts=0;
        BlockPos feet=new BlockPos(x,(int)Math.floor(visualY),z);
        announce(level.getServer(),level,"Pausado: no pude salir de "+x+" "+feet.getY()+" "+z+". Bloque a los pies: "+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet).getBlock())+". Conservé las tareas pendientes; retiren el obstáculo y usen iniciar.");
    }
    /** Follow the floor near the previous foot level, including paving, not a roof from the heightmap. */
    private BlockPos navigationStep(ServerLevel level,ConstructorData d,int x,int z,int referenceY){
        BlockPos p=standingGround(level,x,z,referenceY);if(p==null)return null;
        if(isWalkableGround(level,p)||p.getY()<=d.centerY)return p;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos nativeGround=surface(level,x+dx,z+dz);
            if(nativeGround!=null&&isWalkableGround(level,nativeGround)&&Math.abs(nativeGround.getY()-p.getY())<=1)return p;
        }return null;
    }
    private double footHeight(ServerLevel level,BlockPos p){
        var shape=level.getBlockState(p).getCollisionShape(level,p);
        return p.getY()+(shape.isEmpty()?1:Math.min(1,shape.max(Direction.Axis.Y)));
    }
    private BlockPos surface(ServerLevel level,int x,int z){BlockPos p=rawSurface(level,x,z);if(p==null)return null;for(int i=0;i<=2;i++){BlockPos q=p.below(i);if(navigationHeadroom(level,q)&&!level.getBlockState(q).getCollisionShape(level,q).isEmpty()&&!(level.getBlockState(q).getBlock() instanceof LeavesBlock)&&level.getFluidState(q).isEmpty())return q;}return null;}
    private BlockPos findSafeStart(ServerLevel level,ConstructorData d){
        for(int radius=0;radius<=8;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){if(Math.abs(dx)+Math.abs(dz)!=radius||!d.contains(d.centerX+dx,d.centerZ+dz))continue;
            level.getChunk(Math.floorDiv(d.centerX+dx,16),Math.floorDiv(d.centerZ+dz,16));BlockPos p=surface(level,d.centerX+dx,d.centerZ+dz);if(p!=null&&isWalkableGround(level,p))return p;
        }return null;
    }
    private boolean isWalkableGround(ServerLevel level,BlockPos p){if(!navigationHeadroom(level,p))return false;BlockState s=level.getBlockState(p);return s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.DIRT)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.FARMLAND)||s.is(Blocks.STONE)||s.is(Blocks.GRAVEL)||s.is(Blocks.SAND)||s.is(Blocks.PODZOL)||s.is(Blocks.ROOTED_DIRT)||s.is(Blocks.COARSE_DIRT);}
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
    private void resetActor(MinecraftServer server){
        if(actor!=null)for(ServerPlayer p:server.getPlayerList().getPlayers()){
            if(visible.contains(p.getUUID()))p.connection.send(new ClientboundRemoveEntitiesPacket(actor.getId()));
            if(listed.contains(p.getUUID()))p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(actor.getUUID())));
        }
        actor=null;visible.clear();listed.clear();
    }


}
