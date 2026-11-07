package net.barttolo.bots;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;

@GameTestHolder(Bots.ID)
@PrefixGameTestTemplate(false)
public final class AdaptiveRoadGameTests {
    static BlockPos ground(GameTestHelper h,int offset){
        var l=h.getLevel();BlockPos p=h.absolutePos(new BlockPos(offset,2,0));p=new BlockPos(p.getX(),l.getMaxBuildHeight()-24,p.getZ());
        for(int x=-6;x<=20;x++)for(int z=-8;z<=12;z++){
            for(int y=-5;y<=-2;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.STONE.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=0;y<=9;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        return p;
    }
    static AdaptiveRoadConstructor.Data data(GameTestHelper h,BlockPos a,BlockPos b){
        var d=new AdaptiveRoadConstructor.Data();d.a=a;d.b=b;d.dimension=h.getLevel().dimension().location().toString();return d;
    }
    static void route(AdaptiveRoadConstructor.Data d,BlockPos p,int kind){CompoundTag t=new CompoundTag();t.putLong("pos",p.asLong());t.putInt("kind",kind);d.route.add(t);}
    static void drive(GameTestHelper h,AdaptiveRoadConstructor bot,AdaptiveRoadConstructor.Data d,Consumer<AdaptiveRoadConstructor.Data> ready,Consumer<AdaptiveRoadConstructor.Data> done){
        final AdaptiveRoadConstructor.Data[] active={d};final boolean[] checked={false};
        h.succeedWhen(()->{
            var job=active[0];
            if(job.state.equals("RECHAZADO")||job.state.equals("PAUSADO")){bot.shutdown();h.assertTrue(false,"Adaptive road halted: "+job.summary());return;}
            int count=job.changes.size();bot.tickProject(h.getLevel().getServer(),job);
            h.assertTrue(job.changes.size()-count<=8,"At most eight block edits per tick");
            if(job.state.equals("LISTO")){ready.accept(job);job.state="CONSTRUYENDO";}
            if(!checked[0]&&job.cursor>3&&job.state.equals("CONSTRUYENDO")){
                var restored=AdaptiveRoadConstructor.Data.load(job.save(new CompoundTag()));
                h.assertTrue(restored.state.equals("PAUSADO")&&restored.cursor==job.cursor,"Checkpoint survives restart and pauses");restored.state="CONSTRUYENDO";active[0]=restored;checked[0]=true;
            }
            if(active[0].state.equals("TERMINADO")){done.accept(active[0]);bot.shutdown();return;}
            throw new GameTestAssertException("Waiting for adaptive road: "+active[0].summary());
        });
    }
    @GameTest(template="empty",timeoutTicks=1600)
    public static void riverAtDeckHeightWithAquaticPlantsAndOneBlockRise(GameTestHelper h){
        BlockPos p=ground(h,6804);var l=h.getLevel();
        for(int x=2;x<=6;x++)for(int z=-4;z<=4;z++){
            l.setBlockAndUpdate(p.offset(x,-3,z),Blocks.STONE.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,-2,z),Blocks.WATER.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,-1,z),Blocks.WATER.defaultBlockState());
        }
        l.setBlockAndUpdate(p.offset(4,-2,0),Blocks.SEAGRASS.defaultBlockState());
        for(int x=7;x<=12;x++)for(int z=-4;z<=4;z++)l.setBlockAndUpdate(p.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());
        var d=data(h,p,p.offset(8,1,0));var b=new AdaptiveRoadConstructor();b.begin(d);
        drive(h,b,d,job->h.assertTrue(job.bridges>0&&job.route.size()==9,"Direct river crossing with different bank heights"),
            job->{
                h.assertTrue(job.cursor==job.tasks.size(),"Finish the river crossing");
                job.state="DESHACIENDO";job.undo=job.changes.size()-1;
                for(int i=0;i<300&&job.state.equals("DESHACIENDO");i++)b.undoTick(l.getServer(),l,job);
                h.assertTrue(job.changes.isEmpty(),"Undo the complete bridge");
                h.assertTrue(l.getBlockState(p.offset(3,-1,0)).is(Blocks.WATER),"Restore river water");
            });
    }
    @GameTest(template="empty",timeoutTicks=1600)
    public static void findsOffAxisRouteAcrossDifferentHeightsAndExcavates(GameTestHelper h){
        BlockPos p=ground(h,5000);var l=h.getLevel();
        for(int x=5;x<=15;x++)for(int z=-7;z<=10;z++){
            l.setBlockAndUpdate(p.offset(x,-1,z),Blocks.DIRT.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,0,z),Blocks.DIRT.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,1,z),Blocks.GRASS_BLOCK.defaultBlockState());
        }
        var d=data(h,p,p.offset(10,2,4));var b=new AdaptiveRoadConstructor();b.begin(d);
        drive(h,b,d,job->{
            int previousStep=-100;
            for(int i=1;i<job.route.size();i++){
                BlockPos a=BlockPos.of(job.route.getCompound(i-1).getLong("pos")),z=BlockPos.of(job.route.getCompound(i).getLong("pos"));
                if(a.getY()!=z.getY()){h.assertTrue(i-previousStep>=4,"Vertical steps must have three flat sections between them");previousStep=i;}
            }
            h.assertTrue(job.lights>0,"The route must include lighting");
            h.assertTrue(job.route.size()>10&&job.cut>0,"Search must find a non-cardinal, different-height route with excavation");
            h.assertTrue(BlockPos.of(job.route.getCompound(0).getLong("pos")).equals(job.a)&&BlockPos.of(job.route.getCompound(job.route.size()-1).getLong("pos")).equals(job.b),"Route must preserve both endpoints");
        },job->{
            boolean stairs=false;for(int i=0;i<job.tasks.size();i++){var t=job.tasks.getCompound(i);if(t.getCompound("after").getString("Name").equals("minecraft:stone_brick_stairs"))stairs=true;}
            h.assertTrue(stairs&&job.changes.size()>0,"Construct traversable stairs and keep undo journal");
        });
    }
    @GameTest(template="empty",timeoutTicks=1600)
    public static void tunnelHasFiveByFiveClearanceAndProtectedChestIsRejected(GameTestHelper h){
        BlockPos p=ground(h,5256);var l=h.getLevel();
        for(int x=2;x<=6;x++)for(int z=-3;z<=3;z++)for(int y=0;y<=7;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.STONE.defaultBlockState());
        var d=data(h,p,p.east(8));var b=new AdaptiveRoadConstructor();b.begin(d);

        l.setBlockAndUpdate(p.offset(12,0,0),Blocks.CHEST.defaultBlockState());
        h.assertTrue(b.survey(l,p.east(12))==null,"Never excavate a chest in the survey footprint");
        l.setBlockAndUpdate(p.east(18).below(),Blocks.AIR.defaultBlockState());
        for(int y=2;y<=14;y++)l.setBlockAndUpdate(p.east(18).below(y),Blocks.OAK_LEAVES.defaultBlockState());
        h.assertTrue(b.survey(l,p.east(18))==null,"Leaves cannot serve as a bridge foundation");
        drive(h,b,d,job->h.assertTrue(job.tunnels>=5&&job.cut>=125,"Count tunnel sections and excavation"),job->{
            for(int x=2;x<=6;x++)for(int z=-2;z<=2;z++)for(int y=0;y<5;y++)h.assertTrue(l.getBlockState(p.offset(x,y,z)).isAir(),"Tunnel must have five-wide five-high air clearance");
            h.assertTrue(l.getBlockState(p.offset(4,5,0)).is(Blocks.STONE),"Keep the tunnel roof");
        });
    }
    @GameTest(template="empty",timeoutTicks=1600)
    public static void bridgeHasDeckRailsPiersAndCanUndoConnectedWalls(GameTestHelper h){
        BlockPos p=ground(h,5512);var l=h.getLevel();
        for(int x=-4;x<=12;x++)for(int z=-5;z<=5;z++){
            if(x>=3&&x<=5){for(int y=-3;y<=-1;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.WATER.defaultBlockState());}
            else l.setBlockAndUpdate(p.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState());
        }
        var d=data(h,p.above(),p.offset(8,1,0));var b=new AdaptiveRoadConstructor();b.begin(d);

        drive(h,b,d,job->h.assertTrue(job.bridges>=3&&job.lights>0,"Automatically choose a bridge over the river"),job->{
            for(int x=3;x<=5;x++){
                for(int z=-2;z<=2;z++)h.assertTrue(l.getBlockState(p.offset(x,0,z)).isSolid(),"Continuous five-wide bridge deck");
                for(int z:new int[]{-3,3})h.assertTrue(l.getBlockState(p.offset(x,1,z)).getBlock() instanceof WallBlock,"Bridge railings stay outside the five-wide deck");
            }
            h.assertTrue(l.getBlockState(p.offset(5,-2,0)).is(Blocks.STONE_BRICKS),"Bridge pier reaches the river bed");
            job.state="DESHACIENDO";job.undo=job.changes.size()-1;
            for(int i=0;i<100&&job.state.equals("DESHACIENDO");i++)b.undoTick(l.getServer(),l,job);
            h.assertTrue(job.changes.isEmpty()&&l.getBlockState(p.offset(4,0,0)).isAir(),"Undo must also handle auto-connected wall states");
        });
    }
    @GameTest(template="empty",timeoutTicks=1600)
    public static void findsShortStraightRouteAcrossFrozenSurface(GameTestHelper h){
        BlockPos p=ground(h,5652);var l=h.getLevel();
        for(int x=-4;x<=24;x++)for(int z=-5;z<=5;z++){
            for(int y=-5;y<=-2;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.STONE.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,-1,z),Blocks.PACKED_ICE.defaultBlockState());
            for(int y=0;y<=9;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        var d=data(h,p,p.east(20));var b=new AdaptiveRoadConstructor();b.begin(d);
        drive(h,b,d,job->{
            h.assertTrue(job.route.size()==21,"A twenty-block straight path should plan as twenty-one sections");
            h.assertTrue(job.roads==21&&job.bridges==0&&job.tunnels==0,"Frozen level ground should remain a road");
        },job->h.assertTrue(job.cursor==job.tasks.size(),"Finish the frozen-surface route"));
    }
    @GameTest(template="empty",timeoutTicks=2400)
    public static void spansDeepTwentyBlockRavineWithSupportedBridge(GameTestHelper h){
        BlockPos p=ground(h,6404);var l=h.getLevel();
        for(int x=-4;x<=24;x++)for(int z=-5;z<=5;z++){
            for(int y=-5;y<=-2;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.STONE.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=0;y<=9;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        for(int x=3;x<=17;x++)for(int z=-4;z<=4;z++){
            for(int y=-1;y>=-23;y--)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.AIR.defaultBlockState());
            l.setBlockAndUpdate(p.offset(x,-24,z),Blocks.STONE.defaultBlockState());
        }
        var d=data(h,p.above(),p.east(20).above());var b=new AdaptiveRoadConstructor();
        for(int x=0;x<=20;x++)h.assertTrue(b.survey(l,p.east(x).above())!=null,"Survey crossing section "+x);
        b.begin(d);
        drive(h,b,d,job->{
            h.assertTrue(job.route.size()==21,"Keep the direct twenty-block crossing");
            h.assertTrue(job.bridges>=10,"Classify the unsupported ravine span as a bridge");
        },job->{
            h.assertTrue(l.getBlockState(p.offset(10,0,0)).isSolid(),"Bridge deck crosses the ravine");
            h.assertTrue(l.getBlockState(p.offset(10,-24,0)).is(Blocks.STONE),"Keep the ravine bed intact");
        });
    }
    @GameTest(template="empty",timeoutTicks=1600)
    public static void lightsPreserveTunnelClearanceAndAreJournaled(GameTestHelper h){
        BlockPos p=ground(h,5800);var l=h.getLevel();
        for(int x=2;x<=14;x++)for(int z=-3;z<=3;z++)for(int y=0;y<=7;y++)l.setBlockAndUpdate(p.offset(x,y,z),Blocks.STONE.defaultBlockState());
        var d=data(h,p,p.east(16));var b=new AdaptiveRoadConstructor();b.begin(d);
        for(int x=0;x<=16;x++)route(d,p.east(x),x>=2&&x<=14?2:0);d.state="DISEÑANDO";
        drive(h,b,d,job->{
            h.assertTrue(job.lights>=2,"Plan lights on a long tunnel");
            boolean ceiling=false;for(int i=0;i<job.lamps.size();i++)if(job.lamps.getCompound(i).getString("type").equals("ceiling"))ceiling=true;
            h.assertTrue(ceiling,"Use an embedded ceiling light inside the tunnel");
        },job->{
            h.assertTrue(l.getBlockState(p.offset(10,5,0)).is(Blocks.GLOWSTONE),"Embedded light must be above the five-high clearance");
            for(int z=-2;z<=2;z++)for(int y=0;y<5;y++)h.assertTrue(l.getBlockState(p.offset(10,y,z)).isAir(),"Lighting must not obstruct the tunnel");
            job.undo=job.changes.size()-1;job.state="DESHACIENDO";
            for(int i=0;i<300&&job.state.equals("DESHACIENDO");i++)b.undoTick(l.getServer(),l,job);
            h.assertTrue(job.changes.isEmpty()&&l.getBlockState(p.offset(10,5,0)).is(Blocks.STONE),"Undo must restore the embedded light and excavations");
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void visualWorkerWalksInSmallCollisionCheckedSteps(GameTestHelper h){
        BlockPos p=ground(h,6100);var l=h.getLevel();var b=new AdaptiveRoadConstructor();
        try{
            b.showWorker(l,p);
            var entities=l.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class,new net.minecraft.world.phys.AABB(p).inflate(8),e->e.getTags().contains("barttolo_adaptive_worker"));
            h.assertTrue(entities.size()==1,"Spawn a visible mason worker");var worker=entities.get(0);double start=worker.getX();
            b.showWorker(l,p.east(5));
            for(int i=0;i<10;i++){var old=worker.position();b.walkWorker(l);h.assertTrue(worker.position().distanceTo(old)<=.19,"Worker must walk, not teleport across the section");}
            h.assertTrue(worker.getX()>start+1&&worker.getX()<start+3,"Worker must visibly approach its target gradually");
            h.succeed();
        }finally{b.shutdown();}
    }

}
