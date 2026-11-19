#!/bin/bash
# Builds the service on this computer with all tests and installs it on the Raspberry Pi. Building on a Pi 3 with
# 1 GB next to the running service filled the memory until the Pi did not answer any more.
#
#   ./deploy.sh pi@host        or   STBL_HOST=pi@host ./deploy.sh
#
# The Pi needs ssh access and sudo for systemctl and /var/stbl-service.
set -euo pipefail

ZIEL=${1:-${STBL_HOST:-}}
if [ -z "$ZIEL" ]; then
    echo "Ziel fehlt: ./deploy.sh pi@host oder STBL_HOST setzen" >&2
    exit 1
fi
JAR=target/stbl-service-0.2.0-SNAPSHOT.jar

# The jar names the commit it was built from, so only committed changes are installed
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "Nicht eingecheckte Änderungen, erst committen oder verwerfen:" >&2
    git status --short --untracked-files=no >&2
    exit 1
fi
echo "Baue $(git rev-parse --abbrev-ref HEAD) $(git rev-parse --short HEAD) mit Tests ..."
mvn -q package
echo "Kopiere nach $ZIEL ..."
scp -q "$JAR" "$ZIEL:/tmp/stbl-service.jar"
# One connection for stopping, copying and starting
ssh "$ZIEL" 'sudo systemctl stop stbl && sudo cp /tmp/stbl-service.jar /var/stbl-service/stbl-service-0.2.0-SNAPSHOT.jar \
    && rm /tmp/stbl-service.jar && sudo systemctl start stbl && echo "stbl: $(systemctl is-active stbl)"'
