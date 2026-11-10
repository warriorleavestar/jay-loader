package amber.ul.fabricbridge.forge.mixin.fapi;

import java.lang.reflect.Field;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * fabric-dimensions-v1 on Forge 47: a FabricDimensions.teleport of a player out of the End goes to its custom target
 * instead of rolling the End credits. Fabric's EntityMixin#stopEndSpecificBehavior (stripped) redirects Level.END in
 * vanilla's changeDimension(ServerLevel) on both ServerPlayer and Entity; Forge moved the body to
 * changeDimension(ServerLevel, ITeleporter), where only ServerPlayer's reads Level.END, and Mixin checks require per
 * target class, so the shared mixin can't carry it. Same redirect here on ServerPlayer only; the custom target is the
 * field Fabric's EntityMixin merges into ServerPlayer (the mixin targets ServerPlayer and Entity, so each gets its own copy and
 * a player's Teleportable setter writes ServerPlayer's; read reflectively: another mixin's field can't be shadowed).
 */
@Mixin(value = ServerPlayer.class, remap = false)
public abstract class ServerPlayerEndFabricMixin {
    @Unique private static volatile Field amber$customTarget;

    @Redirect(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;", remap = false,
            at = @At(value = "FIELD", remap = false, target = "Lnet/minecraft/world/level/Level;END:Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> amber$fabricNoCredits() {
        return amber$hasCustomTarget(this) ? null : Level.END;
    }

    @Unique private static boolean amber$hasCustomTarget(Object self) {
        try {
            Field f = amber$customTarget;
            if (f == null) {
                for (Class<?> c = ServerPlayer.class; c != null && f == null; c = c.getSuperclass())
                    try { f = c.getDeclaredField("customTeleportTarget"); } catch (NoSuchFieldException e) { /* next */ }
                if (f == null) { System.out.println("[amber-ul] fabric-dimensions: no customTeleportTarget field"); return false; }
                f.setAccessible(true); amber$customTarget = f;
            }
            return f.get(self) != null;
        } catch (ReflectiveOperationException e) { return false; }
    }
}
