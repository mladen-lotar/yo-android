import argparse
import hashlib
import hmac
import ipaddress
import json
import math
import os
import re
import threading
import time
import unicodedata
from collections import defaultdict, deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from traceback import format_exc
from typing import Any, Callable, Deque, Dict, Optional
from urllib.parse import parse_qs, urlsplit

import yo_auth
import yo_google
from fcm_client import FCMClient, FCMDeliveryError, FCMNotConfiguredError
from yo_db import YoDatabase


DEFAULT_PORT = 8790
DEFAULT_DATABASE = Path(__file__).with_name("yo.db")
MAX_BODY_BYTES = 2_000_000

# A link has to survive a round trip through an FCM data payload and back out into an Intent, so
# it is bounded here rather than wherever it first overflows something. Measured in BYTES, because
# that is what FCM's ~4 KB data-payload cap counts.
MAX_LINK_BYTES = 2048
MAX_HASHTAG_BYTES = 140

# A hashtag is interpolated verbatim into the recipient's notification body, which is also where
# the app writes its own separators and its "TAP TO OPEN" tap promises. Without a charset rule a
# sender could attach the hashtag "x  ·  TAP TO OPEN paypal.com" and forge a second tap target in
# somebody else's shade, wearing a name they recognise. \w is Unicode-aware here, so #worldcup and
# #世界 both pass while spaces, control characters and the separator itself do not.
#
# Five of \w's own letters are exceptions to that: U+037A, U+115F, U+1160, U+3164 and U+FFA0
# render as nothing (or as blank whitespace) in every mainstream renderer, so a hashtag built from
# these instead of ASCII spaces reproduces the same forgery - "TAP TO OPEN paypal.com" spelled
# with blanks - and a category-only charset rule still calls every one of them a letter. They are
# excluded by exact codepoint, not by shape: dot-rendering letters such as U+1427 (Canadian
# syllabics) are real letters in a real script and stay allowed. It is the missing WHITESPACE
# that makes a forgery legible, not the dot.
#
# This is now a pattern of characters to STRIP rather than a shape the whole string must match -
# see _optional_attachment. A hashtag's byte length still 400s: that cannot diverge between
# processes. Its charset used to as well, and a modern handset's ICU and this process's Unicode
# table disagree on which astral code points are even assigned yet (production has rejected a real
# Chinese-name hashtag the sending phone accepted), so a 400 here failed the WHOLE Yo and G25's
# retry re-issued that identical doomed request forever. Reject what cannot diverge; sanitise what
# can.
HASHTAG_PATTERN = re.compile(r"[^\w-]|[ͺᅟᅠㅤﾠ]")

# \w does not cover Unicode categories Mn (non-spacing mark) or Mc (spacing combining mark), so
# HASHTAG_PATTERN alone stripped every combining mark exactly as unconditionally as it strips a
# literal space. That is not narrowing a hashtag, it is corrupting one: Devanagari and Arabic
# spell a real word by attaching a vowel sign or virama/harakat to the letter in front of it, and
# a category-blind strip reduces "नमस्ते" (namaste) to "नमसत", a consonant skeleton that reads as
# nonsense - see _optional_attachment and test_a_devanagari_hashtag_keeps_its_vowel_signs. A
# character in HASHTAG_MARK_CATEGORIES is admitted in addition to HASHTAG_PATTERN's [\w-],
# provided it is not one of the five codepoints above, which is automatic: none of those five is
# Mn or Mc (they are Lm/Lo), so widening here cannot let any of them back in.
#
# This cannot reopen the forgery HASHTAG_PATTERN exists to stop. Unicode's General Category is a
# strict partition, so a code point that is Mn or Mc can never simultaneously be Zs (a literal
# space) or the Po that '·' belongs to - see test_mn_and_mc_can_never_be_space_or_middle_dot. A
# combining mark is also zero-width by definition: it draws on the character before it rather
# than occupying a column of its own, so unlike the five excluded letters above it cannot
# reproduce the VISIBLE word-separating width that made "TAP TO OPEN" read as three words in the
# first place.
#
# A mark has no meaning without the base character it modifies, so a hashtag left with nothing
# but marks after sanitising is treated as an attachment that vanished - see _optional_attachment.
HASHTAG_MARK_CATEGORIES = ("Mn", "Mc")

# A broadcast client id is delivered to every subscriber as the SENDER, landing in exactly the
# position a username lands in - the app renders "From <SENDER>" with no charset filter of its
# own, because until now that field could only ever have come from validate_username. Nothing
# validated a client id anywhere: register_client.py stored whatever --client-id was given. So a
# client registered as "WORLDCUP  ·  TAP TO OPEN evil.com" forges a second tap promise in every
# subscriber's shade - G30's hashtag forgery, in the one route that never got the rule. It is
# admin-only and production holds zero clients, so this closes it before it can ever be true
# rather than after.
#
# Case is NOT constrained and the id is NOT uppercased on the way in, deliberately. The historical
# clients this route exists for are named `fedex` and `worldcup` (PRD FR7), the app renders the
# sender through `uppercase()` anyway, and normalising at registration while the caller keeps
# sending what they typed would simply break the lookup. What matters is the character SET: no
# whitespace, no `·`, no dot, no control character - nothing that can imitate the app's own
# separators or its "TAP TO OPEN" wording. `-` is allowed because it cannot.
CLIENT_ID_PATTERN = re.compile(r"\A[A-Za-z0-9_-]{2,32}\Z")

# Signup and login are necessarily public - an invitee has no credentials by definition - so
# they are the abuse surface that replaces the old shared key. Sends are limited per account.
CREDENTIAL_ATTEMPTS = 10
CREDENTIAL_WINDOW_SECONDS = 900
SEND_ATTEMPTS = 60
SEND_WINDOW_SECONDS = 60

# Adding a friend and blocking both answer `no_such_user` for a name nobody holds, which makes
# either one an account-existence probe. That disclosure is deliberate and cannot be removed -
# you have to be able to add somebody by typing their name (FR9). The RATE was not deliberate:
# nothing limited these routes at all, so a single signup bought an unmetered sweep of the whole
# username space at line rate, which is the reconnaissance half of G30's "push a link into any
# username you can guess". Registering a device sits in the same bucket because it is the other
# authenticated write with no limit. A person adds a handful of friends in a lifetime of using
# this app and registers once per launch, so this is nowhere near a real user.
LOOKUP_ATTEMPTS = 30
LOOKUP_WINDOW_SECONDS = 60

# An FCM registration token is ~150-200 characters. Nothing bounded it, so any authenticated
# caller could store up to MAX_BODY_BYTES of arbitrary text per write, repeatedly and unmetered,
# into a non-WAL SQLite file that journal_mode=delete rewrites on every transaction - on a disk
# shared with every other container on the host. This is the shape G24 removed /v1/photo for,
# left standing one route away.
MAX_FCM_TOKEN_BYTES = 4096

# A bearer token stops working 90 days after it was issued.
#
# It used to work forever. The `tokens` table has always had a `created_at` that nothing read, so
# an exfiltrated token was valid until that exact session called DELETE /v1/session - which the
# thief has no reason to do. That is G10, and issue #37 filed it as "a stolen Yo session can never
# be revoked".
#
# 90 days is chosen against the recovery path, not against a threat model in the abstract. There is
# no refresh mechanism and none is wanted: an expired token produces a 401, the client clears the
# session on any authenticated 401, and the sign-in screen appears by itself. So the entire cost of
# expiry is "sign in again", and the entire benefit is that every stolen token has a deadline.
# Shorter would be defensible; much shorter turns a one-tap app into a login prompt.
#
# Deliberately NOT paired with a "sign out everywhere" band in the menu. The server could support
# it trivially, but the menu already renders eight bands and clips the eighth (G37) - a ninth would
# push DELETE ACCOUNT off the sheet entirely, trading a Play requirement for a feature nobody has
# asked for. An unreachable ViewModel method would be G26 all over again, so it is not built.
TOKEN_TTL_SECONDS = 90 * 24 * 60 * 60

# The authenticated writes that are metered per account. /v1/send has its own, tighter budget.
LOOKUP_LIMITED_PATHS = ("/v1/friends", "/v1/block", "/v1/register")

# How many allowed requests between sweeps of the rate limiter's idle keys.
_SWEEP_EVERY = 256

# Cloudflare's published egress ranges, supplied as a comma-separated list. MUST stay in sync
# with the yo-cf-only ipAllowList sourceRange - they are the same trust boundary stated twice.
# Empty means never believe CF-Connecting-IP, which is the right default off the proxy.
CLOUDFLARE_RANGES = tuple(
    ipaddress.ip_network(cidr.strip())
    for cidr in os.environ.get("YO_CLOUDFLARE_RANGES", "").split(",")
    if cidr.strip()
)


# Everything after a query parameter's "=" up to the next "&" or whitespace. Names are kept so
# an access-log line still says which route did what; only the values go.
_QUERY_VALUE = re.compile(r"([?&][A-Za-z0-9_.%\-\[\]]+=)[^&\s\"]*")


def _redact_query_values(message: str) -> str:
    return _QUERY_VALUE.sub(r"\1[redacted]", message)


def _parse_ip(raw: str) -> Optional[ipaddress._BaseAddress]:
    try:
        return ipaddress.ip_address(raw.strip())
    except ValueError:
        return None


def _hash_client_key(raw_key: str) -> str:
    return hashlib.sha256(raw_key.encode("utf-8")).hexdigest()


class RateLimiter:
    """A fixed-capacity sliding window per key, held in memory.

    In-memory is the honest scope here: this is a single-process ThreadingHTTPServer, so there
    is no second instance to share state with. It resets on restart, which is acceptable for
    slowing credential stuffing and unacceptable as a billing control - it is only the former.
    """

    def __init__(self, limit: int, window_seconds: float, clock=time.monotonic):
        self._limit = limit
        self._window = window_seconds
        self._clock = clock
        self._hits: Dict[str, Deque[float]] = defaultdict(deque)
        self._sweep_counter = 0
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = self._clock()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] >= self._window:
                hits.popleft()
            if len(hits) >= self._limit:
                return False
            hits.append(now)
            self._evict_idle(now)
            return True

    def _evict_idle(self, now: float) -> None:
        """Drop keys whose window has fully expired.

        Without this the dict is a slow leak: it is keyed on caller IP and nothing ever removes
        an entry, so it grows for the lifetime of the process - and since the key comes from a
        request header, an attacker could grow it as fast as they can send requests.
        """
        self._sweep_counter += 1
        if self._sweep_counter < _SWEEP_EVERY:
            return
        self._sweep_counter = 0
        stale = [
            key
            for key, hits in self._hits.items()
            if not hits or now - hits[-1] >= self._window
        ]
        for key in stale:
            del self._hits[key]


class DeliveryCostEstimator:
    """A shared deadline both a blocked and a delivered send are padded to, so refusing to
    deliver costs exactly what delivering does.

    A block answers `200 {"delivered":true}` and sends nothing, which is deliberate: telling a
    sender they were blocked turns the block into a notification for the person who was blocked.
    The body was identical. The timing was not, and it is not close - a blocked send is two SQLite
    probes while a delivered one is an HTTPS round trip to Google. Measured on this codebase:
    blocked p50 0.79 ms, delivered p50 116.69 ms, ranges disjoint by 110 ms. Against production's
    own jitter (yo.the-shop.io /healthz p50 42 ms, IQR 4 ms) a min-of-three classifier separates
    them essentially perfectly, and the sender is allowed 60 sends a minute.

    Padding only the blocked branch to a single scalar mean was still two independent leaks.
    First, a delivered send was never padded at all, so it landed anywhere in the real FCM
    round-trip distribution while a blocked send landed almost exactly on the mean of that
    distribution - a min-of-N classifier still separates a point mass from the distribution it
    was drawn from, even with identical means, because the variance differs. Second, the estimate
    cold-starts at a laptop number every restart (see `_INITIAL_SECONDS` below) and decays toward
    the real production cost only after roughly a dozen deliveries, so a blocked send is briefly
    slower than a real one in the opposite direction, in memory only, reopened by every deploy.
    Both are fixed the same way: there is no per-branch pad to get wrong if both branches sleep to
    one shared deadline. That makes the property structural rather than statistical - both become
    the same point mass, so there is no distribution left to model - and it subsumes the
    cold-start problem too, since a wrong deadline is now a latency bug, not a leak.

    So the one control whose threat model explicitly names the sender as the adversary announced
    itself to that adversary. This is the same defect the login path had, in the same week -
    identical bodies mistaken for indistinguishability - and yo_auth already states the rule:
    the equal-cost path is the security property, not a detail.

    Self-calibrating rather than a hardcoded constant, because the right delay is a property of
    where the server is sitting. A laptop behind a home connection pays ~117 ms to reach FCM; the
    Hetzner host, far closer to Google, pays perhaps 60-75 ms. A constant tuned on one is wrong on
    the other, and wrong in the direction of leaking again.
    """

    # How many recent delivery durations the deadline is drawn from. Bounded so a long-dead
    # deployment's early traffic eventually ages out rather than permanently biasing the window.
    _WINDOW_SIZE = 128

    # The percentile the deadline sits at, not the mean: the deadline must sit above almost every
    # real delivery, or the tail below it is exactly the leak this class exists to remove. Not
    # `max` either - a single outlier would pad every send for the rest of the window.
    _PAD_PERCENTILE = 0.95

    # Every restart starts with an empty window, and an empty (or otherwise degenerate) window
    # must not collapse the pad to zero - that would reopen the exact leak this class fixes.
    # Deliberately still a laptop-shaped number, not a guessed production one: once both branches
    # share a deadline this constant is a latency knob, not a security boundary, and it errs slow,
    # which is the safe side. A module constant, not an environment variable, for the same reason.
    _INITIAL_SECONDS = 0.12

    # However bad a stretch of real FCM latency gets, no Yo should be made to wait longer than
    # this for the sake of matching it.
    _CEILING_SECONDS = 1.0

    def __init__(
        self,
        initial_seconds: float = _INITIAL_SECONDS,
        clock: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
    ):
        self._floor = initial_seconds
        self._durations: Deque[float] = deque(maxlen=self._WINDOW_SIZE)
        self._lock = threading.Lock()
        # Injected rather than called directly, so a test can assert on the EFFECT - the
        # instant both branches sleep to - instead of only that some sleep function was called,
        # which passes even when the two branches disagree on when to stop.
        self._clock = clock
        self._sleeper = sleeper

    def observe(self, seconds: float) -> None:
        """Record what a real delivery cost.

        Callers must pass the PRE-sleep elapsed time. If a padded (post-sleep) duration were
        ever observed here, the deadline would feed on its own output and ratchet upward without
        bound - every Yo slower forever. This method has no way to enforce that on its own; the
        call site is what must measure elapsed, observe it, and only then sleep.
        """
        with self._lock:
            self._durations.append(seconds)

    def _deadline_pad(self) -> float:
        """The seconds to pad to, floored and ceilinged, computed under the lock."""
        with self._lock:
            if not self._durations:
                percentile = 0.0
            else:
                ordered = sorted(self._durations)
                index = min(
                    len(ordered) - 1,
                    max(0, math.ceil(self._PAD_PERCENTILE * len(ordered)) - 1),
                )
                percentile = ordered[index]
        return min(max(percentile, self._floor), self._CEILING_SECONDS)

    def sleep_until_deadline(self, started: float) -> None:
        """Sleep until `started` plus the shared pad, whatever branch is calling.

        Not "sleep to match a delivery" - there is deliberately no per-branch pad any more. Both
        the blocked branch and the delivered branch call this against the same `started`, and
        both return at the same absolute instant, which is what makes the two indistinguishable
        by construction rather than by a mean the FCM round-trip distribution still spreads
        around.
        """
        deadline = started + self._deadline_pad()
        remaining = deadline - self._clock()
        if remaining > 0:
            self._sleeper(remaining)


def _configured_apk_path() -> Optional[Path]:
    """The APK to hand out at /install/yo.apk, or None if none is configured."""
    configured = os.environ.get("YO_APK_PATH", "").strip()
    if not configured:
        return None
    path = Path(configured)
    return path if path.is_file() else None


# Styled from Yo's own values: Amethyst #9B59B6, Montserrat Bold, white centred type, the mixed-case
# "Yo" wordmark and the tagline "It's that simple." See docs/PRD.md section 4.1.
#
# NO WEBFONT LINK, deliberately. This page used to pull Montserrat from fonts.googleapis.com, which
# made every visitor's browser announce its IP address to Google before it had agreed to anything -
# and this is the page every INVITE lands on, so its visitors are by definition people who do not
# have the app and have accepted no policy. The served privacy policy says Google receives data in
# exactly two narrow roles, push and sign-in verification, and "Nothing is shared with anyone else":
# a third-party font request is a third role the document denies. The publisher is Croatian, so
# that is GDPR-relevant and not merely untidy (LG Munchen I, 3 O 17493/20). Montserrat still renders
# for anyone who has it installed; everyone else gets the declared fallback and an identical layout.
# If the face is ever wanted for certain, self-host the woff2 - do not re-add a third-party link.
INSTALL_PAGE_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#9b59b6">
<title>Yo - It's that simple.</title>
<style>
  html, body { height: 100%; margin: 0; }
  body {
    background: #9B59B6;
    font-family: 'Montserrat', arial, sans-serif;
    font-weight: 700;
    color: white;
    text-align: center;
    text-rendering: optimizeLegibility;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    padding: 24px;
    box-sizing: border-box;
  }
  h1 { font-size: 22vw; line-height: 1; margin: 0; letter-spacing: -0.04em; }
  h2 { font-size: 5vw; margin: 12px 0 36px; font-weight: 700; }
  .btn {
    display: inline-block; background: #1ABC9C; color: white;
    text-decoration: none; padding: 18px 34px; border: 0; border-radius: 5px;
    font-size: 18px; font-family: inherit;
  }
  .small { font-size: 13px; font-weight: 400; opacity: 0.85; max-width: 22em; }
  @media (min-width: 700px) { h1 { font-size: 150px; } h2 { font-size: 32px; } }
</style>
</head>
<body>
  <h1>Yo</h1>
  <h2>It's that simple.</h2>
  {{ACTION}}
</body>
</html>
"""


# Google Play requires a privacy policy at a public URL, and - for any app that offers account
# creation - a web page where deletion can be requested by someone who cannot use the app. Both
# are served here rather than from a separate host so that they cannot drift away from the
# implementation they describe.
DOCUMENT_PAGE_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#9b59b6">
<title>{{TITLE}}</title>
<style>
  body {
    background: #9B59B6; color: #fff; margin: 0; padding: 32px 20px 64px;
    font-family: arial, helvetica, sans-serif; line-height: 1.55;
  }
  main { max-width: 40em; margin: 0 auto; }
  h1 { font-size: 40px; margin: 0 0 4px; letter-spacing: -0.03em; }
  h2 { font-size: 20px; margin: 32px 0 8px; }
  p, li { font-size: 16px; }
  a { color: #fff; }
  .updated { opacity: 0.85; font-size: 14px; margin-top: 0; }
</style>
</head>
<body>
<main>
{{BODY}}
</main>
</body>
</html>
"""

PRIVACY_PAGE_BODY = """<h1>Yo privacy policy</h1>
<p class="updated">Last updated 3 August 2026. Yo is published by The Shop.</p>

<h2>The short version</h2>
<p>Yo sends one word. It needs to know who you are and which device to wake, and it is not
interested in anything else. There is no advertising, no analytics, no profiling and no selling
of anything to anyone.</p>

<h2>What we store</h2>
<ul>
  <li><strong>Your username.</strong> Chosen by you. It is how friends address you.</li>
  <li><strong>Your password, hashed.</strong> PBKDF2-HMAC-SHA256. The password itself is never
      stored and cannot be recovered from what is.</li>
  <li><strong>A session key, hashed.</strong> Signing in creates one; only a hash of it is kept,
      never the key itself, and each signed-in device holds its own. It stops working 90 days
      after it was issued, and logging out removes that device's entry, and its notification
      token, immediately.</li>
  <li><strong>A Google account identifier</strong>, if you sign in with Google: only Google's
      opaque subject id. Your email address is never stored.</li>
  <li><strong>A notification token</strong> for your device, issued by Firebase Cloud Messaging.
      Without it a Yo cannot reach your phone.</li>
  <li><strong>Your friends and blocks</strong>, which are the usernames you added or blocked.</li>
  <li><strong>Your IP address, briefly</strong>, to rate-limit sign-ups and sending. It is not
      retained as a log of who you are.</li>
  <li><strong>A server access log</strong>, written as each request arrives and never edited
      afterwards. It records the time, the address a request came from, and which route it asked
      for - never who you asked about: a username only ever travels inside a request's body, and
      any value in the query string is redacted before the line is written. Deleting your
      account does not reach back into a log already written. It rotates on size, not on a
      schedule, and the oldest file is discarded first as new ones fill up.</li>
</ul>

<h2>What passes through without being stored</h2>
<p>Anything you attach to a Yo travels inside the notification to the person you sent it to, and
is not written to our database. We carry it; we do not keep it.</p>
<ul>
  <li><strong>Your location, only when you attach it.</strong> Turning on "attach location" for a
      Yo takes a single position fix and sends it with that Yo, so the person receiving it can
      open a map. There is no continuous tracking and no access while the app is in the
      background: one fix, when you ask for it, for one message.</li>
  <li><strong>A link or a hashtag</strong>, when you attach one, so that the person receiving the
      Yo can open or read it.</li>
</ul>

<h2>What stays on your phone</h2>
<ul>
  <li><strong>Your contacts.</strong> If you allow contacts access, Yo reads names on the device
      to show an invite list. Only a name and a local id are ever held, never a phone number or
      an email address, and none of it is sent to us or to anyone else. Invitations are sent by
      whichever messaging app you pick, and Yo never learns who you chose.</li>
  <li><strong>Your Yo history and groups.</strong> Stored locally, never on our servers. Deleting
      your account in the app, or logging out, erases it immediately. A deletion requested
      because you could not open the app happens on our side first and is erased on your phone
      the next time the app runs and finds the account gone. If it never runs again, uninstalling
      removes it: the app does not back its data up to the cloud, so there is no copy anywhere
      else for the uninstall to leave behind. Your groups never leave the phone at all: sending to
      a group sends an ordinary Yo to each member, and we never learn that the group exists.</li>
</ul>

<h2>Who else sees it</h2>
<p>The person you send a Yo to, which is the point of sending it. If you attached a location, a
link or a hashtag, they see that too.</p>
<p>Google, in two narrow roles: Firebase Cloud Messaging carries the notification to your device -
including anything you attached, since that travels inside the notification - and Google verifies
the sign-in token if you choose "continue with Google".</p>
<p>Cloudflare sits in front of every request on its way to our server, the way any reverse proxy
has to, which means it terminates the connection before we do. Hetzner is the company whose
machine our server and its database run on. Both handle what passes through them only on our
instructions, to route and to host what we already run, and neither uses it for any purpose of
their own.</p>
<p>Nothing is shared with anyone else, and nothing is sold.</p>

<h2>How long we keep it</h2>
<p>Until you delete your account. Deleting it removes everything the database holds about you
straight away.</p>
<p>Backups and the access log are the two exceptions, and we would rather say so than leave you to
assume otherwise. The database is backed up regularly so that a disk failure does not lose
everyone's accounts, and a backup taken before you left still contains what you had at that
moment. Those copies expire on their own within 30 days and are never used to bring an account
back. Nothing is restored from them except to recover the service after a failure. The access log
is written continuously and a deletion does not reach back into lines already written; it still
carries no username, and it rotates out on its own as described above.</p>

<h2>Deleting your account</h2>
<p>In the app: menu, then DELETE ACCOUNT. This erases your account, your friends, your blocks,
your notification token and your local history, and removes you from other people's friend
lists. It cannot be undone. If you cannot use the app, request deletion at
<a href="/delete-account">/delete-account</a>.</p>

<h2>Children</h2>
<p>Yo is not directed at children under 13 and we do not knowingly hold their data.</p>

<h2>Your rights</h2>
<p>You can access, correct or erase what we hold. Deleting your account does all three at once.
For anything else, write to
<!--email_off--><a href="mailto:mladen@the-shop.io">mladen@the-shop.io</a><!--/email_off-->.</p>

<h2>Changes</h2>
<p>If this policy changes materially we will say so in the app before the change takes effect.</p>
"""

DELETE_ACCOUNT_PAGE_BODY = """<h1>Delete your Yo account</h1>
<p class="updated">Yo is published by The Shop.</p>

<h2>The fastest way</h2>
<p>Open Yo, tap the menu button, then <strong>DELETE ACCOUNT</strong> and confirm. It takes
effect immediately and needs nothing from us.</p>

<h2>If you cannot open the app</h2>
<p>Email
<!--email_off--><a href="mailto:mladen@the-shop.io">mladen@the-shop.io</a><!--/email_off-->
from an address you can be reached at, with the username you want deleted. We will verify that
the account is yours before deleting anything, and confirm once it is done - within 30 days, and
normally far sooner.</p>

<h2>What deletion removes</h2>
<ul>
  <li>Your account and username, which becomes free for anyone to claim again.</li>
  <li>Every sign-in session, on every device.</li>
  <li>Your friends and blocks - and you disappear from other people's friend lists.</li>
  <li>Your notification token, so no further Yo can reach you.</li>
</ul>
<p>None of it can be undone, and nothing is kept in the live service afterwards. Backups taken
before you left still hold what you had at that moment; they expire on their own within 30 days
and are never used to restore an account.</p>

<h2>What this cannot reach</h2>
<p>A deletion requested this way happens on our server, which cannot reach inside a phone we do
not control. If the app is opened again on that handset, it finds the account gone and clears its
own local Yo history and groups at that point. If it is never opened again, the data leaves with
the phone: the app keeps no cloud copy of it, so nothing survives for an uninstall to remove and
nothing is left for us, or anyone else, to get to.</p>
"""


class BadRequestError(ValueError):
    pass


class YoHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        database: YoDatabase,
        fcm_client: Any,
        password_iterations: int = yo_auth.DEFAULT_ITERATIONS,
        google_verifier: Optional[Any] = None,
    ):
        super().__init__(server_address, YoRequestHandler)
        self.database = database
        self.fcm_client = fcm_client
        self.password_iterations = password_iterations
        # None when no YO_GOOGLE_CLIENT_ID is configured, which keeps /v1/google answering 503
        # rather than pretending to verify tokens against an audience nobody set.
        self.google_verifier = google_verifier
        self.credential_limiter = RateLimiter(
            CREDENTIAL_ATTEMPTS,
            CREDENTIAL_WINDOW_SECONDS,
        )
        self.send_limiter = RateLimiter(SEND_ATTEMPTS, SEND_WINDOW_SECONDS)
        self.lookup_limiter = RateLimiter(LOOKUP_ATTEMPTS, LOOKUP_WINDOW_SECONDS)
        self.delivery_cost = DeliveryCostEstimator()


class YoRequestHandler(BaseHTTPRequestHandler):
    server: YoHTTPServer

    # Set the moment a status line goes out, so _guarded never writes a second one over a
    # reply that was already on its way.
    _response_started = False

    # True while serving HEAD: headers and Content-Length are written, the body is not.
    _suppress_body = False

    # BaseHTTPRequestHandler defaults this to None, meaning a socket that never times out, and
    # ThreadingHTTPServer caps its thread count at nothing. A client that opens a connection and
    # then dribbles - or sends `Content-Length: 2000000` and stops - therefore holds a thread
    # forever, and enough of them exhaust a 256 MB container without sending a single valid
    # request.
    #
    # Production is not currently exposed to that: Traefik terminates the client connection and
    # talks to this process over a pooled one, so a slow client never reaches here. That is the
    # same shape as G33 - a real safety property living entirely in the proxy while the
    # application assumed it held on its own - and G33's lesson was that the proxy side of it is
    # one label in a shared file. This makes the property true here too.
    timeout = 30

    def do_GET(self) -> None:
        self._guarded(self._route_get)

    def do_HEAD(self) -> None:
        """Same status and headers as GET, no body.

        `BaseHTTPRequestHandler` has no default implementation, so this answered **501** - and
        `curl -I` is the reflex reach for "is it up", which made a healthy service look broken.
        That is not merely cosmetic: uptime monitors default to HEAD precisely because it is
        cheap, so the service that most needs watching was the one that would have reported
        itself down from the first check.

        Every GET route here is a pure read - /healthz, the three public pages, /v1/friends,
        /v1/blocked - so replaying one and dropping the body is safe. Content-Length is still
        sent, and still describes the body a GET would have returned, which is what HEAD is for.
        """
        self._suppress_body = True
        try:
            self._guarded(self._route_get)
        finally:
            self._suppress_body = False

    def do_POST(self) -> None:
        self._guarded(self._route_post)

    def do_DELETE(self) -> None:
        self._guarded(self._route_delete)

    def _guarded(self, route: Any) -> None:
        """Run a route, answering 500 rather than dropping the connection.

        Without this, any exception a route did not anticipate escapes into socketserver, which
        logs a traceback and closes the socket with **no response at all** - so the caller sees a
        connection reset and cannot tell a server fault from a network one. Two such escapes were
        found and fixed on 2026-08-01 (an oversized JSON integer, and math.isfinite on a large
        int), but the ones worth guarding against are the ones nobody has listed yet: the store is
        a non-WAL SQLite file behind a threaded server, so `sqlite3.OperationalError: database is
        locked` is reachable in production under ordinary concurrency and is not a bug in any
        route.

        The reason is logged and never sent. It is derived from a caller-controlled request and
        tells an honest client nothing they can act on, which is the same rule /v1/google already
        applies to Google's rejection messages.

        Nothing is written if the route already began a response: a second status line on the same
        connection would corrupt a reply that may well have been correct.
        """
        try:
            route()
        except Exception:  # noqa: BLE001 - the whole point is that it is unenumerated
            self.log_error("unhandled error serving %s: %s", self.command, format_exc().strip())
            if not self._response_started:
                self._write_json(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    {"error": "internal_error"},
                )

    def _route_get(self) -> None:
        parsed = urlsplit(self.path)
        if parsed.path == "/healthz":
            self._write_json(HTTPStatus.OK, {"ok": True})
            return
        # The install page is the target of shared invite links, so it has to be reachable by
        # someone who does not have the app and therefore has no shared key. It is static and
        # exposes no data.
        if parsed.path in ("/install", "/install/"):
            self._handle_install_page()
            return
        if parsed.path == "/install/yo.apk":
            self._handle_install_apk()
            return
        # Unauthenticated by requirement: Google Play checks both URLs without an account, and
        # the deletion page exists precisely for people who cannot get into the app.
        if parsed.path in ("/privacy", "/privacy/"):
            self._handle_document_page("Yo privacy policy", PRIVACY_PAGE_BODY)
            return
        if parsed.path in ("/delete-account", "/delete-account/"):
            self._handle_document_page(
                "Delete your Yo account",
                DELETE_ACCOUNT_PAGE_BODY,
            )
            return
        username = self._authenticate()
        if username is None:
            return
        if parsed.path == "/v1/friends":
            self._handle_friends(username)
            return
        if parsed.path == "/v1/blocked":
            self._handle_list_blocked(username)
            return
        self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def _route_delete(self) -> None:
        parsed = urlsplit(self.path)
        username = self._authenticate()
        if username is None:
            return
        try:
            if parsed.path == "/v1/session":
                self._handle_logout(username)
                return
            if parsed.path == "/v1/friends":
                self._handle_remove_friend(username, parsed.query)
                return
            if parsed.path == "/v1/block":
                self._handle_unblock(username, parsed.query)
                return
            if parsed.path == "/v1/account":
                self._handle_delete_account(username)
                return
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )

    def _route_post(self) -> None:
        parsed = urlsplit(self.path)
        # Signup and login mint credentials, so they cannot themselves require one.
        if parsed.path in ("/v1/signup", "/v1/login"):
            self._handle_credentials(parsed.path)
            return
        if parsed.path == "/v1/google":
            self._handle_google()
            return
        client_id: Optional[str] = None
        username: Optional[str] = None
        if parsed.path == "/v1/broadcast":
            # Client credentials are guessable in exactly the way a password is, and this was the
            # one credential-checking route with no limiter at all - signup, login and google all
            # have one. It shares their bucket deliberately: an attacker must not be able to dodge
            # the limit by moving between routes.
            if not self.server.credential_limiter.allow(self._limiter_key()):
                self._write_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "rate_limited"})
                return
            client_id = self._authorize_client()
            if client_id is None:
                return
        else:
            username = self._authenticate()
            if username is None:
                return
        try:
            body = self._read_json_body()
            if client_id is not None:
                self._handle_broadcast(client_id, body)
                return
            # Keyed on the authenticated account, not the IP: the caller has already proved who
            # they are, and one abusive account must not spend a shared bucket that would lock
            # out everybody else behind the same carrier NAT.
            if parsed.path in LOOKUP_LIMITED_PATHS and not self.server.lookup_limiter.allow(
                username or ""
            ):
                self._write_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "rate_limited"})
                return
            if parsed.path == "/v1/friends":
                self._handle_add_friend(username, body)
                return
            if parsed.path == "/v1/block":
                self._handle_block(username, body)
                return
            if parsed.path == "/v1/register":
                self._handle_register(username, body)
                return
            if parsed.path == "/v1/send":
                self._handle_send(username, body)
                return
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )

    def _authenticate(self) -> Optional[str]:
        """Resolve the caller from their bearer token, or answer 401 and return None.

        This is what closes gap G3: the caller's identity comes from a per-device token the
        server issued, so an attacker who extracts an APK gets the credentials of nobody.
        """
        token = self._bearer_token()
        if token:
            username = self.server.database.username_for_token(
                yo_auth.hash_token(token),
                issued_after=int(time.time()) - TOKEN_TTL_SECONDS,
            )
            if username is not None:
                return username
        self._write_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
        return None

    def _bearer_token(self) -> str:
        header = self.headers.get("Authorization", "")
        if header.lower().startswith("bearer "):
            return header[7:].strip()
        return self.headers.get("X-Yo-Token", "").strip()

    def _client_address(self) -> str:
        """The caller's real IP, which is NOT the socket address.

        Behind a reverse proxy every socket reports the proxy, so keying a rate limiter on it
        would make one global bucket for the whole internet - any single abuser would lock out
        every user.

        The RIGHTMOST X-Forwarded-For entry is the only one this server can trust, and only from
        a proxy on this host: a proxy appends the TCP peer there on every request, whether or not
        it is configured to trust an inbound chain. Position [0] is attacker-chosen, because
        Cloudflare *forwards* a client-supplied X-Forwarded-For unmodified.

        CF-Connecting-IP is believed only when that peer is a Cloudflare address. Cloudflare
        rejects any request carrying a client-supplied CF-Connecting-IP and writes the header
        itself, so it is authentic for traffic that reached us through Cloudflare and
        caller-controlled otherwise. The yo-cf-only ipAllowList middleware is what guarantees
        the first case; if it is ever removed this falls back to the peer rather than
        reopening the spoof.
        """
        socket_peer = self.client_address[0] if self.client_address else "unknown"
        peer = socket_peer

        # Only a reverse proxy on this host may author the chain. Measured 2026-07-28: Traefik
        # strips a client-supplied X-Forwarded-For and appends its own peer, so production was
        # never spoofable - twelve forged values landed in one bucket and the 11th request got
        # its 429. But that safety lived entirely in the proxy, while the comment above promised
        # this function fell back to the peer on its own. It does now: a direct caller reaches us
        # from a public address and has its header ignored, so the claim is true without Traefik.
        parsed_socket = _parse_ip(socket_peer)
        proxied = parsed_socket is not None and (
            parsed_socket.is_private or parsed_socket.is_loopback
        )
        if proxied:
            chain = self.headers.get("X-Forwarded-For", "").strip()
            if chain:
                # A non-IP entry is not a peer, and keying the limiter on it would let a caller
                # mint unlimited buckets out of arbitrary strings.
                forwarded = _parse_ip(chain.split(",")[-1])
                if forwarded is not None:
                    peer = str(forwarded)

        parsed = _parse_ip(peer)
        if parsed is not None and any(parsed in net for net in CLOUDFLARE_RANGES):
            real = _parse_ip(self.headers.get("CF-Connecting-IP", ""))
            if real is not None:
                return str(real)
        return peer

    def log_message(self, format: str, *args: Any) -> None:
        """The access log, with query-string values removed.

        BaseHTTPRequestHandler logs the full request line, so `DELETE /v1/block?username=BOB`
        sat next to the caller's IP - a record of who was blocked, beside a privacy policy that
        promises the IP is kept "briefly" and only "to rate-limit sign-ups and sending". A log
        that rotates at 30 MB keeps it for months, and debugging is not that stated purpose.
        Parameter names survive so the line is still useful; the values do not.

        Overriding here rather than in log_request is deliberate: log_error funnels through this
        too, and that is the path that echoes a malformed request line back from send_error.
        """
        super().log_message("%s", _redact_query_values(format % args))

    def _limiter_key(self) -> str:
        """The rate-limit bucket for the caller.

        IPv6 is bucketed to its /64. The smallest allocation a residential customer receives is
        a /64 - 18 quintillion addresses - so keying on the full address hands every IPv6 client
        an unlimited supply of fresh buckets, and this limiter is simultaneously the signup-flood
        control, the login brute-force control, and the only cost control on the 600,000-iteration
        PBKDF2 that /v1/signup runs. IPv4 keys on the address, where one address is roughly one
        host. Both are unchanged for `send_limiter`, which keys on the authenticated username.
        """
        address = self._client_address()
        parsed = _parse_ip(address)
        if isinstance(parsed, ipaddress.IPv6Address):
            return str(ipaddress.ip_network(f"{address}/64", strict=False).network_address)
        return address

    def _handle_credentials(self, path: str) -> None:
        """POST /v1/signup and POST /v1/login - the only routes that mint a token."""
        if not self.server.credential_limiter.allow(self._limiter_key()):
            self._write_json(
                HTTPStatus.TOO_MANY_REQUESTS,
                {"error": "rate_limited"},
            )
            return
        try:
            body = self._read_json_body()
            username = yo_auth.validate_username(
                self._required_string(body, "username")
            )
            password = yo_auth.validate_password(body.get("password"))
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return
        except yo_auth.CredentialError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return

        if path == "/v1/signup":
            created = self.server.database.create_account(
                username,
                yo_auth.hash_password(password, self.server.password_iterations),
            )
            if not created:
                self._write_json(
                    HTTPStatus.CONFLICT,
                    {"error": "username_taken"},
                )
                return
            status = HTTPStatus.CREATED
        else:
            stored = self.server.database.get_password_hash(username)
            if not yo_auth.verify_password(
                password,
                stored,
                equal_cost_iterations=self.server.password_iterations,
            ):
                # Deliberately identical for an unknown user and a wrong password, so the
                # response cannot be used to enumerate which usernames exist. The BODY was
                # always identical; what made that sentence true is verify_password spending
                # the same CPU on both, which it did not until 2026-08-01 - see its docstring.
                self._write_json(
                    HTTPStatus.UNAUTHORIZED,
                    {"error": "invalid_credentials"},
                )
                return
            status = HTTPStatus.OK

        self._issue_token(status, username)

    def _handle_google(self) -> None:
        """POST /v1/google - sign in with a Google ID token.

        Two steps by necessity rather than by choice. Google hands over a subject and an email;
        neither is a Yo username, and the username is how friends address each other. So a Google
        account nobody has seen before is answered with `username_required`, the app asks for one,
        and it posts the same token again. Every later sign-in is a single round trip.
        """
        verifier = self.server.google_verifier
        if verifier is None:
            self._write_json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "google_not_configured"},
            )
            return
        if not self.server.credential_limiter.allow(self._limiter_key()):
            self._write_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "rate_limited"})
            return
        try:
            body = self._read_json_body()
            raw_token = self._required_string(body, "id_token")
        except BadRequestError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return

        try:
            subject = verifier.subject_for(raw_token)
        except yo_google.GoogleAuthError:
            # The library's reason is not echoed back: it is derived from attacker-controlled
            # input and tells an honest caller nothing they can act on.
            self._write_json(
                HTTPStatus.UNAUTHORIZED,
                {"error": "invalid_google_token"},
            )
            return
        except yo_google.GoogleUnavailableError:
            self._write_json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "google_unavailable"},
            )
            return

        username = self.server.database.username_for_identity(
            yo_google.PROVIDER,
            subject,
        )
        if username is not None:
            self._issue_token(HTTPStatus.OK, username)
            return

        requested = body.get("username")
        if not isinstance(requested, str) or not requested.strip():
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "username_required"})
            return
        try:
            username = yo_auth.validate_username(requested)
        except yo_auth.CredentialError as error:
            self._write_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "bad_request", "reason": str(error)},
            )
            return

        created = self.server.database.create_linked_account(
            username,
            yo_google.PROVIDER,
            subject,
            yo_auth.UNUSABLE_PASSWORD_HASH,
        )
        if not created:
            # Either the name belongs to somebody else, or this Google account was linked by a
            # request that arrived while this one was verifying. Only the second can sign in.
            linked = self.server.database.username_for_identity(
                yo_google.PROVIDER,
                subject,
            )
            if linked is None:
                self._write_json(HTTPStatus.CONFLICT, {"error": "username_taken"})
                return
            self._issue_token(HTTPStatus.OK, linked)
            return
        self._issue_token(HTTPStatus.CREATED, username)

    def _issue_token(self, status: HTTPStatus, username: str) -> None:
        """Hand out a session, unless the account stopped existing while we were working.

        Both callers - password sign-in and Google sign-in - read the account, then spend real
        time (a ~300 ms PBKDF2, or a Google token verification), and only then arrive here. A
        DELETE /v1/account inside that window completes first and reaps the tokens that exist at
        that moment; this one is written afterwards. store_token now refuses to write a token for
        an absent account, so the loser of that race is told its credentials are no longer good
        rather than being handed a session on a deleted account.
        """
        # Swept here rather than on a timer, because this process has no scheduler and sign-in is
        # exactly the moment a new row appears. Expired tokens are already unusable - this is
        # about the table not growing forever, which nothing else prevented.
        self.server.database.delete_expired_tokens(
            int(time.time()) - TOKEN_TTL_SECONDS
        )
        token = yo_auth.new_token()
        if not self.server.database.store_token(yo_auth.hash_token(token), username):
            self._write_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid_credentials"})
            return
        self._write_json(status, {"token": token, "username": username})

    def _handle_logout(self, username: str) -> None:
        """End the session AND stop delivering to the handset that had it.

        Revoking the token alone left the `devices` row in place, so a signed-out phone kept
        receiving the account's Yos - sender name, location, link and all. See delete_device.

        Note for whoever next seeds the Play reviewer accounts: this deliberately changes a
        behaviour RELEASE.md used to rely on. `YODEMO2`'s device row was created by signing in on
        a handset and signing out again, on the observation that registrations survived logout.
        They no longer do. Leave the demo friend signed in, or re-register it, or the reviewer's
        first Yo answers `recipient_unregistered` and visibly fails.
        """
        self.server.database.delete_token(yo_auth.hash_token(self._bearer_token()))
        self.server.database.delete_device(username)
        self._write_json(HTTPStatus.OK, {"ended": True})

    def _handle_delete_account(self, username: str) -> None:
        """Erase the caller's account. Required by Google Play for any app with sign-up.

        The bearer token is the only authorisation asked for. A password would be a second
        factor for exactly one class of account and none at all for the other: accounts created
        through Google sign-in have no password, so requiring one would make them undeletable -
        the opposite of what the policy is for. The destructive confirmation lives in the app.

        Deleting is idempotent from the caller's side: a second attempt with a now-invalid token
        gets 401, which is indistinguishable from success and gives an interrupted client
        nothing dangerous to retry into.
        """
        deleted = self.server.database.delete_account(username)
        if not deleted:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "no_such_user"})
            return
        self._write_json(HTTPStatus.OK, {"deleted": True, "username": username})

    def _authorize_client(self) -> Optional[str]:
        client_id = self.headers.get("X-Yo-Client-Id", "")
        supplied_key = self.headers.get("X-Yo-Client-Key", "")
        stored_hash = self.server.database.get_api_key_hash(client_id)
        if (
            client_id
            # Re-checked at USE, not only at registration. A row written by hand, by an older
            # build of register_client.py, or by a restored backup would otherwise still reach
            # every subscriber's notification body. Validating only where ids are created leaves
            # the rejection reachable from anywhere that is not that one script.
            and CLIENT_ID_PATTERN.fullmatch(client_id)
            and supplied_key
            and stored_hash is not None
            and hmac.compare_digest(
                _hash_client_key(supplied_key),
                stored_hash,
            )
        ):
            return client_id
        self._write_json(
            HTTPStatus.UNAUTHORIZED,
            {"error": "unauthorized"},
        )
        return None

    def _handle_register(self, username: str, body: Dict[str, Any]) -> None:
        """The username comes from the token, never the body - you may only claim your own."""
        fcm_token = self._required_string(body, "fcm_token")
        if len(fcm_token.encode("utf-8")) > MAX_FCM_TOKEN_BYTES:
            raise BadRequestError(
                f"fcm_token must be at most {MAX_FCM_TOKEN_BYTES} bytes"
            )
        self.server.database.upsert_device(username, fcm_token)
        self._write_json(
            HTTPStatus.OK,
            {"registered": True, "username": username},
        )

    def _handle_friends(self, username: str) -> None:
        friends = self.server.database.list_friends(username)
        self._write_json(HTTPStatus.OK, {"friends": friends})

    def _handle_add_friend(self, username: str, body: Dict[str, Any]) -> None:
        friend = self._target_username(body)
        if friend == username:
            raise BadRequestError("you cannot add yourself")
        if not self.server.database.account_exists(friend):
            self._write_json(
                HTTPStatus.NOT_FOUND,
                {"error": "no_such_user"},
            )
            return
        self.server.database.add_friend(username, friend)
        self._write_json(HTTPStatus.OK, {"added": friend})

    def _handle_remove_friend(self, username: str, query: str) -> None:
        friend = self._query_username(query)
        self.server.database.remove_friend(username, friend)
        self._write_json(HTTPStatus.OK, {"removed": friend})

    def _handle_block(self, username: str, body: Dict[str, Any]) -> None:
        blocked = self._target_username(body)
        if blocked == username:
            raise BadRequestError("you cannot block yourself")
        # Refuse unknown names so an authenticated caller cannot stuff the blocks table with
        # arbitrary junk; there is no cap on its size.
        if not self.server.database.account_exists(blocked):
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "no_such_user"})
            return
        self.server.database.block(username, blocked)
        # Blocking also drops them from your own list, so the block is visible immediately.
        self.server.database.remove_friend(username, blocked)
        self._write_json(HTTPStatus.OK, {"blocked": blocked})

    def _handle_unblock(self, username: str, query: str) -> None:
        blocked = self._query_username(query)
        self.server.database.unblock(username, blocked)
        self._write_json(HTTPStatus.OK, {"unblocked": blocked})

    def _handle_list_blocked(self, username: str) -> None:
        self._write_json(
            HTTPStatus.OK,
            {"blocked": self.server.database.list_blocked(username)},
        )

    def _optional_coordinates(
        self,
        body: Dict[str, Any],
    ) -> tuple[Optional[float], Optional[float]]:
        """The attached location, or (None, None) when the sender did not share one.

        Rejected rather than silently dropped when malformed: a Yo whose location quietly
        vanished looks to the sender exactly like one that arrived, and they have no way to tell
        that the recipient never got the pin.
        """
        latitude = body.get("latitude")
        longitude = body.get("longitude")
        if latitude is None and longitude is None:
            return None, None
        if latitude is None or longitude is None:
            raise BadRequestError("latitude and longitude must be sent together")
        for name, value in (("latitude", latitude), ("longitude", longitude)):
            # bool is a subclass of int, so True would otherwise pass as the coordinate 1.
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise BadRequestError(f"{name} must be a number")
            # isfinite is asked ONLY of floats. Python integers are arbitrary precision, and
            # math.isfinite raises OverflowError - not a 400 - for any int too large to become a
            # float, so `{"latitude": 999...}` with 400 digits crashed the handler and dropped the
            # connection with no response at all. The float case was clearly considered: 1e400
            # correctly answers 400. The int case was not, because in every other language the two
            # share a ceiling. An oversized int needs no finiteness check anyway; it is out of
            # range, and the bounds below refuse it on its own terms.
            if isinstance(value, float) and not math.isfinite(value):
                raise BadRequestError(f"{name} must be a finite number")
        if not -90 <= latitude <= 90:
            raise BadRequestError("latitude must be between -90 and 90")
        if not -180 <= longitude <= 180:
            raise BadRequestError("longitude must be between -180 and 180")
        return float(latitude), float(longitude)

    def _optional_attachment(
        self,
        body: Dict[str, Any],
        key: str,
        limit: int,
    ) -> Optional[str]:
        """A link or hashtag the sender attached, or None.

        Rejected rather than silently dropped, for the same reason as the coordinates above: an
        attachment that vanishes on the way looks identical to one that arrived, and the sender
        has no way to discover the recipient never saw it. That mismatch was gap G20 for
        location and G23 for these two.
        """
        value = body.get(key)
        if value is None:
            return None
        if not isinstance(value, str):
            raise BadRequestError(f"{key} must be a string")
        value = value.strip()
        if not value:
            return None
        # Bytes, not code points. FCM's data payload cap is 4096 BYTES, so a 2048-character link
        # of astral-plane characters is 8 KB on the wire: it would pass a len() check here, be
        # rejected by Google, and surface to the sender as a 502 that no retry can ever clear.
        #
        # Checked before any charset work, and left a 400: byte length is measured the same way
        # regardless of which Unicode table this process or the sender's happens to know about, so
        # it is the one property here that cannot diverge.
        if len(value.encode("utf-8")) > limit:
            raise BadRequestError(f"{key} must be at most {limit} bytes")
        if key == "hashtag":
            # Sanitised, not rejected - see HASHTAG_PATTERN. A charset check keyed to this
            # process's own Unicode table would 400 an astral character a modern handset already
            # accepts as a letter, and the client's retry re-issues that identical doomed request
            # forever. Stripping instead means an older table only ever narrows what a hashtag
            # keeps, never fails the whole Yo over it.
            #
            # The leading hash is stripped for the same reason it always was - so "#worldcup"
            # isn't punished for the hash the app itself would have added - but the rest of
            # `value` is preserved untouched whenever nothing needed removing, so a hashtag that
            # was already clean carries on unchanged rather than losing its leading hash.
            candidate = value.lstrip("#")
            # Char-by-char rather than a single HASHTAG_PATTERN.sub: a character HASHTAG_PATTERN
            # would strip is kept anyway when it is a combining mark (see HASHTAG_MARK_CATEGORIES
            # above), so a vowel sign or virama travels with the letter it belongs to instead of
            # being torn off it.
            sanitized = "".join(
                ch
                for ch in candidate
                if not HASHTAG_PATTERN.match(ch)
                or unicodedata.category(ch) in HASHTAG_MARK_CATEGORIES
            )
            if sanitized and all(
                unicodedata.category(ch) in HASHTAG_MARK_CATEGORIES for ch in sanitized
            ):
                # A mark with no base character in front of it modifies nothing - it renders as
                # garbage attached to whatever precedes the hashtag in the notification body, not
                # as the sender's word. Treated as vanished, same as the empty-string case below.
                sanitized = ""
            if not sanitized:
                # An attachment that sanitises to nothing is an attachment that vanished, and
                # that has to be reported for the same reason as the docstring above - not
                # silently dropped.
                raise BadRequestError("hashtag must be letters, digits, underscores or dashes")
            if sanitized != candidate:
                value = sanitized
        return value

    def _handle_send(self, sender: str, body: Dict[str, Any]) -> None:
        # Taken before any work, so the blocked path can be made to cost what the delivering path
        # costs INCLUDING everything they share. Timing from a later point would leave the
        # difference in the validation and the lookups.
        started = time.monotonic()
        recipient = self._target_username(body, key="recipient")
        latitude, longitude = self._optional_coordinates(body)
        link = self._optional_attachment(body, "link", MAX_LINK_BYTES)
        hashtag = self._optional_attachment(body, "hashtag", MAX_HASHTAG_BYTES)
        if not self.server.send_limiter.allow(sender):
            self._write_json(
                HTTPStatus.TOO_MANY_REQUESTS,
                {"delivered": False, "reason": "rate_limited"},
            )
            return
        # Held rather than acted on immediately: evaluating this before the device lookup used to
        # let a deviceless recipient answer 200 delivered:true for a blocked sender and 404
        # recipient_unregistered for an unblocked one (G9) - the status code itself was the
        # oracle, no timing needed. Every path below now does the same work in the same order
        # regardless of `blocked`, so the two cases diverge only at the very end.
        blocked = self.server.database.is_blocked(recipient, sender)
        fcm_token = self.server.database.get_fcm_token(recipient)
        if fcm_token is None:
            # A real account with no device is not a missing account. Reporting both as
            # "recipient_not_found" told senders their friend did not exist whenever that friend's
            # registration had quietly failed. This discloses nothing new: /v1/friends and /v1/block
            # already answer "no_such_user" for names that do not exist. Unguarded by `blocked` on
            # purpose - see the comment above.
            reason = (
                "recipient_unregistered"
                if self.server.database.account_exists(recipient)
                else "recipient_not_found"
            )
            self._write_json(
                HTTPStatus.NOT_FOUND,
                {
                    "delivered": False,
                    "reason": reason,
                },
            )
            return
        if blocked:
            # Indistinguishable from a delivered Yo on purpose: telling the sender they were
            # blocked turns the block into a notification for the person who was blocked.
            #
            # "Indistinguishable" has to include the clock. The body was always identical and the
            # timing never was - two SQLite probes against an HTTPS round trip to Google, which
            # measured 0.79 ms against 116.69 ms with the ranges 110 ms apart. See
            # DeliveryCostEstimator; both branches sleep to the SAME shared deadline now, not a
            # scalar this one spends toward - a delivered send pads to it too, just below.
            self.server.delivery_cost.sleep_until_deadline(started)
            self._write_json(HTTPStatus.OK, {"delivered": True})
            return
        try:
            delivered = bool(
                self.server.fcm_client.send_yo(
                    fcm_token,
                    sender,
                    latitude=latitude,
                    longitude=longitude,
                    link=link,
                    hashtag=hashtag,
                )
            )
        except FCMNotConfiguredError:
            self._write_json(
                HTTPStatus.OK,
                {
                    "delivered": False,
                    "reason": "fcm_not_configured",
                },
            )
            return
        except FCMDeliveryError as exc:
            # Carries Google's actual status and body. Without logging it, a bad
            # service-account key and Google being down are indistinguishable - and on a
            # server nobody is sitting next to, this is the only diagnostic that exists.
            self.log_error("fcm delivery failed: %s", exc)
            self._write_json(
                HTTPStatus.BAD_GATEWAY,
                {
                    "delivered": False,
                    "reason": "fcm_delivery_failed",
                },
            )
            return
        # What a real delivery cost, which is what a block must be made to cost. Recorded only on
        # this path: the failure paths answer a different status and are not what is being matched.
        #
        # Order matters and must not change: measure elapsed, observe() it, THEN sleep to the
        # deadline. observe() must see the PRE-sleep duration - if it saw the padded total
        # instead, the deadline would feed on its own output and ratchet upward without bound.
        elapsed = time.monotonic() - started
        self.server.delivery_cost.observe(elapsed)
        self.server.delivery_cost.sleep_until_deadline(started)
        self._write_json(HTTPStatus.OK, {"delivered": delivered})

    def _handle_broadcast(
        self,
        client_id: str,
        body: Dict[str, Any],
    ) -> None:
        # A broadcast Yo carries no content, exactly like every other Yo - the fan-out below sends
        # the client id as the sender and nothing else. `message` used to be accepted here, type
        # checked, and then never passed to send_yo: the caller was told their text had gone out
        # and no subscriber ever saw it. That is G20's defect class in a route of its own, so the
        # field is refused rather than dropped. Delivering it would be a product change, not a fix.
        if "message" in body:
            raise BadRequestError("a broadcast carries no message; a Yo is the whole content")
        tokens = self.server.database.list_subscriber_tokens(client_id)
        delivered_count = 0
        failed_count = 0
        for token in tokens:
            try:
                if self.server.fcm_client.send_yo(token, client_id):
                    delivered_count += 1
                else:
                    failed_count += 1
            except FCMNotConfiguredError:
                self._write_json(
                    HTTPStatus.OK,
                    {
                        "delivered": 0,
                        "failed": 0,
                        "subscriber_count": len(tokens),
                        "reason": "fcm_not_configured",
                    },
                )
                return
            except FCMDeliveryError:
                failed_count += 1
        self._write_json(
            HTTPStatus.OK,
            {
                "delivered": delivered_count,
                "failed": failed_count,
                "subscriber_count": len(tokens),
            },
        )

    def _read_json_body(self) -> Dict[str, Any]:
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            raise BadRequestError("Content-Length is required")
        try:
            content_length = int(raw_length)
        except ValueError as error:
            raise BadRequestError("Content-Length must be an integer") from error
        if content_length <= 0:
            raise BadRequestError("request body is required")
        if content_length > MAX_BODY_BYTES:
            raise BadRequestError("request body is too large")
        raw_body = self.rfile.read(content_length)
        try:
            body = json.loads(raw_body.decode("utf-8"))
        except ValueError as error:
            # ValueError, not (UnicodeDecodeError, JSONDecodeError). Both of those ARE
            # ValueErrors, and naming them individually looked exhaustive while missing a third:
            # a JSON integer of more than 4300 digits raises a plain ValueError from CPython's
            # integer-string conversion limit, which escaped this handler entirely and dropped the
            # connection without a response. Enumerating the subclasses you thought of is how a
            # catch-list goes stale; catching the base covers the one you did not.
            raise BadRequestError("request body must be valid JSON") from error
        if not isinstance(body, dict):
            raise BadRequestError("request body must be a JSON object")
        return body

    @staticmethod
    def _required_string(body: Dict[str, Any], key: str) -> str:
        value = body.get(key)
        if not isinstance(value, str) or not value.strip():
            raise BadRequestError(f"{key} is required")
        return value.strip()

    @classmethod
    def _target_username(cls, body: Dict[str, Any], key: str = "username") -> str:
        """Another user named in a request body, normalised to the canonical uppercase form."""
        try:
            return yo_auth.validate_username(cls._required_string(body, key))
        except yo_auth.CredentialError as error:
            raise BadRequestError(str(error)) from error

    @staticmethod
    def _query_username(query: str, key: str = "username") -> str:
        parameters = parse_qs(query, keep_blank_values=True)
        values = parameters.get(key)
        if not values or not values[0].strip():
            raise BadRequestError(f"{key} is required")
        try:
            return yo_auth.validate_username(values[0])
        except yo_auth.CredentialError as error:
            raise BadRequestError(str(error)) from error

    def _write_html(self, page: str) -> None:
        """Serve a self-contained HTML page with the headers these pages need.

        `/privacy` and `/delete-account` are the two pages Google Play re-checks after launch,
        and `/delete-account` is the only route to erasure for somebody who cannot open the app.
        Framing that page is therefore worth denying outright: it is a destructive action reached
        by people who by definition have no other way through.

        The pages are entirely self-contained - one inline <style>, no script, no image, no
        third-party request of any kind - so `default-src 'none'` is not aspirational here, it is
        a description. Anything that later needs an external asset should be self-hosted rather
        than have this relaxed; a webfont link on the install page is exactly the mistake this
        policy would have caught.

        No `preload` on HSTS, deliberately: preload is a browser-list submission that is slow and
        awkward to undo, and the value here is the ordinary one. Traefik already issues a
        permanent redirect to https, so this only closes the very first request.
        """
        encoded = page.encode("utf-8")
        self._response_started = True
        self.send_response(HTTPStatus.OK.value)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; "
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        )
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Strict-Transport-Security", "max-age=31536000")
        self.end_headers()
        if not self._suppress_body:
            self.wfile.write(encoded)

    def _handle_document_page(self, title: str, body_html: str) -> None:
        page = DOCUMENT_PAGE_TEMPLATE.replace("{{TITLE}}", title).replace(
            "{{BODY}}",
            body_html,
        )
        self._write_html(page)

    def _handle_install_page(self) -> None:
        apk = _configured_apk_path()
        if apk is not None:
            action = (
                '<a class="btn" href="/install/yo.apk">Download Yo</a>'
                '<p class="small">Android only. You may need to allow installs '
                "from your browser.</p>"
            )
        else:
            action = '<p class="small">The download is not published yet.</p>'
        self._write_html(INSTALL_PAGE_TEMPLATE.replace("{{ACTION}}", action))

    def _handle_install_apk(self) -> None:
        apk = _configured_apk_path()
        if apk is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        # stat() rather than read_bytes() for HEAD: reading a multi-megabyte file into a
        # 256 MB container purely to throw it away is how a cheap probe becomes a DoS.
        data = b"" if self._suppress_body else apk.read_bytes()
        length = apk.stat().st_size if self._suppress_body else len(data)
        self._response_started = True
        self.send_response(HTTPStatus.OK.value)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(length))
        self.send_header("Content-Disposition", 'attachment; filename="yo.apk"')
        self.end_headers()
        if not self._suppress_body:
            self.wfile.write(data)

    def _write_json(self, status: HTTPStatus, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self._response_started = True
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not self._suppress_body:
            self.wfile.write(body)


def create_server(
    host: str,
    port: int,
    database_path: os.PathLike | str,
    fcm_client: Optional[Any] = None,
    password_iterations: int = yo_auth.DEFAULT_ITERATIONS,
    google_verifier: Optional[Any] = None,
) -> YoHTTPServer:
    database = YoDatabase(database_path)
    database.initialize()
    if google_verifier is None:
        client_id = yo_google.configured_client_id()
        if client_id is not None:
            google_verifier = yo_google.GoogleIdTokenVerifier(client_id)
    return YoHTTPServer(
        (host, port),
        database=database,
        fcm_client=fcm_client or FCMClient(),
        password_iterations=password_iterations,
        google_verifier=google_verifier,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Yo push delivery backend")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--database", default=str(DEFAULT_DATABASE))
    arguments = parser.parse_args()

    # YO_SERVER_KEY is deliberately gone. A single shared key that every install carried was
    # gap G3; callers now authenticate with a per-account token issued by /v1/signup.
    server = create_server(
        host=arguments.host,
        port=arguments.port,
        database_path=arguments.database,
    )
    try:
        print(
            f"Yo backend listening on http://{arguments.host}:{arguments.port}",
            flush=True,
        )
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
