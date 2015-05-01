import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

import edu.ucla.sspace.util.*;

import static edu.ucla.sspace.util.LoggerUtil.*;

public class CrownLogger {

    public static final Logger LOGGER =
        Logger.getLogger("crown");
    
    private static final Object[] EMPTY = new Object[0];

    public static final String LOG_FILE_PROPERTY = "crown.logfile";

    static {
        String logFileName = System.getProperty(LOG_FILE_PROPERTY);
        if (logFileName != null) {
            try {
                Handler handler = new FileHandler(logFileName);
                LOGGER.addHandler(handler);
            } catch (IOException ioe) {
                throw new IOError(ioe);
            }
        }
    }

    public static void info(String format, Object... args) {
        LOGGER.log(Level.INFO, String.format(format, args));
    }

    public static void info(String mesg) {
        LOGGER.log(Level.INFO, mesg);
    }

    public static void verbose(String format, Object... args) {
        LOGGER.log(Level.FINE, String.format(format, args));
    }

    public static void verbose(String mesg) {
        LOGGER.log(Level.FINE, mesg);
    }

}
