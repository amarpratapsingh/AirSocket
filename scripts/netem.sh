#!/usr/bin/env bash
# AirSocket - Linux Traffic Control (tc/netem) Network Simulation Script

set -euo pipefail

DEV="lo"
DELAY=""
JITTER=""
LOSS=""
RATE=""

usage() {
    echo "Usage: $0 {apply|reset|status} [options]"
    echo ""
    echo "Commands:"
    echo "  apply              Apply tc/netem simulation rules to network interface"
    echo "  reset              Remove all tc qdisc simulation rules"
    echo "  status             Display current tc qdisc settings on interface"
    echo ""
    echo "Options:"
    echo "  --dev <interface>  Network device (default: lo)"
    echo "  --delay <ms>       Base latency in ms (e.g., 20ms)"
    echo "  --jitter <ms>      Latency jitter in ms (e.g., 5ms)"
    echo "  --loss <percent>   Packet loss percentage (e.g., 2%)"
    echo "  --rate <mbit>      Bandwidth rate limit (e.g., 100mbit)"
    echo ""
    echo "Examples:"
    echo "  sudo $0 apply --dev lo --delay 25ms --jitter 5ms --loss 1% --rate 100mbit"
    echo "  sudo $0 status --dev lo"
    echo "  sudo $0 reset --dev lo"
    exit 1
}

if [[ $# -lt 1 ]]; then
    usage
fi

COMMAND="$1"
shift

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dev)
            DEV="$2"
            shift 2
            ;;
        --delay)
            DELAY="$2"
            shift 2
            ;;
        --jitter)
            JITTER="$2"
            shift 2
            ;;
        --loss)
            LOSS="$2"
            shift 2
            ;;
        --rate)
            RATE="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

check_root() {
    if [[ $EUID -ne 0 ]]; then
        echo "Error: This script requires root privileges. Please run with sudo."
        exit 1
    fi
}

case "$COMMAND" in
    apply)
        check_root
        echo "[+] Resetting any existing qdisc on $DEV..."
        tc qdisc del dev "$DEV" root 2>/dev/null || true

        NETEM_ARGS=""
        if [[ -n "$DELAY" ]]; then
            NETEM_ARGS="$NETEM_ARGS delay $DELAY"
            if [[ -n "$JITTER" ]]; then
                NETEM_ARGS="$NETEM_ARGS $JITTER"
            fi
        fi

        if [[ -n "$LOSS" ]]; then
            NETEM_ARGS="$NETEM_ARGS loss $LOSS"
        fi

        if [[ -n "$RATE" ]]; then
            NETEM_ARGS="$NETEM_ARGS rate $RATE"
        fi

        if [[ -z "$NETEM_ARGS" ]]; then
            echo "Error: Please specify at least one impairment (--delay, --loss, or --rate)."
            exit 1
        fi

        echo "[+] Applying netem qdisc on $DEV:$NETEM_ARGS"
        tc qdisc add dev "$DEV" root netem $NETEM_ARGS
        echo "[✔] Simulation rules applied successfully."
        tc qdisc show dev "$DEV"
        ;;

    reset)
        check_root
        echo "[+] Clearing tc qdisc on $DEV..."
        tc qdisc del dev "$DEV" root 2>/dev/null || true
        echo "[✔] Qdisc reset on $DEV."
        ;;

    status)
        echo "[+] Current qdisc configuration on $DEV:"
        tc qdisc show dev "$DEV"
        ;;

    *)
        usage
        ;;
esac
