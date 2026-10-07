package com.airsocket.protocol;

public enum TransferState
{
    INITIALIZED,   // Socket connected, preparing handshake
    HANDSHAKING,   // Handshake parameters exchanged & authenticated
    READY,         // Handshake verified, resume offset and transfer plan agreed
    TRANSFERRING,  // Active chunk streaming
    FINALIZING,    // All chunks sent/received, verifying checksum and collision resolution
    COMPLETED,     // Integrity verified and final file saved
    FAILED,        // Terminal failure state
    CLOSED         // Socket and stream resources released
}
