package it.unige.portcommand.agents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S11 landing (the snapshot-NPE lead): pins the named half of the register-last invariant.
 *
 * <p>The bug: {@code PortCommandAgent.setup()} used to register the agent in the
 * {@code AgentDirectory} BEFORE {@code onSetup()} initialized its fields, so a save landing
 * in any agent's boot window snapshotted half-built state — a null {@code tugId} crashed
 * {@code GameStateBuilder}'s id sort as a bare comparator NPE two files from the cause
 * (observed once, under a half-speed machine, in {@code SaveLoadSessionIT}). The fix is
 * register-LAST (directory-visible ⇒ fully initialized, with the CHM put as the publication
 * edge); these pins make any future violation die AT THE SEAM with the invariant named.
 *
 * <p>Born red on the pre-fix tree: {@code snapshotDto()} on an un-setup agent returned a DTO
 * with a null id silently. (The ORDER half of the invariant is deliberately not pinned by a
 * poll race — it is documented at the register call and enforced by these sentinels.)
 *
 * <p>JADE agents are plain objects until a platform starts them, so raw construction without
 * a container is legal here — {@code setup()} simply never runs.
 */
class SnapshotSentinelTest {

    @Test
    void tugSnapshotBeforeSetupDiesWithTheInvariantNamed() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> new TugAgent().snapshotDto(),
                "an un-setup tug must refuse to snapshot, not emit a null-id DTO");
        assertTrue(e.getMessage() != null && e.getMessage().contains("before setup"),
                "the error must name the register-last invariant; got: " + e.getMessage());
    }

    @Test
    void terminalSnapshotBeforeSetupDiesWithTheInvariantNamed() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> new TerminalAgent().snapshotDto(),
                "an un-setup terminal must refuse to snapshot, not emit a null-id DTO");
        assertTrue(e.getMessage() != null && e.getMessage().contains("before setup"),
                "the error must name the register-last invariant; got: " + e.getMessage());
    }
}
