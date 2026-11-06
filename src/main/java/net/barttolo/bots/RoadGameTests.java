package net.barttolo.bots;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;

@GameTestHolder(Bots.ID)
@PrefixGameTestTemplate(false)
public final class RoadGameTests {
    static RoadConstructor.Data project(GameTestHelper h,int offset){
        var l=h.getLevel();BlockPos a=h.absolutePos(new BlockPos(offset,2,4));
        a=new BlockPos(a.getX(),l.getMaxBuildHeight()-16,a.getZ());
        var d=new RoadConstructor.Data();d.a=a;d.b=a.east(4);d.dimension=l.dimension().location().toString();d.state="PLANIFICANDO";
        for(int x=0;x<=4;x++)for(int z=-2;z<=2;z++){
            BlockPos p=a.offset(x,-1,z);l.setBlockAndUpdate(p.below(),Blocks.STONE.defaultBlockState());
            l.setBlockAndUpdate(p,Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=1;y<=3;y++)l.setBlockAndUpdate(p.above(y),Blocks.AIR.defaultBlockState());
        }
        return d;
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void roadBuildPauseRestoreAndUndo(GameTestHelper h){
        var d=project(h,4096);var b=new RoadConstructor();var l=h.getLevel();
        try{
            for(int i=0;i<10&&d.state.equals("PLANIFICANDO");i++)b.tickProject(l.getServer(),d);
            h.assertTrue(d.state.equals("LISTO")&&d.changes.isEmpty(),"Planning must validate without changing terrain");
            d.state="CONSTRUYENDO";b.tickProject(l.getServer(),d);
            h.assertTrue(d.cursor==8&&d.changes.size()==8,"Construction must respect eight changes per tick");
            var saved=RoadConstructor.Data.load(d.save(new CompoundTag()));
            h.assertTrue(saved.state.equals("PAUSADO")&&saved.cursor==8,"Restart must pause and preserve progress");
            saved.state="CONSTRUYENDO";
            for(int i=0;i<10&&saved.state.equals("CONSTRUYENDO");i++)b.tickProject(l.getServer(),saved);
            h.assertTrue(saved.state.equals("TERMINADO")&&saved.changes.size()==25,"Resume must complete without duplicate journal entries");
            for(int i=0;i<25;i++)h.assertTrue(l.getBlockState(saved.cell(i)).is(Blocks.STONE_BRICKS),"Five-wide road must be continuous");
            saved.undo=24;saved.state="DESHACIENDO";
            for(int i=0;i<10&&saved.state.equals("DESHACIENDO");i++)b.tickProject(l.getServer(),saved);
            h.assertTrue(saved.changes.isEmpty(),"Undo must clear history after completion");
            for(int i=0;i<25;i++)h.assertTrue(l.getBlockState(saved.cell(i)).is(Blocks.GRASS_BLOCK),"Undo must restore exact original ground");
            h.succeed();
        }finally{b.shutdown();}
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void roadRejectsWaterAndUndoPreservesPlayerEdits(GameTestHelper h){
        var d=project(h,4352);var b=new RoadConstructor();var l=h.getLevel();
        try{
            l.setBlockAndUpdate(d.cell(0),Blocks.WATER.defaultBlockState());b.tickProject(l.getServer(),d);
            h.assertTrue(d.state.equals("RECHAZADO")&&d.changes.isEmpty(),"Water must reject the plan without edits");
            l.setBlockAndUpdate(d.cell(0),Blocks.GRASS_BLOCK.defaultBlockState());d.state="CONSTRUYENDO";
            b.tickProject(l.getServer(),d);BlockPos last=d.cell(7);
            l.setBlockAndUpdate(last,Blocks.GOLD_BLOCK.defaultBlockState());d.state="DESHACIENDO";d.undo=7;
            b.tickProject(l.getServer(),d);
            h.assertTrue(d.state.equals("DESHACER_PAUSADO")&&l.getBlockState(last).is(Blocks.GOLD_BLOCK),"Undo must stop and preserve a later player edit");
            h.succeed();
        }finally{b.shutdown();}
    }
}
