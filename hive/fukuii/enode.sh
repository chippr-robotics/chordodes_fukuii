#!/bin/bash
# Returns the enode URL for this Fukuii instance.
# Called by hive to discover the node's P2P identity (handed to sink nodes as a bootnode).
#
# admin_nodeInfo only returns a non-null enode after ServerActor.finishBinding runs
# (ServerStatus.Listening). With the advertised-address override in fukuii.sh that
# happens almost immediately, but boot ordering (chain import -> network start -> TCP
# bind) can still leave a short window, so poll for up to 30s instead of querying once.
# Fail loudly (empty stdout + exit 1) rather than emit a bogus enode that hive then
# rejects with an opaque "can't get node peer-to-peer endpoint:" message.

DEADLINE=$(( $(date +%s) + 30 ))

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    # Primary: admin_nodeInfo (requires `admin` in fukuii.network.rpc.apis)
    RESULT=$(curl -s -X POST -H "Content-Type: application/json" \
      -d '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
      http://localhost:8545 2>/dev/null)
    ENODE=$(echo "$RESULT" | jq -r '.result.enode // empty' 2>/dev/null)
    if [ -n "$ENODE" ]; then
        echo "$ENODE"
        exit 0
    fi

    # Fallback: parse the "Node address: enode://..." line from the server log.
    # Require a full 128-hex-char node id so a malformed/placeholder enode never matches.
    ENODE=$(grep -ohE 'enode://[0-9a-fA-F]{128}@[^ ]+' /app/data/logs/*.log 2>/dev/null | head -1)
    if [ -n "$ENODE" ]; then
        echo "$ENODE"
        exit 0
    fi

    sleep 1
done

echo "enode.sh: node never reported a P2P endpoint (admin_nodeInfo + log both empty after 30s)" >&2
exit 1
