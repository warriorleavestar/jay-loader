package amber.ul.fmlforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.modlauncher.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.minecraftforge.coremod.CoreModProvider;
import net.minecraftforge.forgespi.coremod.ICoreModFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Forge 1.20.1's JS coremods (META-INF/coremods.json in the Forge universal jar and in any mod jar given) applied to the
 * Mojmap game jar AT BAKE TIME with the real engine (coremods 5.2.4 + Nashorn 15.4): field_to_method (Biome climate /
 * effects, Structure settings, MobEffectInstance, LiquidBlock, BucketItem, StairBlock, FlowerPotBlock, ItemStack -> getters),
 * method_redirector (finalizeSpawn -> ForgeEventFactory.onFinalizeSpawn in 22 classes), field_to_instanceof (empty data in
 * 47.4.28), add_bouncer_method (targets a 1.16 class, no-op).
 * Their targets are vanilla classes, so no runtime transformer is needed (as modlauncher: before Mixin, which is an AFTER
 * launch plugin). ASMAPI.mapField/mapMethod ask Launcher.INSTANCE.environment().findNameMapping("srg"): the isolated loader
 * redirects Launcher.findNameMapping to ForgeShim (Jpms.forgeLauncher) and we stub Launcher.INSTANCE, so SRG ids map to the
 * Mojmap names of our runtime (production Forge runs in SRG, where the same call is the identity).
 * Order per class as modlauncher 10's ClassTransformer: PRE_CLASS, FIELD, METHOD, CLASS; castVote first. Re-framed with
 * COMPUTE_FRAMES over a flat lookup (game jar + JDK).
 */
public final class ForgeCoreMods {
    private ForgeCoreMods() {}

    /** sources: jars that may carry META-INF/coremods.json (Forge universal first). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String apply(Path in, Path out, List<Path> sources, Path srgTable) throws Exception {
        ForgeShim.load(srgTable);
        ForgeFmlBridge.stubModLauncher();
        CoreModProvider prov = new CoreModProvider();
        List<FileSystem> open = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<ITransformer<?>> all;
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ForgeCoreMods.class.getClassLoader());   // NashornFactory: app class loader = TCCL
        try {
            for (Path src : sources) {
                FileSystem fs = FileSystems.newFileSystem(src);
                Path idx = fs.getPath("META-INF/coremods.json");
                if (!Files.exists(idx)) { fs.close(); continue; }
                open.add(fs);
                String owner = src.getFileName().toString();
                JsonObject o = JsonParser.parseString(Files.readString(idx)).getAsJsonObject();
                for (var e : o.entrySet()) {
                    Path script = fs.getPath(e.getValue().getAsString());
                    names.add(owner + ":" + e.getKey());
                    prov.addCoreMod(new ICoreModFile() {
                        public String getOwnerId() { return owner; }
                        public Reader readCoreMod() throws IOException { return Files.newBufferedReader(script, StandardCharsets.UTF_8); }
                        public Path getPath() { return script; }
                        public Reader getAdditionalFile(String f) throws IOException { return Files.newBufferedReader(fs.getPath(f), StandardCharsets.UTF_8); }
                    });
                }
            }
            all = prov.getCoreModTransformers();
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
            for (FileSystem fs : open) fs.close();
        }
        if (all.isEmpty()) return null;
        Map<String, List<ITransformer<?>>> byClass = new HashMap<>();
        for (ITransformer<?> t : all) for (ITransformer.Target tg : t.targets()) {
            List<ITransformer<?>> l = byClass.computeIfAbsent(tg.getClassName().replace('.', '/'), k -> new ArrayList<>());
            if (!l.contains(t)) l.add(t);
        }
        int changed = 0;
        List<String> missing = new ArrayList<>();
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jf = new JarFile(in.toFile())) {
            for (JarEntry e : Collections.list(jf.entries())) if (e.getName().endsWith(".class")) classes.put(e.getName().substring(0, e.getName().length() - 6), jf.getInputStream(e).readAllBytes());
            for (String c : byClass.keySet()) if (!classes.containsKey(c)) missing.add(c);
            Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
            try (JarOutputStream o = new JarOutputStream(Files.newOutputStream(tmp))) {
                for (JarEntry e : Collections.list(jf.entries())) {
                    if (e.isDirectory()) continue;
                    byte[] b = jf.getInputStream(e).readAllBytes();
                    String n = e.getName();
                    String internal = n.endsWith(".class") ? n.substring(0, n.length() - 6) : null;
                    List<ITransformer<?>> ts = internal == null ? null : byClass.get(internal);
                    if (ts != null) { byte[] nb = transform(internal, b, ts, classes); if (nb != b) { b = nb; changed++; } }
                    o.putNextEntry(amber.ul.bake.SafeFiles.entry(n)); o.write(b); o.closeEntry();
                }
            }
            Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return String.format("Forge JS coremods %s: %d transformers, %d target classes, %d classes changed%s", names, all.size(), byClass.size(), changed,
                missing.isEmpty() ? "" : ", targets not in the game jar: " + missing);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static byte[] transform(String internal, byte[] b, List<ITransformer<?>> ts, Map<String, byte[]> classes) {
        ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, 0);
        ITransformerVotingContext ctx = context(internal.replace('/', '.'));
        boolean touched = false;
        for (ITransformer.TargetType phase : List.of(ITransformer.TargetType.PRE_CLASS, ITransformer.TargetType.FIELD, ITransformer.TargetType.METHOD, ITransformer.TargetType.CLASS)) {
            for (ITransformer t : ts) {
                for (Object o : t.targets()) {
                    ITransformer.Target tg = (ITransformer.Target) o;
                    if (tg.getTargetType() != phase || !tg.getClassName().replace('.', '/').equals(internal)) continue;
                    if (t.castVote(ctx) != TransformerVoteResult.YES) continue;
                    if (phase == ITransformer.TargetType.CLASS || phase == ITransformer.TargetType.PRE_CLASS) { cn = (ClassNode) t.transform(cn, ctx); touched = true; }
                    else if (phase == ITransformer.TargetType.METHOD) {
                        for (ListIterator<MethodNode> it = cn.methods.listIterator(); it.hasNext(); ) {
                            MethodNode mn = it.next();
                            if (mn.name.equals(tg.getElementName()) && mn.desc.equals(tg.getElementDescriptor())) { it.set((MethodNode) t.transform(mn, ctx)); touched = true; }
                        }
                    } else {
                        for (ListIterator<FieldNode> it = cn.fields.listIterator(); it.hasNext(); ) {
                            FieldNode fn = it.next();
                            if (fn.name.equals(tg.getElementName())) { it.set((FieldNode) t.transform(fn, ctx)); touched = true; }
                        }
                    }
                }
            }
        }
        if (!touched) return b;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String c) { return commonSuper(a, c, classes); }
        };
        cn.accept(cw);
        byte[] nb = cw.toByteArray();
        return Arrays.equals(nb, b) ? b : nb;
    }

    static ITransformerVotingContext context(String className) {
        return (ITransformerVotingContext) java.lang.reflect.Proxy.newProxyInstance(ForgeCoreMods.class.getClassLoader(), new Class<?>[]{ITransformerVotingContext.class}, (p, m, a) -> switch (m.getName()) {
            case "getClassName" -> className;
            case "doesClassExist" -> true;
            case "getInitialClassSha256" -> new byte[0];
            case "getAuditActivities" -> List.of();
            case "getReason" -> "classloading";
            case "applyFieldPredicate", "applyMethodPredicate", "applyClassPredicate", "applyInstructionPredicate" -> true;
            case "hashCode" -> 0; case "equals" -> p == a[0]; case "toString" -> "AmberVotingContext[" + className + "]";
            default -> null;
        });
    }

    static String commonSuper(String a, String b, Map<String, byte[]> classes) {
        if (isInterface(b, classes) || isInterface(a, classes)) return "java/lang/Object";
        List<String> chainA = new ArrayList<>();
        for (String c = a; c != null; c = superOf(c, classes)) chainA.add(c);
        for (String c = b; c != null; c = superOf(c, classes)) if (chainA.contains(c)) return c;
        return "java/lang/Object";
    }
    static String superOf(String n, Map<String, byte[]> classes) {
        byte[] b = bytes(n, classes);
        return b == null ? (n.equals("java/lang/Object") ? null : "java/lang/Object") : new ClassReader(b).getSuperName();
    }
    static boolean isInterface(String n, Map<String, byte[]> classes) {
        byte[] b = bytes(n, classes);
        return b != null && (new ClassReader(b).getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }
    static byte[] bytes(String n, Map<String, byte[]> classes) {
        byte[] b = classes.get(n);
        if (b != null) return b;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(n + ".class")) { return in == null ? null : in.readAllBytes(); } catch (IOException e) { return null; }
    }
}
