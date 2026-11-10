package amber.ul.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public final class AmberMixinBootstrap implements IMixinServiceBootstrap {
    @Override public String getName() { return "Amber"; }
    @Override public String getServiceClassName() { return "amber.ul.mixin.AmberMixinService"; }
    @Override public void bootstrap() {}
}
