package com.airsocket;

import com.airsocket.apps.Cli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class CliTest
{
    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    public void setUp()
    {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    public void tearDown()
    {
        System.setOut(originalOut);
    }

    @Test
    public void testCliHelpOutput()
    {
        Cli.main(new String[]{"--help"});
        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Usage: java -jar"), "Should contain usage string");
        assertTrue(output.contains("discover"), "Should list discover command");
        assertTrue(output.contains("send <file>"), "Should list send command");
        assertTrue(output.contains("receive"), "Should list receive command");
        assertTrue(output.contains("benchmark"), "Should list benchmark command");
    }

    @Test
    public void testCliEmptyArgsPrintsHelp()
    {
        Cli.main(new String[]{});
        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Usage: java -jar"), "Empty args should display help");
    }

    @Test
    public void testCliInvalidPortReportsError()
    {
        Cli.main(new String[]{"receive", "--port", "999999"});
        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Error: --port must be a value between 1 and 65535"),
            "Invalid port should be rejected with an error message");
    }

    @Test
    public void testCliSendWithoutFileReportsError()
    {
        Cli.main(new String[]{"send"});
        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Error: Please specify the file to send"),
            "Send without file argument should report error");
    }

    @Test
    public void testCliUnknownCommandReportsHelp()
    {
        Cli.main(new String[]{"invalid-command"});
        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Unknown command: invalid-command"),
            "Unknown command should be reported");
    }
}
