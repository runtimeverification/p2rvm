To programmatically enable/disable monitors during testing:
1. Use `junit` branch of the rv-monitor
2. Use `--controlAPI` flag when invoking the rv-monitor (see the command [here](https://github.com/runtimeverification/p2rvm/blob/a3f86a9402c4c348d5f84b7e84dc09545bebeb1f/twophasecommit/gen_monitor.py#L52)). The generated code provides 3 extra APIs: `enable()`, `disable()` and `resetMonitor()` respectively.
3. By default, the monitor is disabled. To enable the monitor, `enable()` function should be called at the beginning of the test function (see [here](https://github.com/runtimeverification/p2rvm/blob/c4de5e3548f66e866057462bfa5c64fa511e7743/twophasecommit/src/test/java/twophasecommit/TwoPhaseCommitTest.java#L13) for exmaple).
4. At the end of the test function, `resetMonitor()` function should be called (see [here](https://github.com/runtimeverification/p2rvm/blob/c4de5e3548f66e866057462bfa5c64fa511e7743/twophasecommit/src/test/java/twophasecommit/TwoPhaseCommitTest.java#L18)). `resetMonitor()` will first disable the monitor and then clear the state of the internal data structure so that different tests will not interfere with each other (Note that all the tests share a single monitor).

For example, run the following command:

```
$ mvn clean test -Dtest=TwoPhaseCommitTest
```
 
The following lines will be printed to the command line:

```
=== testPrepareFailure ===
[System] Prepare Phase.
[System] Rollback Phase.
[Monitor] RolledBack.
=== testPrepareSuccess ===
[System] Prepare Phase.
[System] Commit Phase.
[Monitor] Committed.
```

It shows that the monitor is enabled for both test cases.

If we comment out the `twoPhaseCommitRuntimeMonitor.enable();` in the `testPrepareSuccess()` function,
the following lines will be printed to the command line:

```
=== testPrepareFailure ===
[System] Prepare Phase.
[System] Rollback Phase.
[Monitor] RolledBack.
=== testPrepareSuccess ===
[System] Prepare Phase.
[System] Commit Phase.
```

Note that `[Monitor] Committed.` is not printed to the command line.

If we enable the monitor for both test cases and comment out `twoPhaseCommitRuntimeMonitor.resetMonitor();` for both test cases,
the following lines will be printed:

```
=== testPrepareFailure ===
[System] Prepare Phase.
[System] Rollback Phase.
[Monitor] RolledBack.
=== testPrepareSuccess ===
No event handler for state 'Rollback' and event 'addParticipant'.
No event handler for state 'Rollback' and event 'addParticipant'.
No event handler for state 'Rollback' and event 'startTx'.
[System] Prepare Phase.
[System] Commit Phase.
No event handler for state 'Rollback' and event 'commitSuccess'.
No event handler for state 'Rollback' and event 'commitSuccess'.
[Monitor] RolledBack.
```

The monitor state is messed up because the monitor is not reset at the end of each test function.
