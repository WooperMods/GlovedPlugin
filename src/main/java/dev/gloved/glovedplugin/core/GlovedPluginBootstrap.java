package dev.gloved.glovedplugin.core;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Discovers and registers the bundled Soul advancements datapack (Paper lifecycle API). */
public final class GlovedPluginBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                var url = this.getClass().getResource("/gloved_souls_datapack");
                if (url == null) {
                    context.getLogger().warn("[SoulAdvancements] Bundled datapack resource '/gloved_souls_datapack' not found in jar.");
                    return;
                }
                URI uri = url.toURI();
                event.registrar().discoverPack(uri, "soul_advancements");
                context.getLogger().info("[SoulAdvancements] Registered bundled soul advancements datapack.");
            } catch (URISyntaxException | IOException e) {
                context.getLogger().warn("[SoulAdvancements] Failed to register bundled datapack: {}", e.getMessage(), e);
            } catch (Throwable t) {
                context.getLogger().warn("[SoulAdvancements] Failed to register bundled datapack: {}", t.toString(), t);
            }
        });
    }
}
