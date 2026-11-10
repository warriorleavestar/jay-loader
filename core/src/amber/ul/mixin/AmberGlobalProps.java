package amber.ul.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.service.*;

/** Mixin's global "blackboard". */
public final class AmberGlobalProps implements IGlobalPropertyService {
    record Key(String name) implements IPropertyKey {}
    private static final Map<String, Object> P = new ConcurrentHashMap<>();
    @Override public IPropertyKey resolveKey(String name) { return new Key(name); }
    @SuppressWarnings("unchecked") @Override public <T> T getProperty(IPropertyKey k) { return (T) P.get(((Key) k).name()); }
    @Override public void setProperty(IPropertyKey k, Object v) { if (v == null) P.remove(((Key) k).name()); else P.put(((Key) k).name(), v); }
    @SuppressWarnings("unchecked") @Override public <T> T getProperty(IPropertyKey k, T def) { return (T) P.getOrDefault(((Key) k).name(), def); }
    @Override public String getPropertyString(IPropertyKey k, String def) { Object v = P.get(((Key) k).name()); return v == null ? def : v.toString(); }
}
