package org.txazo.log4j2.extend;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Plugin(name = "FileAppender", category = "Core", elementType = "appender", printObject = true)
public class FileAppender extends AbstractAppender {

    private final String filePath;

    protected FileAppender(String name, Filter filter, Layout<? extends Serializable> layout, String filePath) {
        super(name, filter, layout);
        this.filePath = filePath;
    }

    @PluginFactory
    public static FileAppender createAppender(@PluginAttribute("name") String name,
                                              @PluginElement("Filter") Filter filter,
                                              @PluginElement("Layout") Layout layout,
                                              @PluginAttribute("filePath") String filePath) {
        if (name == null) {
            LOGGER.error("Name for FileAppender is required");
            return null;
        }

        if (filePath == null) {
            LOGGER.error("FilePath for FileAppender is required");
            return null;
        }

        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        return new FileAppender(name, filter, layout, filePath);
    }

    @Override
    public void append(LogEvent event) {
        final byte[] bytes = getLayout().toByteArray(event);
        try {
            Files.write(Paths.get(filePath), bytes, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("write file exception", e);
        }
    }

}
