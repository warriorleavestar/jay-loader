package amber.worldtest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.recipe.v1.ingredient.DefaultCustomIngredients;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * fabric-recipe-api-v1 probe (-Damber.recipeprobe=true). Fabric custom ingredients built in code (toVanilla), the
 * ingredient JSON codec and network stream codec round trip, and two data-pack recipes in this mod written with Fabric's
 * "fabric:type" key (fabric:any, fabric:difference), crafted through the server's RecipeManager. Also the
 * FabricIngredient view (getCustomIngredient/requiresTesting) that Fabric mods call on any Ingredient.
 */
final class RecipeProbe {
    private RecipeProbe() {}
    static int ok, total;
    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] recipe probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }
    static Object fabricCustom(Ingredient i) {
        // through the interface, as Fabric mods compile it (invokeinterface FabricIngredient.getCustomIngredient); reflection by
        // name would be ambiguous on NeoForge, whose Ingredient also has getCustomIngredient() returning ICustomIngredient
        try { Object c = ((net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient) (Object) i).getCustomIngredient(); return c == null ? null : c.getClass().getSimpleName(); }
        catch (Throwable e) { return "err " + e; }
    }

    static void run(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            try { runOnServer(server); }
            catch (Throwable e) { System.out.println("[amber-worldtest] recipe probe total: crashed " + e); e.printStackTrace(System.out); return; }
            System.out.println("[amber-worldtest] recipe probe total: " + ok + "/" + total + " OK");
        });
    }

    static void runOnServer(MinecraftServer server) {
        Ingredient any = DefaultCustomIngredients.any(Ingredient.of(Items.DIRT), Ingredient.of(Items.SAND));
        Ingredient diff = DefaultCustomIngredients.difference(Ingredient.of(ItemTags.PLANKS), Ingredient.of(Items.OAK_PLANKS));
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD); named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Amber"));
        Ingredient comp = DefaultCustomIngredients.components(named);   // matches swords carrying that name (the stack's component patch)
        check("any test sand", true, any.test(new ItemStack(Items.SAND)));
        check("any test stone", false, any.test(new ItemStack(Items.STONE)));
        check("difference test birch_planks", true, diff.test(new ItemStack(Items.BIRCH_PLANKS)));
        check("difference test oak_planks", false, diff.test(new ItemStack(Items.OAK_PLANKS)));
        check("any getItems count", 2, any.getItems().length);
        ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
        check("components test named sword", true, comp.test(named.copy()));
        check("components test plain sword", false, comp.test(plain));
        check("FabricIngredient getCustomIngredient on any", "AnyIngredient", fabricCustom(any));
        check("FabricIngredient getCustomIngredient on vanilla", null, fabricCustom(Ingredient.of(Items.STONE)));
        // JSON codec round trip (what datagen writes / data packs read) and the network codec (recipe sync)
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        JsonElement json = Ingredient.CODEC.encodeStart(ops, diff).getOrThrow();
        System.out.println("[amber-worldtest] recipe probe: difference as JSON " + json);
        Ingredient back = Ingredient.CODEC.parse(ops, json).getOrThrow();
        check("JSON round trip difference test birch/oak", "true/false", back.test(new ItemStack(Items.BIRCH_PLANKS)) + "/" + back.test(new ItemStack(Items.OAK_PLANKS)));
        Ingredient fromFabricJson = Ingredient.CODEC.parse(ops, com.google.gson.JsonParser.parseString(
                "{\"fabric:type\":\"fabric:all\",\"ingredients\":[{\"tag\":\"minecraft:logs\"},{\"item\":\"minecraft:oak_log\"}]}")).getOrThrow();
        check("parse fabric:type all logs+oak_log: oak/birch", "true/false", fromFabricJson.test(new ItemStack(Items.OAK_LOG)) + "/" + fromFabricJson.test(new ItemStack(Items.BIRCH_LOG)));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        Ingredient.CONTENTS_STREAM_CODEC.encode(buf, comp);
        Ingredient net = Ingredient.CONTENTS_STREAM_CODEC.decode(buf);
        check("network round trip components named/plain", "true/false", net.test(named.copy()) + "/" + net.test(plain));
        // data-pack recipes with "fabric:type"
        var rm = server.getRecipeManager();
        check("recipe fabric_any_probe loaded", true, rm.byKey(ResourceLocation.fromNamespaceAndPath("amber-worldtest", "fabric_any_probe")).isPresent());
        check("recipe fabric_difference_probe loaded", true, rm.byKey(ResourceLocation.fromNamespaceAndPath("amber-worldtest", "fabric_difference_probe")).isPresent());
        check("craft sand+stick", "diamond", craft(server, new ItemStack(Items.SAND), new ItemStack(Items.STICK)));
        check("craft stone+stick (no match)", "none", craft(server, new ItemStack(Items.STONE), new ItemStack(Items.STICK)));
        check("craft birch_planks+flint", "emerald", craft(server, new ItemStack(Items.BIRCH_PLANKS), new ItemStack(Items.FLINT)));
        check("craft oak_planks+flint (subtracted)", "none", craft(server, new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.FLINT)));
    }

    static String craft(MinecraftServer server, ItemStack a, ItemStack b) {
        CraftingInput in = CraftingInput.of(2, 1, List.of(a, b));
        return server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, server.overworld())
                .map(r -> BuiltInRegistries.ITEM.getKey(r.value().assemble(in, server.registryAccess()).getItem()).getPath()).orElse("none");
    }
}
