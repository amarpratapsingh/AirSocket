package com.airsocket.protocol;

import java.util.Objects;
import java.util.UUID;

/*
  Thread-safe finite state machine governing AirSocket transfer lifecycles.
  Enforces valid state transitions and frame processing sequences.
 */
public class TransferStateMachine
{
    private final UUID transferId;
    private TransferState state;

    public TransferStateMachine(UUID transferId)
    {
        this.transferId = transferId != null ? transferId : UUID.randomUUID();
        this.state = TransferState.INITIALIZED;
    }

    public synchronized UUID getTransferId()
    {
        return transferId;
    }

    public synchronized TransferState getState()
    {
        return state;
    }

    public synchronized boolean isTerminal()
    {
        return state == TransferState.COMPLETED || state == TransferState.FAILED || state == TransferState.CLOSED;
    }

    /*
      Attempts to transition to the specified next state.
      Throws AirSocketProtocolException if the transition is illegal.
     */
    public synchronized void transition(TransferState nextState) throws AirSocketProtocolException
    {
        Objects.requireNonNull(nextState, "nextState cannot be null");
        if (state == nextState)
        {
            return;
        }

        if (!isValidTransition(state, nextState))
        {
            TransferState prev = state;
            state = TransferState.FAILED;
            throw new AirSocketProtocolException(
                ErrorCode.STATE_VIOLATION,
                transferId,
                "Illegal state transition from " + prev + " to " + nextState
            );
        }

        this.state = nextState;
    }

    /*
      Validates whether an incoming frame type is legally acceptable in the current state.
      If illegal, transitions to FAILED and throws an exception.
     */
    public synchronized void validateIncomingFrame(FrameType frameType) throws AirSocketProtocolException
    {
        Objects.requireNonNull(frameType, "frameType cannot be null");

        // Control frames acceptable across open states
        if (frameType == FrameType.ERROR || frameType == FrameType.PING || frameType == FrameType.PONG)
        {
            if (state == TransferState.CLOSED)
            {
                throw new AirSocketProtocolException(
                    ErrorCode.STATE_VIOLATION,
                    transferId,
                    "Cannot process " + frameType + " on CLOSED transfer"
                );
            }
            if (frameType == FrameType.ERROR)
            {
                this.state = TransferState.FAILED;
            }
            return;
        }

        boolean valid = switch (state)
        {
            case INITIALIZED -> frameType == FrameType.HANDSHAKE_INIT || frameType == FrameType.RESUME_REQ;
            case HANDSHAKING -> frameType == FrameType.HANDSHAKE_ACK || frameType == FrameType.RESUME_ACK;
            case READY -> frameType == FrameType.CHUNK_DATA || frameType == FrameType.TRANSFER_DONE;
            case TRANSFERRING -> frameType == FrameType.CHUNK_DATA || frameType == FrameType.CHUNK_ACK || frameType == FrameType.TRANSFER_DONE;
            case FINALIZING -> frameType == FrameType.TRANSFER_ACK;
            case COMPLETED -> frameType == FrameType.TRANSFER_ACK;
            case FAILED, CLOSED -> false;
        };

        if (!valid)
        {
            TransferState prev = state;
            this.state = TransferState.FAILED;
            throw new AirSocketProtocolException(
                ErrorCode.STATE_VIOLATION,
                transferId,
                "Unexpected frame " + frameType + " received while in state " + prev
            );
        }
    }

    /*
      Marks the state machine as failed.
     */
    public synchronized void fail()
    {
        if (state != TransferState.CLOSED)
        {
            this.state = TransferState.FAILED;
        }
    }

    /*
      Asserts that the current state is within one of the allowed states.
     */
    public synchronized void assertState(TransferState... expectedStates) throws AirSocketProtocolException
    {
        for (TransferState s : expectedStates)
        {
            if (this.state == s)
            {
                return;
            }
        }
        throw new AirSocketProtocolException(
            ErrorCode.STATE_VIOLATION,
            transferId,
            "Transfer state " + state + " does not match any expected state"
        );
    }

    private static boolean isValidTransition(TransferState from, TransferState to)
    {
        // Unconditional transitions to FAILED or CLOSED are always allowed
        if (to == TransferState.FAILED || to == TransferState.CLOSED)
        {
            return true;
        }

        return switch (from)
        {
            case INITIALIZED -> to == TransferState.HANDSHAKING;
            case HANDSHAKING -> to == TransferState.READY;
            case READY -> to == TransferState.TRANSFERRING || to == TransferState.FINALIZING;
            case TRANSFERRING -> to == TransferState.TRANSFERRING || to == TransferState.FINALIZING;
            case FINALIZING -> to == TransferState.COMPLETED;
            case COMPLETED -> to == TransferState.CLOSED;
            case FAILED, CLOSED -> false;
        };
    }

    @Override
    public synchronized String toString()
    {
        return String.format("TransferStateMachine[id=%s, state=%s]", transferId, state);
    }
}
