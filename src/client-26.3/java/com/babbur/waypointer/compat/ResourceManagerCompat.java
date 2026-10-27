package com.babbur.waypointer.compat;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import java.util.List;
import java.util.Map;

/** Adapts the resource-list selector signature without duplicating locale overlays. */
public abstract class ResourceManagerCompat implements ResourceManager {
    protected abstract ResourceManager delegate();

    @Override public Map<Identifier, Resource> listResources(String path, ResourceManager.Selector selector) {
        return delegate().listResources(path, selector);
    }

    @Override public Map<Identifier, List<Resource>> listResourceStacks(String path, ResourceManager.Selector selector) {
        return delegate().listResourceStacks(path, selector);
    }
}
