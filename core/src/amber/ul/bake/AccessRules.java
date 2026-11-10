package amber.ul.bake;

import java.util.*;
import org.objectweb.asm.Opcodes;

/** One model for Fabric access wideners and Forge/NeoForge access transformers. */
public final class AccessRules {
    public enum Kind { CLASS, FIELD, METHOD }
    public record Rule(Kind kind, String owner, String name, String desc, boolean makePublic, boolean stripFinal, String source) {}
    public final List<Rule> rules = new ArrayList<>();

    /** accessWidener v2 &lt;namespace&gt; ... ; caller must already have remapped it into the runtime namespace. */
    public void parseAccessWidener(String text, String source) {
        for (String raw : text.split("\n")) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty() || line.startsWith("accessWidener")) continue;
            String[] p = line.split("\\s+");
            String access = p[0].replace("transitive-", "");
            boolean pub = access.equals("accessible") || access.equals("extendable");
            boolean fin = access.equals("mutable") || access.equals("extendable");
            switch (p[1]) {
                case "class" -> rules.add(new Rule(Kind.CLASS, p[2], null, null, pub, fin, source));
                case "field" -> rules.add(new Rule(Kind.FIELD, p[2], p[3], p[4], pub, fin, source));
                case "method" -> rules.add(new Rule(Kind.METHOD, p[2], p[3], p[4], pub, fin, source));
            }
            // as access-widener 2.x AccessWidener.applyAccess: an accessible member makes its owner class accessible
            // (makeClassAccessible), an extendable member makes it extendable (makeClassExtendable); mutable doesn't touch it
            if (!p[1].equals("class") && access.equals("accessible")) rules.add(new Rule(Kind.CLASS, p[2], null, null, true, false, source));
            if (!p[1].equals("class") && access.equals("extendable")) rules.add(new Rule(Kind.CLASS, p[2], null, null, true, true, source));
        }
    }

    /** public|protected|default|private[-f|+f] a.b.C [member[(desc)]] */
    public void parseAccessTransformer(String text, String source) {
        for (String raw : text.split("\n")) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            boolean pub = p[0].startsWith("public") || p[0].startsWith("protected");
            boolean fin = p[0].endsWith("-f");
            String owner = p[1].replace('.', '/');
            if (p.length == 2) rules.add(new Rule(Kind.CLASS, owner, null, null, pub, fin, source));
            else if (p[2].contains("(")) { int i = p[2].indexOf('('); rules.add(new Rule(Kind.METHOD, owner, p[2].substring(0, i), p[2].substring(i), pub, fin, source)); }
            else rules.add(new Rule(Kind.FIELD, owner, p[2], null, pub, fin, source));
        }
    }

    public Set<String> owners() { Set<String> s = new HashSet<>(); rules.forEach(r -> s.add(r.owner())); return s; }

    public static int widen(int access, boolean pub, boolean stripFinal) {
        if (pub) access = (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        if (stripFinal) access &= ~Opcodes.ACC_FINAL;
        return access;
    }

    public String fingerprint() { return Hash.str(rules.toString()); }
}
