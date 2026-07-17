package it.unige.portcommand.gui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.WindowConstants;

import it.unige.portcommand.artifacts.PortStateArtifact;
import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.nlp.ChatInputProcessor;
import it.unige.portcommand.util.EventBus;

/**
 * The Swing shell (task 17): a {@code JFrame} with five panel slots laid out
 * per planning/17 §Step 17.3 — HUD across the top, Map/Chat/CommLog sharing
 * the middle row (~50/25/25 width split), a notification strip along the
 * bottom. Every panel is a placeholder; real content arrives in tasks 18-21.
 * {@code GridBagLayout} is pinned (planning/17: "MigLayout ... keep
 * GridBagLayout").
 *
 * <p><b>Single EDT rule.</b> This class and every panel it holds must only be
 * constructed and mutated on the EDT — {@code Main} constructs and shows it
 * via {@code SwingUtilities.invokeLater} (behind the {@code --gui} flag;
 * {@code gradlew run} stays headless without it).
 *
 * <p><b>Agents never reach this class.</b> The only channel from agents to the
 * GUI is {@link EventBus} (INVARIANTS.md: "GUI ↔ agents only through
 * EventBus"); nothing here is ever called directly from an agent or behaviour
 * thread, and nothing under {@code agents/} or {@code behaviours/} may import
 * {@code java.awt}/{@code javax.swing} (grep-gated, planning/17).
 */
public final class MainWindow extends JFrame {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 800;

    /**
     * @param eventBus          the shared bus every panel subscribes through
     * @param portStateArtifact the shared berth/tug state {@code MapPanel} (task 18) reads and
     *                          subscribes to — the only panel that needs it
     * @param chatInputProcessor the NLP pipeline seam (task 19) {@code ChatPanel} calls on Send —
     *                          a functional interface rather than the concrete {@code NLPPipeline}
     *                          so this class stays decoupled from the {@code nlp} package's
     *                          construction details (mirrors the {@code onClose Runnable} seam)
     * @param onClose           clean-shutdown hook run before the window disposes and the JVM
     *                          exits (wired to {@code JadeBootstrap::shutdown} in {@code Main})
     *                          — kept as a {@code Runnable} rather than a concrete bootstrap type
     *                          so this class has no dependency on the {@code bootstrap} package.
     */
    public MainWindow(EventBus eventBus, PortStateArtifact portStateArtifact,
                      ChatInputProcessor chatInputProcessor, Runnable onClose) {
        super("Port Command Genova");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); // we drive shutdown ourselves
        setJMenuBar(buildMenuBar(onClose));
        layOutPanels(eventBus, portStateArtifact, chatInputProcessor);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownAndExit(onClose);
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // Fires exactly once, the first time this window is made visible — the
                // natural "first paint has happened" signal (Window API contract), rather
                // than guessing at invokeLater ordering.
                eventBus.publish(new GuiReadyEvent());
            }
        });
        setMinimumSize(new Dimension(800, 600));
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setLocationRelativeTo(null);
    }

    private void layOutPanels(EventBus eventBus, PortStateArtifact portStateArtifact,
                              ChatInputProcessor chatInputProcessor) {
        setLayout(new GridBagLayout());
        add(new HUDPanel(eventBus), slot(0, 0, 3, 1.0, 0.0));
        add(new MapPanel(eventBus, portStateArtifact), slot(0, 1, 1, 0.5, 1.0));
        add(new ChatPanel(eventBus, chatInputProcessor), slot(1, 1, 1, 0.25, 1.0));
        add(new CommLogPanel(eventBus), slot(2, 1, 1, 0.25, 1.0));
        add(new NotificationStrip(eventBus), slot(0, 2, 3, 1.0, 0.0));
    }

    private static GridBagConstraints slot(int gridx, int gridy, int gridwidth, double weightx, double weighty) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = gridx;
        c.gridy = gridy;
        c.gridwidth = gridwidth;
        c.weightx = weightx;
        c.weighty = weighty;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(1, 1, 1, 1);
        return c;
    }

    private JMenuBar buildMenuBar(Runnable onClose) {
        JMenuBar bar = new JMenuBar();
        JMenu game = new JMenu("Game");
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> shutdownAndExit(onClose));
        game.add(exit);
        bar.add(game);
        return bar;
    }

    private void shutdownAndExit(Runnable onClose) {
        onClose.run();
        dispose();
        System.exit(0);
    }
}
