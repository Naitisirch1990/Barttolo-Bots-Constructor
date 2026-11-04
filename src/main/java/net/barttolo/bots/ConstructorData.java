package net.barttolo.bots;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent assignment, stationary scan cursor, work queue and progress. */
public final class ConstructorData extends SavedData {
    public String dimension="minecraft:overworld";
    public int centerX,centerY,centerZ,width=32,cursor,taskCursor,placed,skipped,holesFound,roadsFound,stoneFound;
    public boolean configured,active,scanning=true,finished,auditOnly;
    public final ListTag tasks=new ListTag();

    public static ConstructorData get(MinecraftServer server){
        ServerLevel level=server.overworld();
        return level.getDataStorage().computeIfAbsent(ConstructorData::load,ConstructorData::new,"barttolobots_constructor");
    }
    public static ConstructorData load(CompoundTag tag){
        ConstructorData d=new ConstructorData();d.dimension=tag.getString("dimension");
        d.centerX=tag.getInt("centerX");d.centerY=tag.getInt("centerY");d.centerZ=tag.getInt("centerZ");
        d.width=tag.contains("width")?tag.getInt("width"):32;d.cursor=tag.getInt("cursor");d.taskCursor=tag.getInt("taskCursor");
        d.placed=tag.getInt("placed");d.skipped=tag.getInt("skipped");d.holesFound=tag.getInt("holesFound");d.roadsFound=tag.getInt("roadsFound");d.stoneFound=tag.getInt("stoneFound");d.configured=tag.getBoolean("configured");
        d.active=tag.getBoolean("active");d.finished=tag.getBoolean("finished");d.auditOnly=tag.getBoolean("auditOnly");
        if(tag.contains("scanning")){
            d.scanning=tag.getBoolean("scanning");d.tasks.addAll(tag.getList("tasks",10));
        }else{
            // The earlier prototype used the cursor for its moving scan, not this stationary survey.
            d.cursor=0;d.taskCursor=0;d.scanning=true;d.finished=false;
        }
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
        tag.put("tasks",tasks.copy());return tag;
    }
}
