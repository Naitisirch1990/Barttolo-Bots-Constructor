package net.barttolo.ayudante;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Separate persistent job: never shares gardening selection, queue or progress. */
public final class BridgeData extends SavedData {
    public String dimension="minecraft:overworld",reason="";
    public boolean hasA,configured,active,undoing,finished;
    public int ax,ay,az,bx,by,bz,cursor,changed;
    public final ListTag plan=new ListTag(),history=new ListTag();
    public static BridgeData get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(BridgeData::load,BridgeData::new,"barttoloayudante_bridge");}
    public static BridgeData load(CompoundTag n){
        BridgeData d=new BridgeData();d.dimension=n.getString("dimension");d.reason=n.getString("reason");
        d.hasA=n.getBoolean("hasA");d.configured=n.getBoolean("configured");d.active=n.getBoolean("active");d.undoing=n.getBoolean("undoing");d.finished=n.getBoolean("finished");
        d.ax=n.getInt("ax");d.ay=n.getInt("ay");d.az=n.getInt("az");d.bx=n.getInt("bx");d.by=n.getInt("by");d.bz=n.getInt("bz");d.cursor=n.getInt("cursor");d.changed=n.getInt("changed");
        d.plan.addAll(n.getList("plan",10));d.history.addAll(n.getList("history",10));return d;
    }
    @Override public CompoundTag save(CompoundTag n){
        n.putString("dimension",dimension);n.putString("reason",reason);n.putBoolean("hasA",hasA);n.putBoolean("configured",configured);n.putBoolean("active",active);n.putBoolean("undoing",undoing);n.putBoolean("finished",finished);
        n.putInt("ax",ax);n.putInt("ay",ay);n.putInt("az",az);n.putInt("bx",bx);n.putInt("by",by);n.putInt("bz",bz);n.putInt("cursor",cursor);n.putInt("changed",changed);n.put("plan",plan.copy());n.put("history",history.copy());return n;
    }
}
