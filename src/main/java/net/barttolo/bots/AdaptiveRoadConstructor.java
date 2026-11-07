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
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;

/** Bounded, incremental terrain-aware road search. All world reads stay on the server thread. */
public final class AdaptiveRoadConstructor {
    static final int MAX_NODES=16000,MAX_EDITS=120000,MAX_DISTANCE=2000;
    private static final TicketType<Long> TICKET=TicketType.create("barttolo_adaptive_road",Long::compareTo,40);
    private ServerLevel ticketLevel;
    private final Set<Long> tickets=new HashSet<>();
    private Villager worker;
    private Search search;
    private int recipeIndex,previewCursor;
    private BlockPos recipeWorker;
    private final Map<BlockPos,BlockPos> workers=new HashMap<>();
    private Data planningData;
    private final LinkedHashMap<BlockPos,BlockState> recipe=new LinkedHashMap<>();

    public static final class Data extends SavedData {
        BlockPos a,b;String dimension="minecraft:overworld",state="VACIO",report="";
        int cursor,undo,roads,bridges,tunnels,cut,fill,previewTicks;
        final ListTag route=new ListTag(),tasks=new ListTag(),changes=new ListTag();
        static Data get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(Data::load,Data::new,"barttolobots_terrain_roads_v2");}
        static Data load(CompoundTag t){
            Data d=new Data();if(t.contains("a"))d.a=BlockPos.of(t.getLong("a"));if(t.contains("b"))d.b=BlockPos.of(t.getLong("b"));
            if(t.contains("dimension"))d.dimension=t.getString("dimension");d.state=t.getString("state");d.report=t.getString("report");
            d.cursor=t.getInt("cursor");d.undo=t.getInt("undo");d.roads=t.getInt("roads");d.bridges=t.getInt("bridges");d.tunnels=t.getInt("tunnels");d.cut=t.getInt("cut");d.fill=t.getInt("fill");
            d.route.addAll(t.getList("route",10));d.tasks.addAll(t.getList("tasks",10));d.changes.addAll(t.getList("changes",10));
            if(d.state.equals("PLANIFICANDO")||d.state.equals("DISEÑANDO")){d.route.clear();d.tasks.clear();d.state="PAUSADO";}
            if(d.state.equals("CONSTRUYENDO"))d.state="PAUSADO";
            if(d.state.equals("DESHACIENDO"))d.state="DESHACER_PAUSADO";
            return d;
        }
        public CompoundTag save(CompoundTag t){
            if(a!=null)t.putLong("a",a.asLong());if(b!=null)t.putLong("b",b.asLong());
            t.putString("dimension",dimension);t.putString("state",state);t.putString("report",report);t.putInt("cursor",cursor);t.putInt("undo",undo);
            t.putInt("roads",roads);t.putInt("bridges",bridges);t.putInt("tunnels",tunnels);t.putInt("cut",cut);t.putInt("fill",fill);
            t.put("route",route.copy());t.put("tasks",tasks.copy());t.put("changes",changes.copy());return t;
        }
        String summary(){return state+" | "+cursor+"/"+tasks.size()+" cambios | camino "+roads+", puente "+bridges+", túnel "+tunnels+" secciones | excavar "+cut+", colocar "+fill+" | "+report;}
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
        Node current;int neighbor,expanded;String failure="";
        Search(BlockPos a,BlockPos b){start=a;goal=b;Node n=new Node(new Key(a,0,0,0,3),null,0,heuristic(a,b),0,0,0);open.add(n);best.put(n.key,0.0);}
    }
    static double heuristic(BlockPos a,BlockPos b){return Math.max(Math.abs(a.getX()-b.getX())+Math.abs(a.getZ()-b.getZ()),Math.abs(a.getY()-b.getY()));}
    static boolean editable(ServerLevel l,BlockPos p){
        if(l.getBlockEntity(p)!=null||!l.getWorldBorder().isWithinBounds(p))return false;
        BlockState b=l.getBlockState(p);
        return b.isAir()||b.is(BlockTags.DIRT)||b.is(BlockTags.BASE_STONE_OVERWORLD)||b.is(BlockTags.LEAVES)||b.is(BlockTags.LOGS)
            ||b.is(BlockTags.COAL_ORES)||b.is(BlockTags.IRON_ORES)||b.is(BlockTags.COPPER_ORES)||b.is(BlockTags.GOLD_ORES)||b.is(BlockTags.REDSTONE_ORES)||b.is(BlockTags.LAPIS_ORES)||b.is(BlockTags.DIAMOND_ORES)||b.is(BlockTags.EMERALD_ORES)
            ||b.is(Blocks.SAND)||b.is(Blocks.RED_SAND)||b.is(Blocks.SANDSTONE)||b.is(Blocks.RED_SANDSTONE)||b.is(Blocks.GRAVEL)||b.is(Blocks.CLAY)
            ||b.is(Blocks.GRASS)||b.is(Blocks.TALL_GRASS)||b.is(Blocks.FERN)||b.is(Blocks.LARGE_FERN)||b.is(Blocks.SNOW)||b.is(Blocks.SNOW_BLOCK)||b.is(BlockTags.FLOWERS);
    }
    private ServerLevel level(MinecraftServer s,Data d){return s.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation(d.dimension)));}
    public void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("constructor").requires(s->s.hasPermission(2));
        for(String name:List.of("crear","puntoA","puntoB","planificar","vista","iniciar","pausar","continuar","detener","estado","ir","listar","deshacer"))root.then(Commands.literal(name).executes(c->command(c.getSource(),name)));
        var legacy=Commands.literal("legado");
        for(String name:List.of("estado","deshacer","pausar"))legacy.then(Commands.literal(name).executes(c->Bots.roads.command(c.getSource(),name)));
        root.then(legacy);dispatcher.register(Commands.literal("bot").requires(s->s.hasPermission(2)).then(root));
    }
    int command(CommandSourceStack s,String name)throws CommandSyntaxException{
        Data d=Data.get(s.getServer());
        switch(name){
            case "crear":
                if(!d.changes.isEmpty())return fail(s,"Primero deshacé la obra anterior: conserva su historial.");
                reset();d.a=null;d.b=null;d.cursor=0;d.route.clear();d.tasks.clear();d.dimension=s.getLevel().dimension().location().toString();d.state="CONFIGURANDO";d.report="Marcá A y B: alturas y direcciones libres.";break;
            case "puntoA":case "puntoB":
                if(!Set.of("CONFIGURANDO","RECHAZADO").contains(d.state)||!d.changes.isEmpty())return fail(s,"Primero crear una obra nueva.");
                if(!d.dimension.equals(s.getLevel().dimension().location().toString()))return fail(s,"A y B deben estar en la misma dimensión.");
                if(name.equals("puntoA"))d.a=s.getPlayerOrException().blockPosition();else d.b=s.getPlayerOrException().blockPosition();
                d.state="CONFIGURANDO";break;
            case "planificar":
                if(!Set.of("CONFIGURANDO","RECHAZADO").contains(d.state))return fail(s,"La obra ya tiene un plan. Pausá o creá otra.");
                if(d.a==null||d.b==null)return fail(s,"Marcá ambos puntos.");
                if(d.a.getX()==d.b.getX()&&d.a.getZ()==d.b.getZ())return fail(s,"Separá horizontalmente los puntos para poder diseñar una pendiente.");
                if(heuristic(d.a,d.b)>MAX_DISTANCE)return fail(s,"Distancia máxima: 2000 bloques.");
                begin(d);break;
            case "vista":
                if(!Set.of("LISTO","CONFIRMADO").contains(d.state))return fail(s,"Esperá el plan LISTO.");
                d.state="CONFIRMADO";d.previewTicks=200;d.report="Partículas: trazado y excavaciones durante 10 segundos cerca de jugadores. iniciar confirma los cambios.";break;
            case "iniciar":
                if(!d.state.equals("CONFIRMADO"))return fail(s,"Primero planificar y vista; iniciar confirma también la excavación.");
                d.state="CONSTRUYENDO";d.report="Construcción progresiva.";break;
            case "pausar":case "detener":
                if(Set.of("PLANIFICANDO","DISEÑANDO").contains(d.state)){d.tasks.clear();d.route.clear();}
                if(d.state.equals("DESHACIENDO"))d.state="DESHACER_PAUSADO";
                else if(Set.of("CONSTRUYENDO","PLANIFICANDO","DISEÑANDO").contains(d.state))d.state="PAUSADO";
                reset();break;
            case "continuar":
                if(d.state.equals("DESHACER_PAUSADO")){d.state="DESHACIENDO";break;}
                if(!d.state.equals("PAUSADO"))return fail(s,"No hay obra pausada.");
                if(d.tasks.isEmpty())begin(d);else{d.state="LISTO";d.report="Plan recuperado; usá vista e iniciar. Cada bloque se revalidará al modificarlo.";}break;
            case "deshacer":
                if(Set.of("CONSTRUYENDO","PLANIFICANDO","DISEÑANDO").contains(d.state))return fail(s,"Pausá antes de deshacer.");
                if(d.changes.isEmpty())return fail(s,"No hay cambios en la obra nueva. Para 0.1.0 usá /bot constructor legado deshacer.");
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
    static int fail(CommandSourceStack s,String text){s.sendFailure(Component.literal(text));return 0;}
    void begin(Data d){reset();d.route.clear();d.tasks.clear();d.cursor=0;d.roads=d.bridges=d.tunnels=d.cut=d.fill=0;d.state="PLANIFICANDO";d.report="Buscando por cuadrícula 3D; analiza rodeos, desniveles, excavación y soporte.";search=new Search(d.a,d.b);planningData=d;}
    public void tick(MinecraftServer s){
        Data d=Data.get(s);if(s.getAverageTickTime()>55&&s.getTickCount()%4!=0)return;tickProject(s,d);
    }
    void tickProject(MinecraftServer s,Data d){
        ServerLevel l=level(s,d);if(l==null){if(!d.state.equals("VACIO"))halt(s,d,"Dimensión no disponible.");return;}
        if(d.previewTicks>0){if(s.getTickCount()%10==0)preview(l,d);d.previewTicks--;}
        if(d.state.equals("PLANIFICANDO")){if(search==null||planningData!=d){search=new Search(d.a,d.b);planningData=d;}searchTick(s,l,d);return;}
        if(d.state.equals("DISEÑANDO")){designTick(s,l,d);return;}
        if(d.state.equals("CONSTRUYENDO")){buildTick(s,l,d);return;}
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
    Survey survey(ServerLevel l,BlockPos foot){
        if(foot.getY()<l.getMinBuildHeight()+14||foot.getY()+6>=l.getMaxBuildHeight())return null;
        int cut=0,fill=0,depth=0;boolean elevated=false,roof=false;
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            BlockPos floor=foot.offset(x,-1,z);if(!editable(l,floor)||!l.getFluidState(floor).isEmpty())return null;
            for(int h=1;h<=5;h++){
                BlockPos q=floor.above(h);if(!editable(l,q)||!l.getFluidState(q).isEmpty())return null;
                // Never open a dry tunnel into adjacent liquid.
                for(Direction dir:Direction.Plane.HORIZONTAL)if(!l.getFluidState(q.relative(dir)).isEmpty())return null;
                if(!l.getBlockState(q).isAir())cut++;
            }
            BlockPos ceiling=floor.above(6);if(!l.getFluidState(ceiling).isEmpty()||l.getBlockEntity(ceiling)!=null)return null;
            if(l.getBlockState(ceiling).is(Blocks.STONE)||l.getBlockState(ceiling).is(BlockTags.BASE_STONE_OVERWORLD))roof=true;
            int n=0;BlockPos support=floor.below();
            while(!l.getBlockState(support).isFaceSturdy(l,support,Direction.UP)&&n<12){
                if(!editable(l,support)&&!l.getBlockState(support).is(Blocks.WATER))return null;
                if(!l.getFluidState(support).isEmpty()&&!l.getBlockState(support).is(Blocks.WATER))return null;
                support=support.below();n++;
            }
            if(n>=12||l.getBlockEntity(support)!=null||l.getBlockState(support).is(Blocks.BEDROCK))return null;
            depth=Math.max(depth,n);if(n>1||l.getBlockState(floor).isAir())elevated=true;fill+=n;
        }
        return new Survey(elevated?1:roof&&cut>0?2:0,cut,fill,depth);
    }
    void searchTick(MinecraftServer s,ServerLevel l,Data d){
        int[][] dirs={{1,0},{0,1},{-1,0},{0,-1}};int[] grades={0,1,-1};
        for(int budget=0;budget<4;budget++){
            if(search.current==null){
                while(!search.open.isEmpty()){
                    Node n=search.open.poll();if(n.cost<=search.best.getOrDefault(n.key,Double.MAX_VALUE)){search.current=n;break;}
                }
                if(search.current==null){halt(s,d,"No encontró una ruta segura en el área explorada.");return;}
                if(++search.expanded>MAX_NODES){halt(s,d,"Límite de exploración alcanzado; probá puntos intermedios.");return;}
                Node n=search.current;
                if(n.key.pos.equals(d.b)){
                    ArrayList<Node> nodes=new ArrayList<>();for(Node p=n;p!=null;p=p.parent)nodes.add(p);Collections.reverse(nodes);
                    for(Node p:nodes){CompoundTag t=new CompoundTag();t.putLong("pos",p.key.pos.asLong());t.putInt("kind",p.kind);d.route.add(t);}
                    d.state="DISEÑANDO";recipeIndex=0;recipe.clear();d.report="Ruta encontrada: "+nodes.size()+" secciones. Calculando excavaciones y materiales.";d.setDirty();search=null;return;
                }
                search.neighbor=0;
            }
            Node from=search.current;int i=search.neighbor;int[] dir=dirs[i/3];int grade=grades[i%3];
            boolean turn=from.parent!=null&&(from.key.dx!=dir[0]||from.key.dz!=dir[1]);
            if((turn&&(grade!=0||from.key.flat<3))||(grade!=0&&from.key.flat<0)||(from.key.grade>0&&grade<0)||(turn&&from.key.dx==-dir[0]&&from.key.dz==-dir[1])){advance();continue;}
            BlockPos pos=from.key.pos.offset(dir[0],grade,dir[1]);
            if(from.length>=MAX_DISTANCE||Math.abs(pos.getX()-d.a.getX())+Math.abs(pos.getZ()-d.a.getZ())>MAX_DISTANCE
                ||heuristic(pos,d.b)>MAX_DISTANCE||Math.abs(pos.getY()-d.a.getY())>96){advance();continue;}
            window(l,pos);if(!loaded(l,pos))return;
            Survey v=search.cache.get(pos);
            if(v==null){v=survey(l,pos);search.cache.put(pos,v==null?new Survey(-1,0,0,0):v);}
            if(v.kind>=0){
                int span=v.kind==1?from.span+1:0;
                if(span<=48){
                    double cost=from.cost+1+(grade==0?0:.8)+(turn?.6:0)+v.cut*.018+v.fill*.012+(v.kind==1?.8:0);
                    Key key=new Key(pos,dir[0],dir[1],grade,grade==0?(turn?-2:Math.min(3,from.key.flat+1)):0);
                    if(cost<search.best.getOrDefault(key,Double.MAX_VALUE)){
                        search.best.put(key,cost);search.open.add(new Node(key,from,cost,heuristic(pos,d.b),v.kind,v.depth,span));
                    }
                }
            }
            advance();d.report="Exploradas "+search.expanded+" celdas; pendientes "+search.open.size()+".";
        }
    }
    void advance(){if(++search.neighbor>=12)search.current=null;}
    BlockState pavement(ServerLevel l,BlockPos p){
        String biome=l.getBiome(p).unwrapKey().map(k->k.location().getPath()).orElse("");long hash=Math.floorMod(p.getX()*7349L+p.getZ()*9151L,5);
        if(biome.contains("desert")||biome.contains("badlands"))return (hash==0?Blocks.CUT_SANDSTONE:Blocks.SMOOTH_SANDSTONE).defaultBlockState();
        if(biome.contains("taiga")||biome.contains("snow")||biome.contains("frozen"))return (hash==0?Blocks.COBBLESTONE:Blocks.STONE_BRICKS).defaultBlockState();
        if(biome.contains("swamp"))return Blocks.MANGROVE_PLANKS.defaultBlockState();
        return (hash==0?Blocks.ANDESITE:Blocks.STONE_BRICKS).defaultBlockState();
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
        if(planningData!=d){halt(s,d,"El diseño fue interrumpido. Volvé a planificar.");return;}
        if(recipeIndex<d.route.size()){
            int i=recipeIndex;BlockPos foot=BlockPos.of(d.route.getCompound(i).getLong("pos"));window(l,foot);if(!loaded(l,foot))return;
            BlockPos before=BlockPos.of(d.route.getCompound(Math.max(0,i-1)).getLong("pos"));
            BlockPos next=BlockPos.of(d.route.getCompound(Math.min(d.route.size()-1,i+1)).getLong("pos"));
            int dx=Integer.signum(next.getX()-foot.getX()),dz=Integer.signum(next.getZ()-foot.getZ());if(dx==0&&dz==0){dx=Integer.signum(foot.getX()-before.getX());dz=Integer.signum(foot.getZ()-before.getZ());}
            recipeWorker=foot;int kind=d.route.getCompound(i).getInt("kind");if(kind==1)d.bridges++;else if(kind==2)d.tunnels++;else d.roads++;
            int px=Integer.signum(foot.getX()-before.getX()),pz=Integer.signum(foot.getZ()-before.getZ());boolean corner=i>0&&i<d.route.size()-1&&(px!=dx||pz!=dz);
            boolean stairs=before.getY()<foot.getY()||next.getY()<foot.getY();
            Direction ascent=before.getY()<foot.getY()?Direction.fromDelta(px,0,pz):Direction.fromDelta(-dx,0,-dz);
            for(int w=-2;w<=2;w++)for(int along=corner?-2:0;along<=(corner?2:0);along++){
                BlockPos floor=foot.offset(-dz*w+dx*along,-1,dx*w+dz*along);
                BlockPos ceiling=floor.above(6);if(l.getBlockState(ceiling).getBlock() instanceof FallingBlock)put(ceiling,Blocks.STONE_BRICKS.defaultBlockState());
                for(int h=5;h>=1;h--)if(!l.getBlockState(floor.above(h)).isAir()&&!put(floor.above(h),Blocks.AIR.defaultBlockState())){halt(s,d,"Solapamiento de trazado; probá puntos intermedios.");return;}
                BlockState paving=stairs?Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,ascent):pavement(l,floor);
                if(!put(floor,paving)){halt(s,d,"Cruce a distinta altura: requiere otro trazado.");return;}
                // Roads and ramps get shallow foundations; bridges get piers every five sections and at ends.
                if(kind!=1||i%5==0||i==d.route.size()-1){
                    BlockPos support=floor.below();int n=0;
                    while(!l.getBlockState(support).isFaceSturdy(l,support,Direction.UP)&&n++<12){
                        if(!put(support,Blocks.STONE_BRICKS.defaultBlockState())){halt(s,d,"Demasiados cambios en el soporte.");return;}support=support.below();
                    }
                }
            }
            if(kind==1)for(int side:new int[]{-3,3}){
                BlockPos edge=foot.offset(-dz*side,-1,dx*side);BlockPos rail=edge.above();
                if(!editable(l,edge)||!editable(l,rail)||!l.getFluidState(rail).isEmpty()){halt(s,d,"No hay espacio seguro para barandillas.");return;}
                if(!put(edge,pavement(l,edge))||!put(rail,Blocks.COBBLESTONE_WALL.defaultBlockState())){halt(s,d,"Barandilla cruza otra sección.");return;}
            }
            recipeIndex++;d.setDirty();return;
        }
        // Snapshot one small batch per tick, under a moving chunk window.
        int end=Math.min(recipe.size(),d.tasks.size()+64);int index=0;
        for(var e:recipe.entrySet()){
            if(index++<d.tasks.size())continue;if(d.tasks.size()>=end)break;
            BlockPos p=e.getKey();window(l,p);if(!loaded(l,p))return;
            BlockState old=l.getBlockState(p),after=e.getValue();
            if(!safeChange(l,p,old,after)){halt(s,d,"Bloque sensible o líquido en el diseño: "+p);return;}
            CompoundTag t=new CompoundTag();t.putLong("pos",p.asLong());t.put("before",NbtUtils.writeBlockState(old));t.put("after",NbtUtils.writeBlockState(after));
            t.putLong("worker",workers.getOrDefault(p,p.above()).asLong());d.tasks.add(t);if(!old.equals(after)){if(after.isAir())d.cut++;else d.fill++;}
        }
        if(d.tasks.size()==recipe.size()){
            d.state="LISTO";d.report="Plan calculado. vista muestra el trazado; iniciar autoriza excavar y construir.";d.setDirty();recipe.clear();release();
            s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+d.summary()),false);
        }
    }
    boolean safeChange(ServerLevel l,BlockPos p,BlockState before,BlockState after){
        if(l.getBlockEntity(p)!=null||!l.getWorldBorder().isWithinBounds(p))return false;
        if(before.is(Blocks.WATER)&&!after.isAir())return true;
        return editable(l,p)&&l.getFluidState(p).isEmpty();
    }
    BlockState read(ServerLevel l,CompoundTag t,String key){return NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK),t.getCompound(key));}
    void buildTick(MinecraftServer s,ServerLevel l,Data d){
        int edits=0,checked=0;
        while(d.cursor<d.tasks.size()&&edits<8&&checked++<64){
            CompoundTag task=d.tasks.getCompound(d.cursor);BlockPos p=BlockPos.of(task.getLong("pos"));window(l,p);if(!loaded(l,p))return;
            BlockState old=read(l,task,"before"),after=read(l,task,"after"),current=l.getBlockState(p);
            boolean pending=d.changes.size()>0&&d.changes.getCompound(d.changes.size()-1).getInt("task")==d.cursor;
            if(pending&&matches(current,after)){d.cursor++;d.setDirty();continue;}
            if(current.isAir()&&after.isAir()&&(old.is(BlockTags.LEAVES)||old.is(BlockTags.FLOWERS)||old.is(Blocks.GRASS)||old.is(Blocks.TALL_GRASS)||old.is(Blocks.FERN)||old.is(Blocks.LARGE_FERN))){d.cursor++;d.setDirty();continue;}
            if(!current.equals(old)||!safeChange(l,p,current,after)){halt(s,d,"El terreno cambió o está protegido en "+p+". No se sobrescribió.");return;}
            if(old.equals(after)){d.cursor++;d.setDirty();continue;}
            if(!allowed(l,p)){halt(s,d,"Protección canceló el cambio en "+p);return;}
            if(after.isAir())for(Direction dir:Direction.values())if(!l.getFluidState(p.relative(dir)).isEmpty()){halt(s,d,"Se detectó líquido junto a la excavación en "+p);return;}
            if(!after.isAir()&&!l.getEntities(null,new net.minecraft.world.phys.AABB(p),e->e!=worker&&e.isAlive()&&!e.isSpectator()).isEmpty()){halt(s,d,"Una entidad ocupa "+p+"; movela y continuá.");return;}
            if(!pending){CompoundTag change=task.copy();change.putInt("task",d.cursor);d.changes.add(change);d.setDirty();}
            if(!l.setBlock(p,after,3)){halt(s,d,"No se pudo modificar "+p);return;}
            d.cursor++;d.setDirty();edits++;showWorker(l,BlockPos.of(task.getLong("worker")));
        }
        if(d.cursor>=d.tasks.size()){d.state="TERMINADO";d.report="Ruta terminada.";d.setDirty();reset();s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+d.summary()),false);}
    }
    boolean allowed(ServerLevel l,BlockPos p){return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(l,p,l.getBlockState(p),FakePlayerFactory.getMinecraft(l)));}
    void undoTick(MinecraftServer s,ServerLevel l,Data d){
        for(int i=0;i<8;i++){
            if(d.undo<0){d.changes.clear();d.tasks.clear();d.route.clear();d.cursor=0;d.state="CONFIGURANDO";d.report="Terreno restaurado.";d.setDirty();reset();return;}
            CompoundTag t=d.changes.getCompound(d.undo);BlockPos p=BlockPos.of(t.getLong("pos"));window(l,p);if(!loaded(l,p))return;
            BlockState old=read(l,t,"before"),after=read(l,t,"after"),current=l.getBlockState(p);
            if(current.equals(old)){d.undo--;d.setDirty();continue;}
            if(!matches(current,after)||l.getBlockEntity(p)!=null||!allowed(l,p)){d.state="DESHACER_PAUSADO";d.report="Conflicto o protección al restaurar "+p;d.setDirty();reset();return;}
            if(!old.isAir()&&!l.getEntities(null,new net.minecraft.world.phys.AABB(p),e->e!=worker&&e.isAlive()&&!e.isSpectator()).isEmpty()){d.state="DESHACER_PAUSADO";d.report="Entidad en el volumen a restaurar: "+p;d.setDirty();reset();return;}
            if(!l.setBlock(p,old,3)){d.state="DESHACER_PAUSADO";d.report="Falló restauración "+p;d.setDirty();reset();return;}
            d.undo--;d.setDirty();
        }
    }
    void preview(ServerLevel l,Data d){
        int count=0;
        for(int i=0;i<d.route.size()&&count<128;i++){
            CompoundTag t=d.route.getCompound(i);BlockPos p=BlockPos.of(t.getLong("pos"));if(!l.hasChunkAt(p))continue;
            if(l.players().stream().noneMatch(player->player.distanceToSqr(p.getX(),p.getY(),p.getZ())<80*80))continue;
            l.sendParticles(t.getInt("kind")==1?ParticleTypes.END_ROD:t.getInt("kind")==2?ParticleTypes.FLAME:ParticleTypes.HAPPY_VILLAGER,p.getX()+.5,p.getY()+.2,p.getZ()+.5,1,0,0,0,0);count++;
        }
        int cuts=0;for(int checked=0;checked<Math.min(2048,d.tasks.size())&&cuts<64;checked++){
            int i=Math.floorMod(previewCursor++,d.tasks.size());CompoundTag t=d.tasks.getCompound(i);if(!t.getCompound("after").getString("Name").equals("minecraft:air"))continue;BlockPos p=BlockPos.of(t.getLong("pos"));
            if(!l.hasChunkAt(p)||l.players().stream().noneMatch(player->player.distanceToSqr(p.getX(),p.getY(),p.getZ())<64*64))continue;
            l.sendParticles(ParticleTypes.SMOKE,p.getX()+.5,p.getY()+.5,p.getZ()+.5,1,0,0,0,0);cuts++;
        }
    }
    void showWorker(ServerLevel l,BlockPos p){
        // Only show the worker where a full two-block air volume is available.
        BlockPos safe=null;for(int y=0;y<=6;y++)if(l.getBlockState(p.above(y)).isAir()&&l.getBlockState(p.above(y+1)).isAir()){safe=p.above(y);break;}
        if(safe==null)return;
        if(worker==null||worker.isRemoved()||worker.level()!=l){removeWorker();worker=EntityType.VILLAGER.create(l);if(worker==null)return;worker.setNoAi(true);worker.setInvulnerable(true);worker.setSilent(true);worker.setCustomName(Component.literal("Constructor"));worker.setCustomNameVisible(true);worker.setVillagerData(worker.getVillagerData().setProfession(VillagerProfession.MASON));worker.addTag("barttolo_adaptive_worker");worker.moveTo(safe.getX()+.5,safe.getY(),safe.getZ()+.5,0,0);l.addFreshEntity(worker);}
        worker.teleportTo(safe.getX()+.5,safe.getY(),safe.getZ()+.5);worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }
    void halt(MinecraftServer s,Data d,String text){d.state=d.changes.isEmpty()?"RECHAZADO":"PAUSADO";d.report=text;d.setDirty();reset();s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+text),false);}
    void removeWorker(){if(worker!=null){worker.discard();worker=null;}}
    void release(){if(ticketLevel!=null)for(long key:tickets)ticketLevel.getChunkSource().removeRegionTicket(TICKET,new net.minecraft.world.level.ChunkPos(key),0,key);tickets.clear();ticketLevel=null;}
    void reset(){release();removeWorker();search=null;recipe.clear();workers.clear();recipeIndex=0;planningData=null;}
    public boolean staleWorker(net.minecraft.world.entity.Entity e){return e.getTags().contains("barttolo_adaptive_worker")&&e!=worker;}
    public void shutdown(){reset();}
}
