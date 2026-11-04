import java.util.*;
import net.barttolo.bots.RoadLayout;
import net.barttolo.bots.RoadLayout.Cell;

public class RoadLayoutChecks {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        for(int width=1;width<=3;width++)for(boolean alongX:new boolean[]{true,false}){
            Set<Cell> original=new HashSet<>();
            for(int a=-12;a<=12;a++)for(int b=0;b<width;b++)original.add(alongX?new Cell(a,b):new Cell(b,a));
            var plan=RoadLayout.plan(original);
            for(int a=-9;a<=9;a++){
                int count=0;for(int b=-5;b<=5;b++)if(plan.corridor().contains(alongX?new Cell(a,b):new Cell(b,a)))count++;
                check(count==3,"width "+width+" must normalize to 3, got "+count);
            }
            check(plan.corridor().containsAll(original),"must preserve the original corridor");
        }
        Set<Cell> gap=new HashSet<>();for(int x=-12;x<=12;x++)if(x!=0)gap.add(new Cell(x,-7));
        check(RoadLayout.plan(gap).corridor().contains(new Cell(0,-7)),"repair one missing path column");
        Set<Cell> crossing=new HashSet<>();for(int a=-12;a<=12;a++)for(int b=-1;b<=1;b++){crossing.add(new Cell(a,b));crossing.add(new Cell(b,a));}
        check(RoadLayout.plan(crossing).corridor().containsAll(crossing),"crossing remains connected");
        check(RoadLayout.plan(Set.of(new Cell(1,1))).axes().isEmpty(),"an isolated tile is not a road axis");
        check(RoadLayout.entrance(0,5,0,0,0,1),"protect approach to door");
        check(RoadLayout.entrance(0,-5,0,0,0,1),"protect reverse-facing doorway");
        check(!RoadLayout.entrance(4,5,0,0,0,1),"allow sites away from entry corridor");
        check(RoadLayout.plan(crossing).equals(RoadLayout.plan(new HashSet<>(crossing))),"stable ordering");
        System.out.println("Road layout checks passed: widths, axes, gaps, crossings, entrances and stable layout.");
    }
}
