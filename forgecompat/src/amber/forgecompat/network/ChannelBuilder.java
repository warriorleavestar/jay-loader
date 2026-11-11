package amber.forgecompat.network;

import net.minecraft.resources.ResourceLocation;

/** Forge's ChannelBuilder; {@link #simpleChannel()} makes a SimpleChannel over Amber's NeoForge payload. */
public final class ChannelBuilder {
    final ResourceLocation name;
    int version; boolean optional;
    Channel.VersionTest client, server;
    private ChannelBuilder(ResourceLocation name) { this.name = name; }
    public static ChannelBuilder named(ResourceLocation name) { return new ChannelBuilder(name); }
    public static ChannelBuilder named(String namespace, String path) { return named(ResourceLocation.fromNamespaceAndPath(namespace, path)); }
    public ChannelBuilder networkProtocolVersion(int version) { this.version = version; return this; }
    public ChannelBuilder clientAcceptedVersions(Channel.VersionTest t) { client = t; return this; }
    public ChannelBuilder serverAcceptedVersions(Channel.VersionTest t) { server = t; return this; }
    public ChannelBuilder acceptedVersions(Channel.VersionTest t) { client = t; server = t; return this; }
    public ChannelBuilder optional() { optional = true; return this; }
    public ChannelBuilder optionalClient() { return optional(); }
    public ChannelBuilder optionalServer() { return optional(); }
    public SimpleChannel simpleChannel() { return SimpleChannel.create(name); }
}
