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
        boolean selected=(boolean)call(b,"beginNearestTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(selected&&d.inaccessibleJobs==0&&d.taskCursor==0,"worker must select another job after standing on its own farm");
        h.succeed();
    }
}
