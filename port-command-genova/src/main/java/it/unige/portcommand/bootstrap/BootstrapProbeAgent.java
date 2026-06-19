package it.unige.portcommand.bootstrap;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.wrapper.AgentContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Tiny liveness/DF probe spawned by {@link JadeBootstrap}. Its {@code setup()}
 * runs on the agent's own thread, which is the only safe place to call
 * {@code DFService.search}; it performs a boot-time DF self-check (expects no
 * {@code smoke-test} providers) and signals readiness via a latch. The container,
 * latch, and result future are passed as constructor args (by reference, local
 * container) — no static registry.
 */
public final class BootstrapProbeAgent extends Agent {

    private static final Logger log = LoggerFactory.getLogger(BootstrapProbeAgent.class);

    @Override
    protected void setup() {
        MDC.put("agent", getLocalName());
        log.info("BootstrapProbeAgent up — running DF self-check");

        Object[] args = getArguments();
        AgentContainer container = (AgentContainer) args[0];
        CountDownLatch readyLatch = (CountDownLatch) args[1];
        @SuppressWarnings("unchecked")
        CompletableFuture<List<AID>> dfCheck = (CompletableFuture<List<AID>>) args[2];

        ServiceLocator locator = new ServiceLocator(container, this);

        addBehaviour(new OneShotBehaviour(this) {
            @Override
            public void action() {
                MDC.put("agent", getAgent().getLocalName());
                try {
                    List<AID> found = locator.findAll("smoke-test");
                    log.info("DF self-check: {} 'smoke-test' provider(s)", found.size());
                    dfCheck.complete(found);
                } catch (RuntimeException e) {
                    dfCheck.completeExceptionally(e);
                } finally {
                    readyLatch.countDown();
                }
            }
        });
    }
}
