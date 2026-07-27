package it.unige.portcommand.smoke;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Puts {@code --smoke} — and with it <b>DEMO_CHECKLIST row 10</b> — inside {@code ./gradlew check}.
 *
 * <p>Row 10 ("all 10 FIPA performatives across {@code tutorial} + {@code busy_day} + {@code storm}
 * combined", PROJECT_DEFINITION §13.3) is the project's one genuinely cross-scenario acceptance
 * criterion, and until task 26 nothing checked it. Two of the ten were unreachable in the shipped
 * game and nobody could have known:
 *
 * <ul>
 *   <li><b>DISCONFIRM</b> — {@code RetractIfFloodBehaviour} had implemented the terminal half since
 *       task 06, but nothing ever sent the flood INFORM it waits for except {@code TerminalAgentIT}.
 *       Fixed by {@code BerthFloodEvent} (the "scripted Busy-Day terminal-flood" the canon always
 *       described) plus a DISCONFIRM arm on {@code AutoFlowDispatcherBehaviour}'s reply template,
 *       without which the message reached nobody and so reached no comm log either.</li>
 *   <li><b>CANCEL</b> — the storm's escort-cancel cascade needs a vessel that is IN_TRANSIT with
 *       tugs when the alert lands, a 120 sim-second window; the scripted storm struck six
 *       sim-minutes after the tanker had docked, and the alert itself waited up to 15 sim-minutes
 *       for the next broadcast tick. Fixed by retiming {@code storm.json} and by having
 *       {@code WeatherChangeScriptedEvent} broadcast at its scripted instant.</li>
 * </ul>
 *
 * <p>So this is the regression test for both fixes, and it is deliberately the whole runner rather
 * than a narrow assertion on either one: what has to keep working is the CLAIM — that a person
 * playing the three scenarios sees all ten speech acts — and the only honest way to test a claim
 * about three playthroughs is to do three playthroughs.
 *
 * <p><b>Cost.</b> ~2.5 minutes: the runner plays each scenario at its OWN declared pacing
 * (900/900/1800 real-seconds per game day) rather than the integration lane's compressed 5 s/day,
 * because the scripted timings encode reaction windows measured against that pacing — at 5 s/day
 * the storm's whole timeline drains in a tenth of a second and the CANCEL it exists to produce
 * cannot happen. Measured 3/3 green on repeat runs with ~58 sim-seconds of margin either side of
 * the storm window; a failure here is a real demo-criterion failure, not a flake, and the runner's
 * report names which performative went missing in which scenario.
 */
@Tag("integration")
class SmokeRunIT {

    @Test
    void allTenPerformativesAppearAcrossTheThreeScenarios() {
        assertEquals(0, SmokeRunner.runAll(),
                "--smoke reported failures; its printed table above names the missing performative(s) "
                        + "and the scenario(s) searched.\n"
                        + "IF THE ONLY MISS IS **CANCEL**, RE-RUN BEFORE CONCLUDING ANYTHING IS BROKEN. "
                        + "The storm's escort-cancel needs the alert to land while the tanker is in "
                        + "transit with tugs, and at 900 real-seconds per game day that whole window is "
                        + "about ONE WALL SECOND. It is green on this machine, repeatedly, with roughly "
                        + "a second of margin — but it is a sub-second window and a full GC, a loaded "
                        + "CPU or a slower machine can close it. A repeatable miss means the cascade "
                        + "really did break; a single miss means the machine was busy. Measured "
                        + "numbers and the reasoning: docs/BUGS_FOUND.md BUG-02.\n"
                        + "DISCONFIRM comes from busy_day's scripted BerthFlood and has no such window "
                        + "— a miss there is a real regression.");
    }
}
