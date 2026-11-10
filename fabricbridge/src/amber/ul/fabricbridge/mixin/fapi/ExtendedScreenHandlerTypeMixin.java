package amber.ul.fabricbridge.mixin.fapi;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * fabric-screen-handler-api-v1 re-base, client half: NeoForge's AdvancedOpenScreenPayload handler calls
 * MenuType.create(id, inv, buf), which for a non-IContainerFactory type falls back to create(id, inv) — Fabric throws
 * there. This override decodes the opening data with the type's packet codec and calls Fabric's factory.
 */
@Mixin(value = ExtendedScreenHandlerType.class, remap = false)
public abstract class ExtendedScreenHandlerTypeMixin<T extends AbstractContainerMenu, D> {
    @Shadow public abstract T create(int syncId, Inventory inventory, D data);
    @Shadow public abstract StreamCodec<? super RegistryFriendlyByteBuf, D> getPacketCodec();

    @SuppressWarnings("unchecked")
    public T create(int syncId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        return create(syncId, inventory, ((StreamCodec<RegistryFriendlyByteBuf, D>) getPacketCodec()).decode(buf));
    }
}
