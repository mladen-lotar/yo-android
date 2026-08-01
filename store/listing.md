# Play store listing copy

Paste-ready. Every field below is final text, not an outline. Character counts are against Play's
limits as of July 2026 and are recounted whenever this file changes.

The independence statement in the full description is not decoration. Yo is a common word and the
trademark registry is clear — US application 86368420 "YO" (Life Before Us) is DEAD, abandoned
14 March 2019 in classes 009/038, the app's exact classes, never registered — so the residual risk
is Play's **impersonation** policy rather than trademark. A listing called plainly "Yo" that says
nothing about its origin is the shape Play reads as impersonation of the 2014 app. The title and
the closing paragraph together are the mitigation. Do not drop either.

---

## App name (limit 30)

```
Yo - The Shop
```
13 characters.

Not bare "Yo". Not "Yo!". The launcher label stays "Yo" and deliberately does not match this:
Play does not require them to be the same, launchers truncate at roughly 10-12 characters so
"Yo - The Shop" would render as "Yo - The...", and Android prepends the application label to every
notification header — where "Yo - The Shop - Yo - From ADA" is a real regression in the one screen
this product exists to render.

## Short description (limit 80)

```
One tap. One word. Yo.
```
22 characters.

## Full description (limit 4000)

```
Yo sends one word.

Tap a name. That person's phone says Yo. That is the entire app.

No typing, no threads, no read receipts, no "typing..." indicator, no feed to scroll. When
everything else wants a paragraph, sometimes you only mean "I'm here", "on my way", "thinking of
you", "get to your phone" - and all of those are the same word to the person who already knows
what you mean.

WHAT IT DOES

- Tap a name to send a Yo. It arrives as a notification, immediately.
- Long-press a name first to attach a link, a hashtag or your location. Attach a location and the
  Yo opens a map pinned where you were. Attach a link and it opens on tap.
- Make a group to Yo several people at once.
- Add friends by username. Nothing is discovered about you automatically.

WHAT IT DOES NOT DO

- No advertising. No analytics. No tracking. No profiling. Nothing is sold to anyone, ever.
- No message history on our servers. A Yo is delivered, not stored.
- Your contacts never leave your phone. If you allow contacts access, it is only so the invite
  list can show names you recognise. Only a name and a local id are ever read - never a phone
  number, never an email address - and none of it is sent to us or to anybody else. Invitations
  go through whichever messaging app you pick, and Yo never learns who you chose.
- No account is required to be linked to anything. Sign up with a username and a password. No
  email address, no phone number, no verification step.

LOCATION, PRECISELY

Location is off unless you turn it on for one Yo. Turning it on takes a single position fix and
sends it with that message, so the person receiving it can open a map. There is no background
access and no continuous tracking: one fix, when you ask for it, for one message. It is passed
straight through to the notification and is never written to our database.

DELETING YOUR ACCOUNT

Menu, then DELETE ACCOUNT. It takes effect immediately, erases your account, friends, blocks and
notification token, and removes you from other people's friend lists. If you cannot open the app,
the same request can be made at https://yo.the-shop.io/delete-account - no reinstall required.

BLOCKING

Long-press anyone in your list to remove or block them. Blocked people are never told, and you can
unblock from the menu at any time.

ABOUT THIS APP

Yo - The Shop is an independent app built by The Shop, an independent studio in Croatia. It is not
affiliated with, endorsed by, or connected to Yo, the app released in 2014 by Or Arbel and Moshe
Hogeg, or to any company associated with it. It shares the idea of a one-word message and nothing
else: no code, no data, no accounts and no continuity. If you had the original, this is not it,
and none of your old friends or history will appear here.

Privacy policy: https://yo.the-shop.io/privacy
```
**The description deliberately does not mention Google sign-in.** It read "sign up with a username
and a password, or continue with Google, whichever you prefer" until 2026-07-29. That advertised a
path which fails for every account outside `the-shop.hr` while the OAuth consent screen stays
`orgInternalOnly` (PRD G27) — including the reviewer's, who is told two sections below not to use
it. A store listing promising a feature the installer cannot use is its own rejection reason, and
the App access note calling the same feature unusable makes the pair self-contradictory.

If G27 is ever closed, the clause can go back. Until then the honest copy is the one that only
names the path that works. Note the character count below shifts when this is edited.

Inside the 4000 limit. Recount with:
`python3 -c "import re,pathlib;print(len(re.findall(r'\`\`\`\n(.*?)\`\`\`',pathlib.Path('store/listing.md').read_text(),re.S)[2].rstrip()))"`

## App access (for the Play reviewer)

Google sign-in is unusable for a reviewer while the OAuth consent screen is restricted to the
the-shop.hr organisation, so the declaration must be **All functionality is restricted** with a
username/password demo account, and must say so:

> **The password is deliberately not in this file, and must never be put back.** It is a live
> credential for a real account in the production database, and this repository is **public**. It
> was committed here on 2026-07-29 and published for as long as that commit was the tip; treat the
> value that was in it as compromised and rotate it before submission regardless of anything else.
> The convention this broke was already written down — PRD §7.1 records the demo password as
> "generated and reported to the user, deliberately NOT committed". Paste the current value
> straight into the Play Console field below; it belongs in a password manager, not in git.

```
Username: YODEMO1
Password: <from the operator's password manager - NOT stored in this repository>

Sign in with LOG IN on the first screen. Do NOT use CONTINUE WITH GOOGLE - Google sign-in is
currently restricted to our organisation's accounts and will fail for you.

The account already has three friends, so the main screen is populated. Tap a coloured band to
send a Yo. Long-press a band to attach a link, a hashtag or a location, or to remove or block
that person. The red button at the bottom right opens the menu, which holds the privacy policy,
the blocked list, and account deletion.
```

**Both preconditions are now satisfied, verified on a Galaxy S23 on 29 July 2026.**

| | |
|---|---|
| Username | `YODEMO1` |
| Password | not recorded here - see the warning above |
| Friends visible on the home screen | `ADA`, `LEO`, `YODEMO2` |

1. ~~the demo account has at least one friend, in BOTH directions~~ - done. `list_friends`
   selects on `owner` only, so a single `add_friend` populates one side and leaves the other
   empty; `YODEMO1` and `YODEMO2` are friends both ways.
2. ~~the friend has registered a device~~ - done, and this was the part that could only be
   closed with a handset. `YODEMO2` had no `devices` row, so `POST /v1/send` answered 404
   `recipient_unregistered` and a reviewer's first tap would have visibly failed. Signing in as
   `YODEMO2` on a real device registered an FCM token; `YODEMO1 -> YODEMO2` now returns
   `{"delivered":true}` and the push was observed arriving. Both directions were then verified.

**These are real accounts in the production database and must be deleted after launch**, the same
way `GTEST` was (PRD section 7.1). Four to remove: `YODEMO1`, `YODEMO2`, `ADA`, `LEO`.

## Category and contact

- Category: Communication
- Contact email: mladen@the-shop.io
- Website: https://yo.the-shop.io
- Privacy policy: https://yo.the-shop.io/privacy

## Content rating (IARC) answers

Derived from the code, not guessed:

| Question | Answer | Why |
|---|---|---|
| User-generated content | Yes | usernames, group names, and an attachable link/hashtag |
| Users interact / communicate | Yes | the entire app |
| Users can share location | Yes | optional, per message, relayed to the recipient |
| In-app purchases | No | no billing library |
| Ads | No | no ad SDK, no `AD_ID` permission |
| Violence, sexuality, language, drugs, gambling | No | the app sends one fixed word; there is no free-text message field |
| Unrestricted internet / in-app browser | No | outbound intents only, and links are restricted to http/https |

Expect PEGI 3 / ESRB Everyone with "Users Interact" and "Shares Location" interactive-elements
disclaimers. Because UGC and location sharing are both Yes, the questionnaire asks how abuse is
controlled: the answer is the in-app block, reachable by long-pressing anyone in the list, and
undoable from the BLOCKED sheet in the menu.
