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
    public static void cropsRoadsAndWorkApproaches(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();BlockPos c=h.absolutePos(new BlockPos(4,2,4));ConstructorBuilder b=new ConstructorBuilder();
        ConstructorData d=new ConstructorData();d.width=128;d.centerX=c.getX();d.centerY=c.getY()+1;d.centerZ=c.getZ();d.dimension=level.dimension().location().toString();d.chestsDone=true;
        for(int dx=-5;dx<=5;dx++)for(int dz=-5;dz<=5;dz++){
            BlockPos p=c.offset(dx,0,dz);level.setBlockAndUpdate(p,Blocks.GRASS_BLOCK.defaultBlockState());
            for(int dy=1;dy<=8;dy++)level.setBlockAndUpdate(p.above(dy),Blocks.AIR.defaultBlockState());
        }
        field(b,"visualX",c.getX()+.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        level.setBlockAndUpdate(c,Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(c.above(),Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE,7));
        h.assertTrue((boolean)call(b,"isWalkableGround",new Class[]{ServerLevel.class,BlockPos.class},level,c),"mature carrots on farmland must not strand the worker");
        h.assertTrue(c.equals(call(b,"surface",new Class[]{ServerLevel.class,int.class,int.class},level,c.getX(),c.getZ())),"surface must find farmland below crops");
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
            h.assertTrue(distance>=(type==6?2:4),"worker must stand outside the footprint of farm/worksite/garden");
        }
        level.setBlockAndUpdate(c,Blocks.GRASS_BLOCK.defaultBlockState());
        // Exercise the real farm placement queue, then navigation over its output.
        level.setBlockAndUpdate(dirt,Blocks.GRASS_BLOCK.defaultBlockState());d.tasks.clear();d.taskCursor=0;d.tasks.add(task(6,c));
        call(b,"prepareTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        for(int tick=0;tick<220&&d.taskCursor==0;tick++)call(b,"workTick",new Class[]{net.minecraft.server.MinecraftServer.class,ServerLevel.class,ConstructorData.class},level.getServer(),level,d);
        h.assertTrue(d.taskCursor==1&&level.getBlockState(c).is(Blocks.WATER),"farm task must finish all its placements");
        h.assertTrue((boolean)call(b,"isWalkableGround",new Class[]{ServerLevel.class,BlockPos.class},level,c.west()),"the farm created by the real queue must remain traversable");
        field(b,"visualX",c.getX()-.5);field(b,"visualY",c.getY()+1.0);field(b,"visualZ",c.getZ()+.5);
        d.tasks.clear();d.taskCursor=0;d.tasks.add(task(0,c.offset(4,0,0)));
        call(b,"beginNearestTask",new Class[]{ServerLevel.class,ConstructorData.class},level,d);
        h.assertTrue(d.inaccessibleJobs==0&&d.taskCursor==0,"worker must select another job after standing on its own farm");
        h.succeed();
    }
}
