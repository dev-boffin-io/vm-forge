#!/bin/bash
# Triggered from the vm-forge app's "Stop VM" button via Termux RUN_COMMAND.
set -e

if pkill -f qemu-system-aarch64; then
    echo "VM stopped."
else
    echo "No running VM process found."
fi
