package com.airsocket.protocol;

/*
  Standard protocol error codes exchanged between AirSocket peers.
 */
public enum ErrorCode
{
    NONE((byte) 0x00, "Success / No error"),
    AUTH_FAILED((byte) 0x01, "Authentication failed: invalid passphrase or corrupted token"),
    UNSUPPORTED_VERSION((byte) 0x02, "Unsupported protocol version"),
    INSUFFICIENT_SPACE((byte) 0x03, "Insufficient disk space on destination"),
    CHECKSUM_MISMATCH((byte) 0x04, "SHA-256 checksum mismatch"),
    PATH_TRAVERSAL((byte) 0x05, "Path traversal or illegal path characters detected"),
    STATE_VIOLATION((byte) 0x06, "Protocol state machine violation"),
    TIMEOUT((byte) 0x07, "Operation or socket connection timed out"),
    INVALID_TRANSFER_ID((byte) 0x08, "Invalid or mismatched transfer ID"),
    MALFORMED_FRAME((byte) 0x09, "Malformed frame structure or payload length"),
    TRANSFER_REJECTED((byte) 0x0A, "Transfer rejected by receiver configuration"),
    INTERNAL_ERROR((byte) 0xFF, "Internal error encountered by peer");

    private final byte code;
    private final String description;

    ErrorCode(byte code, String description)
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

    public static ErrorCode fromCode(byte code)
    {
        for (ErrorCode ec : values())
        {
            if (ec.code == code)
            {
                return ec;
            }
        }
        return INTERNAL_ERROR;
    }
}
