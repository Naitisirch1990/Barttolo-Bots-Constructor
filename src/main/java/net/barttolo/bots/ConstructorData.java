package net.barttolo.bots;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent assignment, stationary scan cursor, work queue and progress. */
public final class ConstructorData extends SavedData {
    public String dimension="minecraft:overworld";
    public int centerX,centerY,centerZ,width=64,cursor,taskCursor,placed,skipped,holesFound,roadsFound,stoneFound;
    public boolean configured,active,scanning=true,finished,auditOnly,reviewPending;
    public int chestCursor,chestsFound,chestsConverted,chestsFailed,chestsBoundary;
    public boolean chestsDone,chestsPaused;
    public final ListTag tasks=new ListTag(),doors=new ListTag(),roofs=new ListTag(),memory=new ListTag(),roadCells=new ListTag(),retryTasks=new ListTag();
    public String villageReport="pendiente",lastReport="",landscapeReport="pendiente";
    public CompoundTag completed=new CompoundTag(),deferred=new CompoundTag();
    public int inaccessibleJobs,invalidJobs,failedPlacements,partialJobs,satisfiedJobs,retryPass,retryBaseline;
    public final ListTag pendingExamples=new ListTag();

    public static ConstructorData get(MinecraftServer server){
        ServerLevel level=server.overworld();
        return level.getDataStorage().computeIfAbsent(ConstructorData::load,ConstructorData::new,"barttolobots_constructor");
    }
    public static ConstructorData load(CompoundTag tag){
        ConstructorData d=new ConstructorData();d.dimension=tag.getString("dimension");
        d.centerX=tag.getInt("centerX");d.centerY=tag.getInt("centerY");d.centerZ=tag.getInt("centerZ");
        d.width=tag.contains("width")?tag.getInt("width"):64;d.cursor=tag.getInt("cursor");d.taskCursor=tag.getInt("taskCursor");
        d.placed=tag.getInt("placed");d.skipped=tag.getInt("skipped");d.holesFound=tag.getInt("holesFound");d.roadsFound=tag.getInt("roadsFound");d.stoneFound=tag.getInt("stoneFound");d.configured=tag.getBoolean("configured");
        d.chestCursor=tag.getInt("chestCursor");d.chestsFound=tag.getInt("chestsFound");d.chestsConverted=tag.getInt("chestsConverted");d.chestsFailed=tag.getInt("chestsFailed");d.chestsBoundary=tag.getInt("chestsBoundary");d.chestsDone=!tag.contains("chestsDone")||tag.getBoolean("chestsDone");d.chestsPaused=tag.getBoolean("chestsPaused");
        d.active=tag.getBoolean("active");d.finished=tag.getBoolean("finished");d.auditOnly=tag.getBoolean("auditOnly");
        if(tag.contains("scanning")){
            d.scanning=tag.getBoolean("scanning");d.tasks.addAll(tag.getList("tasks",10));
        }else{
            // The earlier prototype used the cursor for its moving scan, not this stationary survey.
            d.cursor=0;d.taskCursor=0;d.scanning=true;d.finished=false;
        }
        d.roadCells.addAll(tag.getList("roadCells",10));d.satisfiedJobs=tag.getInt("satisfiedJobs");d.doors.addAll(tag.getList("doors",10));d.roofs.addAll(tag.getList("roofs",10));d.memory.addAll(tag.getList("memory",10));d.villageReport=tag.getString("villageReport");
        d.landscapeReport=tag.getString("landscapeReport");d.lastReport=tag.getString("lastReport");d.completed=tag.getCompound("completed");d.deferred=tag.getCompound("deferred");d.inaccessibleJobs=tag.getInt("inaccessibleJobs");d.invalidJobs=tag.getInt("invalidJobs");d.failedPlacements=tag.getInt("failedPlacements");d.partialJobs=tag.getInt("partialJobs");d.pendingExamples.addAll(tag.getList("pendingExamples",10));
        d.reviewPending=tag.getBoolean("reviewPending");d.retryPass=tag.getInt("retryPass");d.retryBaseline=tag.getInt("retryBaseline");d.retryTasks.addAll(tag.getList("retryTasks",10));
        // Earlier versions marked zones finished even with unresolved work; recheck their stored plan on iniciar.
        if(!tag.contains("retryTasks")&&d.finished&&!d.deferred.isEmpty()){d.finished=false;d.reviewPending=true;d.retryTasks.addAll(d.tasks.copy());}
        return d;
    }
    public CompoundTag task(int index){return tasks.getCompound(index);}
    public void addTask(int type,int x,int y,int z,int direction){
        CompoundTag tag=new CompoundTag();tag.putInt("type",type);tag.putInt("x",x);tag.putInt("y",y);tag.putInt("z",z);tag.putInt("direction",direction);tasks.add(tag);
    }
    @Override public CompoundTag save(CompoundTag tag){
        tag.putString("dimension",dimension);tag.putInt("centerX",centerX);tag.putInt("centerY",centerY);tag.putInt("centerZ",centerZ);
        tag.putInt("width",width);tag.putInt("cursor",cursor);tag.putInt("taskCursor",taskCursor);tag.putInt("placed",placed);tag.putInt("skipped",skipped);tag.putInt("holesFound",holesFound);tag.putInt("roadsFound",roadsFound);tag.putInt("stoneFound",stoneFound);
        tag.putBoolean("configured",configured);tag.putBoolean("active",active);tag.putBoolean("scanning",scanning);tag.putBoolean("finished",finished);tag.putBoolean("auditOnly",auditOnly);
        tag.putInt("chestCursor",chestCursor);tag.putInt("chestsFound",chestsFound);tag.putInt("chestsConverted",chestsConverted);tag.putInt("chestsFailed",chestsFailed);tag.putInt("chestsBoundary",chestsBoundary);tag.putBoolean("chestsDone",chestsDone);tag.putBoolean("chestsPaused",chestsPaused);
        tag.put("doors",doors.copy());tag.put("roofs",roofs.copy());tag.put("memory",memory.copy());tag.putString("villageReport",villageReport);
        tag.putString("landscapeReport",landscapeReport);tag.putString("lastReport",lastReport);tag.put("completed",completed.copy());tag.put("deferred",deferred.copy());tag.putInt("inaccessibleJobs",inaccessibleJobs);tag.putInt("invalidJobs",invalidJobs);tag.putInt("failedPlacements",failedPlacements);tag.putInt("partialJobs",partialJobs);tag.put("pendingExamples",pendingExamples.copy());
        tag.put("roadCells",roadCells.copy());tag.putInt("satisfiedJobs",satisfiedJobs);tag.put("tasks",tasks.copy());tag.put("retryTasks",retryTasks.copy());tag.putBoolean("reviewPending",reviewPending);tag.putInt("retryPass",retryPass);tag.putInt("retryBaseline",retryBaseline);return tag;
    }
}
