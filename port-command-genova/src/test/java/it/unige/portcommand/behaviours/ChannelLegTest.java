package it.unige.portcommand.behaviours;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The channel leg a ship is sent on ({@code transit_sim_millis} of {@code proceed_to_berth}),
 * as literals (S16, ADR-20).
 *
 * <p>The leg is the distance from the channel entry (700, 200) to the berth, at the tugs' 12 kn
 * and the S16 scale of 60 px per km — so ship and escort arrive together. The numbers below are
 * worked out by hand from those four positions, not asked of the code: they are the "channel
 * leg" row of ADR-20's table (13.7 / 9.3 / 15.1 / 11.2 game minutes), and a change of the scale,
 * the tug speed or a berth position that moves that row has to move this test too.
 */
class ChannelLegTest {

    /** One game second of slack: the literals are rounded to the millisecond by hand. */
    private static final long TOLERANCE_MS = 1_000L;

    @Test
    void theLegToEachBerthIsItsDistanceAtTwelveKnots() {
        // berth_1 (400,150): 304.14 px = 5.069 km; at 22.224 km/h = 821.1 s
        assertEquals(821_107L, MovementControl.channelLegSimMillis("berth_1"), TOLERANCE_MS);
        // berth_2 (500,150): 206.16 px = 3.436 km = 556.6 s
        assertEquals(556_575L, MovementControl.channelLegSimMillis("berth_2"), TOLERANCE_MS);
        // berth_3 (400,350): 335.41 px = 5.590 km = 905.5 s
        assertEquals(905_535L, MovementControl.channelLegSimMillis("berth_3"), TOLERANCE_MS);
        // berth_4 (500,350): 250.00 px = 4.167 km = 674.9 s
        assertEquals(674_946L, MovementControl.channelLegSimMillis("berth_4"), TOLERANCE_MS);
    }

    @Test
    void noLegIsTheOldTwoMinutes() {
        // Before S16 every ship made a fixed 120-second leg on its own, whatever the berth.
        for (String berth : new String[] {"berth_1", "berth_2", "berth_3", "berth_4"}) {
            long leg = MovementControl.channelLegSimMillis(berth);
            assertEquals(true, leg > 9 * 60_000L && leg < 16 * 60_000L, berth + " leg " + leg);
        }
    }
}
