package net.barttolo.bots;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Vanilla player packets: a stationary, nonphysical NPC, never a logged-in account.
 * No entity ticking, forced chunks, networking threads, or world modification.
 * Per-viewer tracking avoids duplicate TAB entries and removes visuals on unload.
 */
public final class HostVisual {
    private FakePlayer actor;
    private final Set<UUID> listed=new HashSet<>(), visible=new HashSet<>();
    private boolean inTab;
    private UUID uuid(){return UUID.nameUUIDFromBytes("barttolobots:anfitrion:v1".getBytes(StandardCharsets.UTF_8));}
    public void ensure(MinecraftServer s,HostData d,Guide g){
        if(actor!=null||!d.enabled)return;
        ResourceLocation loc=ResourceLocation.tryParse(d.dimension);if(loc==null)return;
        ServerLevel level=s.getLevel(ResourceKey.create(Registries.DIMENSION,loc));if(level==null)return;
        if(d.skinVersion<2){
            try(var stream=HostVisual.class.getResourceAsStream("/data/barttolobots/butler-skin.json")){
                if(stream==null)throw new IllegalStateException("Falta la apariencia del asistente");
                var skin=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
                d.texture=skin.get("value").getAsString();d.signature=skin.get("signature").getAsString();d.skinVersion=2;d.setDirty();
            }catch(java.io.IOException ex){throw new IllegalStateException("No se pudo cargar la apariencia",ex);}
        }
        GameProfile profile=new GameProfile(uuid(),"Asistente");
        if(!d.texture.isBlank()){
            Property skin=d.signature.isBlank()?new Property("textures",d.texture):new Property("textures",d.texture,d.signature);
            profile.getProperties().put("textures",skin);
        }
        actor=new FakePlayer(level,profile){
            {getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION,(byte)127);}
            @Override public Component getTabListDisplayName(){return Component.literal(g.name);}
        };
        actor.setPos(d.x,d.y,d.z);actor.setYRot(d.yaw);actor.setYHeadRot(d.yaw);
        actor.setNoGravity(true);actor.setInvulnerable(true);inTab=g.showInTab;
    }
    public void sync(MinecraftServer s,HostData d,Guide g){
        if(!d.enabled){clear(s);return;}ensure(s,d,g);if(actor==null)return;
        for(ServerPlayer p:s.getPlayerList().getPlayers()){
            if(p instanceof FakePlayer)continue;
            boolean close=p.level().dimension().location().toString().equals(d.dimension)
                &&p.distanceToSqr(d.x,d.y,d.z)<=g.visualRadius*g.visualRadius
                &&p.serverLevel().hasChunkAt(actor.blockPosition())
                &&Math.abs((p.blockPosition().getX()>>4)-(actor.blockPosition().getX()>>4))<=s.getPlayerList().getViewDistance()
                &&Math.abs((p.blockPosition().getZ()>>4)-(actor.blockPosition().getZ()>>4))<=s.getPlayerList().getViewDistance();
            UUID id=p.getUUID();
            if((inTab||close)&&listed.add(id))list(p);
            if(close&&visible.add(id)){
                p.connection.send(new ClientboundAddPlayerPacket(actor));
                var data=actor.getEntityData().getNonDefaultValues();
                if(data!=null)p.connection.send(new ClientboundSetEntityDataPacket(actor.getId(),data));
                p.connection.send(new ClientboundRotateHeadPacket(actor,(byte)(d.yaw*256/360)));
            }else if(!close&&visible.remove(id))p.connection.send(new ClientboundRemoveEntitiesPacket(actor.getId()));
            if(!inTab&&!close&&listed.remove(id))p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(uuid())));
        }
    }
    private void list(ServerPlayer p){
        p.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(actor)));
    }
    public void forget(UUID player){listed.remove(player);visible.remove(player);}
    public void clear(MinecraftServer s){
        if(actor!=null)for(ServerPlayer p:s.getPlayerList().getPlayers()){
            if(visible.contains(p.getUUID()))p.connection.send(new ClientboundRemoveEntitiesPacket(actor.getId()));
            if(listed.contains(p.getUUID()))p.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(uuid())));
        }
        actor=null;listed.clear();visible.clear();
    }
    public boolean exists(){return actor!=null;}
}
