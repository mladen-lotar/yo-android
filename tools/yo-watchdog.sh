#!/bin/sh
# Watch the Yo backend and act when it is wedged.
#
# WHY THIS EXISTS. Nothing watched this service at all. `restart: unless-stopped` covers a process
# that EXITS, and Docker's healthcheck covers a process that is alive but broken - except that a
# healthcheck only ever *labels* a container `unhealthy`. It takes no action whatsoever. So the one
# failure a healthcheck is uniquely able to detect was the one failure nothing responded to.
#
# That matters past availability: Google Play re-checks /privacy and /delete-account after launch,
# so an unnoticed outage is a policy problem as well as a downtime one.
#
# Deliberately a shell script in cron rather than an autoheal container. This host runs 27
# containers; adding one that watches the Docker socket to fix a single service is a larger blast
# radius than the problem, and a socket-mounted container is a privilege escalation path for
# anything that compromises it.
#
# Usage: yo-watchdog.sh [container] [url]
set -u

CONTAINER="${1:-yo-backend}"
URL="${2:-https://yo.the-shop.io/healthz}"
STATE="/var/tmp/yo-watchdog.state"
LOG="/var/log/yo-watchdog.log"

# Two consecutive bad checks before acting. One is noise: a healthcheck can fail transiently while
# the container is merely busy, and restarting on a single sample turns a hiccup into an outage.
STRIKES_BEFORE_RESTART=2

# Never restart more than this often. A container that is broken on startup would otherwise be
# restarted every run forever, which looks like self-healing and is actually a crash loop with a
# cron job hiding it.
MIN_SECONDS_BETWEEN_RESTARTS=900

log() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$LOG"
}

read_state() {
    if [ -f "$STATE" ]; then
        # shellcheck disable=SC2013
        strikes=$(sed -n '1p' "$STATE" 2>/dev/null)
        last_restart=$(sed -n '2p' "$STATE" 2>/dev/null)
    fi
    strikes=${strikes:-0}
    last_restart=${last_restart:-0}
    case "$strikes" in ''|*[!0-9]*) strikes=0 ;; esac
    case "$last_restart" in ''|*[!0-9]*) last_restart=0 ;; esac
}

write_state() {
    printf '%s\n%s\n' "$1" "$2" > "$STATE"
}

read_state
now=$(date -u +%s)

health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    "$CONTAINER" 2>/dev/null)
running=$(docker inspect --format '{{.State.Running}}' "$CONTAINER" 2>/dev/null)

# GET, never HEAD. This checks the WHOLE path a user takes - DNS, Cloudflare, Traefik, the
# container - which is the point of probing the public hostname rather than localhost: a container
# can be perfectly healthy while the router in front of it has stopped sending it anything.
http=$(curl -s -o /dev/null -w '%{http_code}' -m 15 "$URL" 2>/dev/null)

if [ "$running" = "true" ] && [ "$health" = "healthy" ] && [ "$http" = "200" ]; then
    [ "$strikes" -gt 0 ] && log "recovered (health=$health http=$http)"
    write_state 0 "$last_restart"
    exit 0
fi

strikes=$((strikes + 1))
log "unhealthy check $strikes/$STRIKES_BEFORE_RESTART (running=$running health=$health http=$http)"

if [ "$strikes" -lt "$STRIKES_BEFORE_RESTART" ]; then
    write_state "$strikes" "$last_restart"
    exit 1
fi

# A container that is up and healthy while the public URL fails is an EDGE problem - DNS, the
# Cloudflare allowlist, a Traefik router. Restarting the container cannot fix that and would only
# add an outage to an outage, so say so loudly and stop.
if [ "$running" = "true" ] && [ "$health" = "healthy" ]; then
    log "EDGE FAULT: container is healthy but $URL returned '$http'. NOT restarting - a restart cannot fix routing."
    write_state "$strikes" "$last_restart"
    exit 1
fi

since_restart=$((now - last_restart))
if [ "$since_restart" -lt "$MIN_SECONDS_BETWEEN_RESTARTS" ]; then
    log "STILL BROKEN ${since_restart}s after the last restart - not restarting again. This needs a human."
    write_state "$strikes" "$last_restart"
    exit 1
fi

log "restarting $CONTAINER"
if docker restart "$CONTAINER" >/dev/null 2>&1; then
    log "restart issued"
    write_state 0 "$now"
else
    log "RESTART FAILED"
    write_state "$strikes" "$now"
fi
exit 1
