package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import it.unige.portcommand.gui.model.GameOverModel;
import it.unige.portcommand.harbourmaster.financial.Leaderboard;
import it.unige.portcommand.lifecycle.events.GameOverEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The end-of-run report (task 24). Subscribes to {@link GameOverEvent} — published exactly once
 * per run by {@code GameOverGuard}, AFTER the guard has recorded the score, so the top-5 this
 * dialog renders already includes the run that just ended.
 *
 * <p>Same thin-view contract as {@link EndOfDaySummaryDialog}: every string comes from the
 * headless-tested {@link GameOverModel}; this class cannot run in the test lanes at all
 * (HeadlessException). [Restart] is deliberately absent — restarting means re-running a scenario
 * boot, which is task 23's {@code NewGameDialog}; a dead button would be worse than none.
 * [Close] keeps the window open for a post-mortem look at the map/comm-log (the world is frozen
 * at GAME_OVER); [Exit] leaves the game.
 */
public final class GameOverDialog extends JDialog {

    private final Subscription<GameOverEvent> subscription;
    private final Leaderboard leaderboard;
    private final Runnable onExit;

    public GameOverDialog(Window owner, EventBus eventBus, Leaderboard leaderboard, Runnable onExit) {
        super(owner, "Game Over", ModalityType.APPLICATION_MODAL);
        this.leaderboard = leaderboard;
        this.onExit = onExit;
        this.subscription = eventBus.subscribe(GameOverEvent.class, this::show, DeliveryMode.EDT);
    }

    /** Public so a future scenario/debug path can drive the same rendering the live event does. */
    public void show(GameOverEvent event) {
        GameOverModel model = new GameOverModel(event, leaderboard.top5());
        setTitle(model.title());
        setContentPane(buildContent(model));
        pack();
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    private JPanel buildContent(GameOverModel model) {
        JTextArea report = new JTextArea(String.join("\n", model.lines()));
        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        report.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton close = new JButton("Close");
        close.addActionListener(e -> setVisible(false));
        JButton exit = new JButton("Exit Game");
        exit.addActionListener(e -> onExit.run());
        buttons.add(close);
        buttons.add(exit);

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JScrollPane(report), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        return content;
    }

    @Override
    public void dispose() {
        subscription.cancel();
        super.dispose();
    }
}
