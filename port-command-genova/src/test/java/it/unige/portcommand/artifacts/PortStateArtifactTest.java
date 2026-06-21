package it.unige.portcommand.artifacts;

import java.util.ArrayList;
import java.util.List;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.ontology.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure-JVM coverage of the shared port-state store + its subscribe/emit channel. */
class PortStateArtifactTest {

    @Test
    void updateStoresBerthAndNotifiesSubscriber() {
        PortStateArtifact artifact = new PortStateArtifact();
        List<PortStateUpdate> received = new ArrayList<>();
        artifact.subscribe(received::add);

        BerthOccupancy occ =
                new BerthOccupancy("berth_1", "v1", 0L, 100L, 2, BerthOccupancy.Status.PROVISIONAL);
        artifact.update(new BerthOccupancyUpdate("berth_1", occ));

        assertEquals(occ, artifact.getBerth("berth_1").orElseThrow());
        assertEquals(1, received.size());
        PortStateUpdate.BerthDelta delta = assertInstanceOf(PortStateUpdate.BerthDelta.class, received.get(0));
        assertEquals("berth_1", delta.berthId());
    }

    @Test
    void updateTugStoresStatusAndNotifies() {
        PortStateArtifact artifact = new PortStateArtifact();
        List<PortStateUpdate> received = new ArrayList<>();
        artifact.subscribe(received::add);

        artifact.updateTug("tug_1", new Position(1.0, 2.0, 0.0), TugStatus.EN_ROUTE_TO_VESSEL);

        assertEquals(TugStatus.EN_ROUTE_TO_VESSEL, artifact.tugSnapshot().get("tug_1"));
        PortStateUpdate.TugDelta delta = assertInstanceOf(PortStateUpdate.TugDelta.class, received.get(0));
        assertEquals("tug_1", delta.tugId());
        assertEquals(TugStatus.EN_ROUTE_TO_VESSEL, delta.status());
    }

    @Test
    void snapshotsAreImmutableCopies() {
        PortStateArtifact artifact = new PortStateArtifact();
        artifact.update(new BerthOccupancyUpdate("berth_1", BerthOccupancy.free("berth_1")));
        assertThrows(UnsupportedOperationException.class,
                () -> artifact.berthSnapshot().put("x", BerthOccupancy.free("x")));
    }

    @Test
    void subscriberExceptionDoesNotBreakOthers() {
        PortStateArtifact artifact = new PortStateArtifact();
        List<PortStateUpdate> good = new ArrayList<>();
        artifact.subscribe(u -> {
            throw new RuntimeException("boom");
        });
        artifact.subscribe(good::add);
        artifact.update(new BerthOccupancyUpdate("berth_1", BerthOccupancy.free("berth_1")));
        assertEquals(1, good.size(), "second subscriber still notified despite the first throwing");
    }
}
