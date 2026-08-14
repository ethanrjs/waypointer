package com.babbur.waypointer.i18n;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class RemoteLocaleResourceManager implements ResourceManager {
    private static final String PREFIX = "lang/";
    private static final String SUFFIX = ".json";
    private static final PackResources SYNTHETIC_PACK = new PackResources() {
        private final PackLocationInfo location = new PackLocationInfo(
                "waypointer_remote_languages",
                Component.literal("Waypointer downloaded languages"),
                PackSource.BUILT_IN,
                Optional.empty());

        @Override public IoSupplier<InputStream> getRootResource(String... path) { return null; }
        @Override public IoSupplier<InputStream> getResource(PackType type, Identifier id) { return null; }
        @Override public void listResources(PackType type, String namespace, String path, ResourceOutput output) {}
        @Override public Set<String> getNamespaces(PackType type) { return Set.of("waypointer"); }
        @Override public <T> T getMetadataSection(MetadataSectionType<T> type) throws IOException { return null; }
        @Override public PackLocationInfo location() { return location; }
        @Override public void close() {}
    };

    private final ResourceManager delegate;

    private RemoteLocaleResourceManager(ResourceManager delegate) {
        this.delegate = delegate;
    }

    public static ResourceManager wrap(ResourceManager delegate) {
        return delegate instanceof RemoteLocaleResourceManager ? delegate : new RemoteLocaleResourceManager(delegate);
    }

    @Override public Optional<Resource> getResource(Identifier id) { return delegate.getResource(id); }
    @Override public Set<String> getNamespaces() { return delegate.getNamespaces(); }

    @Override
    public List<Resource> getResourceStack(Identifier id) {
        List<Resource> original = delegate.getResourceStack(id);
        String locale = locale(id);
        if (locale == null) return original;
        byte[] overlay = RemoteLocales.overlay(locale);
        if (overlay == null) return original;
        List<Resource> combined = new ArrayList<>(original.size() + 1);
        combined.add(new Resource(SYNTHETIC_PACK, () -> new ByteArrayInputStream(overlay)));
        combined.addAll(original);
        return List.copyOf(combined);
    }

    @Override public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> filter) { return delegate.listResources(path, filter); }
    @Override public Map<Identifier, List<Resource>> listResourceStacks(String path, Predicate<Identifier> filter) { return delegate.listResourceStacks(path, filter); }
    @Override public Stream<PackResources> listPacks() { return delegate.listPacks(); }

    private static String locale(Identifier id) {
        if (!"waypointer".equals(id.getNamespace())) return null;
        String path = id.getPath();
        if (!path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) return null;
        String locale = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        return locale.matches("[a-z0-9]+(?:_[a-z0-9]+)*") ? locale : null;
    }
}
