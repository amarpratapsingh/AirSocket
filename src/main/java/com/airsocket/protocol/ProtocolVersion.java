package com.airsocket.protocol;

import java.io.IOException;

public enum ProtocolVersion
{
    V1((byte) 1, "V1 - Legacy Streaming Unencrypted"),
    V2((byte) 2, "V2 - AES-GCM Encrypted Streaming/Chunked"),
    V3((byte) 3, "V3 - Unencrypted with Transfer ID"),
    V4((byte) 4, "V4 - Frame-based Protocol with State Machine and Bidirectional Error Codes");

    private final byte versionNumber;
    private final String description;

    ProtocolVersion(byte versionNumber, String description)
    {
        this.versionNumber = versionNumber;
        this.description = description;
    }

    public byte versionNumber()
    {
        return versionNumber;
    }

    public String description()
    {
        return description;
    }

    public static final ProtocolVersion MIN_SUPPORTED = V1;
    public static final ProtocolVersion MAX_SUPPORTED = V4;
    public static final ProtocolVersion CURRENT = V4;

    public static boolean isSupported(int version)
    {
        return version >= MIN_SUPPORTED.versionNumber && version <= MAX_SUPPORTED.versionNumber;
    }

    public static ProtocolVersion fromByte(byte b) throws IOException
    {
        return fromInt(b & 0xFF);
    }

    public static ProtocolVersion fromInt(int v) throws IOException
    {
        for (ProtocolVersion pv : values())
        {
            if (pv.versionNumber == (byte) v)
            {
                return pv;
            }
        }
        throw new IOException("Unsupported protocol version: " + v);
    }

    /*
     Negotiates the common protocol version between client requested and server supported.
     Returns the highest mutually supported version.
     */
    public static ProtocolVersion negotiate(ProtocolVersion clientVersion, ProtocolVersion serverVersion)
    {
        if (clientVersion.versionNumber <= serverVersion.versionNumber)
        {
            return clientVersion;
        }
        return serverVersion;
    }
}
