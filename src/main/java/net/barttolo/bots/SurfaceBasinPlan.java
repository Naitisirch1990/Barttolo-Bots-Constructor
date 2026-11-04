package net.barttolo.bots;

import java.util.*;

/** Priority flood: fill enclosed surface depressions to their spill rim, never open cliff valleys. */
public final class SurfaceBasinPlan {
    public record Cell(int x,int z){Cell next(int dx,int dz){return new Cell(x+dx,z+dz);}}
    private record Node(Cell cell,int level){}
    public record Plan(Map<Cell,Integer> targets,int basins,int oversized,int tooDeep){}
    public static Plan plan(Map<Cell,Integer> ground,Set<Cell> protectedCells,int minX,int minZ,int width){
        Comparator<Node> order=Comparator.comparingInt(Node::level).thenComparingInt(n->n.cell.x).thenComparingInt(n->n.cell.z);
        PriorityQueue<Node> open=new PriorityQueue<>(order);Set<Cell> seen=new HashSet<>();Map<Cell,Integer> raised=new HashMap<>();
        int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
        for(var e:ground.entrySet()){
            Cell c=e.getKey();boolean drain=c.x==minX||c.z==minZ||c.x==minX+width-1||c.z==minZ+width-1||protectedCells.contains(c);
            for(int[] dir:dirs)if(!ground.containsKey(c.next(dir[0],dir[1])))drain=true;
            if(drain&&seen.add(c))open.add(new Node(c,e.getValue()));
        }
        while(!open.isEmpty()){
            Node n=open.remove();for(int[] dir:dirs){Cell c=n.cell.next(dir[0],dir[1]);Integer y=ground.get(c);
                if(y==null||!seen.add(c))continue;int level=Math.max(y,n.level);open.add(new Node(c,level));
                if(level>y&&!protectedCells.contains(c))raised.put(c,level);
            }
        }
        Set<Cell> grouped=new HashSet<>();Map<Cell,Integer> accepted=new HashMap<>();int count=0,large=0,deep=0;
        for(Cell seed:raised.keySet())if(grouped.add(seed)){
            List<Cell> cells=new ArrayList<>();ArrayDeque<Cell> queue=new ArrayDeque<>();queue.add(seed);int depth=0;
            while(!queue.isEmpty()){Cell c=queue.removeFirst();cells.add(c);depth=Math.max(depth,raised.get(c)-ground.get(c));
                for(int[] dir:dirs){Cell next=c.next(dir[0],dir[1]);if(raised.containsKey(next)&&grouped.add(next))queue.addLast(next);}
            }
            if(cells.size()>96){large++;continue;}if(depth>6){deep++;continue;}count++;
            for(Cell c:cells)accepted.put(c,raised.get(c));
        }
        List<Cell> cells=new ArrayList<>(accepted.keySet());cells.sort(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z));
        Map<Cell,Integer> stable=new LinkedHashMap<>();for(Cell c:cells)stable.put(c,accepted.get(c));
        return new Plan(Collections.unmodifiableMap(stable),count,large,deep);
    }
    private SurfaceBasinPlan(){}
}
