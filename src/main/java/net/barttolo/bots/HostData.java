package net.barttolo.asistente;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** World-owned placement and welcome history, independent of the editable guide. */
public final class HostData extends SavedData {
    public boolean enabled;
    public int skinVersion;
    public String dimension="minecraft:overworld", texture="", signature="";
    public double x,y,z;
    public float yaw;
    public final Set<UUID> welcomed=new HashSet<>();
    public static HostData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(HostData::load,HostData::new,"barttolo_bots_host");
    }
    public static HostData load(CompoundTag n) {
        HostData d=new HostData();d.enabled=n.getBoolean("enabled");
        if(n.contains("dimension"))d.dimension=n.getString("dimension");
        d.x=n.getDouble("x");d.y=n.getDouble("y");d.z=n.getDouble("z");d.yaw=n.getFloat("yaw");
        d.skinVersion=n.getInt("skinVersion");d.texture=n.getString("texture");d.signature=n.getString("signature");
        for(var tag:n.getList("welcomed",8))try{d.welcomed.add(UUID.fromString(tag.getAsString()));}catch(IllegalArgumentException ignored){}
        return d;
    }
    @Override public CompoundTag save(CompoundTag n) {
        n.putInt("schema",2);n.putInt("skinVersion",skinVersion);n.putBoolean("enabled",enabled);n.putString("dimension",dimension);
        n.putDouble("x",x);n.putDouble("y",y);n.putDouble("z",z);n.putFloat("yaw",yaw);
        n.putString("texture",texture);n.putString("signature",signature);
        ListTag people=new ListTag();welcomed.stream().sorted().forEach(id->people.add(StringTag.valueOf(id.toString())));n.put("welcomed",people);
        return n;
    }
}
