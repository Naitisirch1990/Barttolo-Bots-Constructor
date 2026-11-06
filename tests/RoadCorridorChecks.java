import net.barttolo.bots.RoadCorridor;
import java.util.*;
public class RoadCorridorChecks {
    public static void main(String[] args){
        for(int[] end:new int[][]{{20,0},{-20,0},{0,20},{0,-20}}){
            Set<String> cells=new HashSet<>();
            for(int i=0;i<105;i++){
                int[] p=RoadCorridor.cell(0,0,end[0],end[1],i);
                if(Math.abs(end[0]==0?p[0]:p[1])>2)throw new AssertionError("Outside five-block lane");
                if(!cells.add(Arrays.toString(p)))throw new AssertionError("Duplicate placement");
            }
            if(cells.size()!=105)throw new AssertionError("Lane gaps");
            for(int i=0;i<5;i++){
                int[] p=RoadCorridor.cell(0,0,end[0],end[1],100+i);
                if((end[0]==0?p[1]:p[0])!=(end[0]==0?end[1]:end[0]))throw new AssertionError("Destination omitted");
            }
        }
        RoadCorridor.cell(0,0,2000,0,10004);
        reject(1,1,0);reject(0,0,0);reject(2001,0,0);reject(1,0,10);reject(1,0,-1);
        System.out.println("Road corridor checks passed: four directions, width, uniqueness, endpoints and limits.");
    }
    static void reject(int x,int z,int index){try{RoadCorridor.cell(0,0,x,z,index);}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid corridor accepted");}
}
