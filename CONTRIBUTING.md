# Small contributions welcome

Fix a typo, share a command, recommend a package, improve accessibility, or propose
a meeting. A five-minute demo is a talk. Participation matters more than expertise.

1. Fork the repository or create a branch.
2. Add or edit EDN under `content/` or `members/`.
3. Run `npm run validate` and `npm run test:unit`.
4. Build with `npm run build`; for UI changes also run the browser tests.
5. Open a pull request describing your change.

To become a member, add your member record **and one small useful contribution**.
Once merged, the next Pages build publishes your entry.

The member entry code block's **copy icon**, or **M-x copy-member-template**,
copies the minimal entry with the current joining month. Replace the handle
placeholder and use it as the filename. If clipboard access is unavailable,
select the displayed template and copy it manually.

Every code block has the same keyboard- and touch-accessible copy icon. Inline
code is not a block and has no copy button.

## Content records

Use one EDN map per file. The filename must match `:id`:

```clojure
{:id "a-useful-command"
 :title "A useful command"
 :description "What it helps with."
 :body ["An explanation in plain text." "Another paragraph."]
 :tags #{:built-in :navigation}
 :command "a-useful-command"
 :tip? true}
```

The shared required fields are `:id`, `:title`, `:description`, and a nonempty
vector of plain-text `:body` paragraphs. Optional shared fields are `:tags` (a
keyword set), `:url` (absolute HTTPS), `:discussion-number` (a real positive
Discussion number), and `:author` (a member handle).
No Markdown or arbitrary HTML is interpreted. Code belongs in `:code`, and is
escaped for both static and interactive rendering.

Set `:author` to a member's GitHub handle, for example `:author "hkjels"`.
Lists and pages show a linked byline with that member's avatar and optional
`:full-name`, falling back to the handle. `:speaker` is independent: if an event
or talk has a different author and speaker, both are credited and appear on their
respective member pages. An unknown handle fails validation.

| Directory | Additional fields |
|---|---|
| `pages/` | Required source prose for `scratch` and `about`; adding a new primary buffer also requires a router/view entry |
| `meetings/` | Required `:date` (`YYYY-MM-DD`), `:time` (`HH:mm`), `:timezone` (IANA), `:duration-minutes` (positive integer), `:status` (`:scheduled`, `:past`); optional `:speaker`, `:join-url`, `:location`, `:notes-url`, `:recording-url` |
| `talks/` | Optional `:speaker`, `:meeting-id`; displayed within Meetings |
| `snippets/` | Optional `:code`, `:language`, `:command`, `:tip?`; a tip requires a command |

Package recommendations belong in `snippets/`, alongside their configuration and
explanation. Use `use-package` for Elisp configuration examples. It is bundled with
Emacs 29 and later; older versions need a separate installation. Use `:ensure nil`
for built-in packages and `:ensure t` for external ones, documenting any trusted
archive or version prerequisite. Not every programming mode has a Flymake or
Eldoc provider; avoid enabling integrations without explaining their requirements.

Talks remain separate source records because material need not have an event
date or time. Their canonical URLs are `/meetings/talks/<id>/`; `:meeting-id`
connects material to the corresponding event in both directions. Joining is a
section of People rather than a source page. Published legacy URLs are listed
in `router/redirects`; keep their destinations valid when retiring content.

Scheduled meetings need a public joining URL or location. Only publish actual
events, talk material, and member records. Fictional records belong in
`test/fixtures/community.edn`, not `content/` or `members/`. Tests use
`:status :example` and `:example? true` to check that examples never invite users.
Speaker/author handles and meeting references must exist in canonical records.
IDs must be unique within a collection. Member handles are case-insensitively
unique. Invalid dates, unsupported fields, malformed URLs, multiple EDN forms,
and reader tags fail validation.

Meeting times must be unambiguous in the published time zone. Times skipped by
the spring daylight-saving transition, or repeated by the autumn transition,
fail validation rather than shifting silently or choosing an arbitrary offset.
Choose an unambiguous local time. The build derives `:start-utc`, `:end-utc`,
`:utc-offset`, and `:date-label`; do not put these generated fields in source EDN.
Duration is elapsed minutes, including across a daylight-saving change.

The build writes `meetings/<id>/event.ics` only for real `:scheduled` records.
These static downloads use UTC event times and are included in the offline
edition. When the schedule changes, rebuild and download the updated event;
this is not a subscribed or live-synchronized calendar. Mark completed meetings
`:past` and add public, absolute HTTPS `:notes-url` and/or `:recording-url` links.
Never publish internal meeting links.

Shared tags also connect talks and snippets through **Related reading**.
Suggestions exclude the current record, rank by number of shared tags, and use
the canonical URL to break ties. At most three links appear. Real content never
recommends fictional examples. Meeting pages also exclude talks already listed
as their own material. Tag records specifically, such as `:eglot`, rather
than relying only on broad tags like `:built-in`.

Add any source paragraph containing search terms you want to expose: the index
covers titles, descriptions, body text, code, tags, member interests, commands,
and author/speaker handles and display names.
Generated output is in `data/generated/`; do not edit it manually.
Related-reading sections are deliberately excluded from the source page's search
text so neighboring titles do not create misleading search matches.

The decorative hero collection lives separately in `content/constellation.edn`:
one map with a `:names` vector of unique Emacs package/built-in names. Use lowercase
letters, digits, and hyphens, starting with a letter; names may be at most 24
characters. Keep at least 36 names for the 18 fixed asterisk positions. These
labels are not package recommendations, membership records, or search entries.
The cycling model and geometry live in `constellation.cljc`; native animation and
visibility/pause handling live in `motion.cljs`. Prefer recognizable Emacs package
or mode names, not bare language names. Keep the mobile artwork behind the text,
non-interactive, and faint. Pause remains available through `M-x toggle-animation`.
The alternate orbital mobile has pure beam geometry and central-word cycling in
`parentheses.cljc` and shares that motion lifecycle. Keep each beam's tail behind
its head in both directions, fading from transparent to opaque. Its gradients
and native keyframes replace a per-frame drawing loop. Boot chooses one illustration per page load;
rendering must not choose randomly. Keep both the same size, cancel animations
when unmounting, and run only the selected illustration's cycling clock.
The larger-screen navigation underline is rendered declaratively and animated by
`nav.cljs`; it must follow both pointer previews and keyboard focus.

## Commands & UI

Add commands as data in `src/scratch/commands.cljc`. Views and reducers stay pure.
Put new browser effects in `effects.cljs`, register an action in `actions.cljs`,
and test the transition. Keep normal `href` links even when intercepting navigation.
An optional `:contexts` set of buffer IDs boosts equally matching commands in
relevant buffers without hiding anything. State-aware annotations and safe
matching highlights are generated from plain data; never inject HTML to highlight
user input. Calendar download links use native `download` attributes and must not
be intercepted as buffer navigation.

UI changes should preserve keyboard and touch use, focus restoration, visible
focus, contrast in both themes, reduced motion, and no horizontal body scrolling
at 320px. Browser tests cover routes, history, completion, themes, axe accessibility,
small/mobile/tablet/desktop widths, no-JavaScript content, and offline navigation.
After building, use `npm run test:browser:smoke` for the core checks. Run affected
tests with `npm run test:browser -- --grep "test title"`; `npm run test:browser`
runs the full suite. Keep critical regressions in the `@smoke` selection, which
runs on pull requests and ordinary publishes. Scheduled and manual workflow runs
retain full coverage.

## Public information only

Everything shipped to Pages is public. Do not publish credentials, confidential
configuration, internal joining URLs, corporate directory information, or another
person’s profile without their consent. Optional member fields are opt-in.
Public GitHub avatars are resized to static 96px PNGs by `scripts/avatars.mjs`
and published locally; do not embed remote images or fetch other profile data.
Local builds reuse `.cache/avatars/`; delete a member's cached PNG to refresh a
changed avatar. CI downloads fresh images in its clean workspace.

The Teams chat URL in `site.edn` was supplied for publication. Keep it a Microsoft
Teams HTTPS link and do not embed chat or fetch tenant/member information. Access
may require sign-in; a link does not imply that the conversation is public.

For conversation, use GitHub Discussions. The Pages build snapshots public
threads and all comment/reply pages, excluding minimized comments, using a server-side token with
`discussions: read`. Conversation changes and a six-hour schedule rebuild the
snapshot. API errors fail the build rather than publishing empty conversations.
Local builds without a token reuse their cached snapshot, or explicitly report
that no snapshot is available. Never add a token to source, frontend code, or
generated data.

Set `:discussion-number` on a content record to display that thread beneath the
buffer. Other buffers offer the six most recently updated threads. Readers load
only same-origin JSON, once per page load, with loading/error/retry states and a
UTC snapshot timestamp. Posts are rendered as escaped plain text; no GitHub HTML,
remote avatars, or profile enrichment is embedded. Saved snapshots also work
offline but are never labelled live. All posting still happens on GitHub.

For bugs and concrete tasks, use Issues.
Do not invent meeting schedules, membership, contribution history, or activity.
