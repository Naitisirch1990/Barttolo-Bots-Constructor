package net.barttolo.bots;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;
import java.util.ArrayList;

/** Optional registry-only compatibility with Lootr 0.7.35.94; no inventory reads or item drops. */
public final class ConstructorLootr {
    private static final Logger LOG=LogUtils.getLogger();
    private static final String[] FALLBACKS={"village_plains_house","village_savanna_house","village_taiga_house","village_desert_house","village_snowy_house","village_toolsmith","village_weaponsmith","village_armorer"};
    private ConstructorLootr() {}
    public static boolean available(){return BuiltInRegistries.BLOCK.containsKey(new ResourceLocation("lootr","lootr_chest"));}
    private static boolean inside(ConstructorData d,BlockPos p){return d.contains(p.getX(),p.getZ());}
    /** One chunk each tick, all heights; persist cursor only after the whole chunk has been handled. */
    public static void tick(ServerLevel level,ConstructorData d){
        if(d.chestsDone||d.auditOnly||d.chestsPaused)return;
        if(!available()){d.chestsDone=true;d.setDirty();return;}
        int minX=Math.floorDiv(d.minX(),16),minZ=Math.floorDiv(d.minZ(),16);
        int nx=Math.floorDiv(d.minX()+d.spanX()-1,16)-minX+1;
        int nz=Math.floorDiv(d.minZ()+d.spanZ()-1,16)-minZ+1;
        if(d.chestCursor>=nx*nz){d.chestsDone=true;d.setDirty();return;}
        int cx=minX+d.chestCursor%nx,cz=minZ+d.chestCursor/nx;
        var chunk=level.getChunk(cx,cz);
        for(BlockPos p:new ArrayList<>(chunk.getBlockEntitiesPos())){
            if(!inside(d,p))continue;
            BlockState original=level.getBlockState(p);
            if(!(original.is(Blocks.CHEST)||original.is(Blocks.TRAPPED_CHEST)))continue;
            d.chestsFound++;
            if(original.getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
                BlockPos other=p.relative(ChestBlock.getConnectedDirection(original));
                if(!inside(d,other)){d.chestsBoundary++;continue;}
            }
            if(convert(level,p,original))d.chestsConverted++;else d.chestsFailed++;
        }
        d.chestCursor++;d.chestsDone=d.chestCursor>=nx*nz;d.setDirty();
    }
    private static boolean convert(ServerLevel level,BlockPos pos,BlockState original){
        BlockEntity old=level.getBlockEntity(pos);if(old==null)return false;
        CompoundTag backup=old.saveWithoutMetadata();
        ResourceLocation table=ResourceLocation.tryParse(backup.getString("LootTable"));
        if(table==null||level.getServer().getLootData().getLootTable(table)==LootTable.EMPTY){
            String name=FALLBACKS[Math.floorMod(pos.asLong(),FALLBACKS.length)];
            table=new ResourceLocation("minecraft","chests/village/"+name);
            if(level.getServer().getLootData().getLootTable(table)==LootTable.EMPTY)return false;
        }
        ResourceLocation id=new ResourceLocation("lootr",original.is(Blocks.TRAPPED_CHEST)?"lootr_trapped_chest":"lootr_chest");
        if(!BuiltInRegistries.BLOCK.containsKey(id))return false;
        Block target=BuiltInRegistries.BLOCK.get(id);if(!(target instanceof EntityBlock factory))return false;
        BlockState replacement=target.defaultBlockState().setValue(ChestBlock.FACING,original.getValue(ChestBlock.FACING))
            .setValue(ChestBlock.WATERLOGGED,original.getValue(ChestBlock.WATERLOGGED)).setValue(ChestBlock.TYPE,ChestType.SINGLE);
        BlockEntity next=factory.newBlockEntity(pos,replacement);
        if(!(next instanceof RandomizableContainerBlockEntity lootChest))return false;
        next.setLevel(level);lootChest.setLootTable(table,-1L);
        // Close live menus before detaching the original inventory. Never call destroyBlock or dropResources.
        for(var player:level.players())if(player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<64&&player.containerMenu!=player.inventoryMenu)player.closeContainer();
        try{
            level.removeBlockEntity(pos);
            if(!level.setBlock(pos,replacement,3))throw new IllegalStateException("setBlock rejected conversion");
            level.setBlockEntity(next);next.setChanged();level.sendBlockUpdated(pos,original,replacement,3);
            return true;
        }catch(RuntimeException error){
            level.removeBlockEntity(pos);level.setBlock(pos,original,3);old.load(backup);level.setBlockEntity(old);old.setChanged();
            LOG.error("Constructor: no se pudo convertir el cofre {} en {}",pos,level.dimension().location(),error);return false;
        }
    }
}
