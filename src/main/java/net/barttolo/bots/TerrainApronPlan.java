package net.barttolo.bots;

import java.util.*;

/** A continuous grass apron along a flat terrace: two horizontal columns per one-block descent. */
public final class TerrainApronPlan {
    public record Cell(int x,int z){Cell step(int dx,int dz,int n){return new Cell(x+dx*n,z+dz*n);}}
    public record Plan(Map<Cell,Integer> targets,int edges,int tooDeep,int blocked,int clipped){}
    public static Plan plan(Map<Cell,Integer> heights,Set<Cell> anchors,Set<Cell> blocked,int minX,int minZ,int width){
        return plan(heights,anchors,blocked,minX,minZ,width,width);
    }
    public static Plan plan(Map<Cell,Integer> heights,Set<Cell> anchors,Set<Cell> blocked,int minX,int minZ,int width,int depth){
        Map<Cell,Integer> targets=new HashMap<>();Set<Cell> obstructions=new HashSet<>(),outside=new HashSet<>();int edges=0,deep=0;
        List<Cell> ordered=new ArrayList<>(anchors);ordered.sort(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z));
        for(Cell high:ordered){Integer upper=heights.get(high);if(upper==null||blocked.contains(high))continue;
            for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){int dx=dir[0],dz=dir[1];
                Integer low=heights.get(high.step(dx,dz,1));if(low==null||upper-low<2)continue;
                // A continuous, flat inland platform distinguishes the cut edge from a small natural bump.
                if(!Objects.equals(heights.get(high.step(dx,dz,-1)),upper)||!Objects.equals(heights.get(high.step(dx,dz,-2)),upper))continue;
                if(!Objects.equals(heights.get(high.step(-dz,dx,1)),upper)&&!Objects.equals(heights.get(high.step(-dz,dx,-1)),upper))continue;
                int drop=upper-low;edges++;if(drop>12){deep++;continue;}
                for(int step=1;step<=drop*2;step++){
                    Cell c=high.step(dx,dz,step);int target=upper-(step+1)/2;
                    if(c.x<minX||c.x>=minX+width||c.z<minZ||c.z>=minZ+depth){outside.add(c);break;}
                    Integer ground=heights.get(c);
                    if(ground==null||blocked.contains(c)){obstructions.add(c);break;}
                    if(ground>=target)break; // Land on existing ground; never cut into a hillside.
                    if(target-ground>12){obstructions.add(c);break;}
                    targets.merge(c,target,Math::max);
                }
            }
        }
        List<Cell> cells=new ArrayList<>(targets.keySet());cells.sort(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z));
        Map<Cell,Integer> stable=new LinkedHashMap<>();for(Cell c:cells)stable.put(c,targets.get(c));
        return new Plan(Collections.unmodifiableMap(stable),edges,deep,obstructions.size(),outside.size());
    }
    private TerrainApronPlan(){}
}
