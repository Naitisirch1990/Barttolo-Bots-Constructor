package net.barttolo.bots;

import java.util.*;

/** Terrain-independent corridor geometry. Never dilate every road tile: find its axis first. */
public final class RoadLayout {
    public record Cell(int x,int z) {
        Cell offset(int dx,int dz){return new Cell(x+dx,z+dz);}
    }
    public record Axis(Cell cell,boolean alongX) {}
    public record Plan(Set<Cell> corridor,List<Axis> axes) {}
    public static Plan plan(Set<Cell> input){
        Set<Cell> seeds=new HashSet<>(input);
        // Repair a single missing column between two existing stretches.
        for(Cell c:input)for(int[] dir:new int[][]{{1,0},{0,1}})
            if(input.contains(c.offset(dir[0]*2,dir[1]*2)))seeds.add(c.offset(dir[0],dir[1]));
        Set<Cell> corridor=new HashSet<>();Set<Axis> axes=new HashSet<>();
        for(Cell c:seeds){
            int sx=score(seeds,c,1,0),sz=score(seeds,c,0,1);
            if(sx==0&&sz==0)continue;
            if(sx>=sz&&sx>0)addAxis(seeds,c,true,corridor,axes);
            if(sz>=sx&&sz>0)addAxis(seeds,c,false,corridor,axes);
        }
        List<Axis> ordered=new ArrayList<>(axes);
        ordered.sort(Comparator.comparingInt((Axis a)->a.cell.x).thenComparingInt(a->a.cell.z).thenComparing(Axis::alongX));
        return new Plan(Set.copyOf(corridor),List.copyOf(ordered));
    }
    private static int score(Set<Cell> s,Cell c,int dx,int dz){
        int n=0;for(int distance=3;distance<=5;distance++)for(int sign:new int[]{-1,1})
            if(s.contains(c.offset(dx*distance*sign,dz*distance*sign)))n++;
        return n;
    }
    private static void addAxis(Set<Cell> seeds,Cell c,boolean x,Set<Cell> road,Set<Axis> axes){
        int lo=0,hi=0;
        while(lo>-3&&seeds.contains(c.offset(x?0:lo-1,x?lo-1:0)))lo--;
        while(hi<3&&seeds.contains(c.offset(x?0:hi+1,x?hi+1:0)))hi++;
        // Crossings/plazas keep their existing footprint; neighboring stretches define their axis.
        if(hi-lo>=4){road.add(c);return;}
        int mid=Math.floorDiv(lo+hi,2);Cell center=c.offset(x?0:mid,x?mid:0);
        axes.add(new Axis(center,x));
        for(int side=-1;side<=1;side++)road.add(center.offset(x?0:side,x?side:0));
    }
    public static boolean entrance(int x,int z,int doorX,int doorZ,int facingX,int facingZ){
        int dx=x-doorX,dz=z-doorZ,forward=dx*facingX+dz*facingZ,side=dx*facingZ-dz*facingX;
        // Both sides of a door stay clear, including doors whose facing points indoors.
        return dx*dx+dz*dz<=9||(Math.abs(forward)<=5&&Math.abs(side)<=1);
    }
    private RoadLayout(){}
}
