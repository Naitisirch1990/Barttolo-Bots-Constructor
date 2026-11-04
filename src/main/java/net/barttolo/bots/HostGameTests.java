package net.barttolo.bots;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder(Bots.ID)
@PrefixGameTestTemplate(false)
public class HostGameTests {
 @GameTest(template="empty",timeoutTicks=100)
 public static void placementPersistenceAndPackets(GameTestHelper h)throws Exception{
  var level=h.getLevel();var server=level.getServer();var d=HostData.get(server);
  var pos=h.absolutePos(new BlockPos(1,2,1));
  var source=server.createCommandSourceStack().withPermission(4).withLevel(level).withPosition(Vec3.atBottomCenterOf(pos));
  var dispatcher=server.getCommands().getDispatcher();
  int realPlayers=server.getPlayerCount();
  dispatcher.execute("bots anfitrion colocar",source);
  h.assertTrue(d.enabled&&d.x==pos.getX()+.5&&d.y==pos.getY(),"placement must use command source position");
  Bots.visual.ensure(server,d,Bots.guide);
  h.assertTrue(Bots.visual.exists(),"visual actor must instantiate");
  h.assertTrue(server.getPlayerCount()==realPlayers,"NPC must not inflate player count");
  UUID sample=UUID.randomUUID();d.welcomed.add(sample);d.setDirty();
  HostData copy=HostData.load(d.save(new CompoundTag()));
  h.assertTrue(copy.enabled&&copy.welcomed.contains(sample)&&copy.dimension.equals(d.dimension)&&copy.x==d.x,"position and history must survive NBT roundtrip");
  var fake=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"PacketTestBOT"));
  fake.setPos(pos.getX(),pos.getY(),pos.getZ());
  h.assertTrue(Bots.answer(fake,"misiones"),"first answer must pass cooldown");
  h.assertTrue(!Bots.answer(fake,"misiones"),"second immediate answer must be throttled");
  var linked=Bots.linkify("Discord: https://discord.gg/xBWHDmKwc");
  h.assertTrue(linked.getSiblings().stream().anyMatch(c->c.getStyle().getClickEvent()!=null
      &&c.getStyle().getClickEvent().getAction()==net.minecraft.network.chat.ClickEvent.Action.OPEN_URL
      &&c.getStyle().getClickEvent().getValue().equals("https://discord.gg/xBWHDmKwc")),"Discord URL must be clickable");
  h.assertTrue(!Bots.guide.topics.containsKey("tiempos")&&Bots.guide.answer("eventos").contains("Cada semana"),"no spoiler topics");
  String config=java.nio.file.Files.readString(GuideConfig.file());
  Guide old=Bots.guide;
  try{
   java.nio.file.Files.writeString(GuideConfig.file(),"{invalid json");
   int reload=dispatcher.execute("bots anfitrion recargar",source);
   h.assertTrue(reload==0&&Bots.guide==old,"invalid reload must preserve last valid guide");
  }finally{java.nio.file.Files.writeString(GuideConfig.file(),config);}
  h.assertTrue(dispatcher.execute("bots anfitrion recargar",source)==1,"valid guide must reload");
  // Real packet encoders verify FakePlayer's absent chat session is supported.
  var tab=ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake));
  FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
  try{tab.write(buffer);var decoded=new ClientboundPlayerInfoUpdatePacket(buffer);h.assertTrue(decoded.entries().size()==1,"TAB packet roundtrip");}finally{buffer.release();}
  buffer=new FriendlyByteBuf(Unpooled.buffer());
  try{new ClientboundAddPlayerPacket(fake).write(buffer);var spawn=new ClientboundAddPlayerPacket(buffer);h.assertTrue(spawn.getPlayerId().equals(fake.getUUID()),"spawn packet UUID");}finally{buffer.release();}
  boolean denied=false;
  try{dispatcher.execute("bots anfitrion retirar",source.withPermission(0));}catch(com.mojang.brigadier.exceptions.CommandSyntaxException ex){denied=true;}
  h.assertTrue(denied&&d.enabled,"non-OP cannot remove host");
  dispatcher.execute("bots anfitrion retirar",source);
  h.assertTrue(!d.enabled&&!Bots.visual.exists()&&d.welcomed.contains(sample),"removal must clean visual and preserve history");
  dispatcher.execute("bots anfitrion colocar ~ ~ ~",source);
  h.assertTrue(d.enabled,"placement must be repeatable");
  dispatcher.execute("bots anfitrion retirar",source);
  d.welcomed.remove(sample);d.setDirty();
  h.succeed();
 }
}
