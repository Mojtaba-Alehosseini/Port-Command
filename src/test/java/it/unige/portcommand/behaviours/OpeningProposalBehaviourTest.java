package it.unige.portcommand.behaviours;

import it.unige.portcommand.behaviours.negotiation.OpeningProposalBehaviour;
import it.unige.portcommand.negotiation.Personality;
import it.unige.portcommand.negotiation.VesselTemplate;
import it.unige.portcommand.negotiation.VesselTemplates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 19 STEP 1 fix: pins the literal acceptance bound
 * {@code [0.70*minTarget, 0.85*maxTarget]} per vessel type, for every personality and
 * across the roll space — with NO live JADE agent (drives
 * {@link OpeningProposalBehaviour#computeOpening} directly, public for exactly this
 * purpose). Lives in the parent {@code behaviours} package, NOT
 * {@code behaviours.negotiation} — a test class in a catalogue-counted sub-package
 * shadows {@code BehaviourCatalogueTest}'s {@code getResource} scan (same reason as
 * {@code WithdrawalBehaviourTest}).
 */
class OpeningProposalBehaviourTest {

    private static final double LOWER_FRACTION = 0.70;
    private static final double UPPER_FRACTION = 0.85;

    @Test
    void everyTypeAndPersonalityStaysWithinTheDerivedBand() {
        for (String type : VesselTemplates.types()) {
            VesselTemplate template = VesselTemplates.forType(type);
            double minTarget = template.targetPriceRange()[0];
            double maxTarget = template.targetPriceRange()[1];
            double lowerBound = LOWER_FRACTION * minTarget;
            double upperBound = UPPER_FRACTION * maxTarget;

            for (Personality personality : Personality.values()) {
                // Both target-price boundaries, and a spread of rolls including the 0/1 edges.
                for (double target : new double[] {minTarget, maxTarget, (minTarget + maxTarget) / 2.0}) {
                    for (double roll : new double[] {0.0, 0.25, 0.5, 0.75, 0.999999}) {
                        double opening = OpeningProposalBehaviour.computeOpening(target, personality, roll);
                        assertTrue(opening >= lowerBound - 0.5 && opening <= upperBound + 0.5,
                                type + "/" + personality + " target=" + target + " roll=" + roll
                                        + " -> opening=" + opening + " outside [" + lowerBound + "," + upperBound + "]");
                        assertEquals(Math.rint(opening), opening,
                                "opening must be a whole number of euros: " + opening);
                    }
                }
            }
        }
    }

    @Test
    void aggressiveOpensLowerThanDesperateAtTheSameTargetAndRoll() {
        double target = 6000.0;
        double roll = 0.5;
        double aggressive = OpeningProposalBehaviour.computeOpening(target, Personality.AGGRESSIVE, roll);
        double neutral = OpeningProposalBehaviour.computeOpening(target, Personality.NEUTRAL, roll);
        double desperate = OpeningProposalBehaviour.computeOpening(target, Personality.DESPERATE, roll);
        assertTrue(aggressive < neutral, "AGGRESSIVE must lowball deeper than NEUTRAL at the same roll");
        assertTrue(neutral < desperate, "NEUTRAL must lowball deeper than DESPERATE at the same roll");
    }
}
