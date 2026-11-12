package amber.ul;

import amber.ul.bake.AccessRules;
import amber.ul.bake.Baker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.ClassReader;

/**
 * Plain-main tests (no game files needed; CI "Tests that need no Minecraft downloads") for the v1.3.3 quick play hooks
 * and request helpers:
 * - the hook tables for 1.20.1 / 1.21.1 carry quick_play_singleplayer / quick_play_multiplayer;
 * - Baker.patchClass injects the call at the head of a present target method, and a hook whose target class is
 *   present but whose method is missing fails the game bake (IllegalStateException "found no injection point"),
 *   instead of being skipped silently;
 * - QuickPlay.encode (the 1.12.2 property) is pure ASCII.
 *   java -cp "build/loader;build/test;$LIBS" amber.ul.HookTest
 */
public final class HookTest {
    static int pass, fail;
    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + name); }

    public static void main(String[] a) throws Exception {
        Method patch = Baker.class.getDeclaredMethod("patchClass", byte[].class, String.class, AccessRules.class, List.class);
        patch.setAccessible(true);
        for (String mc : new String[] { "1.20.1", "1.21.1" }) {
            List<Baker.Hook> hooks = hooks(mc);
            List<Baker.Hook> qp = new ArrayList<>();
            for (Baker.Hook h : hooks) if (h.id().startsWith("quick_play_")) qp.add(h);
            check(mc + ": hook table has quick_play_singleplayer and quick_play_multiplayer on QuickPlay.join*World", qp.size() == 2
                    && qp.stream().allMatch(h -> h.owner().equals("net/minecraft/client/quickplay/QuickPlay") && h.call().equals("amber/ul/runtime/GameHooks.quickPlay") && h.at().equals("head")));
            String owner = "net/minecraft/client/quickplay/QuickPlay";
            // present: both methods exist -> the call is at the head of each
            byte[] full = cls(owner, true);
            byte[] out = (byte[]) patch.invoke(null, full, owner, new AccessRules(), hooks);
            ClassNode cn = new ClassNode(); new ClassReader(out).accept(cn, 0);
            boolean both = cn.methods.stream().filter(m -> m.name.startsWith("join")).allMatch(m -> {
                var first = m.instructions.getFirst();
                while (first != null && first.getOpcode() < 0) first = first.getNext();
                return first instanceof MethodInsnNode mi && mi.owner.equals("amber/ul/runtime/GameHooks") && mi.name.equals("quickPlay");
            });
            check(mc + ": QuickPlay.joinSingleplayerWorld / joinMultiplayerWorld get GameHooks.quickPlay() at the head", both);
            // target class present, method missing -> the bake fails loudly
            try {
                patch.invoke(null, cls(owner, false), owner, new AccessRules(), hooks);
                check(mc + ": a missing hook method fails the game bake", false);
            } catch (InvocationTargetException e) {
                check(mc + ": a missing hook method fails the game bake (" + e.getCause().getMessage() + ")", e.getCause() instanceof IllegalStateException && e.getCause().getMessage().contains("found no injection point"));
            }
        }
        String enc = QuickPlay.encode("café 🙂%+-._~");
        check("quick play: percent-encoding is ASCII and keeps unreserved characters (" + enc + ")", enc.equals("caf%C3%A9%20%F0%9F%99%82%25%2B-._~"));
        check("quick play: an unpaired surrogate is refused as a world name", QuickPlay.unpairedSurrogate("a\ud83d") && QuickPlay.unpairedSurrogate("\ude42") && !QuickPlay.unpairedSurrogate("🙂"));
        System.out.printf("RESULT hooks %s: %d passed, %d failed%n", fail == 0 ? "PASS" : "FAIL", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static List<Baker.Hook> hooks(String mc) throws Exception {
        JsonObject o;
        try (var in = new InputStreamReader(HookTest.class.getResourceAsStream("/amber-ul/hooks/" + mc + ".json"), StandardCharsets.UTF_8)) { o = JsonParser.parseReader(in).getAsJsonObject(); }
        List<Baker.Hook> out = new ArrayList<>();
        for (var e : o.getAsJsonArray("hooks")) {
            JsonObject h = e.getAsJsonObject();
            out.add(new Baker.Hook(h.get("id").getAsString(), h.get("owner").getAsString(), h.get("method").getAsString(), h.get("desc").getAsString(), h.get("at").getAsString(),
                    h.has("target") ? h.get("target").getAsString() : null, h.has("ldc") ? h.get("ldc").getAsString() : null, h.has("passThis") && h.get("passThis").getAsBoolean(), h.get("call").getAsString()));
        }
        return out;
    }

    /** A stand-in QuickPlay class: with both join methods, or with neither (a game jar where they were renamed). */
    static byte[] cls(String owner, boolean withMethods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        String[] names = withMethods ? new String[] { "joinSingleplayerWorld", "joinMultiplayerWorld" } : new String[] { "somethingElse" };
        for (String n : names) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, n, "(Lnet/minecraft/client/Minecraft;Ljava/lang/String;)V", null, null);
            mv.visitCode(); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 2); mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
