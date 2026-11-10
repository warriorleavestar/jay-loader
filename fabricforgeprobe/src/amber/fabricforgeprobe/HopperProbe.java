package amber.fabricforgeprobe;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * fabric-transfer-api-v1 hoppers on Forge: a block with no block entity that is only a Fabric ItemStorage (through
 * ItemStorage.SIDED) sits above a hopper and another one beside it; the hopper pulls from the top one and pushes into the
 * side one (bridge: HopperTransferFabricMixin on Forge's suckInItems / ejectItems).
 */
final class HopperProbe {
    private HopperProbe() {}
    static Block storageBlock;
    static final Map<BlockPos, SimpleContainer> CONTENTS = new HashMap<>();
    static BlockPos top, hopper, side;
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] hopper: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        storageBlock = Registry.register(BuiltInRegistries.BLOCK, new ResourceLocation("amber_ffprobe", "storage"), new Block(BlockBehaviour.Properties.copy(Blocks.STONE)));
        ItemStorage.SIDED.registerForBlocks((level, pos, state, be, dir) -> {
            SimpleContainer c = CONTENTS.get(pos);
            return c == null ? null : InventoryStorage.of(c, dir);
        }, storageBlock);
    }

    /** tick 100: build top storage / hopper facing east / side storage, two items in the top one. */
    static void run(MinecraftServer server, BlockPos near) {
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                hopper = near.offset(-3, 3, -3); top = hopper.above(); side = hopper.east();
                CONTENTS.put(top, new SimpleContainer(3)); CONTENTS.put(side, new SimpleContainer(3));
                CONTENTS.get(top).setItem(0, new ItemStack(Items.DIRT, 2));
                level.setBlockAndUpdate(top, storageBlock.defaultBlockState());
                level.setBlockAndUpdate(side, storageBlock.defaultBlockState());
                level.setBlockAndUpdate(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
                check("storage block is a Fabric ItemStorage", true, ItemStorage.SIDED.find(level, top, Direction.DOWN) != null);
                check("storage block is not a vanilla container", true, net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, top) == null);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] hopper: crashed " + e); e.printStackTrace(System.out); }
        });
    }

    static volatile boolean reported;

    /** ticks 180..245: report once both items arrived (the server can lag behind the client's tick count), at 245 anyway. */
    static void report(MinecraftServer server, boolean last) {
        if (reported) return;
        server.execute(() -> {
            if (reported || (!last && CONTENTS.get(side).countItem(Items.DIRT) < 2)) return;
            reported = true;
            check("hopper pulled both items from the Fabric storage above", 0, CONTENTS.get(top).countItem(Items.DIRT));
            check("hopper pushed them into the Fabric storage beside it", 2, CONTENTS.get(side).countItem(Items.DIRT));
            ServerLevel level = server.overworld();
            for (BlockPos p : new BlockPos[] { top, hopper, side }) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            System.out.println("[amber-ffprobe] hopper total: " + ok + "/" + total + " OK");
        });
    }
}
