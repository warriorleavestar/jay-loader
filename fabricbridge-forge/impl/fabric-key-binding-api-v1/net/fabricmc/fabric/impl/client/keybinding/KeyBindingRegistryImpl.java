/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.client.keybinding;

import java.util.*;
import net.fabricmc.fabric.mixin.client.keybinding.KeyBindingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;

/**
 * Amber's re-based fabric-key-binding-api-v1 impl (Forge 47 platform). Same binary surface as the official class.
 * Fabric client init runs in Forge's registration window, after Options exist, so the official check ("GameOptions has
 * already been initialised") would fail every key binding. Keys are collected and handed to Forge when it fires
 * RegisterKeyMappingsEvent (ForgeHooksClient.onRegisterKeyMappings from initClientHooks; the bridge's
 * ForgeKeyMappingsFabricMixin calls flush). ClientModLoader later runs options.load(true), which restores saved keys.
 * The official GameOptionsMixin (Options.load()V HEAD) is vetoed: one producer only.
 */
public final class KeyBindingRegistryImpl {
    private static final List<KeyMapping> MODDED_KEY_BINDINGS = new ArrayList<>();
    private static boolean flushed;

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
        if (flushed) System.out.printf("[amber-ul] 问题 fabric_api_late_keybinding (warning): 按键 %s 在 RegisterKeyMappingsEvent 之后注册, Forge 已经收集完按键, 它不会出现在控制设置里%n", binding.getName());
        return binding;
    }

    /** Called by the bridge when Forge fires RegisterKeyMappingsEvent. */
    public static synchronized void flush(RegisterKeyMappingsEvent e) {
        for (KeyMapping k : MODDED_KEY_BINDINGS) e.register(k);
        flushed = true;
        System.out.printf("[amber-ul] 桥接: RegisterKeyMappingsEvent: %d 个 Fabric 按键%n", MODDED_KEY_BINDINGS.size());
    }

    /** Kept for binary compatibility (the official GameOptionsMixin called it; that mixin is vetoed on Forge). */
    public static KeyMapping[] process(KeyMapping[] keysAll) {
        List<KeyMapping> newKeysAll = new ArrayList<>(Arrays.asList(keysAll));
        newKeysAll.removeAll(MODDED_KEY_BINDINGS);
        newKeysAll.addAll(MODDED_KEY_BINDINGS);
        return newKeysAll.toArray(new KeyMapping[0]);
    }
}
