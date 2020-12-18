We give step by step instructions on how to integrate the monitor framework with the maven project here.

1. Follow the [Prerequisite](https://github.com/runtimeverification/p2rvm#prerequisite) section.
1. Create a `monitor` folder and place the p specification in the `monitor` folder.
1. In the `pom.xml`, add the following content:
    - In the `dependencies` section, add
      ``` 
        <dependency>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>aspectj-maven-plugin</artifactId>
            <version>1.11</version>
        </dependency>
        <dependency>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>build-helper-maven-plugin</artifactId>
            <version>3.0.0</version>
        </dependency>
        <dependency>
            <groupId>org.aspectj</groupId>
            <artifactId>aspectjrt</artifactId>
            <version>1.8.13</version>
        </dependency>
        <dependency>
            <groupId>com.runtimeverification.rvmonitor</groupId>
            <artifactId>rv-monitor-rt</artifactId>
            <version>1.4-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>1.6.0</version>
        </dependency>
        <dependency>
            <groupId>p.runtime</groupId>
            <artifactId>p-runtime</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
      ```
    - In the `plugins` section, add
      ```
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>1.6.0</version>
            <executions>
                <execution>
                    <id>generate-monitor-sources</id>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>exec</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <workingDirectory>${project.basedir}</workingDirectory>
                <executable>python</executable>
                <arguments>
                    <argument>gen_monitor.py</argument>
                </arguments>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>build-helper-maven-plugin</artifactId>
            <version>3.0.0</version>
            <executions>
                <execution>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>add-source</goal>
                    </goals>
                    <configuration>
                        <sources>
                            <source>${project.build.directory}/generated-sources</source>
                        </sources>
                    </configuration>
                </execution>
            </executions>
            <dependencies>
                <dependency>
                    <groupId>org.aspectj</groupId>
                    <artifactId>aspectjtools</artifactId>
                    <version>${aspectj.version}</version>
                </dependency>
                <dependency>
                    <groupId>org.aspectj</groupId>
                    <artifactId>aspectjweaver</artifactId>
                    <version>${aspectj.version}</version>
                </dependency>
            </dependencies>
        </plugin>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>aspectj-maven-plugin</artifactId>
            <version>1.11</version>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>test-compile</goal>
                    </goals>
                    <configuration>
                        <showWeaveInfo>true</showWeaveInfo>
                        <verbose>true</verbose>
                        <source>1.8</source>
                        <target>1.8</target>
                        <complianceLevel>1.8</complianceLevel>
                    </configuration>
                </execution>
            </executions>
        </plugin>
      ```
    - Note that we use `exec-maven-plugin` plugin to generate the monitor code and use `build-helper-maven-plugin` to add the generated code to the source. `aspectj-maven-plugin` is responsible for instrumentation.

1. `gen_monitor.py` is invoked by `exec-maven-plugin` plugin. The script does the following things step by step.
    - Run PCompiler on the `getConsistency.p` file.
      ```
      $ pc monitor/getConsistency.p -g:RVM -o:monitor/generated
      ```
      After running the command, four files will be generated in the `monitor/generated` folder.
      1. `getConsistencyMonitorAspect.aj` is an instrumentation(AspectJ) template file. Developers need to manually fill in the file. The example code can be found [here](https://github.com/runtimeverification/p2rvm/blob/master/getconsistency/monitor/ajcode.txt). For exmaple, [L2-L10](https://github.com/runtimeverification/p2rvm/blob/master/getconsistency/monitor/ajcode.txt#L2-L10) means that after the execution of `Database.getReq` function, P event `eGetReq` is fired and the corresponding event handler `getConsistencyRuntimeMonitor.getConsistency_eGetReqEvent` is called. The code extracts information from the arugments of the function and constructs a `NamedTuple` object. The comments in the template file list all the event handler signatures. For more information about AspectJ, please refer to the [documentation](https://www.eclipse.org/aspectj/doc/released/progguide/index.html).
      2. `getConsistency.rvm` encodes the propery specified in the `getConsistency.p`. It is the input to the rv-monitor tool.
      3. `Events.java` and `StateBase.java` are the generated java classes.
    - Run Rv-monitor on the `getConsistency.rvm` file.
      ```
      $ rv-monitor getConsistency.rvm -merge
      ```
      The command will generate `getConsistencyRuntimeMonitor` class which holds the implementations of all the event handlers listed in the comments of the `getConsistencyMonitorAspect.aj` file.
1. Add filled `getConsistencyMonitorAspect.aj`, `Events.java`, `StateBase.java` and `getConsistencyRuntimeMonitor.java` to the source directory. This can be done automatically by the `build-helper-maven-plugin` plugin.
1. Compile the project with aspectJ compiler and run the tests.
