/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.client.particle;

import java.util.IdentityHashMap;
import java.util.Map;

import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;

/**
 * Amber re-base of fabric-particles-v1's registry for Forge 47 (copy of the NeoForge one; Fabric API 0.92 has the same class). The official DirectParticleFactoryRegistry puts
 * into ParticleEngine's vanilla {@code Int2ObjectMap factories} (by raw id) through ParticleManagerAccessor.getFactories;
 * Forge 47 keys {@code providers} by ResourceLocation and has no such field, so that accessor is stripped by the bridge
 * manifest. Direct registration goes through Forge's public ParticleEngine.register overloads instead: a plain
 * provider, and a pending factory as a SpriteParticleRegistration (Forge makes and keys the MutableSpriteSet the same
 * way Fabric did, so FabricSpriteProviderImpl wraps the same set the atlas reload fills). Deferred registration and
 * initialize() are unchanged.
 */
public final class ParticleFactoryRegistryImpl implements ParticleFactoryRegistry {
    public static final ParticleFactoryRegistryImpl INSTANCE = new ParticleFactoryRegistryImpl();

    private ParticleFactoryRegistry internalRegistry = new DeferredParticleFactoryRegistry();

    private ParticleFactoryRegistryImpl() {}

    @Override
    public <T extends ParticleOptions> void register(ParticleType<T> type, ParticleProvider<T> factory) {
        internalRegistry.register(type, factory);
    }

    @Override
    public <T extends ParticleOptions> void register(ParticleType<T> type, PendingParticleFactory<T> constructor) {
        internalRegistry.register(type, constructor);
    }

    public void initialize(ParticleEngine manager) {
        DirectParticleFactoryRegistry direct = new DirectParticleFactoryRegistry(manager);
        DeferredParticleFactoryRegistry deferred = (DeferredParticleFactoryRegistry) internalRegistry;
        deferred.applyTo(direct);
        internalRegistry = direct;
    }

    private static final class DeferredParticleFactoryRegistry implements ParticleFactoryRegistry {
        private final Map<ParticleType<?>, ParticleProvider<?>> factories = new IdentityHashMap<>();
        private final Map<ParticleType<?>, PendingParticleFactory<?>> constructors = new IdentityHashMap<>();

        @Override
        public <T extends ParticleOptions> void register(ParticleType<T> type, ParticleProvider<T> factory) {
            factories.put(type, factory);
        }

        @Override
        public <T extends ParticleOptions> void register(ParticleType<T> type, PendingParticleFactory<T> factory) {
            constructors.put(type, factory);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void applyTo(ParticleFactoryRegistry registry) {
            for (Map.Entry<ParticleType<?>, ParticleProvider<?>> e : factories.entrySet()) registry.register((ParticleType) e.getKey(), (ParticleProvider) e.getValue());
            for (Map.Entry<ParticleType<?>, PendingParticleFactory<?>> e : constructors.entrySet()) registry.register((ParticleType) e.getKey(), (PendingParticleFactory) e.getValue());
        }
    }

    static final class DirectParticleFactoryRegistry implements ParticleFactoryRegistry {
        private final ParticleEngine particleManager;

        DirectParticleFactoryRegistry(ParticleEngine particleManager) {
            this.particleManager = particleManager;
        }

        @Override
        public <T extends ParticleOptions> void register(ParticleType<T> type, ParticleProvider<T> factory) {
            particleManager.register(type, factory);
        }

        @Override
        public <T extends ParticleOptions> void register(ParticleType<T> type, PendingParticleFactory<T> constructor) {
            particleManager.register(type, (ParticleEngine.SpriteParticleRegistration<T>) sprites -> constructor.create(new FabricSpriteProviderImpl(particleManager, sprites)));
        }
    }
}
