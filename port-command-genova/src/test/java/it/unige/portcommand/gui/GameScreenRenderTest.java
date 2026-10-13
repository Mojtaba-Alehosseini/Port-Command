package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;
import javax.swing.JButton;

import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.events.NegotiationOpenedEvent;
import it.unige.portcommand.gui.events.NotificationEvent;
import it.unige.portcommand.gui.events.PortStatusEvent;
import it.unige.portcommand.gui.events.PortStatusEvent.BerthRow;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.harbourmaster.VesselTracking.Channel;
import it.unige.portcommand.harbourmaster.VesselTracking.Stage;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.ontology.Position;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): paints the whole game screen, populated like a game in progress, into PNG
 * files — {@code build/gui-renders/main-1280x800.png}, {@code main-1536x864.png} and
 * {@code main-1280x800-paused.png} — so the layout can be LOOKED AT without launching the game.
 *
 * <p>No window, no agents, no JADE: a {@link GameScreen} on a private bus, clock and port
 * artifact, fed the same events the running game publishes (two walk-ins negotiating, with about
 * 2:24 and 0:24 left on their counters; a port with one vessel docked, one arriving and one
 * queued; a mixed tug fleet; weather; wallet, reputation and clock; a few comm-log lines, one
 * citing a Prolog rule; a notification), laid out by {@link HeadlessSwing} and painted into an
 * image. The paused picture paints the real {@link PauseOverlay} over the first one, with its
 * pass-through region bound to this screen's three columns, as {@code MainWindow} binds it.
 *
 * <p><b>What is asserted is structure, never looks.</b> The pictures differ with the platform's
 * fonts, so nothing here compares them to a stored image: the files exist and are not empty, the
 * three columns are where they should be at both sizes, and in the paused picture the top bar,
 * the notification strip and the PAUSED box are darker than before while the three columns are
 * pixel-for-pixel untouched. Whether it looks RIGHT is for the person opening the files — which
 * is why all three are written before the first of those assertions runs.
 *
 * <p>One known difference from the real window: a split pane's divider is an AWT container, which
 * Swing paints only when it has a native peer — so here a divider shows as the plain gap between
 * two columns, without its grip texture.
 */
class GameScreenRenderTest {

    private static final Path OUTPUT_DIR = Path.of("build", "gui-renders");

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    /** The moment the pictures show: day 1, 09:12. */
    private static final long NOW = 9 * HOUR + 12 * MINUTE;
    private static final Position AT_BASE = new Position(50, 100, 0);

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(3_600);
    private final PortStateArtifact portState = new PortStateArtifact();

    /** Where the named panels are, in the screen's coordinates, at one window size. */
    private record Layout(Rectangle hud, Rectangle waiting, Rectangle port, Rectangle chat, Rectangle commLog,
                          Rectangle columns) {

        static Layout of(GameScreen screen) {
            return new Layout(onScreen(screen, GameScreen.NAME_HUD), onScreen(screen, WaitingListPanel.NAME),
                    onScreen(screen, GameScreen.NAME_PORT), onScreen(screen, GameScreen.NAME_CHAT),
                    onScreen(screen, GameScreen.NAME_COMMLOG), screen.columnsBoundsIn(screen));
        }
    }

    @Test
    @Timeout(120)
    void rendersTheScreenAtTwoWindowSizesAndPaused() throws Exception {
        GameScreen[] built = new GameScreen[1];
        try {
            HeadlessSwing.onEdt(() -> built[0] = new GameScreen(bus, portState,
                    (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")),
                    clock, new GameSpeedControl(clock, bus, () -> false)));
            GameScreen screen = built[0];

            seedAGameInProgress();
            // More than once: handling an event on the EDT can queue more EDT work (the port
            // artifact's hop to the EDT, a tab asking for focus, the log jumping to its tail),
            // and all of that must have run before anything is painted.
            for (int i = 0; i < 3; i++) {
                HeadlessSwing.flushEdt();
            }

            HeadlessSwing.onEdt(() -> {
                Files.createDirectories(OUTPUT_DIR);

                // 1280x800, the default window.
                show(screen, 1280, 800);
                Layout at1280 = Layout.of(screen);
                BufferedImage normal = HeadlessSwing.paint(screen);
                write(normal, "main-1280x800.png");

                // The same picture, paused: the real overlay, bound to this screen's columns.
                PauseOverlay overlay = new PauseOverlay(() -> { }, Rectangle::new,
                        () -> screen.columnsBoundsIn(screen));
                overlay.setBounds(0, 0, 1280, 800);
                overlay.doLayout();
                BufferedImage paused = copyOf(normal);
                Graphics2D g = paused.createGraphics();
                try {
                    overlay.paint(g);
                } finally {
                    g.dispose();
                }
                write(paused, "main-1280x800-paused.png");
                // Read now: the overlay's own answers depend on the screen's CURRENT layout.
                Rectangle pausedBox = overlay.captionBox();
                boolean[] clicks = clicksWhilePaused(overlay, at1280);

                // 1536x864, a 1080p desktop at 125% scaling.
                show(screen, 1536, 864);
                Layout at1536 = Layout.of(screen);
                write(HeadlessSwing.paint(screen), "main-1536x864.png");

                // All three pictures are on disk; now the structure.
                assertColumnsInOrderAtTheirMinimumsOrWider(at1280, 1280);
                assertColumnsInOrderAtTheirMinimumsOrWider(at1536, 1536);
                assertThePauseDimsEverythingButTheColumns(at1280, pausedBox, normal, paused);
                assertTrue(clicks[0], "a click on Resume reaches the overlay");
                assertFalse(clicks[1], "a click on the waiting list falls through to it");
                assertFalse(clicks[2], "a click on the Port table falls through");
                assertFalse(clicks[3], "a click on the chat input falls through");
                assertFalse(clicks[4], "a click on the comm log falls through");
                assertTrue(clicks[5], "a click on the top bar does not fall through");
                assertTrue(clicks[6], "a click on the notification strip does not fall through");
            });
        } finally {
            // Cascades to every panel: bus subscriptions cancelled, the chat's and the
            // notification chips' timers stopped.
            HeadlessSwing.onEdt(() -> {
                if (built[0] != null) {
                    built[0].removeNotify();
                }
            });
        }
    }

    // --- the game in progress -----------------------------------------------------------------------

    /** Publishes, from this (non-EDT) thread like an agent would, what a running game would have
     * published by 09:12 on day 1. */
    private void seedAGameInProgress() {
        // Two walk-ins hailed; their conversations run on the clock's conversation time. Opened
        // with 4:00 and 2:00 and advanced 96 s BEFORE the tabs open, so the counters — which a tab
        // reads when it opens — show 2:24 and 0:24: one normal, one in the under-30-seconds colour.
        clock.conversationClock().open("nego-W003", 240_000L);
        clock.conversationClock().open("nego-W004", 120_000L);
        clock.advance(96_000L);
        bus.publish(new NegotiationOpenedEvent("nego-W003", new WalkInDialogueSnapshot("W003", "cargo_vessel",
                9.0, 150.0, 30_000, "general_cargo", "berth_3", 6, 1436.0, 0.0, 1, 0.0, false, "Genoa Star")));
        bus.publish(new NegotiationOpenedEvent("nego-W004", new WalkInDialogueSnapshot("W004", "ferry",
                5.0, 110.0, 9_000, "general_cargo", "berth_4", 3, 1250.0, 0.0, 1, 0.0, false, "Ligurian Dawn")));

        // The port: a contracted vessel docked until 14:30, a walk-in on its way in, one queued.
        bus.publish(new PortStatusEvent(1, List.of(
                new BerthRow("berth_1", "C501", "C501", Channel.CONTRACTED, Stage.DOCKED,
                        14 * HOUR + 30 * MINUTE, false),
                BerthRow.free("berth_2"),
                new BerthRow("berth_3", "W002", "Santa Margherita", Channel.WALK_IN, Stage.IN_TRANSIT, null, false),
                BerthRow.free("berth_4")),
                List.of("Camogli Express")));
        portState.updateTug("tug_1", AT_BASE, TugStatus.IDLE);
        portState.updateTug("tug_2", AT_BASE, TugStatus.ESCORTING);
        portState.updateTug("tug_3", AT_BASE, TugStatus.RETURNING);
        portState.updateTug("tug_4", AT_BASE, TugStatus.BIDDING);
        bus.publish(new WeatherChangeEvent(18.4, "good", 1.2, "sunny", false));

        // The top bar.
        bus.publish(new SimClockTickEvent(NOW, 1, 9, 12));
        bus.publish(new WalletChangedEvent(52_300.0, 2_300.0, "render", null, NOW));
        bus.publish(new ReputationChangedEvent(72.0, 2.0, "render", null, NOW));

        // The comm log: a contracted arrival's berth request, its grant citing a rule, the tug
        // call and a bid, a routine weather report, and one walk-in's opening offer.
        String ontology = "port_command_v1";
        bus.publish(new CommLogEvent(NOW - 9 * MINUTE, "vessel_c501", List.of("harbour_master"), ACLMessage.REQUEST,
                "C501 requests a berth for 6h", "berth-C501", ontology, "fipa-request",
                "{\"vessel_id\":\"C501\",\"hours\":6}"));
        bus.publish(new CommLogEvent(NOW - 9 * MINUTE + 4_000L, "harbour_master", List.of("vessel_c501"),
                ACLMessage.ACCEPT_PROPOSAL, "berth_1 granted to C501 (compatible [R7])", "berth-C501", ontology,
                "fipa-request", "{\"vessel_id\":\"C501\",\"berth_id\":\"berth_1\"}"));
        bus.publish(new CommLogEvent(NOW - 8 * MINUTE, "harbour_master",
                List.of("tug_1", "tug_2", "tug_3", "tug_4"), ACLMessage.CFP,
                "call for tugs: escort C501 to berth_1", "cnp-C501", ontology, "fipa-contract-net",
                "{\"vessel_id\":\"C501\",\"berth_id\":\"berth_1\",\"tugs_required\":1}"));
        bus.publish(new CommLogEvent(NOW - 8 * MINUTE + 6_000L, "tug_2", List.of("harbour_master"),
                ACLMessage.PROPOSE, "tug_2 bids 180 for the escort, 4 min away", "cnp-C501", ontology,
                "fipa-contract-net", "{\"tug_id\":\"tug_2\",\"cost\":180,\"eta_minutes\":4}"));
        bus.publish(new CommLogEvent(NOW - 5 * MINUTE, "weather_agent", List.of("harbour_master"), ACLMessage.INFORM,
                "weather: 18 kn, sunny, visibility good", "weather-37", ontology, "weather-broadcast",
                "{\"wind\":18.4,\"visibility\":\"good\",\"swell\":1.2,\"state\":\"sunny\"}"));
        bus.publish(new CommLogEvent(NOW - 2 * MINUTE, "vessel_w003", List.of("harbour_master"), ACLMessage.PROPOSE,
                "Genoa Star offers 1436 for 6h at berth_3", "nego-W003", ontology, "walk-in-negotiation",
                "{\"vessel_id\":\"W003\",\"price\":1436,\"hours\":6}"));

        bus.publish(new NotificationEvent("Scenario started: Tutorial — Calm Morning",
                NotificationEvent.Severity.INFO, NOW));
    }

    // --- layout, files, images (EDT only) -------------------------------------------------------------

    /** Sizes the screen like a window of that size and puts the dividers at the default
     * proportions — what {@code MainWindow} does once it is shown. */
    private static void show(GameScreen screen, int width, int height) {
        HeadlessSwing.layOut(screen, width, height);
        screen.applyDividerProportions(GameScreen.DEFAULT_LEFT_PROPORTION, GameScreen.DEFAULT_CHAT_PROPORTION);
        HeadlessSwing.layOut(screen, width, height);
    }

    private static Rectangle onScreen(GameScreen screen, String name) {
        Component found = HeadlessSwing.byName(screen, name);
        assertNotNull(found, "no component named '" + name + "' on the screen");
        return HeadlessSwing.boundsIn(found, screen);
    }

    private static void write(BufferedImage image, String fileName) throws IOException {
        Path file = OUTPUT_DIR.resolve(fileName);
        assertTrue(ImageIO.write(image, "png", file.toFile()), "no PNG writer for " + file);
        assertTrue(Files.size(file) > 1_000, file.toAbsolutePath() + " is " + Files.size(file) + " bytes");
    }

    private static BufferedImage copyOf(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    /**
     * Whether the overlay keeps a click at each of seven places — Resume, waiting list, Port
     * table, chat input, comm log, top bar, notification strip, in that order. Asked while the
     * screen still has the layout the overlay was painted over.
     */
    private static boolean[] clicksWhilePaused(PauseOverlay overlay, Layout at) {
        JButton resume = null;
        for (Component child : overlay.getComponents()) {
            if (child instanceof JButton button) {
                resume = button;
            }
        }
        assertNotNull(resume, "fixture: the overlay has its Resume button");
        Rectangle resumeBounds = resume.getBounds();
        return new boolean[] {
                overlay.contains(resumeBounds.x + resumeBounds.width / 2, resumeBounds.y + resumeBounds.height / 2),
                overlay.contains(at.waiting().x + at.waiting().width / 2, at.waiting().y + at.waiting().height / 2),
                overlay.contains(at.port().x + at.port().width / 2, at.port().y + 20),
                overlay.contains(at.chat().x + at.chat().width / 2, at.chat().y + at.chat().height - 15),
                overlay.contains(at.commLog().x + at.commLog().width - 20, at.commLog().y + at.commLog().height / 2),
                overlay.contains(at.hud().x + at.hud().width / 2, at.hud().y + at.hud().height / 2),
                overlay.contains(overlay.getWidth() / 2, overlay.getHeight() - 3),
        };
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual, Rectangle area, String what) {
        assertFalse(area.isEmpty(), "fixture: no area to compare for " + what);
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                    throw new AssertionError(what + " changed at (" + x + ", " + y + ")");
                }
            }
        }
    }

    // --- structural assertions --------------------------------------------------------------------------

    private static void assertColumnsInOrderAtTheirMinimumsOrWider(Layout at, int width) {
        Rectangle port = at.port();
        Rectangle chat = at.chat();
        Rectangle commLog = at.commLog();
        String where = " at " + width + " px: " + port + " | " + chat + " | " + commLog;

        assertTrue(port.x < chat.x && chat.x < commLog.x, "the columns run left to right" + where);
        assertEquals(width, commLog.x + commLog.width, "the comm log ends at the right edge" + where);
        assertTrue(port.width >= GameScreen.LEFT_MIN_WIDTH, "the left column has its minimum" + where);
        assertTrue(chat.width >= GameScreen.COLUMN_MIN_WIDTH, "the chat has its minimum" + where);
        assertTrue(commLog.width >= GameScreen.COLUMN_MIN_WIDTH, "the comm log has its minimum" + where);
        assertTrue(port.width < chat.width, "the left column is the narrow one" + where);
    }

    /**
     * The paused picture against the unpaused one it was painted over (so everything the overlay
     * left alone is identical by construction, and any difference is the overlay's doing).
     *
     * @param box the overlay's PAUSED group, in the picture's coordinates
     */
    private static void assertThePauseDimsEverythingButTheColumns(Layout at, Rectangle box, BufferedImage normal,
            BufferedImage paused) {
        Rectangle hud = at.hud();
        Rectangle chat = at.chat();
        Rectangle commLog = at.commLog();
        Rectangle columns = at.columns();
        assertFalse(box.isEmpty(), "fixture: the PAUSED group was laid out");
        assertTrue(columns.contains(box), "fixture: the PAUSED group sits over the columns: " + box);

        // Dimmed: the top bar (sampled at its empty right end), the notification strip, and the
        // margin of the PAUSED group (inside the group, above its caption).
        int belowColumns = columns.y + columns.height;
        Rectangle topBarPatch = new Rectangle(hud.x + hud.width - 60, hud.y + 2, 50, hud.height - 4);
        Rectangle stripPatch = new Rectangle(normal.getWidth() - 60, belowColumns + 1, 50,
                normal.getHeight() - belowColumns - 2);
        Rectangle boxMarginPatch = new Rectangle(box.x + 1, box.y + 1, box.width - 2, 8);
        assertFalse(topBarPatch.isEmpty() || stripPatch.isEmpty() || boxMarginPatch.isEmpty(),
                "fixture: the three sampled patches have an area");
        assertTrue(HeadlessSwing.brightness(paused, topBarPatch) < HeadlessSwing.brightness(normal, topBarPatch),
                "the top bar is dimmed while paused");
        assertTrue(HeadlessSwing.brightness(paused, stripPatch) < HeadlessSwing.brightness(normal, stripPatch),
                "the notification strip is dimmed while paused");
        assertTrue(HeadlessSwing.brightness(paused, boxMarginPatch) < HeadlessSwing.brightness(normal, boxMarginPatch),
                "the scrim stays behind the PAUSED group, even though it sits over the chat");

        // Untouched: the three columns, everywhere the PAUSED group is not. Each sampled area is
        // far from the window's centre, where the group is.
        Rectangle leftColumn = new Rectangle(at.port().x, at.waiting().y, at.port().width,
                at.port().y + at.port().height - at.waiting().y);
        Rectangle chatBottom = new Rectangle(chat.x, chat.y + chat.height - 40, chat.width, 40);
        Rectangle chatTop = new Rectangle(chat.x, chat.y, chat.width, 30);
        Rectangle commLogRightHalf = new Rectangle(commLog.x + commLog.width / 2, commLog.y,
                commLog.width - commLog.width / 2, commLog.height);
        for (Rectangle area : List.of(leftColumn, chatBottom, chatTop, commLogRightHalf)) {
            assertFalse(area.intersects(box), "fixture: " + area + " is clear of the PAUSED group " + box);
        }
        assertSamePixels(normal, paused, leftColumn, "the left column (waiting list and Port table)");
        assertSamePixels(normal, paused, chatBottom, "the chat's input rows");
        assertSamePixels(normal, paused, chatTop, "the chat's tab strip");
        assertSamePixels(normal, paused, commLogRightHalf, "the comm log");
    }
}
