package amber.ul.resolve;

import amber.ul.model.*;
import java.util.*;

/**
 * Cross-loader resolver. A Forge mod may depend on a Fabric mod and vice versa: ids live in one namespace.
 * Platform ids (minecraft, fabricloader, neoforge, java ...) are provided by the Amber runtime itself.
 * Problems carry the stable codes of docs/amber-bake-interface.md so Amber can map them onto its own UI.
 */
public final class Resolver {
    /** code: missing_dep | version_mismatch | incompatible_loader | incompatible_mc | conflict | duplicate_mod | nested_unresolved | unsupported_feature */
    public record Problem(String code, String severity, String modId, String file, String depId, String range, String found, String message) {
        @Override public String toString() { return message; }
    }
    public record Result(List<ModInfo> ordered, List<Problem> problems) {}

    public static Result resolve(List<ModInfo> candidates, Map<String, String> builtins) {
        List<Problem> problems = new ArrayList<>();
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModInfo m : candidates) {
            ModInfo prev = byId.get(m.id);
            // same mod shipped for two loaders: keep the higher version
            if (prev == null || Versions.compare(m.version, prev.version) > 0) byId.put(m.id, m);
        }
        Map<String, String> provided = new HashMap<>(builtins);
        for (ModInfo m : byId.values()) { provided.put(m.id, m.version); for (String p : m.provides) provided.putIfAbsent(p, m.version); }
        for (ModInfo m : byId.values()) for (Dependency d : m.deps) {
            String have = provided.get(d.modId());
            String file = String.valueOf((m.originalJar != null ? m.originalJar : m.jar).getFileName());
            if (have == null) {
                if (d.required()) problems.add(new Problem("missing_dep", "error", m.id, file, d.modId(), d.range(), null,
                        m.id + " 需要 " + d.modId() + " " + d.range() + "，但没有安装"));
            } else if (!Versions.matches(have, d.range())) {
                String code = d.modId().equals("minecraft") ? "incompatible_mc"
                        : Set.of("fabricloader", "neoforge", "forge", "quilt_loader", "java").contains(d.modId()) ? "incompatible_loader" : "version_mismatch";
                problems.add(new Problem(code, d.required() ? "error" : "warning", m.id, file, d.modId(), d.range(), have,
                        m.id + " 需要 " + d.modId() + " " + d.range() + "，实际是 " + have));
            }
        }
        // topological order so a mod initialises after what it depends on, regardless of loader
        // edges as FML sorts: AFTER = after the dependency, BEFORE = the dependency after this mod, NONE = no edge
        Map<String, List<String>> after = new HashMap<>();
        for (ModInfo m : byId.values()) for (Dependency d : m.deps) {
            if (!byId.containsKey(d.modId())) continue;
            if (d.ordering().equals("AFTER")) after.computeIfAbsent(m.id, k -> new ArrayList<>()).add(d.modId());
            else if (d.ordering().equals("BEFORE")) after.computeIfAbsent(d.modId(), k -> new ArrayList<>()).add(m.id);
        }
        List<ModInfo> order = new ArrayList<>(); Set<String> seen = new HashSet<>(); LinkedHashSet<String> stack = new LinkedHashSet<>();
        for (ModInfo m : byId.values()) visit(m, byId, after, seen, stack, order, problems);
        return new Result(order, problems);
    }

    public static boolean blocking(List<Problem> ps) { return ps.stream().anyMatch(p -> p.severity().equals("error")); }

    private static void visit(ModInfo m, Map<String, ModInfo> byId, Map<String, List<String>> after, Set<String> seen, LinkedHashSet<String> stack, List<ModInfo> out, List<Problem> problems) {
        if (seen.contains(m.id)) return;
        if (stack.contains(m.id)) {
            List<String> cyc = new ArrayList<>(stack); cyc = new ArrayList<>(cyc.subList(cyc.indexOf(m.id), cyc.size())); cyc.add(m.id);
            problems.add(new Problem("conflict", "error", m.id, String.valueOf(m.jar.getFileName()), null, null, null, "循环依赖: " + String.join(" -> ", cyc)));
            return;
        }
        stack.add(m.id);
        for (String id : after.getOrDefault(m.id, List.of())) visit(byId.get(id), byId, after, seen, stack, out, problems);
        stack.remove(m.id); seen.add(m.id); out.add(m);
    }
}
