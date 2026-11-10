package amber.ul.fabricbridge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/** Where bridge code registers NeoForge listeners: game events on NeoForge.EVENT_BUS, mod-bus events on the neoforge container's bus. */
public final class NeoBus {
    private NeoBus() {}
    public static IEventBus mod() {
        IEventBus b = ModList.get().getModContainerById("neoforge").orElseThrow().getEventBus();
        if (b == null) throw new IllegalStateException("neoforge mod bus missing");
        return b;
    }
    public static void log(String fmt, Object... args) { System.out.println("[amber-fabric-bridge] " + String.format(fmt, args)); }
    public static void problem(String code, String sev, String fmt, Object... args) { System.out.println("[amber-ul] 问题 " + code + " (" + sev + ") " + String.format(fmt, args)); }
}
