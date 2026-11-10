package amber.worldtest;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * fabric-screen-handler-api-v1 probe (-Damber.screenprobe=true). The main entrypoint registers an
 * ExtendedScreenHandlerType (opening data = number + text, StreamCodec); client init registers its screen the way Fabric
 * mods do (MenuScreens' private register, opened by an access widener on Fabric; found here by signature). In the world
 * the server opens it with an ExtendedScreenHandlerFactory; the client must build the menu from the decoded data and show
 * the screen, with the same container id as the server.
 */
final class ScreenProbe {
    private ScreenProbe() {}
    record Data(int number, String text) {
        static final StreamCodec<ByteBuf, Data> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Data::number, ByteBufCodecs.STRING_UTF8, Data::text, Data::new);
    }
    static final class ProbeMenu extends AbstractContainerMenu {
        final Data data;
        ProbeMenu(int id, Inventory inv, Data data) {
            super(TYPE, id);
            this.data = data;
            System.out.println("[amber-worldtest] screen probe: menu built on " + (inv.player.level().isClientSide ? "client" : "server") + " id=" + id + " data=" + data);
        }
        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player p) { return true; }
    }
    static final class ProbeScreen extends AbstractContainerScreen<ProbeMenu> {
        ProbeScreen(ProbeMenu m, Inventory inv, Component title) { super(m, inv, title); System.out.println("[amber-worldtest] screen probe: client screen created title=" + title.getString()); }
        @Override protected void renderBg(GuiGraphics g, float pt, int mx, int my) {}
    }
    static ExtendedScreenHandlerType<ProbeMenu, Data> TYPE;
    static volatile int serverId = -1;

    static void registerType() {
        TYPE = Registry.register(BuiltInRegistries.MENU, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "probe_menu"), new ExtendedScreenHandlerType<>(ProbeMenu::new, Data.CODEC));
        System.out.println("[amber-worldtest] screen probe: menu type registered");
    }

    static int beforeTick, afterTick, beforeRender, afterRender, removed;

    static void registerScreen() {
        // fabric-screen-api-v1 per-screen events on the probe screen (tick hooks live in a Minecraft.tick lambda NeoForge renumbered)
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ProbeScreen)) return;
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeTick(screen).register(s -> beforeTick++);
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterTick(screen).register(s -> afterTick++);
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeRender(screen).register((s, g, x, y, d) -> beforeRender++);
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register((s, g, x, y, d) -> afterRender++);
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen).register(s -> removed++);
        });
        try {
            for (Method m : MenuScreens.class.getDeclaredMethods())
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2 && m.getParameterTypes()[0] == MenuType.class && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    Class<?> ctor = m.getParameterTypes()[1];   // MenuScreens.ScreenConstructor (private without the access widener)
                    Object factory = java.lang.reflect.Proxy.newProxyInstance(ctor.getClassLoader(), new Class<?>[]{ctor}, (proxy, method, args) -> {
                        if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                        if (method.getParameterCount() == 3) return new ProbeScreen((ProbeMenu) args[0], (Inventory) args[1], (Component) args[2]);
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        return "ScreenProbeFactory";
                    });
                    m.invoke(null, TYPE, factory);
                    System.out.println("[amber-worldtest] screen probe: screen registered");
                    return;
                }
            System.out.println("[amber-worldtest] screen probe: MenuScreens.register not found");
        } catch (Throwable e) { System.out.println("[amber-worldtest] screen probe: screen register failed " + e); }
    }

    static void open(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            var r = p.openMenu(new ExtendedScreenHandlerFactory<Data>() {
                @Override public Data getScreenOpeningData(ServerPlayer player) { return new Data(42, "amber-" + player.getScoreboardName()); }
                @Override public Component getDisplayName() { return Component.literal("Amber Probe"); }
                @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) { return new ProbeMenu(id, inv, new Data(42, "server-side")); }
            });
            serverId = r.orElse(-1);
            System.out.println("[amber-worldtest] screen probe: server openMenu -> " + r);
        });
    }

    static void report(Minecraft client) {
        String screen = client.screen == null ? "none" : client.screen.getClass().getSimpleName();
        String menu = client.player.containerMenu instanceof ProbeMenu m ? "ProbeMenu id=" + m.containerId + " data=" + m.data : client.player.containerMenu.getClass().getSimpleName();
        boolean ok = client.screen instanceof ProbeScreen && client.player.containerMenu instanceof ProbeMenu m2 && m2.containerId == serverId && m2.data.number() == 42 && m2.data.text().startsWith("amber-");
        if (client.screen instanceof ProbeScreen) client.player.closeContainer();
        boolean events = beforeTick > 0 && afterTick > 0 && beforeRender > 0 && afterRender > 0 && removed == 1;
        System.out.println("[amber-worldtest] screen probe total: " + (ok && events ? "OK" : "FAIL") + " screen=" + screen + " menu=" + menu + " serverId=" + serverId
                + " events tick=" + beforeTick + "/" + afterTick + " render=" + beforeRender + "/" + afterRender + " removed=" + removed);
    }
}
