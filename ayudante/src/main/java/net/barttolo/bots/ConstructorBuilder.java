package net.barttolo.bots;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeableLeatherItem;
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
    static final int TUFT=100,FLOWER=101,TREE=102,LAMP=103,FARM=104,PLAZA=105,BENCH=106,PERGOLA=107,WELL=108,STALL=109,PICNIC=110,POND=111;
    private static final int HALO=6,SCAN_BATCH=256,LAMP_SPACING=12,WORK_DELAY_TICKS=10;
    private static final double WALK_SPEED=.16848;
    private record Placement(BlockPos pos,BlockState state){}
    private record Search(Map<Long,BlockPos> nodes,Map<Long,Long> parents,Map<Long,Integer> costs){}
    private FakePlayer actor;
    // FULL (33), not entity-ticking tickets: the gardener edits from the server tick.
    // Private, transient ownership never interferes with /forceload or other mods.
    private static final TicketType<UUID> GARDEN_TICKET=TicketType.create("barttolobots_garden",Comparator.comparing(UUID::toString));
    private final UUID ticketOwner=UUID.randomUUID();
    private final Set<Long> heldChunks=new HashSet<>();
    private ServerLevel ticketLevel;
    private int ticketMinX,ticketMinZ,ticketWidth;
    private final List<Pair<EquipmentSlot,ItemStack>> clothes=gardenerClothes();
    private final Set<UUID> visible=new HashSet<>(),listed=new HashSet<>();
    private final ArrayDeque<Placement> pending=new ArrayDeque<>();
    private final ArrayList<BlockPos> route=new ArrayList<>();
    private final Map<Long,BlockPos> surveyFloors=new HashMap<>();
    private final Set<Long> obstacles=new HashSet<>();
    private int routeIndex,placementDelay,jobFailures,sectorX=Integer.MIN_VALUE,sectorZ=Integer.MIN_VALUE;
    private boolean visualInitialized,planLoaded,walking,recoveringRoute,movementPacketPending,announced;
    private int recoveryDelay,recoveryAttempts;
    private double visualX,visualY,visualZ;
    static boolean gardenTask(int type){return type>=TUFT&&type<=POND;}
    private BlockPos pos(CompoundTag t){return new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));}
    private long key(BlockPos p){return key(p.getX(),p.getZ());}
    private long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private boolean inside(ConstructorData d,BlockPos p){return d.contains(p.getX(),p.getZ());}

    public int create(CommandSourceStack source,int width){
        return createAt(source,width,(int)Math.floor(source.getPosition().x),(int)Math.floor(source.getPosition().z),false);
    }
    public int createAt(CommandSourceStack source,int width,int centerX,int centerZ,boolean autoStart){
        if(width<32||width>256){source.sendFailure(Component.literal("El área debe medir entre 32 y 256 bloques de lado."));return 0;}
        releaseChunks();ConstructorData d=ConstructorData.get(source.getServer());d.positionSaved=false;visualInitialized=false;d.dimension=source.getLevel().dimension().location().toString();d.width=width;
        d.centerX=centerX;d.centerY=(int)Math.floor(source.getPosition().y);d.centerZ=centerZ;
        d.configured=true;d.placed=0;d.completedJobs=0;d.skipped=0;restartSurvey(d);d.active=autoStart;d.auditOnly=!autoStart;
        BlockPos start=findSafeStart(source.getLevel(),d);if(start==null){d.configured=false;source.sendFailure(Component.literal("No encontré suelo transitable cerca del centro."));return 0;}
        d.centerY=start.getY()+1;visualX=start.getX()+.5;visualY=footHeight(source.getLevel(),start);visualZ=start.getZ()+.5;visualInitialized=true;savePosition(d);resetActor(source.getServer());d.setDirty();
        source.sendSuccess(()->Component.literal(autoStart
            ?"Jardinero: inspección de "+d.areaLabel()+" iniciada. Al terminar, empezaré automáticamente."
            :"Jardinero: inspección de "+d.areaLabel()+" iniciada. Usá /constructor iniciar para trabajar."),false);return 1;
    }
    private void restartSurvey(ConstructorData d){
        d.scanning=true;d.cursor=0;d.taskCursor=0;d.finished=false;d.reviewPending=false;d.lastReport="";d.tasks.clear();d.retryTasks.clear();
        surveyFloors.clear();obstacles.clear();pending.clear();route.clear();routeIndex=0;walking=false;recoveringRoute=false;placementDelay=0;sectorX=Integer.MIN_VALUE;recoveryAttempts=0;recoveryDelay=0;announced=false;planLoaded=true;d.setDirty();
    }
    public int start(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());if(!d.configured){source.sendFailure(Component.literal("Primero usá /jardinero crear [32..256]."));return 0;}
        if(d.active)return 1;if(d.reviewPending)return resume(source);if(d.finished){source.sendSuccess(()->Component.literal("Jardinero: no quedan tareas. Usá auditar si querés una inspección nueva."),false);return 1;}d.active=true;d.auditOnly=false;announced=false;d.setDirty();
        source.sendSuccess(()->Component.literal(d.scanning?"Jardinero: terminaré la inspección antes de moverme.":"Jardinero: trabajo iniciado sobre el césped."),false);return 1;
    }
    public int audit(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());if(!d.configured){source.sendFailure(Component.literal("Primero asigná un área con crear."));return 0;}
        restartSurvey(d);d.active=false;d.auditOnly=true;source.sendSuccess(()->Component.literal("Inspección de jardinería iniciada."),false);return 1;
    }
    public int pause(CommandSourceStack source){ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.auditOnly=false;savePosition(d);releaseChunks();d.setDirty();source.sendSuccess(()->Component.literal("Jardinero pausado; progreso conservado."),false);return 1;}
    public int cancel(CommandSourceStack source){ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.configured=false;savePosition(d);releaseChunks();d.setDirty();pending.clear();route.clear();walking=false;planLoaded=false;visualInitialized=false;resetActor(source.getServer());source.sendSuccess(()->Component.literal("Asignación cancelada; la decoración colocada se conserva."),false);return 1;}
    public int status(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());String state=!d.configured?"sin área":d.scanning?"inspeccionando":d.reviewPending?"con pendientes":d.finished?"terminado":d.active?"trabajando":"pausado";
        String info=!d.lastReport.isEmpty()?d.lastReport:"Jardinero "+state+" | "+d.areaLabel()+" | inspección "+d.cursor+"/"+((d.width+2*HALO)*(d.width+2*HALO))+" columnas | tareas "+d.taskCursor+"/"+d.tasks.size()+" | pasto "+count(d,TUFT)+", flores "+count(d,FLOWER)+", árboles "+count(d,TREE)+", farolas "+count(d,LAMP)+", construcciones "+structureCount(d)+" | colocaciones "+d.placed;
        source.sendSuccess(()->Component.literal(info+" | Posición: "+d.dimension+" "+(int)Math.floor(d.positionSaved?d.workerX:d.centerX)+" "+(int)Math.floor(d.positionSaved?d.workerY:d.centerY)+" "+(int)Math.floor(d.positionSaved?d.workerZ:d.centerZ)+" | /jardinero ir · /jardinero continuar"),false);return 1;
    }
    private int structureCount(ConstructorData d){int n=0;for(int i=0;i<d.tasks.size();i++)if(macro(d.task(i).getInt("type")))n++;return n;}
    private int count(ConstructorData d,int type){int n=0;for(int i=0;i<d.tasks.size();i++)if(d.task(i).getInt("type")==type)n++;return n;}
    /** Keep the whole bounded survey/navigation map available, without simulating every chunk. */
    private void updateChunks(ServerLevel level,ConstructorData d){
        if(!d.configured||!(d.active||d.scanning&&d.auditOnly)){releaseChunks();return;}
        if(ticketLevel==level&&ticketMinX==d.minX()&&ticketMinZ==d.minZ()&&ticketWidth==d.width&&!heldChunks.isEmpty())return;
        releaseChunks();ticketLevel=level;ticketMinX=d.minX();ticketMinZ=d.minZ();ticketWidth=d.width;
        for(int x=Math.floorDiv(d.minX()-HALO,16);x<=Math.floorDiv(d.minX()+d.width-1+HALO,16);x++)
            for(int z=Math.floorDiv(d.minZ()-HALO,16);z<=Math.floorDiv(d.minZ()+d.width-1+HALO,16);z++){
                ChunkPos chunk=new ChunkPos(x,z);level.getChunkSource().addRegionTicket(GARDEN_TICKET,chunk,0,ticketOwner);heldChunks.add(chunk.toLong());
            }
    }
    private boolean chunksReady(){
        if(ticketLevel==null)return false;
        for(long key:heldChunks){ChunkPos chunk=new ChunkPos(key);if(!ticketLevel.hasChunkAt(new BlockPos(chunk.getMinBlockX(),ticketLevel.getMinBuildHeight(),chunk.getMinBlockZ())))return false;}return true;
    }
    private void releaseChunks(){
        if(ticketLevel!=null)for(long key:heldChunks)ticketLevel.getChunkSource().removeRegionTicket(GARDEN_TICKET,new ChunkPos(key),0,ticketOwner);
        heldChunks.clear();ticketLevel=null;
    }
    private void savePosition(ConstructorData d){
        if(!visualInitialized)return;
        if(!d.positionSaved||d.workerX!=visualX||d.workerY!=visualY||d.workerZ!=visualZ){d.workerX=visualX;d.workerY=visualY;d.workerZ=visualZ;d.positionSaved=true;d.setDirty();}
    }
    private boolean ensurePosition(ServerLevel level,ConstructorData d){
        if(visualInitialized)return true;
        if(d.positionSaved&&Double.isFinite(d.workerX)&&Double.isFinite(d.workerY)&&Double.isFinite(d.workerZ)
            &&d.contains((int)Math.floor(d.workerX),(int)Math.floor(d.workerZ))&&d.workerY>=level.getMinBuildHeight()&&d.workerY<level.getMaxBuildHeight()){
            visualX=d.workerX;visualY=d.workerY;visualZ=d.workerZ;visualInitialized=true;return true;
        }
        BlockPos p=findSafeStart(level,d);if(p==null)return false;
        visualX=p.getX()+.5;visualY=footHeight(level,p);visualZ=p.getZ()+.5;visualInitialized=true;savePosition(d);return true;
    }
    public void forget(UUID viewer){visible.remove(viewer);listed.remove(viewer);}
    public void shutdown(MinecraftServer server){
        savePosition(ConstructorData.get(server));releaseChunks();resetActor(server);visualInitialized=false;planLoaded=false;
        pending.clear();route.clear();walking=false;surveyFloors.clear();obstacles.clear();
    }
    public void tick(MinecraftServer server){
        ConstructorData d=ConstructorData.get(server);if(!d.configured){releaseChunks();return;}
        ServerLevel level=level(server,d.dimension);if(level==null){releaseChunks();return;}
        updateChunks(level,d);
        if(!planLoaded){pending.clear();route.clear();walking=false;planLoaded=true;if(d.scanning){d.cursor=0;surveyFloors.clear();obstacles.clear();}}
        if(!ensurePosition(level,d))return;
        if(d.scanning&&(d.active||d.auditOnly))scanBatch(level,d);else if(d.active&&!d.scanning&&chunksReady())workTick(server,level,d);
        savePosition(d);updateChunks(level,d);sync(server,level,d);
    }
    /** Search beside the saved feet, not the heightmap (which may be a roof). */
    private BlockPos nearbySafeGround(ServerLevel level,ConstructorData d,int minimum){
        int x=(int)Math.floor(visualX),z=(int)Math.floor(visualZ),y=(int)Math.floor(visualY-.01);
        for(int r=minimum;r<=8;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r||!d.contains(x+dx,z+dz))continue;
            level.getChunk(Math.floorDiv(x+dx,16),Math.floorDiv(z+dz,16));
            BlockPos p=standingGround(level,x+dx,z+dz,y);
            if(p!=null&&Math.abs(footHeight(level,p)-visualY)<=1.1&&level.getWorldBorder().isWithinBounds(p))return p;
        }return null;
    }
    public int teleportTo(CommandSourceStack source)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        ServerPlayer player=source.getPlayerOrException();ConstructorData d=ConstructorData.get(source.getServer());ServerLevel level=level(source.getServer(),d.dimension);
        if(!d.configured||level==null||!ensurePosition(level,d)){source.sendFailure(Component.literal("No hay Jardinero disponible. Primero asigná un área."));return 0;}
        BlockPos p=nearbySafeGround(level,d,1);
        if(p==null){source.sendFailure(Component.literal("No hay suelo seguro junto al Jardinero; usá /jardinero continuar para intentar sacarlo del atasco."));return 0;}
        player.teleportTo(level,p.getX()+.5,footHeight(level,p),p.getZ()+.5,player.getYRot(),player.getXRot());
        sync(source.getServer(),level,d);source.sendSuccess(()->Component.literal("Te llevé junto al Jardinero."),false);return 1;
    }
    private String taskKey(CompoundTag task){return task.getInt("type")+":"+task.getInt("x")+":"+task.getInt("y")+":"+task.getInt("z");}
    private void restorePending(ConstructorData d){
        Set<String> remaining=new HashSet<>();for(int i=d.taskCursor;i<d.tasks.size();i++)remaining.add(taskKey(d.task(i)));
        for(int i=0;i<d.retryTasks.size();i++){CompoundTag t=d.retryTasks.getCompound(i);if(gardenTask(t.getInt("type"))&&remaining.add(taskKey(t)))d.tasks.add(t.copy());}
        d.retryTasks.clear();d.reviewPending=false;d.finished=false;d.lastReport="";
    }
    public int resume(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());ServerLevel level=level(source.getServer(),d.dimension);
        if(!d.configured||level==null||!ensurePosition(level,d)){source.sendFailure(Component.literal("No hay asignación para continuar."));return 0;}
        if(!d.scanning&&d.taskCursor>=d.tasks.size()&&d.retryTasks.isEmpty()){source.sendSuccess(()->Component.literal("Jardinero: no quedan tareas en el plan actual."),false);return 1;}
        BlockPos p=nearbySafeGround(level,d,2);
        if(p==null){source.sendFailure(Component.literal("No encontré suelo seguro a 2–8 bloques. Conservé el plan y la posición; despejá una salida y volvé a usar continuar."));return 0;}
        restorePending(d);pending.clear();route.clear();routeIndex=0;walking=false;recoveringRoute=false;recoveryAttempts=0;recoveryDelay=0;jobFailures=0;placementDelay=0;sectorX=Integer.MIN_VALUE;
        visualX=p.getX()+.5;visualY=footHeight(level,p);visualZ=p.getZ()+.5;movementPacketPending=true;savePosition(d);announced=false;
        // Preserve the scan cache/cursor if this instance was already inspecting.
        d.active=true;d.auditOnly=false;d.setDirty();updateChunks(level,d);sync(source.getServer(),level,d);
        source.sendSuccess(()->Component.literal("Jardinero: me moví a "+p.getX()+" "+(p.getY()+1)+" "+p.getZ()+" y continúo el mismo plan. Se conservan las construcciones y el progreso."),false);return 1;
    }
    public int skin(CommandSourceStack source,ServerPlayer player){
        var properties=player.getGameProfile().getProperties().get("textures");if(properties.isEmpty()){source.sendFailure(Component.literal("Ese jugador no tiene una skin disponible."));return 0;}
        Property skin=properties.iterator().next();ConstructorData d=ConstructorData.get(source.getServer());d.texture=skin.getValue();d.signature=skin.hasSignature()?skin.getSignature():"";d.setDirty();resetActor(source.getServer());
        source.sendSuccess(()->Component.literal("Skin del Jardinero copiada de "+player.getGameProfile().getName()+"; conserva su nombre y oficio."),false);return 1;
    }
    private BlockPos rawSurface(ServerLevel level,int x,int z){
        if(!level.hasChunkAt(new BlockPos(x,level.getMinBuildHeight(),z)))return null;
        BlockPos p=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,new BlockPos(x,level.getMinBuildHeight(),z)).below();
        return level.getBlockState(p).isAir()?null:p;
    }
    private boolean naturalPlant(BlockState s){return s.is(Blocks.GRASS)||s.is(Blocks.TALL_GRASS)||s.is(Blocks.FERN)||s.is(Blocks.LARGE_FERN)||s.is(BlockTags.FLOWERS)||s.getBlock() instanceof SaplingBlock;}
    private boolean obstacle(ServerLevel level,BlockPos floor){
        if(floor==null||!level.getBlockState(floor).is(Blocks.GRASS_BLOCK))return true;
        for(int y=1;y<=8;y++){BlockPos p=floor.above(y);BlockState s=level.getBlockState(p);if(!s.isAir()&&!naturalPlant(s))return true;if(!level.getFluidState(p).isEmpty())return true;}
        return false;
    }
    private void scanBatch(ServerLevel level,ConstructorData d){
        int w=d.width+HALO*2,total=w*w,stop=Math.min(total,d.cursor+SCAN_BATCH),minX=d.minX()-HALO,minZ=d.minZ()-HALO;
        while(d.cursor<stop){int i=d.cursor++,x=minX+i%w,z=minZ+i/w;level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));BlockPos p=rawSurface(level,x,z);if(p!=null)surveyFloors.put(key(x,z),p);if(obstacle(level,p))obstacles.add(key(x,z));}
        if(d.cursor>=total){planGarden(level,d);d.scanning=false;d.taskCursor=0;if(d.auditOnly||!d.active)announce(level.getServer(),level,"Inspección lista en "+d.areaLabel()+": "+count(d,TUFT)+" pastos, "+count(d,FLOWER)+" flores, "+count(d,TREE)+" árboles y "+count(d,LAMP)+" farolas y "+structureCount(d)+" construcciones. Usá iniciar para trabajar.");surveyFloors.clear();obstacles.clear();}
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
    private boolean macro(int type){return type>=FARM&&type<=POND;}
    private int macroRadius(int type){return type==BENCH||type==PLAZA?3:4;}
    private int separation(BlockPos a,BlockPos b){return Math.max(Math.abs(a.getX()-b.getX()),Math.abs(a.getZ()-b.getZ()));}
    private List<BlockPos> existingSites(ServerLevel level,ConstructorData d,int type){
        List<BlockPos> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);BlockPos p=pos(m);if(m.getInt("type")!=type||!d.dimension.equals(m.getString("dimension"))||!seen.add(key(p)))continue;
            if(taskSatisfied(level,d,m))result.add(p);
        }return result;
    }
    /** Choose the best translation of a 12-block grid, instead of rejecting small plots by world alignment. */
    private List<BlockPos> gridSites(ServerLevel level,ConstructorData d,int type,int[] dist,List<BlockPos> lamps,List<CompoundTag> sites){
        int w=d.width+HALO*2,threshold=type==TREE?5:3;List<List<BlockPos>> phases=new ArrayList<>();for(int i=0;i<144;i++)phases.add(new ArrayList<>());
        List<BlockPos> existing=existingSites(level,d,type);
        for(int x=d.minX();x<d.minX()+d.width;x++)for(int z=d.minZ();z<d.minZ()+d.width;z++){
            BlockPos p=surveyFloors.get(key(x,z));if(p==null||!level.getBlockState(p).is(Blocks.GRASS_BLOCK)||dist[x-d.minX()+HALO+(z-d.minZ()+HALO)*w]<=threshold)continue;
            if(existing.stream().anyMatch(q->separation(p,q)<12))continue;
            if(type==TREE&&(lamps.stream().anyMatch(q->separation(p,q)<6)||sites.stream().anyMatch(t->separation(p,pos(t))<=macroRadius(t.getInt("type"))+5)))continue;
            phases.get(Math.floorMod(x,12)+Math.floorMod(z,12)*12).add(p);
        }
        List<Integer> order=new ArrayList<>();for(int i=0;i<144;i++)order.add(i);order.sort(Comparator.<Integer>comparingInt(i->phases.get(i).size()).reversed().thenComparingInt(i->i));
        for(int phase:order){List<BlockPos> valid=new ArrayList<>();for(BlockPos p:phases.get(phase))if(siteValid(level,d,p,type,false))valid.add(p);if(!valid.isEmpty())return valid;}
        return List.of();
    }
    private void planGarden(ServerLevel level,ConstructorData d){
        d.tasks.clear();int w=d.width+HALO*2;int[] dist=distances(d);List<CompoundTag> sites=new ArrayList<>();List<BlockPos> lamps=new ArrayList<>(gridSites(level,d,LAMP,dist,List.of(),sites));
        for(BlockPos p:lamps)d.addTask(LAMP,p.getX(),p.getY(),p.getZ());
        int existing=0;Set<Long> seen=new HashSet<>();for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);if(macro(m.getInt("type"))&&d.dimension.equals(m.getString("dimension"))&&inside(d,pos(m))&&seen.add(key(pos(m))))existing++;}
        int limit=Math.min(12,Math.max(2,d.width*d.width/2048)),added=0;int[] types={FARM,PLAZA,BENCH,PERGOLA,PICNIC,STALL,WELL,POND};
        for(int attempt=0;attempt<types.length&&existing+added<limit;attempt++){
            int type=types[(attempt+existing)%types.length],r=macroRadius(type);BlockPos best=null;
            for(int x=d.minX()+r;x<d.minX()+d.width-r&&best==null;x++)for(int z=d.minZ()+r;z<d.minZ()+d.width-r;z++){
                BlockPos p=surveyFloors.get(key(x,z));if(p==null||dist[x-d.minX()+HALO+(z-d.minZ()+HALO)*w]<=r||sites.stream().anyMatch(t->separation(p,pos(t))<r+macroRadius(t.getInt("type"))+3))continue;
                long remove=lamps.stream().filter(l->separation(l,p)<=r+3).count();if(!lamps.isEmpty()&&remove==lamps.size())continue;
                if(siteValid(level,d,p,type,false)){best=p;break;}
            }
            if(best==null)continue;final BlockPos selected=best;
            lamps.removeIf(l->separation(l,selected)<=r+3);
            for(int i=d.tasks.size()-1;i>=0;i--)if(d.task(i).getInt("type")==LAMP&&separation(pos(d.task(i)),selected)<=r+3)d.tasks.remove(i);
            d.addTask(type,best.getX(),best.getY(),best.getZ());sites.add(d.task(d.tasks.size()-1));added++;
        }
        for(BlockPos p:gridSites(level,d,TREE,dist,lamps,sites))d.addTask(TREE,p.getX(),p.getY(),p.getZ());
        for(int x=d.minX();x<d.minX()+d.width;x++)for(int z=d.minZ();z<d.minZ()+d.width;z++){
            BlockPos p=surveyFloors.get(key(x,z));if(p==null||!level.getBlockState(p).is(Blocks.GRASS_BLOCK)||!level.getBlockState(p.above()).isAir()||sites.stream().anyMatch(t->separation(p,pos(t))<=macroRadius(t.getInt("type"))))continue;
            int distance=dist[x-d.minX()+HALO+(z-d.minZ()+HALO)*w];if((distance==1||distance==3)&&Math.floorMod(x+z,2)==0){int type=Math.floorMod(x*31+z*17,3)==0?FLOWER:TUFT;d.addTask(type,x,p.getY(),z);}
        }
    }
    private List<Placement> structureBlueprint(BlockPos base,int type){
        LinkedHashMap<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        if(type==PLAZA){
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)blocks.put(base.offset(x,1,z),(Math.floorMod(x+z,2)==0?Blocks.STONE_BRICKS:Blocks.ANDESITE).defaultBlockState());
            for(int x:new int[]{-1,0,1})blocks.put(base.offset(x,2,-1),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
            for(int x:new int[]{-2,2}){blocks.put(base.offset(x,1,1),Blocks.MOSS_BLOCK.defaultBlockState());blocks.put(base.offset(x,2,1),Blocks.FLOWERING_AZALEA.defaultBlockState());}
            blocks.put(base.offset(0,2,1),Blocks.CHISELED_STONE_BRICKS.defaultBlockState());blocks.put(base.offset(0,3,1),Blocks.LANTERN.defaultBlockState());
            blocks.put(base.offset(0,1,3),Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        }else{
            int original=switch(type){case FARM->6;case BENCH->VillageCatalog.BENCH;case PERGOLA->VillageCatalog.PERGOLA;case WELL->VillageCatalog.WELL;case STALL->VillageCatalog.STALL;case PICNIC->VillageCatalog.PICNIC;case POND->VillageCatalog.POND;default->throw new IllegalArgumentException("Unknown garden structure");};
            int variant=Math.floorMod(base.getX()*31+base.getZ()*17,type==FARM?3:2);
            var recipe=VillageCatalog.build(original,variant,Blocks.OAK_FENCE,Blocks.OAK_LOG,Blocks.OAK_SLAB,Blocks.OAK_STAIRS,Blocks.OAK_TRAPDOOR);
            int lift=recipe.stream().anyMatch(p->p.offset().getY()<0)?2:1,r=type==BENCH?2:3;
            for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)for(int y=1;y<=lift;y++)blocks.put(base.offset(x,y,z),Blocks.STONE_BRICKS.defaultBlockState());
            for(var part:recipe)blocks.put(base.offset(part.offset()).above(lift),part.state());
            blocks.put(base.offset(0,1,r+1),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
            if(lift==2)blocks.put(base.offset(0,2,r),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));
        }
        List<Placement> result=new ArrayList<>();blocks.forEach((p,state)->result.add(new Placement(p,state)));result.sort(Comparator.<Placement>comparingInt(p->p.state().is(Blocks.WATER)?1:p.state().getBlock() instanceof CropBlock||p.state().is(Blocks.LILY_PAD)?2:0).thenComparingInt(p->p.pos().getY()));return result;
    }
    private List<Placement> blueprint(ServerLevel level,BlockPos base,int type){
        if(type==TUFT)return List.of(new Placement(base.above(),Blocks.GRASS.defaultBlockState()));
        if(type==FLOWER)return List.of(new Placement(base.above(),flowerFor(base.getX(),base.getZ()).defaultBlockState()));
        if(macro(type))return structureBlueprint(base,type);
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
    private void ignoreOwnedLamps(ServerLevel level,ConstructorData d,BlockPos base,Set<Long> own){
        // A nearby garden lamp is allowed beside a tree; buildings and paths still require five clear blocks.
        for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);BlockPos p=pos(m);
            if(m.getInt("type")==LAMP&&d.dimension.equals(m.getString("dimension"))&&separation(base,p)>=6&&separation(base,p)<=7&&taskSatisfied(level,d,m))for(Placement part:blueprint(level,p,LAMP))own.add(key(part.pos()));
        }
    }
    private boolean siteValid(ServerLevel level,ConstructorData d,BlockPos base,int type,boolean started){
        if(!gardenTask(type)||!inside(d,base)||!level.hasChunkAt(base))return false;
        if(!level.getBlockState(base).is(Blocks.GRASS_BLOCK)){
            CompoundTag check=new CompoundTag();check.putInt("type",type);check.putInt("x",base.getX());check.putInt("y",base.getY());check.putInt("z",base.getZ());check.putBoolean("started",started);if(!workBase(level,check))return false;
        }
        List<Placement> parts=blueprint(level,base,type);Set<Long> own=new HashSet<>();Map<BlockPos,BlockState> expected=new HashMap<>();for(Placement part:parts)expected.put(part.pos(),part.state());
        if(started)for(Placement p:parts)own.add(key(p.pos()));if(type==TREE)ignoreOwnedLamps(level,d,base,own);
        if(type==TREE||type==LAMP||macro(type)){int radius=macro(type)?macroRadius(type):type==TREE?2:1;
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){BlockPos p=base.offset(dx,0,dz);if(!inside(d,p))return false;
                if(!level.getBlockState(p).is(Blocks.GRASS_BLOCK)){
                    BlockState support=expected.get(p.above());if(!started||!level.getBlockState(p).is(Blocks.DIRT)||support==null||!level.getBlockState(p.above()).is(support.getBlock()))return false;
                }
                if(macro(type))for(int y=1;y<=7;y++){BlockPos q=p.above(y);BlockState current=level.getBlockState(q);if(started&&expected.containsKey(q)&&current.is(expected.get(q).getBlock()))continue;if(!current.isAir()&&!current.is(Blocks.GRASS))return false;}
            }
            if(started)for(var part:expected.entrySet())if(level.getBlockState(part.getKey()).is(part.getValue().getBlock()))own.add(key(part.getKey()));
            if(!macro(type)&&!liveClearance(level,base,type==TREE?5:3,own))return false;
        }
        for(Placement part:parts){BlockPos p=part.pos();if(!inside(d,p)||p.getY()<=base.getY()||p.getY()>=level.getMaxBuildHeight())return false;
            BlockState current=level.getBlockState(p);if(started&&current.is(part.state().getBlock()))continue;
            if(!level.getFluidState(p).isEmpty()||level.getBlockEntity(p)!=null||!current.isAir()&&!(type>=TREE&&current.is(Blocks.GRASS)))return false;
        }return true;
    }
    private Block flowerFor(int x,int z){return switch(Math.floorMod(x*19+z*73,5)){case 0->Blocks.POPPY;case 1->Blocks.DANDELION;case 2->Blocks.AZURE_BLUET;case 3->Blocks.OXEYE_DAISY;default->Blocks.CORNFLOWER;};}
    private boolean workBase(ServerLevel level,CompoundTag task){
        BlockPos base=pos(task);if(level.getBlockState(base).is(Blocks.GRASS_BLOCK))return true;
        if(!task.getBoolean("started")||task.getInt("type")<TREE||!level.getBlockState(base).is(Blocks.DIRT))return false;
        // Vanilla grass may decay beneath our own support while a large build is still in progress.
        for(Placement p:blueprint(level,base,task.getInt("type")))if(p.pos().equals(base.above())&&level.getBlockState(p.pos()).is(p.state().getBlock()))return true;
        return false;
    }
    private boolean taskSatisfied(ServerLevel level,ConstructorData d,CompoundTag task){
        BlockPos base=pos(task);if(!level.hasChunkAt(base)||!workBase(level,task))return false;
        for(Placement p:blueprint(level,base,task.getInt("type")))if(!level.getBlockState(p.pos()).is(p.state().getBlock())||p.state().is(Blocks.WATER)&&!level.getFluidState(p.pos()).isSource())return false;return true;
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
            if(!gardenTask(task.getInt("type"))||!inside(d,part.pos())||part.pos().getY()<=base.getY()||!workBase(level,task))jobFailures++;
            else if(current.is(part.state().getBlock())&&(!part.state().is(Blocks.WATER)||current.getFluidState().isSource())){} // resume a partially built tree/lamp without replacing its blocks
            else if((current.isAir()||task.getInt("type")>=TREE&&current.is(Blocks.GRASS)||part.state().is(Blocks.WATER)&&current.is(Blocks.WATER))&&(level.getFluidState(part.pos()).isEmpty()||part.state().is(Blocks.WATER)&&current.is(Blocks.WATER))&&level.getBlockEntity(part.pos())==null){
                if(level.setBlock(part.pos(),part.state(),3)){d.placed++;animate(server,level);}else jobFailures++;
            }else jobFailures++;
            placementDelay=WORK_DELAY_TICKS;d.setDirty();
            if(pending.isEmpty()){
                if(jobFailures>0){defer(d,task,"el sitio cambió durante la colocación");complete(d,false);}
                else{if(task.getInt("type")==TREE||task.getInt("type")==LAMP||macro(task.getInt("type"))){CompoundTag m=task.copy();m.putString("dimension",d.dimension);d.memory.add(m);}complete(d,true);}
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
        BlockPos target=pos(task),best=null;int cost=Integer.MAX_VALUE,type=task.getInt("type");Set<Long> occupied=new HashSet<>();if(macro(type))for(Placement part:blueprint(level,target,type))occupied.add(key(part.pos()));
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            if(macro(type)&&occupied.contains(key(target.getX()+dx,target.getZ()+dz)))continue;
            if(dx*dx+dz*dz>20||type==TREE&&Math.max(Math.abs(dx),Math.abs(dz))<3||type==LAMP&&Math.max(Math.abs(dx),Math.abs(dz))<2)continue;
            BlockPos p=search.nodes().get(key(target.getX()+dx,target.getZ()+dz));if(p==null||Math.abs(p.getY()-target.getY())>2||!(macro(type)&&task.getBoolean("started"))&&!visibleWork(level,p,target))continue;
            int c=search.costs().get(key(p));if(c<cost){cost=c;best=p;}
        }return best;
    }
    private boolean beginNearestTask(ServerLevel level,ConstructorData d){
        for(int i=d.taskCursor;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(!gardenTask(t.getInt("type"))||!workBase(level,t)||taskSatisfied(level,d,t)){
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
        announce(level.getServer(),level,"Pausado: no pude salir de "+x+" "+feet.getY()+" "+z+". Bloque a los pies: "+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet).getBlock())+". Conservé las tareas pendientes; usá /jardinero ir para encontrarme o /jardinero continuar para moverme a un sitio seguro.");
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
    private static List<Pair<EquipmentSlot,ItemStack>> gardenerClothes(){
        List<Pair<EquipmentSlot,ItemStack>> result=new ArrayList<>();
        ItemStack cap=new ItemStack(Items.LEATHER_HELMET),apron=new ItemStack(Items.LEATHER_CHESTPLATE),boots=new ItemStack(Items.LEATHER_BOOTS);
        ((DyeableLeatherItem)cap.getItem()).setColor(cap,0xD8BA64);((DyeableLeatherItem)apron.getItem()).setColor(apron,0x487C35);((DyeableLeatherItem)boots.getItem()).setColor(boots,0x68462E);
        result.add(Pair.of(EquipmentSlot.HEAD,cap));result.add(Pair.of(EquipmentSlot.CHEST,apron));result.add(Pair.of(EquipmentSlot.FEET,boots));result.add(Pair.of(EquipmentSlot.MAINHAND,new ItemStack(Items.IRON_HOE)));return result;
    }
    private void ensureActor(ServerLevel level){
        if(actor!=null)return;
        GameProfile profile=new GameProfile(UUID.nameUUIDFromBytes("barttolobots:constructor:v1".getBytes(StandardCharsets.UTF_8)),"Jardinero");
        ConstructorData d=ConstructorData.get(level.getServer());
        if(!d.texture.isBlank())profile.getProperties().put("textures",d.signature.isBlank()?new Property("textures",d.texture):new Property("textures",d.texture,d.signature));
        actor=new FakePlayer(level,profile){ {getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION,(byte)127);} };
        actor.setNoGravity(true);actor.setInvulnerable(true);actor.setPos(visualX,visualY,visualZ);
        for(var equipment:clothes)actor.setItemSlot(equipment.getFirst(),equipment.getSecond().copy());
    }
    private void sync(MinecraftServer server,ServerLevel level,ConstructorData d){
        ensureActor(level);actor.setPos(visualX,visualY,visualZ);
        for(ServerPlayer p:server.getPlayerList().getPlayers()){
            boolean close=p.level()==level&&p.distanceToSqr(actor)<=128*128&&p.serverLevel().hasChunkAt(actor.blockPosition());UUID id=p.getUUID();
            if(close&&listed.add(id))p.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(actor)));
            if(close&&visible.add(id)){
                p.connection.send(new ClientboundAddPlayerPacket(actor));p.connection.send(new ClientboundSetEquipmentPacket(actor.getId(),clothes));var data=actor.getEntityData().getNonDefaultValues();
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
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(p.level()==level)p.sendSystemMessage(Component.literal("Jardinero: ").withStyle(ChatFormatting.GOLD).append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
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
