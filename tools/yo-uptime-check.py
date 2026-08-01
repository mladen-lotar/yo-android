#!/usr/bin/env python3
"""Check Yo from OUTSIDE and say something when it is down.

The watchdog on the host can restart a wedged container. It cannot tell anybody, because that box
has no notification channel configured. This is the other half: it runs somewhere else, reaches the
service the way a user does, and alerts through the fleet's Slack sender.

WHAT IT CHECKS, AND WHY THESE THREE:

  /healthz          the service is answering at all
  /privacy          Google Play RE-CHECKS this after launch
  /delete-account   likewise, and it is the only route to erasure for somebody who cannot open the
                    app - which is exactly the person who cannot wait for it to come back

The last two are why this is not merely an uptime script. An outage on those pages is a policy
exposure, not just downtime, and they are served by the same process as the API - so they fail
together and nothing else would notice.

Deliberately probes the PUBLIC hostname, not the origin. It is the whole path or it is nothing:
DNS, Cloudflare, the yo-cf-only allowlist, Traefik's router, then the container. A check that talks
to the container directly is green in precisely the failure the container cannot see.

Exit codes: 0 all good, 1 something is down (and an alert was attempted).
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_BASE = "https://yo.the-shop.io"
PATHS = ("/healthz", "/privacy", "/delete-account")

SLACK_SEND = Path.home() / "projects/fleet/bin/slack-send.py"

# Two consecutive failures before alerting. A single timeout across the public internet is weather,
# not an outage, and an alerter that cries wolf is one that gets muted - after which it is worse
# than nothing, because its silence now means nothing too.
STATE = Path.home() / ".ai-fleet/state/yo-uptime.json"
FAILURES_BEFORE_ALERT = 2

# Do not re-alert about the same ongoing outage more often than this.
ALERT_COOLDOWN_SECONDS = 3600


# Cloudflare answers **403** to the default `Python-urllib/3.x` user-agent. Measured, not guessed:
# `curl` unmodified gets 200 and `curl -A "Python-urllib/3.12"` gets 403 against the same URL in the
# same second. Without this the monitor would have reported a permanent outage on a perfectly
# healthy service from its very first run - and an alerter that is always screaming gets muted,
# after which its silence means nothing either. A monitor's first job is to be believable.
USER_AGENT = "yo-uptime-check/1.0 (+https://yo.the-shop.io)"


def probe(base: str, path: str, timeout: float = 15.0) -> tuple[bool, str]:
    """GET, never HEAD - and read the body, because a 200 with an empty body is still broken."""
    url = base + path
    request = urllib.request.Request(url, method="GET", headers={"User-Agent": USER_AGENT})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
            elapsed = (time.monotonic() - started) * 1000
            if response.status != 200:
                return False, f"{path} -> HTTP {response.status}"
            if not body:
                return False, f"{path} -> 200 but an EMPTY body"
            return True, f"{path} -> 200 ({len(body)}B, {elapsed:.0f}ms)"
    except urllib.error.HTTPError as error:
        return False, f"{path} -> HTTP {error.code}"
    except Exception as error:  # noqa: BLE001 - any failure to reach it is a failure
        return False, f"{path} -> {type(error).__name__}: {error}"


def load_state() -> dict:
    try:
        return json.loads(STATE.read_text())
    except (OSError, ValueError):
        return {"consecutive_failures": 0, "last_alert": 0}


def save_state(state: dict) -> None:
    try:
        STATE.parent.mkdir(parents=True, exist_ok=True)
        STATE.write_text(json.dumps(state))
    except OSError:
        pass


def alert(message: str) -> bool:
    if not SLACK_SEND.exists():
        print(f"[no slack sender at {SLACK_SEND}] {message}", file=sys.stderr)
        return False
    try:
        subprocess.run(
            [sys.executable, str(SLACK_SEND), "--no-dedup", "--text", message],
            check=True,
            capture_output=True,
            timeout=60,
        )
        return True
    except Exception as error:  # noqa: BLE001
        print(f"[alert failed: {error}] {message}", file=sys.stderr)
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument(
        "--once",
        action="store_true",
        help="report and exit without touching the failure counter (for a manual check)",
    )
    arguments = parser.parse_args()

    results = [probe(arguments.base, path) for path in PATHS]
    failures = [detail for ok, detail in results if not ok]
    for ok, detail in results:
        print(("  OK   " if ok else "  DOWN ") + detail)

    if arguments.once:
        return 1 if failures else 0

    state = load_state()
    if not failures:
        if state.get("consecutive_failures", 0) >= FAILURES_BEFORE_ALERT:
            alert(f":white_check_mark: Yo is back up — {arguments.base} answering on all of {', '.join(PATHS)}")
        save_state({"consecutive_failures": 0, "last_alert": 0})
        return 0

    consecutive = state.get("consecutive_failures", 0) + 1
    last_alert = state.get("last_alert", 0)
    now = int(time.time())

    should_alert = consecutive >= FAILURES_BEFORE_ALERT and (
        now - last_alert >= ALERT_COOLDOWN_SECONDS
    )
    if should_alert:
        body = "\n".join(f"• {failure}" for failure in failures)
        sent = alert(
            f":rotating_light: *Yo is down* ({consecutive} consecutive checks)\n{body}\n"
            f"Host: `ssh root@46.225.53.158` → `docker ps --filter name=yo-backend`, "
            f"watchdog log at `/var/log/yo-watchdog.log`.\n"
            f"Note /privacy and /delete-account are re-checked by Google Play, so this is a "
            f"policy exposure as well as an outage."
        )
        if sent:
            last_alert = now

    save_state({"consecutive_failures": consecutive, "last_alert": last_alert})
    return 1


if __name__ == "__main__":
    sys.exit(main())
