package com.airsocket;

import com.airsocket.protocol.AirSocketProtocolException;
import com.airsocket.protocol.ErrorCode;
import com.airsocket.protocol.Frame;
import com.airsocket.protocol.FrameType;
import com.airsocket.protocol.ProtocolVersion;
import com.airsocket.protocol.TransferState;
import com.airsocket.protocol.TransferStateMachine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ProtocolTest
{
    @Test
    public void testFrameHeaderSerializationAndDeserialization() throws Exception
    {
        UUID transferId = UUID.randomUUID();
        byte[] payload = "Hello AirSocket Protocol V4!".getBytes(StandardCharsets.UTF_8);

        Frame frame = new Frame(
            ProtocolVersion.V4,
            FrameType.HANDSHAKE_INIT,
            (byte) (Frame.FLAG_ENCRYPTED | Frame.FLAG_RESUME),
            transferId,
            payload
        );

        assertTrue(frame.isEncrypted());
        assertTrue(frame.isResume());
        assertFalse(frame.isLastChunk());
        assertEquals(ProtocolVersion.V4, frame.version());
        assertEquals(FrameType.HANDSHAKE_INIT, frame.type());
        assertEquals(transferId, frame.transferId());
        assertEquals(payload.length, frame.payloadLength());
        assertArrayEquals(payload, frame.payload());

        byte[] serialized = frame.toByteArray();
        assertEquals(Frame.HEADER_SIZE + payload.length, serialized.length);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(serialized));
        Frame decoded = Frame.readFrom(dis);

        assertEquals(ProtocolVersion.V4, decoded.version());
        assertEquals(FrameType.HANDSHAKE_INIT, decoded.type());
        assertTrue(decoded.isEncrypted());
        assertTrue(decoded.isResume());
        assertEquals(transferId, decoded.transferId());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    public void testErrorFrameFactoryAndParsing() throws Exception
    {
        UUID transferId = UUID.randomUUID();
        String errorMsg = "Passphrase rejected: authentication failed";
        Frame errorFrame = Frame.error(transferId, ErrorCode.AUTH_FAILED, errorMsg);

        assertEquals(FrameType.ERROR, errorFrame.type());
        assertEquals(transferId, errorFrame.transferId());

        byte[] bytes = errorFrame.toByteArray();
        Frame decoded = Frame.readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));

        assertEquals(ErrorCode.AUTH_FAILED, decoded.parseErrorCode());
        assertEquals(errorMsg, decoded.parseErrorMessage());
    }

    @Test
    public void testHandshakeAckPayloadParsing() throws Exception
    {
        UUID transferId = UUID.randomUUID();
        long resumeOffset = 1048576L;
        Frame ackFrame = Frame.handshakeAck(transferId, ProtocolVersion.V4, resumeOffset);

        assertEquals(FrameType.HANDSHAKE_ACK, ackFrame.type());
        assertEquals(transferId, ackFrame.transferId());

        byte[] bytes = ackFrame.toByteArray();
        Frame decoded = Frame.readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));

        assertEquals(ProtocolVersion.V4, decoded.parseNegotiatedVersion());
        assertEquals(resumeOffset, decoded.parseResumeOffset());
    }

    @Test
    public void testChunkDataFramePayloadParsing() throws Exception
    {
        UUID transferId = UUID.randomUUID();
        long chunkIndex = 42L;
        byte[] chunkBytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        Frame chunkFrame = Frame.chunkData(transferId, chunkIndex, chunkBytes, true);

        assertTrue(chunkFrame.isLastChunk());
        assertEquals(FrameType.CHUNK_DATA, chunkFrame.type());

        byte[] bytes = chunkFrame.toByteArray();
        Frame decoded = Frame.readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));

        assertTrue(decoded.isLastChunk());
        assertEquals(chunkIndex, decoded.parseChunkIndex());
        assertArrayEquals(chunkBytes, decoded.parseChunkData());
    }

    @Test
    public void testMalformedFrameThrowsProtocolException()
    {
        // 1. Invalid magic
        byte[] badMagic = new byte[32];
        badMagic[0] = 0x12;
        assertThrows(AirSocketProtocolException.class, () ->
        {
            Frame.readFrom(new DataInputStream(new ByteArrayInputStream(badMagic)));
        });

        // 2. Oversized payload creation
        assertThrows(IllegalArgumentException.class, () ->
        {
            new Frame(ProtocolVersion.V4, FrameType.CHUNK_DATA, (byte) 0, UUID.randomUUID(), new byte[17 * 1024 * 1024]);
        });
    }

    @Test
    public void testProtocolVersionNegotiationAndBounds()
    {
        assertTrue(ProtocolVersion.isSupported(1));
        assertTrue(ProtocolVersion.isSupported(2));
        assertTrue(ProtocolVersion.isSupported(3));
        assertTrue(ProtocolVersion.isSupported(4));
        assertFalse(ProtocolVersion.isSupported(0));
        assertFalse(ProtocolVersion.isSupported(5));

        // Negotiate highest common version
        assertEquals(ProtocolVersion.V2, ProtocolVersion.negotiate(ProtocolVersion.V2, ProtocolVersion.V4));
        assertEquals(ProtocolVersion.V4, ProtocolVersion.negotiate(ProtocolVersion.V4, ProtocolVersion.V4));
    }

    @Test
    public void testStateMachineValidLifecycle() throws Exception
    {
        UUID transferId = UUID.randomUUID();
        TransferStateMachine sm = new TransferStateMachine(transferId);

        assertEquals(TransferState.INITIALIZED, sm.getState());
        assertEquals(transferId, sm.getTransferId());
        assertFalse(sm.isTerminal());

        // INITIALIZED -> HANDSHAKING
        sm.transition(TransferState.HANDSHAKING);
        assertEquals(TransferState.HANDSHAKING, sm.getState());

        // HANDSHAKING -> READY
        sm.transition(TransferState.READY);
        assertEquals(TransferState.READY, sm.getState());

        // READY -> TRANSFERRING
        sm.transition(TransferState.TRANSFERRING);
        assertEquals(TransferState.TRANSFERRING, sm.getState());

        // Loop in TRANSFERRING
        sm.transition(TransferState.TRANSFERRING);
        assertEquals(TransferState.TRANSFERRING, sm.getState());

        // TRANSFERRING -> FINALIZING
        sm.transition(TransferState.FINALIZING);
        assertEquals(TransferState.FINALIZING, sm.getState());

        // FINALIZING -> COMPLETED
        sm.transition(TransferState.COMPLETED);
        assertEquals(TransferState.COMPLETED, sm.getState());
        assertTrue(sm.isTerminal());

        // COMPLETED -> CLOSED
        sm.transition(TransferState.CLOSED);
        assertEquals(TransferState.CLOSED, sm.getState());
        assertTrue(sm.isTerminal());
    }

    @Test
    public void testStateMachineIllegalTransitionThrowsProtocolException()
    {
        UUID transferId = UUID.randomUUID();
        TransferStateMachine sm = new TransferStateMachine(transferId);

        // Attempting to jump directly from INITIALIZED to TRANSFERRING
        AirSocketProtocolException ex = assertThrows(AirSocketProtocolException.class, () ->
        {
            sm.transition(TransferState.TRANSFERRING);
        });

        assertEquals(ErrorCode.STATE_VIOLATION, ex.getErrorCode());
        assertEquals(TransferState.FAILED, sm.getState());
        assertTrue(sm.isTerminal());
    }

    @Test
    public void testStateMachineIncomingFrameValidation() throws Exception
    {
        TransferStateMachine sm = new TransferStateMachine(UUID.randomUUID());

        // In INITIALIZED, only HANDSHAKE_INIT, PING, or ERROR allowed
        sm.validateIncomingFrame(FrameType.HANDSHAKE_INIT);

        // CHUNK_DATA in INITIALIZED must fail
        assertThrows(AirSocketProtocolException.class, () ->
        {
            sm.validateIncomingFrame(FrameType.CHUNK_DATA);
        });
        assertEquals(TransferState.FAILED, sm.getState());
    }

    @Test
    public void testErrorFrameTransitionsStateMachineToFailed() throws Exception
    {
        TransferStateMachine sm = new TransferStateMachine(UUID.randomUUID());
        sm.transition(TransferState.HANDSHAKING);

        // Receiving an ERROR frame
        sm.validateIncomingFrame(FrameType.ERROR);
        assertEquals(TransferState.FAILED, sm.getState());
        assertTrue(sm.isTerminal());
    }

    @Test
    public void testResumeReqAndAckSerializationAndDeserialization() throws Exception
    {
        UUID transferId = UUID.randomUUID();
        long offset = 524288L;

        Frame req = Frame.resumeReq(transferId, ProtocolVersion.V4, offset);
        assertEquals(FrameType.RESUME_REQ, req.type());
        assertTrue(req.isResume());
        assertEquals(transferId, req.transferId());

        byte[] reqBytes = req.toByteArray();
        Frame reqDecoded = Frame.readFrom(new DataInputStream(new ByteArrayInputStream(reqBytes)));
        assertEquals(req, reqDecoded);

        Frame ack = Frame.resumeAck(transferId, ProtocolVersion.V4, offset);
        assertEquals(FrameType.RESUME_ACK, ack.type());
        assertTrue(ack.isResume());
        assertEquals(transferId, ack.transferId());

        byte[] ackBytes = ack.toByteArray();
        Frame ackDecoded = Frame.readFrom(new DataInputStream(new ByteArrayInputStream(ackBytes)));
        assertEquals(ack, ackDecoded);
        assertEquals(ProtocolVersion.V4, ackDecoded.parseNegotiatedVersion());
        assertEquals(offset, ackDecoded.parseResumeOffset());
    }

    @Test
    public void testTransferDoneChecksumParsing() throws Exception
    {
        UUID transferId = UUID.randomUUID();
        byte[] checksum = new byte[]{10, 20, 30, 40, 50};
        Frame doneFrame = Frame.transferDone(transferId, checksum);

        assertEquals(FrameType.TRANSFER_DONE, doneFrame.type());
        assertArrayEquals(checksum, doneFrame.parseChecksum());

        byte[] bytes = doneFrame.toByteArray();
        Frame decoded = Frame.readFrom(new DataInputStream(new ByteArrayInputStream(bytes)));
        assertArrayEquals(checksum, decoded.parseChecksum());
    }

    @Test
    public void testFrameEqualsAndHashCode()
    {
        UUID transferId = UUID.randomUUID();
        byte[] payload = new byte[]{1, 2, 3};
        Frame f1 = new Frame(ProtocolVersion.V4, FrameType.CHUNK_DATA, Frame.FLAG_NONE, transferId, payload);
        Frame f2 = new Frame(ProtocolVersion.V4, FrameType.CHUNK_DATA, Frame.FLAG_NONE, transferId, payload);
        Frame f3 = new Frame(ProtocolVersion.V4, FrameType.CHUNK_DATA, Frame.FLAG_LAST_CHUNK, transferId, payload);

        assertEquals(f1, f2);
        assertEquals(f1.hashCode(), f2.hashCode());
        assertNotEquals(f1, f3);
        assertNotEquals(f1, null);
    }

    @Test
    public void testStateMachineAssertState() throws Exception
    {
        TransferStateMachine sm = new TransferStateMachine(UUID.randomUUID());
        assertDoesNotThrow(() -> sm.assertState(TransferState.INITIALIZED));
        assertDoesNotThrow(() -> sm.assertState(TransferState.HANDSHAKING, TransferState.INITIALIZED));

        AirSocketProtocolException ex = assertThrows(AirSocketProtocolException.class, () ->
        {
            sm.assertState(TransferState.READY, TransferState.TRANSFERRING);
        });
        assertEquals(ErrorCode.STATE_VIOLATION, ex.getErrorCode());
    }

    @Test
    public void testStateMachineFailMethod() throws Exception
    {
        TransferStateMachine sm = new TransferStateMachine(UUID.randomUUID());
        sm.transition(TransferState.HANDSHAKING);
        assertFalse(sm.isTerminal());

        sm.fail();
        assertEquals(TransferState.FAILED, sm.getState());
        assertTrue(sm.isTerminal());
    }

    @Test
    public void testStateMachineResumeTransitions() throws Exception
    {
        TransferStateMachine sm = new TransferStateMachine(UUID.randomUUID());
        // In INITIALIZED, RESUME_REQ is valid
        assertDoesNotThrow(() -> sm.validateIncomingFrame(FrameType.RESUME_REQ));

        sm.transition(TransferState.HANDSHAKING);
        // In HANDSHAKING, RESUME_ACK is valid
        assertDoesNotThrow(() -> sm.validateIncomingFrame(FrameType.RESUME_ACK));
    }

    @Test
    public void testMalformedFrameTypeThrowsProtocolException()
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeInt(Frame.MAGIC);
            dos.writeByte(4); // V4
            dos.writeByte(0x7F); // Invalid frame type code!
            dos.writeByte(0);
            dos.writeByte(0);
            dos.writeLong(0);
            dos.writeLong(0);
            dos.writeInt(0);
        }
        catch (IOException ignored) {}

        AirSocketProtocolException ex = assertThrows(AirSocketProtocolException.class, () ->
        {
            Frame.readFrom(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
        });
        assertEquals(ErrorCode.MALFORMED_FRAME, ex.getErrorCode());
    }

    @Test
    public void testUnsupportedVersionThrowsProtocolException()
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeInt(Frame.MAGIC);
            dos.writeByte(99); // Unsupported version 99!
            dos.writeByte(FrameType.PING.code());
            dos.writeByte(0);
            dos.writeByte(0);
            dos.writeLong(0);
            dos.writeLong(0);
            dos.writeInt(0);
        }
        catch (IOException ignored) {}

        AirSocketProtocolException ex = assertThrows(AirSocketProtocolException.class, () ->
        {
            Frame.readFrom(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
        });
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, ex.getErrorCode());
    }
}
