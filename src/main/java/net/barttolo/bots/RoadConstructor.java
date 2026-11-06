package net.barttolo.bots;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.server.level.TicketType;
import net.minecraftforge.event.level.BlockEvent;
import com.mojang.brigadier.CommandDispatcher;
import java.util.*;

/** Conservative first road prototype: flat, cardinal, dry corridors only. */
public final class RoadConstructor {
    private static final TicketType<Long> TICKET=TicketType.create("barttolo_road",Long::compareTo,40);
    private final Set<Long> tickets=new HashSet<>();
    private ServerLevel ticketLevel;
    private Villager worker;

    public static final class Data extends SavedData {
        BlockPos a,b; String dimension="minecraft:overworld",state="VACIO",report="";
        int scan,cursor,undo; final ListTag changes=new ListTag();
        static Data get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(Data::load,Data::new,"barttolobots_roads_v1");}
        static Data load(CompoundTag t){
            Data d=new Data();if(t.contains("a"))d.a=BlockPos.of(t.getLong("a"));if(t.contains("b"))d.b=BlockPos.of(t.getLong("b"));
            d.dimension=t.getString("dimension");d.state=t.getString("state");d.report=t.getString("report");
            d.scan=t.getInt("scan");d.cursor=t.getInt("cursor");d.undo=t.getInt("undo");d.changes.addAll(t.getList("changes",10));
            // Explicit resumption after a restart; no persistent chunk tickets.
            if(d.state.equals("CONSTRUYENDO")||d.state.equals("PLANIFICANDO"))d.state="PAUSADO";
            if(d.state.equals("DESHACIENDO"))d.state="DESHACER_PAUSADO";
            return d;
        }
        public CompoundTag save(CompoundTag t){
            if(a!=null)t.putLong("a",a.asLong());if(b!=null)t.putLong("b",b.asLong());
            t.putString("dimension",dimension);t.putString("state",state);t.putString("report",report);
            t.putInt("scan",scan);t.putInt("cursor",cursor);t.putInt("undo",undo);t.put("changes",changes.copy());return t;
        }
        int length(){return a==null||b==null?0:Math.abs(a.getX()-b.getX())+Math.abs(a.getZ()-b.getZ())+1;}
        BlockPos cell(int i){int[] p=RoadCorridor.cell(a.getX(),a.getZ(),b.getX(),b.getZ(),i);return new BlockPos(p[0],a.getY()-1,p[1]);}
    }
    public void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("constructor").requires(s->s.hasPermission(2));
        for(String action:List.of("crear","puntoA","puntoB","planificar","vista","iniciar","pausar","continuar","detener","estado","ir","listar","deshacer")){
            final String command=action;root.then(Commands.literal(action).executes(c->command(c.getSource(),command)));
        }
        dispatcher.register(Commands.literal("bot").requires(s->s.hasPermission(2)).then(root));
    }
    private int command(CommandSourceStack s,String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Data d=Data.get(s.getServer());
        switch(action){
            case "crear":
                if(d.changes.size()>0)return fail(s,"Deshacé la obra anterior antes de crear otra; se conserva su historial.");
                release();removeWorker();d.a=null;d.b=null;d.scan=0;d.cursor=0;d.state="CONFIGURANDO";d.dimension=s.getLevel().dimension().location().toString();break;
            case "puntoA":case "puntoB":
                if(!d.state.equals("CONFIGURANDO"))return fail(s,"Primero /bot constructor crear.");
                if(!d.dimension.equals(s.getLevel().dimension().location().toString()))return fail(s,"Ambos puntos deben estar en la misma dimensión.");
                BlockPos pos=s.getPlayerOrException().blockPosition();if(action.equals("puntoA"))d.a=pos;else d.b=pos;break;
            case "planificar":
                if(!d.state.equals("CONFIGURANDO")&&!d.state.equals("RECHAZADO"))return fail(s,"Creá una obra y fijá ambos puntos.");
                if(d.a==null||d.b==null)return fail(s,"Faltan puntoA o puntoB.");
                if(d.a.getY()!=d.b.getY()||(d.a.getX()!=d.b.getX()&&d.a.getZ()!=d.b.getZ()))return fail(s,"0.1.0 requiere puntos a igual altura, alineados en X o Z.");
                if(d.a.equals(d.b)||d.length()>2001)return fail(s,"La distancia debe ser de 1 a 2000 bloques.");
                d.scan=0;d.state="PLANIFICANDO";d.report="Revisión progresiva del corredor de 5 bloques.";break;
            case "vista":
                if(!d.state.equals("LISTO"))return fail(s,"Esperá un plan válido.");
                d.state="CONFIRMADO";d.report="Vista: "+d.a+" → "+d.b+", ancho 5, "+d.length()+" secciones; pavimento stone_bricks. Confirmá con iniciar.";break;
            case "iniciar":
                if(!d.state.equals("CONFIRMADO"))return fail(s,"Primero planificar y vista. iniciar confirma la construcción.");
                d.state="CONSTRUYENDO";break;
            case "continuar":
                if(d.state.equals("DESHACER_PAUSADO")){d.state="DESHACIENDO";break;}
                if(!d.state.equals("PAUSADO"))return fail(s,"La obra no está pausada.");
                // Revalidate all remaining terrain, requiring a new confirmation.
                d.scan=d.cursor;d.state="PLANIFICANDO";break;
            case "pausar":case "detener":
                if(d.state.equals("DESHACIENDO"))d.state="DESHACER_PAUSADO";
                else if(d.state.equals("CONSTRUYENDO")||d.state.equals("PLANIFICANDO"))d.state="PAUSADO";
                release();removeWorker();break;
            case "deshacer":
                if(d.state.equals("CONSTRUYENDO")||d.state.equals("PLANIFICANDO"))return fail(s,"Pausá antes de deshacer.");
                if(d.changes.isEmpty())return fail(s,"No hay cambios guardados.");
                d.undo=d.changes.size()-1;d.state="DESHACIENDO";break;
            case "ir":
                if(d.a==null||d.b==null||d.a.equals(d.b))return fail(s,"No hay ruta válida.");
                ServerLevel level=level(s.getServer(),d);if(level==null)return fail(s,"Dimensión no disponible.");
                BlockPos p=d.cell(Math.min(d.cursor,d.length()*5-1)).above();
                // Teleport only to an already loaded and verified safe position.
                if(!level.hasChunkAt(p)||!level.getBlockState(p.below()).isFaceSturdy(level,p.below(),net.minecraft.core.Direction.UP)||!level.getBlockState(p).isAir()||!level.getBlockState(p.above()).isAir())return fail(s,"No hay posición segura cargada cerca de la obra.");
                s.getPlayerOrException().teleportTo(level,p.getX()+.5,p.getY(),p.getZ()+.5,0,0);break;
            case "estado":case "listar":break;
            default:return 0;
        }
        d.setDirty();s.sendSuccess(()->Component.literal("Constructor: "+d.state+" | "+d.cursor+"/"+(d.length()*5)+" | "+d.report),false);return 1;
    }
    private static int fail(CommandSourceStack s,String message){s.sendFailure(Component.literal(message));return 0;}
    private static ServerLevel level(MinecraftServer s,Data d){return s.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation(d.dimension)));}
    private static boolean natural(BlockState b){return b.is(Blocks.GRASS_BLOCK)||b.is(Blocks.DIRT)||b.is(Blocks.COARSE_DIRT)||b.is(Blocks.STONE)||b.is(Blocks.SAND)||b.is(Blocks.GRAVEL);}
    private String obstacle(ServerLevel l,BlockPos p,boolean roadAllowed){
        if(!l.getWorldBorder().isWithinBounds(p)||p.getY()<l.getMinBuildHeight()+1||p.getY()+3>=l.getMaxBuildHeight())return "Límite del mundo.";
        BlockState old=l.getBlockState(p);
        if(l.getBlockEntity(p)!=null||(!natural(old)&&!(roadAllowed&&old.is(Blocks.STONE_BRICKS))))return "Suelo sensible, vacío o no natural en "+p;
        if(!l.getFluidState(p).isEmpty()||!l.getBlockState(p.below()).isFaceSturdy(l,p.below(),net.minecraft.core.Direction.UP))return "Líquido o falta de soporte en "+p;
        for(int y=1;y<=3;y++)if(!l.getBlockState(p.above(y)).isAir())return "Obstáculo sobre el camino en "+p;
        return null;
    }
    private void loadWindow(ServerLevel l,BlockPos p){
        if(ticketLevel!=l){release();ticketLevel=l;}
        int cx=p.getX()>>4,cz=p.getZ()>>4;Set<Long> wanted=new HashSet<>();
        for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++)wanted.add(net.minecraft.world.level.ChunkPos.asLong(x,z));
        for(long key:new HashSet<>(tickets))if(!wanted.contains(key)){var c=new net.minecraft.world.level.ChunkPos(key);l.getChunkSource().removeRegionTicket(TICKET,c,0,key);tickets.remove(key);}
        for(long key:wanted){tickets.add(key);var c=new net.minecraft.world.level.ChunkPos(key);l.getChunkSource().addRegionTicket(TICKET,c,0,key);}
    }
    public void tick(MinecraftServer s){
        if(s.getAverageTickTime()>55&&s.getTickCount()%4!=0)return;
        tickProject(s,Data.get(s));
    }
    void tickProject(MinecraftServer s,Data d){
        boolean plan=d.state.equals("PLANIFICANDO"),build=d.state.equals("CONSTRUYENDO"),undo=d.state.equals("DESHACIENDO");
        if(!plan&&!build&&!undo){release();removeWorker();return;}
        ServerLevel l=level(s,d);if(l==null){halt(s,d,"Dimensión ausente.");return;}
        int total=d.length()*5;
        for(int budget=0;budget<8;budget++){
            if(undo){
                if(d.undo<0){d.changes.clear();d.cursor=0;d.state="CONFIGURANDO";d.report="Deshacer terminado.";d.setDirty();release();return;}
                CompoundTag t=d.changes.getCompound(d.undo);BlockPos p=BlockPos.of(t.getLong("pos"));loadWindow(l,p);
                if(!l.hasChunkAt(p))return;
                BlockState expected=NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK),t.getCompound("after"));
                BlockState current=l.getBlockState(p),before=NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK),t.getCompound("before"));
                if(current.equals(before)){d.undo--;d.setDirty();continue;}
                if(!current.equals(expected)||l.getBlockEntity(p)!=null){d.state="DESHACER_PAUSADO";d.report="Conflicto al restaurar "+p+"; no se sobrescribió.";d.setDirty();release();return;}
                if(MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(l,p,current,net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(l)))){d.state="DESHACER_PAUSADO";d.report="Protección impidió restaurar "+p;d.setDirty();release();return;}
                if(!l.setBlock(p,before,3)){d.state="DESHACER_PAUSADO";d.report="No se pudo restaurar "+p;d.setDirty();return;}
                d.undo--;d.setDirty();continue;
            }
            int index=plan?d.scan:d.cursor;
            if(index>=total){d.state=plan?"LISTO":"TERMINADO";d.report=plan?"Corredor seco y plano válido. Usá vista.":"Camino terminado.";d.setDirty();release();removeWorker();s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor: "+d.report),false);return;}
            BlockPos p=d.cell(index);loadWindow(l,p);if(!l.hasChunkAt(p))return;
            if(!plan&&d.changes.size()>d.cursor){
                CompoundTag pending=d.changes.getCompound(d.cursor);
                if(pending.getLong("pos")!=p.asLong()){halt(s,d,"Historial inconsistente.");return;}
                BlockState after=NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK),pending.getCompound("after"));
                BlockState before=NbtUtils.readBlockState(l.holderLookup(Registries.BLOCK),pending.getCompound("before"));
                if(l.getBlockState(p).equals(after)){d.cursor++;d.setDirty();continue;}
                if(!l.getBlockState(p).equals(before)){halt(s,d,"Conflicto en cambio pendiente "+p);return;}
                d.changes.remove(d.cursor);d.setDirty();
            }
            boolean pendingRoad=plan&&d.changes.size()>index&&d.changes.getCompound(index).getLong("pos")==p.asLong();
            String problem=obstacle(l,p,pendingRoad);if(problem!=null){halt(s,d,problem);return;}
            if(plan){d.scan++;d.setDirty();continue;}
            var fake=net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(l);
            if(MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(l,p,l.getBlockState(p),fake))){halt(s,d,"Protección impidió cambiar "+p);return;}
            CompoundTag change=new CompoundTag();change.putLong("pos",p.asLong());change.put("before",NbtUtils.writeBlockState(l.getBlockState(p)));change.put("after",NbtUtils.writeBlockState(Blocks.STONE_BRICKS.defaultBlockState()));
            d.changes.add(change);d.setDirty();
            if(!l.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),3)){d.changes.remove(d.changes.size()-1);halt(s,d,"Falló colocación en "+p);return;}
            d.cursor++;d.setDirty();
            if(index%5==0)showWorker(l,p.above());
        }
    }
    private void showWorker(ServerLevel l,BlockPos p){
        if(worker==null||worker.isRemoved()||worker.level()!=l){removeWorker();worker=EntityType.VILLAGER.create(l);if(worker==null)return;worker.setVillagerData(worker.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.MASON));worker.setNoAi(true);worker.setInvulnerable(true);worker.setSilent(true);worker.setCustomName(Component.literal("Constructor"));worker.setCustomNameVisible(true);worker.setPersistenceRequired();worker.addTag("barttolo_road_worker");worker.moveTo(p.getX()+.5,p.getY(),p.getZ()+.5,0,0);l.addFreshEntity(worker);}
        worker.teleportTo(p.getX()+.5,p.getY(),p.getZ()+.5);worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }
    private void halt(MinecraftServer s,Data d,String message){d.state=d.cursor>0?"PAUSADO":"RECHAZADO";d.report=message;d.setDirty();release();removeWorker();s.getPlayerList().broadcastSystemMessage(Component.literal("Constructor detenido: "+message),false);}
    private void removeWorker(){if(worker!=null){worker.discard();worker=null;}}
    public void release(){if(ticketLevel!=null)for(long key:tickets){var c=new net.minecraft.world.level.ChunkPos(key);ticketLevel.getChunkSource().removeRegionTicket(TICKET,c,0,key);}tickets.clear();ticketLevel=null;}
    public boolean staleWorker(net.minecraft.world.entity.Entity e){return e.getTags().contains("barttolo_road_worker")&&e!=worker;}
    public void shutdown(){release();removeWorker();}
}
