package com.airsocket.protocol;

public enum FrameType
{
    HANDSHAKE_INIT((byte) 0x01, "Handshake Initialization"),
    HANDSHAKE_ACK((byte) 0x02, "Handshake Acknowledgement"),
    RESUME_REQ((byte) 0x03, "Resume Transfer Request"),
    RESUME_ACK((byte) 0x04, "Resume Transfer Acknowledgement"),
    CHUNK_DATA((byte) 0x05, "Chunk Data"),
    CHUNK_ACK((byte) 0x06, "Chunk Acknowledgement"),
    TRANSFER_DONE((byte) 0x07, "Transfer Completed (Sender)"),
    TRANSFER_ACK((byte) 0x08, "Transfer Finalized (Receiver)"),
    ERROR((byte) 0x09, "Explicit Error Frame"),
    PING((byte) 0x0A, "Keepalive Ping"),
    PONG((byte) 0x0B, "Keepalive Pong");

    private final byte code;
    private final String description;

    FrameType(byte code, String description)
    {
        this.code = code;
        this.description = description;
    }

    public byte code()
    {
        return code;
    }

    public String description()
    {
        return description;
    }

    public static FrameType fromCode(byte code)
    {
        for (FrameType type : values())
        {
            if (type.code == code)
            {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown FrameType code: 0x" + Integer.toHexString(code & 0xFF));
    }
}
