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
    public static void grassBandsTreesAndExactLampGrid(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=center(h,256);lawn(level,c,38);ConstructorBuilder b=new ConstructorBuilder();ConstructorData d=data(level,c,64);
        for(int z=-25;z<=25;z++)for(int x=-11;x<=-9;x++)level.setBlockAndUpdate(c.offset(x,0,z),Blocks.STONE.defaultBlockState());
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=1;y<=4;y++)level.setBlockAndUpdate(c.offset(x,y,z),Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(c.offset(14,0,4),Blocks.FARMLAND.defaultBlockState());level.setBlockAndUpdate(c.offset(15,0,4),Blocks.WATER.defaultBlockState());
        var before=groundSnapshot(level,c,32);scan(b,level,d);
        h.assertTrue(d.placed==0&&before.entrySet().stream().allMatch(e->level.getBlockState(e.getKey()).equals(e.getValue())),"inspection must not edit the ground");
        int grasses=0,flowers=0,trees=0,lamps=0;
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.task(i);int type=t.getInt("type");BlockPos p=new BlockPos(t.getInt("x"),t.getInt("y"),t.getInt("z"));int distance=nearestObstacle(b,level,p);
            h.assertTrue(ConstructorBuilder.gardenTask(type)&&level.getBlockState(p).is(Blocks.GRASS_BLOCK),"all tasks must start on grass blocks");
            if(type==ConstructorBuilder.TUFT||type==ConstructorBuilder.FLOWER){h.assertTrue(distance==1||distance==3,"plant only the one- and three-block bands");if(type==ConstructorBuilder.TUFT)grasses++;else flowers++;}
            else if(type==ConstructorBuilder.TREE){trees++;h.assertTrue(distance>5,"trees need more than five blocks from other materials");}
            else{lamps++;h.assertTrue(Math.floorMod(p.getX(),16)==0&&Math.floorMod(p.getZ(),16)==0&&distance>3,"lamps must keep their exact grid and grass clearance");}
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
        level.setBlockAndUpdate(c.north(3).above(3),Blocks.OAK_PLANKS.defaultBlockState());h.assertTrue(c.north(3).equals(call(b,"navigationStep",new Class[]{ServerLevel.class,ConstructorData.class,int.class,int.class,int.class},level,d,c.getX(),c.getZ()-3,c.getY())),"do not select a roof instead of the ground");h.succeed();
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
}
