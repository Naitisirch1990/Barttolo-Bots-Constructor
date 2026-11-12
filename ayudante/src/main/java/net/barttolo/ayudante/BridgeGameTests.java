package net.barttolo.ayudante;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AyudanteMod.ID)
@PrefixGameTestTemplate(false)
public final class BridgeGameTests {
    @GameTest(templateNamespace="barttolobots",template="empty",timeoutTicks=200)
    public static void independentBridgeAndGardener(GameTestHelper h) throws Exception {
        ServerLevel level=h.getLevel();BridgeBuilder builder=new BridgeBuilder();
        BridgeData d=BridgeData.get(level.getServer());d.active=false;d.configured=false;d.undoing=false;
        ConstructorData garden=ConstructorData.get(level.getServer());garden.width=48;garden.centerX=9000;garden.active=false;
        BlockPos a=h.absolutePos(new BlockPos(5,5,5));
        for(int x=0;x<=19;x++)for(int z=-3;z<=3;z++)for(int y=0;y<=4;y++)level.setBlock(a.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
        level.setBlock(a.offset(4,0,0),Blocks.WATER.defaultBlockState(),3);
        for (int x = 12; x <= 18; x++) for (int z = -3; z <= 3; z++) {
            level.setBlock(a.offset(x, -5, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = -4; y < 0; y++)
                level.setBlock(a.offset(x, y, z), Blocks.WATER.defaultBlockState(), 3);
        }
        FakePlayer player=FakePlayerFactory.getMinecraft(level);player.setPos(a.getX()+.5,a.getY()+1,a.getZ()+.5);
        CommandSourceStack source=level.getServer().createCommandSourceStack().withLevel(level).withEntity(player).withPermission(4);
        h.assertTrue(builder.pointA(source)==1,"Point A was rejected");
        player.setPos(a.getX()+19.5,a.getY()+1,a.getZ()+.5);
        h.assertTrue(builder.pointB(source)==1&&d.active,"Point B must start automatically");
        builder.pause(source);builder.tick(level.getServer());h.assertTrue(d.cursor==0,"Paused job advanced");
        BridgeData loaded=BridgeData.load(d.save(new CompoundTag()));
        h.assertTrue(loaded.configured&&loaded.plan.size()==d.plan.size()&&loaded.cursor==0,"Saved queue was lost");
        builder.resume(source);for(int i=0;i<200;i++)builder.tick(level.getServer());
        h.assertTrue(d.finished&&!d.active,"Bridge did not finish");
        h.assertTrue(level.getBlockState(a.offset(4,0,0)).is(Blocks.REINFORCED_DEEPSLATE),"Water gap was not bridged");
        for(int side:new int[]{-2,2})h.assertTrue(level.getBlockState(a.offset(4,0,side)).is(Blocks.SEA_LANTERN),"Missing side light");
        for(int side:new int[]{-3,3})h.assertTrue(level.getBlockState(a.offset(4,1,side)).is(Blocks.DEEPSLATE_BRICK_WALL),"Missing outer railing");
        h.assertTrue(level.getBlockState(a.offset(3,0,2)).is(Blocks.REINFORCED_DEEPSLATE),"Lighting spacing incorrect");
        h.assertTrue(level.getBlockState(a.offset(12,-1,3)).is(Blocks.REINFORCED_DEEPSLATE), "Missing flared cap");
        h.assertTrue(level.getBlockState(a.offset(13,-2,2)).is(Blocks.REINFORCED_DEEPSLATE), "Missing middle step");
        h.assertTrue(level.getBlockState(a.offset(15,-4,0)).is(Blocks.DEEPSLATE_BRICKS), "Shaft must reach the riverbed");
        h.assertTrue(level.getBlockState(a.offset(12,-2,3)).is(Blocks.WATER), "Cap must taper below the deck");
        h.assertTrue(garden.width==48&&garden.centerX==9000&&!garden.active,"Bridge changed gardening assignment");
        var root=level.getServer().getCommands().getDispatcher().getRoot();
        h.assertTrue(root.getChild("constructor")!=null&&root.getChild("jardinero")!=null,"Both public commands are required");
        h.assertTrue(root.getChild("constructor").getChild("puntoA")!=null&&root.getChild("constructor").getChild("puntoa")==null,"Point A suggestions are duplicated");
        builder.undo(source);for(int i=0;i<200;i++)builder.tick(level.getServer());
        h.assertTrue(d.history.isEmpty()&&!d.active,"Undo did not complete");
        h.assertTrue(level.getBlockState(a.offset(4,0,0)).is(Blocks.WATER),"Undo did not restore water");
        h.succeed();
    }
}
