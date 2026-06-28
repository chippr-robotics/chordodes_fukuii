#!/usr/bin/env bash
#
# refresh-bootnodes-dns.sh — Refresh bootstrap-nodes from EIP-1459 DNS trees
#
# Resilient alternative to update-bootnodes.sh (api.etcnodes.org).
# Walks the protocol-level ENR DNS trees maintained as part of EIP-1459
# infrastructure — more durable than any third-party REST API.
#
# Networks supported:
#   etc     ETC mainnet  all.classic.etcdisco.net → all.classic.blockd.info
#   mordor  Mordor       all.mordor.etcdisco.net  → all.mordor.blockd.info
#   eth     ETH mainnet  all.mainnet.ethdisco.net
#   sepolia Sepolia      all.sepolia.ethdisco.net
#
# Usage:
#   bash scripts/refresh-bootnodes-dns.sh [--network NETWORK] [--dry-run]
#
#   --network NETWORK   One of: etc mordor eth sepolia  (default: all)
#   --dry-run           Print enodes, do not write config files
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DECODE_ENR="${SCRIPT_DIR}/decode-enr.py"
CHAINS_DIR="${REPO_ROOT}/src/main/resources/conf/base/chains"
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

DRY_RUN=false
NETWORK="all"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --network) NETWORK="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# Walk an EIP-1459 DNS tree via BFS with `dig`, collecting enode URLs.
# Stops once MAX_ENODES are found. Results appended to OUTPUT_FILE.
#
# Usage: walk_dns_tree <domain> <max_enodes> <output_file>
walk_dns_tree() {
    local domain="$1"
    local max_enodes="$2"
    local output_file="$3"
    local safe_domain="${domain//[^a-zA-Z0-9]/_}"
    local visited_file="${TEMP_DIR}/visited_${safe_domain}.txt"
    local queue_file="${TEMP_DIR}/queue_${safe_domain}.txt"

    touch "$visited_file" "$queue_file"

    # Fetch root TXT record
    local root_record
    root_record=$(dig +short +time=5 +tries=2 TXT "$domain" 2>/dev/null \
        | tr -d '"' | grep -m1 'enrtree-root:' || true)

    if [[ -z "$root_record" ]]; then
        log_warn "  No enrtree-root record at $domain"
        return 0
    fi

    # Extract the ENR-tree root hash (e=...)
    local enr_root
    enr_root=$(echo "$root_record" | grep -oE 'e=[^ ]+' | cut -d= -f2 || true)
    if [[ -z "$enr_root" ]]; then
        log_warn "  Could not parse e= from: $root_record"
        return 0
    fi

    log_info "  Root hash: $enr_root  seq=$(echo "$root_record" | grep -oE 'seq=[0-9]+' | cut -d= -f2 || echo '?')"
    echo "$enr_root" > "$queue_file"

    local count
    count=$(wc -l < "$output_file" | tr -d ' ')

    while [[ -s "$queue_file" ]] && [[ $count -lt $max_enodes ]]; do
        # Pop first hash from BFS queue
        local hash
        hash=$(head -1 "$queue_file")
        # Use a portable in-place deletion (sed -i is GNU, compatible here)
        sed -i '1d' "$queue_file"

        # Skip already-visited hashes
        if grep -qF "$hash" "$visited_file" 2>/dev/null; then
            continue
        fi
        echo "$hash" >> "$visited_file"

        # Fetch the TXT record for this hash subdomain
        local record
        record=$(dig +short +time=5 +tries=2 TXT "${hash}.${domain}" 2>/dev/null \
            | tr -d '"' | head -1 || true)

        if [[ -z "$record" ]]; then
            continue
        fi

        if [[ "$record" == enrtree-branch:* ]]; then
            # Queue each child hash that hasn't been visited
            local branches="${record#enrtree-branch:}"
            for child in ${branches//,/ }; do
                [[ -z "$child" ]] && continue
                grep -qF "$child" "$visited_file" 2>/dev/null && continue
                echo "$child" >> "$queue_file"
            done

        elif [[ "$record" == enr:* ]]; then
            # Decode ENR → enode and validate format
            local enode
            enode=$(echo "$record" | python3 "$DECODE_ENR" 2>/dev/null || true)
            if [[ "$enode" =~ ^enode://[a-fA-F0-9]{128}@[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+$ ]]; then
                echo "$enode" >> "$output_file"
                count=$((count + 1))
            fi
        fi
        # enrtree:// cross-links are intentionally skipped
    done

    local final_count
    final_count=$(wc -l < "$output_file" | tr -d ' ')
    log_info "  Collected $final_count enodes so far"
}

# Collect enodes for a network, trying primary then fallback domain.
# Results written to ${TEMP_DIR}/enodes_<network>.txt
collect_enodes() {
    local network="$1"
    local primary_domain="$2"
    local fallback_domain="$3"
    local max_enodes="$4"
    local output_file="${TEMP_DIR}/enodes_${network}.txt"

    touch "$output_file"

    log_info "Querying $primary_domain ..."
    walk_dns_tree "$primary_domain" "$max_enodes" "$output_file"

    local count
    count=$(wc -l < "$output_file" | tr -d ' ')

    if [[ $count -eq 0 ]] && [[ -n "$fallback_domain" ]]; then
        log_warn "Primary yielded 0 enodes — trying fallback: $fallback_domain"
        walk_dns_tree "$fallback_domain" "$max_enodes" "$output_file"
        count=$(wc -l < "$output_file" | tr -d ' ')
    fi

    if [[ $count -eq 0 ]]; then
        log_warn "Both DNS sources yielded 0 enodes for $network — config unchanged"
    else
        log_info "Total collected for $network: $count enodes"
    fi
}

# Splice new bootstrap-nodes into a chain config file.
update_config() {
    local config_file="$1"
    local network="$2"
    local primary_domain="$3"
    local enodes_file="${TEMP_DIR}/enodes_${network}.txt"

    local count
    count=$(wc -l < "$enodes_file" | tr -d ' ')

    if [[ $count -eq 0 ]]; then
        return 0
    fi

    if [[ "$DRY_RUN" == "true" ]]; then
        log_info "DRY-RUN: $count enodes for $network (would update $config_file):"
        cat "$enodes_file"
        return 0
    fi

    local backup="${config_file}.backup.$(date +%Y%m%d_%H%M%S)"
    cp "$config_file" "$backup"
    log_info "Backup: $backup"

    # Build the replacement bootstrap-nodes block
    local new_block="${TEMP_DIR}/block_${network}.conf"
    cat > "$new_block" <<EOF
  # Set of initial nodes
  # Updated by scripts/refresh-bootnodes-dns.sh from EIP-1459 DNS tree
  # Source: ${primary_domain} (${count} resolved)
  # Last updated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')
  # These serve as fallback when DNS discovery is unavailable
  bootstrap-nodes = [
EOF

    local first=true
    while IFS= read -r enode; do
        [[ -z "$enode" ]] && continue
        if [[ "$first" == "true" ]]; then
            first=false
        else
            printf ',\n' >> "$new_block"
        fi
        printf '    "%s"' "$enode" >> "$new_block"
    done < "$enodes_file"
    printf '\n  ]\n' >> "$new_block"

    # Locate the bootstrap-nodes section in the config file.
    # Look for the "# Set of initial nodes" marker first; fall back to the
    # array declaration itself.
    local start_line
    start_line=$(grep -n '# Set of initial nodes' "$config_file" | head -1 | cut -d: -f1 || true)

    if [[ -z "$start_line" ]]; then
        local array_line
        array_line=$(grep -n 'bootstrap-nodes = \[' "$config_file" | cut -d: -f1 || true)
        if [[ -z "$array_line" ]]; then
            log_error "Could not find bootstrap-nodes section in $config_file"
            return 1
        fi
        # Step back over any comment lines immediately before the array
        start_line=$((array_line - 1))
        while [[ $start_line -gt 0 ]]; do
            local line_content
            line_content=$(sed -n "${start_line}p" "$config_file")
            if [[ "$line_content" =~ ^[[:space:]]*# ]]; then
                start_line=$((start_line - 1))
            else
                start_line=$((start_line + 1))
                break
            fi
        done
    fi

    # Find the closing ] of the bootstrap-nodes array
    local rel_end
    rel_end=$(tail -n +$((start_line + 1)) "$config_file" \
        | grep -n '^\s*\]' | head -1 | cut -d: -f1)
    local end_line=$((start_line + rel_end))

    # Splice: everything before start + new block + everything after end
    local new_config="${TEMP_DIR}/new_config_${network}.conf"
    head -n $((start_line - 1)) "$config_file" > "$new_config"
    cat "$new_block"                            >> "$new_config"
    tail -n +$((end_line + 1)) "$config_file"  >> "$new_config"
    mv "$new_config" "$config_file"

    log_info "Updated $config_file"
}

# Process one network end-to-end
process_network() {
    local network="$1"
    local primary_domain="$2"
    local fallback_domain="$3"
    local max_enodes="$4"
    local config_file="${CHAINS_DIR}/${5}"

    echo ""
    log_info "=== $network (target: $max_enodes enodes) ==="

    if [[ ! -f "$config_file" ]]; then
        log_error "Config file not found: $config_file"
        return 1
    fi

    collect_enodes "$network" "$primary_domain" "$fallback_domain" "$max_enodes"
    update_config  "$config_file" "$network" "$primary_domain"
}

# ── Prerequisites ────────────────────────────────────────────────────────────

if [[ ! -f "$DECODE_ENR" ]]; then
    log_error "decode-enr.py not found: $DECODE_ENR"
    exit 1
fi
if ! command -v python3 &>/dev/null; then
    log_error "python3 is required"
    exit 1
fi
if ! command -v dig &>/dev/null; then
    log_error "dig is required (apt install dnsutils)"
    exit 1
fi

# ── Network definitions ──────────────────────────────────────────────────────
# Format: primary_domain fallback_domain(or "") max_enodes config_file

declare -A NET_PRIMARY=(
    [etc]="all.classic.etcdisco.net"
    [mordor]="all.mordor.etcdisco.net"
    [eth]="all.mainnet.ethdisco.net"
    [sepolia]="all.sepolia.ethdisco.net"
)
declare -A NET_FALLBACK=(
    [etc]="all.classic.blockd.info"
    [mordor]="all.mordor.blockd.info"
    [eth]=""
    [sepolia]=""
)
declare -A NET_MAX=(
    [etc]=30
    [mordor]=15
    [eth]=15
    [sepolia]=10
)
declare -A NET_CONFIG=(
    [etc]="etc-chain.conf"
    [mordor]="mordor-chain.conf"
    [eth]="eth-chain.conf"
    [sepolia]="sepolia-chain.conf"
)
ALL_NETWORKS=(etc mordor eth sepolia)

# ── Dispatch ─────────────────────────────────────────────────────────────────

log_info "=== DNS Bootnode Refresh  dry-run=$DRY_RUN ==="

if [[ "$NETWORK" == "all" ]]; then
    for net in "${ALL_NETWORKS[@]}"; do
        process_network "$net" \
            "${NET_PRIMARY[$net]}" \
            "${NET_FALLBACK[$net]}" \
            "${NET_MAX[$net]}" \
            "${NET_CONFIG[$net]}"
    done
elif [[ -v "NET_PRIMARY[$NETWORK]" ]]; then
    process_network "$NETWORK" \
        "${NET_PRIMARY[$NETWORK]}" \
        "${NET_FALLBACK[$NETWORK]}" \
        "${NET_MAX[$NETWORK]}" \
        "${NET_CONFIG[$NETWORK]}"
else
    log_error "Unknown network: $NETWORK  (valid: ${ALL_NETWORKS[*]} all)"
    exit 1
fi

echo ""
log_info "=== Done ==="
