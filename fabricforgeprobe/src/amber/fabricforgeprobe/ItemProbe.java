package amber.fabricforgeprobe;

import com.google.common.collect.Multimap;
import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.google.common.collect.ImmutableMultimap;
import java.util.UUID;

/**
 * fabric-item-api-v1 on Forge (bridge: RecipeMixin / furnace / brewing remainder mixins vetoed, ItemStackMixin
 * attribute / suitable-tool redirects stripped, ItemFabricExtensionMixin answers IForgeItem from FabricItem). Three
 * Fabric items overriding getRecipeRemainder, getAttributeModifiers, isSuitableFor, one with a CustomDamageHandler
 * (ItemStackMixin's damage hooks, kept); vanilla items must stay as they were.
 */
final class ItemProbe {
    private ItemProbe() {}
    static Item remainder, attrs, suitable, damage; static int damageCalls, ok, total;
    static final UUID ID = UUID.fromString("6b1f3c2e-8a4d-4d7e-9a51-3e2b7c1d0f42");

    static final class RemainderItem extends Item implements FabricItem {
        RemainderItem() { super(new Item.Properties()); }
        @Override public ItemStack getRecipeRemainder(ItemStack stack) { return new ItemStack(Items.BOWL); }
    }
    static final class AttrItem extends Item implements FabricItem {
        AttrItem() { super(new Item.Properties()); }
        @Override public Multimap<Attribute, AttributeModifier> getAttributeModifiers(ItemStack stack, EquipmentSlot slot) {
            return slot == EquipmentSlot.MAINHAND ? ImmutableMultimap.of(Attributes.ATTACK_DAMAGE, new AttributeModifier(ID, "amber probe", 5, AttributeModifier.Operation.ADDITION)) : ImmutableMultimap.of();
        }
    }
    static final class SuitableItem extends Item implements FabricItem {
        SuitableItem() { super(new Item.Properties()); }
        @Override public boolean isSuitableFor(ItemStack stack, BlockState state) { return state.is(Blocks.OBSIDIAN); }
    }

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] item: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        remainder = Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("amber_ffprobe", "remainder"), new RemainderItem());
        attrs = Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("amber_ffprobe", "attrs"), new AttrItem());
        suitable = Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("amber_ffprobe", "suitable"), new SuitableItem());
        damage = Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("amber_ffprobe", "damage"),
            new Item(new FabricItemSettings().maxDamage(100).customDamage(ItemProbe::onDamage)));
    }

    static <T extends LivingEntity> int onDamage(ItemStack stack, int amount, T entity, Consumer<T> onBreak) { damageCalls++; return 0; }

    static double attack(ItemStack s) { return s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE).stream().mapToDouble(AttributeModifier::getAmount).sum(); }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                ItemStack r = new ItemStack(remainder);
                check("Fabric remainder: hasCraftingRemainingItem", true, r.hasCraftingRemainingItem());
                check("Fabric remainder: getCraftingRemainingItem", "bowl", BuiltInRegistries.ITEM.getKey(r.getCraftingRemainingItem().getItem()).getPath());
                check("vanilla water bucket remainder still bucket", "bucket", BuiltInRegistries.ITEM.getKey(new ItemStack(Items.WATER_BUCKET).getCraftingRemainingItem().getItem()).getPath());
                check("plain item has no remainder", false, new ItemStack(Items.STICK).hasCraftingRemainingItem());
                check("Fabric attribute modifiers (main hand attack +5)", 5.0, attack(new ItemStack(attrs)));
                check("vanilla diamond sword attack modifier kept", 6.0, attack(new ItemStack(Items.DIAMOND_SWORD)));
                check("Fabric isSuitableFor obsidian", true, new ItemStack(suitable).isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState()));
                check("Fabric isSuitableFor stone (no)", false, new ItemStack(suitable).isCorrectToolForDrops(Blocks.STONE.defaultBlockState()));
                check("vanilla diamond pickaxe for obsidian kept", true, new ItemStack(Items.DIAMOND_PICKAXE).isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState()));
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                GameType old = p.gameMode.getGameModeForPlayer();
                p.setGameMode(GameType.SURVIVAL);
                ItemStack d = new ItemStack(damage);
                d.hurtAndBreak(3, p, e -> {});
                check("CustomDamageHandler called", 1, damageCalls);
                check("CustomDamageHandler amount 0 kept the stack undamaged", 0, d.getDamageValue());
                p.setGameMode(old);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] item: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] item total: " + ok + "/" + total + " OK");
        });
    }
}
