import java.util.*;
import net.barttolo.bots.TerrainApronPlan;
import net.barttolo.bots.TerrainApronPlan.Cell;

public class TerrainApronChecks {
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static TerrainApronPlan.Plan edge(int drop,boolean irregular,Set<Cell> blocked,int width){
        Map<Cell,Integer> heights=new HashMap<>();Set<Cell> anchors=new HashSet<>();
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            Cell c=new Cell(x,z);int y=x<=0?80:80-drop-(irregular&&x>2&&z%3==0?1:0);heights.put(c,y);if(x<=0)anchors.add(c);
        }
        return TerrainApronPlan.plan(heights,anchors,blocked,-16,-16,width);
    }
    public static void main(String[] args){
        for(int drop:new int[]{2,3,4,8}){
            var p=edge(drop,false,Set.of(),32);check(p.edges()>=30,"detect the entire edge");
            for(int z=-12;z<=12;z++)for(int x=1;x<drop*2-1;x++)
                check(p.targets().get(new Cell(x,z))==80-(x+1)/2,"continuous two-block grass rows for drop "+drop);
            check(p.targets().values().stream().allMatch(y->y<80&&y>80-drop),"only fill below the platform and above original ground");
        }
        var mixed=edge(3,true,Set.of(new Cell(2,0)),32);
        check(!mixed.targets().containsKey(new Cell(2,0)),"protect crop/tree/water cells");
        check(mixed.targets().containsKey(new Cell(2,3)),"an obstacle must not cancel other edge rows");
        check(mixed.blocked()>0,"report obstructed columns");
        var deep=edge(13,false,Set.of(),32);check(deep.targets().isEmpty()&&deep.tooDeep()>0,"report unsupported depth instead of silently doing nothing");
        var clipped=edge(12,false,Set.of(),32);check(clipped.clipped()>0,"report the plot boundary");
        check(clipped.targets().keySet().stream().allMatch(c->c.x()>=-16&&c.x()<16&&c.z()>=-16&&c.z()<16),"never fill outside the configured square");
        check(edge(3,false,Set.of(),32).equals(edge(3,false,Set.of(),32)),"deterministic apron");
        System.out.println("Continuous terrain apron checks passed.");
    }
}
