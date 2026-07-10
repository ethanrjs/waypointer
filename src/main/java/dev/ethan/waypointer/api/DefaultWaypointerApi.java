package dev.ethan.waypointer.api;

import dev.ethan.waypointer.codec.WaypointExportCodec;
import dev.ethan.waypointer.codec.WaypointImporter;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Default implementation backed by Waypointer's in-memory group manager.
 *
 * <p>Constructed by Waypointer after storage has loaded. Consumers receive this
 * as the {@link WaypointerApi} interface, not as a mutable state owner.
 */
public final class DefaultWaypointerApi implements WaypointerApi {

    private static final String OVERLAY_ID_PREFIX = "api-overlay::";

    private final ActiveGroupManager manager;

    public DefaultWaypointerApi(ActiveGroupManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public ZoneSnapshot currentZone() {
        return ZoneSnapshot.from(manager.currentZone());
    }

    @Override
    public List<WaypointGroupSnapshot> allGroups() {
        return snapshot(manager.allGroups());
    }

    @Override
    public List<WaypointGroupSnapshot> activeGroups() {
        return snapshot(manager.activeGroups());
    }

    @Override
    public List<WaypointGroupSnapshot> groupsForZone(String zoneId) {
        Objects.requireNonNull(zoneId, "zoneId");
        return snapshot(manager.groupsForZone(zoneId));
    }

    @Override
    public String createRoute(RouteSpec route) {
        Objects.requireNonNull(route, "route");
        WaypointGroup group = routeGroup(route);
        manager.add(group);
        return group.id();
    }

    @Override
    public boolean removeRoute(String groupId) {
        Objects.requireNonNull(groupId, "groupId");
        if (manager.get(groupId) == null) return false;
        manager.remove(groupId);
        return true;
    }

    @Override
    public boolean addWaypoint(String groupId, WaypointSpec waypoint) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(waypoint, "waypoint");
        WaypointGroup group = manager.get(groupId);
        if (group == null) return false;
        group.add(waypoint.toWaypoint());
        manager.fireDataChangedFor(group);
        return true;
    }

    @Override
    public boolean updateWaypoint(String groupId, int waypointIndex, WaypointSpec replacement) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(replacement, "replacement");
        WaypointGroup group = manager.get(groupId);
        if (group == null) return false;
        if (waypointIndex < 0 || waypointIndex >= group.size()) return false;

        group.set(waypointIndex, replacement.toWaypoint());
        manager.fireDataChangedFor(group);
        return true;
    }

    @Override
    public WaypointGroupSnapshot addTempWaypoint(WaypointSpec waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        WaypointGroup group = manager.getOrCreateTempGroup();
        group.add(waypoint.toTempWaypoint());
        manager.fireTransientDataChanged();
        return WaypointGroupSnapshot.from(group);
    }

    @Override
    public WaypointerHandle showRouteOverlay(RouteOverlaySpec overlay) {
        Objects.requireNonNull(overlay, "overlay");
        WaypointGroup group = overlayGroup(overlay);
        manager.add(group);
        return closeOnce(() -> removeRoute(group.id()));
    }

    @Override
    public ImportSummary importRoutes(String payload, ImportOptions options) {
        Objects.requireNonNull(payload, "payload");
        ImportOptions actualOptions = options == null ? ImportOptions.defaults() : options;
        WaypointImporter.ImportResult result = WaypointImporter.importAny(payload);
        List<String> ids = addImportedGroups(result.groups(), actualOptions);
        int waypointCount = result.groups().stream().mapToInt(WaypointGroup::size).sum();
        return new ImportSummary(
                result.source(),
                result.label(),
                result.groups().size(),
                waypointCount,
                ids);
    }

    @Override
    public String exportRoutes(List<String> groupIds, ExportOptions options) {
        Objects.requireNonNull(groupIds, "groupIds");
        ExportOptions actualOptions = options == null ? ExportOptions.defaults() : options;
        return WaypointExportCodec.encode(
                exportGroups(groupIds),
                actualOptions.toCodecOptions(),
                actualOptions.target().toCodecTarget());
    }

    @Override
    public WaypointerHandle onDataChanged(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        manager.addDataListener(listener);
        return closeOnce(() -> manager.removeDataListener(listener));
    }

    @Override
    public WaypointerHandle onZoneChanged(Consumer<ZoneSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        Consumer<Zone> wrapper = zone -> listener.accept(ZoneSnapshot.from(zone));
        manager.addZoneListener(wrapper);
        return closeOnce(() -> manager.removeZoneListener(wrapper));
    }

    private static WaypointerHandle closeOnce(Runnable closeAction) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) closeAction.run();
        };
    }

    private static List<WaypointGroupSnapshot> snapshot(Collection<WaypointGroup> groups) {
        List<WaypointGroupSnapshot> out = new ArrayList<>(groups.size());
        for (WaypointGroup group : groups) out.add(WaypointGroupSnapshot.from(group));
        return List.copyOf(out);
    }

    private static WaypointGroup routeGroup(RouteSpec route) {
        WaypointGroup group = WaypointGroup.create(route.name(), route.zoneId());
        group.setEnabled(route.enabled());
        group.setLoadMode(route.loadMode().toCore());
        group.setDefaultRadius(route.defaultRadius());
        List<dev.ethan.waypointer.core.Waypoint> waypoints = new ArrayList<>(route.waypoints().size());
        for (WaypointSpec waypoint : route.waypoints()) waypoints.add(waypoint.toWaypoint());
        group.addAll(waypoints);
        return group;
    }

    private static WaypointGroup overlayGroup(RouteOverlaySpec overlay) {
        String id = OVERLAY_ID_PREFIX + UUID.randomUUID();
        WaypointGroup group = new WaypointGroup(id, overlay.name(), overlay.zoneId());
        group.setTemp(true);
        group.setLoadMode(overlay.loadMode().toCore());
        List<dev.ethan.waypointer.core.Waypoint> waypoints = new ArrayList<>(overlay.waypoints().size());
        for (WaypointSpec waypoint : overlay.waypoints()) waypoints.add(waypoint.toTempWaypoint());
        group.addAll(waypoints);
        return group;
    }

    private List<String> addImportedGroups(List<WaypointGroup> groups, ImportOptions options) {
        List<String> ids = new ArrayList<>(groups.size());
        for (WaypointGroup group : groups) {
            retargetUnknownZone(group, options);
            ids.add(group.id());
        }
        manager.addAll(groups);
        return ids;
    }

    private void retargetUnknownZone(WaypointGroup group, ImportOptions options) {
        Zone zone = manager.currentZone();
        if (!options.targetCurrentZoneWhenUnknown() || zone == null) return;
        if (Zone.UNKNOWN.id().equals(group.zoneId())) group.setZoneId(zone.id());
    }

    private List<WaypointGroup> exportGroups(List<String> groupIds) {
        List<WaypointGroup> groups = new ArrayList<>(groupIds.size());
        for (String groupId : groupIds) {
            if (groupId == null) continue;
            WaypointGroup group = manager.get(groupId);
            if (group != null) groups.add(group);
        }
        return groups;
    }
}
