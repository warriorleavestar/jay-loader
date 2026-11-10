package amber.ul.fabricbridge.fapi.screen;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * fabric-screen-handler-api-v1 on NeoForge, server half. NeoForge's ServerPlayer.openMenu asks the provider to write its
 * client-side data (IMenuProviderExtension.writeClientSideData) and, when anything was written, sends its own
 * AdvancedOpenScreenPayload instead of the vanilla packet; the client then calls MenuType.create(id, inv, buf).
 * An ExtendedScreenHandlerFactory passed to openMenu is wrapped in this provider (ServerPlayerOpenMenuMixin): the opening
 * data goes into that buffer with the menu type's own packet codec, as Fabric's OpenScreenPayload would carry it, and
 * Fabric's shouldCloseCurrentScreen() becomes NeoForge's closing choice (closeContainer vs doCloseContainer, the same
 * branch Fabric's vetoed ServerPlayerEntityMixin added).
 */
public final class ExtendedMenuProvider implements MenuProvider {
    public final ExtendedScreenHandlerFactory<?> f;
    private final ServerPlayer player;
    public ExtendedMenuProvider(ExtendedScreenHandlerFactory<?> f, ServerPlayer player) { this.f = f; this.player = player; }

    @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return f.createMenu(id, inv, p); }
    @Override public Component getDisplayName() { return f.getDisplayName(); }
    @Override public boolean shouldTriggerClientSideContainerClosingOnOpen() { return ((net.fabricmc.fabric.api.screenhandler.v1.FabricScreenHandlerFactory) (Object) f).shouldCloseCurrentScreen() && f.shouldTriggerClientSideContainerClosingOnOpen(); }

    @Override @SuppressWarnings({"unchecked", "rawtypes"})
    public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buf) {
        f.writeClientSideData(menu, buf);
        if (!(menu.getType() instanceof ExtendedScreenHandlerType<?, ?> t))
            throw new IllegalArgumentException("ExtendedScreenHandlerFactory opened a menu whose type is not an ExtendedScreenHandlerType: " + menu.getType());
        ((StreamCodec) t.getPacketCodec()).encode(buf, f.getScreenOpeningData(player));
    }
}
