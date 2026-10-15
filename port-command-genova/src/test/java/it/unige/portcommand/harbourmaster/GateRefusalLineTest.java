package it.unige.portcommand.harbourmaster;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S19 (playtest 5, PT30): what the player reads when the Prolog gate refuses a move. Playtest 5:
 * "Command PROPOSE for WALKIN-1 refused: berth_4 is incompatible with a cargo_vessel [R7]" was
 * technical. The rule id stays; the rest is plain.
 */
class GateRefusalLineTest {

    @Test
    void anIncompatibleBerthSaysWhichBerthsFit() {
        assertEquals("Berth 4 can't take a cargo ship [R7] — not sent. Berths that fit: 3.",
                GateRefusalLine.incompatibleBerth("berth_4", "cargo_vessel", List.of("berth_3")));
        assertEquals("Berth 2 can't take a tanker [R7] — not sent. Berths that fit: 1, 4.",
                GateRefusalLine.incompatibleBerth("berth_2", "tanker", List.of("berth_1", "berth_4")));
        assertEquals("Berth 1 can't take a cruise ship [R7] — not sent. No berth here fits it [R8].",
                GateRefusalLine.incompatibleBerth("berth_1", "cruise_ship", List.of()));
    }

    @Test
    void noFittingBerthAtAllIsR8() {
        assertEquals("No berth here can take a container ship [R8] — not sent.",
                GateRefusalLine.noBerthFits("container_vessel"));
    }

    @Test
    void everyVesselTypeHasPlainWords() {
        assertEquals("No berth here can take a ferry [R8] — not sent.", GateRefusalLine.noBerthFits("ferry"));
        assertEquals("No berth here can take a tanker [R8] — not sent.", GateRefusalLine.noBerthFits("tanker"));
        assertEquals("No berth here can take a cargo ship [R8] — not sent.", GateRefusalLine.noBerthFits("cargo_vessel"));
        assertEquals("No berth here can take a cruise ship [R8] — not sent.", GateRefusalLine.noBerthFits("cruise_ship"));
        assertEquals("No berth here can take a hovercraft [R8] — not sent.", GateRefusalLine.noBerthFits("hovercraft"),
                "a type with no words of its own: its id, underscores as spaces");
    }
}
