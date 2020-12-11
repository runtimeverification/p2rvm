## Logging

```
$ mvn clean test -Dtest=GetInconsistent2Test
```

This command will run the test and log the events to the file `getConsistency.log` in the current directory.
The log is in the JSON format. An example snippet is shown below:

```
"@class":"mop.PEvent","arg":{"@class":"p.runtime.values.NamedTuple","fieldNames":["record","rId"],"fieldValues":[{"@class":"p.runtime.values.NamedTuple","fieldNames":["key","val","sqr"],"fieldValues":[{"@class":"p.runtime.values.StringValue","value":"a"},{"@class":"p.runtime.values.IntValue","value":5},{"@class":"p.runtime.values.IntValue","value":0}]},{"@class":"p.runtime.values.IntValue","value":0}]},"eventName":"putReq"}
```

## Monitor the log

```
$ python gen_monitor.py
```

This command will generate and compile a monitor program under `mop` folder.
To run the program, use the command

```
$ java -cp ./:lib/* mop.LogMonitor getConsistency.log
```

This will produce the following output:

```
putReq((record: (key: a, val: 5, sqr: 0), rId: 0))
putReq((record: (key: a, val: 6, sqr: 1), rId: 1))
putRes((res: true, record: (key: a, val: 5, sqr: 0), rId: 0))
putRes((res: true, record: (key: a, val: 6, sqr: 1), rId: 1))
getReq((key: a, rId: 2))
getRes((res: true, record: (key: a, val: 5, sqr: 0), rId: 2))
Exception in thread "main" p.runtime.exceptions.AssertStmtError: Assertion Failed: For key a, expected value of sequencer is >= 1 but got 0. Get is not Consistent!
	at p.runtime.exceptions.Assertion.rvmAssert(Assertion.java:8)
	at mop.getConsistencyRawMonitor.CheckGetRespConsistency(getConsistencyRuntimeMonitor.java:257)
	at mop.getConsistencyRawMonitor$WaitForGetAndPutOperationsState.eGetRespHandler(getConsistencyRuntimeMonitor.java:128)
	at mop.getConsistencyRawMonitor.event_eGetResp(getConsistencyRuntimeMonitor.java:313)
	at mop.getConsistencyRuntimeMonitor.getConsistency_eGetRespEvent(getConsistencyRuntimeMonitor.java:452)
	at mop.LogMonitor.monitorLog(LogMonitor.java:32)
	at mop.LogMonitor.main(LogMonitor.java:44)
```

