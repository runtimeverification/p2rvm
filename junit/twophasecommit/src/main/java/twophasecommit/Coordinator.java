/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved. */
package twophasecommit;

import java.util.*;

public class Coordinator {

    private List<Participant> participants = new ArrayList<>();

    public void addParticipant(Participant p) {
        participants.add(p);
    }

    public void commit(boolean isBuggy) {
        try {
            System.out.println("[System] Prepare Phase.");
            preparePhase(isBuggy);
        } catch (RollbackException e) {
            System.out.println("[System] Rollback Phase.");
            rollbackPhase();
            return;
        }

        System.out.println("[System] Commit Phase.");
        commitPhase();
    }

    private void preparePhase(boolean isBuggy) throws RollbackException {
        for (Participant p : participants) {
            if(!p.prepare()) {
                if (!isBuggy) {
                    throw new RollbackException(String.format("machine %d prepare failed.", p.machineId));
                }
            }
        }
    }

    private void rollbackPhase() {
        for (Participant p : participants) {
            p.rollback();
        }
    }

    private void commitPhase() {
        for (Participant p : participants) {
            p.commit();
        }
    }

}
