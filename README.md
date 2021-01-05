The repository contains the following example:
- [getconsistency](https://github.com/runtimeverification/p2rvm/tree/master/online/getconsistency) is a mock implemenation of a database and we monitor the `getConsistency` property on it.
- [twophasecommit](https://github.com/runtimeverification/p2rvm/tree/master/online/twophasecommit) is a mock implementation of the two phase commit protocol and we monitor that the implementation conforms to the protocol.
- [btm](https://github.com/runtimeverification/p2rvm/tree/master/online/btm) contains the bitronix implmentation of the two phase commit protocol and we monitor that the implementation conforms to the protocol.

Each example directory is a maven project. In each example directory:
- The `gen_monitor.py` is in charge of generating the instrumentation code and the monitor code.
- The `monitor` folder contains the specification.
- You can run `run_test.py` to run the tests.

## Prerequisite
To download all the dependencies, run
```
$ git submodule update --init --recursive
```

### P Compiler
To install the P compiler, run
```
$ cd ext/P/Bld
$ ./build.sh
```
You can follow the instructions in the command line to add aliases to the bash\_profile after running the build script.

### RV-Monitor
To install the rv-monitor, run
```
$ cd ext/rv-monitor
$ mvn clean install
```

## Integration
For detailed information on how to integrate the monitoring framework with maven, please refer to the [README.md](https://github.com/runtimeverification/p2rvm/blob/master/online/getconsistency/README.md) in the `getconsistency` folder.
