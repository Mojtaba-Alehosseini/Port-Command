package it.unige.portcommand.agents;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import it.unige.portcommand.behaviours.coordination.HandleClearanceRequestBehaviour;
import it.unige.portcommand.util.RandomSource;

/**
 * Customs pre-clearance. Handles clearance REQUESTs from the HarbourMaster: hazmat
 * cargo goes through the Prolog {@code clearance_ok} rule, non-hazmat through a
 * seeded inspection roll. The master RNG is injected at args[0]; the inspection
 * roll uses its {@code "customs"} sub-stream.
 */
public final class CustomsAgent extends PortCommandAgent {

    @Override
    protected void registerServices() {
        registerDfService("customs", getLocalName());
    }

    @Override
    protected void onSetup() {
        RandomSource randomSource = initArg(RandomSource.class);
        if (randomSource == null) {
            throw new IllegalStateException(
                    "CustomsAgent " + getLocalName() + " requires RandomSource at args[0]");
        }
        Random customsRng = randomSource.forStream("customs");
        addBehaviour(new HandleClearanceRequestBehaviour(this, customsRng, new AtomicInteger(1)));
    }
}
