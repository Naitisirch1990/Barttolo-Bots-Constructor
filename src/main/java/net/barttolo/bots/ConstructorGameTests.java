package net.barttolo.bots;

import java.lang.reflect.*;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraftforge.gametest.*;

@GameTestHolder(Bots.ID)
@PrefixGameTestTemplate(false)
public class ConstructorGameTests {
    static Object call(ConstructorBuilder b,String name,Class<?>[] signature,Object... args)throws Exception{
        Method m=ConstructorBuilder.class.getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(b,args);
    }
    static void field(ConstructorBuilder b,String name,Object value)throws Exception{Field f=ConstructorBuilder.class.getDeclaredField(name);f.setAccessible(true);f.set(b,value);}
    static Object field(ConstructorBuilder b,String name)throws Exception{Field f=ConstructorBuilder.class.getDeclaredField(name);f.setAccessible(true);return f.get(b);}
    static CompoundTag task(int type,BlockPos p){CompoundTag t=new CompoundTag();t.putInt("type",type);t.putInt("x",p.getX());t.putInt("y",p.getY());t.putInt("z",p.getZ());return t;}
    @GameTest(template="empty",timeoutTicks=200)
    public static void fourBedrockCornersDefinePersistentRectangle(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX()+1792,level.getMaxBuildHeight()-28,origin.getZ()+1792);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;d.active=true;d.markerSearch=true;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            level.setBlockAndUpdate(c.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());for(int y=1;y<=4;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        for(int x:new int[]{-6,6})for(int z:new int[]{-4,4})level.setBlockAndUpdate(c.offset(x,1,z),Blocks.BEDROCK.defaultBlockState());
        for(int tick=0;tick<40&&d.markerSearch&&d.active;tick++)call(b,"scanMarkers",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(d.bounded&&!d.markerSearch&&d.spanX()==13&&d.spanZ()==9,"use the exact four-corner rectangle, not the original square");
        h.assertTrue(d.placed==0&&d.tasks.isEmpty(),"marker discovery is read-only");
        ConstructorData saved=ConstructorData.load(d.save(new CompoundTag()));
        h.assertTrue(saved.bounded&&saved.contains(c.getX()+6,c.getZ()+4)&&!saved.contains(c.getX()+7,c.getZ()),"exact limits must survive restart");
        Method insideLoot=ConstructorLootr.class.getDeclaredMethod("inside",ConstructorData.class,BlockPos.class);insideLoot.setAccessible(true);
        h.assertTrue(!(boolean)insideLoot.invoke(null,saved,c.east(7)),"chest conversion must obey the same rectangle");
        level.setBlockAndUpdate(c.offset(6,1,4),Blocks.AIR.defaultBlockState());
        d.bounded=false;d.markerSearch=true;d.cornerMarkers.clear();d.cursor=0;d.active=true;
        for(int tick=0;tick<40&&d.active;tick++)call(b,"scanMarkers",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(!d.active&&d.markerSearch&&d.placed==0,"three corners must pause before any world edit");
        for(int x:new int[]{-6,6})for(int z:new int[]{-4,4})level.setBlockAndUpdate(c.offset(x,1,z),Blocks.AIR.defaultBlockState());
        for(int[] q:new int[][]{{-6,-4},{6,-4},{-6,4},{5,4}})level.setBlockAndUpdate(c.offset(q[0],1,q[1]),Blocks.BEDROCK.defaultBlockState());
        d.cornerMarkers.clear();d.cursor=0;d.active=true;
        for(int tick=0;tick<40&&d.active;tick++)call(b,"scanMarkers",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(!d.active&&d.markerSearch&&d.placed==0,"four unaligned markers must also be rejected");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void markedGroundFinishesBeforeFenceAndKeepsOutsideUntouched(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX()+2048,level.getMaxBuildHeight()-28,origin.getZ()+2048);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;d.active=true;d.staged=true;d.bounded=true;d.boundMinX=c.getX()-8;d.boundMaxX=c.getX()+8;d.boundMinZ=c.getZ()-6;d.boundMaxZ=c.getZ()+6;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            int floor=x>=-4&&x<=-2&&z>=-3&&z<=-1?c.getY()-3:c.getY();
            for(int y=c.getY()-4;y<=c.getY()+5;y++)level.setBlockAndUpdate(new BlockPos(c.getX()+x,y,c.getZ()+z),y<floor?Blocks.STONE.defaultBlockState():y==floor?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.AIR.defaultBlockState());
        }
        BlockPos ownBase=c.offset(-3,-3,-2),ownFence=ownBase.above();level.setBlockAndUpdate(ownFence,Blocks.OAK_FENCE.defaultBlockState());
        CompoundTag memory=task(15,ownBase);memory.putString("dimension",d.dimension);d.memory.add(memory);
        BlockPos playerFence=c.offset(5,1,4);level.setBlockAndUpdate(playerFence,Blocks.OAK_FENCE.defaultBlockState());
        BlockPos marker=c.offset(-8,1,-6);level.setBlockAndUpdate(marker,Blocks.BEDROCK.defaultBlockState());
        field(b,"visualX",c.getX()+.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        while(d.scanning)call(b,"scanBatch",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(java.util.stream.IntStream.range(0,d.tasks.size()).anyMatch(i->d.task(i).getInt("type")==26),"audit removal of the remembered fence before filling");
        h.assertTrue(java.util.stream.IntStream.range(0,d.tasks.size()).noneMatch(i->d.task(i).getInt("type")==15),"never schedule a new fence in the terrain stage");
        for(int tick=0;tick<12000&&d.workStage==0&&!d.reviewPending;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.workStage==1&&d.scanning&&!d.reviewPending,"only advance to road audit after terrain is complete; pending="+d.retryTasks.size());
        for(int x=-4;x<=-2;x++)for(int z=-3;z<=-1;z++){
            h.assertTrue(level.getBlockState(c.offset(x,0,z)).is(Blocks.GRASS_BLOCK),"fill the whole marked depression to village height");
            for(int y=-2;y<0;y++)h.assertTrue(level.getBlockState(c.offset(x,y,z)).is(Blocks.DIRT),"do not leave fences or air buried inside the fill");
        }
        h.assertTrue(d.memory.isEmpty()&&level.getBlockState(playerFence).is(Blocks.OAK_FENCE),"remove ownership of the old fence, preserve unowned fences");
        h.assertTrue(level.getBlockState(marker).is(Blocks.BEDROCK),"preserve the bedrock marker");
        // Every later fence is freshly planned on the final grade and within the exact limits.
        d.workStage=3;d.cursor=0;d.tasks.clear();d.doors.clear();call(b,"loadPlan",new Class[]{ConstructorData.class},d);
        while(d.scanning)call(b,"scanBatch",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(d.tasks.size()>0,"plan the marked perimeter at the final stage even without automatic village bounds");
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);h.assertTrue(t.getInt("type")==15&&t.getInt("y")==c.getY()&&d.contains(t.getInt("x"),t.getInt("z")),"fence must use final ground, inside rectangle");}
        ConstructorBuilder guard=new ConstructorBuilder();ConstructorData small=ConstructorData.load(d.save(new CompoundTag()));small.staged=false;small.tasks.clear();small.taskCursor=0;
        BlockPos outside=c.east(9);CompoundTag fake=task(10,outside);fake.putInt("direction",c.getY()+1);small.tasks.add(fake);
        call(guard,"queueEarthColumn",new Class[]{ServerLevel.class,ConstructorData.class,BlockPos.class,int.class,boolean.class},level,small,outside,c.getY()+1,false);
        call(guard,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,small);
        h.assertTrue(level.getBlockState(outside.above()).isAir()&&small.failedPlacements>0,"final placement guard must reject even a stale outside task");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void wetCliffAndFoliageAreFilledFromSolidBottom(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX()+1280,level.getMaxBuildHeight()-32,origin.getZ()+1280);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            int floor=x<=0?c.getY():c.getY()-4;
            for(int y=c.getY()-6;y<=c.getY()+6;y++){
                BlockPos p=new BlockPos(c.getX()+x,y,c.getZ()+z);
                level.setBlockAndUpdate(p,y<floor?Blocks.STONE.defaultBlockState():y==floor?(x>0?Blocks.CLAY:Blocks.GRASS_BLOCK).defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            if(x>0&&x<4)for(int y=-3;y<=-2;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.WATER.defaultBlockState());
            if(x>=4&&x<=7)level.setBlockAndUpdate(c.offset(x,-3,z),Blocks.PINK_PETALS.defaultBlockState());
        }
        BlockPos leaf=c.offset(3,-1,-4);level.setBlockAndUpdate(leaf,Blocks.CHERRY_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true));
        BlockPos tree=c.offset(2,-4,8);for(int y=1;y<=6;y++)level.setBlockAndUpdate(tree.above(y),Blocks.CHERRY_LOG.defaultBlockState());
        for(int z:new int[]{-8,8}){CompoundTag door=task(0,c.offset(-8,1,z));door.putInt("facing",0);d.doors.add(door);}
        field(b,"visualX",c.getX()-3.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        h.assertTrue(c.offset(1,-4,0).equals(call(b,"terrainSurface",new Class[]{ServerLevel.class,int.class,int.class},level,c.getX()+1,c.getZ())),"water surface must resolve to its solid clay bed");
        call(b,"planLandscape",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(d.tasks.size()>100,"wet edge and petals must not stop the continuous apron");
        for(int tick=0;tick<60000&&!d.finished&&!d.reviewPending;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.finished&&d.failedPlacements==0,"finish wet apron from its reachable bank, pending="+d.retryTasks.size());
        for(int z:new int[]{-4,0,4})for(int x=1;x<=6;x++){
            int target=c.getY()-(x+1)/2;
            h.assertTrue(level.getBlockState(new BlockPos(c.getX()+x,target,c.getZ()+z)).is(Blocks.GRASS_BLOCK),"missing wet/foliage apron cap "+x+","+z);
            for(int y=c.getY()-3;y<target;y++)h.assertTrue(level.getBlockState(new BlockPos(c.getX()+x,y,c.getZ()+z)).is(Blocks.DIRT),"fill must replace liquid from the bed upward");
        }
        h.assertTrue(level.getBlockState(tree.above()).is(Blocks.CHERRY_LOG),"never remove a tree trunk to force the apron");
        h.assertTrue(!level.getBlockState(leaf).is(Blocks.CHERRY_LEAVES),"foliage intersecting the work must be cleared");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void sectorsAndRoadRepairDuringTravel(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX()+1536,level.getMaxBuildHeight()-24,origin.getZ()+1536);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            level.setBlockAndUpdate(c.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=1;y<=5;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        field(b,"visualX",c.getX()-6.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()-4.5);
        CompoundTag distant=task(10,c.offset(6,0,-4));distant.putInt("direction",c.getY()+1);d.tasks.add(distant);
        d.tasks.add(task(2,c.offset(-6,0,-4)));d.tasks.add(task(2,c.offset(-3,0,-4)));
        for(int tick=0;tick<500&&d.taskCursor==0;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.taskCursor==1&&d.task(0).getInt("type")==2,"choose local work before a remote higher-priority job");
        call(b,"beginNearestTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(d.task(1).getInt("type")==2,"finish the current sector before crossing to the distant fill");
        ConstructorBuilder walker=new ConstructorBuilder();ConstructorData roads=new ConstructorData();roads.width=32;roads.centerX=c.getX();roads.centerY=c.getY()+1;roads.centerZ=c.getZ();roads.dimension=d.dimension;roads.chestsDone=true;
        for(int x=-5;x<=5;x++)roads.tasks.add(task(16,c.offset(x,0,6)));
        call(walker,"loadPlan",new Class[]{ConstructorData.class},roads);
        field(walker,"visualX",c.getX()-6.5);field(walker,"visualY",c.getY()+1.0);field(walker,"visualZ",c.getZ()+6.5);
        @SuppressWarnings("unchecked") java.util.ArrayList<BlockPos> route=(java.util.ArrayList<BlockPos>)field(walker,"route");
        for(int x=-6;x<=7;x++)route.add(c.offset(x,0,6));field(walker,"walking",true);
        for(int tick=0;tick<70;tick++)call(walker,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,roads);
        h.assertTrue((boolean)field(walker,"walking")&&roads.taskCursor==0,"road edits must occur during the current trip");
        h.assertTrue(roads.placed>=4&&level.getBlockState(c.offset(-4,0,6)).is(Blocks.DIRT_PATH),"repair audited tiles while walking, without a separate trip per tile");
        h.assertTrue(level.getBlockState(c.offset(-4,0,7)).is(Blocks.GRASS_BLOCK),"do not turn unrelated lawn into a path");
        // Full audit is stationary and preserves unfinished jobs even if saved during the audit.
        call(b,"restartSurvey",new Class[]{ConstructorData.class},d);
        ConstructorData restored=ConstructorData.load(d.save(new CompoundTag()));
        h.assertTrue(restored.scanning&&!restored.auditCarry.isEmpty(),"carry unfinished sector jobs across a restart during the survey");
        double before=(double)field(b,"visualX");int placed=d.placed;
        while(d.scanning)call(b,"scanBatch",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue((double)field(b,"visualX")==before&&d.placed==placed,"complete the audit without moving or editing blocks");
        h.assertTrue(!d.scanning&&!d.auditCarry.isEmpty(),"retain future decoration jobs until their own stage audit");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void pavedVillageFloorIsConnectedAtCurrentHeight(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX()+1024,level.getMaxBuildHeight()-24,origin.getZ()+1024);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;d.active=true;d.scanning=false;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            level.setBlockAndUpdate(c.offset(x,-1,z),Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(c.offset(x,0,z),(x>=0&&x<=2?Blocks.STONE_BRICKS:Blocks.GRASS_BLOCK).defaultBlockState());
            for(int y=1;y<=6;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        // All routes cross a three-block band of manufactured paving that the old natural-soil filter rejected.
        BlockPos target=c.east(6);level.setBlockAndUpdate(target,Blocks.DIRT.defaultBlockState());
        field(b,"visualX",c.getX()-3.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        d.tasks.add(task(16,target));
        for(int tick=0;tick<10000&&!d.finished&&!d.reviewPending;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.finished&&d.inaccessibleJobs==0&&level.getBlockState(target).is(Blocks.DIRT_PATH),"road repair must cross village paving instead of pausing without access");
        h.assertTrue(level.getBlockState(c).is(Blocks.STONE_BRICKS),"walking must preserve the paving");
        BlockPos slab=c.offset(-4,0,8);level.setBlockAndUpdate(slab,Blocks.STONE_BRICK_SLAB.defaultBlockState());
        h.assertTrue(slab.equals(call(b,"navigationStep",new Class[]{ServerLevel.class,ConstructorData.class,int.class,int.class,int.class},level,d,slab.getX(),slab.getZ(),c.getY())),"recognize a ground-level slab");
        h.assertTrue(Math.abs((double)call(b,"footHeight",new Class[]{ServerLevel.class,BlockPos.class},level,slab)-(slab.getY()+.5))<.001,"feet must follow the slab surface");
        BlockPos underRoof=c.offset(-8,0,8);level.setBlockAndUpdate(underRoof.above(3),Blocks.OAK_PLANKS.defaultBlockState());
        h.assertTrue(underRoof.equals(call(b,"navigationStep",new Class[]{ServerLevel.class,ConstructorData.class,int.class,int.class,int.class},level,d,underRoof.getX(),underRoof.getZ(),c.getY())),"walk below an eave instead of selecting the highest roof block");
        var source=level.getServer().createCommandSourceStack().withPermission(4).withLevel(level).withPosition(new net.minecraft.world.phys.Vec3(c.getX()+.5,c.getY()+12,c.getZ()+.5));
        try{
            h.assertTrue(b.create(source,32)==1,"allow assigning the village while the administrator is flying");
            h.assertTrue(ConstructorData.get(level.getServer()).centerY==c.getY()+1,"navigation height must use ground, not the flying administrator's height");
        }finally{b.cancel(source);}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void unfinishedWorkPersistsAndResumes(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX()+768,level.getMaxBuildHeight()-28,origin.getZ()+768);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.configured=true;d.chestsDone=true;d.scanning=false;d.active=true;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){level.setBlockAndUpdate(c.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());for(int y=1;y<=5;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.AIR.defaultBlockState());}
        BlockPos isolated=c.east(8);level.setBlockAndUpdate(c.above(),Blocks.STONE_BRICKS.defaultBlockState());
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)for(int y=1;y<=2;y++)level.setBlockAndUpdate(isolated.offset(dx,y,dz),Blocks.STONE_BRICKS.defaultBlockState());
        d.tasks.add(task(2,c));d.tasks.add(task(2,isolated));
        field(b,"visualX",c.getX()-3.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        for(int tick=0;tick<10000&&!d.reviewPending&&!d.finished;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.reviewPending&&!d.finished&&d.retryTasks.size()==2,"blocked and inaccessible work must remain pending, never falsely finished");
        h.assertTrue(d.retryPass>=1,"automatically recheck deferred work before pausing");
        h.assertTrue(d.lastReport.contains("No marqué la zona como terminada"),"final report must distinguish blocked work from completion");
        ConstructorData restored=ConstructorData.load(d.save(new CompoundTag()));
        h.assertTrue(restored.reviewPending&&restored.retryTasks.size()==2&&!restored.finished,"pending jobs and review state must survive restart");
        level.setBlockAndUpdate(c.above(),Blocks.AIR.defaultBlockState());
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)for(int y=1;y<=2;y++)level.setBlockAndUpdate(isolated.offset(dx,y,dz),Blocks.AIR.defaultBlockState());
        call(b,"resumePending",new Class[]{ConstructorData.class,boolean.class},restored,false);
        for(int tick=0;tick<10000&&!restored.finished&&!restored.reviewPending;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,restored);
        h.assertTrue(restored.finished&&!restored.reviewPending&&restored.retryTasks.isEmpty(),"resume must finish stored jobs after access becomes available");
        h.assertTrue(level.getBlockState(c.above()).getBlock() instanceof net.minecraft.world.level.block.FlowerBlock&&level.getBlockState(isolated.above()).getBlock() instanceof net.minecraft.world.level.block.FlowerBlock,"both deferred jobs must actually be placed");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void multiBlockPitAndPartiallyFilledColumns(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX()+512,level.getMaxBuildHeight()-24,origin.getZ()+512);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            int ground=c.getY()-(x>=0&&x<4&&z>=0&&z<4?(x==3&&z==3?3:1):0);
            for(int y=c.getY()-4;y<=c.getY()+5;y++)level.setBlockAndUpdate(new BlockPos(c.getX()+x,y,c.getZ()+z),y<ground?Blocks.DIRT.defaultBlockState():y==ground?(x>=0&&x<4&&z>=0&&z<4?Blocks.STONE.defaultBlockState():Blocks.GRASS_BLOCK.defaultBlockState()):Blocks.AIR.defaultBlockState());
            if(x>=0&&x<4&&z>=0&&z<4)level.setBlockAndUpdate(new BlockPos(c.getX()+x,ground+1,c.getZ()+z),Blocks.GRASS.defaultBlockState());
        }
        BlockPos road=c.offset(-2,0,0);level.setBlockAndUpdate(road,Blocks.DIRT_PATH.defaultBlockState());
        BlockPos pond=c.offset(8,0,8);level.setBlockAndUpdate(pond,Blocks.WATER.defaultBlockState());
        CompoundTag decorativePond=task(25,pond);decorativePond.putString("dimension",d.dimension);d.memory.add(decorativePond);
        h.assertTrue((boolean)call(b,"isWalkableGround",new Class[]{ServerLevel.class,BlockPos.class},level,c.below()),"natural stone floors must be traversable");
        field(b,"visualX",c.getX()-3.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        CompoundTag nearbyBench=task(20,c.offset(8,0,0));nearbyBench.putString("dimension",d.dimension);d.memory.add(nearbyBench);
        call(b,"planSurfaceRepairs",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(d.holesFound==1&&d.tasks.size()==16,"audit the entire 4x4 nested pit");
        BlockPos partial=c.offset(3,-2,3);level.setBlockAndUpdate(partial,Blocks.GRASS_BLOCK.defaultBlockState());
        h.assertTrue((int)call(b,"priority",new Class[]{int.class},15)>(int)call(b,"priority",new Class[]{int.class},0),"perimeter fence must be built after surface repairs");
        for(int tick=0;tick<20000&&!d.finished;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.finished&&d.inaccessibleJobs==0&&d.invalidJobs==0&&d.failedPlacements==0,"complete pit repair after a planned column has already changed: invalid="+d.invalidJobs);
        for(int x=0;x<4;x++)for(int z=0;z<4;z++)h.assertTrue(level.getBlockState(c.offset(x,0,z)).is(Blocks.GRASS_BLOCK),"pit must match its flat rim, including inner columns");
        h.assertTrue(level.getBlockState(road).is(Blocks.DIRT_PATH)&&level.getBlockState(pond).is(Blocks.WATER),"preserve nearby road and the remembered decorative pond");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void continuousCliffApronWithTallGrass(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));
        BlockPos c=new BlockPos(origin.getX()+256,level.getMaxBuildHeight()-32,origin.getZ()+256);
        ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=new ConstructorData();d.width=32;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            int ground=x<=0?c.getY():c.getY()-3-(x>2&&z%3==0?1:0);
            for(int y=c.getY()-5;y<=c.getY()+6;y++)level.setBlockAndUpdate(new BlockPos(c.getX()+x,y,c.getZ()+z),y<ground?Blocks.DIRT.defaultBlockState():y==ground?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.AIR.defaultBlockState());
            if(x>0&&z%2==0)level.setBlockAndUpdate(new BlockPos(c.getX()+x,ground+1,c.getZ()+z),Blocks.GRASS.defaultBlockState());
        }
        for(int z:new int[]{-8,8}){CompoundTag door=new CompoundTag();door.putInt("x",c.getX()-8);door.putInt("y",c.getY()+1);door.putInt("z",c.getZ()+z);door.putInt("facing",0);d.doors.add(door);}
        BlockPos road=c.offset(2,-3,-7);level.setBlockAndUpdate(road.above(),Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(road,Blocks.DIRT_PATH.defaultBlockState());
        BlockPos tree=c.offset(1,-3,6);for(int y=1;y<=5;y++)level.setBlockAndUpdate(tree.above(y),Blocks.OAK_LOG.defaultBlockState());
        field(b,"visualX",c.getX()-3.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        call(b,"planLandscape",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        long columns=java.util.stream.IntStream.range(0,d.tasks.size()).filter(i->d.task(i).getInt("type")==17).count();
        h.assertTrue(columns>60,"long three-block cliff must produce continuous rows, not zero or a single ramp");
        h.assertTrue(d.landscapeReport.contains("bordes detectados"),"report detected edges and protected columns");
        for(int tick=0;tick<30000&&!d.finished;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.finished&&d.inaccessibleJobs==0&&d.failedPlacements==0,"worker must finish the slope from its reachable top edge: inaccessible="+d.inaccessibleJobs+", failed="+d.failedPlacements);
        for(int z:new int[]{-10,-3,0,3,10})for(int x=1;x<=4;x++){
            int y=c.getY()-(x+1)/2;BlockPos p=new BlockPos(c.getX()+x,y,c.getZ()+z);
            h.assertTrue(level.getBlockState(p).is(Blocks.GRASS_BLOCK),"missing grass apron row at "+x+","+z);
            for(int fillY=c.getY()-3;fillY<y;fillY++)h.assertTrue(!level.getBlockState(new BlockPos(p.getX(),fillY,p.getZ())).isAir(),"apron must be solid, not a floating grass sheet");
        }
        h.assertTrue(level.getBlockState(road).is(Blocks.DIRT_PATH),"slope must preserve the existing road");
        h.assertTrue(level.getBlockState(tree.above()).is(Blocks.OAK_LOG),"slope must preserve the tree");
        h.assertTrue(level.getBlockState(c.offset(16,-1,0)).isAir(),"no fill outside the assigned square");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void catalogFitsAndSurvivesPlacement(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));BlockPos c=new BlockPos(origin.getX(),level.getMaxBuildHeight()-20,origin.getZ());
        int checked=0;
        for(int type:new int[]{6,20,21,22,23,24,25})for(int variant=0;variant<(type==6?3:2);variant++){
            for(int rotation=0;rotation<4;rotation++){
                for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++){
                    level.setBlockAndUpdate(c.offset(x,-1,z),Blocks.DIRT.defaultBlockState());
                    level.setBlockAndUpdate(c.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());
                    for(int y=1;y<=7;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.AIR.defaultBlockState());
                }
                var parts=VillageCatalog.build(type,variant,Blocks.OAK_FENCE,Blocks.OAK_LOG,Blocks.OAK_SLAB,Blocks.OAK_STAIRS,Blocks.OAK_TRAPDOOR);
                if(variant==1)h.assertTrue(!parts.equals(VillageCatalog.build(type,0,Blocks.OAK_FENCE,Blocks.OAK_LOG,Blocks.OAK_SLAB,Blocks.OAK_STAIRS,Blocks.OAK_TRAPDOOR)),"catalog variants must have different blocks");
                var rot=net.minecraft.world.level.block.Rotation.values()[rotation];
                java.util.Set<BlockPos> unique=new java.util.HashSet<>();
                for(var part:parts){BlockPos offset=part.offset().rotate(rot);
                    h.assertTrue(Math.abs(offset.getX())<=3&&Math.abs(offset.getZ())<=3&&offset.getY()>=-1&&offset.getY()<=5,"catalog must stay in the reserved envelope");
                    h.assertTrue(unique.add(offset),"one final state per coordinate");
                    level.setBlockAndUpdate(c.offset(offset),part.state().rotate(rot));
                }
                for(var part:parts){BlockPos p=c.offset(part.offset().rotate(rot));var expected=part.state().rotate(rot);
                    h.assertTrue(level.getBlockState(p).is(expected.getBlock()),"catalog block lost after placement: type "+type+" at "+part.offset());
                    h.assertTrue(level.getBlockState(p).canSurvive(level,p),"unsupported catalog block: type "+type+" at "+part.offset());
                }
                // The worker's cardinal approach cells stay clear in every orientation.
                ConstructorBuilder b=new ConstructorBuilder();for(int[] step:new int[][]{{4,0},{-4,0},{0,4},{0,-4}})
                    h.assertTrue((boolean)call(b,"isWalkableGround",new Class[]{ServerLevel.class,BlockPos.class},level,c.offset(step[0],0,step[1])),"catalog must not enclose its worker");
                checked++;
            }
        }
        h.assertTrue(checked==60,"exercise all 15 recipes in four orientations");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void cropsRoadsAndWorkApproaches(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=h.absolutePos(new BlockPos(4,2,4));
        // Forge spawns this template at -60 in a normal world; isolate the outdoor fixture above existing terrain.
        BlockPos c=new BlockPos(origin.getX(),level.getMaxBuildHeight()-20,origin.getZ());ConstructorBuilder b=new ConstructorBuilder();
        ConstructorData d=new ConstructorData();d.width=128;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;
        for(int dx=-5;dx<=5;dx++)for(int dz=-5;dz<=5;dz++){
            BlockPos p=c.offset(dx,0,dz);level.setBlockAndUpdate(p,Blocks.GRASS_BLOCK.defaultBlockState());
            for(int dy=1;dy<=8;dy++)level.setBlockAndUpdate(p.above(dy),Blocks.AIR.defaultBlockState());
        }
        d.tasks.add(task(15,c.east(3)));
        h.assertTrue(!(boolean)call(b,"canCatalogSite",new Class[]{ServerLevel.class,ConstructorData.class,BlockPos.class},level,d,c),"catalog must respect the planned village perimeter");d.tasks.clear();
        field(b,"visualX",c.getX()+.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        level.setBlockAndUpdate(c,Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(c.above(),Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE,7));
        h.assertTrue((boolean)call(b,"isWalkableGround",new Class[]{ServerLevel.class,BlockPos.class},level,c),"mature carrots on farmland must not strand the worker");
        h.assertTrue(c.equals(call(b,"surface",new Class[]{ServerLevel.class,int.class,int.class},level,c.getX(),c.getZ())),"surface must find farmland below crops; expected "+c+", found "+call(b,"surface",new Class[]{ServerLevel.class,int.class,int.class},level,c.getX(),c.getZ()));
        h.assertTrue(c.equals(call(b,"standingGround",new Class[]{ServerLevel.class,int.class,int.class,int.class},level,c.getX(),c.getZ(),c.getY())),"feet anchor must accept the newly planted farm");
        h.assertTrue(level.getBlockState(c.above()).is(Blocks.CARROTS),"navigation must not break crops");
        level.setBlockAndUpdate(c.above(),Blocks.STONE.defaultBlockState());
        h.assertTrue(!(boolean)call(b,"isWalkableGround",new Class[]{ServerLevel.class,BlockPos.class},level,c),"a solid obstacle must still block navigation");
        level.setBlockAndUpdate(c.above(),Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(c,Blocks.DIRT_PATH.defaultBlockState());
        d.tasks.add(task(0,c));call(b,"prepareTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(level.getBlockState(c).is(Blocks.DIRT_PATH)&&d.taskCursor==1,"stale grass task must preserve a path and advance");
        d.tasks.clear();d.taskCursor=0;BlockPos dirt=c.east();level.setBlockAndUpdate(dirt,Blocks.DIRT.defaultBlockState());
        d.tasks.add(task(0,dirt));call(b,"prepareTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(level.getBlockState(dirt).is(Blocks.DIRT)&&d.taskCursor==1,"brown road-side fragment must not be turned to lawn");
        for(int type:new int[]{6,11,13}){
            BlockPos approach=(BlockPos)call(b,"approachFor",new Class[]{ServerLevel.class,ConstructorData.class,CompoundTag.class},level,d,task(type,c));
            int distance=Math.max(Math.abs(approach.getX()-c.getX()),Math.abs(approach.getZ()-c.getZ()));
            h.assertTrue(distance>=4,"worker must stand outside the footprint of farm/worksite/garden");
        }
        level.setBlockAndUpdate(c,Blocks.GRASS_BLOCK.defaultBlockState());
        // Exercise the real farm placement queue, then navigation over its output.
        level.setBlockAndUpdate(dirt,Blocks.GRASS_BLOCK.defaultBlockState());d.tasks.clear();d.taskCursor=0;d.tasks.add(task(6,c));
        call(b,"prepareTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        for(int tick=0;tick<2000&&d.taskCursor==0;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.taskCursor==1&&level.getBlockState(c).is(Blocks.WATER),"farm task must finish all its placements");
        h.assertTrue((boolean)call(b,"isWalkableGround",new Class[]{ServerLevel.class,BlockPos.class},level,c.west()),"the farm created by the real queue must remain traversable");
        field(b,"visualX",c.getX()-.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        d.tasks.clear();d.taskCursor=0;d.tasks.add(task(0,c.offset(4,0,0)));
        call(b,"beginNearestTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(d.taskCursor==1&&d.inaccessibleJobs==0,"already-grassy targets must resolve without a spurious trip or access error");
        level.setBlockAndUpdate(c.offset(4,0,0),Blocks.DIRT.defaultBlockState());d.tasks.clear();d.taskCursor=0;d.tasks.add(task(0,c.offset(4,0,0)));
        boolean selected=(boolean)call(b,"beginNearestTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(selected&&d.inaccessibleJobs==0&&d.taskCursor==0,"worker must select a needed job after standing on its own farm");
        h.succeed();
    }
}
