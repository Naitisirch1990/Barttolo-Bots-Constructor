import net.barttolo.bots.TerrainRampProfile;
public class TerrainRampProfileChecks {
    public static void main(String[] args){
        for(int drop=4;drop<=10;drop++)for(int upper:new int[]{-30,64,200})for(int lane=-1;lane<=1;lane++){
            int previous=upper;
            for(int step=1;step<=drop*2;step++){
                int y=TerrainRampProfile.height(upper,drop,step,lane);
                if(y>previous||previous-y>(step==1&&lane!=0?2:1)||y<upper-drop)throw new AssertionError("Disconnected or excessive ramp grade");
                previous=y;
            }
            if(previous!=upper-drop)throw new AssertionError("Ramp does not meet lower ground");
        }
        for(int bad:new int[]{0,3,11,100}){boolean rejected=false;try{TerrainRampProfile.height(64,bad,1,0);}catch(IllegalArgumentException e){rejected=true;}if(!rejected)throw new AssertionError("Unbounded drop accepted");}
        System.out.println("Ramp profile checks passed: grades, shoulders, landing and bounds");
    }
}
