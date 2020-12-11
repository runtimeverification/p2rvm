package mop;

import mop.PEventMessage;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

public class RVMLogger {

    private Logger logger = LogManager.getLogger(RVMLogger.class);

    public RVMLogger(String logPath) {
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();

        builder.setStatusLevel(Level.INFO);
        builder.setConfigurationName("DefaultLogger");
        AppenderComponentBuilder fileApp = builder.newAppender("File", "File");
        fileApp.addAttribute("fileName", logPath);
        fileApp.add(builder.newLayout("CustomJsonLayout"));
        builder.add(fileApp);

        RootLoggerComponentBuilder rootLogger = builder.newRootLogger(Level.DEBUG);
        rootLogger.add(builder.newAppenderRef("File"));
        builder.add(rootLogger);

        Configurator.reconfigure(builder.build());
    }

    public void log(PEventMessage msg) {
        logger.info(msg);
    }

}
