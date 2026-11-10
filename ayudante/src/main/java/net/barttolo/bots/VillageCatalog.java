package net.barttolo.ayudante;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Small vanilla builds, bounded by a 7x7 footprint; all fronts face south before rotation. */
public final class VillageCatalog {
    public static final int BENCH=20,PERGOLA=21,WELL=22,STALL=23,PICNIC=24,POND=25;
    public record Part(BlockPos offset,BlockState state) {}
    private final LinkedHashMap<BlockPos,BlockState> blocks=new LinkedHashMap<>();
    private final Block fence,log,slab,stairs,trapdoor;
    private int style;
    private VillageCatalog(Block fence,Block log,Block slab,Block stairs,Block trapdoor){this.fence=fence;this.log=log;this.slab=slab;this.stairs=stairs;this.trapdoor=trapdoor;}
    private void put(int x,int y,int z,Block b){put(x,y,z,b.defaultBlockState());}
    private void put(int x,int y,int z,BlockState s){blocks.put(new BlockPos(x,y,z),s);}
    private void leaves(int x,int y,int z,boolean flowering){put(x,y,z,(flowering&&style==0?Blocks.FLOWERING_AZALEA_LEAVES:Blocks.OAK_LEAVES).defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));}
    private void seat(int x,int z){put(x,1,z,stairs.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH));}
    private void planter(int x,int z){put(x,0,z,Blocks.MOSS_BLOCK);put(x,1,z,style==1?Blocks.FLOWERING_AZALEA:Blocks.AZALEA);}
    private void water(int x,int z){put(x,-1,z,Blocks.CLAY);put(x,0,z,Blocks.WATER);}
    private void path(int x,int z){put(x,0,z,Blocks.DIRT_PATH);}
    private void post(int x,int z,int height){for(int y=1;y<=height;y++)put(x,y,z,fence);}
    private void roof(int y,boolean green){
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            if(Math.abs(x)==2||Math.abs(z)==2||x==0)put(x,y,z,slab);
            if(green&&(Math.abs(x)==2||Math.abs(z)==2)&&Math.floorMod(x+z,2)==0)leaves(x,y+1,z,true);
        }
    }
    private void farm(int variant){
        // A hydrated 5x5 bed inside a 7x7 frame, with a clear southern entrance.
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(Math.abs(x)==3||Math.abs(z)==3){
            if(x==0&&z==3){path(x,z);continue;}
            if(variant==1){put(x,0,z,Blocks.STONE_BRICKS);if(Math.abs(x)==3&&Math.abs(z)==3)put(x,1,z,Blocks.STONE_BRICK_WALL);}
            else{put(x,0,z,log);put(x,1,z,fence);}
        }
        water(0,0);put(0,1,0,Blocks.LILY_PAD);
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            if(x==0&&z==0)continue;
            if(x==0&&z>0){path(x,z);continue;}
            if(x==2&&z==-2){put(x,1,z,Blocks.COMPOSTER);continue;}
            if(x==-2&&z==-2){put(x,1,z,variant==2?Blocks.HAY_BLOCK:Blocks.BARREL);continue;}
            put(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
            Block crop=variant==0?(x<0?Blocks.WHEAT:Blocks.CARROTS):variant==1?(x<0?Blocks.POTATOES:Blocks.BEETROOTS):(z<0?Blocks.WHEAT:Blocks.POTATOES);
            put(x,1,z,crop);
        }
        for(int x:new int[]{-3,3}){post(x,-3,2);put(x,3,-3,Blocks.LANTERN);}
        put(2,1,2,Blocks.HAY_BLOCK); // one small supply stack, outside the entrance lane
        // The supply stack replaces a crop bed: restore soil underneath, rather than duplicate placements.
        put(2,0,2,Blocks.DIRT);
    }
    private void decorate(int type,int variant){
        switch(type){
            case BENCH -> {
                for(int x=-1;x<=1;x++)seat(x,0);
                for(int x:new int[]{-2,2}){put(x,1,0,slab);planter(x,-1);put(x,1,1,trapdoor.defaultBlockState().setValue(TrapDoorBlock.OPEN,true).setValue(TrapDoorBlock.FACING,x<0?Direction.WEST:Direction.EAST));}
                for(int x=-1;x<=1;x++)path(x,2);
                put(0,1,-1,Blocks.MOSS_BLOCK);put(0,2,-1,Blocks.LANTERN);
            }
            case PERGOLA -> {
                for(int x:new int[]{-2,2})for(int z:new int[]{-2,2})post(x,z,3);
                roof(4,true);for(int z=-2;z<=2;z++)path(0,z);
                for(int x:new int[]{-1,1}){seat(x,-1);planter(x,1);}
                put(0,3,-2,Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING,true));
                for(int x:new int[]{-2,2})leaves(x,2,-2,true);
            }
            case WELL -> {
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
                    if(Math.abs(x)==2||Math.abs(z)==2)put(x,0,z,variant==0?Blocks.STONE_BRICKS:Blocks.MOSSY_STONE_BRICKS);
                    else water(x,z);
                }
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(x!=0||z!=0)put(x,1,z,Blocks.STONE_BRICK_WALL);
                for(int x:new int[]{-2,2}){put(x,1,0,Blocks.STONE_BRICKS);post(x,0,3);}
                roof(4,false);put(0,3,0,Blocks.CHAIN);put(0,2,0,Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING,true));
                for(int x=-1;x<=1;x++)path(x,3);
                planter(-2,2);planter(2,2);
            }
            case STALL -> {
                for(int x:new int[]{-2,2})for(int z:new int[]{-1,1})post(x,z,3);
                for(int x=-2;x<=2;x++)for(int z=-1;z<=1;z++)put(x,4,z,(x+variant)%2==0?Blocks.WHITE_WOOL:variant==0?Blocks.RED_WOOL:Blocks.GREEN_WOOL);
                for(int x=-1;x<=1;x++){put(x,1,0,Blocks.BARREL);path(x,2);}
                put(-1,2,0,Blocks.POTTED_DANDELION);put(0,2,0,Blocks.HAY_BLOCK);put(1,2,0,Blocks.POTTED_POPPY);
                put(2,3,1,Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING,true));
                planter(-3,0);planter(3,0);
            }
            case PICNIC -> {
                for(int x=-1;x<=1;x++){put(x,1,0,slab);seat(x,2);put(x,1,-2,stairs.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));}
                post(0,0,3);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)put(x,4,z,(x+z)%2==0?Blocks.WHITE_WOOL:variant==0?Blocks.YELLOW_WOOL:Blocks.RED_WOOL);
                planter(-3,0);planter(3,0);put(2,3,0,Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING,true));
            }
            case POND -> {
                for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
                    int r=x*x+z*z;if(r<=5)water(x,z);
                    else if(r<=10)put(x,0,z,(x+z)%2==0?log:Blocks.MOSSY_COBBLESTONE);
                }
                put(-1,1,0,Blocks.LILY_PAD);put(1,1,1,Blocks.LILY_PAD);
                for(int x:new int[]{-2,2}){planter(x,2);put(x,1,-2,Blocks.POTTED_BLUE_ORCHID);}
                put(0,1,-3,Blocks.LANTERN);path(0,3);
            }
            default -> throw new IllegalArgumentException("Unknown catalog type "+type);
        }
    }
    public static List<Part> build(int type,int variant,Block fence,Block log,Block slab,Block stairs,Block trapdoor){
        VillageCatalog c=new VillageCatalog(fence,log,slab,stairs,trapdoor);c.style=Math.floorMod(variant,2);
        if(type==6)c.farm(Math.floorMod(variant,3));else c.decorate(type,Math.floorMod(variant,2));
        // Ground and supports first; then crops, furniture, roofs and detail. Water is confined by the unchanged ground.
        List<Part> result=new ArrayList<>();c.blocks.forEach((p,s)->result.add(new Part(p,s)));
        result.sort(Comparator.comparingInt(p->p.offset().getY()));return List.copyOf(result);
    }
    public static boolean contains(int type){return type>=BENCH&&type<=POND;}
    private VillageCatalog(){throw new AssertionError();}
}
