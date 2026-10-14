package it.unige.portcommand.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.gui.events.ReputationChangedEvent;
import it.unige.portcommand.gui.events.SimClockTickEvent;
import it.unige.portcommand.gui.events.WalletChangedEvent;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.ColumnDividers;
import it.unige.portcommand.gui.model.GameSpeedControl;
import it.unige.portcommand.gui.model.WeatherForecast;
import it.unige.portcommand.nlp.PipelineResult;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.SimClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): how the window's content is put together — which panels are on the screen,
 * in which order, how wide, and what the two column dividers do with a stored proportion, a drag
 * and a window resize.
 *
 * <p>None of this was testable while the layout lived in {@code MainWindow}'s constructor: a
 * {@code JFrame} cannot be built under {@code -Djava.awt.headless=true}. {@link GameScreen} is a
 * plain panel, so it is built here on a private bus, clock and port artifact (no agents, no JADE),
 * given a size and laid out by hand ({@link HeadlessSwing#layOut} — {@code validate()} lays
 * nothing out on a tree that has no window). Everything that touches it runs on the EDT.
 *
 * <p>Widths are asserted against the split panes' own divider size rather than a typed 10, so the
 * numbers follow the look-and-feel; the three minimum widths and the two default proportions are
 * {@code GameScreen}'s constants.
 *
 * <p>S16 (2026-10-13): the "top bar" block — the weather moved there from the Port table, opens a
 * forecast on a click, and is the one cell of the bar that gives way, so that the speed toggles
 * keep their place at the window's minimum width.
 */
class GameScreenCompositionTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;

    /** What a resizable frame's own left and right borders may take from the window's width. Not
     * measurable headless; 24 px is generous for every desktop the game has been run on. */
    private static final int FRAME_SLACK = 24;

    private final EventBus bus = new EventBus();
    private final SimClock clock = new SimClock(3_600);
    private final PortStateArtifact portState = new PortStateArtifact();
    private GameScreen screen;

    @BeforeEach
    void buildTheScreen() throws Exception {
        HeadlessSwing.onEdt(() -> screen = new GameScreen(bus, portState,
                (text, ctx) -> CompletableFuture.completedFuture(new PipelineResult.Error("unused")),
                clock, new GameSpeedControl(clock, bus, () -> false)));
    }

    @AfterEach
    void releaseTheScreen() throws Exception {
        // Cascades to every panel: bus subscriptions cancelled, timers stopped.
        HeadlessSwing.onEdt(() -> {
            if (screen != null) {
                screen.removeNotify();
            }
        });
    }

    // --- helpers (EDT only) ------------------------------------------------------------------------

    /** Sizes the screen like a window would, puts the dividers where two stored proportions say,
     * and lays the columns' contents out at the widths that result. */
    private void show(int width, int height, double left, double chat) {
        HeadlessSwing.layOut(screen, width, height);
        screen.applyDividerProportions(left, chat);
        HeadlessSwing.layOut(screen, width, height);
    }

    private void showAtDefaults(int width, int height) {
        show(width, height, GameScreen.DEFAULT_LEFT_PROPORTION, GameScreen.DEFAULT_CHAT_PROPORTION);
    }

    private Component named(String name) {
        Component found = HeadlessSwing.byName(screen, name);
        assertNotNull(found, "no component named '" + name + "' on the screen");
        return found;
    }

    /** A named panel's rectangle in the screen's own coordinates. */
    private Rectangle onScreen(String name) {
        return HeadlessSwing.boundsIn(named(name), screen);
    }

    private static void collect(Container container, List<Component> into) {
        for (Component child : container.getComponents()) {
            into.add(child);
            if (child instanceof Container nested) {
                collect(nested, into);
            }
        }
    }

    private int dividerSize() {
        return screen.outerSplitForTest().getDividerSize();
    }

    private void assertEveryColumnHasItsMinimum(String when) {
        Rectangle port = onScreen(GameScreen.NAME_PORT);
        Rectangle chat = onScreen(GameScreen.NAME_CHAT);
        Rectangle commLog = onScreen(GameScreen.NAME_COMMLOG);
        assertTrue(port.width >= GameScreen.LEFT_MIN_WIDTH, when + ": the left column is " + port.width + " px");
        assertTrue(chat.width >= GameScreen.COLUMN_MIN_WIDTH, when + ": the chat is " + chat.width + " px");
        assertTrue(commLog.width >= GameScreen.COLUMN_MIN_WIDTH,
                when + ": the comm log is " + commLog.width + " px");
    }

    // --- what is on the screen ------------------------------------------------------------------------

    @Test
    @Timeout(20)
    void theFourNamedPanelsAndTheWaitingListAreOnTheScreen() throws Exception {
        HeadlessSwing.onEdt(() -> {
            assertInstanceOf(HUDPanel.class, named(GameScreen.NAME_HUD));
            assertInstanceOf(PortPanel.class, named(GameScreen.NAME_PORT));
            assertInstanceOf(ChatPanel.class, named(GameScreen.NAME_CHAT));
            assertInstanceOf(CommLogPanel.class, named(GameScreen.NAME_COMMLOG));
            assertInstanceOf(WaitingListPanel.class, named(WaitingListPanel.NAME));

            assertEquals("tutorial-hud", GameScreen.NAME_HUD);
            assertEquals("tutorial-port", GameScreen.NAME_PORT);
            assertEquals("tutorial-chat", GameScreen.NAME_CHAT);
            assertEquals("tutorial-commlog", GameScreen.NAME_COMMLOG);
            assertEquals("waiting-list", WaitingListPanel.NAME);

            // Each name finds ONE panel: the tutorial overlay takes the first match.
            List<Component> all = new ArrayList<>();
            collect(screen, all);
            for (String name : List.of(GameScreen.NAME_HUD, GameScreen.NAME_PORT, GameScreen.NAME_CHAT,
                    GameScreen.NAME_COMMLOG, WaitingListPanel.NAME)) {
                assertEquals(1, all.stream().filter(component -> name.equals(component.getName())).count(),
                        "components named '" + name + "'");
            }
            assertEquals(1, all.stream().filter(NotificationStrip.class::isInstance).count(),
                    "one notification strip");
        });
    }

    @Test
    @Timeout(20)
    void everyTutorialStepHasItsTargetOnTheRealScreen() throws Exception {
        // The overlay looks its highlight target up by name in the window's content; a name that
        // nothing carries fails silently (the scrim just dims everything).
        HeadlessSwing.onEdt(() -> {
            for (int step = 1; step <= TutorialOverlay.TOTAL_STEPS; step++) {
                String target = TutorialOverlay.stepTargetNameForTest(step);
                assertNotNull(HeadlessSwing.byName(screen, target),
                        "tutorial step " + step + " highlights '" + target + "', which is not on the screen");
            }
        });
    }

    @Test
    @Timeout(20)
    void nothingOnTheScreenIsNamedOrClassedLikeAMap() throws Exception {
        HeadlessSwing.onEdt(() -> {
            List<Component> all = new ArrayList<>();
            collect(screen, all);
            assertTrue(all.size() > 20, "fixture: the walk sees the whole tree, not " + all.size() + " components");
            for (Component component : all) {
                String name = component.getName();
                String type = component.getClass().getName();
                assertFalse(name != null && name.toLowerCase(Locale.ROOT).contains("map"),
                        "a component is still named '" + name + "' (" + type + ")");
                assertFalse(type.toLowerCase(Locale.ROOT).contains("map"), "a component of class " + type);
            }
        });
    }

    // --- the top bar (S16: the weather) ----------------------------------------------------------------

    /** The longest line the weather can be: two-digit wind, the longest state and visibility words. */
    private static final WeatherChangeEvent WORST_WEATHER = new WeatherChangeEvent(45.0, "poor", 5.0, "stormy", true);
    private static final String WORST_WEATHER_TEXT = "45 kn · stormy · visibility poor · swell 5.0 m";

    private JLabel weatherLabel() {
        return assertInstanceOf(JLabel.class, named(HUDPanel.NAME_WEATHER));
    }

    private static List<String> labelTexts(Container root) {
        List<Component> all = new ArrayList<>();
        collect(root, all);
        List<String> texts = new ArrayList<>();
        for (Component component : all) {
            if (component instanceof JLabel label && label.getText() != null) {
                texts.add(label.getText());
            }
        }
        return texts;
    }

    @Test
    @Timeout(20)
    void theWeatherIsOnTheTopBarFollowsTheBusAndIsNotInTheLeftColumn() throws Exception {
        HeadlessSwing.onEdt(() -> {
            JLabel weather = weatherLabel();
            Container hud = assertInstanceOf(Container.class, named(GameScreen.NAME_HUD));
            assertTrue(SwingUtilities.isDescendingFrom(weather, hud), "it is on the top bar");
            assertTrue(hud.getComponent(hud.getComponentCount() - 1) == weather,
                    "as the bar's LAST cell: everything that was on the bar before keeps its place and order");
            assertEquals("—", weather.getText(), "no reading yet: a placeholder, not calm weather");
            assertTrue(weather.getToolTipText() == null, "a placeholder has nothing more to say in a tooltip");
            assertFalse(weather.isFocusable(), "a click on it must never pull focus out of the chat input");
            assertFalse(weather.isRequestFocusEnabled());
        });

        bus.publish(new WeatherChangeEvent(18.4, "good", 0.5, "sunny", false));
        HeadlessSwing.flushEdt();

        HeadlessSwing.onEdt(() -> {
            JLabel weather = weatherLabel();
            assertEquals("18 kn · sunny · visibility good · swell 0.5 m", weather.getText());
            assertEquals("18 kn · sunny · visibility good · swell 0.5 m (click for the forecast)",
                    weather.getToolTipText());
            // Not a line of the Port table any more — nor anywhere else in the left column.
            Container port = assertInstanceOf(Container.class, named(GameScreen.NAME_PORT));
            for (String text : labelTexts(port)) {
                assertFalse(text.contains("kn") || text.contains("Weather"),
                        "the Port table still shows the weather: \"" + text + "\"");
            }
        });

        // A world is replaced: its weather is not the next world's.
        bus.publish(new GameLoadedEvent(1, 50_000.0, 70));
        HeadlessSwing.flushEdt();
        HeadlessSwing.onEdt(() -> assertEquals("—", weatherLabel().getText(),
                "back to the placeholder until the new world announces its own weather"));

        // ...which it does right after the marker; even a reading equal to the old one is shown.
        bus.publish(new WeatherChangeEvent(18.4, "good", 0.5, "sunny", false));
        HeadlessSwing.flushEdt();
        HeadlessSwing.onEdt(() -> assertEquals("18 kn · sunny · visibility good · swell 0.5 m",
                weatherLabel().getText()));
    }

    @Test
    @Timeout(20)
    void atTheWindowsMinimumWidthTheSpeedTogglesStayOnTheBarWhateverTheWeatherSays() throws Exception {
        // Every other cell at about its widest too: day 30, six-digit wallet, three-digit reputation.
        long late = 29L * 24 * 3_600_000 + 23 * 3_600_000 + 59 * 60_000;
        bus.publish(new SimClockTickEvent(late, 30, 23, 59));
        bus.publish(new WalletChangedEvent(-123_456.0, 0.0, "test", null, late));
        bus.publish(new ReputationChangedEvent(100.0, 0.0, "test", null, late));
        // S17b: the two score cells at about their widest as well - a five-digit loss against a
        // five-digit target, a three-digit score.
        bus.publish(new it.unige.portcommand.gui.events.ScoreChangedEvent(
                new it.unige.portcommand.harbourmaster.financial.DayScore(30, -12_345.0, 14_500.0, 4, 4, 0, 0, 5.0),
                null));
        bus.publish(WORST_WEATHER);
        HeadlessSwing.flushEdt();

        HeadlessSwing.onEdt(() -> {
            int content = MainWindow.MIN_WIDTH - FRAME_SLACK; // what the frame's borders leave at the minimum
            showAtDefaults(content, HEIGHT);
            Rectangle hud = onScreen(GameScreen.NAME_HUD);
            assertEquals(content, hud.width, "fixture: the top bar spans the window");

            int togglesEnd = 0;
            for (int speed : new int[] {1, 2, 4}) {
                Component toggle = named(HUDPanel.NAME_SPEED_PREFIX + speed);
                Rectangle at = HeadlessSwing.boundsIn(toggle, screen);
                assertEquals(toggle.getPreferredSize().width, at.width, "x" + speed + " is squeezed");
                assertTrue(at.x >= hud.x && at.x + at.width <= hud.x + hud.width,
                        "x" + speed + " is pushed off the top bar at " + content + " px: " + at);
                assertTrue(at.y >= hud.y && at.y + at.height <= hud.y + hud.height,
                        "x" + speed + " is on a row the bar does not show: " + at);
                togglesEnd = Math.max(togglesEnd, at.x + at.width);
            }

            // S17b: "Next ship" is the last cell that may not be cut - it must be whole and on the bar.
            Component nextShip = named(HUDPanel.NAME_NEXT_SHIP);
            Rectangle button = HeadlessSwing.boundsIn(nextShip, screen);
            assertEquals(nextShip.getPreferredSize().width, button.width, "Next ship is squeezed");
            assertTrue(button.x >= togglesEnd && button.x + button.width <= hud.x + hud.width,
                    "Next ship is pushed off the top bar at " + content + " px: " + button);
            togglesEnd = button.x + button.width;

            JLabel weather = weatherLabel();
            Rectangle at = HeadlessSwing.boundsIn(weather, screen);
            assertEquals(WORST_WEATHER_TEXT, weather.getText());
            assertTrue(at.x >= togglesEnd, "the weather comes after the speed toggles and Next ship: " + at);
            assertTrue(at.x + at.width <= hud.x + hud.width, "and ends inside the bar: " + at);
            assertTrue(at.y >= hud.y && at.y + at.height <= hud.y + hud.height, "on the bar's one row: " + at);
            assertTrue(at.width <= weather.getPreferredSize().width, "never wider than its text");
            assertEquals(WORST_WEATHER_TEXT + " (click for the forecast)", weather.getToolTipText(),
                    "whatever the bar had to cut is in the tooltip");
            assertEveryColumnHasItsMinimum("at the window's minimum width");
        });
    }

    @Test
    @Timeout(20)
    void theWeatherIsTheOneCellOfTheBarThatGivesWay() throws Exception {
        // Font-independent: the bar is laid out at its own natural width, then narrower than that.
        bus.publish(WORST_WEATHER);
        HeadlessSwing.flushEdt();

        HeadlessSwing.onEdt(() -> {
            HUDPanel hud = assertInstanceOf(HUDPanel.class, named(GameScreen.NAME_HUD));
            JLabel weather = weatherLabel();
            Component lastToggle = named(HUDPanel.NAME_SPEED_PREFIX + 4);
            int natural = hud.getPreferredSize().width;
            int barHeight = hud.getPreferredSize().height;
            int weatherWidth = weather.getPreferredSize().width;
            assertTrue(weatherWidth > 100, "fixture: the weather line has a real width: " + weatherWidth);

            HeadlessSwing.layOut(hud, natural, barHeight);
            assertEquals(weatherWidth, weather.getWidth(), "at the bar's natural width nothing is cut");
            Rectangle toggleAt = HeadlessSwing.boundsIn(lastToggle, hud);
            int weatherX = weather.getX();
            assertEquals(natural - HUDPanel.HGAP, weatherX + weather.getWidth(), "the bar ends one gap after it");

            HeadlessSwing.layOut(hud, natural + 300, barHeight);
            assertEquals(weatherWidth, weather.getWidth(), "room to spare is not handed to the weather");
            assertEquals(weatherX, weather.getX(), "nor does it move: it follows the toggles");

            HeadlessSwing.layOut(hud, natural - 40, barHeight);
            assertEquals(weatherWidth - 40, weather.getWidth(), "forty pixels short: the weather gives up all forty");
            assertEquals(weatherX, weather.getX());
            assertEquals(toggleAt, HeadlessSwing.boundsIn(lastToggle, hud), "and the speed toggles none");

            HeadlessSwing.layOut(hud, weatherX - 5, barHeight);
            assertEquals(0, weather.getWidth(), "narrower than everything before it: no width, never a negative one");
            assertEquals(toggleAt, HeadlessSwing.boundsIn(lastToggle, hud), "the toggles still have not moved");

            assertEquals(natural - weatherWidth - HUDPanel.HGAP, hud.getMinimumSize().width,
                    "the bar's own minimum is everything but the weather");
        });
    }

    @Test
    @Timeout(20)
    void aClickOnTheWeatherWouldShowTheForecastForTheCurrentState() throws Exception {
        HeadlessSwing.onEdt(() -> {
            HUDPanel hud = assertInstanceOf(HUDPanel.class, named(GameScreen.NAME_HUD));
            assertEquals(List.of("No weather reading yet."), labelTexts(hud.forecastContentForTest()),
                    "before the first reading there is nothing to forecast from, and the popup says so");
        });

        bus.publish(new WeatherChangeEvent(32.0, "good", 3.4, "stormy", true));
        HeadlessSwing.flushEdt();

        HeadlessSwing.onEdt(() -> {
            HUDPanel hud = assertInstanceOf(HUDPanel.class, named(GameScreen.NAME_HUD));
            JPanel forecast = hud.forecastContentForTest();

            // From a storm, by the weather agent's own table: 15/55/30, then its square, then its cube.
            assertEquals(List.of(
                    "Forecast",
                    "", "sunny", "cloudy", "stormy",
                    "in 1 hour", "15%", "55%", "30%",
                    "in 2 hours", "39%", "47%", "14%",
                    "in 3 hours", "54%", "37%", "9%",
                    "From the weather model's transition table.",
                    "A scenario's scripted weather is not included."), labelTexts(forecast));
            assertTrue(String.join(" ", labelTexts(forecast)).endsWith(WeatherForecast.NOTE),
                    "the honest line, whole, at the bottom");
            assertEquals(WeatherForecastPopup.NAME, forecast.getName());

            // It must not be able to take the keyboard from the chat input: nothing in it is focusable.
            List<Component> parts = new ArrayList<>();
            parts.add(forecast);
            collect(forecast, parts);
            for (Component part : parts) {
                assertFalse(part.isFocusable(), part.getClass().getSimpleName() + " in the forecast is focusable");
            }
        });

        // The forecast follows the reading: a sunny sky gives the sunny row of the table.
        bus.publish(new WeatherChangeEvent(12.0, "good", 0.5, "sunny", false));
        HeadlessSwing.flushEdt();
        HeadlessSwing.onEdt(() -> {
            HUDPanel hud = assertInstanceOf(HUDPanel.class, named(GameScreen.NAME_HUD));
            List<String> texts = labelTexts(hud.forecastContentForTest());
            assertEquals(List.of("in 1 hour", "85%", "13%", "2%"), texts.subList(5, 9));
        });
    }

    @Test
    @Timeout(20)
    void nothingOnTheTopBarCanTakeTheKeyboardFocus() throws Exception {
        bus.publish(WORST_WEATHER);
        HeadlessSwing.flushEdt();

        HeadlessSwing.onEdt(() -> {
            assertFalse(weatherLabel().isFocusable(), "the weather, with a reading on it");
            for (int speed : new int[] {1, 2, 4}) {
                assertFalse(named(HUDPanel.NAME_SPEED_PREFIX + speed).isFocusable(), "x" + speed);
            }
        });
    }

    // --- the three columns ----------------------------------------------------------------------------

    @Test
    @Timeout(20)
    void atTheDefaultWindowSizeTheColumnsRunLeftToRightAtTwentyFortyForty() throws Exception {
        HeadlessSwing.onEdt(() -> {
            showAtDefaults(WIDTH, HEIGHT);

            Rectangle hud = onScreen(GameScreen.NAME_HUD);
            Rectangle waiting = onScreen(WaitingListPanel.NAME);
            Rectangle port = onScreen(GameScreen.NAME_PORT);
            Rectangle chat = onScreen(GameScreen.NAME_CHAT);
            Rectangle commLog = onScreen(GameScreen.NAME_COMMLOG);
            int divider = dividerSize();

            // Order, and exact tiling of the width: left | divider | chat | divider | comm log.
            assertEquals(0, port.x, "the left column starts at the window's left edge");
            assertEquals(port.x + port.width + divider, chat.x, "the chat follows the first divider");
            assertEquals(chat.x + chat.width + divider, commLog.x, "the comm log follows the second divider");
            assertEquals(WIDTH, commLog.x + commLog.width, "and ends at the window's right edge");
            assertTrue(port.x < chat.x && chat.x < commLog.x, "left to right: port, chat, comm log");

            // Widths: exactly what the divider arithmetic gives...
            int outerSpan = WIDTH - divider;
            assertEquals(ColumnDividers.location(outerSpan, 0.20, GameScreen.LEFT_MIN_WIDTH,
                    2 * GameScreen.COLUMN_MIN_WIDTH + divider), port.width, "left column");
            int innerSpan = outerSpan - port.width - divider;
            assertEquals(ColumnDividers.location(innerSpan, 0.50, GameScreen.COLUMN_MIN_WIDTH,
                    GameScreen.COLUMN_MIN_WIDTH), chat.width, "chat");
            assertEquals(innerSpan - chat.width, commLog.width, "comm log");
            // ...which is 20 / 40 / 40 of the window, give or take the two dividers.
            assertEquals(0.20 * WIDTH, port.width, 2.0 * divider, "the left column takes about 20%");
            assertEquals(0.40 * WIDTH, chat.width, 2.0 * divider, "the chat takes about 40%");
            assertEquals(0.40 * WIDTH, commLog.width, 2.0 * divider, "the comm log takes about 40%");
            assertTrue(Math.abs(chat.width - commLog.width) <= 1, "chat and comm log share their space evenly");
            assertEveryColumnHasItsMinimum("at " + WIDTH + "x" + HEIGHT);

            // Top bar above, columns below it, all three columns the same height.
            assertEquals(0, hud.y, "the top bar is at the top");
            assertEquals(WIDTH, hud.width, "and spans the window");
            assertTrue(hud.height > 0, "the top bar has a height");
            assertEquals(hud.y + hud.height, chat.y, "the columns start right under the top bar");
            assertEquals(chat.y, commLog.y);
            assertEquals(chat.height, commLog.height);
            assertTrue(chat.height > HEIGHT / 2, "the columns take most of the height: " + chat.height);

            // The left column: the waiting list on top, the Port table under it, same width.
            assertEquals(chat.y, waiting.y, "the waiting list starts at the top of the left column");
            assertEquals(port.width, waiting.width, "the waiting list is as wide as the column");
            assertTrue(waiting.height > 0, "the waiting list shows at least its title and empty line");
            assertTrue(waiting.y + waiting.height <= port.y, "the Port table is below the waiting list");
            // S18b (PT5): "Finished" at the bottom; the Port table fills what lies between.
            Rectangle finished = onScreen(FinishedListPanel.NAME);
            assertEquals(port.width, finished.width, "the Finished list is as wide as the column");
            assertTrue(finished.height > 0, "the Finished list shows at least its title and empty line");
            assertTrue(port.y + port.height <= finished.y, "the Finished list is below the Port table");
            assertEquals(chat.y + chat.height, finished.y + finished.height, "and ends at the column's bottom");
        });
    }

    /** S18b (T8, PT4): the PAUSED caption goes on the top bar, left of its pause button. */
    @Test
    @Timeout(20)
    void thePausedCaptionGoesOnTheTopBarLeftOfThePauseButton() throws Exception {
        HeadlessSwing.onEdt(() -> {
            showAtDefaults(WIDTH, HEIGHT);
            Rectangle hud = onScreen(GameScreen.NAME_HUD);
            Rectangle button = screen.pauseButtonBoundsIn(screen);
            Rectangle area = screen.pauseCaptionAreaIn(screen);

            assertFalse(button.isEmpty(), "the top bar has its pause button");
            assertTrue(hud.contains(button), "on the top bar: " + button + " in " + hud);
            assertEquals(hud.y, area.y);
            assertEquals(hud.height, area.height);
            assertEquals(button.x - HUDPanel.HGAP, area.x + area.width, "it ends a gap before the button");
            assertTrue(area.width > 300, "room for the one-row caption: " + area);
        });
    }

    /** S18b (U13): the empty shell says so in the top bar, the chat column and the strip. */
    @Test
    @Timeout(20)
    void theEmptyShellSaysSoInAllThreePlaces() throws Exception {
        HeadlessSwing.onEdt(() -> {
            screen.showGameState(true, false, false);
            assertEquals("—", screen.hudForTest().model().dayLabel());
            assertTrue(((javax.swing.JLabel) screen.chatPanelForTest().getComponent(0)).getText()
                    .contains(MainWindow.NO_GAME_RUNNING_TEXT), "no game running, in the chat column");
            assertEquals("[i] " + MainWindow.NO_GAME_RUNNING_TEXT,
                    screen.notificationStripForTest().pinnedHintForTest(), "and pinned in the strip");
            assertFalse(screen.hudForTest().pauseButtonForTest().isEnabled(), "nothing to pause");

            screen.showGameState(false, true, true);
            assertTrue(screen.hudForTest().model().dayLabel().startsWith("Day "));
            assertTrue(((javax.swing.JLabel) screen.chatPanelForTest().getComponent(0)).getText()
                    .contains(ChatPanel.WAITING_TEXT), "a game: waiting for a walk-in again");
            assertEquals("", screen.notificationStripForTest().pinnedHintForTest(), "the hint is gone");
            assertEquals(HUDPanel.RESUME_GLYPH, screen.hudForTest().pauseButtonForTest().getText());
            assertTrue(screen.hudForTest().pauseButtonForTest().isEnabled());
        });
    }

    @Test
    @Timeout(20)
    void theColumnsAreaIsTheThreeColumnsAndNothingElse() throws Exception {
        // What the pause overlay leaves usable: from the left edge to the right edge, from under
        // the top bar to above the notification strip.
        HeadlessSwing.onEdt(() -> {
            showAtDefaults(WIDTH, HEIGHT);
            Rectangle hud = onScreen(GameScreen.NAME_HUD);
            Rectangle chat = onScreen(GameScreen.NAME_CHAT);
            Rectangle commLog = onScreen(GameScreen.NAME_COMMLOG);
            Rectangle port = onScreen(GameScreen.NAME_PORT);

            Rectangle columns = screen.columnsBoundsIn(screen);

            assertEquals(new Rectangle(0, hud.height, WIDTH, chat.height), columns);
            assertTrue(columns.contains(port) && columns.contains(chat) && columns.contains(commLog),
                    "all three columns are inside it");
            assertFalse(columns.intersects(hud), "the top bar is not");
            assertTrue(columns.y + columns.height < HEIGHT, "nor is the notification strip under the columns");
            assertEquals(chat, screen.chatBoundsIn(screen), "the chat's own rectangle is the chat panel's");
        });
    }

    // --- stored proportions -----------------------------------------------------------------------------

    @Test
    @Timeout(20)
    void aStoredProportionThatWouldCrushAColumnIsClamped() throws Exception {
        HeadlessSwing.onEdt(() -> {
            int outerSpan = WIDTH - dividerSize();
            assertTrue(outerSpan - (int) (outerSpan * 0.95) < GameScreen.COLUMN_MIN_WIDTH,
                    "non-vacuity: 0.95 applied as-is leaves less than ONE column's minimum for two columns");

            show(WIDTH, HEIGHT, 0.95, 0.95);
            assertEveryColumnHasItsMinimum("stored 0.95 / 0.95");
            assertEquals(GameScreen.COLUMN_MIN_WIDTH, onScreen(GameScreen.NAME_COMMLOG).width,
                    "the comm log is held at exactly its minimum");
            assertEquals(GameScreen.COLUMN_MIN_WIDTH, onScreen(GameScreen.NAME_CHAT).width,
                    "and so is the chat, the left column having taken all it may");

            show(WIDTH, HEIGHT, 0.01, 0.01);
            assertEveryColumnHasItsMinimum("stored 0.01 / 0.01");
            assertEquals(GameScreen.LEFT_MIN_WIDTH, onScreen(GameScreen.NAME_PORT).width,
                    "the left column is held at exactly its minimum");
            assertEquals(GameScreen.COLUMN_MIN_WIDTH, onScreen(GameScreen.NAME_CHAT).width);

            show(WIDTH, HEIGHT, Double.NaN, Double.NaN); // a corrupt preference parses to NaN
            assertEveryColumnHasItsMinimum("stored NaN / NaN");
        });
    }

    @Test
    @Timeout(20)
    void applyingProportionsBeforeTheScreenHasASizeDoesNothing() throws Exception {
        // A location computed for a zero-width split pane would be 0, and the split pane would
        // remember it: the left column would open collapsed.
        HeadlessSwing.onEdt(() -> {
            screen.applyDividerProportions(GameScreen.DEFAULT_LEFT_PROPORTION, GameScreen.DEFAULT_CHAT_PROPORTION);

            assertEquals(-1, screen.outerSplitForTest().getDividerLocation(), "no location was set");
            assertEquals(-1, screen.innerSplitForTest().getDividerLocation());

            showAtDefaults(WIDTH, HEIGHT); // and a later, real application works
            assertEveryColumnHasItsMinimum("after the early call");
        });
    }

    // --- minimum widths ---------------------------------------------------------------------------------

    @Test
    @Timeout(20)
    void shrunkToItsMinimumWidthEveryColumnStillHasItsMinimum() throws Exception {
        HeadlessSwing.onEdt(() -> {
            int minimum = screen.minimumColumnsWidth();
            assertEquals(GameScreen.LEFT_MIN_WIDTH + 2 * GameScreen.COLUMN_MIN_WIDTH + 2 * dividerSize(), minimum,
                    "the three minimum widths and the two dividers");

            showAtDefaults(WIDTH, HEIGHT);
            // A split pane re-applies its stored location on every layout until it has been
            // painted once; from then on a resize is shared out by resize weight and the minimum
            // sizes are respected. A real window is painted as soon as it shows.
            HeadlessSwing.paint(screen);

            HeadlessSwing.layOut(screen, minimum, HEIGHT);

            assertEveryColumnHasItsMinimum("shrunk to " + minimum + " px");
            Rectangle port = onScreen(GameScreen.NAME_PORT);
            Rectangle chat = onScreen(GameScreen.NAME_CHAT);
            Rectangle commLog = onScreen(GameScreen.NAME_COMMLOG);
            assertEquals(minimum, port.width + chat.width + commLog.width + 2 * dividerSize(),
                    "the columns still tile the whole width");
            assertEquals(minimum, commLog.x + commLog.width, "nothing is pushed off the right edge");
        });
    }

    @Test
    @Timeout(20)
    void theWindowsMinimumWidthIsWideEnoughForTheColumnsMinimums() throws Exception {
        HeadlessSwing.onEdt(() -> assertTrue(screen.minimumColumnsWidth() + FRAME_SLACK <= MainWindow.MIN_WIDTH,
                "the columns need " + screen.minimumColumnsWidth() + " px plus the frame's borders, but the window "
                        + "may be made as narrow as " + MainWindow.MIN_WIDTH));
    }

    // --- remembering a drag -----------------------------------------------------------------------------

    @Test
    @Timeout(20)
    void aDividerDragIsReportedAndAWindowResizeIsNot() throws Exception {
        HeadlessSwing.onEdt(() -> {
            showAtDefaults(WIDTH, HEIGHT);
            JSplitPane outer = screen.outerSplitForTest();
            JSplitPane inner = screen.innerSplitForTest();
            List<Double> leftReports = new ArrayList<>();
            List<Double> chatReports = new ArrayList<>();
            screen.trackDividerDrags(proportion -> leftReports.add(proportion),
                    proportion -> chatReports.add(proportion));
            assertTrue(leftReports.isEmpty() && chatReports.isEmpty(), "starting to track reports nothing");

            // A drag is the divider's mouse handler calling setDividerLocation at an unchanged size.
            int innerSpan = inner.getWidth() - inner.getDividerSize();
            inner.setDividerLocation(400);
            assertEquals(List.of(), leftReports, "the chat | comm log divider is not the left one");
            assertEquals(1, chatReports.size(), "the drag of the chat | comm log divider is reported");
            assertEquals(ColumnDividers.proportion(400, innerSpan), chatReports.get(0), 1e-9);

            int outerSpan = outer.getWidth() - outer.getDividerSize();
            outer.setDividerLocation(300);
            assertEquals(1, leftReports.size(), "the drag of the left divider is reported");
            assertEquals(ColumnDividers.proportion(300, outerSpan), leftReports.get(0), 1e-9);
            HeadlessSwing.layOut(screen, WIDTH, HEIGHT); // the chat and the comm log take up the change
            assertEquals(1, chatReports.size(), "moving the left divider is not a drag of the other one");

            // A window resize moves both dividers too (by resize weight) — and must report nothing,
            // or the remembered proportions would drift across resize-and-restart cycles.
            HeadlessSwing.paint(screen); // see shrunkToItsMinimumWidth...: resizes are shared out once painted
            int outerBefore = outer.getDividerLocation();
            int innerBefore = inner.getDividerLocation();
            HeadlessSwing.layOut(screen, WIDTH + 220, HEIGHT);
            assertNotEquals(outerBefore, outer.getDividerLocation(), "non-vacuity: the resize moved the left divider");
            assertNotEquals(innerBefore, inner.getDividerLocation(), "non-vacuity: and the other one");
            assertEquals(1, leftReports.size(), "a resize is not a drag (left divider)");
            assertEquals(1, chatReports.size(), "a resize is not a drag (chat | comm log divider)");

            // Re-applying stored proportions is the program, not the player.
            screen.applyDividerProportions(0.30, 0.60);
            assertEquals(1, leftReports.size(), "applying stored proportions is not a drag");
            assertEquals(1, chatReports.size());

            // And tracking goes on: the next real drag at the new size is reported again.
            HeadlessSwing.layOut(screen, WIDTH + 220, HEIGHT);
            outer.setDividerLocation(outer.getDividerLocation() + 40);
            assertEquals(2, leftReports.size(), "a drag after the resize is reported");
            assertEquals(1, chatReports.size());
        });
    }

    @Test
    @Timeout(20)
    void aReportedProportionPutsTheDividerBackWhereItWasDragged() throws Exception {
        // The round trip the preferences make: drag -> stored proportion -> next launch.
        HeadlessSwing.onEdt(() -> {
            showAtDefaults(WIDTH, HEIGHT);
            List<Double> leftReports = new ArrayList<>();
            List<Double> chatReports = new ArrayList<>();
            screen.trackDividerDrags(proportion -> leftReports.add(proportion),
                    proportion -> chatReports.add(proportion));

            screen.innerSplitForTest().setDividerLocation(430);
            screen.outerSplitForTest().setDividerLocation(320);
            HeadlessSwing.layOut(screen, WIDTH, HEIGHT);
            int draggedLeft = onScreen(GameScreen.NAME_PORT).width;
            assertEquals(320, draggedLeft, "fixture: the drag took effect");

            show(WIDTH, HEIGHT, leftReports.get(leftReports.size() - 1), chatReports.get(chatReports.size() - 1));

            assertEquals(draggedLeft, onScreen(GameScreen.NAME_PORT).width, 1.0, "the left divider comes back");
            assertEveryColumnHasItsMinimum("after the round trip");
        });
    }
}
