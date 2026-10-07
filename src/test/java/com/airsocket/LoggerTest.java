package com.airsocket;

import com.airsocket.logging.LogLevel;
import com.airsocket.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class LoggerTest
{
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream outStream;
    private ByteArrayOutputStream errStream;

    @BeforeEach
    public void setUp()
    {
        originalOut = System.out;
        originalErr = System.err;
        outStream = new ByteArrayOutputStream();
        errStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outStream, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errStream, true, StandardCharsets.UTF_8));
        Logger.setColorsEnabled(false);
        Logger.setGlobalLevel(LogLevel.DEBUG);
    }

    @AfterEach
    public void tearDown()
    {
        System.setOut(originalOut);
        System.setErr(originalErr);
        Logger.clearTransferId();
        Logger.setGlobalLevel(LogLevel.INFO);
    }

    @Test
    public void testLogLevelHierarchyAndParsing()
    {
        assertTrue(LogLevel.INFO.isEnabled(LogLevel.INFO));
        assertTrue(LogLevel.INFO.isEnabled(LogLevel.DEBUG));
        assertFalse(LogLevel.INFO.isEnabled(LogLevel.WARN));

        assertEquals(LogLevel.DEBUG, LogLevel.fromString("debug", LogLevel.INFO));
        assertEquals(LogLevel.TRACE, LogLevel.fromString("TRACE", LogLevel.INFO));
        assertEquals(LogLevel.INFO, LogLevel.fromString("INVALID", LogLevel.INFO));
        assertEquals(LogLevel.INFO, LogLevel.fromString(null, LogLevel.INFO));
    }

    @Test
    public void testLoggerOutputAndFormatting()
    {
        Logger logger = Logger.getLogger(LoggerTest.class);
        logger.info("Test message %s %d", "arg", 42);

        String out = outStream.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("INFO"), "Output should contain log level");
        assertTrue(out.contains("LoggerTest:"), "Output should contain logger name");
        assertTrue(out.contains("Test message arg 42"), "Output should contain formatted message");
    }

    @Test
    public void testLoggerTransferIdContext()
    {
        Logger logger = Logger.getLogger("TransferTest");
        UUID transferId = UUID.randomUUID();

        Logger.setTransferId(transferId);
        logger.info("Correlated transfer event");
        Logger.clearTransferId();

        String out = outStream.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains(transferId.toString()), "Output must contain the correlated Transfer ID");
    }

    @Test
    public void testLoggerLevelFiltering()
    {
        Logger logger = Logger.getLogger("FilterTest");
        Logger.setGlobalLevel(LogLevel.WARN);

        logger.debug("Debug should be filtered out");
        logger.info("Info should be filtered out");
        logger.warn("Warning should appear");
        logger.error("Error should appear");

        String out = outStream.toString(StandardCharsets.UTF_8);
        String err = errStream.toString(StandardCharsets.UTF_8);

        assertFalse(out.contains("Debug should be filtered out"));
        assertFalse(out.contains("Info should be filtered out"));
        assertTrue(err.contains("Warning should appear"));
        assertTrue(err.contains("Error should appear"));
    }

    @Test
    public void testLoggerExceptionStackTrace()
    {
        Logger logger = Logger.getLogger("ExceptionTest");
        Exception ex = new RuntimeException("Simulated failure for logging");
        logger.error(ex, "Failed with exception");

        String err = errStream.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Failed with exception"));
        assertTrue(err.contains("Simulated failure for logging"));
    }
}
