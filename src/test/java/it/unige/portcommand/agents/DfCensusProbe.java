package it.unige.portcommand.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import it.unige.portcommand.bootstrap.ServiceLocator;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;

/**
 * Test-only probe: after a 2 s settle delay (DF registrations are asynchronous),
 * censuses the Directory Facilitator for each Port Command service type via
 * {@link ServiceLocator} (on the agent's own thread, the only safe place) and
 * completes the supplied future with the counts. No {@code Thread.sleep} — the
 * delay is a JADE {@link WakerBehaviour}.
 */
public final class DfCensusProbe extends Agent {

    static final List<String> SERVICE_TYPES = List.of(
            "harbour-master", "terminal", "tug-escort", "customs", "weather", "assistant", "vessel");

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        CompletableFuture<Map<String, Integer>> result =
                (CompletableFuture<Map<String, Integer>>) getArguments()[0];
        ServiceLocator locator = new ServiceLocator(getContainerController(), this);
        addBehaviour(new WakerBehaviour(this, 2000L) {
            @Override
            protected void onWake() {
                try {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (String type : SERVICE_TYPES) {
                        counts.put(type, locator.findAll(type).size());
                    }
                    result.complete(counts);
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            }
        });
    }
}
