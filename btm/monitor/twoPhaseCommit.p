event addParticipant;
event startTx;
event prepareSuccess;
event prepareFailure;
event rollbackSuccess;
event commitSuccess;
event endTx;

spec twoPhaseCommit observes addParticipant, startTx, prepareSuccess, prepareFailure, rollbackSuccess, commitSuccess, endTx {
    var participantNum: int;
    var preparedNum: int;
    var rolledbackNum: int;
    var committedNum: int;

    start state Init {
        entry {
            participantNum = 0;
            preparedNum = 0;
            rolledbackNum = 0;
            committedNum = 0;
        }

        on addParticipant do {
            print "receive addParticipant event";
            participantNum = participantNum + 1;
        }

        on startTx do {
            print "receive startTx event and goto Prepare state";
            goto Prepare;
        }
    }

    state Prepare {
        on prepareSuccess do {
            print "receive prepareSuccess event";
            preparedNum = preparedNum + 1;
            if (preparedNum == participantNum) {
                print "goto Commit state";
                goto Commit;
            }
        }

        on prepareFailure do {
            print "receive prepareFailure event and goto Rollback state";
            goto Rollback;
        }
    }

    state Rollback {
        on prepareSuccess do {
        }

        on prepareFailure do {
        }

        on rollbackSuccess do {
            print "receive rollbackSuccess event";
            rolledbackNum = rolledbackNum + 1;
        }

        on endTx do {
            assert (rolledbackNum == participantNum), "Rollback failed.";
            print "RolledBack";
        }
    }

    state Commit {
        on commitSuccess do {
            print "receive commitSuccess event";
            committedNum = committedNum + 1;
        }

        on endTx do {
            assert (committedNum == participantNum), "Commit failed.";
            print "Committed";
        }
    }
}
