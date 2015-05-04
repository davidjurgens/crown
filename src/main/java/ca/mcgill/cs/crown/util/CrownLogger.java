/* 
 * This source code is subject to the terms of the Creative Commons
 * Attribution-NonCommercial-ShareAlike 4.0 license. If a copy of the BY-NC-SA
 * 4.0 License was not distributed with this file, You can obtain one at
 * https://creativecommons.org/licenses/by-nc-sa/4.0.
*/

package ca.mcgill.cs.crown.util;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

import edu.ucla.sspace.util.*;

import static edu.ucla.sspace.util.LoggerUtil.*;

public class CrownLogger {

    public static final Logger LOGGER =
        Logger.getLogger("ca.mcgill.cs.crown");
    
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

    /**
     * Returns {@code true} if log messages sent at this output level will be
     * shown to the user.
     */
    public static boolean isLoggable(Level outputLevel) {
        return LOGGER.isLoggable(outputLevel);
    }
    
    /**
     * Sets which logging is reported by default during the CROWN build according
     * to the desired level.
     */
    public static void setLevel(Level outputLevel) {
        Logger appRooLogger = Logger.getLogger("ca.mcgill.cs.crown");
        Handler verboseHandler = new ConsoleHandler();
        verboseHandler.setLevel(outputLevel);
        appRooLogger.addHandler(verboseHandler);
        appRooLogger.setLevel(outputLevel);
        appRooLogger.setUseParentHandlers(false);
    }
    
    /**
     * Prints {@link Level#FINER} messages to the CROWN {@link Logger}.
     */
    public static void veryVerbose(String format, Object... args) {
        if (LOGGER.isLoggable(Level.FINER)) {
            StackTraceElement[] callStack = 
                Thread.currentThread().getStackTrace();
            // Index 0 is Thread.getStackTrace()
            // Index 1 is this method
            // Index 2 is the caller
            // Index 3 and beyond we don't care about
            StackTraceElement caller = callStack[2];
            String callingClass = caller.getClassName();
            String callingMethod = caller.getMethodName();
            LOGGER.logp(Level.FINER, callingClass, callingMethod, 
                     String.format(format, args));
        }
    }

    /**
     * Prints {@link Level#FINE} messages to the CROWN {@link Logger}.
     */
    public static void verbose(String format, Object... args) {
        if (LOGGER.isLoggable(Level.FINE)) {
            StackTraceElement[] callStack = 
                Thread.currentThread().getStackTrace();
            // Index 0 is Thread.getStackTrace()
            // Index 1 is this method
            // Index 2 is the caller
            // Index 3 and beyond we don't care about
            StackTraceElement caller = callStack[2];
            String callingClass = caller.getClassName();
            String callingMethod = caller.getMethodName();
            LOGGER.logp(Level.FINE, callingClass, callingMethod, 
                     String.format(format, args));
        }
    }

    /**
     * Prints {@link Level#INFO} messages to the CROWN {@link Logger}.
     */
    public static void info(String format, Object... args) {
        if (LOGGER.isLoggable(Level.INFO)) {
            StackTraceElement[] callStack = 
                Thread.currentThread().getStackTrace();
            // Index 0 is Thread.getStackTrace()
            // Index 1 is this method
            // Index 2 is the caller
            // Index 3 and beyond we don't care about
            StackTraceElement caller = callStack[2];
            String callingClass = caller.getClassName();
            String callingMethod = caller.getMethodName();
            LOGGER.logp(Level.INFO, callingClass, callingMethod, 
                     String.format(format, args));
        }
    }

    /** 
     * Prints {@link Level#WARNING} messages to the CROWN {@link Logger}.
     */
    public static void warning(String format, Object... args) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            StackTraceElement[] callStack = 
                Thread.currentThread().getStackTrace();
            // Index 0 is Thread.getStackTrace()
            // Index 1 is this method
            // Index 2 is the caller
            // Index 3 and beyond we don't care about
            StackTraceElement caller = callStack[2];
            String callingClass = caller.getClassName();
            String callingMethod = caller.getMethodName();
            LOGGER.logp(Level.WARNING, callingClass, callingMethod, 
                     String.format(format, args));
        }
    }

    /** 
     * Prints {@link Level#SEVERE} messages to the CROWN {@link Logger}.
     */
    public static void severe(String format, Object... args) {
        if (LOGGER.isLoggable(Level.SEVERE)) {
            StackTraceElement[] callStack = 
                Thread.currentThread().getStackTrace();
            // Index 0 is Thread.getStackTrace()
            // Index 1 is this method
            // Index 2 is the caller
            // Index 3 and beyond we don't care about
            StackTraceElement caller = callStack[2];
            String callingClass = caller.getClassName();
            String callingMethod = caller.getMethodName();
            LOGGER.logp(Level.SEVERE, callingClass, callingMethod, 
                     String.format(format, args));
        }
    }
}
