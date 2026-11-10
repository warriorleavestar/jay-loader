package amber.ul.fabricbridge.fapi.fluid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * FluidTypes for Fabric (vanilla-style) fluids on NeoForge. NeoForge asks every fluid for a FluidType and throws
 * "Mod fluids must override getFluidType." for any fluid that is not water, lava or milk; a Fabric mod's fluid never
 * overrides it. Vanilla decides fluid behaviour by tag (entities swim, drown, get pushed and extinguished in
 * #minecraft:water, burn in #minecraft:lava), while NeoForge decides by FluidType (WATER_TYPE stands for the water tag
 * in Entity.isInWater, getFluidHeight, isEyeInFluid...). So the answer follows the tag at the time of the call:
 * water-tagged -> WATER_TYPE, lava-tagged -> LAVA_TYPE, otherwise an inert type of its own (one per source fluid),
 * which, like vanilla for an untagged fluid, does not push, float, drown or extinguish. Not cached on the Fluid
 * (tags load after registration and change on reload). Convert-to-source stays the fluid's own: NeoForge's
 * FlowingFluid.canConvertToSource(state, level, pos) calls canConvertToSource(Level).
 */
public final class FabricFluidTypes {
    private FabricFluidTypes() {}
    private static final Map<Fluid, FluidType> INERT = new ConcurrentHashMap<>();

    /** True for fluids that reach Fluid.getFluidType() unoverridden and are not vanilla's (vanilla's resolve normally). */
    public static boolean foreign(Fluid f) {
        return f != Fluids.EMPTY && f != Fluids.WATER && f != Fluids.FLOWING_WATER && f != Fluids.LAVA && f != Fluids.FLOWING_LAVA;
    }

    public static FluidType of(Fluid f) {
        FluidState s = f.defaultFluidState();
        if (s.is(FluidTags.WATER)) return NeoForgeMod.WATER_TYPE.value();
        if (s.is(FluidTags.LAVA)) return NeoForgeMod.LAVA_TYPE.value();
        return inert(f);
    }

    public static boolean isInert(FluidType t) { return t instanceof Inert; }

    static FluidType inert(Fluid f) {
        Fluid key = f instanceof FlowingFluid ff ? ff.getSource() : f;
        return INERT.computeIfAbsent(key, k -> {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(k);
            return new Inert(FluidType.Properties.create()
                    .descriptionId("block." + id.getNamespace() + "." + id.getPath())
                    .canSwim(false).canDrown(false).canPushEntity(false).canExtinguish(false).supportsBoating(false).canHydrate(false)
                    .motionScale(0).fallDistanceModifier(1f).pathType(null).adjacentPathType(null));
        });
    }

    static final class Inert extends FluidType {
        Inert(Properties p) { super(p); }
        @Override public String toString() { return "AmberFabricFluidType[" + getDescriptionId() + "]"; }
    }
}
