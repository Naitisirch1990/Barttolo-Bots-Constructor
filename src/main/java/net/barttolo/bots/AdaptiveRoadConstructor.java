package net.barttolo.bots;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;

/** Bounded, incremental terrain-aware road search. All world reads stay on the server thread. */
public final class AdaptiveRoadConstructor {
    static final int MAX_NODES=16000,MAX_SHORT_ROUTE_NODES=512,MAX_BRIDGE_SUPPORT=32,MAX_EDITS=250000,MAX_DISTANCE=2000;
    static final int FLAT_BETWEEN_STEPS=3;
    private static final Set<UUID> ACTIVE_WORKERS=new HashSet<>();
    private static final TicketType<Long> TICKET=TicketType.create("barttolo_adaptive_road",Long::compareTo,40);
    private ServerLevel ticketLevel;
    private final Set<Long> tickets=new HashSet<>();
    private Villager worker;
    private net.minecraft.world.phys.Vec3 workerTarget;
    private int workerStuck,lightIndex,pierIndex;
    private double pierDistance=15;
    private boolean lastLight,obliqueRaster;
    private final Set<BlockPos> walkVolume=new HashSet<>();
    private Search search;
    private String surveyFailure="";
    java.util.function.LongSupplier planClock=System::nanoTime;
    long planStartedNano;
    private long nextPlanNotice;
    private int recipeIndex,previewCursor,coverSurveyIndex;
    private BlockPos recipeWorker;
    private final Map<BlockPos,BlockPos> workers=new HashMap<>();
    private final Map<Long,BlockPos> deckColumns=new HashMap<>();
    private final LinkedHashSet<BlockPos> tunnelExcavations=new LinkedHashSet<>();
    private final Set<BlockPos> sealedTunnelInterior=new HashSet<>();
    private Iterator<BlockPos> tunnelExcavationIterator;
    private Data planningData;
    private final LinkedHashMap<BlockPos,BlockState> recipe=new LinkedHashMap<>();

    public static final class Data extends SavedData {
        BlockPos a,b;String dimension="minecraft:overworld",state="VACIO",report="";
        int cursor,undo,roads,bridges,tunnels,cut,fill,previewTicks,lights,piers,pierBlocks,pierSkipped;boolean resumeAfterPlan;
        final ListTag route=new ListTag(),tasks=new ListTag(),changes=new ListTag(),lamps=new ListTag(),archives=new ListTag();
        static Data get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(Data::load,Data::new,"barttolobots_terrain_roads_v2");}
        static Data load(CompoundTag t){
            Data d=new Data();d.resumeAfterPlan=t.getBoolean("resumeAfterPlan");d.archives.addAll(t.getList("archives",10));if(t.contains("a"))d.a=BlockPos.of(t.getLong("a"));if(t.contains("b"))d.b=BlockPos.of(t.getLong("b"));
            if(t.contains("dimension"))d.dimension=t.getString("dimension");d.state=t.getString("state");d.report=t.getString("report");
            d.cursor=t.getInt("cursor");d.undo=t.getInt("undo");d.roads=t.getInt("roads");d.bridges=t.getInt("bridges");d.tunnels=t.getInt("tunnels");d.cut=t.getInt("cut");d.fill=t.getInt("fill");d.lights=t.getInt("lights");d.piers=t.getInt("piers");d.pierBlocks=t.getInt("pierBlocks");d.pierSkipped=t.getInt("pierSkipped");d.lamps.addAll(t.getList("lamps",10));
            d.route.addAll(t.getList("route",10));d.tasks.addAll(t.getList("tasks",10));d.changes.addAll(t.getList("changes",10));
            if(d.state.equals("PLANIFICANDO")||d.state.equals("DISEÑANDO")){d.route.clear();d.tasks.clear();d.lamps.clear();d.lights=0;d.state="PAUSADO";}
            if(d.state.equals("CONSTRUYENDO"))d.state="PAUSADO";
            if(d.state.equals("DESHACIENDO"))d.state="DESHACER_PAUSADO";
            if(t.getInt("planVersion")<3&&!d.route.isEmpty()&&d.changes.isEmpty()){d.route.clear();d.tasks.clear();d.lamps.clear();d.lights=0;d.state="CONFIGURANDO";d.report="Plan anterior pendiente: usá crear y marcá A/B otra vez.";}
            if(d.state.equals("LISTO")){d.state="CONFIRMADO";d.previewTicks=200;}
            return d;
        }
        public CompoundTag save(CompoundTag t){
            if(a!=null)t.putLong("a",a.asLong());if(b!=null)t.putLong("b",b.asLong());
            t.putBoolean("resumeAfterPlan",resumeAfterPlan);t.putInt("planVersion",3);t.putString("dimension",dimension);t.putString("state",state);t.putString("report",report);t.putInt("cursor",cursor);t.putInt("undo",undo);
            t.putInt("roads",roads);t.putInt("bridges",bridges);t.putInt("tunnels",tunnels);t.putInt("cut",cut);t.putInt("fill",fill);t.putInt("lights",lights);t.putInt("piers",piers);t.putInt("pierBlocks",pierBlocks);t.putInt("pierSkipped",pierSkipped);t.put("lamps",lamps.copy());
            t.put("archives",archives.copy());t.put("route",route.copy());t.put("tasks",tasks.copy());t.put("changes",changes.copy());return t;
        }
        String summary(){return "Archivadas "+archives.size()+" | "+state+" | "+cursor+"/"+tasks.size()+" cambios | camino "+roads+", puente "+bridges+", túnel "+tunnels+" secciones | pilares "+piers+" ("+pierBlocks+" bloques) | excavar "+cut+", colocar "+fill+" | "+report;}
    }
    record Key(BlockPos pos,int dx,int dz,int grade,int flat){}
    static final class Node {
        final Key key;final Node parent;final double cost,rank;final int kind,depth,length,span;
        Node(Key k,Node p,double c,double h,int kind,int depth,int span){key=k;parent=p;cost=c;rank=c+h;this.kind=kind;this.depth=depth;length=p==null?0:p.length+1;this.span=span;}
    }
    record Survey(int kind,int cut,int fill,int depth){}
    static final class Search {
        final BlockPos start,goal;final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble((Node n)->n.rank).thenComparingDouble(n->-n.cost));
        final Map<Key,Double> best=new HashMap<>();final Map<BlockPos,Survey> cache=new HashMap<>();
        Node current;int neighbor,expanded,directIndex;boolean directPossible=true;String failure="";
        Search(BlockPos a,BlockPos b){start=a;goal=b;Node n=new Node(new Key(a,0,0,0,3),null,0,heuristic(a,b),0,0,0);open.add(n);best.put(n.key,0.0);}
    }
    static double heuristic(BlockPos a,BlockPos b){return Math.max(Math.abs(a.getX()-b.getX())+Math.abs(a.getZ()-b.getZ()),Math.abs(a.getY()-b.getY()));}
    static boolean editable(ServerLevel l,BlockPos p){
        if(!l.getWorldBorder().isWithinBounds(p))return false;
        BlockState b=l.getBlockState(p);
        return b.getDestroySpeed(l,p)>=0;
    }
    static boolean bearing(ServerLevel l,BlockPos p){
        BlockState state=l.getBlockState(p);
        return editable(l,p)&&state.isFaceSturdy(l,p,Direction.UP)&&!state.is(BlockTags.LEAVES)&&!state.is(BlockTags.LOGS);
    }
    private ServerLevel level(MinecraftServer s,Data d){return s.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation(d.dimension)));}
    public void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("constructor").requires(s->s.hasPermission(2));
        for(String name:List.of("crear","puntoA","puntoB","iniciar","pausar","continuar","detener","estado","ir","listar","deshacer"))root.then(Commands.literal(name).executes(c->command(c.getSource(),name)));
        var legacy=Commands.literal("legado");
        for(String name:List.of("estado","deshacer","pausar"))legacy.then(Commands.literal(name).executes(c->{
            if(!name.equals("estado")&&Set.of("PLANIFICANDO","DISEÑANDO","CONSTRUYENDO","DESHACIENDO").contains(Data.get(c.getSource().getServer()).state))return fail(c.getSource(),"Pausá primero la obra adaptativa.");
            return Bots.roads.command(c.getSource(),name);
        }));
        root.then(legacy);dispatcher.register(Commands.literal("bot").requires(s->s.hasPermission(2)).then(root));
    }
    int command(CommandSourceStack s,String name)throws CommandSyntaxException{
        Data d=Data.get(s.getServer());
        if(!Set.of("estado","listar","ir").contains(name)&&RoadConstructor.Data.get(s.getServer()).state.equals("DESHACIENDO"))return fail(s,"Esperá a que termine el deshacer de la obra antigua.");
        switch(name){
            case "crear":
                if(!archiveFinished(d))return fail(s,"Terminá o deshacé la obra activa antes de crear otra.");
                reset();d.a=null;d.b=null;d.cursor=0;d.route.clear();d.tasks.clear();d.lamps.clear();d.lights=0;d.dimension=s.getLevel().dimension().location().toString();d.state="CONFIGURANDO";d.report="Marcá A y B: alturas y direcciones libres.";break;
            case "puntoA":case "puntoB":
                if(!Set.of("CONFIGURANDO","RECHAZADO").contains(d.state)||!d.changes.isEmpty())return fail(s,"Primero crear una obra nueva.");
                if(!d.dimension.equals(s.getLevel().dimension().location().toString()))return fail(s,"A y B deben estar en la misma dimensión.");
                if(name.equals("puntoA")){d.a=s.getPlayerOrException().blockPosition();d.state="CONFIGURANDO";}
                else{
                    String problem=markBAndPlan(d,s.getPlayerOrException().blockPosition());
                    if(problem!=null)return fail(s,problem);
                }break;
            case "iniciar":
                if(!Set.of("CONFIRMADO","LISTO").contains(d.state))return fail(s,"Marcá A y B y esperá la vista automática antes de iniciar.");
                d.state="CONSTRUYENDO";d.report="Construcción progresiva.";break;
            case "pausar":case "detener":
                if(Set.of("PLANIFICANDO","DISEÑANDO").contains(d.state)){d.tasks.clear();d.route.clear();}
                if(d.state.equals("DESHACIENDO"))d.state="DESHACER_PAUSADO";
                else if(Set.of("CONSTRUYENDO","PLANIFICANDO","DISEÑANDO","CONFIRMADO","LISTO").contains(d.state))d.state="PAUSADO";
                reset();break;
            case "continuar":
                if(d.state.equals("DESHACER_PAUSADO")){d.state="DESHACIENDO";break;}
                if(!Set.of("PAUSADO","RECHAZADO").contains(d.state)||d.a==null||d.b==null)return fail(s,"No hay obra interrumpida con A/B marcados.");
                resume(d);break;
            case "deshacer":
                if(Set.of("CONSTRUYENDO","PLANIFICANDO","DISEÑANDO").contains(d.state))return fail(s,"Pausá antes de deshacer.");
                if(d.changes.isEmpty()&&!restoreArchive(d))return fail(s,"No hay obras para deshacer. Para 0.1.0 usá /bot constructor legado deshacer.");
                d.undo=d.changes.size()-1;d.state="DESHACIENDO";reset();break;
            case "ir":
                ServerLevel l=level(s.getServer(),d);if(l==null||d.route.isEmpty())return fail(s,"No hay ruta calculada.");
                BlockPos target=d.cursor<d.tasks.size()?BlockPos.of(d.tasks.getCompound(d.cursor).getLong("worker")):BlockPos.of(d.route.getCompound(d.route.size()-1).getLong("pos"));
                boolean found=false;
                for(int y=3;y>=-2&&!found;y--)for(int x=-2;x<=2&&!found;x++)for(int z=-2;z<=2&&!found;z++){
                    BlockPos p=target.offset(x,y,z);
                    if(l.hasChunkAt(p)&&l.getBlockState(p).isAir()&&l.getBlockState(p.above()).isAir()&&l.getBlockState(p.below()).isFaceSturdy(l,p.below(),Direction.UP)){
                        s.getPlayerOrException().teleportTo(l,p.getX()+.5,p.getY(),p.getZ()+.5,0,0);found=true;
                    }
                }
                if(!found)return fail(s,"No hay posición segura cargada cerca de la obra.");break;
            case "estado":case "listar":break;
            default:return 0;
        }
        d.setDirty();s.sendSuccess(()->Component.literal("Constructor: "+d.summary()),false);return 1;
    }
    String markBAndPlan(Data d,BlockPos point){
        if(d.a==null)return "Primero marcá puntoA.";
        if(d.a.getX()==point.getX()&&d.a.getZ()==point.getZ())return "Separá horizontalmente A y B.";
        if(heuristic(d.a,point)>MAX_DISTANCE)return "Distancia máxima: 2000 bloques.";
        d.b=point;begin(d);return null;
    }
    void resume(Data d){
        if(d.tasks.isEmpty()||d.state.equals("RECHAZADO")){begin(d);d.resumeAfterPlan=true;}
        else{d.state="CONSTRUYENDO";d.report="Obra reanudada.";d.setDirty();}
    }
    static boolean archiveFinished(Data d){
        if(d.changes.isEmpty())return true;
        if(!d.state.equals("TERMINADO"))return false;
        CompoundTag archive=new CompoundTag();archive.putString("dimension",d.dimension);
        archive.put("changes",d.changes.copy());d.archives.add(archive);d.changes.clear();d.setDirty();return true;
    }
    static boolean restoreArchive(Data d){
        if(d.archives.isEmpty())return false;
        CompoundTag archive=d.archives.getCompound(d.archives.size()-1);
        d.dimension=archive.getString("dimension");d.changes.addAll(archive.getList("changes",10));
        d.archives.remove(d.archives.size()-1);d.route.clear();d.tasks.clear();d.setDirty();return true;
    }
    static int fail(CommandSourceStack s,String text){s.sendFailure(Component.literal(text));return 0;}
    void begin(Data d){reset();d.resumeAfterPlan=false;d.route.clear();d.tasks.clear();d.lamps.clear();d.lights=0;d.cursor=0;d.roads=d.bridges=d.tunnels=d.cut=d.fill=d.piers=d.pierBlocks=d.pierSkipped=0;d.state="PLANIFICANDO";planStartedNano=planClock.getAsLong();nextPlanNotice=planStartedNano+5_000_000_000L;d.report="A "+d.a+"; B "+d.b+"; distancia "+heuristic(d.a,d.b)+"; desnivel "+(d.b.getY()-d.a.getY())+". Límite de planificación: 60 s.";search=new Search(d.a,d.b);planningData=d;}
    public void tick(MinecraftServer s){
        Data d=Data.get(s);if(s.getAverageTickTime()>55&&s.getTickCount()%4!=0)return;tickProject(s,d);
    }
    void tickProject(MinecraftServer s,Data d){
        ServerLevel l=level(s,d);if(l==null){if(!d.state.equals("VACIO"))halt(s,d,"Dimensión no disponible.");return;}
        if(d.state.equals("PLANIFICANDO")||d.state.equals("DISEÑANDO")){
            long now=planClock.getAsLong();if(planStartedNano==0){planStartedNano=now;nextPlanNotice=now+5_000_000_000L;}
            if(now-planStartedNano>=60_000_000_000L){
                String detail=d.report;
                halt(s,d,"Límite de planificación de 60 s alcanzado. No se modificó el mundo. Desnivel A/B: "+(d.b.getY()-d.a.getY())+". Último avance: "+detail);return;
            }
            if(now>=nextPlanNotice){nextPlanNotice=now+5_000_000_000L;s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+d.state+" | "+((now-planStartedNano)/1_000_000_000L)+"/60 s | "+d.report),false);}
        }
        if(d.previewTicks>0){if(s.getTickCount()%10==0)preview(l,d);d.previewTicks--;}
        if(d.state.equals("PLANIFICANDO")){if(search==null||planningData!=d){search=new Search(d.a,d.b);planningData=d;}for(int work=0;work<4&&d.state.equals("PLANIFICANDO");work++){searchTick(s,l,d);if(search==null||!search.directPossible)break;}return;}
        if(d.state.equals("DISEÑANDO")){designTick(s,l,d);return;}
        if(d.state.equals("CONSTRUYENDO")){walkWorker(l);buildTick(s,l,d);return;}
        if(d.state.equals("DESHACIENDO")){undoTick(s,l,d);return;}
        release();removeWorker();
    }
    void window(ServerLevel l,BlockPos p){
        if(ticketLevel!=l){release();ticketLevel=l;}Set<Long>wanted=new HashSet<>();int cx=p.getX()>>4,cz=p.getZ()>>4;
        for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++)wanted.add(net.minecraft.world.level.ChunkPos.asLong(x,z));
        for(long key:new HashSet<>(tickets))if(!wanted.contains(key)){l.getChunkSource().removeRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key),0,key);tickets.remove(key);}
        for(long key:wanted){tickets.add(key);l.getChunkSource().addRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key),0,key);}
    }
    boolean loaded(ServerLevel l,BlockPos p){return l.hasChunkAt(p.offset(-3,0,-3))&&l.hasChunkAt(p.offset(3,0,3))&&l.hasChunkAt(p.offset(-3,0,3))&&l.hasChunkAt(p.offset(3,0,-3));}
    static boolean waterTerrain(BlockState b){return b.is(Blocks.WATER)||b.is(Blocks.SEAGRASS)||b.is(Blocks.TALL_SEAGRASS)||b.is(Blocks.KELP)||b.is(Blocks.KELP_PLANT);}
    static boolean liquidTerrain(BlockState state){return waterTerrain(state)||state.is(Blocks.LAVA);}
    static boolean pierBearing(ServerLevel l,BlockPos p){
        BlockState state=l.getBlockState(p);
        return l.getBlockEntity(p)==null&&l.getFluidState(p).isEmpty()&&!state.is(BlockTags.LEAVES)&&!state.is(BlockTags.LOGS)&&state.isFaceSturdy(l,p,Direction.UP);
    }
    Survey survey(ServerLevel l,BlockPos foot){
        surveyFailure="";
        if(foot.getY()<l.getMinBuildHeight()+1||foot.getY()+5>=l.getMaxBuildHeight())return rejectSurvey(l,foot,"fuera de la altura construible");
        int cut=0,fill=0,depth=0;boolean elevated=false,roof=false;
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            BlockPos floor=foot.offset(x,-1,z);boolean water=liquidTerrain(l.getBlockState(floor));
            if(!water&&(!editable(l,floor)||!l.getFluidState(floor).isEmpty()))return rejectSurvey(l,floor,"base no modificable");
            if(water)elevated=true;
            for(int h=1;h<=5;h++){
                // Water and lava inside the tunnel corridor can be replaced after the shell is built.
                BlockPos q=floor.above(h);if(!editable(l,q))return rejectSurvey(l,q,"bloque no modificable en el paso");
                if(!l.getBlockState(q).isAir())cut++;
            }
            BlockPos ceiling=floor.above(6);if(!editable(l,ceiling))return rejectSurvey(l,ceiling,"techo no modificable");
            if(!l.getFluidState(ceiling).isEmpty()||l.getBlockState(ceiling).is(Blocks.STONE)||l.getBlockState(ceiling).is(BlockTags.BASE_STONE_OVERWORLD))roof=true;
            // Classification only: decks do not require foundations.
            if(water||l.getBlockState(floor).isAir()||!pierBearing(l,floor.below()))elevated=true;
        }
        return new Survey(elevated?1:roof&&cut>0?2:0,cut,fill,depth);
    }
    Survey rejectSurvey(ServerLevel l,BlockPos p,String reason){
        surveyFailure=reason+": "+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(l.getBlockState(p).getBlock())+" en "+p;
        return null;
    }
    void searchTick(MinecraftServer s,ServerLevel l,Data d){
        int[][] dirs={{1,0},{0,1},{-1,0},{0,-1}};int[] grades={0,1,-1};
        int expansionLimit=heuristic(d.a,d.b)<=64?MAX_SHORT_ROUTE_NODES:MAX_NODES;
        // Check short, level straight routes first. This avoids exploring thousands of
        // alternatives for the common case, and gives icy/rough biomes a fast verdict.
        if(search.directPossible){
            int dx=Integer.signum(d.b.getX()-d.a.getX()),dz=Integer.signum(d.b.getZ()-d.a.getZ());
            int distance=Math.max(Math.abs(d.b.getX()-d.a.getX()),Math.abs(d.b.getZ()-d.a.getZ()));
            if(distance>0&&distance<=MAX_DISTANCE&&Math.abs(d.a.getY()-d.b.getY())<=distance){
                BlockPos p=new BlockPos(d.a.getX()+(int)Math.round((double)(d.b.getX()-d.a.getX())*search.directIndex/distance),directY(d,search.directIndex,distance),d.a.getZ()+(int)Math.round((double)(d.b.getZ()-d.a.getZ())*search.directIndex/distance));window(l,p);if(!loaded(l,p))return;
                Survey v=search.cache.get(p);
                if(v==null){v=survey(l,p);if(v==null)v=new Survey(-1,0,0,0);search.cache.put(p,v);}
                if(v.kind<0){halt(s,d,"No se puede abrir el corredor directo: "+(surveyFailure.isEmpty()?"obstáculo en "+p:surveyFailure)+". Marcá otro punto para evitarlo.");return;}
                else{
                    CompoundTag t=new CompoundTag();t.putLong("pos",p.asLong());t.putInt("kind",v.kind);d.route.add(t);search.directIndex++;
                    d.report="Verificando tramo recto: "+Math.min(search.directIndex,distance)+"/"+distance+" secciones.";d.setDirty();
                    if(search.directIndex>distance){routeFound(d,distance+1);search=null;return;}
                    return;
                }
            }else search.directPossible=false;
        }
        for(int budget=0;budget<32;budget++){
            if(search.current==null){
                while(!search.open.isEmpty()){
                    Node n=search.open.poll();if(n.cost<=search.best.getOrDefault(n.key,Double.MAX_VALUE)){search.current=n;break;}
                }
                if(search.current==null){halt(s,d,"No encontró una ruta segura en el área explorada.");return;}
                if(++search.expanded>expansionLimit){
                    halt(s,d,expansionLimit==MAX_SHORT_ROUTE_NODES
                        ?"No encontró un trazado seguro dentro del límite para este tramo corto. Probá marcar A/B más cerca del borde del obstáculo."
                        :"Límite de exploración alcanzado; probá puntos intermedios.");return;
                }
                Node n=search.current;
                if(n.key.pos.equals(d.b)){
                    ArrayList<Node> nodes=new ArrayList<>();for(Node p=n;p!=null;p=p.parent)nodes.add(p);Collections.reverse(nodes);
                    for(Node p:nodes){CompoundTag t=new CompoundTag();t.putLong("pos",p.key.pos.asLong());t.putInt("kind",p.kind);d.route.add(t);}
                    routeFound(d,nodes.size());search=null;return;
                }
                search.neighbor=0;
            }
            Node from=search.current;int i=search.neighbor;int[] dir=dirs[i/3];int grade=grades[i%3];
            boolean turn=from.parent!=null&&(from.key.dx!=dir[0]||from.key.dz!=dir[1]);
            if((turn&&(grade!=0||from.key.flat<3))||(grade!=0&&from.key.flat<FLAT_BETWEEN_STEPS)||(from.key.grade>0&&grade<0)||(turn&&from.key.dx==-dir[0]&&from.key.dz==-dir[1])){advance();continue;}
            BlockPos pos=from.key.pos.offset(dir[0],grade,dir[1]);
            if(from.length>=MAX_DISTANCE||Math.abs(pos.getX()-d.a.getX())+Math.abs(pos.getZ()-d.a.getZ())>MAX_DISTANCE
                ||heuristic(pos,d.b)>MAX_DISTANCE||Math.abs(pos.getY()-d.a.getY())>96){advance();continue;}
            window(l,pos);if(!loaded(l,pos))return;
            Survey v=search.cache.get(pos);
            if(v==null){v=survey(l,pos);if(v==null)v=new Survey(-1,0,0,0);search.cache.put(pos,v);}
            if(v.kind>=0){
                int span=v.kind==1?from.span+1:0;
                if(span<=MAX_DISTANCE){
                    double cost=from.cost+1+(grade==0?0:.8)+(turn?.6:0)+v.cut*.018+v.fill*.012+(v.kind==1?.8:0);
                    Key key=new Key(pos,dir[0],dir[1],grade,grade==0?(turn?-2:Math.min(3,from.key.flat+1)):0);
                    if(cost<search.best.getOrDefault(key,Double.MAX_VALUE)){
                        search.best.put(key,cost);search.open.add(new Node(key,from,cost,heuristic(pos,d.b),v.kind,v.depth,span));
                    }
                }
            }
            advance();d.report=search.failure+"Exploradas "+search.expanded+" celdas; pendientes "+search.open.size()+".";
        }
    }
    static int directY(Data d,int step,int length){
        int rise=d.b.getY()-d.a.getY();
        // Adapt stair spacing to the requested rise/run, keeping each step at one block.
        int spacing=rise==0?4:Math.max(1,Math.min(4,length/Math.abs(rise)));
        if(rise>=0)return d.a.getY()+Math.min(rise,(step+spacing-1)/spacing);
        return d.b.getY()+Math.min(-rise,(length-step+spacing-1)/spacing);
    }
    static BlockPos columnFoot(Data d,int x,int z){
        int dx=d.b.getX()-d.a.getX(),dz=d.b.getZ()-d.a.getZ();
        boolean alongX=Math.abs(dx)>=Math.abs(dz);int length=Math.max(Math.abs(dx),Math.abs(dz));
        int step=alongX?(x-d.a.getX())*Integer.signum(dx):(z-d.a.getZ())*Integer.signum(dz);
        return new BlockPos(x,directY(d,Math.max(0,Math.min(length,step)),length),z);
    }
    BlockState rasterPaving(ServerLevel l,Data d,BlockPos floor){
        boolean alongX=Math.abs(d.b.getX()-d.a.getX())>=Math.abs(d.b.getZ()-d.a.getZ());
        int x=floor.getX(),z=floor.getZ(),y=floor.getY()+1;
        if(columnFoot(d,x-(alongX?1:0),z-(alongX?0:1)).getY()<y||columnFoot(d,x+(alongX?1:0),z+(alongX?0:1)).getY()<y){
            int sign=Integer.signum(d.b.getY()-d.a.getY());
            Direction facing=alongX?Direction.fromDelta(Integer.signum(d.b.getX()-d.a.getX())*sign,0,0):Direction.fromDelta(0,0,Integer.signum(d.b.getZ()-d.a.getZ())*sign);
            return stairPavement(l,floor).setValue(StairBlock.FACING,facing);
        }
        return pavement(l,floor);
    }
    void routeFound(Data d,int sections){
        d.state="DISEÑANDO";recipeIndex=0;coverSurveyIndex=0;lightIndex=0;pierIndex=0;pierDistance=15;lastLight=false;walkVolume.clear();recipe.clear();deckColumns.clear();tunnelExcavations.clear();sealedTunnelInterior.clear();tunnelExcavationIterator=null;
        obliqueRaster=d.a.getX()!=d.b.getX()&&d.a.getZ()!=d.b.getZ();
        for(int i=0;i<d.route.size()&&obliqueRaster;i++){BlockPos p=BlockPos.of(d.route.getCompound(i).getLong("pos"));if(!p.equals(columnFoot(d,p.getX(),p.getZ())))obliqueRaster=false;}
        if(obliqueRaster)for(int i=0;i<d.route.size();i++){
            BlockPos center=BlockPos.of(d.route.getCompound(i).getLong("pos"));
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=0;y<5;y++)walkVolume.add(columnFoot(d,center.getX()+x,center.getZ()+z).above(y));
        }
d.report="Ruta encontrada: "+sections+" secciones. Calculando excavaciones y materiales.";d.setDirty();
    }
    void advance(){if(++search.neighbor>=12)search.current=null;}
    BlockState pavement(ServerLevel l,BlockPos p){
        String biome=l.getBiome(p).unwrapKey().map(k->k.location().getPath()).orElse("");long hash=Math.floorMod(p.getX()*7349L+p.getZ()*9151L,5);
        if(biome.contains("desert")||biome.contains("badlands"))return (hash==0?Blocks.CUT_SANDSTONE:Blocks.SMOOTH_SANDSTONE).defaultBlockState();
        if(biome.contains("taiga")||biome.contains("snow")||biome.contains("frozen"))return (hash==0?Blocks.COBBLESTONE:Blocks.STONE_BRICKS).defaultBlockState();
        if(biome.contains("swamp"))return Blocks.MANGROVE_PLANKS.defaultBlockState();
        return (hash==0?Blocks.ANDESITE:Blocks.STONE_BRICKS).defaultBlockState();
    }
    BlockState stairPavement(ServerLevel l,BlockPos p){
        return stairMaterial(pavement(l,p));
    }
    static BlockState stairMaterial(BlockState b){
        if(b.is(Blocks.SMOOTH_SANDSTONE)||b.is(Blocks.CUT_SANDSTONE))return Blocks.SMOOTH_SANDSTONE_STAIRS.defaultBlockState();
        if(b.is(Blocks.MANGROVE_PLANKS))return Blocks.MANGROVE_STAIRS.defaultBlockState();
        return Blocks.STONE_BRICK_STAIRS.defaultBlockState();
    }
    boolean put(BlockPos p,BlockState b){
        workers.putIfAbsent(p,recipeWorker);BlockState old=recipe.get(p);
        if(old!=null&&!old.equals(b))return false;
        recipe.put(p,b);return recipe.size()<=MAX_EDITS;
    }
    static boolean matches(BlockState current,BlockState expected){
        if(current.equals(expected))return true;
        if(!current.is(expected.getBlock()))return false;
        if(expected.getBlock() instanceof WallBlock)return current.getValue(WallBlock.WATERLOGGED).equals(expected.getValue(WallBlock.WATERLOGGED));
        if(expected.getBlock() instanceof StairBlock)return current.getValue(StairBlock.FACING)==expected.getValue(StairBlock.FACING)&&current.getValue(StairBlock.HALF)==expected.getValue(StairBlock.HALF)&&current.getValue(StairBlock.WATERLOGGED).equals(expected.getValue(StairBlock.WATERLOGGED));
        return false;
    }
    void designTick(MinecraftServer s,ServerLevel l,Data d){
        if(planningData!=d){halt(s,d,"El diseño fue interrumpido. Usá crear y marcá A/B otra vez.");return;}
        if(coverSurveyIndex<d.route.size()){
            for(int budget=0;budget<8&&coverSurveyIndex<d.route.size();budget++){
                int i=coverSurveyIndex;BlockPos p=BlockPos.of(d.route.getCompound(i).getLong("pos"));
                window(l,p);if(!loaded(l,p))return;
                if(d.route.getCompound(i).getInt("kind")==1&&liquidAboveDeck(l,p))
                    for(int j=Math.max(0,i-1);j<=Math.min(d.route.size()-1,i+1);j++)
                        if(d.route.getCompound(j).getInt("kind")==1)d.route.getCompound(j).putBoolean("liquidCover",true);
                coverSurveyIndex++;
            }
            return;
        }
        if(recipeIndex<d.route.size()){
            int i=recipeIndex;BlockPos foot=BlockPos.of(d.route.getCompound(i).getLong("pos"));window(l,foot);if(!loaded(l,foot))return;
            BlockPos before=BlockPos.of(d.route.getCompound(Math.max(0,i-1)).getLong("pos"));
            BlockPos next=BlockPos.of(d.route.getCompound(Math.min(d.route.size()-1,i+1)).getLong("pos"));
            int dx=Integer.signum(next.getX()-foot.getX()),dz=Integer.signum(next.getZ()-foot.getZ());if(dx==0&&dz==0){dx=Integer.signum(foot.getX()-before.getX());dz=Integer.signum(foot.getZ()-before.getZ());}
            recipeWorker=foot;int kind=d.route.getCompound(i).getInt("kind");boolean enclosed=kind==2||d.route.getCompound(i).getBoolean("liquidCover");if(kind==1)d.bridges++;else if(kind==2)d.tunnels++;else d.roads++;
            int px=Integer.signum(foot.getX()-before.getX()),pz=Integer.signum(foot.getZ()-before.getZ());boolean corner=i>0&&i<d.route.size()-1&&(px!=dx||pz!=dz);
            boolean stairs=before.getY()<foot.getY()||next.getY()<foot.getY();
            Direction ascent=before.getY()<foot.getY()?Direction.fromDelta(px,0,pz):Direction.fromDelta(-dx,0,-dz);
            List<BlockPos> floors=new ArrayList<>();
            if(obliqueRaster||(dx!=0&&dz!=0)){
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)floors.add((obliqueRaster?columnFoot(d,foot.getX()+x,foot.getZ()+z):foot.offset(x,0,z)).below());
            }else for(int w=-2;w<=2;w++)for(int along=corner?-2:0;along<=(corner?2:0);along++)
                floors.add(foot.offset(-dz*w+dx*along,-1,dx*w+dz*along));
            for(BlockPos floor:floors){
                for(int h=1;h<=5;h++)walkVolume.add(floor.above(h));
                BlockPos ceiling=floor.above(6);if(l.getBlockState(ceiling).getBlock() instanceof FallingBlock)put(ceiling,Blocks.STONE_BRICKS.defaultBlockState());
                BlockState paving=obliqueRaster?rasterPaving(l,d,floor):stairs?stairPavement(l,floor).setValue(StairBlock.FACING,ascent):pavement(l,floor);
                if(!put(floor,paving)){halt(s,d,"Cruce a distinta altura: requiere otro trazado.");return;}
                deckColumns.put(net.minecraft.world.level.ChunkPos.asLong(floor.getX(),floor.getZ()),floor);
                // Seal the deck before excavating over water.
                for(int h=5;h>=1;h--){
                    BlockPos clear=floor.above(h);
                    if(enclosed){tunnelExcavations.add(clear);sealedTunnelInterior.add(clear);}
                    else if(!l.getBlockState(clear).isAir()&&!put(clear,Blocks.AIR.defaultBlockState())){halt(s,d,"Solapamiento de trazado; probá puntos intermedios.");return;}
                }
                // The deck spans air/water without compulsory pillars.
            }
            if(enclosed&&!planTunnelShell(s,l,d,foot,dx,dz))return;
            if(d.route.getCompound(i).getBoolean("liquidCover")&&(i==0||i==d.route.size()-1||!d.route.getCompound(i-1).getBoolean("liquidCover")||!d.route.getCompound(i+1).getBoolean("liquidCover")))
                if(!planGalleryRoofLip(s,l,d,foot,dx,dz))return;
            if(kind==1&&!enclosed)for(int side:new int[]{-3,3}){
                BlockPos edge=foot.offset(-dz*side,-1,dx*side);if(obliqueRaster)edge=columnFoot(d,edge.getX(),edge.getZ()).below();BlockPos rail=edge.above();
                if(walkVolume.contains(rail))continue;
                if(!safeChange(l,edge,l.getBlockState(edge),pavement(l,edge))||!safeChange(l,rail,l.getBlockState(rail),Blocks.COBBLESTONE_WALL.defaultBlockState())){halt(s,d,"Un bloque no modificable impide la barandilla en "+rail);return;}
                if(!put(edge,pavement(l,edge))||!put(rail,Blocks.COBBLESTONE_WALL.defaultBlockState())){halt(s,d,"Barandilla cruza otra sección.");return;}
                deckColumns.put(net.minecraft.world.level.ChunkPos.asLong(edge.getX(),edge.getZ()),edge);
            }
            recipeIndex++;d.setDirty();return;
        }
        // Build all walls and roof sections before opening the five-high interior.
        if(tunnelExcavationIterator==null&&!tunnelExcavations.isEmpty())tunnelExcavationIterator=tunnelExcavations.iterator();
        if(tunnelExcavationIterator!=null){
            for(int budget=0;budget<128&&tunnelExcavationIterator.hasNext();budget++){
                BlockPos p=tunnelExcavationIterator.next();BlockState planned=recipe.get(p);
                if(planned!=null&&!planned.isAir()){halt(s,d,"El túnel cruza un bloque ya planificado en "+p);return;}
                recipe.put(p,Blocks.AIR.defaultBlockState());workers.putIfAbsent(p,p.above());
                if(recipe.size()>MAX_EDITS){halt(s,d,"El túnel supera el límite de 250000 cambios.");return;}
            }
            if(!tunnelExcavationIterator.hasNext()){tunnelExcavationIterator=null;tunnelExcavations.clear();}
            return;
        }
        if(lightIndex<d.route.size()||!lastLight){
            int i=lightIndex;
            if(i>=d.route.size()){lastLight=true;i=d.route.size()-1;if(i<3||i%5<3)return;}
            if(!lastLight&&d.route.getCompound(i).getInt("kind")!=2&&!d.route.getCompound(i).getBoolean("liquidCover")&&i%10!=0){lightIndex=i+5;return;}
            BlockPos foot=BlockPos.of(d.route.getCompound(i).getLong("pos"));window(l,foot);if(!loaded(l,foot)){if(lastLight)lastLight=false;return;}
            recipeWorker=foot;planLight(l,d,i);lightIndex=i+5;return;
        }
        if(pierIndex<d.route.size()){
            for(int budget=0;budget<8&&pierIndex<d.route.size();budget++){
                int i=pierIndex;BlockPos foot=BlockPos.of(d.route.getCompound(i).getLong("pos"));
                window(l,foot);
                for(int x:new int[]{-5,5})for(int z:new int[]{-5,5})if(!l.hasChunkAt(foot.offset(x,0,z)))return;
                boolean bridge=d.route.getCompound(i).getInt("kind")==1;
                if(bridge&&i>0&&d.route.getCompound(i-1).getInt("kind")==1){
                    BlockPos before=BlockPos.of(d.route.getCompound(i-1).getLong("pos"));
                    pierDistance+=Math.hypot(foot.getX()-before.getX(),foot.getZ()-before.getZ());
                }else pierDistance=15;
                if(bridge&&pierDistance>=15){
                    recipeWorker=foot;if(!planPier(s,l,d,i))return;pierDistance=0;
                }
                pierIndex++;d.report="Pilares: "+d.piers+"; "+d.pierBlocks+" bloques. Revisadas "+pierIndex+"/"+d.route.size()+" secciones.";
            }
            d.setDirty();return;
        }
        // Snapshot one small batch per tick, under a moving chunk window.
        int end=Math.min(recipe.size(),d.tasks.size()+64);int index=0;
        for(var e:recipe.entrySet()){
            if(index++<d.tasks.size())continue;if(d.tasks.size()>=end)break;
            BlockPos p=e.getKey();window(l,p);if(!loaded(l,p))return;
            BlockState old=l.getBlockState(p),after=e.getValue();
            if(!safeChange(l,p,old,after)){halt(s,d,"Bloque sensible o líquido en el diseño: "+p);return;}
            CompoundTag t=new CompoundTag();t.putLong("pos",p.asLong());t.put("before",NbtUtils.writeBlockState(old));t.put("after",NbtUtils.writeBlockState(after));t.putBoolean("sealedTunnelInterior",sealedTunnelInterior.contains(p));
            if(l.getBlockEntity(p)!=null)t.put("beforeBlockEntity",l.getBlockEntity(p).saveWithFullMetadata());
            t.putLong("worker",workers.getOrDefault(p,p.above()).asLong());d.tasks.add(t);if(!old.equals(after)){if(after.isAir())d.cut++;else d.fill++;}
        }
        if(d.tasks.size()==recipe.size()){
            if(!prepareSealedExcavations(s,l,d))return;
            d.previewTicks=200;d.state=d.resumeAfterPlan?"CONSTRUYENDO":"CONFIRMADO";d.resumeAfterPlan=false;d.report=d.state.equals("CONSTRUYENDO")?"Plan recuperado. Construcción reanudada.":"Plan calculado. Vista automática durante 10 segundos; usá iniciar para construir.";d.setDirty();recipe.clear();sealedTunnelInterior.clear();release();
            s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+d.summary()),false);
        }
    }
    boolean liquidAboveDeck(ServerLevel l,BlockPos foot){
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=0;y<=5;y++)
            if(!l.getFluidState(foot.offset(x,y,z)).isEmpty())return true;
        return false;
    }
    boolean prepareSealedExcavations(MinecraftServer s,ServerLevel l,Data d){
        ListTag solidTasks=new ListTag(),plugs=new ListTag(),cuts=new ListTag();
        BlockState plugState=Blocks.STONE_BRICKS.defaultBlockState();
        for(int i=0;i<d.tasks.size();i++){
            CompoundTag task=d.tasks.getCompound(i);
            if(!read(l,task,"after").isAir()){solidTasks.add(task);continue;}
            if(task.getBoolean("sealedTunnelInterior")){
                // Replace all interior sources and flowing liquid with temporary solid
                // plugs before clearing the passage. This prevents water source regeneration.
                CompoundTag plug=task.copy();plug.put("after",NbtUtils.writeBlockState(plugState));plug.putBoolean("sealedTunnelInterior",false);plugs.add(plug);
                task=task.copy();task.put("before",NbtUtils.writeBlockState(plugState));task.remove("beforeBlockEntity");
            }
            cuts.add(task);
        }
        if(solidTasks.size()+plugs.size()+cuts.size()>MAX_EDITS){halt(s,d,"El revestimiento y drenaje superan el límite de 250000 cambios.");return false;}
        solidTasks.addAll(plugs);solidTasks.addAll(cuts);d.tasks.clear();d.tasks.addAll(solidTasks);d.cut=d.fill=0;
        for(int i=0;i<d.tasks.size();i++){CompoundTag t=d.tasks.getCompound(i);BlockState after=read(l,t,"after");if(!read(l,t,"before").equals(after)){if(after.isAir())d.cut++;else d.fill++;}}
        return true;
    }
    boolean planGalleryRoofLip(MinecraftServer s,ServerLevel l,Data d,BlockPos foot,int dx,int dz){
        // Raised end lintels divert roof runoff sideways, away from the open portals.
        BlockState material=pavement(l,foot.below());
        if(obliqueRaster){
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)
                if(!putTunnelShell(s,l,d,columnFoot(d,foot.getX()+x,foot.getZ()+z).above(6),material))return false;
        }else for(int w=-3;w<=3;w++)if(!putTunnelShell(s,l,d,foot.offset(-dz*w,6,dx*w),material))return false;
        return true;
    }
    boolean planTunnelShell(MinecraftServer s,ServerLevel l,Data d,BlockPos foot,int dx,int dz){
        if(dx==0&&dz==0)return true;
        int nx=-dz,nz=dx;BlockState shell=pavement(l,foot.below());
        // Five clear interior blocks across the five-block path, with one block of
        // masonry outside each edge. The shell is placed before any tunnel excavation.
        if(obliqueRaster){
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
                BlockPos column=columnFoot(d,foot.getX()+x,foot.getZ()+z);
                if(Math.abs(x)==3||Math.abs(z)==3)for(int y=0;y<=4;y++)
                    if(!putTunnelShell(s,l,d,column.above(y),shell))return false;
                if(!putTunnelShell(s,l,d,column.above(5),shell))return false;
            }
            return true;
        }
        for(int lateral=-3;lateral<=3;lateral++){
            for(int y=0;y<=4;y++)if(Math.abs(lateral)==3){
                BlockPos wall=foot.offset(nx*lateral,y,nz*lateral);
                if(!putTunnelShell(s,l,d,wall,shell))return false;
            }
            BlockPos roof=foot.offset(nx*lateral,5,nz*lateral);
            if(!putTunnelShell(s,l,d,roof,shell))return false;
        }
        return true;
    }
    boolean putTunnelShell(MinecraftServer s,ServerLevel l,Data d,BlockPos p,BlockState shell){
        if(walkVolume.contains(p))return true;
        if(!safeChange(l,p,l.getBlockState(p),shell)){halt(s,d,"Un bloque protegido impide cerrar el túnel en "+p);return false;}
        recipe.put(p,shell);workers.putIfAbsent(p,p.above());
        if(recipe.size()>MAX_EDITS){halt(s,d,"El túnel supera el límite de 250000 cambios.");return false;}
        return true;
    }
    boolean planPier(MinecraftServer s,ServerLevel l,Data d,int i){
        BlockPos center=BlockPos.of(d.route.getCompound(i).getLong("pos"));
        BlockPos before=BlockPos.of(d.route.getCompound(Math.max(0,i-1)).getLong("pos"));
        BlockPos next=BlockPos.of(d.route.getCompound(Math.min(d.route.size()-1,i+1)).getLong("pos"));
        double dx=next.getX()-before.getX(),dz=next.getZ()-before.getZ(),length=Math.hypot(dx,dz);
        if(length==0)return true;
        dx/=length;dz/=length;int added=0;boolean supported=false;
        // The shaft is a centered 3-by-3 pier, not a solid wall as wide as the deck.
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++){
            double along=x*dx+z*dz,across=-x*dz+z*dx;
            if(Math.abs(along)>1.500001||Math.abs(across)>1.600001)continue;
            BlockPos floor=deckColumns.get(net.minecraft.world.level.ChunkPos.asLong(center.getX()+x,center.getZ()+z));
            if(floor==null)continue;
            BlockState paving=pavement(l,floor);
            BlockState material=(paving.is(Blocks.SMOOTH_SANDSTONE)||paving.is(Blocks.CUT_SANDSTONE)?Blocks.SMOOTH_SANDSTONE:Blocks.STONE_BRICKS).defaultBlockState();
            ArrayList<BlockPos> column=new ArrayList<>();BlockPos q=floor.below();
            while(q.getY()>=l.getMinBuildHeight()&&!pierBearing(l,q)){
                if(!safeChange(l,q,l.getBlockState(q),material)){halt(s,d,"Un bloque protegido o sensible impide el pilar en "+q);return false;}
                column.add(q);q=q.below();
            }
            // A void has no foundation: omit the entire column, never leave a partial floating pier.
            if(q.getY()<l.getMinBuildHeight()){d.pierSkipped++;continue;}
            supported=true;
            for(BlockPos p:column){
                if(!put(p,material)){halt(s,d,"El pilar supera el límite de 250000 posiciones o cruza otra parte del plan.");return false;}
                added++;
            }
        }
        if(!supported)return true;
        // Two inverted-stair tiers widen the shaft into the bridge soffit, inside the deck edge.
        int sideX=-Integer.signum((int)Math.round(dz)),sideZ=Integer.signum((int)Math.round(dx));
        Direction towardCenter=Math.abs(sideX)>=Math.abs(sideZ)
                ?(sideX>0?Direction.WEST:Direction.EAST)
                :(sideZ>0?Direction.NORTH:Direction.SOUTH);
        BlockState paving=pavement(l,center);
        BlockState bracket=stairMaterial(paving).setValue(StairBlock.HALF,Half.TOP);
        for(int tier=0;tier<2;tier++){
            int yBelowDeck=tier==0?3:2;
            int firstSide=tier==0?2:2,lastSide=tier==0?2:3;
            for(int side:new int[]{-1,1})for(int distance=firstSide;distance<=lastSide;distance++)for(int along=-1;along<=1;along++){
                int lateralX=sideX*side*distance,lateralZ=sideZ*side*distance;
                if(sideX!=0&&sideZ!=0&&distance==2)lateralX=sideX*side;
                if(sideX!=0&&sideZ!=0&&distance==3)lateralX=sideX*side*2;
                int x=lateralX+Integer.signum((int)Math.round(dx))*along;
                int z=lateralZ+Integer.signum((int)Math.round(dz))*along;
                if(!deckColumns.containsKey(net.minecraft.world.level.ChunkPos.asLong(center.getX()+x,center.getZ()+z)))continue;
                BlockPos bracketPos=center.below(yBelowDeck).offset(x,0,z);
                Direction facing=side>0?towardCenter:towardCenter.getOpposite();
                BlockState stair=bracket.setValue(StairBlock.FACING,facing);
                BlockState existing=recipe.get(bracketPos);
                if(existing!=null&&!matches(existing,stair))continue;
                if(existing==null&&!safeChange(l,bracketPos,l.getBlockState(bracketPos),stair))continue;
                if(!put(bracketPos,stair)){halt(s,d,"El remate del pilar supera el límite del plan.");return false;}
                added++;
            }
        }
        if(added>0){d.piers++;d.pierBlocks+=added;}
        return true;
    }
    BlockState planned(ServerLevel l,BlockPos p){return recipe.getOrDefault(p,l.getBlockState(p));}
    void recordLight(Data d,BlockPos p,String type){CompoundTag t=new CompoundTag();t.putLong("pos",p.asLong());t.putString("type",type);d.lamps.add(t);d.lights++;}
    boolean postLight(ServerLevel l,Data d,BlockPos foot,int dx,int dz,int side){
        BlockPos base=foot.offset(-dz*side,-1,dx*side);
        LinkedHashMap<BlockPos,BlockState> parts=new LinkedHashMap<>();
        parts.put(base,pavement(l,base));
        for(int y=1;y<=3;y++)parts.put(base.above(y),Blocks.COBBLESTONE_WALL.defaultBlockState());
        parts.put(base.above(4),Blocks.LANTERN.defaultBlockState());
        for(var e:parts.entrySet()){
            BlockPos p=e.getKey();BlockState existing=planned(l,p);
            if(walkVolume.contains(p)||!l.hasChunkAt(p)||!l.getFluidState(p).isEmpty()||!editable(l,p))return false;
            if(!p.equals(base)&&!existing.isAir()&&!existing.is(Blocks.COBBLESTONE_WALL))return false;
            if(recipe.containsKey(p)&&!recipe.get(p).isAir()&&!recipe.get(p).equals(e.getValue()))return false;
        }
        if(!recipe.containsKey(base)){
            BlockPos support=base.below();int depth=0;
            while(!bearing(l,support)&&depth++<3){if(!editable(l,support)||!l.getFluidState(support).isEmpty())return false;parts.put(support,Blocks.STONE_BRICKS.defaultBlockState());support=support.below();}
            if(!bearing(l,support))return false;
        }
        if(recipe.size()+parts.size()>MAX_EDITS)return false;
        for(var e:parts.entrySet()){recipe.put(e.getKey(),e.getValue());workers.putIfAbsent(e.getKey(),recipeWorker);}
        recordLight(d,base.above(4),"post");return true;
    }
    void planLight(ServerLevel l,Data d,int i){
        BlockPos foot=BlockPos.of(d.route.getCompound(i).getLong("pos"));
        BlockPos next=BlockPos.of(d.route.getCompound(Math.min(i+1,d.route.size()-1)).getLong("pos"));
        BlockPos previous=BlockPos.of(d.route.getCompound(Math.max(0,i-1)).getLong("pos"));
        int dx=Integer.signum(next.getX()-foot.getX()),dz=Integer.signum(next.getZ()-foot.getZ());
        if(dx==0&&dz==0){dx=Integer.signum(foot.getX()-previous.getX());dz=Integer.signum(foot.getZ()-previous.getZ());}
        BlockPos ceiling=foot.above(5);
        if((d.route.getCompound(i).getInt("kind")==2||d.route.getCompound(i).getBoolean("liquidCover"))&&!walkVolume.contains(ceiling)&&!planned(l,ceiling).isAir()&&editable(l,ceiling)){
            recipe.put(ceiling,Blocks.GLOWSTONE.defaultBlockState());workers.putIfAbsent(ceiling,recipeWorker);recordLight(d,ceiling,"ceiling");return;
        }

        // Two flush glowstone inlays, mirrored across the path, leave the center lane open.
        ArrayList<BlockPos> pair=new ArrayList<>(2);
        for(int w:new int[]{-1,1}){
            BlockPos p=obliqueRaster?columnFoot(d,foot.getX()-dz*w,foot.getZ()+dx*w).below():foot.offset(-dz*w,-1,dx*w);
            BlockState floor=recipe.get(p);
            if(floor==null||floor.isAir()||floor.getBlock() instanceof StairBlock||!safeChange(l,p,l.getBlockState(p),Blocks.GLOWSTONE.defaultBlockState())){
                d.report="Una sección requiere espacio para las dos luces laterales en "+foot;return;
            }
            pair.add(p);
        }
        for(BlockPos p:pair){recipe.put(p,Blocks.GLOWSTONE.defaultBlockState());workers.putIfAbsent(p,recipeWorker);recordLight(d,p,"inlay");}
    }
    boolean safeChange(ServerLevel l,BlockPos p,BlockState before,BlockState after){
        if(!l.getWorldBorder().isWithinBounds(p))return false;
        if(liquidTerrain(before)||l.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)||l.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA))return editable(l,p);
        return editable(l,p)&&l.getFluidState(p).isEmpty();
    }
    BlockState read(ServerLevel l,CompoundTag t,String key){return NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK),t.getCompound(key));}
    void buildTick(MinecraftServer s,ServerLevel l,Data d){
        // The visual worker must not delay block placement.
        int edits=0,checked=0;
        while(d.cursor<d.tasks.size()&&edits<8&&checked++<64){
            CompoundTag task=d.tasks.getCompound(d.cursor);BlockPos p=BlockPos.of(task.getLong("pos"));window(l,p);if(!loaded(l,p))return;
            BlockState old=read(l,task,"before"),after=read(l,task,"after"),current=l.getBlockState(p);
            boolean pending=d.changes.size()>0&&d.changes.getCompound(d.changes.size()-1).getInt("task")==d.cursor;
            if(pending&&matches(current,after)){d.cursor++;d.setDirty();continue;}
            if(current.isAir()&&after.isAir()&&(old.is(BlockTags.LEAVES)||old.is(BlockTags.FLOWERS)||old.is(Blocks.GRASS)||old.is(Blocks.TALL_GRASS)||old.is(Blocks.FERN)||old.is(Blocks.LARGE_FERN))){d.cursor++;d.setDirty();continue;}
            if(matches(current,after)){d.cursor++;d.setDirty();continue;}
            if(!safeChange(l,p,current,after)){halt(s,d,"El terreno cambió o está protegido en "+p+". No se sobrescribió.");return;}
            if(matches(current,after)){d.cursor++;d.setDirty();continue;}
            if(!allowed(l,p)){halt(s,d,"Protección canceló el cambio en "+p);return;}
            if(after.isAir()&&!task.getBoolean("sealedTunnelInterior"))for(Direction dir:Direction.values())if(!l.getFluidState(p.relative(dir)).isEmpty()){halt(s,d,"Se detectó líquido junto a la excavación en "+p);return;}
            if(!after.isAir()&&!clearObstructions(l,p)){halt(s,d,"Un jugador ocupa "+p+"; apartate y usá continuar.");return;}
            if(!pending){CompoundTag change=task.copy();change.put("before",NbtUtils.writeBlockState(current));change.putInt("task",d.cursor);change.remove("beforeBlockEntity");if(l.getBlockEntity(p)!=null)change.put("beforeBlockEntity",l.getBlockEntity(p).saveWithFullMetadata());d.changes.add(change);d.setDirty();}
            if(l.getBlockEntity(p)!=null&&current.getBlock()!=after.getBlock())l.removeBlockEntity(p);
            if(!l.setBlock(p,after,3)){halt(s,d,"No se pudo modificar "+p);return;}
            d.cursor++;d.setDirty();edits++;if(edits==1)showWorker(l,BlockPos.of(task.getLong("worker")));
        }
        if(d.cursor>=d.tasks.size()){d.state="TERMINADO";d.report="Ruta terminada.";d.setDirty();reset();s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+d.summary()),false);}
    }
    static boolean carriesPlayer(net.minecraft.world.entity.Entity entity){
        if(entity instanceof net.minecraft.world.entity.player.Player)return true;
        for(var passenger:entity.getPassengers())if(carriesPlayer(passenger))return true;
        return false;
    }
    boolean clearObstructions(ServerLevel l,BlockPos p){
        var entities=l.getEntities((net.minecraft.world.entity.Entity)null,new net.minecraft.world.phys.AABB(p),e->e!=worker&&e.isAlive()&&!e.isSpectator());
        for(var e:entities)if(carriesPlayer(e))return false;
        for(var e:entities)e.discard();
        return true;
    }
    boolean allowed(ServerLevel l,BlockPos p){return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(l,p,l.getBlockState(p),FakePlayerFactory.getMinecraft(l)));}
    void undoTick(MinecraftServer s,ServerLevel l,Data d){
        for(int i=0;i<8;i++){
            if(d.undo<0){d.changes.clear();d.tasks.clear();d.route.clear();d.lamps.clear();d.lights=0;d.cursor=0;d.state="CONFIGURANDO";d.report="Terreno restaurado.";d.setDirty();reset();return;}
            CompoundTag t=d.changes.getCompound(d.undo);BlockPos p=BlockPos.of(t.getLong("pos"));window(l,p);if(!loaded(l,p))return;
            BlockState old=read(l,t,"before"),after=read(l,t,"after"),current=l.getBlockState(p);
            if(current.equals(old)){restoreBlockEntity(l,p,t);d.undo--;d.setDirty();continue;}
            if(!matches(current,after)||l.getBlockEntity(p)!=null||!allowed(l,p)){d.state="DESHACER_PAUSADO";d.report="Conflicto o protección al restaurar "+p;d.setDirty();reset();return;}
            if(!old.isAir()&&!clearObstructions(l,p)){d.state="DESHACER_PAUSADO";d.report="Jugador en el volumen a restaurar: "+p+"; apartate y usá continuar.";d.setDirty();reset();return;}
            if(!l.setBlock(p,old,3)){d.state="DESHACER_PAUSADO";d.report="Falló restauración "+p;d.setDirty();reset();return;}
            restoreBlockEntity(l,p,t);d.undo--;d.setDirty();
        }
    }
    void restoreBlockEntity(ServerLevel l,BlockPos p,CompoundTag task){
        if(task.contains("beforeBlockEntity")&&l.getBlockEntity(p)!=null){l.getBlockEntity(p).load(task.getCompound("beforeBlockEntity"));l.getBlockEntity(p).setChanged();l.sendBlockUpdated(p,l.getBlockState(p),l.getBlockState(p),3);}
    }
    void preview(ServerLevel l,Data d){
        int count=0;
        for(int i=0;i<d.route.size()&&count<128;i++){
            CompoundTag t=d.route.getCompound(i);BlockPos p=BlockPos.of(t.getLong("pos"));if(!l.hasChunkAt(p))continue;
            if(l.players().stream().noneMatch(player->player.distanceToSqr(p.getX(),p.getY(),p.getZ())<80*80))continue;
            l.sendParticles(t.getInt("kind")==1?ParticleTypes.END_ROD:t.getInt("kind")==2?ParticleTypes.FLAME:ParticleTypes.HAPPY_VILLAGER,p.getX()+.5,p.getY()+.2,p.getZ()+.5,1,0,0,0,0);count++;
        }
        for(int i=0;i<Math.min(64,d.lamps.size());i++){
            BlockPos p=BlockPos.of(d.lamps.getCompound(i).getLong("pos"));
            if(l.hasChunkAt(p)&&l.players().stream().anyMatch(player->player.distanceToSqr(p.getX(),p.getY(),p.getZ())<80*80))l.sendParticles(ParticleTypes.END_ROD,p.getX()+.5,p.getY()+.5,p.getZ()+.5,2,.1,.1,.1,0);
        }
        int cuts=0;for(int checked=0;checked<Math.min(2048,d.tasks.size())&&cuts<64;checked++){
            int i=Math.floorMod(previewCursor++,d.tasks.size());CompoundTag t=d.tasks.getCompound(i);if(!t.getCompound("after").getString("Name").equals("minecraft:air"))continue;BlockPos p=BlockPos.of(t.getLong("pos"));
            if(!l.hasChunkAt(p)||l.players().stream().noneMatch(player->player.distanceToSqr(p.getX(),p.getY(),p.getZ())<64*64))continue;
            l.sendParticles(ParticleTypes.SMOKE,p.getX()+.5,p.getY()+.5,p.getZ()+.5,1,0,0,0,0);cuts++;
        }
    }
    net.minecraft.world.phys.Vec3 safeFeet(ServerLevel l,double x,double z,int nearY){
        BlockPos column=BlockPos.containing(x,nearY,z);if(!l.hasChunkAt(column))return null;
        for(int y=1;y>=-2;y--){
            BlockPos p=column.offset(0,y,0),floor=p.below();
            if(!l.getBlockState(p).isAir()||!l.getBlockState(p.above()).isAir()||!l.getFluidState(floor).isEmpty())continue;
            var shape=l.getBlockState(floor).getCollisionShape(l,floor);
            if(shape.isEmpty())continue;
            double top=floor.getY()+shape.max(Direction.Axis.Y);
            var box=new net.minecraft.world.phys.AABB(x-.31,top+.01,z-.31,x+.31,top+1.91,z+.31);
            if(l.noCollision(worker,box))return new net.minecraft.world.phys.Vec3(x,top,z);
        }
        return null;
    }
    void showWorker(ServerLevel l,BlockPos p){
        net.minecraft.world.phys.Vec3 safe=null;
        for(int r=0;r<=2&&safe==null;r++)for(int x=-r;x<=r&&safe==null;x++)for(int z=-r;z<=r&&safe==null;z++)
            if(Math.max(Math.abs(x),Math.abs(z))==r)safe=safeFeet(l,p.getX()+x+.5,p.getZ()+z+.5,p.getY());
        if(safe==null)return;workerTarget=safe;
        if(worker==null||worker.isRemoved()||worker.level()!=l){
            removeWorker();workerTarget=safe;worker=EntityType.VILLAGER.create(l);if(worker==null)return;
            worker.setNoAi(true);worker.setNoGravity(true);worker.setInvulnerable(true);worker.setSilent(true);worker.setCustomName(Component.literal("Constructor"));worker.setCustomNameVisible(true);
            worker.setVillagerData(worker.getVillagerData().setProfession(VillagerProfession.MASON));worker.addTag("barttolo_adaptive_worker");
            worker.moveTo(safe.x,safe.y,safe.z,0,0);ACTIVE_WORKERS.add(worker.getUUID());
            if(!l.addFreshEntity(worker)){ACTIVE_WORKERS.remove(worker.getUUID());worker=null;return;}
        }
        worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }
    void walkWorker(ServerLevel l){
        if(worker==null||worker.isRemoved()||workerTarget==null)return;
        var position=worker.position();var delta=workerTarget.subtract(position);double horizontal=Math.sqrt(delta.x*delta.x+delta.z*delta.z);
        if(horizontal<.1&&Math.abs(delta.y)<.2){workerStuck=0;return;}
        double scale=horizontal>.18?.18/horizontal:1;
        var next=safeFeet(l,position.x+delta.x*scale,position.z+delta.z*scale,worker.blockPosition().getY());
        if(next!=null&&Math.abs(next.y-position.y)<=1.01){
            float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));worker.setYRot(yaw);worker.setYBodyRot(yaw);worker.setYHeadRot(yaw);
            worker.move(net.minecraft.world.entity.MoverType.SELF,next.subtract(position));
            if(worker.position().distanceToSqr(position)>.001){workerStuck=0;return;}
        }
        if(++workerStuck>=60){
            var safe=safeFeet(l,workerTarget.x,workerTarget.z,(int)Math.floor(workerTarget.y));
            if(safe!=null){worker.teleportTo(safe.x,safe.y,safe.z);workerStuck=0;}
        }
    }
    void halt(MinecraftServer s,Data d,String text){d.state=d.changes.isEmpty()?"RECHAZADO":"PAUSADO";d.report=text;d.setDirty();reset();s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+text),false);}
    void removeWorker(){if(worker!=null){ACTIVE_WORKERS.remove(worker.getUUID());worker.discard();worker=null;}workerTarget=null;workerStuck=0;}
    void release(){if(ticketLevel!=null)for(long key:tickets)ticketLevel.getChunkSource().removeRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key),0,key);tickets.clear();ticketLevel=null;}
    void reset(){planStartedNano=0;nextPlanNotice=0;obliqueRaster=false;release();removeWorker();search=null;recipe.clear();workers.clear();deckColumns.clear();pierIndex=0;pierDistance=15;recipeIndex=0;coverSurveyIndex=0;walkVolume.clear();tunnelExcavations.clear();sealedTunnelInterior.clear();tunnelExcavationIterator=null;planningData=null;}
    public boolean staleWorker(net.minecraft.world.entity.Entity e){return e.getTags().contains("barttolo_adaptive_worker")&&!ACTIVE_WORKERS.contains(e.getUUID());}
    public void shutdown(){reset();}
}
