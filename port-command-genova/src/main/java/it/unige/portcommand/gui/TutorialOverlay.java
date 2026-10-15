package it.unige.portcommand.gui;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The five-step tutorial overlay (task 21, planning/21 §21.3; reconciled 2026-07-18). A
 * translucent scrim over the content area that cuts a bright hole around one UI element and shows
 * a caption card explaining it. Mounted in {@code MainWindow}'s {@code JLayeredPane}, sized to the
 * content pane and kept in sync on resize.
 *
 * <p><b>Highlight by component NAME, not pixels</b> (planning risk note): each step names a target
 * ({@code tutorial-port}/{@code -chat}/{@code -commlog}/{@code -hud}); the cutout is recomputed
 * from the live component bounds every paint, so it tracks resizes and never drifts. A target that
 * isn't showing (e.g. minimised, or a Hint button with no open tab) simply yields no cutout — the
 * scrim dims fully and the caption still explains the step. The name constants, and the components
 * that carry them, are {@link GameScreen}'s since S15 (2026-10-12), when the Port table became the
 * first step's target.
 *
 * <p><b>S19 (playtest 5, PT23): a tour at the paused start.</b> The window starts a game paused and
 * opens the tour on it ({@link #startTour}): "Next" shows the next card at once, the last card's
 * "Start playing" resumes the game, and "Skip tutorial" ends the tour (the game stays paused; the
 * top bar's "▶ Resume" starts it). Help → "Show tutorial" pages the same five cards mid-game and
 * ends on "Done", starting nothing. Until S19 the Tutorial scenario's script drove the cards on its
 * own clock (a {@code TutorialStepAdvancedEvent} at 0/200/600/1100/1500 sim-seconds): playtest 5
 * found a card hidden by "Got it", the next one popping up later by itself, and a "×" beside
 * "Skip tutorial". No game event drives a card any more, and the card has no "×". Ending the tour -
 * Skip, Done or Start playing - is reported ({@link #onClosedForGood}) so the window can remember
 * it (S18b, P10). A world swap ({@code GameLoadedEvent}) clears the overlay at once (audit C-13).
 *
 * <p><b>Non-blocking</b> (planning "common mistake"): {@link #contains(int, int)} is solid only over
 * the caption card, so every click OUTSIDE it — including on the highlighted element — passes
 * through to the app beneath. <b>Paint-safe</b>: nothing is drawn until {@link #isReady()} (backed
 * by {@link GuiReadyEvent} — the window is on screen and component bounds are valid), and the two
 * subscriptions are cancelled in {@link #removeNotify()} so a disposed window leaks neither.
 */
public final class TutorialOverlay extends JComponent {

    /** §3.13 canonical step texts — what Help → "Show tutorial" and the first-launch tour say
     * (the Tutorial scenario's tour shows its own five texts, {@code tutorial.json}).
     *
     * <p><b>Step 1 describes the Port table (S15, 2026-10-12)</b>, which replaced the picture of
     * the harbour the step used to point at: what a berth line says (who holds it, until when)
     * and what the lines under the berths count. It says only what the table really shows — the
     * rule this step has been held to since audit C-05 (2026-07-27), when its text promised
     * vessel movements nothing ever drew. It deliberately says nothing about the "Waiting for you"
     * list above the table, and no step text anywhere may: that list's number is the walk-in's
     * time counter, which is never labelled or explained (INVARIANTS). {@code tutorial.json}'s
     * step-1 sentence was reworded in the same session. */
    private static final String[] STEP_TEXT = {
            "This is the Port table — each berth shows the vessel holding it and until when; "
                    + "the lines below count the queue and the tugs.",
            "When a walk-in vessel hails you, its negotiation opens as a chat tab here.",
            "Press the Hint button in a chat tab to ask the Assistant for a recommended counter-offer.",
            "Every agent message appears in the comm log here, colour-coded by FIPA performative.",
            "Your wallet, reputation and the day clock are up here in the HUD — watch them against the daily target.",
    };

    /** Component name each step highlights; steps 2 and 3 both point at the chat area (the Hint
     * button lives inside it, and has no stable single component to name across tabs). */
    private static final String[] STEP_TARGET = {
            GameScreen.NAME_PORT, GameScreen.NAME_CHAT, GameScreen.NAME_CHAT,
            GameScreen.NAME_COMMLOG, GameScreen.NAME_HUD,
    };

    static final int TOTAL_STEPS = 5;

    /** S19 (PT23): the paging button's three labels. */
    static final String NEXT = "Next";
    static final String DONE = "Done";
    static final String START_PLAYING = "Start playing";

    private final transient Container searchRoot;
    private final Subscription<GuiReadyEvent> readySubscription;
    private final Subscription<GameLoadedEvent> loadedSubscription;

    private final JPanel card = new JPanel(new BorderLayout(8, 8));
    private final JLabel caption = new JLabel();
    private final JLabel stepIndicator = new JLabel();
    private final JButton nextButton = new JButton(NEXT);
    /** S18b (PT18): the way out, in words - since S19 (PT23) the only one; the "×" is gone. */
    private final JButton skipButton = new JButton("Skip tutorial");

    private boolean ready;
    private int currentStep; // 1..TOTAL_STEPS while shown; 0 when never shown
    /** S19 (PT23): the texts of the tour on screen, one per step. */
    private final String[] tourText = STEP_TEXT.clone();
    /** S19 (PT23): what the last card's "Start playing" runs; {@code null} = the last card says Done. */
    private transient Runnable onStartPlaying;
    /** S18b (P10): told when the player ends the tutorial, so the window can remember it. */
    private transient Runnable onClosedForGood = () -> { };

    /**
     * @param eventBus   the shared bus (GuiReady, and the world-loaded marker)
     * @param searchRoot the content pane whose subtree is searched for the named highlight targets
     */
    public TutorialOverlay(EventBus eventBus, Container searchRoot) {
        this.searchRoot = searchRoot;
        setOpaque(false);
        setVisible(false);
        setLayout(null); // the caption card is positioned manually relative to the cutout

        caption.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        stepIndicator.setForeground(new Color(90, 90, 90));
        nextButton.addActionListener(e -> advance());
        skipButton.addActionListener(e -> endTour());
        skipButton.setToolTipText("End the tutorial (reopen it from Help → Show tutorial)");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);
        controls.add(stepIndicator);
        controls.add(skipButton);
        controls.add(nextButton);
        card.setBackground(new Color(250, 250, 250));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 214, 10), 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        card.add(caption, BorderLayout.CENTER);
        card.add(controls, BorderLayout.SOUTH);
        card.setVisible(false);
        add(card);

        this.readySubscription = eventBus.subscribe(GuiReadyEvent.class, e -> {
            ready = true;
            repaint();
        }, DeliveryMode.EDT);
        // Audit C-13 (2026-07-27): the scrim must not outlive the world it explains. Switching
        // Tutorial -> Storm mid-demo (DEMO_SCRIPT Beat 5) otherwise left the tutorial's card and
        // its 60% scrim sitting over the storm. ChatPanel/CommLogPanel already reset on this
        // event; the overlay was one of the three surfaces that did not.
        this.loadedSubscription = eventBus.subscribe(GameLoadedEvent.class, e -> resetForANewGame(),
                DeliveryMode.EDT);
    }

    /** S18b (P10): what to run when the player ends the tutorial (the window remembers it). */
    public void onClosedForGood(Runnable action) {
        this.onClosedForGood = action == null ? () -> { } : action;
    }

    /** Help → "Show tutorial": the five canonical cards, ending on Done - nothing is started. */
    public void startFromBeginning() {
        startTour(List.of(), null);
    }

    /**
     * S19 (playtest 5, PT23): the tour, from its first card.
     *
     * @param texts          the cards' texts in step order; a missing or blank one is the canonical
     *                       text of its step
     * @param onStartPlaying what the last card's "Start playing" runs (the window resumes the
     *                       paused game); {@code null} and the last card says "Done" and runs nothing
     */
    public void startTour(List<String> texts, Runnable onStartPlaying) {
        for (int i = 0; i < TOTAL_STEPS; i++) {
            String text = texts != null && i < texts.size() ? texts.get(i) : null;
            tourText[i] = text == null || text.isBlank() ? STEP_TEXT[i] : text;
        }
        this.onStartPlaying = onStartPlaying;
        showStep(1);
    }

    /** True once the window is on screen ({@link GuiReadyEvent}) OR the overlay is displayable — so
     * nothing paints against not-yet-valid component bounds. */
    public boolean isReady() {
        return ready || isDisplayable();
    }

    private void showStep(int step) {
        currentStep = step;
        caption.setText("<html><div style='width:320px'>" + escape(tourText[step - 1]) + "</div></html>");
        stepIndicator.setText("Step " + step + " of " + TOTAL_STEPS);
        nextButton.setText(step < TOTAL_STEPS ? NEXT : onStartPlaying != null ? START_PLAYING : DONE);
        card.setVisible(true);
        setVisible(true);
        layoutCard();
        repaint();
    }

    /** Next shows the next card at once; on the last card, Done or Start playing ends the tour. */
    private void advance() {
        if (currentStep == 0) {
            return;
        }
        if (currentStep < TOTAL_STEPS) {
            showStep(currentStep + 1);
            return;
        }
        Runnable start = onStartPlaying;
        endTour();
        if (start != null) {
            start.run();
        }
    }

    private void hideCard() {
        card.setVisible(false);
        setVisible(false);
        currentStep = 0;
    }

    /** Skip, Done, or Start playing: the tour is over, and the window is told so it can remember it. */
    private void endTour() {
        hideCard();
        onStartPlaying = null;
        onClosedForGood.run();
    }

    /** A new world (a new game or a load): nothing of the last one's tour stays on screen. */
    private void resetForANewGame() {
        hideCard();
        onStartPlaying = null;
    }

    /**
     * Package-private probe for the headless tests: the overlay is "up" when a step is showing.
     *
     * <p><b>This is the card's state, not the scrim.</b> {@link #paintComponent} additionally gates
     * on {@link #isReady()} and a non-zero size, and headlessly {@code isReady()} is false and the
     * size is 0×0, so nothing paints in the tests at all. Whether the scrim is visible on a real
     * window belongs to the GUI walk. (Adversarial review, 2026-07-27.)
     */
    boolean isShowingAStep() {
        return currentStep != 0 && isVisible();
    }

    /** Package-private test seam for the paging button, so a headless test can page the tour
     * without synthesising an {@code ActionEvent} on a button that is never realized. */
    void advanceForTest() {
        advance();
    }

    /** S18b: the "Skip tutorial" button, as a click would press it. */
    void skipForTest() {
        skipButton.doClick();
    }

    /** S18b: the caption on the card now (HTML). */
    String captionForTest() {
        return caption.getText();
    }

    /** S18b: the label of the paging button now (Next / Done / Start playing). */
    String nextLabelForTest() {
        return nextButton.getText();
    }

    /** Test-only (S19): the card's buttons, left to right. */
    List<String> buttonLabelsForTest() {
        List<String> labels = new ArrayList<>();
        collectButtons(card, labels);
        return labels;
    }

    private static void collectButtons(Container root, List<String> into) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button) {
                into.add(button.getText());
            } else if (child instanceof Container nested) {
                collectButtons(nested, into);
            }
        }
    }

    /** Test-only (S15): the canonical text of step {@code step} (1..{@link #TOTAL_STEPS}) — what
     * Help → "Show tutorial" and the first-launch cards say. */
    static String canonicalStepTextForTest(int step) {
        return STEP_TEXT[step - 1];
    }

    /** Test-only (S15): the component name step {@code step} (1..{@link #TOTAL_STEPS}) highlights,
     * so a test can check that the real screen has a component by that name. */
    static String stepTargetNameForTest(int step) {
        return STEP_TARGET[step - 1];
    }

    /**
     * Click-through: solid ONLY over the caption card (so its buttons work); every other point —
     * including inside the highlight, so the player can actually click the element being pointed
     * at — reports false and the event falls through to the app beneath.
     */
    @Override
    public boolean contains(int x, int y) {
        return isReady() && isVisible() && card.isVisible() && card.getBounds().contains(x, y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (!isReady() || currentStep == 0 || w <= 0 || h <= 0) {
            return; // never paint before the window is up / while dismissed / at zero size
        }
        // Dim (SRC_OVER 0.6) then punch the highlight hole (DST_OUT) — planning/21 §21.3. Composed
        // in an explicit ARGB buffer so DST_OUT has a real alpha channel to clear, rather than
        // depending on the (non-opaque, layered-pane) component's own buffer being alpha-backed.
        BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = layer.createGraphics();
        lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        lg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
        lg.setColor(Color.BLACK);
        lg.fillRect(0, 0, w, h);
        Rectangle cut = currentCutout();
        if (cut != null) {
            lg.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_OUT));
            lg.setColor(Color.WHITE); // opaque source → clears the dim inside the hole
            lg.fillRoundRect(cut.x, cut.y, cut.width, cut.height, 14, 14);
        }
        lg.dispose();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(layer, 0, 0, null);
        if (cut != null) {
            g2.setColor(new Color(255, 214, 10));
            g2.setStroke(new java.awt.BasicStroke(3f));
            g2.drawRoundRect(cut.x, cut.y, cut.width, cut.height, 14, 14);
        }
        g2.dispose();
    }

    /** The highlight rect in overlay-local coordinates, or null when the step's target isn't
     * on screen. Recomputed every paint so a resize/relayout can never leave it stale. */
    private Rectangle currentCutout() {
        if (currentStep == 0) {
            return null;
        }
        Component target = findByName(searchRoot, STEP_TARGET[currentStep - 1]);
        if (target == null || !target.isShowing() || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return null;
        }
        Rectangle r = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), this);
        r.grow(3, 3);
        return r.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
    }

    /** Positions the caption card clear of the cutout: opposite half from the highlight (top if the
     * highlight is low, bottom if high), horizontally centred and clamped on-screen. */
    private void layoutCard() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Dimension pref = card.getPreferredSize();
        int cardW = Math.min(Math.max(pref.width, 300), Math.max(200, w - 40));
        int cardH = Math.min(Math.max(pref.height, 90), Math.max(90, h - 40));
        int x = Math.max(20, (w - cardW) / 2);
        Rectangle cut = currentCutout();
        boolean topBar = currentStep > 0 && GameScreen.NAME_HUD.equals(STEP_TARGET[currentStep - 1]);
        card.setBounds(x, cardY(cut, topBar, h, cardH), cardW, cardH);
    }

    /**
     * Where the card goes. Clear of the highlight: the opposite half from it - except for the top
     * bar, where the card sits just UNDER the bar (S18b, PT18: at the bottom of the window it was
     * far from what it explains). Pure, so the placement is testable without a screen.
     */
    static int cardY(Rectangle cut, boolean targetIsTopBar, int overlayHeight, int cardHeight) {
        if (targetIsTopBar && cut != null) {
            return Math.min(cut.y + cut.height + 8, Math.max(20, overlayHeight - cardHeight - 24));
        }
        boolean highlightInTopHalf = cut != null && cut.y + cut.height / 2 < overlayHeight / 2;
        return highlightInTopHalf ? Math.max(20, overlayHeight - cardHeight - 24) : 24;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        if (card.isVisible()) {
            layoutCard();
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        readySubscription.cancel();
        loadedSubscription.cancel();
    }

    private static Component findByName(Container root, String name) {
        if (root == null) {
            return null;
        }
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                Component found = findByName(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
