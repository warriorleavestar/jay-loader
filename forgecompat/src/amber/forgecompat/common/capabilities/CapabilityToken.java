package amber.forgecompat.common.capabilities;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** Forge's CapabilityToken: an anonymous subclass ({@code new CapabilityToken<IFoo>(){}}) carries the capability's type. */
public abstract class CapabilityToken<T> {
    protected CapabilityToken() {}
    protected final String getType() {
        Type t = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        if (t instanceof ParameterizedType p) t = p.getRawType();
        return t instanceof Class<?> c ? c.getName() : t.getTypeName().replaceAll("<.*", "");
    }
    @Override public String toString() { return "CapabilityToken[" + getType() + "]"; }
}
