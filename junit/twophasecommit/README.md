To programmatically enable/disable monitors during testing:
1. Use `--controlAPI` flag when invoking the rv-monitor (see the command [here](https://github.com/runtimeverification/p2rvm/blob/a3f86a9402c4c348d5f84b7e84dc09545bebeb1f/twophasecommit/gen_monitor.py#L52)). The generated code provides 3 extra APIs: `enable()`, `disable()` and `resetMonitor()` respectively.
2. By default, the monitor is disabled. To enable the monitor, `enable()` function should be called at the beginning of the test function (see [here](https://github.com/runtimeverification/p2rvm/blob/c4de5e3548f66e866057462bfa5c64fa511e7743/twophasecommit/src/test/java/twophasecommit/TwoPhaseCommitTest.java#L13-L14) for exmaple).
3. At the end of the test function, `resetMonitor()` function should be called (see [here](https://github.com/runtimeverification/p2rvm/blob/c4de5e3548f66e866057462bfa5c64fa511e7743/twophasecommit/src/test/java/twophasecommit/TwoPhaseCommitTest.java#L19-L20)). `resetMonitor()` will first disable the monitor and then clear the state of the internal data structure so that different tests will not interfere with each other (Note that all the tests share a single monitor).

In this example, we have 2 almost identical specification: `twoPhaseCommit.p` and `twoPhaseCommit2.p` in the `monitor` folder. We only modify the print messages in the specifications.

Run the following command:

```
$ mvn clean test -Dtest=TwoPhaseCommitTest
```
 
The following lines will be printed to the command line:

```
=== testPrepareFailure ===
[System] Prepare Phase.
[System] Rollback Phase.
[Monitor] RolledBack.
[Monitor2] RolledBack.
=== testPrepareSuccess ===
[System] Prepare Phase.
[System] Commit Phase.
[Monitor] Committed.
[Monitor2] Committed.
```

It shows that the two monitors are enabled for both test cases.

If we comment out the `twoPhaseCommit2RuntimeMonitor.enable();` in the `testPrepareSuccess()` function,
the following lines will be printed to the command line:

```
=== testPrepareFailure ===
[System] Prepare Phase.
[System] Rollback Phase.
[Monitor] RolledBack.
[Monitor2] RolledBack.
=== testPrepareSuccess ===
[System] Prepare Phase.
[System] Commit Phase.
[Monitor] Committed.
```

Note that `[Monitor2] Committed.` is not printed to the command line, which means that monitor for `twoPhaseCommit2.p` is disabled for the `testPrepareSuccess()` function.

If we enable the monitor for both test cases and comment out `resetMonitor()` functions for both test cases,
the following lines will be printed:

```
=== testPrepareFailure ===
[System] Prepare Phase.
[System] Rollback Phase.
[Monitor] RolledBack.
[Monitor2] RolledBack.
=== testPrepareSuccess ===
No event handler for state 'Rollback' and event 'addParticipant'.
No event handler for state 'Rollback\_1' and event 'addParticipant'.
No event handler for state 'Rollback' and event 'addParticipant'.
No event handler for state 'Rollback\_1' and event 'addParticipant'.
No event handler for state 'Rollback' and event 'startTx'.
No event handler for state 'Rollback\_1' and event 'startTx'.
[System] Prepare Phase.
[System] Commit Phase.
No event handler for state 'Rollback' and event 'commitSuccess'.
No event handler for state 'Rollback\_1' and event 'commitSuccess'.
No event handler for state 'Rollback' and event 'commitSuccess'.
No event handler for state 'Rollback\_1' and event 'commitSuccess'.
[Monitor] RolledBack.
[Monitor2] RolledBack.

```

The monitor states are messed up because the two monitors are not reset at the end of each test function.
