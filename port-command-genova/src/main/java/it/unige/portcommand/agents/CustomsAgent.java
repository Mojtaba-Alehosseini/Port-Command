package it.unige.portcommand.agents;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.behaviours.coordination.HandleClearanceRequestBehaviour;
import it.unige.portcommand.persistence.Mementoable;
import it.unige.portcommand.persistence.dto.CustomsStateDTO;
import it.unige.portcommand.util.RandomSource;
import jade.lang.acl.MessageTemplate;

/**
 * Customs pre-clearance. Handles clearance REQUESTs from the HarbourMaster: hazmat
 * cargo goes through the Prolog {@code clearance_ok} rule, non-hazmat through a
 * seeded inspection roll. The master RNG is injected at args[0]; the inspection
 * roll uses its {@code "customs"} sub-stream.
 *
 * <p>Task 22: the {@code CL-} reference sequence is the one persisted belief — held here
 * (not inside the behaviour) so {@link #snapshot()} can read it and a restore-appended
 * {@link CustomsStateDTO} can seed it, keeping clearance refs unique across a save/load.
 */
public final class CustomsAgent extends PortCommandAgent implements Mementoable {

    private final AtomicInteger refSeq = new AtomicInteger(1);

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
        CustomsStateDTO restored = optionalArgOfType(CustomsStateDTO.class);
        if (restored != null) {
            refSeq.set(restored.nextClearanceRef());
            log.info("{}: restored clearance sequence at {}", getLocalName(), refSeq.get());
        }
        it.unige.portcommand.util.SimClock simClock =
                optionalArgOfType(it.unige.portcommand.util.SimClock.class);
        if (simClock == null) {
            throw new IllegalStateException(
                    "CustomsAgent " + getLocalName() + " requires a SimClock arg (S5: the "
                            + "inspection delay is sim-derived)");
        }
        Random customsRng = randomSource.forStream("customs");
        addBehaviour(new HandleClearanceRequestBehaviour(this, customsRng, refSeq, simClock));
    }

    public CustomsStateDTO snapshotDto() {
        return new CustomsStateDTO(refSeq.get());
    }

    @Override
    public JsonNode snapshot() {
        return persistenceMapper().valueToTree(snapshotDto());
    }

    @Override
    public void restore(JsonNode node) {
        refSeq.set(persistenceMapper().convertValue(node, CustomsStateDTO.class).nextClearanceRef());
    }

    /** Audit catch-all union: the single clearance REQUEST template. */
    @Override
    protected MessageTemplate handledTemplate() {
        return HandleClearanceRequestBehaviour.TEMPLATE;
    }

}
