package amber.ul.fabricbridge.mixin.fapi;

import amber.ul.fabricbridge.net.NetBridge;
import java.util.Queue;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationNetworkHandler;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-base of fabric-networking's ServerConfigurationNetworkHandlerMixin (vetoed: it cancels NeoForge's network query and
 * ping(0)). Provides FabricServerConfigurationNetworkHandler (addTask / completeTask; NeoForge's finishCurrentTask is public)
 * and the two hooks NeoForge has no event for: BEFORE_CONFIGURE at HEAD of runConfiguration and DISCONNECT.
 */
@Mixin(value = ServerConfigurationPacketListenerImpl.class, remap = false)
public abstract class ServerConfigurationListenerMixin implements FabricServerConfigurationNetworkHandler {
    @Shadow @Final private Queue<ConfigurationTask> configurationTasks;

    @Override public void addTask(ConfigurationTask task) { configurationTasks.add(task); }
    @Override public void completeTask(ConfigurationTask.Type type) { ((ServerConfigurationPacketListenerImpl) (Object) this).finishCurrentTask(type); }

    @Inject(method = "runConfiguration", at = @At("HEAD"), remap = false)
    private void amber$beforeConfigure(CallbackInfo ci) { NetBridge.beforeConfigure((ServerConfigurationPacketListenerImpl) (Object) this); }

    @Inject(method = "onDisconnect", at = @At("HEAD"), remap = false)
    private void amber$configDisconnect(DisconnectionDetails details, CallbackInfo ci) { NetBridge.configDisconnect((ServerConfigurationPacketListenerImpl) (Object) this); }
}
