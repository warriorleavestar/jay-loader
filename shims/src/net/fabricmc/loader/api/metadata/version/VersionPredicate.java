/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api.metadata.version;

import java.util.*;
import java.util.function.Predicate;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/** Fabric's version predicate ("&gt;=1.2 &lt;2", "1.21.x", "*"), evaluated with Amber's SemVer matcher. Terms/intervals: not provided (yet). */
public interface VersionPredicate extends Predicate<Version> {
    default Collection<?> getTerms() { throw new UnsupportedOperationException("VersionPredicate.getTerms (Amber prototype)"); }
    default Object getInterval() { throw new UnsupportedOperationException("VersionPredicate.getInterval (Amber prototype)"); }

    static VersionPredicate parse(String predicate) throws VersionParsingException {
        String p = predicate.trim();
        return new VersionPredicate() {
            public boolean test(Version v) { return amber.ul.resolve.Versions.matches(v.getFriendlyString(), p); }
            public String toString() { return p; }
        };
    }
    static Collection<VersionPredicate> parse(Collection<String> predicates) throws VersionParsingException {
        List<VersionPredicate> l = new ArrayList<>();
        for (String s : predicates) l.add(parse(s));
        return l;
    }
}
