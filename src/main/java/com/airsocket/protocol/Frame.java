package com.airsocket.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/*
  Binary framed message format for AirSocket communication.
 
  Header Layout (28 bytes):
  - Magic (4 bytes): 0x41525354 ("ARST")
  - Version (1 byte): e.g. 4
  - Type (1 byte): FrameType byte code
  - Flags (1 byte): Bitmask (0x01 encrypted, 0x02 resume, 0x04 last chunk)
  - Reserved (1 byte): 0x00
  - Transfer ID (16 bytes): UUID (mostSigBits: 8B, leastSigBits: 8B)
  - Payload Length (4 bytes): int payload length [0..16MB]
  Followed by payload of N bytes.
 */
public final class Frame
{
    public static final int MAGIC = 0x41525354;
    public static final int HEADER_SIZE = 28;
    public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024; // 16 MiB guard against DoS

    public static final byte FLAG_NONE = 0x00;
    public static final byte FLAG_ENCRYPTED = 0x01;
    public static final byte FLAG_RESUME = 0x02;
    public static final byte FLAG_LAST_CHUNK = 0x04;

    private final ProtocolVersion version;
    private final FrameType type;
    private final byte flags;
    private final UUID transferId;
    private final byte[] payload;

    public Frame(ProtocolVersion version, FrameType type, byte flags, UUID transferId, byte[] payload)
    {
        this.version = Objects.requireNonNull(version, "version cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.flags = flags;
        this.transferId = transferId != null ? transferId : new UUID(0L, 0L);
        this.payload = payload != null ? payload : new byte[0];

        if (this.payload.length > MAX_PAYLOAD_SIZE)
        {
            throw new IllegalArgumentException("Payload size " + this.payload.length + " exceeds maximum allowable " + MAX_PAYLOAD_SIZE);
        }
    }

    public ProtocolVersion version()
    {
        return version;
    }

    public FrameType type()
    {
        return type;
    }

    public byte flags()
    {
        return flags;
    }

    public UUID transferId()
    {
        return transferId;
    }

    public byte[] payload()
    {
        return payload;
    }

    public int payloadLength()
    {
        return payload.length;
    }

    public boolean isEncrypted()
    {
        return (flags & FLAG_ENCRYPTED) != 0;
    }

    public boolean isResume()
    {
        return (flags & FLAG_RESUME) != 0;
    }

    public boolean isLastChunk()
    {
        return (flags & FLAG_LAST_CHUNK) != 0;
    }

    public void writeTo(DataOutputStream out) throws IOException
    {
        out.writeInt(MAGIC);
        out.writeByte(version.versionNumber());
        out.writeByte(type.code());
        out.writeByte(flags);
        out.writeByte(0); // Reserved byte
        out.writeLong(transferId.getMostSignificantBits());
        out.writeLong(transferId.getLeastSignificantBits());
        out.writeInt(payload.length);
        if (payload.length > 0)
        {
            out.write(payload);
        }
        out.flush();
    }

    public byte[] toByteArray()
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(HEADER_SIZE + payload.length);
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            writeTo(dos);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unexpected serialization failure", e);
        }
        return baos.toByteArray();
    }

    public static Frame readFrom(DataInputStream in) throws IOException
    {
        int magic = in.readInt();
        if (magic != MAGIC)
        {
            throw new AirSocketProtocolException(
                ErrorCode.MALFORMED_FRAME,
                "Invalid protocol magic: expected 0x" + Integer.toHexString(MAGIC) + " got 0x" + Integer.toHexString(magic)
            );
        }

        byte versionByte = in.readByte();
        ProtocolVersion version;
        try
        {
            version = ProtocolVersion.fromByte(versionByte);
        }
        catch (IOException e)
        {
            throw new AirSocketProtocolException(
                ErrorCode.UNSUPPORTED_VERSION,
                "Unsupported protocol version: " + (versionByte & 0xFF),
                e
            );
        }

        byte typeByte = in.readByte();
        FrameType type;
        try
        {
            type = FrameType.fromCode(typeByte);
        }
        catch (IllegalArgumentException e)
        {
            throw new AirSocketProtocolException(
                ErrorCode.MALFORMED_FRAME,
                "Unknown frame type code: 0x" + Integer.toHexString(typeByte & 0xFF),
                e
            );
        }

        byte flags = in.readByte();
        in.readByte(); // Reserved for future extensions

        long msb = in.readLong();
        long lsb = in.readLong();
        UUID transferId = new UUID(msb, lsb);

        int payloadLength = in.readInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE)
        {
            throw new AirSocketProtocolException(
                ErrorCode.MALFORMED_FRAME,
                transferId,
                "Invalid frame payload length: " + payloadLength
            );
        }

        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0)
        {
            in.readFully(payload);
        }

        return new Frame(version, type, flags, transferId, payload);
    }

    // --- Factory Methods ---

    public static Frame handshakeInit(UUID transferId, ProtocolVersion version, boolean encrypted, boolean resume, byte[] payload)
    {
        byte flags = FLAG_NONE;
        if (encrypted) flags |= FLAG_ENCRYPTED;
        if (resume) flags |= FLAG_RESUME;
        return new Frame(version, FrameType.HANDSHAKE_INIT, flags, transferId, payload);
    }

    public static Frame handshakeAck(UUID transferId, ProtocolVersion negotiatedVersion, long resumeOffset)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(9);
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeByte(negotiatedVersion.versionNumber());
            dos.writeLong(resumeOffset);
        }
        catch (IOException ignored)
        {
        }
        return new Frame(negotiatedVersion, FrameType.HANDSHAKE_ACK, FLAG_NONE, transferId, baos.toByteArray());
    }

    public static Frame resumeReq(UUID transferId, ProtocolVersion version, long requestedOffset)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8);
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeLong(requestedOffset);
        }
        catch (IOException ignored)
        {
        }
        return new Frame(version, FrameType.RESUME_REQ, FLAG_RESUME, transferId, baos.toByteArray());
    }

    public static Frame resumeAck(UUID transferId, ProtocolVersion negotiatedVersion, long agreedOffset)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(9);
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeByte(negotiatedVersion.versionNumber());
            dos.writeLong(agreedOffset);
        }
        catch (IOException ignored)
        {
        }
        return new Frame(negotiatedVersion, FrameType.RESUME_ACK, FLAG_RESUME, transferId, baos.toByteArray());
    }

    public static Frame chunkData(UUID transferId, long chunkIndex, byte[] chunkPayload, boolean isLast)
    {
        int payloadLen = 8 + (chunkPayload != null ? chunkPayload.length : 0);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(payloadLen);
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeLong(chunkIndex);
            if (chunkPayload != null && chunkPayload.length > 0)
            {
                dos.write(chunkPayload);
            }
        }
        catch (IOException ignored)
        {
        }
        byte flags = isLast ? FLAG_LAST_CHUNK : FLAG_NONE;
        return new Frame(ProtocolVersion.CURRENT, FrameType.CHUNK_DATA, flags, transferId, baos.toByteArray());
    }

    public static Frame chunkAck(UUID transferId, long chunkIndex)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8);
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeLong(chunkIndex);
        }
        catch (IOException ignored)
        {
        }
        return new Frame(ProtocolVersion.CURRENT, FrameType.CHUNK_ACK, FLAG_NONE, transferId, baos.toByteArray());
    }

    public static Frame transferDone(UUID transferId, byte[] checksum)
    {
        byte[] payload = checksum != null ? checksum : new byte[0];
        return new Frame(ProtocolVersion.CURRENT, FrameType.TRANSFER_DONE, FLAG_NONE, transferId, payload);
    }

    public static Frame transferAck(UUID transferId)
    {
        return new Frame(ProtocolVersion.CURRENT, FrameType.TRANSFER_ACK, FLAG_NONE, transferId, new byte[0]);
    }

    public static Frame error(UUID transferId, ErrorCode errorCode, String message)
    {
        byte[] msgBytes = message != null ? message.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1 + 2 + msgBytes.length);
        try (DataOutputStream dos = new DataOutputStream(baos))
        {
            dos.writeByte(errorCode.code());
            dos.writeShort(Math.min(msgBytes.length, 65535));
            dos.write(msgBytes, 0, Math.min(msgBytes.length, 65535));
        }
        catch (IOException ignored)
        {
        }
        return new Frame(ProtocolVersion.CURRENT, FrameType.ERROR, FLAG_NONE, transferId, baos.toByteArray());
    }

    public static Frame ping(UUID transferId)
    {
        return new Frame(ProtocolVersion.CURRENT, FrameType.PING, FLAG_NONE, transferId, new byte[0]);
    }

    public static Frame pong(UUID transferId)
    {
        return new Frame(ProtocolVersion.CURRENT, FrameType.PONG, FLAG_NONE, transferId, new byte[0]);
    }

    // --- Specialized Payload Parsers ---

    public ErrorCode parseErrorCode()
    {
        if (type != FrameType.ERROR || payload.length < 1)
        {
            return ErrorCode.INTERNAL_ERROR;
        }
        return ErrorCode.fromCode(payload[0]);
    }

    public String parseErrorMessage()
    {
        if (type != FrameType.ERROR || payload.length < 3)
        {
            return "";
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload)))
        {
            dis.readByte(); // Skip error code
            int len = dis.readUnsignedShort();
            byte[] msgBytes = new byte[Math.min(len, dis.available())];
            dis.readFully(msgBytes);
            return new String(msgBytes, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return "";
        }
    }

    public long parseResumeOffset()
    {
        if (type == FrameType.RESUME_REQ && payload.length >= 8)
        {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload)))
            {
                return dis.readLong();
            }
            catch (IOException e)
            {
                return 0L;
            }
        }
        if ((type != FrameType.HANDSHAKE_ACK && type != FrameType.RESUME_ACK) || payload.length < 9)
        {
            return 0L;
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload)))
        {
            dis.readByte(); // Skip negotiated version
            return dis.readLong();
        }
        catch (IOException e)
        {
            return 0L;
        }
    }

    public ProtocolVersion parseNegotiatedVersion()
    {
        if ((type != FrameType.HANDSHAKE_ACK && type != FrameType.RESUME_ACK) || payload.length < 1)
        {
            return ProtocolVersion.CURRENT;
        }
        try
        {
            return ProtocolVersion.fromByte(payload[0]);
        }
        catch (IOException e)
        {
            return ProtocolVersion.CURRENT;
        }
    }

    public long parseChunkIndex()
    {
        if (payload.length < 8)
        {
            return 0L;
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload)))
        {
            return dis.readLong();
        }
        catch (IOException e)
        {
            return 0L;
        }
    }

    public byte[] parseChunkData()
    {
        if (payload.length <= 8)
        {
            return new byte[0];
        }
        return Arrays.copyOfRange(payload, 8, payload.length);
    }

    public byte[] parseChecksum()
    {
        if (type != FrameType.TRANSFER_DONE || payload.length == 0)
        {
            return new byte[0];
        }
        return payload.clone();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Frame frame = (Frame) o;
        return flags == frame.flags &&
               version == frame.version &&
               type == frame.type &&
               Objects.equals(transferId, frame.transferId) &&
               Arrays.equals(payload, frame.payload);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(version, type, flags, transferId);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("Frame[ver=%s, type=%s, flags=0x%02X, id=%s, len=%d]",
            version, type, flags, transferId, payload.length);
    }
}
