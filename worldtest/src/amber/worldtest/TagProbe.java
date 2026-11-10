package amber.worldtest;

import java.util.Objects;
import net.fabricmc.fabric.api.tag.v1.FabricTagFile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * fabric-tag-api-v1 probe (-Damber.tagprobe=true): {@code "fabric:remove"} in tag JSON. The probe's own tag removes one
 * of its own values; the probe's minecraft:decorated_pot_sherds removes a vanilla entry (another pack's); untouched
 * entries stay; a TagFile is a FabricTagFile.
 */
final class TagProbe {
    private TagProbe() {}
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] tag probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static boolean in(Item item, TagKey<Item> tag) { return BuiltInRegistries.ITEM.wrapAsHolder(item).is(tag); }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                TagKey<Item> own = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "probe_removal"));
                TagKey<Item> sherds = TagKey.create(Registries.ITEM, ResourceLocation.withDefaultNamespace("decorated_pot_sherds"));
                check("own tag keeps stone", true, in(Items.STONE, own));
                check("own tag keeps gravel", true, in(Items.GRAVEL, own));
                check("own tag: fabric:remove dropped dirt", false, in(Items.DIRT, own));
                check("vanilla tag keeps archer sherd", true, in(Items.ARCHER_POTTERY_SHERD, sherds));
                check("vanilla tag: fabric:remove dropped angler sherd", false, in(Items.ANGLER_POTTERY_SHERD, sherds));
                Object file = net.minecraft.tags.TagFile.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                        com.google.gson.JsonParser.parseString("{\"values\":[\"minecraft:stone\"],\"fabric:remove\":[\"minecraft:dirt\"]}")).getOrThrow();
                check("TagFile is a FabricTagFile", true, file instanceof FabricTagFile);
                check("parsed removal entries", 1, file instanceof FabricTagFile f ? f.remove().size() : -1);
            } catch (Throwable e) { System.out.println("[amber-worldtest] tag probe: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-worldtest] tag probe total: " + ok + "/" + total + " OK");
        });
    }
}
