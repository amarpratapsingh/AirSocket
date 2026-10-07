package com.airsocket.logging;

/**
 * Standard log levels for AirSocket.
 */
public enum LogLevel
{
    TRACE(1),
    DEBUG(2),
    INFO(3),
    WARN(4),
    ERROR(5),
    OFF(6);

    private final int severity;

    LogLevel(int severity)
    {
        this.severity = severity;
    }

    public int severity()
    {
        return severity;
    }

    public boolean isEnabled(LogLevel targetLevel)
    {
        return this != OFF && this.severity >= targetLevel.severity;
    }

    public static LogLevel fromString(String str, LogLevel defaultLevel)
    {
        if (str == null || str.isBlank())
        {
            return defaultLevel;
        }
        try
        {
            return LogLevel.valueOf(str.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return defaultLevel;
        }
    }
}
