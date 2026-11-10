package amber.fabricforgeprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * fabric-screen-api-v1 on Forge (bridge: ScreenRenderFabricMixin on ForgeHooksClient.drawScreenInternal,
 * ScreenKeyboardFabricMixin on Forge's keyPress lambda, manifest retarget of the screen-tick lambda). Opens a probe screen
 * in the world, lets it render and tick, sends F20 press/release through KeyboardHandler.keyPress, then F21 with
 * ALLOW_KEY_PRESS vetoing it, then closes it.
 */
final class ScreenProbe {
    private ScreenProbe() {}
    static int ok, total, init, beforeRender, afterRender, beforeTick, afterTick, removed;
    static final List<String> keys = new ArrayList<>();   // event:key in order
    static final List<Integer> screenSaw = new ArrayList<>();

    static final class ProbeScreen extends Screen {
        ProbeScreen() { super(Component.literal("amber ffprobe screen")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public boolean keyPressed(int key, int scancode, int mods) { screenSaw.add(key); return super.keyPressed(key, scancode, mods); }
    }

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] screen: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof ProbeScreen)) return;
            init++;
            ScreenEvents.beforeRender(screen).register((s, g, x, y, d) -> beforeRender++);
            ScreenEvents.afterRender(screen).register((s, g, x, y, d) -> afterRender++);
            ScreenEvents.beforeTick(screen).register(s -> beforeTick++);
            ScreenEvents.afterTick(screen).register(s -> afterTick++);
            ScreenEvents.remove(screen).register(s -> removed++);
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, k, sc, m) -> { keys.add("allow:" + k); return k != GLFW.GLFW_KEY_F21; });
            ScreenKeyboardEvents.beforeKeyPress(screen).register((s, k, sc, m) -> keys.add("before:" + k));
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, k, sc, m) -> keys.add("after:" + k));
            ScreenKeyboardEvents.allowKeyRelease(screen).register((s, k, sc, m) -> { keys.add("allowRel:" + k); return true; });
            ScreenKeyboardEvents.beforeKeyRelease(screen).register((s, k, sc, m) -> keys.add("beforeRel:" + k));
            ScreenKeyboardEvents.afterKeyRelease(screen).register((s, k, sc, m) -> keys.add("afterRel:" + k));
        });
    }

    static void open(Minecraft mc) { mc.setScreen(new ProbeScreen()); }

    static void keys(Minecraft mc) {
        long w = mc.getWindow().getWindow();
        mc.keyboardHandler.keyPress(w, GLFW.GLFW_KEY_F20, 0, GLFW.GLFW_PRESS, 0);
        mc.keyboardHandler.keyPress(w, GLFW.GLFW_KEY_F20, 0, GLFW.GLFW_RELEASE, 0);
        mc.keyboardHandler.keyPress(w, GLFW.GLFW_KEY_F21, 0, GLFW.GLFW_PRESS, 0);
    }

    static void close(Minecraft mc) { mc.setScreen(null); }

    static void report() {
        try {
            int f20 = GLFW.GLFW_KEY_F20, f21 = GLFW.GLFW_KEY_F21;
            check("AFTER_INIT for the probe screen", 1, init);
            check("BEFORE_RENDER fired", true, beforeRender > 0);
            check("AFTER_RENDER fired as often as BEFORE_RENDER", beforeRender, afterRender);
            check("BEFORE_TICK fired", true, beforeTick > 0);
            check("AFTER_TICK fired as often as BEFORE_TICK", beforeTick, afterTick);
            check("F20 press: allow, before, after in order", List.of("allow:" + f20, "before:" + f20, "after:" + f20), keys.stream().filter(s -> s.endsWith(":" + f20) && !s.contains("Rel")).toList());
            check("F20 release: allowRel, beforeRel, afterRel in order", List.of("allowRel:" + f20, "beforeRel:" + f20, "afterRel:" + f20), keys.stream().filter(s -> s.endsWith(":" + f20) && s.contains("Rel")).toList());
            check("F21 vetoed by ALLOW_KEY_PRESS: only allow fired", List.of("allow:" + f21), keys.stream().filter(s -> s.endsWith(":" + f21)).toList());
            check("screen.keyPressed saw F20 but not F21", List.of(f20), screenSaw);
            check("REMOVE on close", 1, removed);
        } catch (Throwable e) { System.out.println("[amber-ffprobe] screen: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-ffprobe] screen total: " + ok + "/" + total + " OK");
    }
}
