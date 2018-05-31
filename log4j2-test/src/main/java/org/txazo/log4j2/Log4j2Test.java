package org.txazo.log4j2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;

public class Log4j2Test {

    private static final Logger LOGGER = LogManager.getLogger(Log4j2Test.class);

    @Test
    public void test() {
        LOGGER.debug("debug");
        LOGGER.warn("warn");
        LOGGER.info("info");
        LOGGER.error("error");
    }

}
