package com.airsocket.logging;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
  Zero-dependency structured logger for AirSocket using pure java.base.
  Features thread-safe level filtering, timestamping, thread-tagging,
  and Transfer ID MDC correlation.
 */
public final class Logger
{
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<String, Logger> LOGGERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<UUID> CURRENT_TRANSFER_ID = new ThreadLocal<>();

    private static volatile LogLevel globalLevel = LogLevel.fromString(
        System.getProperty("airsocket.log.level", "INFO"), LogLevel.INFO
    );

    private static volatile boolean colorsEnabled = !"true".equalsIgnoreCase(System.getenv("NO_COLOR"))
        && !"false".equalsIgnoreCase(System.getProperty("airsocket.log.colors", "true"));

    // ANSI Colors
    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String BLUE = "\u001B[34m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";

    private final String name;

    private Logger(String name)
    {
        this.name = name;
    }

    public static Logger getLogger(Class<?> clazz)
    {
        return getLogger(clazz.getSimpleName());
    }

    public static Logger getLogger(String name)
    {
        return LOGGERS.computeIfAbsent(name, Logger::new);
    }

    public static void setGlobalLevel(LogLevel level)
    {
        if (level != null)
        {
            globalLevel = level;
        }
    }

    public static LogLevel getGlobalLevel()
    {
        return globalLevel;
    }

    public static void setColorsEnabled(boolean enabled)
    {
        colorsEnabled = enabled;
    }

    public static boolean isColorsEnabled()
    {
        return colorsEnabled;
    }

    public static void setTransferId(UUID transferId)
    {
        if (transferId != null)
        {
            CURRENT_TRANSFER_ID.set(transferId);
        }
        else
        {
            CURRENT_TRANSFER_ID.remove();
        }
    }

    public static void clearTransferId()
    {
        CURRENT_TRANSFER_ID.remove();
    }

    public static UUID getTransferId()
    {
        return CURRENT_TRANSFER_ID.get();
    }

    public boolean isTraceEnabled()
    {
        return globalLevel.severity() <= LogLevel.TRACE.severity();
    }

    public boolean isDebugEnabled()
    {
        return globalLevel.severity() <= LogLevel.DEBUG.severity();
    }

    public boolean isInfoEnabled()
    {
        return globalLevel.severity() <= LogLevel.INFO.severity();
    }

    public boolean isWarnEnabled()
    {
        return globalLevel.severity() <= LogLevel.WARN.severity();
    }

    public boolean isErrorEnabled()
    {
        return globalLevel.severity() <= LogLevel.ERROR.severity();
    }

    public void trace(String format, Object... args)
    {
        log(LogLevel.TRACE, null, format, args);
    }

    public void debug(String format, Object... args)
    {
        log(LogLevel.DEBUG, null, format, args);
    }

    public void info(String format, Object... args)
    {
        log(LogLevel.INFO, null, format, args);
    }

    public void warn(String format, Object... args)
    {
        log(LogLevel.WARN, null, format, args);
    }

    public void warn(Throwable throwable, String format, Object... args)
    {
        log(LogLevel.WARN, throwable, format, args);
    }

    public void error(String format, Object... args)
    {
        log(LogLevel.ERROR, null, format, args);
    }

    public void error(Throwable throwable, String format, Object... args)
    {
        log(LogLevel.ERROR, throwable, format, args);
    }

    private void log(LogLevel level, Throwable throwable, String format, Object... args)
    {
        if (globalLevel == LogLevel.OFF || level.severity() < globalLevel.severity())
        {
            return;
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String threadName = Thread.currentThread().getName();
        UUID transferId = CURRENT_TRANSFER_ID.get();
        String message = (args != null && args.length > 0) ? String.format(format, args) : format;

        StringBuilder sb = new StringBuilder(128);

        if (colorsEnabled)
        {
            sb.append(GRAY).append(timestamp).append(RESET).append(" ");
            sb.append(getColor(level)).append(String.format("%-5s", level.name())).append(RESET).append(" ");
            sb.append(GRAY).append("[").append(threadName).append("]").append(RESET).append(" ");
            if (transferId != null)
            {
                sb.append(CYAN).append("[").append(transferId).append("]").append(RESET).append(" ");
            }
            sb.append(BLUE).append(name).append(":").append(RESET).append(" ");
            sb.append(message);
        }
        else
        {
            sb.append(timestamp).append(" ");
            sb.append(String.format("%-5s", level.name())).append(" ");
            sb.append("[").append(threadName).append("] ");
            if (transferId != null)
            {
                sb.append("[").append(transferId).append("] ");
            }
            sb.append(name).append(": ");
            sb.append(message);
        }

        PrintStream stream = (level == LogLevel.ERROR || level == LogLevel.WARN) ? System.err : System.out;
        stream.println(sb.toString());

        if (throwable != null)
        {
            throwable.printStackTrace(stream);
        }
    }

    private static String getColor(LogLevel level)
    {
        return switch (level)
        {
            case TRACE -> CYAN;
            case DEBUG -> BLUE;
            case INFO -> GREEN;
            case WARN -> YELLOW;
            case ERROR -> RED;
            case OFF -> RESET;
        };
    }
}
