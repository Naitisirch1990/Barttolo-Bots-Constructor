package net.barttolo.bots;
/** Two horizontal blocks per level, lower grass shoulders, fixed landing at the lower elevation. */
public final class TerrainRampProfile {
    private TerrainRampProfile() {}
    public static int height(int upper,int drop,int step,int lane){
        if(drop<4||drop>10||step<1||step>drop*2||Math.abs(lane)>1)throw new IllegalArgumentException("Ramp outside supported bounds");
        return Math.max(upper-drop,upper-(step+1)/2-(lane==0?0:1));
    }
}
