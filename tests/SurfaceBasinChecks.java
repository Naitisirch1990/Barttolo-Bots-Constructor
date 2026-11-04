import java.util.*;
import net.barttolo.bots.SurfaceBasinPlan;
import net.barttolo.bots.SurfaceBasinPlan.Cell;
public class SurfaceBasinChecks {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        Map<Cell,Integer> g=new HashMap<>();for(int x=-8;x<8;x++)for(int z=-8;z<8;z++)g.put(new Cell(x,z),70);
        for(int x=0;x<4;x++)for(int z=0;z<4;z++)g.put(new Cell(x,z),x==3&&z==3?67:69);
        var p=SurfaceBasinPlan.plan(g,Set.of(),-8,-8,16);
        check(p.basins()==1&&p.targets().size()==16,"fill a complete stepped 4x4 hole, including its interior");
        check(p.targets().values().stream().allMatch(y->y==70),"match the surrounding lawn rim");
        var water=SurfaceBasinPlan.plan(g,Set.of(new Cell(2,2)),-8,-8,16);check(!water.targets().containsKey(new Cell(2,2)),"preserve protected water/crop cells");
        Map<Cell,Integer> open=new HashMap<>();for(int x=-8;x<8;x++)for(int z=-8;z<8;z++)open.put(new Cell(x,z),x<0?70:65);
        check(SurfaceBasinPlan.plan(open,Set.of(),-8,-8,16).targets().isEmpty(),"do not level an open valley as a pit");
        g.put(new Cell(3,3),60);check(SurfaceBasinPlan.plan(g,Set.of(),-8,-8,16).tooDeep()==1,"report deep caves instead of plugging only their top");
        check(p.equals(SurfaceBasinPlan.plan(original(),Set.of(),-8,-8,16)),"stable plan");
        System.out.println("Surface basin checks passed.");
    }
    static Map<Cell,Integer> original(){Map<Cell,Integer> g=new HashMap<>();for(int x=-8;x<8;x++)for(int z=-8;z<8;z++)g.put(new Cell(x,z),70);for(int x=0;x<4;x++)for(int z=0;z<4;z++)g.put(new Cell(x,z),x==3&&z==3?67:69);return g;}
}
