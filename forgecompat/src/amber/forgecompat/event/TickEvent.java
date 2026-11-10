package amber.forgecompat.event;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Forge's TickEvent (one event with a START/END phase). NeoForge split it into Pre/Post classes per kind; Amber posts
 * this one on NeoForge's game bus from those, so Forge mods' @SubscribeEvent TickEvent.XTickEvent handlers run.
 */
public class TickEvent extends Event {
    public enum Type { LEVEL, PLAYER, CLIENT, SERVER, RENDER }
    public enum Phase { START, END }
    public final Type type; public final LogicalSide side; public final Phase phase;
    public TickEvent(Type type, LogicalSide side, Phase phase) { this.type = type; this.side = side; this.phase = phase; }

    public static class ServerTickEvent extends TickEvent {
        private final BooleanSupplier haveTime; private final MinecraftServer server;
        public ServerTickEvent(BooleanSupplier haveTime, MinecraftServer server, Phase phase) { super(Type.SERVER, LogicalSide.SERVER, phase); this.haveTime = haveTime; this.server = server; }
        public boolean haveTime() { return haveTime.getAsBoolean(); }
        public MinecraftServer getServer() { return server; }
        public static class Pre extends ServerTickEvent { public Pre(BooleanSupplier t, MinecraftServer s) { super(t, s, Phase.START); } }
        public static class Post extends ServerTickEvent { public Post(BooleanSupplier t, MinecraftServer s) { super(t, s, Phase.END); } }
    }
    public static class PlayerTickEvent extends TickEvent {
        public final Player player;
        public PlayerTickEvent(Phase phase, Player player) { super(Type.PLAYER, player.level().isClientSide ? LogicalSide.CLIENT : LogicalSide.SERVER, phase); this.player = player; }
        public Player getEntity() { return player; }
        public static class Pre extends PlayerTickEvent { public Pre(Player p) { super(Phase.START, p); } }
        public static class Post extends PlayerTickEvent { public Post(Player p) { super(Phase.END, p); } }
    }
    public static class LevelTickEvent extends TickEvent {
        public final Level level; private final BooleanSupplier haveTime;
        public LevelTickEvent(LogicalSide side, Phase phase, Level level, BooleanSupplier haveTime) { super(Type.LEVEL, side, phase); this.level = level; this.haveTime = haveTime; }
        public boolean haveTime() { return haveTime.getAsBoolean(); }
        public static class Pre extends LevelTickEvent { public Pre(LogicalSide s, Level l, BooleanSupplier t) { super(s, Phase.START, l, t); } }
        public static class Post extends LevelTickEvent { public Post(LogicalSide s, Level l, BooleanSupplier t) { super(s, Phase.END, l, t); } }
    }
    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase) { super(Type.CLIENT, LogicalSide.CLIENT, phase); }
        public static class Pre extends ClientTickEvent { public Pre() { super(Phase.START); } }
        public static class Post extends ClientTickEvent { public Post() { super(Phase.END); } }
    }

    static LogicalSide side(Level l) { return l.isClientSide ? LogicalSide.CLIENT : LogicalSide.SERVER; }

    /** NeoForge tick events -> Forge TickEvents on the same (game) bus. */
    public static void install(IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e) -> bus.post(new ServerTickEvent.Pre(e::hasTime, e.getServer())));
        bus.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> bus.post(new ServerTickEvent.Post(e::hasTime, e.getServer())));
        bus.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre e) -> bus.post(new PlayerTickEvent.Pre(e.getEntity())));
        bus.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) -> bus.post(new PlayerTickEvent.Post(e.getEntity())));
        bus.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Pre e) -> bus.post(new LevelTickEvent.Pre(side(e.getLevel()), e.getLevel(), e::hasTime)));
        bus.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Post e) -> bus.post(new LevelTickEvent.Post(side(e.getLevel()), e.getLevel(), e::hasTime)));
        if (FMLEnvironment.dist.isClient()) Client.install(bus);
    }
    static final class Client {
        static void install(IEventBus bus) {
            bus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Pre e) -> bus.post(new ClientTickEvent.Pre()));
            bus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> bus.post(new ClientTickEvent.Post()));
        }
    }
}
