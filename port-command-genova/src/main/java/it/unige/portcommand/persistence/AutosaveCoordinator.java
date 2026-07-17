package it.unige.portcommand.persistence;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Supplier;

import it.unige.portcommand.lifecycle.events.AutosaveRequestedEvent;
import it.unige.portcommand.persistence.events.GameSavedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THE consumer of task 24's {@link AutosaveRequestedEvent} (planning/22 ownership note:
 * the coordinator publishes it once per settled day and this class — nothing else —
 * subscribes). CALLER_THREAD is load-bearing twice over: the event arrives on the
 * HarbourMaster thread inside the EOD settlement chain, which is exactly where
 * {@link GameStateBuilder#build} must run, and it means the autosave file is complete
 * before {@code DayRolloverEvent} (and the day-cap game-over check) fires.
 *
 * <p>Deduped by day ({@code lastAutoSavedDay}) as defence in depth — the coordinator's
 * own EOD idempotency already guarantees one event per day. {@link #resetAfterLoad()}
 * clears the guard so loading an EARLIER day's save does not suppress that day's
 * re-settlement autosave.
 *
 * <p>Session-scoped like {@code GameOverGuard}: constructed once by {@code GameSession},
 * survives save/load teardown-rebuild (the {@link AgentDirectory} it snapshots through
 * always reflects the live world). A failed write is logged and the game continues — an
 * autosave must never take down a running game.
 */
public final class AutosaveCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AutosaveCoordinator.class);

    private final SaveLoadManager manager;
    private final EventBus eventBus;
    private final Supplier<GameStateBuilder.Sources> sources;
    private final Subscription<AutosaveRequestedEvent> subscription;
    private volatile int lastAutoSavedDay;

    public AutosaveCoordinator(EventBus eventBus, SaveLoadManager manager,
                               Supplier<GameStateBuilder.Sources> sources) {
        this.eventBus = eventBus;
        this.manager = manager;
        this.sources = sources;
        this.subscription = eventBus.subscribe(AutosaveRequestedEvent.class, this::onAutosaveRequested,
                DeliveryMode.CALLER_THREAD);
    }

    /** Public so tests drive it directly (the task-10 subscribe-once precedent). */
    public void onAutosaveRequested(AutosaveRequestedEvent event) {
        if (event.day() <= lastAutoSavedDay) {
            log.debug("autosave for day {} already written — ignoring duplicate", event.day());
            return;
        }
        lastAutoSavedDay = event.day();
        try {
            GameState state = GameStateBuilder.build(sources.get(), Instant.now().toString(), true);
            manager.save(manager.autosavePath(), state);
            eventBus.publish(new GameSavedEvent(manager.autosavePath().toString(),
                    sizeOf(manager.autosavePath()), true, event.day()));
        } catch (SaveNotAllowedException e) {
            throw new IllegalStateException("autosave must drop, never refuse", e); // unreachable
        } catch (IOException | RuntimeException e) {
            log.error("autosave for day {} failed — game continues", event.day(), e);
        }
    }

    /** Clears the day guard after a load (an earlier day may legitimately settle again). */
    public void resetAfterLoad() {
        lastAutoSavedDay = 0;
    }

    /** Symmetry with the other session-scoped bus subscribers. */
    public void close() {
        subscription.cancel();
    }

    private static long sizeOf(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }
}
