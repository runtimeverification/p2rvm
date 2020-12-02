/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved. */
package twophasecommit;

import mop.*;
import org.junit.jupiter.api.Test;

public class TwoPhaseCommitTest {

    @Test
    void testPrepareSuccess() {
        System.out.println("=== testPrepareSuccess ===");
        // Comment out the following line to disable monitor.
        twoPhaseCommitRuntimeMonitor.enable();
        Coordinator co = new Coordinator();
        co.addParticipant(new Participant(0, true));
        co.addParticipant(new Participant(1, true));
        co.commit(false);
        twoPhaseCommitRuntimeMonitor.resetMonitor();
    }

    @Test
    void testPrepareFailure() {
        System.out.println("=== testPrepareFailure ===");
        // Comment out the following line to disable monitor.
        twoPhaseCommitRuntimeMonitor.enable();
        Coordinator co = new Coordinator();
        co.addParticipant(new Participant(0, true));
        co.addParticipant(new Participant(1, false));
        co.commit(false);
        twoPhaseCommitRuntimeMonitor.resetMonitor();
    }

}
