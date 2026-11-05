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
        PATCH=5, FARM=6, POT=7, TORCH=8, LEAVES=9, FILL=10, WORKSITE=11, EDGE_FLOWER=12, GARDEN=13, CLEAR=14, FENCE=15, PATH=16, RAMP=17, RETAIN=18, LOOKOUT=19, REMOVE_FENCE=26;
    private static final int SCAN_BATCH=48, LAMP_SPACING=14, OPEN_LAMP_SPACING=12, WORK_DELAY_TICKS=10;
    private static final double WALK_SPEED=.16848;

    private record Placement(BlockPos pos,BlockState state,boolean force) {
        Placement(BlockPos pos,BlockState state){this(pos,state,false);}
    }
    private record LocalContext(int openLawn,int roadTiles,int buildingEdges,int flowers,int trees,int heightRange) {}
    private FakePlayer actor;
    private final Set<UUID> visible=new HashSet<>(),listed=new HashSet<>();
    private final ArrayDeque<Placement> pending=new ArrayDeque<>();
    private final Set<String> planned=new HashSet<>();
    private final Set<Long> roadReserve=new HashSet<>();
    private Map<Long,BlockPos> reachable=Map.of();
    private final Map<Long,CompoundTag> roadsideJobs=new HashMap<>();
    private final Set<BlockPos> ownedFences=new HashSet<>();
    private int sectorX=Integer.MIN_VALUE,sectorZ=Integer.MIN_VALUE;
    private final ArrayList<BlockPos> route=new ArrayList<>();
    private int routeIndex,placementDelay,taskPlacedStart,taskPlacementFailures;
    private boolean preparing,recoveringRoute;
    private int recoveryDelay,recoveryAttempts;
    private double visualX,visualY,visualZ;
    private boolean announced,visualInitialized,planLoaded,walking,movementPacketPending;

    public int create(CommandSourceStack source,int width){
        ConstructorData d=ConstructorData.get(source.getServer());
        d.dimension=source.getLevel().dimension().location().toString();
        d.centerX=(int)Math.floor(source.getPosition().x);d.centerY=(int)Math.floor(source.getPosition().y);d.centerZ=(int)Math.floor(source.getPosition().z);
        d.chestCursor=0;d.chestsFound=0;d.chestsConverted=0;d.chestsFailed=0;d.chestsBoundary=0;d.chestsDone=false;d.chestsPaused=false;
        d.auditCarry.clear();sectorX=Integer.MIN_VALUE;roadsideJobs.clear();roadReserve.clear();resetReport(d);d.roadCells.clear();d.doors.clear();d.roofs.clear();d.villageReport="pendiente";
        d.width=width;d.bounded=false;d.markerSearch=true;d.staged=true;d.workStage=0;d.cornerMarkers.clear();ownedFences.clear();d.cursor=0;d.taskCursor=0;d.placed=0;d.skipped=0;d.holesFound=0;d.roadsFound=0;d.stoneFound=0;d.configured=true;d.active=false;d.scanning=true;d.finished=false;d.auditOnly=false;d.tasks.clear();
        ServerLevel level=source.getLevel();BlockPos start=findSafeStart(level,d);
        if(start==null){d.configured=false;source.sendFailure(Component.literal("No encontré suelo natural abierto en esta zona para ubicar al Constructor. Probá desde un punto de terreno despejado."));return 0;}
        d.centerY=start.getY()+1;
        visualX=start.getX()+.5;visualY=start.getY()+1;visualZ=start.getZ()+.5;visualInitialized=true;
        recoveringRoute=false;recoveryDelay=0;recoveryAttempts=0;pending.clear();route.clear();planned.clear();planLoaded=true;announced=false;walking=false;movementPacketPending=false;resetActor(source.getServer());d.setDirty();
        source.sendSuccess(()->Component.literal("Búsqueda asignada "+width+"×"+width+". Al iniciar buscaré cuatro esquinas de bedrock antes de planificar el terreno."),false);return 1;
    }
    public int start(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        if(!d.configured){source.sendFailure(Component.literal("Primero asigná una zona con /constructor crear [32..128]."));return 0;}
        if(d.active){source.sendSuccess(()->Component.literal("El Constructor ya está trabajando; conserva su recorrido por sectores."),false);return 1;}
        // Each explicit start surveys the current world before travelling. Keep unfinished jobs across the survey.
        if(!d.scanning)restartSurvey(d);
        d.active=true;d.auditOnly=false;d.chestsPaused=false;d.setDirty();announced=false;
        source.sendSuccess(()->Component.literal(d.scanning?"Constructor quieto: empieza a analizar la zona.":"Constructor retoma el trabajo desde el suelo."),false);return 1;
    }
    private void restartSurvey(ConstructorData d){
        d.staged=true;d.workStage=0;
        for(int i=d.taskCursor;i<d.tasks.size();i++)d.auditCarry.add(d.task(i).copy());
        for(int i=0;i<d.retryTasks.size();i++)d.auditCarry.add(d.retryTasks.getCompound(i).copy());
        roadReserve.clear();roadsideJobs.clear();planned.clear();resetReport(d);d.roadCells.clear();d.doors.clear();d.roofs.clear();
        d.tasks.clear();d.cursor=0;d.taskCursor=0;d.scanning=true;d.finished=false;d.villageReport="pendiente";
        d.holesFound=0;d.roadsFound=0;d.stoneFound=0;pending.clear();route.clear();walking=false;routeIndex=0;
        recoveringRoute=false;preparing=false;recoveryAttempts=0;recoveryDelay=0;sectorX=Integer.MIN_VALUE;placementDelay=0;
    }
    private void finishSurvey(ServerLevel level,ConstructorData d){
        // Prefer newly audited columns; carry jobs not rediscovered (for example a partially built structure).
        Set<String> columns=new HashSet<>();
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);columns.add(t.getInt("type")+":"+key(t.getInt("x"),t.getInt("z")));}
        for(int i=0;i<d.auditCarry.size();i++){CompoundTag t=d.auditCarry.getCompound(i);String k=t.getInt("type")+":"+key(t.getInt("x"),t.getInt("z"));
            if(stageOf(t.getInt("type"))>=2&&t.getInt("type")!=FENCE&&inside(d,new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z")))&&!taskSatisfied(level,d,t)&&(!d.staged||stageOf(t.getInt("type"))==d.workStage)&&columns.add(k))d.tasks.add(t.copy());
        }
        // Keep jobs belonging to later stages until their fresh surface audit.
        for(int i=d.auditCarry.size()-1;i>=0;i--){CompoundTag t=d.auditCarry.getCompound(i);if(!d.staged||stageOf(t.getInt("type"))<=d.workStage)d.auditCarry.remove(i);}
        loadPlan(d);sectorX=Integer.MIN_VALUE;
    }
    /** Rebuilds a read-only task audit for the currently assigned square. */
    public int audit(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        if(!d.configured){source.sendFailure(Component.literal("Primero asigná la parcela con /constructor crear [32..128]."));return 0;}
        if(d.active){source.sendFailure(Component.literal("Pausá el Constructor antes de reiniciar la auditoría."));return 0;}
        restartSurvey(d);d.active=true;d.auditOnly=true;d.setDirty();announced=false;
        source.sendSuccess(()->Component.literal("Auditoría iniciada: revisaré la superficie y las columnas vecinas en toda la parcela, sin cambiar bloques."),false);return 1;
    }
    public int pause(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.chestsPaused=true;d.setDirty();
        source.sendSuccess(()->Component.literal("Constructor pausado. La zona y el progreso quedaron guardados."),false);return 1;
    }
    public int cancel(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());d.active=false;d.configured=false;d.finished=false;d.setDirty();
        recoveringRoute=false;recoveryDelay=0;recoveryAttempts=0;pending.clear();route.clear();planned.clear();planLoaded=false;d.auditOnly=false;resetActor(source.getServer());
        source.sendSuccess(()->Component.literal("Asignación cancelada. Los cambios ya hechos se conservan."),false);return 1;
    }
    public int status(CommandSourceStack source){
        ConstructorData d=ConstructorData.get(source.getServer());
        String state=!d.configured?"sin zona":d.finished?"terminado":d.reviewPending?"pendiente de revisión":d.auditOnly&&!d.active&&!d.scanning?"auditoría lista":!d.active?"pausado":d.scanning?(d.markerSearch?"buscando cuatro esquinas":d.auditOnly?"auditando superficie":"escaneando quieto"):"trabajando";
        int total=d.spanX()*d.spanZ();
        if((d.finished||d.reviewPending)&&!d.lastReport.isEmpty()){source.sendSuccess(()->Component.literal(d.lastReport),false);return 1;}
        String progress=d.configured?(d.scanning?" | columnas "+Math.min(d.cursor,total)+"/"+total:" | etapa "+stageName(d.workStage)+" | tareas "+Math.min(d.taskCursor,d.tasks.size())+"/"+d.tasks.size())+" | aldea "+d.villageReport+" | huecos "+d.holesFound+" | camino "+d.roadsFound+" columnas | piedra aislada "+d.stoneFound+" | huertas "+taskCount(d,FARM)+" | jardines "+taskCount(d,GARDEN)+" | obras "+taskCount(d,WORKSITE)+" | tareas "+d.tasks.size()+" | bloques "+d.placed+" | omitidos "+d.skipped+" | Lootr "+d.chestsConverted+"/"+d.chestsFound+" | cofres fallidos "+d.chestsFailed+" | dobles en límite "+d.chestsBoundary+" | conversión "+(!ConstructorLootr.available()?"Lootr ausente":d.chestsDone?"lista":d.auditOnly||d.chestsPaused?"pausada":"en curso"):"";
        source.sendSuccess(()->Component.literal("Constructor "+state+progress+" | "+(d.configured?d.dimension+" centro "+d.centerX+" "+d.centerY+" "+d.centerZ+" | "+d.areaLabel():"")),false);return 1;
    }

    public void tick(MinecraftServer server){
        ConstructorData d=ConstructorData.get(server);if(!d.configured)return;
        if(!planLoaded)loadPlan(d);
        ServerLevel level=level(server,d.dimension);if(level==null)return;
        if(d.active&&!d.scanning)ConstructorLootr.tick(level,d);
        if(!visualInitialized){BlockPos start=findSafeStart(level,d);if(start==null)return;visualX=start.getX()+.5;visualY=start.getY()+1;visualZ=start.getZ()+.5;visualInitialized=true;}
        if(d.active){if(d.scanning)scanBatch(level,d);else workTick(server,level,d);}
        sync(server,level,d);
    }

    /** Survey does not move the visible bot. It only records safe outdoor candidates. */
    private void scanBatch(ServerLevel level,ConstructorData d){
        if(d.markerSearch){scanMarkers(level,d);return;}
        if(d.cursor==0)refreshOwnedFences(level,d);
        int total=d.spanX()*d.spanZ(),stop=Math.min(total,d.cursor+SCAN_BATCH),minX=d.minX(),minZ=d.minZ();
        while(d.cursor<stop){
            int row=d.cursor/d.spanX(),col=d.cursor%d.spanX(),x=minX+col,z=minZ+row;d.cursor++;
            level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
            surveyColumn(level,d,x,z);
            BlockPos exposed=rawSurface(level,x,z);if(exposed==null||!isOutdoorGround(level,exposed)||!level.getFluidState(exposed).isEmpty())continue;
            if(isDebris(level,exposed)&&!isRoad(level,exposed))addTask(d,CLEAR,exposed,0);
            if(isRoad(level,exposed)){d.roadsFound++;}
            if(isIsolatedStone(level,exposed)&&!isRoad(level,exposed)){addTask(d,PATCH,exposed,0);d.stoneFound++;}
            BlockPos ground=surface(level,x,z);if(ground==null)continue;
            BlockState floor=level.getBlockState(ground);
            if(!isOutdoorGround(level,ground))continue;
            if(floor.is(Blocks.DIRT))addTask(d,GRASS,ground,0);
            if(!d.bounded&&canTerrace(level,ground))addTask(d,TERRACE,ground,0);

            if(d.staged&&d.workStage!=2)continue;
            planHouseDetails(level,d,ground);
            // Sample only some columns for macro sites; each candidate is scored from its local blocks.
            if(Math.floorMod(x+z-d.centerX-d.centerZ,3)==0)planContextualSite(level,d,ground);

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
        if(d.cursor>=total){
            if(!d.staged||d.workStage==0){
                if(d.bounded)planMarkedGrade(level,d);else{planSurfaceRepairs(level,d);planLandscape(level,d);}
                for(BlockPos fence:ownedFences)addTask(d,REMOVE_FENCE,fence,0);
            }
            if(!d.staged||d.workStage>0){planVillage(level,d);finalizeRoadPlan(level,d);}
            if(d.staged)for(int i=d.tasks.size()-1;i>=0;i--)if(stageOf(d.task(i).getInt("type"))!=d.workStage)d.tasks.remove(i);
            finishSurvey(level,d);d.scanning=false;d.taskCursor=0;
            if(d.auditOnly){d.active=false;announce(level.getServer(),level,"Auditoría lista: "+d.areaLabel()+", "+d.tasks.size()+" tareas en "+stageName(d.workStage)+". No cambié bloques.");}
            d.setDirty();
        }
    }

    /** Marker search is read-only, batched and persisted; never inspect the world's natural bedrock layer. */
    private void scanMarkers(ServerLevel level,ConstructorData d){
        int total=d.width*d.width,stop=Math.min(total,d.cursor+SCAN_BATCH),minX=d.centerX-d.width/2,minZ=d.centerZ-d.width/2;
        while(d.cursor<stop){int i=d.cursor++,x=minX+i%d.width,z=minZ+i/d.width;
            level.getChunk(Math.floorDiv(x,16),Math.floorDiv(z,16));
            int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z)-1;
            for(int y=top;y>=Math.max(level.getMinBuildHeight()+8,top-24);y--){BlockPos p=new BlockPos(x,y,z);
                if(level.getBlockState(p).is(Blocks.BEDROCK)){CompoundTag t=new CompoundTag();t.putInt("x",x);t.putInt("y",y);t.putInt("z",z);d.cornerMarkers.add(t);break;}
            }
        }
        if(d.cursor<total){d.setDirty();return;}
        int count=d.cornerMarkers.size();
        if(count==0){d.markerSearch=false;d.cursor=0;announce(level.getServer(),level,"No encontré marcadores de bedrock; usaré la parcela "+d.areaLabel()+". Auditoría de terreno primero.");d.setDirty();return;}
        Set<Integer> xs=new HashSet<>(),zs=new HashSet<>();Set<Long> cells=new HashSet<>();
        for(int i=0;i<count;i++){CompoundTag t=d.cornerMarkers.getCompound(i);xs.add(t.getInt("x"));zs.add(t.getInt("z"));cells.add(key(t.getInt("x"),t.getInt("z")));}
        if(count!=4||xs.size()!=2||zs.size()!=2||cells.size()!=4){
            d.active=false;d.cursor=0;d.cornerMarkers.clear();d.setDirty();
            announce(level.getServer(),level,"Marcadores inválidos: encontré "+count+" bloques de bedrock. Colocá exactamente cuatro, alineados como esquinas de un rectángulo, dentro de la búsqueda. No cambié bloques; corregilos y usá iniciar.");return;
        }
        int left=Collections.min(xs),right=Collections.max(xs),north=Collections.min(zs),south=Collections.max(zs);
        if(d.centerX<left||d.centerX>right||d.centerZ<north||d.centerZ>south||right-left<4||south-north<4){
            d.active=false;d.cursor=0;d.cornerMarkers.clear();d.setDirty();
            announce(level.getServer(),level,"El rectángulo debe contener el punto de crear y medir al menos 5×5. No cambié bloques; ajustá los marcadores y usá iniciar.");return;
        }
        d.bounded=true;d.boundMinX=left;d.boundMaxX=right;d.boundMinZ=north;d.boundMaxZ=south;
        d.centerX=left+(right-left)/2;d.centerZ=north+(south-north)/2;d.markerSearch=false;d.cursor=0;
        if(visualInitialized&&!d.contains((int)Math.floor(visualX),(int)Math.floor(visualZ))){BlockPos safe=findSafeStart(level,d);
            if(safe!=null){visualX=safe.getX()+.5;visualY=footHeight(level,safe);visualZ=safe.getZ()+.5;movementPacketPending=true;}
        }d.setDirty();
        announce(level.getServer(),level,"Cuatro esquinas aceptadas: "+d.areaLabel()+", X "+left+".."+right+", Z "+north+".."+south+". Ningún trabajo saldrá de estos límites. Primero terreno, luego caminos, decoración y vallado al final.");
    }
    private int stageOf(int type){return switch(type){case REMOVE_FENCE,CLEAR,FILL,RAMP,RETAIN,TERRACE,PATCH,GRASS->0;case PATH->1;case FENCE->3;default->2;};}
    private String stageName(int stage){return switch(stage){case 0->"terreno";case 1->"caminos";case 2->"iluminación y construcciones";default->"vallado final";};}
    private void nextStage(ServerLevel level,ConstructorData d){
        d.workStage++;d.roadsFound=0;d.tasks.clear();d.taskCursor=0;d.cursor=0;d.scanning=true;d.doors.clear();d.roofs.clear();planned.clear();
        roadsideJobs.clear();pending.clear();route.clear();walking=false;sectorX=Integer.MIN_VALUE;d.retryPass=0;d.retryBaseline=d.placed;
        d.setDirty();announce(level.getServer(),level,"Terreno/etapa anterior completada. Audito "+stageName(d.workStage)+" sobre el suelo actualizado.");
    }
    private int villageGrade(ConstructorData d){
        if(d.doors.isEmpty())return d.centerY-1;
        List<Integer> floors=new ArrayList<>();for(int i=0;i<d.doors.size();i++)floors.add(d.doors.getCompound(i).getInt("y")-1);
        Collections.sort(floors);return floors.get((floors.size()-1)/2);
    }
    private void refreshOwnedFences(ServerLevel level,ConstructorData d){
        ownedFences.clear();
        for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);BlockPos p=new BlockPos(m.getInt("x"),m.getInt("y")+1,m.getInt("z"));
            if(m.getInt("type")==FENCE&&d.dimension.equals(m.getString("dimension"))&&inside(d,p)&&level.getBlockState(p).is(villageFence(level,p.below())))ownedFences.add(p);
        }
    }
    private boolean isOwnedFence(ServerLevel level,BlockPos p){return ownedFences.contains(p)&&level.getBlockState(p).is(villageFence(level,p.below()));}
    /** Four explicit corners authorize filling the interior to the village floor, not terracing a distant mountain. */
    private void planMarkedGrade(ServerLevel level,ConstructorData d){
        int grade=villageGrade(d),columns=0,blocked=0,deep=0;
        for(int x=d.minX();x<d.minX()+d.spanX();x++)for(int z=d.minZ();z<d.minZ()+d.spanZ();z++){
            BlockPos p=terrainSurface(level,x,z);if(p==null||p.getY()>=grade)continue;
            if(grade-p.getY()>12){deep++;continue;}
            if(!validEarthColumn(level,d,p,grade)){blocked++;continue;}
            int before=d.tasks.size();addTask(d,RAMP,p,grade);if(d.tasks.size()>before)columns++;
        }
        // A complete planned fill owns these cells, so one-block terraces/plants cannot compete with it.
        Set<Long> fill=new HashSet<>();for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(t.getInt("type")==RAMP)fill.add(key(t.getInt("x"),t.getInt("z")));}
        for(int i=d.tasks.size()-1;i>=0;i--){CompoundTag t=d.task(i);if(t.getInt("type")!=RAMP&&t.getInt("type")!=REMOVE_FENCE&&fill.contains(key(t.getInt("x"),t.getInt("z"))))d.tasks.remove(i);}
        d.landscapeReport="rectángulo bedrock, nivel de aldea Y="+grade+": "+columns+" columnas a rellenar desde el fondo, "+blocked+" protegidas, "+deep+" de más de 12 bloques. Montañas sin excavar; fuera del rectángulo sin cambios";
    }

    private boolean entranceReserved(ConstructorData d,BlockPos p){
        for(int i=0;i<d.doors.size();i++){CompoundTag door=d.doors.getCompound(i);Direction f=Direction.from2DDataValue(door.getInt("facing"));
            if(Math.abs(door.getInt("y")-p.getY())<=4&&RoadLayout.entrance(p.getX(),p.getZ(),door.getInt("x"),door.getInt("z"),f.getStepX(),f.getStepZ()))return true;
        }return false;
    }
    private boolean decorConflicts(ConstructorData d,int type,BlockPos p){
        if(type==CLEAR||type==FILL||type==PATCH||type==GRASS||type==PATH)return false;
        int radius=(isMacro(type)||type==SAPLING)?4:type==LAMP?1:0;
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            BlockPos q=p.offset(dx,0,dz);if(roadReserve.contains(key(q))||entranceReserved(d,q))return true;
        }return false;
    }
    private boolean roadSoil(BlockState s){return s.is(Blocks.DIRT_PATH)||s.is(Blocks.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.ROOTED_DIRT)||s.is(Blocks.GRAVEL);}
    private boolean canRoadTile(ServerLevel level,ConstructorData d,BlockPos p){
        return inside(d,p)&&!reservedCrop(d,p)&&level.getBlockEntity(p)==null&&roadSoil(level.getBlockState(p))&&isWalkableGround(level,p);
    }
    /** Final pass runs after the complete door census. Keep the footprint persistent across restarts. */
    private void finalizeRoadPlan(ServerLevel level,ConstructorData d){
        Set<RoadLayout.Cell> seeds=new HashSet<>(),mixed=new HashSet<>();Map<RoadLayout.Cell,BlockPos> floors=new HashMap<>();
        int minX=d.minX(),minZ=d.minZ();
        for(int x=minX;x<minX+d.spanX();x++)for(int z=minZ;z<minZ+d.spanZ();z++){
            BlockPos p=surface(level,x,z);if(p==null)continue;RoadLayout.Cell c=new RoadLayout.Cell(x,z);floors.put(c,p);BlockState floor=level.getBlockState(p);
            if(floor.is(Blocks.DIRT_PATH)||isRoad(level,p))seeds.add(c);
            else if(floor.is(Blocks.COARSE_DIRT)||floor.is(Blocks.ROOTED_DIRT)||floor.is(Blocks.GRAVEL))mixed.add(c);
        }
        // Mixed brown patches belong to the path only when connected to actual path blocks.
        boolean changed=true;while(changed){changed=false;for(Iterator<RoadLayout.Cell> it=mixed.iterator();it.hasNext();){RoadLayout.Cell c=it.next();
            boolean connected=false;for(Direction dir:Direction.Plane.HORIZONTAL){RoadLayout.Cell n=new RoadLayout.Cell(c.x()+dir.getStepX(),c.z()+dir.getStepZ());
                if(seeds.contains(n)&&Math.abs(floors.get(c).getY()-floors.get(n).getY())<=1){connected=true;break;}}
            if(connected){seeds.add(c);it.remove();changed=true;}
        }}
        Set<RoadLayout.Cell> dirtPatches=new HashSet<>();
        for(var entry:floors.entrySet())if(level.getBlockState(entry.getValue()).is(Blocks.DIRT)){
            RoadLayout.Cell c=entry.getKey();int neighbors=0;
            for(Direction dir:Direction.Plane.HORIZONTAL){RoadLayout.Cell n=new RoadLayout.Cell(c.x()+dir.getStepX(),c.z()+dir.getStepZ());
                if(seeds.contains(n)&&Math.abs(floors.get(n).getY()-entry.getValue().getY())<=1)neighbors++;
            }if(neighbors>=2)dirtPatches.add(c);
        }seeds.addAll(dirtPatches);
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(t.getInt("type")==PATH)seeds.add(new RoadLayout.Cell(t.getInt("x"),t.getInt("z")));}
        RoadLayout.Plan layout=RoadLayout.plan(seeds);Set<RoadLayout.Cell> corridor=new HashSet<>(layout.corridor());corridor.addAll(seeds);
        roadReserve.clear();d.roadCells.clear();
        List<RoadLayout.Cell> ordered=new ArrayList<>(corridor);ordered.sort(Comparator.comparingInt(RoadLayout.Cell::x).thenComparingInt(RoadLayout.Cell::z));
        for(RoadLayout.Cell c:ordered){BlockPos p=floors.get(c);if(p==null||!inside(d,p))continue;
            // No trench cutting, foundations, crops or water replacement to force the requested width.
            boolean sameGrade=false;for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos n=floors.get(new RoadLayout.Cell(c.x()+dir.getStepX(),c.z()+dir.getStepZ()));
                if(n!=null&&seeds.contains(new RoadLayout.Cell(n.getX(),n.getZ()))&&Math.abs(n.getY()-p.getY())<=1){sameGrade=true;break;}}
            if(!seeds.contains(c)&&!sameGrade)continue;
            roadReserve.add(key(p));CompoundTag record=new CompoundTag();record.putInt("x",p.getX());record.putInt("z",p.getZ());d.roadCells.add(record);
            if(canRoadTile(level,d,p)&&!level.getBlockState(p).is(Blocks.DIRT_PATH))addTask(d,PATH,p,0);
        }
        // Avoid planning decoration that the repaired path, a doorway or a macro footprint will invalidate.
        for(int i=d.tasks.size()-1;i>=0;i--){CompoundTag t=d.task(i);int type=t.getInt("type");BlockPos p=new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));
            if((isLawnJob(type)&&!lawnRepairAllowed(level,d,p))||decorConflicts(d,type,p))d.tasks.remove(i);
        }
        for(RoadLayout.Axis axis:layout.axes()){
            RoadLayout.Cell c=axis.cell();
            // A stable 14-block rhythm, with a global exclusion radius also across crossings.
            if(Math.floorMod((axis.alongX()?c.x()-d.centerX:c.z()-d.centerZ),LAMP_SPACING)!=0)continue;
            BlockPos center=floors.get(c);if(center==null)continue;
            for(int side:new int[]{1,-1}){
                BlockPos base=surface(level,c.x()+(axis.alongX()?0:3*side),c.z()+(axis.alongX()?3*side:0));
                if(base!=null&&Math.abs(base.getY()-center.getY())<=1&&!decorConflicts(d,LAMP,base)&&canBuildLamp(level,d,base,0)){addTask(d,LAMP,base,0);break;}
            }
        }
        if(!d.staged||d.workStage==2)planCatalogSites(level,d);
        d.villageReport+="; caminos reservados "+d.roadCells.size()+" columnas, ancho objetivo 3; farolas separadas ≥14";
    }
    private boolean existingLampNear(ServerLevel level,BlockPos base){
        for(int dx=-LAMP_SPACING+1;dx<LAMP_SPACING;dx++)for(int dz=-LAMP_SPACING+1;dz<LAMP_SPACING;dz++){
            if(dx*dx+dz*dz>=LAMP_SPACING*LAMP_SPACING)continue;
            for(int dy=4;dy<=8;dy++){BlockPos q=base.offset(dx,dy,dz);if(level.hasChunkAt(q)&&level.getBlockState(q).is(Blocks.REDSTONE_LAMP))return true;}
        }return false;
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
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int type=t.getInt("type");if(type!=RAMP&&type!=FILL&&type!=RETAIN)continue;
            if(Math.abs(t.getInt("x")-base.getX())<=2&&Math.abs(t.getInt("z")-base.getZ())<=2)return false;
        }
        if(!inside(d,base)||!level.hasChunkAt(base)||!isOutdoorGround(level,base)||isRoad(level,base)||roadReserve.contains(key(base))||entranceReserved(d,base))return false;
        if(existingLampNear(level,base))return false;
        if(!(level.getBlockState(base).is(Blocks.GRASS_BLOCK)||level.getBlockState(base).is(Blocks.DIRT)))return false;
        for(Placement p:lampBlueprint(level,base))if(!inside(d,p.pos())||p.pos().getY()>=level.getMaxBuildHeight()||!level.hasChunkAt(p.pos())||!level.getBlockState(p.pos()).canBeReplaced())return false;
        return true;
    }
    private void addTask(ConstructorData d,int type,BlockPos p,int direction){
        String key=type+":"+p.getX()+":"+p.getY()+":"+p.getZ();
        if(!planned.add(key))return;
        if(type==FLOWER||type==EDGE_FLOWER||type==SAPLING||type==LAMP){int limit=type==SAPLING?Math.max(3,d.spanX()*d.spanZ()/400):type==LAMP?Math.max(2,d.spanX()*d.spanZ()/400):Math.max(12,d.spanX()*d.spanZ()/90);int count=0;for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int tt=t.getInt("type");if(tt==type||(type==FLOWER||type==EDGE_FLOWER)&&(tt==FLOWER||tt==EDGE_FLOWER)){count++;int dx=t.getInt("x")-p.getX(),dz=t.getInt("z")-p.getZ();if(dx*dx+dz*dz<(type==SAPLING?49:4)){planned.remove(key);return;}}}if(count>=limit){planned.remove(key);return;}}

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
        if(d.reviewPending)return;
        if(!pending.isEmpty()){
            if(placementDelay>0){placementDelay--;return;}
            Placement p=pending.removeFirst();
            if(inside(d,p.pos())&&!level.getBlockState(p.pos()).is(Blocks.BEDROCK)&&level.hasChunkAt(p.pos())&&(p.force()||level.getBlockState(p.pos()).canBeReplaced())){
                if(actor!=null){face(actor,p.pos().getX()+.5-visualX,p.pos().getZ()+.5-visualZ);actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(p.state().isAir()?net.minecraft.world.item.Items.IRON_SHOVEL:p.state().getBlock().asItem()));for(ServerPlayer viewer:server.getPlayerList().getPlayers())if(viewer.level()==level&&visible.contains(viewer.getUUID()))viewer.connection.send(new ClientboundSetEquipmentPacket(actor.getId(),List.of(com.mojang.datafixers.util.Pair.of(net.minecraft.world.entity.EquipmentSlot.MAINHAND,actor.getMainHandItem().copy()))));movementPacketPending=true;}
                if(level.setBlock(p.pos(),p.state(),3)){d.placed++;animate(server,level);}else{d.failedPlacements++;taskPlacementFailures++;}
            }else{d.skipped++;d.failedPlacements++;taskPlacementFailures++;}
            placementDelay=WORK_DELAY_TICKS;d.setDirty();if(pending.isEmpty())completeTask(d,false);return;
        }
        if(walking){
            BlockPos next=route.get(routeIndex);BlockPos checked=standingGround(level,next.getX(),next.getZ(),next.getY());
            if(!next.equals(checked)){walking=false;recoveringRoute=false;route.clear();routeIndex=0;return;}
            walkOneTick(level);
            repairRoadWhileWalking(server,level,d);
            if(routeIndex>=route.size()){walking=false;if(recoveringRoute){recoveringRoute=false;route.clear();routeIndex=0;}else prepareTask(level,d);}
            return;
        }
        if(d.taskCursor>=d.tasks.size()){
            if(!d.chestsDone)return;
            if(!d.retryTasks.isEmpty()){
                if(d.retryPass<2&&(d.retryPass==0||d.placed>d.retryBaseline)){resumePending(d,true);return;}
                d.active=false;d.finished=false;d.reviewPending=true;
            }else if(d.staged&&d.workStage<3){nextStage(level,d);return;}else{d.active=false;d.finished=true;}
            d.lastReport=completionReport(d);d.setDirty();announce(server,level,d.lastReport);return;
        }
        if(!beginNearestTask(level,d))return;
        if(!walking)prepareTask(level,d);
    }
    private boolean beginNearestTask(ServerLevel level,ConstructorData d){
        if(d.taskCursor>=d.tasks.size())return false;
        for(int i=d.taskCursor;i<d.tasks.size();i++)if(taskSatisfied(level,d,d.task(i))){
            if(i!=d.taskCursor){var t=d.tasks.get(d.taskCursor);d.tasks.set(d.taskCursor,d.tasks.get(i));d.tasks.set(i,t);}
            taskPlacedStart=d.placed;taskPlacementFailures=0;d.satisfiedJobs++;completeTask(d,false);return false;
        }
        BlockPos current=standingGround(level,(int)Math.floor(visualX),(int)Math.floor(visualZ),(int)Math.floor(visualY-.01));
        if(current==null){recoverPosition(level,d);return false;}
        recoveryAttempts=0;recoveryDelay=0;
        Map<Long,Long> parents=new HashMap<>();Map<Long,BlockPos> nodes=new HashMap<>();Map<Long,Integer> steps=new HashMap<>();ArrayDeque<BlockPos> open=new ArrayDeque<>();
        open.add(current);parents.put(key(current),key(current));nodes.put(key(current),current);steps.put(key(current),0);
        while(!open.isEmpty()){
            BlockPos at=open.removeFirst();for(Direction dir:Direction.Plane.HORIZONTAL){BlockPos q=at.relative(dir);long k=key(q);
                if(!inside(d,q)||parents.containsKey(k))continue;
                BlockPos next=navigationStep(level,d,q.getX(),q.getZ(),at.getY());if(next==null)continue;
                parents.put(k,key(at));nodes.put(k,next);steps.put(k,steps.get(key(at))+1);open.addLast(next);
            }
        }
        reachable=nodes;
        int best=-1,bestPriority=Integer.MAX_VALUE,bestSector=2,bestCleanup=2;double bestDistance=Double.MAX_VALUE;BlockPos goal=null;
        for(int i=d.taskCursor;i<d.tasks.size();i++){
            CompoundTag task=d.task(i);BlockPos approach=approachFor(level,d,task);if(approach==null)continue;
            int sx=Math.floorDiv(task.getInt("x")-d.minX(),16),sz=Math.floorDiv(task.getInt("z")-d.minZ(),16);
            int sameSector=(sx==sectorX&&sz==sectorZ)?0:1,pri=priority(task.getInt("type"));double dist=steps.get(key(approach));
            // Finish reachable work in the current 16x16 sector; choose the next by actual route length.
            int cleanup=task.getInt("type")==REMOVE_FENCE?0:1;
            boolean better=cleanup<bestCleanup||cleanup==bestCleanup&&(sameSector<bestSector||sameSector==bestSector&&(sameSector==0?(pri<bestPriority||pri==bestPriority&&dist<bestDistance):(dist<bestDistance||dist==bestDistance&&pri<bestPriority)));
            if(better){bestCleanup=cleanup;best=i;bestPriority=pri;bestSector=sameSector;bestDistance=dist;goal=approach;}
        }
        reachable=Map.of();
        if(best<0){for(int i=d.taskCursor;i<d.tasks.size();i++){d.inaccessibleJobs++;deferJob(d,d.task(i),accessReason(level,d.task(i),nodes.size()));}d.skipped+=d.tasks.size()-d.taskCursor;d.taskCursor=d.tasks.size();d.setDirty();return false;}
        int start=d.taskCursor;if(best!=start){var a=d.tasks.get(start);d.tasks.set(start,d.tasks.get(best));d.tasks.set(best,a);}
        CompoundTag chosen=d.task(start);sectorX=Math.floorDiv(chosen.getInt("x")-d.minX(),16);sectorZ=Math.floorDiv(chosen.getInt("z")-d.minZ(),16);
        recoveringRoute=false;route.clear();for(long k=key(goal);k!=key(current);k=parents.get(k))route.add(nodes.get(k));Collections.reverse(route);routeIndex=0;walking=!route.isEmpty();return true;
    }
    /** Repair only audited road tiles within one block of the worker, without interrupting its route. */
    private void repairRoadWhileWalking(MinecraftServer server,ServerLevel level,ConstructorData d){
        if(placementDelay>0){placementDelay--;return;}
        int x=(int)Math.floor(visualX),z=(int)Math.floor(visualZ);
        for(int[] offset:new int[][]{{0,0},{1,0},{-1,0},{0,1},{0,-1}}){
            CompoundTag job=roadsideJobs.get(key(x+offset[0],z+offset[1]));if(job==null)continue;
            BlockPos p=new BlockPos(job.getInt("x"),job.getInt("y"),job.getInt("z"));
            if(Math.abs(footHeight(level,p)-visualY)>1.2||level.getBlockState(p).is(Blocks.DIRT_PATH)||!canRoadTile(level,d,p))continue;
            if(level.setBlock(p,Blocks.DIRT_PATH.defaultBlockState(),3)){
                d.placed++;String name=Integer.toString(PATH);d.completed.putInt(name,d.completed.getInt(name)+1);
                placementDelay=WORK_DELAY_TICKS;animate(server,level);d.setDirty();
            }return;
        }
    }
    private void walkOneTick(ServerLevel level){
        if(routeIndex>=route.size())return;
        BlockPos next=route.get(routeIndex);double tx=next.getX()+.5,ty=footHeight(level,next),tz=next.getZ()+.5;
        double dx=tx-visualX,dy=ty-visualY,dz=tz-visualZ,dist=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(dist<=WALK_SPEED){visualX=tx;visualY=ty;visualZ=tz;routeIndex++;if(actor!=null)face(actor,dx,dz);}
        else{double scale=WALK_SPEED/dist;visualX+=dx*scale;visualY+=dy*scale;visualZ+=dz*scale;if(actor!=null)face(actor,dx,dz);}
        movementPacketPending=true;if(actor!=null)actor.setPos(visualX,visualY,visualZ);
    }
    private void prepareTask(ServerLevel level,ConstructorData d){
        if(d.taskCursor>=d.tasks.size())return;
        CompoundTag task=d.task(d.taskCursor);int type=task.getInt("type"),x=task.getInt("x"),y=task.getInt("y"),z=task.getInt("z"),direction=task.getInt("direction");
        BlockPos ground=new BlockPos(x,y,z);
        if(taskSatisfied(level,d,task)){taskPlacedStart=d.placed;taskPlacementFailures=0;d.satisfiedJobs++;completeTask(d,false);return;}
        if(isLawnJob(type)&&!lawnRepairAllowed(level,d,ground)){taskPlacedStart=d.placed;taskPlacementFailures=0;d.satisfiedJobs++;completeTask(d,false);return;}
        if((type==PATH&&level.getBlockState(ground).is(Blocks.DIRT_PATH))||(type==GRASS&&level.getBlockState(ground).is(Blocks.GRASS_BLOCK))){taskPlacedStart=d.placed;taskPlacementFailures=0;d.satisfiedJobs++;completeTask(d,false);return;}
        if(!level.hasChunkAt(ground)||(type!=REMOVE_FENCE&&type!=PATH&&type!=FILL&&type!=RAMP&&type!=RETAIN&&!isOutdoorGround(level,ground))){completeTask(d,true);return;}
        if(type!=REMOVE_FENCE&&type!=PATH&&type!=CLEAR&&type!=FILL&&type!=PATCH&&type!=GRASS&&decorConflicts(d,type,ground)){completeTask(d,true);return;}
        preparing=true;taskPlacedStart=d.placed;taskPlacementFailures=0;
        if(actor!=null)face(actor,x+.5-visualX,z+.5-visualZ);movementPacketPending=true;
        if(type==REMOVE_FENCE){if(isOwnedFence(level,ground)){
            int grade=villageGrade(d);pending.add(new Placement(ground,ground.getY()>grade?Blocks.AIR.defaultBlockState():ground.getY()==grade?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.DIRT.defaultBlockState(),true));
        }}
        else if(type==RAMP||type==RETAIN){BlockPos current=terrainSurface(level,x,z);
            if(current!=null&&earth(level.getBlockState(current))&&current.getY()>=direction){d.satisfiedJobs++;completeTask(d,false);return;}
            if(current!=null&&validEarthColumn(level,d,current,direction))queueEarthColumn(level,d,current,direction,type==RETAIN);
        }
        else if(type==LOOKOUT){if(canLookout(level,d,ground))planLookout(level,ground,direction);}
        else if(type==CLEAR){if(isDebris(level,ground))pending.add(new Placement(ground,Blocks.AIR.defaultBlockState(),true));}
        else if(type==FENCE){if(canFence(level,d,ground))pending.add(new Placement(ground.above(),villageFence(level,ground).defaultBlockState()));}
        else if(type==PATH){if(canRoadTile(level,d,ground))pending.add(new Placement(ground,Blocks.DIRT_PATH.defaultBlockState(),true));}
        else if(type==GRASS){if(level.getBlockState(ground).is(Blocks.DIRT))pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState(),true));}
        else if(type==TERRACE){if(canTerrace(level,ground))pending.add(new Placement(ground.above(),Blocks.GRASS_BLOCK.defaultBlockState()));}
        else if(type==FLOWER){if(clearForPlant(level,ground))pending.add(new Placement(ground.above(),flowerFor(x,z).defaultBlockState()));}
        else if(type==SAPLING){if(clearForTree(level,ground))pending.add(new Placement(ground.above(),saplingFor(level,ground).defaultBlockState()));}
        else if(type==PATCH){if(isIsolatedStone(level,ground))pending.add(new Placement(ground,Blocks.GRASS_BLOCK.defaultBlockState(),true));}
        else if(type==FILL){BlockPos current=terrainSurface(level,x,z);
            if(current!=null&&fillMaterial(level.getBlockState(current))&&current.getY()>=direction){d.satisfiedJobs++;completeTask(d,false);return;}
            if(current!=null&&validBasinColumn(level,d,current,direction))queueEarthColumn(level,d,current,direction,false);
        }
        else if(type==EDGE_FLOWER){if(canEdgeFlower(level,ground))pending.add(new Placement(ground.above(),flowerFor(x,z).defaultBlockState()));}
        else if(type==WORKSITE){if(canWorksite(level,ground,d))planWorksite(level,ground);}
        else if(type==GARDEN){if(canGarden(level,ground,d))planGarden(level,ground);}
        else if(type==FARM){if(canFarm(level,ground,d))planCatalogBlocks(level,ground,FARM,direction);}
        else if(VillageCatalog.contains(type)){if(canCatalogSite(level,d,ground))planCatalogBlocks(level,ground,type,direction);}
        else if(type==POT){if(canPlacePot(level,ground))pending.add(new Placement(ground.above(),Blocks.POTTED_POPPY.defaultBlockState()));}
        else if(type==TORCH){if(canPlaceTorch(level,ground))pending.add(new Placement(ground.above(),Blocks.TORCH.defaultBlockState()));}
        else if(type==LEAVES){if(canPlaceLeaves(level,ground)){
            pending.add(new Placement(ground.above(),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true)));
            // Low corner hedge: preserve headroom and avoid enclosing the worker.
        }}
        else if(type==LAMP){
            if(!canBuildLamp(level,d,ground,direction)){completeTask(d,true);return;}
            planLampBlocks(level,ground,direction);
        }
        if(type==GARDEN||type==WORKSITE||type==FARM||VillageCatalog.contains(type))orientSite(level,ground);
        preparing=false;
        if(pending.isEmpty())completeTask(d,true);
    }
    private void planLampBlocks(ServerLevel level,BlockPos base,int ignored){pending.addAll(lampBlueprint(level,base));}
    private List<VillageCatalog.Part> catalogBlueprint(ServerLevel level,BlockPos p,int type,int variant){
        return VillageCatalog.build(type,variant,villageFence(level,p),villageWood(level,p,"log"),villageWood(level,p,"slab"),villageWood(level,p,"stairs"),villageWood(level,p,"trapdoor"));
    }
    private void planCatalogBlocks(ServerLevel level,BlockPos center,int type,int variant){
        List<VillageCatalog.Part> recipe=catalogBlueprint(level,center,type,variant);
        // Every site reserves its complete 7x7x5 envelope, so rotation cannot exceed the validated area.
        for(VillageCatalog.Part part:recipe){BlockPos p=center.offset(part.offset());
            if(part.offset().getY()<=0&&(level.getBlockEntity(p)!=null||!(level.getBlockState(p).isAir()||earth(level.getBlockState(p)))))return;
        }
        for(VillageCatalog.Part part:recipe)pending.add(new Placement(center.offset(part.offset()),part.state(),part.offset().getY()<=0));
    }
    private boolean canCatalogSite(ServerLevel level,ConstructorData d,BlockPos c){
        if(decorConflicts(d,WORKSITE,c))return false;
        for(int i=0;i<d.tasks.size();i++){
            if(preparing&&i==d.taskCursor)continue;CompoundTag t=d.task(i);int type=t.getInt("type");
            int dx=t.getInt("x")-c.getX(),dz=t.getInt("z")-c.getZ(),distance=dx*dx+dz*dz;
            if(isMacro(type)&&distance<14*14)return false;
            if(type==FENCE&&Math.abs(dx)<=4&&Math.abs(dz)<=4)return false;
            if((type==LAMP||type==SAPLING)&&distance<7*7)return false;
            if((type==RAMP||type==RETAIN||type==FILL||type==TERRACE)&&distance<6*6)return false;
        }
        for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);if(!d.dimension.equals(m.getString("dimension"))||!isMacro(m.getInt("type")))continue;
            int dx=m.getInt("x")-c.getX(),dz=m.getInt("z")-c.getZ();if(dx*dx+dz*dz<14*14)return false;
        }
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos p=c.offset(dx,0,dz);if(!inside(d,p)||!level.hasChunkAt(p)||!level.getBlockState(p).is(Blocks.GRASS_BLOCK)||isRoad(level,p)||isNearBuilding(level,p))return false;
            if(!level.canSeeSky(p.above()))return false;
            for(int y=1;y<=5;y++)if(!level.getBlockState(p.above(y)).isAir()||!level.getFluidState(p.above(y)).isEmpty())return false;
            if(level.getBlockEntity(p.below())!=null||!(level.getBlockState(p.below()).isAir()||earth(level.getBlockState(p.below()))))return false;
        }return true;
    }
    private void planCatalogSites(ServerLevel level,ConstructorData d){
        int limit=Math.max(2,d.spanX()*d.spanZ()/1500),added=0;
        int minX=d.minX(),minZ=d.minZ();
        // Spread families through the parcel; prefer available plots near the road without occupying its verge.
        for(int type=VillageCatalog.BENCH;type<=VillageCatalog.POND&&added<limit;type++){
            BlockPos best=null;int bestScore=Integer.MIN_VALUE;
            for(int x=minX+4;x<minX+d.spanX()-4;x+=4)for(int z=minZ+4;z<minZ+d.spanZ()-4;z+=4){
                BlockPos p=surface(level,x,z);if(p==null||!canCatalogSite(level,d,p))continue;
                int roadDistance=d.width;
                for(int j=0;j<d.roadCells.size();j++){CompoundTag r=d.roadCells.getCompound(j);roadDistance=Math.min(roadDistance,Math.abs(x-r.getInt("x"))+Math.abs(z-r.getInt("z")));}
                int desired=type==VillageCatalog.POND?16:10;
                int score=100-3*Math.abs(roadDistance-desired)+Math.floorMod(x*31+z*17+type,7);
                if(score>bestScore){best=p;bestScore=score;}
            }
            if(best!=null){int before=d.tasks.size();addTask(d,type,best,Math.floorMod(best.getX()*31+best.getZ()*17,2));if(d.tasks.size()>before){added++;
                for(int i=d.tasks.size()-2;i>=0;i--){CompoundTag t=d.task(i);int tt=t.getInt("type");if(isMacro(tt)||tt==PATH||tt==LAMP||tt==FENCE)continue;
                    if(Math.abs(t.getInt("x")-best.getX())<=3&&Math.abs(t.getInt("z")-best.getZ())<=3)d.tasks.remove(i);
                }
            }}
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
        if(bestType>=0)addTask(d,bestType,ground,bestType==FARM?Math.floorMod(ground.getX()*31+ground.getZ()*17,3):0);
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
            if(isMacro(type)){int dx=t.getInt("x")-center.getX(),dz=t.getInt("z")-center.getZ();if(dx*dx+dz*dz<100)return false;}
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
    private void completeTask(ConstructorData d,boolean skipped){preparing=false;
        if(!skipped&&taskPlacementFailures==0&&d.taskCursor<d.tasks.size()&&d.task(d.taskCursor).getInt("type")==REMOVE_FENCE){
            CompoundTag job=d.task(d.taskCursor);BlockPos p=new BlockPos(job.getInt("x"),job.getInt("y"),job.getInt("z"));ownedFences.remove(p);
            for(int i=d.memory.size()-1;i>=0;i--){CompoundTag m=d.memory.getCompound(i);if(m.getInt("type")==FENCE&&d.dimension.equals(m.getString("dimension"))&&m.getInt("x")==p.getX()&&m.getInt("z")==p.getZ()&&m.getInt("y")+1==p.getY())d.memory.remove(i);}
        }
if(d.taskCursor<d.tasks.size()){CompoundTag job=d.task(d.taskCursor);if(skipped){d.invalidJobs++;deferJob(d,job,"sitio cambió o dejó de cumplir condiciones");}else if(taskPlacementFailures>0){d.partialJobs++;deferJob(d,job,"colocación parcial: bloques ocupados o rechazados");}else if(d.placed>taskPlacedStart){String key=Integer.toString(job.getInt("type"));d.completed.putInt(key,d.completed.getInt(key)+1);}}if(!skipped&&taskPlacementFailures==0&&d.placed>taskPlacedStart&&d.taskCursor<d.tasks.size()){CompoundTag t=d.task(d.taskCursor);int type=t.getInt("type");if(type==LAMP||isMacro(type)||type==FENCE){CompoundTag m=t.copy();m.putString("dimension",d.dimension);d.memory.add(m);}}if(skipped)d.skipped++;d.taskCursor++;placementDelay=0;pending.clear();route.clear();routeIndex=0;walking=false;d.setDirty();}

    private List<BlockPos> findPath(ServerLevel level,ConstructorData d,BlockPos start,BlockPos goal){
        int minX=d.minX(),minZ=d.minZ(),maxX=minX+d.spanX()-1,maxZ=minZ+d.spanZ()-1;
        long startKey=key(start),goalKey=key(goal);if(startKey==goalKey)return List.of();
        ArrayDeque<BlockPos> open=new ArrayDeque<>();Map<Long,Long> parent=new HashMap<>();Map<Long,BlockPos> nodes=new HashMap<>();
        open.add(start);parent.put(startKey,startKey);nodes.put(startKey,start);
        int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
        while(!open.isEmpty()){
            BlockPos at=open.removeFirst();long atKey=key(at);
            for(int[] dir:dirs){
                int x=at.getX()+dir[0],z=at.getZ()+dir[1];if(x<minX||x>maxX||z<minZ||z>maxZ)continue;
                long k=key(x,z);if(parent.containsKey(k))continue;
                BlockPos next=navigationStep(level,d,x,z,at.getY());if(next==null)continue;
                parent.put(k,atKey);nodes.put(k,next);
                if(k==goalKey){ArrayList<BlockPos> result=new ArrayList<>();long cursor=k;while(cursor!=startKey){result.add(nodes.get(cursor));cursor=parent.get(cursor);}Collections.reverse(result);return result;}
                open.addLast(next);
            }
        }return null;
    }
    private BlockPos approachFor(ServerLevel level,ConstructorData d,CompoundTag task){
        BlockPos target=new BlockPos(task.getInt("x"),task.getInt("y"),task.getInt("z"));
        int type=task.getInt("type"),direction=task.getInt("direction");
        if(type==RAMP||type==RETAIN||type==FILL||type==PATH||type==REMOVE_FENCE){
            BlockPos best=null;double score=Double.MAX_VALUE;
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
                if((dx==0&&dz==0&&type!=PATH)||dx*dx+dz*dz>20)continue;
                BlockPos p=reachable.isEmpty()?surface(level,target.getX()+dx,target.getZ()+dz):reachable.get(key(target.getX()+dx,target.getZ()+dz));
                if(p==null||!inside(d,p)||(!reachable.isEmpty()&&!reachable.containsKey(key(p))))continue;
                if(type==PATH?Math.abs(p.getY()-target.getY())>2:type==REMOVE_FENCE?Math.abs(p.getY()-target.getY())>12:Math.abs(p.getY()-direction)>12)continue;
                if(type==PATH&&!roadWorkVisible(level,p,target))continue;
                double distance=(p.getX()+.5-visualX)*(p.getX()+.5-visualX)+(p.getZ()+.5-visualZ)*(p.getZ()+.5-visualZ)+.02*(dx*dx+dz*dz);
                if(distance<score){best=p;score=distance;}
            }return best;
        }
        int[][] dirs=(type==WORKSITE||type==GARDEN||type==FARM||VillageCatalog.contains(type))?new int[][]{{4,0},{-4,0},{0,4},{0,-4},{4,1},{4,-1},{-4,1},{-4,-1},{1,4},{-1,4},{1,-4},{-1,-4}}:type==LOOKOUT?new int[][]{{3,0},{-3,0},{0,3},{0,-3}}:(type==RAMP||type==RETAIN)?new int[][]{{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}}:type==LAMP?new int[][]{{0,2},{0,-2},{2,0},{-2,0},{0,3},{0,-3},{3,0},{-3,0}}:new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
        BlockPos best=null;double bestDistance=Double.MAX_VALUE;
        for(int[] dir:dirs){BlockPos p=reachable.isEmpty()?surface(level,target.getX()+dir[0],target.getZ()+dir[1]):reachable.get(key(target.getX()+dir[0],target.getZ()+dir[1]));if(p==null||!inside(d,p)||(!reachable.isEmpty()&&!reachable.containsKey(key(p))))continue;
            if(Math.abs(p.getY()-target.getY())>2&&type!=RAMP&&type!=RETAIN)continue;
            double dx=p.getX()+.5-visualX,dz=p.getZ()+.5-visualZ,dist=dx*dx+dz*dz;
            if(dist<bestDistance){best=p;bestDistance=dist;}}
        return best;
    }

    private boolean isMacro(int type){return type==FARM||type==WORKSITE||type==GARDEN||type==LOOKOUT||VillageCatalog.contains(type);}
    private boolean isLawnJob(int type){return type==GRASS||type==PATCH||type==TERRACE;}
    private boolean lawnRepairAllowed(ServerLevel level,ConstructorData d,BlockPos p){
        if(roadReserve.contains(key(p))||level.getBlockState(p).is(Blocks.DIRT_PATH)||level.getBlockState(p).is(Blocks.FARMLAND))return false;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            if(roadReserve.contains(key(p.getX()+dx,p.getZ()+dz)))return false;
            BlockPos q=surface(level,p.getX()+dx,p.getZ()+dz);
            if(q!=null&&Math.abs(q.getY()-p.getY())<=1&&level.getBlockState(q).is(Blocks.DIRT_PATH))return false;
        }return true;
    }
    /** Crops have no blocking collision even though they are not replaceable plants. Never harvest them. */
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
            if(nativeGround!=null&&Math.abs(nativeGround.getY()-p.getY())<=1)return p;
        }return null;
    }
    private double footHeight(ServerLevel level,BlockPos p){
        var shape=level.getBlockState(p).getCollisionShape(level,p);
        return p.getY()+(shape.isEmpty()?1:Math.min(1,shape.max(Direction.Axis.Y)));
    }
    private boolean roadWorkVisible(ServerLevel level,BlockPos from,BlockPos target){
        // Work within reach from a sidewalk/diagonal, but do not act through a house wall or foliage.
        int dx=target.getX()-from.getX(),dz=target.getZ()-from.getZ(),steps=Math.max(Math.abs(dx),Math.abs(dz));
        for(int i=1;i<steps;i++){
            int x=from.getX()+(int)Math.round((double)dx*i/steps),z=from.getZ()+(int)Math.round((double)dz*i/steps);
            int y=(int)Math.floor(from.getY()+1+(double)(target.getY()-from.getY())*i/steps);
            BlockPos q=new BlockPos(x,y,z);BlockState s=level.getBlockState(q);
            if(!s.getCollisionShape(level,q).isEmpty()&&!earth(s)&&!s.is(Blocks.DIRT_PATH))return false;
        }return true;
    }
    private String accessReason(ServerLevel level,CompoundTag task,int reachableCount){
        BlockPos p=new BlockPos(task.getInt("x"),task.getInt("y"),task.getInt("z"));
        if(!level.hasChunkAt(p))return "chunk del trabajo no cargado; conservado para reintento";
        return "sin posición de trabajo conectada; suelo "+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock())+", "+reachableCount+" posiciones transitables revisadas";
    }
    private BlockPos surface(ServerLevel level,int x,int z){
        BlockPos probe=new BlockPos(x,level.getMinBuildHeight(),z);if(!level.hasChunkAt(probe))return null;
        BlockPos ground=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,probe).below();
        for(int depth=0;depth<=16&&ground.getY()>=level.getMinBuildHeight();depth++,ground=ground.below())
            if(isWalkableGround(level,ground))return ground;
        return null;
    }
    private BlockPos findSafeStart(ServerLevel level,ConstructorData d){
        for(int radius=0;radius<=8;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            if(Math.abs(dx)+Math.abs(dz)!=radius)continue;
            int x=d.centerX+dx,z=d.centerZ+dz;
            if(!d.contains(x,z))continue;
            BlockPos p=surface(level,x,z);if(p!=null)return p;
        }return null;
    }
    private boolean isWalkableGround(ServerLevel level,BlockPos ground){
        if(!navigationHeadroom(level,ground))return false;
        BlockState floor=level.getBlockState(ground);
        return floor.is(Blocks.GRASS_BLOCK)||floor.is(Blocks.DIRT)||floor.is(Blocks.COARSE_DIRT)||floor.is(Blocks.PODZOL)
            ||floor.is(Blocks.ROOTED_DIRT)||floor.is(Blocks.MYCELIUM)||floor.is(Blocks.DIRT_PATH)||floor.is(Blocks.GRAVEL)
            ||floor.is(Blocks.MOSS_BLOCK)||floor.is(Blocks.SAND)||floor.is(Blocks.RED_SAND)||floor.is(Blocks.FARMLAND)||floor.is(Blocks.STONE)||floor.is(Blocks.ANDESITE)||floor.is(Blocks.GRANITE)||floor.is(Blocks.DIORITE);
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
            BlockPos n=surface(level,p.getX()+dir[0],p.getZ()+dir[1]);
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
        int open=0;for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if((dx!=0||dz!=0)&&level.getBlockState(ground.offset(dx,1,dz)).isAir()&&level.getBlockState(ground.offset(dx,2,dz)).isAir())open++;
        return walls>=2&&log&&open>=6;
    }
    private boolean canFarm(ServerLevel level,BlockPos center,ConstructorData d){
        if(!insideFarmMargin(center,d)||!canCatalogSite(level,d,center))return false;
        return true;
    }
    private boolean insideFarmMargin(BlockPos p,ConstructorData d){
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)if(!inside(d,p.offset(dx,0,dz)))return false;
        return taskCount(d,FARM)<Math.max(1,d.width/64);
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
        if(s.is(Blocks.STONE)){
            for(int[] axis:new int[][]{{1,0},{0,1}}){
                boolean ends=true;for(int side:new int[]{-1,1}){boolean verge=false;for(int distance=1;distance<=3;distance++){
                    BlockPos q=surface(level,top.getX()+axis[0]*distance*side,top.getZ()+axis[1]*distance*side);
                    if(q!=null&&q.getY()==top.getY()&&(level.getBlockState(q).is(Blocks.GRASS_BLOCK)||level.getBlockState(q).is(Blocks.DIRT))){verge=true;break;}
                }ends&=verge;}if(ends)return true;
            }return false;
        }
        if(!isRoadMaterial(s))return false;
        int connected=0;boolean east=false,west=false,north=false,south=false;
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos n=surface(level,p.getX()+dir[0],p.getZ()+dir[1]);
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
    private boolean inside(ConstructorData d,BlockPos p){return d.contains(p.getX(),p.getZ());}

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
        ownedFences.clear();for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);BlockPos p=new BlockPos(m.getInt("x"),m.getInt("y")+1,m.getInt("z"));if(m.getInt("type")==FENCE&&d.dimension.equals(m.getString("dimension"))&&inside(d,p))ownedFences.add(p);}
        roadReserve.clear();for(int i=0;i<d.roadCells.size();i++){CompoundTag c=d.roadCells.getCompound(i);roadReserve.add(key(c.getInt("x"),c.getInt("z")));}
        roadsideJobs.clear();planned.clear();for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(t.getInt("type")==PATH)roadsideJobs.put(key(t.getInt("x"),t.getInt("z")),t);planned.add(t.getInt("type")+":"+t.getInt("x")+":"+t.getInt("y")+":"+t.getInt("z"));}planLoaded=true;
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
    private int priority(int type){return switch(type){case REMOVE_FENCE->-1;case CLEAR,FILL,RAMP,RETAIN->0;case PATH->1;case TERRACE,PATCH,GRASS->6;case LAMP,TORCH->2;case FARM,WORKSITE,GARDEN,LOOKOUT,20,21,22,23,24,25->3;case FENCE->9;default->4;};}
    private boolean remembered(ConstructorData d,int type,BlockPos p){
        if(type==RAMP||type==FILL||type==RETAIN)return terrainFootprintReserved(d,p);
        for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);if(!d.dimension.equals(m.getString("dimension")))continue;
            int dx=m.getInt("x")-p.getX(),dz=m.getInt("z")-p.getZ(),mt=m.getInt("type");
            if(isMacro(mt)&&dx*dx+dz*dz<196)return true;
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
        if(d.doors.size()<2&&!d.bounded){d.villageReport="sin suficientes casas; sin perímetro";return;}
        int left=Integer.MAX_VALUE,right=Integer.MIN_VALUE,north=Integer.MAX_VALUE,south=Integer.MIN_VALUE;
        for(int i=0;i<d.doors.size();i++){CompoundTag t=d.doors.getCompound(i);int x=t.getInt("x"),z=t.getInt("z");left=Math.min(left,x);right=Math.max(right,x);north=Math.min(north,z);south=Math.max(south,z);planEntranceLink(level,d,t);}
        for(int i=0;i<d.roofs.size();i++){CompoundTag r=d.roofs.getCompound(i);boolean near=false;for(int j=0;j<d.doors.size();j++){CompoundTag h=d.doors.getCompound(j);int dx=r.getInt("x")-h.getInt("x"),dz=r.getInt("z")-h.getInt("z");if(dx*dx+dz*dz<=144){near=true;break;}}
            if(near){left=Math.min(left,r.getInt("x"));right=Math.max(right,r.getInt("x"));north=Math.min(north,r.getInt("z"));south=Math.max(south,r.getInt("z"));}}
        if(d.bounded){left=d.minX();right=left+d.spanX()-1;north=d.minZ();south=north+d.spanZ()-1;}else{left-=3;right+=3;north-=3;south+=3;}int cx=(left+right)/2,cz=(north+south)/2;
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
    private boolean wildGroundCover(BlockState s){return s.is(Blocks.GRASS)||s.is(Blocks.TALL_GRASS)||s.is(Blocks.FERN)||s.is(Blocks.LARGE_FERN)||s.is(Blocks.DEAD_BUSH)||s.is(Blocks.SNOW);}
    /** Terrain may replace natural water/lava and foliage, never waterlogged construction, crops or logs. */
    private boolean terrainCover(ServerLevel level,BlockPos p){
        BlockState s=level.getBlockState(p);
        if(level.getBlockEntity(p)!=null||s.getBlock() instanceof net.minecraft.world.level.block.CropBlock||s.is(BlockTags.LOGS))return false;
        return isOwnedFence(level,p)||s.isAir()||wildGroundCover(s)||s.is(BlockTags.LEAVES)||s.is(BlockTags.FLOWERS)
            ||s.is(Blocks.PINK_PETALS)||s.is(Blocks.VINE)||s.is(Blocks.GLOW_LICHEN)||s.is(Blocks.LILY_PAD)
            ||s.is(Blocks.SEAGRASS)||s.is(Blocks.TALL_SEAGRASS)||s.is(Blocks.KELP)||s.is(Blocks.KELP_PLANT)
            ||s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
    }
    private BlockPos terrainSurface(ServerLevel level,int x,int z){
        BlockPos p=rawSurface(level,x,z);if(p==null)return null;
        for(int n=0;n<32&&p.getY()>level.getMinBuildHeight()&&terrainCover(level,p);n++)p=p.below();
        return p;
    }
    private boolean openTerrainColumn(ServerLevel level,BlockPos p){
        int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ());
        for(int y=p.getY()+1;y<=top;y++)if(!terrainCover(level,new BlockPos(p.getX(),y,p.getZ())))return false;
        return true;
    }
    private boolean fillMaterial(BlockState s){return earth(s)||s.is(Blocks.SAND)||s.is(Blocks.RED_SAND)||s.is(Blocks.CLAY)||s.is(Blocks.MUD)||s.is(Blocks.COBBLESTONE)||s.is(Blocks.GRAVEL)||s.is(Blocks.ROOTED_DIRT)||s.is(Blocks.PODZOL);}
    private boolean terrainFootprintReserved(ConstructorData d,BlockPos p){
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int type=t.getInt("type"),r=isMacro(type)?3:type==LAMP?1:-1;
            if(r>=0&&Math.abs(t.getInt("x")-p.getX())<=r&&Math.abs(t.getInt("z")-p.getZ())<=r)return true;
        }
        for(int i=0;i<d.memory.size();i++){CompoundTag t=d.memory.getCompound(i);if(!d.dimension.equals(t.getString("dimension")))continue;int type=t.getInt("type"),r=isMacro(type)?3:type==LAMP?1:-1;
            if(r>=0&&Math.abs(t.getInt("x")-p.getX())<=r&&Math.abs(t.getInt("z")-p.getZ())<=r)return true;
        }return false;
    }
    private boolean validBasinColumn(ServerLevel level,ConstructorData d,BlockPos p,int target){
        if(!inside(d,p)||target<=p.getY()||target-p.getY()>6||!fillMaterial(level.getBlockState(p))||terrainFootprintReserved(d,p)||!openTerrainColumn(level,p))return false;
        for(int y=p.getY()+1;y<=target+2;y++){BlockPos q=new BlockPos(p.getX(),y,p.getZ());BlockState s=level.getBlockState(q);if(!terrainCover(level,q))return false;}
        return true;
    }
    private void queueEarthColumn(ServerLevel level,ConstructorData d,BlockPos p,int target,boolean stone){
        for(int y=target+2;y>target;y--){BlockPos cover=new BlockPos(p.getX(),y,p.getZ());if(!level.getBlockState(cover).isAir()&&terrainCover(level,cover))pending.add(new Placement(cover,Blocks.AIR.defaultBlockState(),true));}
        for(int y=p.getY()+1;y<=target;y++)pending.add(new Placement(new BlockPos(p.getX(),y,p.getZ()),stone?Blocks.COBBLESTONE.defaultBlockState():y==target?(roadReserve.contains(key(p))?Blocks.DIRT_PATH.defaultBlockState():Blocks.GRASS_BLOCK.defaultBlockState()):Blocks.DIRT.defaultBlockState(),true));
    }
    private void planSurfaceRepairs(ServerLevel level,ConstructorData d){
        Map<SurfaceBasinPlan.Cell,Integer> heights=new HashMap<>();Map<SurfaceBasinPlan.Cell,BlockPos> bases=new HashMap<>();Set<SurfaceBasinPlan.Cell> protectedCells=new HashSet<>();
        int minX=d.minX(),minZ=d.minZ();
        for(int x=minX;x<minX+d.spanX();x++)for(int z=minZ;z<minZ+d.spanZ();z++){
            BlockPos p=terrainSurface(level,x,z);if(p==null)continue;var c=new SurfaceBasinPlan.Cell(x,z);heights.put(c,p.getY());bases.put(c,p);
            if(!fillMaterial(level.getBlockState(p))||!openTerrainColumn(level,p)||level.getBlockEntity(p)!=null||terrainFootprintReserved(d,p))protectedCells.add(c);
        }
        var plan=SurfaceBasinPlan.plan(heights,protectedCells,minX,minZ,d.spanX(),d.spanZ());int columns=0,blocked=0;
        for(var entry:plan.targets().entrySet()){BlockPos p=bases.get(entry.getKey());if(!validBasinColumn(level,d,p,entry.getValue())){blocked++;continue;}
            int before=d.tasks.size();addTask(d,FILL,p,entry.getValue());if(d.tasks.size()>before)columns++;
        }
        d.holesFound=plan.basins();
        for(int i=d.tasks.size()-1;i>=0;i--){CompoundTag t=d.task(i);int type=t.getInt("type");if(type==FILL||type==PATH||isMacro(type)||type==LAMP||type==FENCE)continue;
            if(plan.targets().containsKey(new SurfaceBasinPlan.Cell(t.getInt("x"),t.getInt("z"))))d.tasks.remove(i);
        }
        d.landscapeReport="pozos: "+plan.basins()+" cubetas, "+columns+" columnas previstas, "+blocked+" protegidas, "+plan.oversized()+" extensas y "+plan.tooDeep()+" profundas omitidas";
    }
    private boolean validEarthColumn(ServerLevel level,ConstructorData d,BlockPos p,int target){
        if(terrainFootprintReserved(d,p))return false;
        if(!inside(d,p)||target<=p.getY()||target-p.getY()>12||target>=level.getMaxBuildHeight()-2||!fillMaterial(level.getBlockState(p))||level.getBlockEntity(p)!=null)return false;
        if(!openTerrainColumn(level,p)||entranceReserved(d,p)||isRoad(level,p))return false;
        for(int y=p.getY()+1;y<=target+2;y++){BlockPos q=new BlockPos(p.getX(),y,p.getZ());BlockState state=level.getBlockState(q);if(!terrainCover(level,q))return false;}
        return true;
    }
    private void planLandscape(ServerLevel level,ConstructorData d){
        for(int i=d.tasks.size()-1;i>=0;i--){CompoundTag t=d.task(i);int type=t.getInt("type");if(type==FLOWER||type==SAPLING){int distance=nearestHouseDistance(d,new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z")));int divisor=distance>24?4:distance>14?2:1;if(Math.floorMod(t.getInt("x")*31+t.getInt("z")*17,divisor)!=0)d.tasks.remove(i);}}
        if(d.doors.size()<2){d.landscapeReport+="; sin aldea suficiente para modificar bordes";return;}
        int minX=d.minX(),minZ=d.minZ();
        Map<TerrainApronPlan.Cell,Integer> heights=new HashMap<>();Set<TerrainApronPlan.Cell> anchors=new HashSet<>(),blocked=new HashSet<>();
        Map<TerrainApronPlan.Cell,BlockPos> bases=new HashMap<>();Map<Long,Integer> basins=new HashMap<>();
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(t.getInt("type")==FILL)basins.merge(key(t.getInt("x"),t.getInt("z")),t.getInt("direction"),Math::max);}
        for(int x=minX;x<minX+d.spanX();x++)for(int z=minZ;z<minZ+d.spanZ();z++){
            BlockPos p=terrainSurface(level,x,z);if(p==null)continue;var c=new TerrainApronPlan.Cell(x,z);BlockState floor=level.getBlockState(p);
            heights.put(c,Math.max(p.getY(),basins.getOrDefault(key(p),p.getY())));bases.put(c,p);
            if(!fillMaterial(floor)||!openTerrainColumn(level,p)||level.getBlockEntity(p)!=null){blocked.add(c);continue;}
            if((floor.is(Blocks.GRASS_BLOCK)||floor.is(Blocks.DIRT))&&Math.abs(p.getY()-villageGrade(d))<=2&&nearestHouseDistance(d,p)<=32)anchors.add(c);
        }
        var apron=TerrainApronPlan.plan(heights,anchors,blocked,minX,minZ,d.spanX(),d.spanZ());
        int columns=0,obstacles=apron.blocked(),volume=0;
        for(var entry:apron.targets().entrySet()){
            BlockPos p=bases.get(entry.getKey());int target=entry.getValue();
            if(!validEarthColumn(level,d,p,target)){obstacles++;continue;}
            int before=d.tasks.size();addTask(d,RAMP,p,target);
            if(d.tasks.size()>before){columns++;volume+=target-p.getY();}
        }
        // The apron owns these columns. Remove competing one-block terraces, plants and shallow fills.
        for(int i=d.tasks.size()-1;i>=0;i--){CompoundTag t=d.task(i);int type=t.getInt("type");
            if(type==RAMP||type==FILL||type==PATH||isMacro(type)||type==LAMP||type==FENCE)continue;
            if(apron.targets().containsKey(new TerrainApronPlan.Cell(t.getInt("x"),t.getInt("z"))))d.tasks.remove(i);
        }
        int farmLinks=0;for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);if(t.getInt("type")!=FARM)continue;int before=d.tasks.size();for(Direction dir:Direction.Plane.HORIZONTAL){CompoundTag entrance=new CompoundTag();entrance.putInt("x",t.getInt("x")+dir.getStepX()*2);entrance.putInt("y",t.getInt("y")+1);entrance.putInt("z",t.getInt("z")+dir.getStepZ()*2);entrance.putInt("facing",dir.get2DDataValue());planEntranceLink(level,d,entrance);if(d.tasks.size()>before){farmLinks++;break;}}}
        d.landscapeReport=(d.landscapeReport.startsWith("pozos:")?d.landscapeReport+"; ":"")+"taludes: "+apron.edges()+" bordes detectados, "+columns+" columnas y "+volume+" bloques de relleno previstos; "+obstacles+" columnas protegidas/obstruidas, "+apron.clipped()+" continuaciones fuera de parcela, "+apron.tooDeep()+" cortes de más de 12 bloques; "+farmLinks+" accesos a cultivos. Hileras de césped cada 2 bloques, desde fondo firme; admite líquidos y follaje, sin excavar";
    }
    private boolean canLookout(ServerLevel level,ConstructorData d,BlockPos center){
        if(remembered(d,LOOKOUT,center))return false;
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int type=t.getInt("type");if(type==RAMP||type==RETAIN||isMacro(type)){int dx=t.getInt("x")-center.getX(),dz=t.getInt("z")-center.getZ();if(dx*dx+dz*dz<64)return false;}}
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

    private void resetReport(ConstructorData d){d.reviewPending=false;d.retryPass=0;d.retryBaseline=0;d.retryTasks.clear();d.landscapeReport="pendiente";d.lastReport="";d.completed=new CompoundTag();d.deferred=new CompoundTag();d.pendingExamples.clear();d.inaccessibleJobs=0;d.invalidJobs=0;d.failedPlacements=0;d.partialJobs=0;d.satisfiedJobs=0;}
    private String jobName(int type){return switch(type){case REMOVE_FENCE->"retirada de vallas propias";case RAMP->"taludes de césped";case RETAIN->"contención";case LOOKOUT->"miradores";case CLEAR->"limpieza";case FILL,TERRACE->"relleno";case PATCH,GRASS->"césped";case PATH->"caminos";case LAMP,TORCH->"iluminación";case FARM->"huertas";case GARDEN->"jardines";case WORKSITE->"talleres";case FENCE->"vallado";case 20->"bancos/jardineras";case 21->"pérgolas";case 22->"pozos";case 23->"puestos";case 24->"merenderos";case 25->"estanques";default->"plantas/decoración";};}
    private void deferJob(ConstructorData d,CompoundTag job,String reason){
        String key=Integer.toString(job.getInt("type"));d.deferred.putInt(key,d.deferred.getInt(key)+1);
        if(d.pendingExamples.size()<4){CompoundTag example=job.copy();example.putString("reason",reason);d.pendingExamples.add(example);}
        for(int i=0;i<d.retryTasks.size();i++){CompoundTag old=d.retryTasks.getCompound(i);if(old.getInt("type")==job.getInt("type")&&old.getInt("x")==job.getInt("x")&&old.getInt("y")==job.getInt("y")&&old.getInt("z")==job.getInt("z"))return;}
        CompoundTag retry=job.copy();retry.putString("reason",reason);d.retryTasks.add(retry);d.setDirty();
    }
    private void resumePending(ConstructorData d,boolean automatic){
        var jobs=d.retryTasks.copy();d.tasks.clear();d.tasks.addAll(jobs);d.retryTasks.clear();d.taskCursor=0;
        d.retryPass=automatic?d.retryPass+1:0;d.retryBaseline=d.placed;d.finished=false;d.reviewPending=false;d.active=true;
        d.deferred=new CompoundTag();d.pendingExamples.clear();d.inaccessibleJobs=0;d.invalidJobs=0;d.partialJobs=0;d.failedPlacements=0;d.lastReport="";
        pending.clear();route.clear();routeIndex=0;walking=false;recoveringRoute=false;preparing=false;placementDelay=0;announced=false;loadPlan(d);d.setDirty();
    }
    private boolean taskSatisfied(ServerLevel level,ConstructorData d,CompoundTag t){
        int type=t.getInt("type");BlockPos p=new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));if(!level.hasChunkAt(p))return false;
        BlockState ground=level.getBlockState(p),above=level.getBlockState(p.above());
        if(isMacro(type)||type==FENCE)for(int i=0;i<d.memory.size();i++){CompoundTag m=d.memory.getCompound(i);if(d.dimension.equals(m.getString("dimension"))&&m.getInt("type")==type&&m.getInt("x")==p.getX()&&m.getInt("y")==p.getY()&&m.getInt("z")==p.getZ())return true;}
        return switch(type){
            case REMOVE_FENCE -> !ground.is(BlockTags.FENCES);
            case GRASS,PATCH -> ground.is(Blocks.GRASS_BLOCK);
            case PATH -> ground.is(Blocks.DIRT_PATH);
            case TERRACE -> above.is(Blocks.GRASS_BLOCK);
            case FILL,RAMP -> {BlockPos cap=new BlockPos(p.getX(),t.getInt("direction"),p.getZ());yield level.getBlockState(cap).is(Blocks.GRASS_BLOCK)||level.getBlockState(cap).is(Blocks.DIRT_PATH);}
            case RETAIN -> level.getBlockState(new BlockPos(p.getX(),t.getInt("direction"),p.getZ())).is(Blocks.COBBLESTONE);
            case CLEAR -> ground.isAir();
            case FLOWER,EDGE_FLOWER -> above.is(flowerFor(p.getX(),p.getZ()));
            case SAPLING -> above.getBlock() instanceof SaplingBlock||above.is(net.minecraft.tags.BlockTags.LOGS);
            case POT -> above.is(Blocks.POTTED_POPPY);
            case TORCH -> above.is(Blocks.TORCH);
            case LEAVES -> above.is(Blocks.OAK_LEAVES);
            case FENCE -> above.is(villageFence(level,p));
            case LAMP -> {boolean complete=true;for(Placement part:lampBlueprint(level,p))if(!level.getBlockState(part.pos()).is(part.state().getBlock())){complete=false;break;}yield complete;}
            default -> false;
        };
    }
    private String completionReport(ConstructorData d){
        Map<String,Integer> done=new LinkedHashMap<>(),left=new LinkedHashMap<>();
        for(String key:d.completed.getAllKeys())done.merge(jobName(Integer.parseInt(key)),d.completed.getInt(key),Integer::sum);
        for(String key:d.deferred.getAllKeys())left.merge(jobName(Integer.parseInt(key)),d.deferred.getInt(key),Integer::sum);
        StringBuilder report=new StringBuilder(d.reviewPending?"Trabajo pausado con "+d.retryTasks.size()+" tareas pendientes en ":"Trabajo finalizado en ").append(d.areaLabel()).append(": ").append(d.placed).append(" cambios de bloque.\nCompletado (tareas): ").append(done.isEmpty()?"sin cambios elegibles":done).append(". Lootr: ").append(d.chestsConverted).append(" cofres.\nPendiente: ").append(left.isEmpty()?"ninguna tarea propuesta quedó pendiente":left).append(". Sin acceso: ").append(d.inaccessibleJobs).append("; condiciones cambiadas: ").append(d.invalidJobs).append("; ya resueltas naturalmente: ").append(d.satisfiedJobs).append("; trabajos parciales: ").append(d.partialJobs).append("; colocaciones fallidas: ").append(d.failedPlacements).append('.');
        report.append("\nAldea: ").append(d.villageReport).append('.').append(" Paisaje: ").append(d.landscapeReport).append('.');
        if(d.roadsFound==0)report.append(" No reconocí caminos: revisar los materiales del sendero.");
        if(d.doors.size()<2)report.append(" No hubo suficientes accesos para proponer un perímetro.");
        if(d.villageReport.contains("fuera de parcela"))report.append(" Ampliar la próxima parcela para incluir todas las casas.");
        if(d.chestsFailed>0||d.chestsBoundary>0)report.append(" Cofres pendientes: fallidos ").append(d.chestsFailed).append(", dobles que cruzan el límite ").append(d.chestsBoundary).append('.');
        if(!ConstructorLootr.available())report.append(" Lootr no está instalado: cofres conservados.");
        for(int i=0;i<d.pendingExamples.size();i++){CompoundTag e=d.pendingExamples.getCompound(i);report.append("\nEjemplo pendiente: ").append(jobName(e.getInt("type"))).append(" en ").append(e.getInt("x")).append(' ').append(e.getInt("y")).append(' ').append(e.getInt("z")).append(": ").append(e.getString("reason")).append('.');}
        if(d.reviewPending)report.append(" No marqué la zona como terminada. Revisé las tareas pendientes después de las reparaciones; siguen protegidas o sin acceso. Usá /constructor iniciar para auditar de nuevo y reintentar sin crear otra zona.");
        if(left.isEmpty())report.append(" No detecté más trabajos dentro de las reglas actuales; esto no garantiza que toda la aldea esté completa.");
        return report.toString();
    }

    private long key(BlockPos p){return key(p.getX(),p.getZ());}
    private long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
