package net.barttolo.bots;

/** Integer geometry independent of Minecraft; one cardinal lane five blocks wide. */
public final class RoadCorridor {
    private RoadCorridor(){}
    public static int[] cell(int ax,int az,int bx,int bz,int index){
        if((ax!=bx&&az!=bz)||(ax==bx&&az==bz))throw new IllegalArgumentException("Distinct cardinal endpoints required");
        int length=Math.abs(ax-bx)+Math.abs(az-bz)+1;
        if(length>2001||index<0||index>=length*5)throw new IllegalArgumentException("Corridor index or distance outside bounds");
        int section=index/5,offset=index%5-2;
        return new int[]{ax+Integer.signum(bx-ax)*section+(ax==bx?offset:0),az+Integer.signum(bz-az)*section+(az==bz?offset:0)};
    }
}
