package amber.worldtest;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;

/**
 * fabric-biome-api-v1 probe (-Damber.biomeprobe=true): at init, BiomeModifications.addSpawn(plains, CREATURE, goat,
 * weight 77, 1-1); in the world, read the integrated server's plains biome and report whether that spawn entry is there.
 */
final class BiomeProbe {
    static final int WEIGHT = 77;
    static void register() {
        BiomeModifications.addSpawn(BiomeSelectors.includeByKey(Biomes.PLAINS), MobCategory.CREATURE, EntityType.GOAT, WEIGHT, 1, 1);
        System.out.println("[amber-worldtest] biome probe: addSpawn(plains, CREATURE, goat, " + WEIGHT + ") registered");
    }
    static void report(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) { System.out.println("[amber-worldtest] biome probe: no integrated server"); return; }
        Biome plains = server.registryAccess().registryOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        boolean found = false, sheep55 = false;
        StringBuilder all = new StringBuilder();
        for (MobSpawnSettings.SpawnerData d : plains.getMobSettings().getMobs(MobCategory.CREATURE).unwrap()) {
            if (d.type == EntityType.GOAT && d.getWeight().asInt() == WEIGHT) found = true;
            if (d.type == EntityType.SHEEP && d.getWeight().asInt() == 55) sheep55 = true;   // NeoForge biome_modifier from amber-nfworldtest
            all.append(' ').append(EntityType.getKey(d.type).getPath()).append('@').append(d.getWeight().asInt());
        }
        System.out.println("[amber-worldtest] biome probe: plains CREATURE =" + all);
        System.out.println("[amber-worldtest] biome probe: neoforge add_spawns sheep@55=" + sheep55);
        System.out.println("[amber-worldtest] biome probe: plains CREATURE has goat@" + WEIGHT + "=" + found + " -> " + (found ? "OK" : "MISSING"));
    }
}
