package amber.fabricforgeprobe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;

/**
 * fabric-registry-sync-v0 on Forge 47 (integrated server): a block and item registered the Fabric way (vanilla
 * Registry.register at Fabric init) are in Forge's registries with the same numeric ids as the vanilla view; a Fabric
 * custom static registry (FabricRegistryBuilder, SYNCED) exists and keeps its entries and attributes; Fabric dynamic
 * (data-pack) registries, plain and synced, load their JSON entries on the server, and the synced one reaches the client.
 * Forge syncs ids of its own registries in the FML handshake; Fabric's own registry sync is skipped for the singleplayer
 * host, so the remote-client case is a dedicated-server real test.
 */
final class RegSyncProbe {
    private RegSyncProbe() {}
    record Dyn(int value) {
        static final Codec<Dyn> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.INT.fieldOf("value").forGetter(Dyn::value)).apply(i, Dyn::new));
    }
    static final ResourceLocation BLOCK_ID = new ResourceLocation("amber_ffprobe", "synced");
    static final ResourceKey<Registry<String>> CUSTOM = ResourceKey.createRegistryKey(new ResourceLocation("amber_ffprobe", "custom"));
    static final ResourceKey<Registry<Dyn>> DYN = ResourceKey.createRegistryKey(new ResourceLocation("amber_ffprobe", "dyn"));
    static final ResourceKey<Registry<Dyn>> DYN_SYNCED = ResourceKey.createRegistryKey(new ResourceLocation("amber_ffprobe", "dyn_synced"));
    static Block block;
    static Item item;
    static MappedRegistry<String> custom;
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] regsync: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        block = Registry.register(BuiltInRegistries.BLOCK, BLOCK_ID, new Block(BlockBehaviour.Properties.copy(Blocks.STONE)));
        item = Registry.register(BuiltInRegistries.ITEM, BLOCK_ID, new BlockItem(block, new Item.Properties()));
        custom = FabricRegistryBuilder.createSimple(CUSTOM).attribute(RegistryAttribute.SYNCED).buildAndRegister();
        Registry.register(custom, new ResourceLocation("amber_ffprobe", "first"), "first");
        Registry.register(custom, new ResourceLocation("amber_ffprobe", "second"), "second");
        DynamicRegistries.register(DYN, Dyn.CODEC);
        DynamicRegistries.registerSynced(DYN_SYNCED, Dyn.CODEC);
    }

    @SuppressWarnings("unchecked")
    static void run(Minecraft mc, MinecraftServer server) {
        try {
            check("Forge BLOCKS has the Fabric block", true, ForgeRegistries.BLOCKS.getValue(BLOCK_ID) == block);
            check("Forge ITEMS has the Fabric item", true, ForgeRegistries.ITEMS.getValue(BLOCK_ID) == item);
            ForgeRegistry<Block> fb = RegistryManager.ACTIVE.getRegistry(Registries.BLOCK);
            ForgeRegistry<Item> fi = RegistryManager.ACTIVE.getRegistry(Registries.ITEM);
            check("block id: Forge == vanilla", BuiltInRegistries.BLOCK.getId(block), fb.getID(block));
            check("item id: Forge == vanilla", BuiltInRegistries.ITEM.getId(item), fi.getID(item));
            check("custom registry in the root registry", true, BuiltInRegistries.REGISTRY.get(CUSTOM.location()) == custom);
            check("custom registry entries", "first,second", custom.get(new ResourceLocation("amber_ffprobe", "first")) + "," + custom.get(new ResourceLocation("amber_ffprobe", "second")));
            check("custom registry raw ids", "0,1", custom.getId("first") + "," + custom.getId("second"));
            check("custom registry attributes SYNCED+MODDED", "true,true", RegistryAttributeHolder.get(CUSTOM).hasAttribute(RegistryAttribute.SYNCED) + "," + RegistryAttributeHolder.get(CUSTOM).hasAttribute(RegistryAttribute.MODDED));
            check("custom registry frozen with the rest", true, custom.isEmpty() == false && !tryRegister());
            var dyn = server.registryAccess().registry(DYN);
            check("server has the Fabric dynamic registries", "true,true", dyn.isPresent() + "," + server.registryAccess().registry(DYN_SYNCED).isPresent());
            check("server loaded Fabric dynamic registry entry", 7, dyn.map(r -> r.get(new ResourceLocation("amber_ffprobe", "hello"))).map(Dyn::value).orElse(-1));
            var dynS = server.registryAccess().registry(DYN_SYNCED);
            check("server loaded synced dynamic registry entry", 9, dynS.map(r -> r.get(new ResourceLocation("amber_ffprobe", "hello"))).map(Dyn::value).orElse(-1));
            var cl = mc.getConnection().registryAccess().registry(DYN_SYNCED);
            check("client has the synced dynamic registry entry", 9, cl.map(r -> r.get(new ResourceLocation("amber_ffprobe", "hello"))).map(Dyn::value).orElse(-1));
            check("client has no unsynced dynamic registry", false, mc.getConnection().registryAccess().registry(DYN).isPresent());
        } catch (Throwable e) { System.out.println("[amber-ffprobe] regsync: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-ffprobe] regsync total: " + ok + "/" + total + " OK");
    }

    /** Registering after the freeze must be refused (as on Fabric). */
    static boolean tryRegister() {
        try { Registry.register(custom, new ResourceLocation("amber_ffprobe", "late"), "late"); return true; } catch (Throwable e) { return false; }
    }
}
