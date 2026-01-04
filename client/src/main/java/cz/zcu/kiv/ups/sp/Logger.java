package cz.zcu.kiv.ups.sp;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Logger {

    public enum Level {
        INFO,
        WARNING,
        ERROR
    }

    private static final Logger instance = new Logger();

    private PrintWriter logFile;
    private Logger() {
    }

    public static Logger getInstance() {
        return instance;
    }

    public void log(Level level, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String fullMessage = "[" + timestamp + "] [" + level.name() + "] " + message;

        if (level == Level.ERROR) {
            System.err.println(fullMessage);
        } else {
            System.out.println(fullMessage);
        }

        if (logFile != null) {
            logFile.println(fullMessage);
            logFile.flush();
        }
    }

    public void setLogFile(String filename) {
        try {
            if (logFile != null) {
                logFile.close();
            }
            logFile = new PrintWriter(new FileWriter(filename, false));
        } catch (IOException e) {
            log(Level.ERROR,"Unable to open log file: " + filename);
        }
    }

    public static void info(String message) {
        getInstance().log(Level.INFO, message);
    }

    public static void warning(String message) {
        getInstance().log(Level.WARNING, message);
    }

    public static void error(String message) {
        getInstance().log(Level.ERROR, message);
    }
}
