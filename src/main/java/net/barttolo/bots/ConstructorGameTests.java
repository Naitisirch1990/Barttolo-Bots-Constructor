package net.barttolo.bots;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.*;

@GameTestHolder(Bots.ID)
@PrefixGameTestTemplate(false)
public class ConstructorGameTests {
    static Object call(ConstructorBuilder b,String name,Class<?>[] signature,Object... args)throws Exception{Method m=ConstructorBuilder.class.getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(b,args);}
    static void field(ConstructorBuilder b,String name,Object value)throws Exception{Field f=ConstructorBuilder.class.getDeclaredField(name);f.setAccessible(true);f.set(b,value);}
    static BlockPos center(GameTestHelper h,int offset){BlockPos p=h.absolutePos(new BlockPos(4,2,4));return new BlockPos(p.getX()+offset,h.getLevel().getMaxBuildHeight()-24,p.getZ()+offset);}
    static ConstructorData data(ServerLevel level,BlockPos c,int width){ConstructorData d=new ConstructorData();d.width=width;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.configured=true;return d;}
    static void lawn(ServerLevel level,BlockPos c,int radius){for(int x=-radius;x<radius;x++)for(int z=-radius;z<radius;z++){level.setBlockAndUpdate(c.offset(x,-1,z),Blocks.DIRT.defaultBlockState());level.setBlockAndUpdate(c.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());for(int y=1;y<=9;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.AIR.defaultBlockState());}}
    static void scan(ConstructorBuilder b,ServerLevel level,ConstructorData d)throws Exception{while(d.scanning)call(b,"scanBatch",new Class[]{ServerLevel.class,ConstructorData.class},level,d);}
    static void worker(ConstructorBuilder b,BlockPos p)throws Exception{field(b,"visualX",p.getX()+.5);field(b,"visualY",p.getY()+1.0);field(b,"visualZ",p.getZ()+.5);}
    static int nearestObstacle(ConstructorBuilder b,ServerLevel level,BlockPos p)throws Exception{for(int radius=0;radius<=6;radius++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)if(Math.max(Math.abs(x),Math.abs(z))==radius){BlockPos floor=(BlockPos)call(b,"rawSurface",new Class[]{ServerLevel.class,int.class,int.class},level,p.getX()+x,p.getZ()+z);if((boolean)call(b,"obstacle",new Class[]{ServerLevel.class,BlockPos.class},level,floor))return radius;}return 99;}
    static Map<BlockPos,BlockState> groundSnapshot(ServerLevel level,BlockPos c,int radius){Map<BlockPos,BlockState> states=new HashMap<>();for(int x=-radius;x<radius;x++)for(int z=-radius;z<radius;z++)for(int y=-4;y<=0;y++){BlockPos p=c.offset(x,y,z);states.put(p,level.getBlockState(p));}return states;}

    @GameTest(template="empty",timeoutTicks=200)
    public static void smallPlotsHaveTwelveBlockLightsTreesAndSmallBuilds(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,1536);lawn(level,c,31);
        for(int width:new int[]{32,40,48}){ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,width);scan(b,level,d);int lights=0,trees=0,builds=0;List<BlockPos> lampPositions=new ArrayList<>(),treePositions=new ArrayList<>();
            for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);BlockPos p=new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));if(t.getInt("type")==ConstructorBuilder.LAMP){lights++;lampPositions.add(p);}else if(t.getInt("type")==ConstructorBuilder.TREE){trees++;treePositions.add(p);}else if(t.getInt("type")>=ConstructorBuilder.FARM)builds++;}
            h.assertTrue(lights>0&&trees>0&&builds>0,"small clear plot must include lighting, trees and restored constructions: width="+width+", counts="+lights+","+trees+","+builds);
            for(var positions:List.of(lampPositions,treePositions))for(int i=0;i<positions.size();i++)for(int j=i+1;j<positions.size();j++){
                BlockPos a=positions.get(i),z=positions.get(j);h.assertTrue(Math.floorMod(a.getX()-z.getX(),12)==0&&Math.floorMod(a.getZ()-z.getZ(),12)==0,"tree/light candidates use twelve-block grids");
            }
        }h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void restoredRecipesAreSupportedAndStayAboveGrass(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos origin=center(h,1792);ConstructorBuilder b=new ConstructorBuilder();
        for(int type:new int[]{ConstructorBuilder.FARM,ConstructorBuilder.PLAZA,ConstructorBuilder.BENCH,ConstructorBuilder.PERGOLA,ConstructorBuilder.WELL,ConstructorBuilder.STALL,ConstructorBuilder.PICNIC,ConstructorBuilder.POND})for(int variant=0;variant<3;variant++){
            BlockPos c=origin.east(variant);lawn(level,c,6);
            @SuppressWarnings("unchecked") List<Object> parts=(List<Object>)call(b,"structureBlueprint",new Class[]{BlockPos.class,int.class},c,type);
            for(Object part:parts){Method pm=part.getClass().getDeclaredMethod("pos"),sm=part.getClass().getDeclaredMethod("state");pm.setAccessible(true);sm.setAccessible(true);BlockPos p=(BlockPos)pm.invoke(part);BlockState state=(BlockState)sm.invoke(part);h.assertTrue(p.getY()>c.getY()&&Math.abs(p.getX()-c.getX())<=4&&Math.abs(p.getZ()-c.getZ())<=4,"recipes must stay above existing ground and within their footprint");level.setBlockAndUpdate(p,state);}
            for(Object part:parts){Method pm=part.getClass().getDeclaredMethod("pos"),sm=part.getClass().getDeclaredMethod("state");pm.setAccessible(true);sm.setAccessible(true);BlockPos p=(BlockPos)pm.invoke(part);BlockState state=(BlockState)sm.invoke(part);h.assertTrue(level.getBlockState(p).is(state.getBlock())&&state.canSurvive(level,p),"unsupported restored recipe part, type="+type+", block="+state+", pos="+p);}
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)h.assertTrue(level.getBlockState(c.offset(x,0,z)).is(Blocks.GRASS_BLOCK),"restored recipes must not replace the original ground");
        }h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void partialRaisedFarmResumesAfterGrassNaturallyBecomesDirt(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,2048);lawn(level,c,22);ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,32);d.scanning=false;d.addTask(ConstructorBuilder.FARM,c.getX(),c.getY(),c.getZ());worker(b,c.east(4));
        call(b,"prepareTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        for(int tick=0;tick<2000&&level.getBlockState(c.above()).isAir();tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        Blocks.GRASS_BLOCK.defaultBlockState().randomTick(level,c,level.random);
        h.assertTrue(level.getBlockState(c).is(Blocks.DIRT),"exercise vanilla grass decay below our own raised support");
        ConstructorData restored=ConstructorData.load(d.save(new CompoundTag()));ConstructorBuilder resumed=new ConstructorBuilder();worker(resumed,c.east(4));
        for(int tick=0;tick<10000&&!restored.finished&&!restored.reviewPending;tick++)call(resumed,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,restored);
        h.assertTrue(restored.finished&&restored.retryTasks.isEmpty()&&level.getBlockState(c.above(2)).is(Blocks.WATER),"resume and finish the raised crop bed without mistaking its support for a new obstruction");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void grassBandsTreesAndExactLampGrid(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,256);lawn(level,c,38);ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,64);
        for(int z=-25;z<=25;z++)for(int x=-11;x<=-9;x++)level.setBlockAndUpdate(c.offset(x,0,z),Blocks.STONE.defaultBlockState());
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=1;y<=4;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(c.offset(14,0,4),Blocks.FARMLAND.defaultBlockState());level.setBlockAndUpdate(c.offset(15,0,4),Blocks.WATER.defaultBlockState());
        var before=groundSnapshot(level,c,32);scan(b,level,d);
        h.assertTrue(d.placed==0&&before.entrySet().stream().allMatch(e->level.getBlockState(e.getKey()).equals(e.getValue())),"inspection must not edit the ground");
        int grasses=0,flowers=0,trees=0,lamps=0;Integer phaseX=null,phaseZ=null;
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int type=t.getInt("type");BlockPos p=new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));int distance=nearestObstacle(b,level,p);
            h.assertTrue(ConstructorBuilder.gardenTask(type)&&level.getBlockState(p).is(Blocks.GRASS_BLOCK),"all tasks must start on grass blocks");
            if(type==ConstructorBuilder.TUFT||type==ConstructorBuilder.FLOWER){h.assertTrue(distance==1||distance==3,"plant only the one- and three-block bands");if(type==ConstructorBuilder.TUFT)grasses++;else flowers++;}
            else if(type==ConstructorBuilder.TREE){trees++;h.assertTrue(distance>5,"trees need more than five blocks from other materials");}
            else if(type==ConstructorBuilder.LAMP){lamps++;int px=Math.floorMod(p.getX(),12),pz=Math.floorMod(p.getZ(),12);if(phaseX==null){phaseX=px;phaseZ=pz;}h.assertTrue(px==phaseX&&pz==phaseZ&&distance>3,"lamps must keep their translated twelve-block grid and grass clearance");}
            else h.assertTrue(type>=ConstructorBuilder.FARM&&type<=ConstructorBuilder.POND,"allow the restored small catalog");
        }
        h.assertTrue(grasses>0&&flowers>0&&trees>1&&lamps>1,"produce all requested gardening categories: "+grasses+","+flowers+","+trees+","+lamps);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void gardeningPreservesRoadsPitsBuildingsCropsAndChestLoot(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,512);lawn(level,c,22);ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,32);
        for(int z=-16;z<16;z++)level.setBlockAndUpdate(c.offset(8,0,z),Blocks.STONE.defaultBlockState());
        for(int z=-2;z<=2;z++)for(int y=1;y<=3;y++)level.setBlockAndUpdate(c.offset(-8,y,z),Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos chest=c.offset(-8,1,0);level.setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());ChestBlockEntity box=(ChestBlockEntity)level.getBlockEntity(chest);box.setItem(0,new ItemStack(Items.DIAMOND,3));CompoundTag loot=box.saveWithFullMetadata();
        BlockPos farm=c.offset(10,0,4);level.setBlockAndUpdate(farm,Blocks.FARMLAND.defaultBlockState());level.setBlockAndUpdate(farm.above(),Blocks.CARROTS.defaultBlockState());
        BlockPos water=c.offset(10,0,-4);level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());
        BlockPos pit=c.offset(12,0,10);for(int y=0;y>=-2;y--)level.setBlockAndUpdate(pit.offset(0,y,0),Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pit.below(3),Blocks.STONE.defaultBlockState());
        BlockPos fence=c.offset(-13,1,9);level.setBlockAndUpdate(fence,Blocks.OAK_FENCE.defaultBlockState());
        Map<BlockPos,BlockState> before=groundSnapshot(level,c,16);scan(b,level,d);worker(b,c.south(4));
        for(int tick=0;tick<60000&&!d.finished&&!d.reviewPending;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.finished,"reachable gardening must finish, pending="+d.retryTasks.size()+", report="+d.lastReport);
        h.assertTrue(before.entrySet().stream().allMatch(e->level.getBlockState(e.getKey()).equals(e.getValue())),"never change grass bases, roads, water, holes or underground blocks");
        h.assertTrue(level.getBlockState(chest).is(Blocks.CHEST)&&loot.equals(((ChestBlockEntity)level.getBlockEntity(chest)).saveWithFullMetadata()),"do not replace chests or alter their loot");
        h.assertTrue(level.getBlockState(farm.above()).is(Blocks.CARROTS)&&level.getBlockState(fence).is(Blocks.OAK_FENCE),"preserve crops and existing fences");
        int treeJobs=0,lampJobs=0;for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);BlockPos p=new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));
            if(t.getInt("type")==ConstructorBuilder.TREE){treeJobs++;h.assertTrue(level.getBlockState(p.above()).is(BlockTags.LOGS),"place a complete small tree, not just a sapling");}
            if(t.getInt("type")==ConstructorBuilder.LAMP){lampJobs++;h.assertTrue(level.getBlockState(p.above(6)).is(Blocks.REDSTONE_LAMP)&&level.getBlockState(p.above(6)).getValue(RedstoneLampBlock.LIT),"lamp must actually be powered");}
        }
        h.assertTrue(treeJobs>0&&lampJobs>0&&d.placed>0,"exercise real tree and lamp placement queues");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void limits256AndOldTerrainPlansCannotExecute(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,768);lawn(level,c,12);ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,256);
        h.assertTrue(d.contains(c.getX()+127,c.getZ()+127)&&!d.contains(c.getX()+128,c.getZ()),"256 means a 256 by 256 square");
        CompoundTag old=new CompoundTag();old.putBoolean("configured",true);old.putBoolean("active",true);old.putInt("width",128);old.putInt("centerX",c.getX());old.putInt("centerY",c.getY()+1);old.putInt("centerZ",c.getZ());
        var jobs=new net.minecraft.nbt.ListTag();CompoundTag fill=new CompoundTag();fill.putInt("type",10);jobs.add(fill);old.put("tasks",jobs);old.putBoolean("bounded",true);
        ConstructorData migrated=ConstructorData.load(old);h.assertTrue(migrated.configured&&!migrated.active&&migrated.scanning&&migrated.tasks.isEmpty(),"discard all old terraforming, fence and Lootr work before resuming");
        var source=level.getServer().createCommandSourceStack().withPermission(4).withLevel(level).withPosition(new net.minecraft.world.phys.Vec3(c.getX()+.5,c.getY()+1,c.getZ()+.5));
        var dispatcher=level.getServer().getCommands().getDispatcher();var valid=dispatcher.parse("constructor crear 256",source);h.assertTrue(valid.getExceptions().isEmpty()&&!valid.getReader().canRead(),"command must accept 256");
        var invalid=dispatcher.parse("constructor crear 257",source);h.assertTrue(!invalid.getExceptions().isEmpty()||invalid.getReader().canRead(),"command must reject 257");
        try{h.assertTrue(b.create(source,256)==1,"create must support 256");ConstructorData actual=ConstructorData.get(level.getServer());h.assertTrue(actual.width==256&&actual.scanning&&!actual.active&&actual.auditOnly,"crear must begin a read-only inspection");}finally{b.cancel(source);}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void navigationStillCrossesPavingSlabsAndCrops(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,1024);lawn(level,c,10);ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,32);
        level.setBlockAndUpdate(c,Blocks.FARMLAND.defaultBlockState());level.setBlockAndUpdate(c.above(),Blocks.CARROTS.defaultBlockState());
        h.assertTrue(c.equals(call(b,"standingGround",new Class[]{ServerLevel.class,int.class,int.class,int.class},level,c.getX(),c.getZ(),c.getY())),"crops must not falsely strand the worker");
        BlockPos slab=c.east(2);level.setBlockAndUpdate(slab,Blocks.STONE_BRICK_SLAB.defaultBlockState());h.assertTrue(Math.abs((double)call(b,"footHeight",new Class[]{ServerLevel.class,BlockPos.class},level,slab)-(slab.getY()+.5))<.001,"follow the slab surface");
        BlockPos paving=c.east(4);level.setBlockAndUpdate(paving,Blocks.STONE_BRICKS.defaultBlockState());h.assertTrue(paving.equals(call(b,"navigationStep",new Class[]{ServerLevel.class,ConstructorData.class,int.class,int.class,int.class},level,d,paving.getX(),paving.getZ(),c.getY())),"walking across paving must remain possible");
        level.setBlockAndUpdate(c.north(3).above(3),Blocks.OAK_PLANKS.defaultBlockState());h.assertTrue(c.north(3).equals(call(b,"navigationStep",new Class[]{ServerLevel.class,ConstructorData.class,int.class,int.class,int.class},level,d,c.getX(),c.getZ()-3,c.getY())),"do not select a roof instead of the ground");
        d.centerZ=c.getZ()-3;BlockPos start=(BlockPos)call(b,"findSafeStart",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(start!=null&&start.getY()==c.getY(),"creating over a wooden roof must select nearby outdoor ground");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void blockedPlantsDoNotPreventReachableWorkAndPersistHonestly(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,1280);lawn(level,c,22);ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,32);d.scanning=false;
        BlockPos isolated=c.offset(8,0,8);for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)if(Math.abs(x)==4||Math.abs(z)==4)for(int y=1;y<=2;y++)level.setBlockAndUpdate(isolated.offset(x,y,z),Blocks.STONE_BRICKS.defaultBlockState());
        d.addTask(ConstructorBuilder.TUFT,isolated.getX(),isolated.getY(),isolated.getZ());BlockPos clear=c.offset(-5,0,-5);d.addTask(ConstructorBuilder.FLOWER,clear.getX(),clear.getY(),clear.getZ());worker(b,clear.west());
        for(int tick=0;tick<3000&&!d.finished&&!d.reviewPending;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(level.getBlockState(clear.above()).is(BlockTags.FLOWERS)&&d.reviewPending&&!d.finished&&d.retryTasks.size()==1,"do reachable work before reporting inaccessible decoration");
        ConstructorData restored=ConstructorData.load(d.save(new CompoundTag()));h.assertTrue(restored.reviewPending&&restored.retryTasks.size()==1,"pending decoration must survive a restart");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void continueRelocatesAndPreservesTheOriginalPartialPlan(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,2560);lawn(level,c,24);ConstructorBuilder b=new ConstructorBuilder();
        var source=level.getServer().createCommandSourceStack().withPermission(4).withLevel(level).withPosition(net.minecraft.world.phys.Vec3.atBottomCenterOf(c.above()));
        ConstructorData d=ConstructorData.get(level.getServer());
        try{
            b.create(source,32);d.scanning=false;d.auditOnly=false;d.tasks.clear();d.retryTasks.clear();
            d.addTask(ConstructorBuilder.TUFT,c.getX()-6,c.getY(),c.getZ());d.taskCursor=1;d.completedJobs=1;
            d.addTask(ConstructorBuilder.LAMP,c.getX(),c.getY(),c.getZ());d.task(1).putBoolean("started",true);
            worker(b,c.east(4));call(b,"prepareTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
            for(int i=0;i<12;i++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
            h.assertTrue(d.placed>0&&d.taskCursor==1,"start a real lamp before simulating a blockage");
            BlockPos bloom=c.offset(-6,0,-6);CompoundTag retry=new CompoundTag();retry.putInt("type",ConstructorBuilder.FLOWER);retry.putInt("x",bloom.getX());retry.putInt("y",bloom.getY());retry.putInt("z",bloom.getZ());d.retryTasks.add(retry);d.retryTasks.add(retry.copy());d.reviewPending=true;
            level.setBlockAndUpdate(c.east(4).above(),Blocks.STONE_BRICKS.defaultBlockState());
            int placed=d.placed;h.assertTrue(b.resume(source)==1,"continuar must relocate from blocked feet");
            h.assertTrue(d.tasks.size()==3&&d.taskCursor==1&&d.completedJobs==1&&d.placed==placed&&d.retryTasks.isEmpty()&&!d.reviewPending,"keep completed prefix, partial lamp and unique pending flower without replanning");
            h.assertTrue(d.positionSaved&&Math.max(Math.abs(d.workerX-(c.getX()+4.5)),Math.abs(d.workerZ-(c.getZ()+.5)))>=2,"move the gardener at least two blocks");
            ConstructorData copy=ConstructorData.load(d.save(new CompoundTag()));ConstructorBuilder afterRestart=new ConstructorBuilder();
            h.assertTrue((boolean)call(afterRestart,"ensurePosition",new Class[]{ServerLevel.class,ConstructorData.class},level,copy)&&copy.workerX==d.workerX&&copy.workerY==d.workerY&&copy.workerZ==d.workerZ,"saved position survives reload");
            h.assertTrue(copy.taskCursor==1&&copy.tasks.size()==3&&copy.task(1).getBoolean("started"),"partial plan survives reload");
            for(int i=0;i<15000&&!d.finished&&!d.reviewPending;i++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
            h.assertTrue(d.finished&&d.retryTasks.isEmpty()&&d.completedJobs==3&&level.getBlockState(bloom.above()).is(BlockTags.FLOWERS),"complete the same lamp and pending flower after rescue");
            int lamps=0;for(int i=0;i<d.memory.size();i++)if(d.memory.getCompound(i).getInt("type")==ConstructorBuilder.LAMP)lamps++;
            h.assertTrue(lamps==1,"rescuing a partial lamp must record exactly one lamp");
            var dispatcher=level.getServer().getCommands().getDispatcher();
            for(String command:new String[]{"jardinero ir","jardinero continuar","constructor continuar","constructor ir"}){
                var parse=dispatcher.parse(command,source);h.assertTrue(parse.getExceptions().isEmpty()&&!parse.getReader().canRead(),"new and legacy command names must parse: "+command);
            }
            var denied=dispatcher.parse("jardinero continuar",source.withPermission(0));h.assertTrue(!denied.getExceptions().isEmpty()||denied.getReader().canRead(),"gardener management remains OP-only");
            boolean consoleRejected=false;try{b.teleportTo(source);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){consoleRejected=true;}h.assertTrue(consoleRejected,"ir requires a player, not console teleport");
            call(b,"ensureActor",new Class[]{ServerLevel.class},level);Field actor=ConstructorBuilder.class.getDeclaredField("actor");actor.setAccessible(true);var npc=(net.minecraftforge.common.util.FakePlayer)actor.get(b);
            h.assertTrue(npc.getGameProfile().getName().equals("Jardinero")&&npc.getMainHandItem().is(Items.IRON_HOE),"name and gardening outfit are applied to the visible NPC");
        }finally{b.cancel(source);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void rescueWithoutSafeGroundLeavesProgressIntact(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,2816);lawn(level,c,24);ConstructorBuilder b=new ConstructorBuilder();
        var source=level.getServer().createCommandSourceStack().withPermission(4).withLevel(level).withPosition(net.minecraft.world.phys.Vec3.atBottomCenterOf(c.above()));
        try{
            b.create(source,32);ConstructorData d=ConstructorData.get(level.getServer());d.scanning=false;d.auditOnly=false;d.tasks.clear();d.addTask(ConstructorBuilder.FLOWER,c.getX()+12,c.getY(),c.getZ());d.reviewPending=true;
            for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++)for(int dy=1;dy<=3;dy++)level.setBlockAndUpdate(c.offset(dx,dy,dz),Blocks.STONE_BRICKS.defaultBlockState());
            double x=d.workerX,z=d.workerZ;h.assertTrue(b.resume(source)==0&&d.tasks.size()==1&&d.taskCursor==0&&d.reviewPending&&!d.active&&d.workerX==x&&d.workerZ==z,"failed rescue must not erase tasks, move to a roof, restart or activate work");
        }finally{b.cancel(source);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void residentTicketsKeepRemoteWorkAvailableAndReleaseOnlyOurTickets(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,4096);lawn(level,c,24);ConstructorBuilder b=new ConstructorBuilder(),other=new ConstructorBuilder();ConstructorData d=data(level,c,32);d.scanning=false;d.active=true;d.addTask(ConstructorBuilder.FLOWER,c.getX()+4,c.getY(),c.getZ());worker(b,c);
        call(b,"updateChunks",new Class[]{ServerLevel.class,ConstructorData.class},level,d);call(other,"updateChunks",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        Field tickets=ConstructorBuilder.class.getDeclaredField("heldChunks");tickets.setAccessible(true);int count=((Set<?>)tickets.get(b)).size();
        h.assertTrue(count>0&&count<=16,"32-block assignment holds only its bounded survey halo");
        h.assertTrue(level.getServer().getPlayerList().getPlayers().stream().noneMatch(p->p.distanceToSqr(c.getX(),c.getY(),c.getZ())<128*128),"remote fixture must have no nearby players");
        // Wait past vanilla's temporary getChunk ticket; neither NPC is a logged-in player.
        h.runAfterDelay(360,()->{
            try{
                call(b,"releaseChunks",new Class[]{});
                h.assertTrue(((Set<?>)tickets.get(b)).isEmpty()&&((Set<?>)tickets.get(other)).size()==count,"releasing one gardener must not release another owner's tickets");
                h.assertTrue(level.hasChunkAt(c),"the remote map must still be available after temporary load tickets expire");
                for(int i=0;i<3000&&!d.finished&&!d.reviewPending;i++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
                h.assertTrue(d.finished&&level.getBlockState(c.east(4).above()).is(BlockTags.FLOWERS),"remote gardening must complete with no players around");
                call(other,"releaseChunks",new Class[]{});h.assertTrue(((Set<?>)tickets.get(other)).isEmpty(),"all private tickets are released at completion");h.succeed();
            }catch(Exception e){throw new RuntimeException(e);}finally{try{call(b,"releaseChunks",new Class[]{});call(other,"releaseChunks",new Class[]{});}catch(Exception e){throw new RuntimeException(e);}}
        });
    }
}
