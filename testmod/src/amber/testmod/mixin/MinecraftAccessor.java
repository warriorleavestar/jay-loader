package amber.testmod.mixin;

import net.minecraft.class_310;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(class_310.class)
public interface MinecraftAccessor {
    @Accessor("fps") static int amberTestmod$getFps() { throw new AssertionError(); }
}
