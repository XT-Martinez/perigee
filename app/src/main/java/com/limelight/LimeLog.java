package com.limelight;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class LimeLog {
    private static final Logger LOGGER = BuildConfig.LOG_ENABLED
            ? Logger.getLogger(LimeLog.class.getName()) : null;

    public static void info(String msg) {
        if (BuildConfig.LOG_ENABLED) LOGGER.info(msg);
    }
    
    public static void warning(String msg) {
        if (BuildConfig.LOG_ENABLED) LOGGER.warning(msg);
    }
    
    public static void severe(String msg) {
        if (BuildConfig.LOG_ENABLED) LOGGER.severe(msg);
    }
    
    public static void setFileHandler(String fileName) throws IOException {
        if (BuildConfig.LOG_ENABLED) LOGGER.addHandler(new FileHandler(fileName));
    }
}
