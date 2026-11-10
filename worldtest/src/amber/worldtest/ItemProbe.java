package amber.worldtest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.item.v1.EnchantingContext;
import net.fabricmc.fabric.api.item.v1.EnchantmentEvents;
import net.fabricmc.fabric.api.item.v1.EnchantmentSource;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.fabricmc.fabric.api.item.v1.FabricItemStack;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

/**
 * fabric-item-api-v1 probe (-Damber.itemprobe=true). Items using every FabricItem hook, checked through the game's own
 * call sites: recipe remainder (crafting Recipe.getRemainingItems, furnace fuel), canBeEnchantedWith (anvil, /enchant,
 * enchant_randomly, enchanting table), EnchantmentEvents.ALLOW_ENCHANTING (vanilla items), EnchantmentEvents.MODIFY
 * (minecraft:sharpness gets prevent_equipment_drop, source VANILLA), CustomDamageHandler (hurtAndBreak on a living
 * entity: modified amount, break callback), EquipmentSlotProvider, FabricItemStack, allowContinuingBlockBreaking
 * (MultiPlayerGameMode's same-target check, found by type: the worldtest jar is remapped, names would not be).
 */
final class ItemProbe {
    private ItemProbe() {}
    static Item REMAINDER, ENCHANT, DAMAGE, BREAK, SLOT, CONTINUE;
    static int damageCalls, ok, total; static EquipmentSlot damageSlot; static EnchantmentSource modifySource;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] item probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static final class RemainderItem extends Item implements FabricItem {
        RemainderItem(Properties p) { super(p); }
        @Override public ItemStack getRecipeRemainder(ItemStack stack) { return new ItemStack(Items.BOWL); }
    }
    static final class EnchantItem extends Item implements FabricItem {
        EnchantItem(Properties p) { super(p); }
        @Override public boolean canBeEnchantedWith(ItemStack stack, Holder<Enchantment> e, EnchantingContext ctx) { return e.is(Enchantments.SHARPNESS); }
        @Override public int getEnchantmentValue() { return 10; }
    }
    static final class ContinueItem extends Item implements FabricItem {
        ContinueItem(Properties p) { super(p); }
        @Override public boolean allowContinuingBlockBreaking(Player player, ItemStack old, ItemStack now) { return true; }
    }

    static Item reg(String id, Item item) { return Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("amber-worldtest", id), item); }

    /** Main entrypoint: items, a fuel, and both enchantment events (MODIFY must be in before the datapack loads). */
    static void register() {
        REMAINDER = reg("probe_remainder", new RemainderItem(new Item.Properties()));
        ENCHANT = reg("probe_enchant", new EnchantItem(new Item.Properties().durability(50)));
        DAMAGE = reg("probe_damage", new Item(((FabricItem.Settings) new Item.Properties().durability(100)).customDamage((stack, amount, entity, slot, onBroken) -> {
            damageCalls++; damageSlot = slot; return amount + 2; })));
        BREAK = reg("probe_break", new Item(((FabricItem.Settings) new Item.Properties().durability(100)).customDamage((stack, amount, entity, slot, onBroken) -> { onBroken.run(); return 0; })));
        SLOT = reg("probe_slot", new Item(((FabricItem.Settings) new Item.Properties()).equipmentSlot((entity, stack) -> EquipmentSlot.HEAD)));
        CONTINUE = reg("probe_continue", new ContinueItem(new Item.Properties().durability(100)));
        FuelRegistry.INSTANCE.add(REMAINDER, 200);
        EnchantmentEvents.ALLOW_ENCHANTING.register((ench, stack, ctx) -> {
            if (stack.is(Items.STICK) && ench.is(Enchantments.KNOCKBACK)) return TriState.TRUE;
            if (stack.is(Items.DIAMOND_SWORD) && ench.is(Enchantments.SHARPNESS)) return TriState.FALSE;
            return TriState.DEFAULT;
        });
        EnchantmentEvents.MODIFY.register((key, builder, source) -> {
            if (!key.equals(Enchantments.SHARPNESS)) return;
            modifySource = source; builder.withEffect(EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
        });
    }

    static boolean can(ItemStack s, Holder<Enchantment> e, EnchantingContext c) { return ((FabricItemStack) (Object) s).canBeEnchantedWith(e, c); }
    static int level(Holder<Enchantment> e, ItemStack s) { return EnchantmentHelper.getItemEnchantmentLevel(e, s); }

    static void run(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        server.execute(() -> {
            try { server(server); } catch (Throwable e) { System.out.println("[amber-worldtest] item probe: crashed " + e); e.printStackTrace(System.out); }
            client.execute(() -> {
                try { client(client); } catch (Throwable e) { System.out.println("[amber-worldtest] item probe: crashed " + e); e.printStackTrace(System.out); }
                System.out.println("[amber-worldtest] item probe total: " + ok + "/" + total + " OK");
            });
        });
    }

    static void server(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        var enchReg = server.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> sharp = enchReg.getHolderOrThrow(Enchantments.SHARPNESS), knock = enchReg.getHolderOrThrow(Enchantments.KNOCKBACK), unbreak = enchReg.getHolderOrThrow(Enchantments.UNBREAKING);

        // recipe remainder
        ItemStack rem = new ItemStack(REMAINDER);
        check("FabricItemStack.getRecipeRemainder", "minecraft:bowl", BuiltInRegistries.ITEM.getKey(((FabricItemStack) (Object) rem).getRecipeRemainder().getItem()));
        CraftingRecipe table = (CraftingRecipe) server.getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("crafting_table")).orElseThrow().value();
        var left = table.getRemainingItems(CraftingInput.of(1, 1, List.of(rem.copy())));
        check("crafting remainder (Recipe.getRemainingItems)", "minecraft:bowl", BuiltInRegistries.ITEM.getKey(left.get(0).getItem()));
        check("vanilla remainder kept (milk bucket)", "minecraft:bucket", BuiltInRegistries.ITEM.getKey(table.getRemainingItems(CraftingInput.of(1, 1, List.of(new ItemStack(Items.MILK_BUCKET)))).get(0).getItem()));
        check("plain item no remainder", true, table.getRemainingItems(CraftingInput.of(1, 1, List.of(new ItemStack(Items.STONE)))).get(0).isEmpty());
        BlockPos fp = server.getPlayerList().getPlayers().get(0).blockPosition().offset(3, 0, 3);
        level.setBlockAndUpdate(fp, Blocks.FURNACE.defaultBlockState());
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(fp);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON)); furnace.setItem(1, rem.copy());
        AbstractFurnaceBlockEntity.serverTick(level, fp, level.getBlockState(fp), furnace);
        check("furnace fuel remainder", "minecraft:bowl", BuiltInRegistries.ITEM.getKey(furnace.getItem(1).getItem()));
        level.removeBlock(fp, false);

        // canBeEnchantedWith
        ItemStack en = new ItemStack(ENCHANT);
        check("canBeEnchantedWith sharpness (item says yes)", true, can(en, sharp, EnchantingContext.ACCEPTABLE));
        check("canBeEnchantedWith knockback (item says no)", false, can(en, knock, EnchantingContext.ACCEPTABLE));
        check("canBeEnchantedWith sharpness PRIMARY", true, can(en, sharp, EnchantingContext.PRIMARY));
        
        List<EnchantmentInstance> table2 = EnchantmentHelper.getAvailableEnchantmentResults(30, en,
                java.util.stream.StreamSupport.stream(enchReg.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE).spliterator(), false));
        check("enchanting table offers only sharpness", "[minecraft:sharpness]", table2.stream().map(i -> i.enchantment.unwrapKey().orElseThrow().location().toString()).distinct().toList());
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        boolean instabuild = player.getAbilities().instabuild; player.getAbilities().instabuild = false;   // creative anvils accept any book
        AnvilMenu anvil = new AnvilMenu(0, player.getInventory(), ContainerLevelAccess.NULL);
        anvil.getSlot(0).set(en.copy()); anvil.getSlot(1).set(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(sharp, 1))); anvil.createResult();
        check("anvil applies sharpness book", 1, level(sharp, anvil.getSlot(2).getItem()));
        anvil.getSlot(1).set(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(knock, 1))); anvil.createResult();
        check("anvil refuses knockback book", true, anvil.getSlot(2).getItem().isEmpty() || level(knock, anvil.getSlot(2).getItem()) == 0);
        anvil.getSlot(0).set(ItemStack.EMPTY); anvil.getSlot(1).set(ItemStack.EMPTY);
        player.getAbilities().instabuild = instabuild;
        LootContext lc = new LootContext.Builder(new LootParams.Builder(level).create(LootContextParamSets.EMPTY)).create(Optional.empty());
        ItemStack rolled = EnchantRandomlyFunction.randomApplicableEnchantment(server.registryAccess()).build().apply(en.copy(), lc);
        check("enchant_randomly picks sharpness", 1, rolled.getEnchantments().size() == 1 ? level(sharp, rolled) > 0 ? 1 : 0 : -rolled.getEnchantments().size());
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND).copy();
        var src = player.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        player.setItemInHand(InteractionHand.MAIN_HAND, en.copy());
        server.getCommands().performPrefixedCommand(src, "enchant @s minecraft:sharpness 2");
        check("/enchant sharpness", 2, level(sharp, player.getMainHandItem()));
        server.getCommands().performPrefixedCommand(src, "enchant @s minecraft:knockback 1");
        check("/enchant knockback refused", 0, level(knock, player.getMainHandItem()));

        // ALLOW_ENCHANTING on vanilla items
        ItemStack stick = new ItemStack(Items.STICK), sword = new ItemStack(Items.DIAMOND_SWORD);
        check("event: stick accepts knockback", true, can(stick, knock, EnchantingContext.ACCEPTABLE));
        check("event: stick primary for knockback", true, can(stick, knock, EnchantingContext.PRIMARY));
        check("event: sword refuses sharpness", false, can(sword, sharp, EnchantingContext.ACCEPTABLE));
        check("event: sword unbreaking untouched", true, can(sword, unbreak, EnchantingContext.ACCEPTABLE));
        check("event: stick sharpness untouched", false, can(stick, sharp, EnchantingContext.ACCEPTABLE));
        player.getAbilities().instabuild = false;
        anvil.getSlot(0).set(sword.copy()); anvil.getSlot(1).set(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(sharp, 1))); anvil.createResult();
        check("event: anvil refuses sharpness on sword", true, anvil.getSlot(2).getItem().isEmpty() || level(sharp, anvil.getSlot(2).getItem()) == 0);
        anvil.getSlot(0).set(ItemStack.EMPTY); anvil.getSlot(1).set(ItemStack.EMPTY);
        player.getAbilities().instabuild = instabuild;
        player.setItemInHand(InteractionHand.MAIN_HAND, stick.copy());
        server.getCommands().performPrefixedCommand(src, "enchant @s minecraft:knockback 1");
        check("event: /enchant knockback on stick", 1, level(knock, player.getMainHandItem()));
        player.setItemInHand(InteractionHand.MAIN_HAND, held);

        // MODIFY
        check("MODIFY: sharpness has prevent_equipment_drop", true, sharp.value().effects().has(EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP));
        check("MODIFY: source", "VANILLA", modifySource);
        check("MODIFY: knockback untouched", false, knock.value().effects().has(EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP));

        // CustomDamageHandler
        ArmorStand stand = new ArmorStand(level, player.getX(), player.getY(), player.getZ());
        ItemStack dmg = new ItemStack(DAMAGE);
        dmg.hurtAndBreak(3, stand, EquipmentSlot.MAINHAND);
        check("damage handler called", 1, damageCalls);
        check("damage handler slot", "MAINHAND", damageSlot);
        check("damage handler amount used (3+2)", 5, dmg.getDamageValue());
        ItemStack plain = new ItemStack(Items.IRON_PICKAXE); plain.hurtAndBreak(3, stand, EquipmentSlot.MAINHAND);
        check("plain item damage", 3, plain.getDamageValue());
        ItemStack brk = new ItemStack(BREAK); brk.hurtAndBreak(1, stand, EquipmentSlot.MAINHAND);
        check("damage handler break callback", true, brk.isEmpty());

        // EquipmentSlotProvider, creator namespace
        check("equipment slot provider", "HEAD", stand.getEquipmentSlotForItem(new ItemStack(SLOT)));
        check("creator namespace", "amber-worldtest", ((FabricItemStack) (Object) new ItemStack(SLOT)).getCreatorNamespace());
    }

    static void client(Minecraft client) throws Exception {
        var gm = client.gameMode; var p = client.player;
        Field posF = null, itemF = null; Method same = null;
        for (Field f : gm.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == BlockPos.class) posF = f; else if (f.getType() == ItemStack.class) itemF = f;
        }
        for (Method m : gm.getClass().getDeclaredMethods())
            if (Modifier.isPrivate(m.getModifiers()) && m.getReturnType() == boolean.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class) same = m;
        posF.setAccessible(true); itemF.setAccessible(true); same.setAccessible(true);
        Object oldPos = posF.get(gm), oldItem = itemF.get(gm); ItemStack held = p.getMainHandItem().copy();
        BlockPos at = p.blockPosition().below();
        try {
            ItemStack a = new ItemStack(CONTINUE), b = a.copy(); b.setDamageValue(7);
            posF.set(gm, at); itemF.set(gm, a); p.setItemInHand(InteractionHand.MAIN_HAND, b);
            check("allowContinuingBlockBreaking keeps target", true, same.invoke(gm, at));
            ItemStack c = new ItemStack(Items.IRON_PICKAXE), d = c.copy(); d.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("x"));   // NeoForge ignores damage-only changes
            itemF.set(gm, c); p.setItemInHand(InteractionHand.MAIN_HAND, d);
            check("plain pickaxe renamed resets", false, same.invoke(gm, at));
            itemF.set(gm, c.copy()); p.setItemInHand(InteractionHand.MAIN_HAND, c.copy());
            check("same pickaxe keeps target", true, same.invoke(gm, at));
        } finally { posF.set(gm, oldPos); itemF.set(gm, oldItem); p.setItemInHand(InteractionHand.MAIN_HAND, held); }
    }
}
