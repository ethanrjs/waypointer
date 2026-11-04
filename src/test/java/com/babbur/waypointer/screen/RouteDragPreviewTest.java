package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteDragPreviewTest {
    @Test
    void previewReservesTheCommittedSlotWithoutMutatingRoutes() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup a = new WaypointGroup("a", "A", "hub");
        WaypointGroup b = new WaypointGroup("b", "B", "hub");
        WaypointGroup c = new WaypointGroup("c", "C", "hub");
        manager.addAll(List.of(a, b, c));
        List<WaypointerRouteList.Row> rows = List.of(row(a), row(b), row(c));
        for (String before : new String[]{"b", "c", null}) {
            var preview = WaypointerRouteList.previewRows(rows, manager, "a",
                    new WaypointerRouteList.DropTarget(null, before));
            int slot = before == null ? 2 : before.equals("b") ? 0 : 1;
            assertNull(preview.get(slot));
            assertEquals(List.of(b, c), preview.stream().filter(r -> r != null)
                    .map(WaypointerRouteList.Row::group).toList());
            assertEquals(List.of(a, b, c), manager.allGroupsList());
        }
        var upward = WaypointerRouteList.previewRows(rows, manager, "c",
                new WaypointerRouteList.DropTarget(null, "a"));
        assertNull(upward.getFirst());
        assertEquals(a, upward.get(1).group());
        assertEquals(rows, WaypointerRouteList.previewRows(rows, manager, "a", null));
        assertTrue(manager.moveGroupToContainer("a", null, "c"));
        assertEquals(List.of(b, a, c), manager.allGroupsList());
    }

    @Test
    void folderDropPreviewsTheEndAndUnfilingPreviewsAfterFolders() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup a = new WaypointGroup("a", "A", "hub");
        WaypointGroup b = new WaypointGroup("b", "B", "hub");
        manager.addAll(List.of(a, b));
        RouteFolder folder = new RouteFolder("folder", "Folder", "hub", false);
        manager.addFolder(folder, List.of("b"));
        var header = WaypointerRouteList.Row.folder(folder, 1, false);
        var preview = WaypointerRouteList.previewRows(List.of(header, row(b), row(a)),
                manager, "a", new WaypointerRouteList.DropTarget("folder", null));
        assertEquals(header, preview.getFirst());
        assertEquals(b, preview.get(1).group());
        assertNull(preview.get(2));
        manager.setFolderCollapsed(folder.id(), true);
        var collapsed = WaypointerRouteList.previewRows(List.of(header, row(a)), manager,
                "a", new WaypointerRouteList.DropTarget("folder", null));
        assertNull(collapsed.get(1));
        var unfiled = WaypointerRouteList.previewRows(List.of(header, row(b)), manager,
                "b", new WaypointerRouteList.DropTarget(null, null));
        assertEquals(header, unfiled.getFirst());
        assertNull(unfiled.getLast());
    }

    private static WaypointerRouteList.Row row(WaypointGroup group) {
        return WaypointerRouteList.Row.group(null, group, false, false);
    }
}
