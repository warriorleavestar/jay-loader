/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.client.keybinding;

import amber.ul.fabricbridge.NeoBus;
import java.util.*;
import net.fabricmc.fabric.mixin.client.keybinding.KeyBindingAccessor;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Amber's re-based fabric-key-binding-api-v1 impl (NeoForge platform). Same binary surface as the official class.
 * On NeoForge, Fabric client init runs after Options exist, so key bindings are collected and handed to NeoForge in
 * RegisterKeyMappingsEvent (NeoForge's finishModLoading then runs options.load(true), which restores saved keys).
 * The official GameOptionsMixin (Options.load()V HEAD) is vetoed: one producer only.
 */
public final class KeyBindingRegistryImpl {
    private static final List<KeyMapping> MODDED_KEY_BINDINGS = new ArrayList<>();
    private static boolean flushed;

    static {
        NeoBus.mod().addListener(RegisterKeyMappingsEvent.class, e -> {
            synchronized (KeyBindingRegistryImpl.class) {
                for (KeyMapping k : MODDED_KEY_BINDINGS) e.register(k);
                flushed = true;
                NeoBus.log("RegisterKeyMappingsEvent: %d 个 Fabric 按键", MODDED_KEY_BINDINGS.size());
            }
        });
    }

    private KeyBindingRegistryImpl() {}

    private static Map<String, Integer> getCategoryMap() { return KeyBindingAccessor.fabric_getCategoryMap(); }

    public static boolean addCategory(String categoryTranslationKey) {
        Map<String, Integer> map = getCategoryMap();
        if (map.containsKey(categoryTranslationKey)) return false;
        Optional<Integer> largest = map.values().stream().max(Integer::compareTo);
        map.put(categoryTranslationKey, largest.orElse(0) + 1);
        return true;
    }

    public static synchronized KeyMapping registerKeyBinding(KeyMapping binding) {
        for (KeyMapping existing : MODDED_KEY_BINDINGS) {
            if (existing == binding) throw new IllegalArgumentException("Attempted to register a key binding twice: " + binding.getName());
            if (existing.getName().equals(binding.getName())) throw new IllegalArgumentException("Attempted to register two key bindings with equal ID: " + binding.getName() + "!");
        }
        addCategory(binding.getCategory());
        MODDED_KEY_BINDINGS.add(binding);
        if (flushed) NeoBus.problem("fabric_api_late_keybinding", "warning", "按键 %s 在 RegisterKeyMappingsEvent 之后注册, NeoForge 已经收集完按键, 它不会出现在控制设置里", binding.getName());
        return binding;
    }

    /** Kept for binary compatibility (the official GameOptionsMixin called it; that mixin is vetoed on NeoForge). */
    public static KeyMapping[] process(KeyMapping[] keysAll) {
        List<KeyMapping> newKeysAll = new ArrayList<>(Arrays.asList(keysAll));
        newKeysAll.removeAll(MODDED_KEY_BINDINGS);
        newKeysAll.addAll(MODDED_KEY_BINDINGS);
        return newKeysAll.toArray(new KeyMapping[0]);
    }
}
