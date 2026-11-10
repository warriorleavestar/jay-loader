package amber.fabricforgeprobe;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Arrays;
import java.util.Objects;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * fabric-key-binding-api-v1 on Forge (bridge: re-based KeyBindingRegistryImpl + ForgeKeyMappingsFabricMixin). A key
 * registered in Fabric client init must reach Options.keyMappings through Forge's RegisterKeyMappingsEvent, and its
 * category must be known to KeyMapping's category order.
 */
final class KeyProbe {
    private KeyProbe() {}
    static KeyMapping key; static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] keys: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.amber_ffprobe.test", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.amber_ffprobe"));
    }

    static void run(Minecraft mc) {
        try {
            check("Fabric key in Options.keyMappings", true, Arrays.asList(mc.options.keyMappings).contains(key));
            check("vanilla keys still there", true, Arrays.asList(mc.options.keyMappings).contains(mc.options.keyUse));
            KeyMapping other = Arrays.stream(mc.options.keyMappings).filter(k -> k != key && k.getCategory().equals("key.categories.misc")).findFirst().orElse(null);
            check("Fabric category ordered (compareTo works)", true, other == null || key.compareTo(other) != 0);
            check("bound key", "key.keyboard.f10", key.saveString());
        } catch (Throwable e) { System.out.println("[amber-ffprobe] keys: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-ffprobe] keys total: " + ok + "/" + total + " OK");
    }
}
