package net.barttolo.bots;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Gardening assignments. Old terrain/loot plans are never executable in this schema. */
public final class ConstructorData extends SavedData {
    public static final int SCHEMA=2;
    public String dimension="minecraft:overworld",lastReport="";
    public int centerX,centerY,centerZ,width=64,cursor,taskCursor,placed,completedJobs,skipped;
    public int pointAX,pointAY,pointAZ;
    public boolean configured,active,scanning=true,finished,auditOnly,reviewPending,positionSaved;
    public boolean hasPointA;
    public String pointDimension="minecraft:overworld";
    public double workerX,workerY,workerZ;
    public String texture="",signature="";
    public final ListTag tasks=new ListTag(),retryTasks=new ListTag(),memory=new ListTag();
    public int minX(){return centerX-width/2;}
    public int minZ(){return centerZ-width/2;}
    public boolean contains(int x,int z){return x>=minX()&&x<minX()+width&&z>=minZ()&&z<minZ()+width;}
    public String areaLabel(){return width+"×"+width;}
    public static ConstructorData get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(ConstructorData::load,ConstructorData::new,"barttolobots_constructor");}
    public CompoundTag task(int i){return tasks.getCompound(i);}
    public void addTask(int type,int x,int y,int z){CompoundTag t=new CompoundTag();t.putInt("type",type);t.putInt("x",x);t.putInt("y",y);t.putInt("z",z);tasks.add(t);}
    public static ConstructorData load(CompoundTag tag){
        ConstructorData d=new ConstructorData();if(tag.contains("dimension"))d.dimension=tag.getString("dimension");
        d.centerX=tag.getInt("centerX");d.centerY=tag.getInt("centerY");d.centerZ=tag.getInt("centerZ");d.width=Math.max(32,Math.min(256,tag.contains("width")?tag.getInt("width"):64));
        d.positionSaved=tag.getBoolean("positionSaved");d.workerX=tag.getDouble("workerX");d.workerY=tag.getDouble("workerY");d.workerZ=tag.getDouble("workerZ");
        d.hasPointA=tag.getBoolean("hasPointA");d.pointAX=tag.getInt("pointAX");d.pointAY=tag.getInt("pointAY");d.pointAZ=tag.getInt("pointAZ");d.pointDimension=tag.contains("pointDimension")?tag.getString("pointDimension"):d.dimension;
        d.texture=tag.getString("texture");d.signature=tag.getString("signature");
        d.configured=tag.getBoolean("configured");d.memory.addAll(tag.getList("memory",10));
        if(tag.getInt("gardenSchema")!=SCHEMA)return d; // pause and re-audit legacy assignments; never run old fills/fences/Lootr
        d.active=tag.getBoolean("active");d.scanning=tag.getBoolean("scanning");d.finished=tag.getBoolean("finished");d.auditOnly=tag.getBoolean("auditOnly");d.reviewPending=tag.getBoolean("reviewPending");
        d.cursor=0;d.taskCursor=tag.getInt("taskCursor");d.placed=tag.getInt("placed");d.completedJobs=tag.getInt("completedJobs");d.skipped=tag.getInt("skipped");d.lastReport=tag.getString("lastReport");
        d.tasks.addAll(tag.getList("tasks",10));d.retryTasks.addAll(tag.getList("retryTasks",10));
        if(d.scanning){d.tasks.clear();d.taskCursor=0;} // rebuild the transient full survey after a restart
        for(int i=d.tasks.size()-1;i>=0;i--)if(!ConstructorBuilder.gardenTask(d.task(i).getInt("type"))){d.tasks.remove(i);d.taskCursor=Math.min(d.taskCursor,i);}
        return d;
    }
    @Override public CompoundTag save(CompoundTag tag){
        tag.putBoolean("positionSaved",positionSaved);tag.putDouble("workerX",workerX);tag.putDouble("workerY",workerY);tag.putDouble("workerZ",workerZ);tag.putString("texture",texture);tag.putString("signature",signature);
        tag.putBoolean("hasPointA",hasPointA);tag.putInt("pointAX",pointAX);tag.putInt("pointAY",pointAY);tag.putInt("pointAZ",pointAZ);tag.putString("pointDimension",pointDimension);
        tag.putInt("gardenSchema",SCHEMA);tag.putString("dimension",dimension);tag.putInt("centerX",centerX);tag.putInt("centerY",centerY);tag.putInt("centerZ",centerZ);tag.putInt("width",width);
        tag.putBoolean("configured",configured);tag.putBoolean("active",active);tag.putBoolean("scanning",scanning);tag.putBoolean("finished",finished);tag.putBoolean("auditOnly",auditOnly);tag.putBoolean("reviewPending",reviewPending);
        tag.putInt("taskCursor",taskCursor);tag.putInt("placed",placed);tag.putInt("completedJobs",completedJobs);tag.putInt("skipped",skipped);tag.putString("lastReport",lastReport);
        tag.put("tasks",tasks.copy());tag.put("retryTasks",retryTasks.copy());tag.put("memory",memory.copy());return tag;
    }
}
