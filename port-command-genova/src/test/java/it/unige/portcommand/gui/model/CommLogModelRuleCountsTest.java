package it.unige.portcommand.gui.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import it.unige.portcommand.gui.LogArchiver;
import it.unige.portcommand.gui.events.CommLogEvent;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** S19 (playtest 5, PT43): the comm log counts the rules its messages cite, for this game. */
class CommLogModelRuleCountsTest {

    private static CommLogEvent row(long t, String paraphrase) {
        return new CommLogEvent(t, "harbour_master", List.of("vessel_c501"), ACLMessage.ACCEPT_PROPOSAL,
                paraphrase, "berth-vessel_c501", "port_command_v1", null, null);
    }

    @Test
    void everyMessageThisGameCountsEvenOnceTheLogHasLetItGo(@TempDir Path tempDir) {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        for (int i = 0; i < 600; i++) { // past the 500 rows the log keeps
            model.add(row(i, "berth_1 granted (compatible [R7])"));
        }
        model.add(row(600, "held [R15,R19]"));

        assertEquals(Map.of("R7", 600, "R15", 1, "R19", 1), model.ruleCitations());
    }

    @Test
    void aNewWorldStartsItsCountsAtZero(@TempDir Path tempDir) {
        CommLogModel model = new CommLogModel(new LogArchiver(tempDir));
        model.add(row(1, "granted [R7]"));

        model.clear();

        assertEquals(Map.of(), model.ruleCitations());
    }
}
