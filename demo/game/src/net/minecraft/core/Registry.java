package net.minecraft.core;
public final class Registry {
    private static final java.util.Map<String, String> ITEMS = new java.util.LinkedHashMap<>();
    private static boolean frozen;
    public static void register(String id, String v) { if (frozen) throw new IllegalStateException("registry frozen"); ITEMS.put(id, v); }
    public static void freeze() { frozen = true; }
    public static void dump() { ITEMS.forEach((k, v) -> System.out.println("  " + k + " = " + v)); }
}
