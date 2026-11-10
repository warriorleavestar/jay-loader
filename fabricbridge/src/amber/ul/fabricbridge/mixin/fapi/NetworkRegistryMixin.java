package amber.ul.fabricbridge.mixin.fapi;

import amber.ul.fabricbridge.net.ClientNetBridge;
import amber.ul.fabricbridge.net.NetBridge;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric networking hooks on NeoForge's NetworkRegistry: client configuration INIT/START at TAIL of both client
 * initialize*Connection (brief §4.10: FFAPI misses the second), and codec selection by direction for Fabric payload ids whose
 * C2S and S2C codecs differ (brief §4.3; e.g. fabric:accepted_attachments_v1 has a different payload class each way).
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class NetworkRegistryMixin {
    @Inject(method = "initializeNeoForgeConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;Lnet/neoforged/neoforge/network/registration/NetworkPayloadSetup;)V", at = @At("TAIL"), remap = false)
    private static void amber$startNeo(ClientConfigurationPacketListener l, NetworkPayloadSetup setup, CallbackInfo ci) { ClientNetBridge.configurationStart(l); }

    @Inject(method = "initializeOtherConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;)V", at = @At("TAIL"), remap = false)
    private static void amber$startOther(ClientConfigurationPacketListener l, CallbackInfo ci) { ClientNetBridge.configurationStart(l); }

    @Inject(method = "getCodec", at = @At("HEAD"), cancellable = true, remap = false)
    private static void amber$splitCodec(ResourceLocation id, ConnectionProtocol protocol, PacketFlow flow,
                                         CallbackInfoReturnable<StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>> cir) {
        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> c = NetBridge.splitCodec(id, protocol, flow);
        if (c != null) cir.setReturnValue(c);
    }
}
