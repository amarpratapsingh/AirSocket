package com.airsocket.protocol;

import java.io.IOException;
import java.util.UUID;

/*
 Exception thrown when a protocol framing, state, or negotiation error occurs.
 */
public class AirSocketProtocolException extends IOException
{
    private final ErrorCode errorCode;
    private final UUID transferId;

    public AirSocketProtocolException(ErrorCode errorCode, String message)
    {
        this(errorCode, null, message, null);
    }

    public AirSocketProtocolException(ErrorCode errorCode, String message, Throwable cause)
    {
        this(errorCode, null, message, cause);
    }

    public AirSocketProtocolException(ErrorCode errorCode, UUID transferId, String message)
    {
        this(errorCode, transferId, message, null);
    }

    public AirSocketProtocolException(ErrorCode errorCode, UUID transferId, String message, Throwable cause)
    {
        super("[" + errorCode.name() + (transferId != null ? " / " + transferId : "") + "] " + message, cause);
        this.errorCode = errorCode != null ? errorCode : ErrorCode.INTERNAL_ERROR;
        this.transferId = transferId;
    }

    public ErrorCode getErrorCode()
    {
        return errorCode;
    }

    public UUID getTransferId()
    {
        return transferId;
    }
}

