package net.barttolo.bots;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent job assignment and progress for the Constructor. */
public final class ConstructorData extends SavedData {
    public String dimension="minecraft:overworld";
    public int centerX,centerY,centerZ,width=32,cursor,placed,skipped;
    public boolean configured,active,finished;

    public static ConstructorData get(MinecraftServer server){
        ServerLevel level=server.overworld();
        return level.getDataStorage().computeIfAbsent(ConstructorData::load,ConstructorData::new,"barttolobots_constructor");
    }
    public static ConstructorData load(CompoundTag tag){
        ConstructorData d=new ConstructorData();d.dimension=tag.getString("dimension");
        d.centerX=tag.getInt("centerX");d.centerY=tag.getInt("centerY");d.centerZ=tag.getInt("centerZ");
        d.width=tag.getInt("width");d.cursor=tag.getInt("cursor");d.placed=tag.getInt("placed");d.skipped=tag.getInt("skipped");
        d.configured=tag.getBoolean("configured");d.active=tag.getBoolean("active");d.finished=tag.getBoolean("finished");return d;
    }
    @Override public CompoundTag save(CompoundTag tag){
        tag.putString("dimension",dimension);tag.putInt("centerX",centerX);tag.putInt("centerY",centerY);tag.putInt("centerZ",centerZ);
        tag.putInt("width",width);tag.putInt("cursor",cursor);tag.putInt("placed",placed);tag.putInt("skipped",skipped);
        tag.putBoolean("configured",configured);tag.putBoolean("active",active);tag.putBoolean("finished",finished);return tag;
    }
}
