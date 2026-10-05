# AGENTS.md

## Project

This repository contains **DNV `*scratch-buffer*`**, a static, responsive, PWA-capable website for the DNV Emacs User Group.

The site is hosted on **GitHub Pages** and should require no traditional application backend for its core functionality.

The central product idea is:

> A beautifully typeset community website whose interaction grammar borrows from Emacs.

It is **not** an Emacs emulator, terminal-themed novelty site, or generic dashboard with Emacs colors.

---

## Core product principles

Preserve these ideas throughout implementation:

1. A page is conceptually a **buffer**.
2. The persistent footer is a **mode-line**.
3. `M-x` opens the primary command interface.
4. Command completion is vertically listed and inspired by `icomplete-vertical` / Vertico.
5. Emacs keyboard shortcuts are power-user affordances, never the only way to perform an action.
6. Typography, whitespace, hierarchy, and motion matter more than decorative UI.
7. The site must remain understandable to people who do not use Emacs.
8. Prefer a few polished Emacs interactions over many gimmicks.
9. GitHub is the collaboration/persistence layer.
10. Static/generated data is preferred over runtime API dependency.

The defining interaction concepts are:

```text
buffer
mode-line
minibuffer
M-x
```

Everything else is secondary.

---

## Required technology

Use:

- GitHub Pages
- GitHub Actions
- HTML5
- modern CSS
- CSS custom properties
- ClojureScript via Scittle
- Replicant
- Nexus
- m1p where useful for textual/presentation dictionaries

Do not introduce React, Vue, Angular, Svelte, or another large frontend framework.

Prefer browser APIs and small focused dependencies.

Use current stable mutually compatible versions.

---

## Architecture

Keep application state as plain data.

A representative state shape:

```clojure
{:route {}
 :buffer {:current :scratch
          :previous nil}
 :minibuffer {:open? false
              :query ""
              :selection 0
              :history []}
 :theme {:mode :system}
 :content {}
 :members {}
 :github {}
 :ui {}
 :pwa {}}
```

Rendering should remain conceptually pure:

```clojure
(view state) => hiccup
```

### Responsibilities

**Replicant**
- render application state to the DOM
- keep views declarative
- avoid imperative DOM manipulation except where browser APIs require it

**Nexus**
- represent actions/effects as data
- keep side effects outside pure rendering logic

Example actions:

```clojure
[:buffer/open :meetings]
[:buffer/previous]
[:minibuffer/open]
[:minibuffer/set-query "mee"]
[:command/execute :meetings]
[:theme/set :dark]
[:github/load-discussion 123]
```

Effects may include:

- network requests
- History API
- localStorage
- clipboard
- focus
- scroll restoration
- service worker/PWA operations

Do not bury side effects inside view functions.

---

## Suggested repository layout

Keep code, source content, membership records, and generated data clearly separated.

```text
/
├── index.html
├── manifest.webmanifest
├── sw.js
├── assets/
│   ├── css/
│   ├── icons/
│   └── fonts/
├── src/
│   ├── app.cljs
│   ├── state.cljs
│   ├── view.cljs
│   ├── actions.cljs
│   ├── effects.cljs
│   ├── router.cljs
│   ├── commands.cljs
│   ├── keyboard.cljs
│   ├── github.cljs
│   ├── pwa.cljs
│   └── views/
├── content/
│   ├── pages/
│   ├── meetings/
│   ├── talks/
│   ├── snippets/
│   └── packages/
├── members/
├── data/
│   └── generated/
├── scripts/
└── .github/
    └── workflows/
```

Do not treat this structure as immutable if a simpler structure emerges, but preserve the separation of concerns.

---

## Routing and buffers

Every primary page is represented internally as a named buffer.

Examples:

```text
*scratch-buffer*
*about*
*meetings*
*talks*
*snippets*
*packages*
*people*
*join*
*person/henrik*
```

URLs must remain normal and human-readable:

```text
/
/about/
/meetings/
/talks/
/people/
/people/henrik/
```

Requirements:

- browser Back works
- browser Forward works
- direct URL entry works
- page refresh works
- links can be opened in a new tab
- URLs are copyable/bookmarkable
- navigation updates document title
- navigation updates mode-line state
- navigation updates buffer state

Do not create an application that only works through in-memory navigation.

---

## Main UI

Use a calm, editorial layout.

Avoid:

- dashboard grids
- excessive cards
- large shadows
- pill-heavy controls
- fake terminal windows
- faux editor chrome
- decorative code rain
- oversized gradients
- generic SaaS patterns

Prefer:

- typography
- whitespace
- rules
- indentation
- hierarchy
- restrained color
- text-first interaction

Prose should generally use a comfortable reading measure of roughly 65–80 characters.

---

## Mode-line

The mode-line is persistent at the bottom of the viewport.

Desktop example:

```text
DNV  *meetings*  Emacs User Group  67%  UTF-8  M-x
```

Mobile example:

```text
*meetings*  67%  M-x
```

Use responsive priority rules: remove low-value segments before allowing crowding.

Interactive segments should include, where appropriate:

- current buffer
- scroll position
- `M-x`
- mode/group information
- theme control

The mode-line may be visually stronger than the rest of the site.

The page content must never be obscured by it.

---

## Minibuffer and `M-x`

`M-x` is the signature interaction.

The minibuffer opens directly above the mode-line and keeps the underlying buffer visible.

Requirements:

- open by clicking/tapping `M-x`
- open by keyboard shortcut
- immediate filtering
- keyboard navigation
- pointer/touch navigation
- selected result state
- short command annotations
- Escape / `C-g` cancels
- Enter executes
- command history may use `M-p` / `M-n`

Example:

```text
M-x mee

  meetings          Browse meetings
  meeting-notes     Previous sessions
  propose-meeting   Suggest a meeting
```

Do not reproduce Emacs completion visuals literally. Use the interaction model, then apply modern typography and spacing.

---

## Command registry

Commands must be data-driven.

Example:

```clojure
{:id :meetings
 :name "meetings"
 :description "Browse upcoming and previous meetings"
 :category :navigation
 :action [:buffer/open :meetings]}
```

Initial command set:

```text
about
meetings
talks
snippets
packages
people
search
switch-buffer
previous-buffer
describe-mode
describe-key
join-user-group
submit-talk
suggest-topic
ask-question
discuss-page
random-tip
toggle-theme
set-theme
source
install
```

Adding a command should not require modifying minibuffer rendering logic.

---

## Keyboard behavior

Support Emacs-inspired shortcuts where practical:

```text
M-x       command interface
C-x b     switch buffer
C-g       cancel current minibuffer/modal state
C-s       site search
M-p       previous minibuffer history item
M-n       next minibuffer history item
```

Rules:

- do not override browser shortcuts casually
- call `preventDefault` only for deliberate supported commands
- every important action must also be accessible by pointer/touch
- never require Emacs knowledge to navigate the site

---

## Search

Site-wide search should cover:

- buffers/pages
- meetings
- talks
- snippets
- packages
- people
- relevant body text

Prefer a generated static search index.

Search should reuse the completion interaction model where practical.

---

## Content

Prefer EDN for naturally structured data.

Example meeting:

```clojure
{:id "2026-10-21-structural-editing"
 :title "Structural editing without losing your mind"
 :date "2026-10-21"
 :time "14:00"
 :speaker "alice"
 :duration-minutes 15
 :tags #{:editing :paredit :clojure}
 :description "..."}
```

Use Markdown for prose-heavy material when it improves authoring.

Do not force all content into one format.

Generated indexes may normalize source content for the frontend.

---

## Membership

Membership is explicit and stored in source control.

Do not use GitHub's computed contributor list as the membership database.

Each member has a small record under `members/`.

Minimum:

```clojure
{:github "username"
 :joined "YYYY-MM"}
```

Optional fields may include:

```clojure
{:favorite-command "magit-status"
 :interests #{:clojure :org-mode :elisp}}
```

Do not require:

- real name
- job title
- email
- team
- office
- photograph
- biography

Profile information beyond the minimum must be opt-in.

---

## Joining the group

Joining means making one small contribution.

`M-x join-user-group` should explain:

```text
1. Create your member entry.
2. Contribute one small useful thing.
3. Open a pull request.
4. Once merged, you are a member.
```

Valid contributions include:

- an Emacs tip
- package recommendation
- useful command
- configuration snippet
- documentation improvement
- typo fix
- accessibility improvement
- meeting suggestion
- another small useful change

The purpose is participation, not gatekeeping.

Do not require sophisticated code contributions.

---

## People view

Render `*people*` from canonical member records.

Prefer a text-first directory rather than corporate employee cards.

Example:

```text
*people*

42 members

henrik

  magit · clojure · org-mode

  M-x magit-status
```

A member may have a buffer such as:

```text
*person/henrik*
```

Optional enriched data may include:

- GitHub avatar
- short bio
- interests
- favorite command
- repository contributions
- talks
- snippets

Only display public or explicitly opt-in information.

---

## GitHub model

Use GitHub according to these semantics:

```text
repository files   = published knowledge
member files       = membership
commits            = history
pull requests      = contributions/changes
Discussions        = conversation
Issues             = bugs/tasks
GitHub Actions     = build/index/deployment
GitHub Pages       = hosting
```

Never place write-capable GitHub credentials in frontend code.

Assume everything shipped to GitHub Pages is public.

---

## GitHub Discussions

Prefer Discussions to Issues for community conversation.

Suggested categories:

```text
General
Questions
Show & tell
Talk proposals
Meeting topics
Site feedback
```

A page may store a discussion number:

```clojure
{:discussion-number 123}
```

Initial site behavior may be read-only.

`M-x discuss-page` may link to the relevant GitHub Discussion for posting.

Do not implement custom OAuth merely to make comments work in the MVP.

---

## Data loading

Prefer generated static data over live API calls.

Preferred model:

```text
GitHub
  ↓
GitHub Action
  ↓
generated EDN/JSON
  ↓
GitHub Pages
  ↓
Scittle application
```

Generate static snapshots for data such as:

- members
- meetings
- talks
- snippets
- packages
- search index
- selected GitHub metadata
- recent activity when appropriate

Use live GitHub API requests only when actual freshness is valuable.

The first meaningful render must not depend on a live GitHub request.

Handle API/network failure gracefully.

---

## Themes

Support exactly these theme modes at minimum:

```text
system
light
dark
```

Use semantic CSS tokens.

Required token concepts include:

```css
--bg
--surface
--text
--text-muted
--border
--accent
--focus
--selection
--code-bg
--mode-line-bg
--mode-line-fg
--minibuffer-bg
--minibuffer-fg
```

Components should consume semantic tokens, not arbitrary color values.

Persist explicit theme choice locally.

When using `system`, respond to operating-system theme changes.

Support:

```text
M-x toggle-theme
```

---

## Typography

Typography is a first-class design concern.

Use three voices:

### Editorial
For prose, introductions, article titles, meeting descriptions.

### UI
For metadata, dates, labels, annotations.

### Mono
For buffer names, commands, keybindings, code, mode-line, minibuffer.

Do not make the whole site monospace.

Optimize for:

- readable line length
- generous line-height
- consistent vertical rhythm
- strong hierarchy
- restrained number of type styles

Use system fonts or properly licensed/self-hosted web fonts.

Avoid large font payloads.

---

## Responsive behavior

The same conceptual UI must work across viewport sizes.

### Mobile

- one reading column
- compact mode-line
- no horizontal page scrolling
- code blocks may scroll independently
- touch targets are comfortably sized
- minibuffer behaves like a bottom command sheet
- completion may occupy most of the viewport

### Tablet

- expanded mode-line where space permits
- comfortable editorial measure
- bottom-oriented minibuffer is acceptable

### Desktop

- full mode-line
- centered editorial reading measure
- keyboard interaction is prominent but never required

Do not simply shrink the desktop layout.

---

## Motion

Motion must communicate continuity, not decoration.

Typical durations:

```text
100–150ms   immediate interaction
150–220ms   minibuffer/buffer transitions
200–300ms   larger contextual transitions
```

Keep translations subtle, usually around 6–10px.

Prefer opacity + small translation for buffer changes.

Do not animate every child independently.

Completion filtering should update immediately.

Honor:

```css
@media (prefers-reduced-motion: reduce)
```

Reduced-motion mode should remove nonessential transforms and make transitions nearly immediate.

---

## Accessibility

Target WCAG 2.2 AA behavior.

Requirements:

- semantic HTML
- logical headings
- full keyboard operation
- visible focus styles
- sufficient contrast
- meaningful link text
- accessible names for icon-only controls
- no color-only state
- no pointer-only functions
- no keyboard-only functions
- appropriate dialog/listbox semantics for minibuffer/completion UI
- reduced-motion support
- sensible focus management after navigation
- screen-reader announcements for important dynamic state changes where useful

Accessibility is not a final polish pass. Preserve it during implementation.

---

## PWA

Provide:

- `manifest.webmanifest`
- service worker
- favicon
- PWA icon set
- theme-color metadata

Manifest naming:

```text
name: DNV *scratch-buffer*
short_name: *scratch*
```

Support `M-x install` only where a meaningful install flow is available.

Otherwise provide useful explanatory behavior rather than a dead command.

---

## Offline behavior

Cache:

- application shell
- CSS
- application source/runtime
- icons
- fonts if used
- generated site indexes
- useful published content

When dynamic data cannot be refreshed, say so clearly.

Example:

```text
*Messages*

Offline.

Showing cached buffers from the most recent successful update.
GitHub discussions are unavailable.
```

Never imply cached dynamic data is current.

---

## Icon and favicon

Use a minimalist geometric identity based on something like:

```text
*_
```

or:

- asterisk
- cursor/block
- scratch-buffer metaphor

Requirements:

- recognizable at favicon size
- monochrome-capable
- works on light/dark surfaces
- vector source exists
- minimal detail
- no fake terminal window
- no generic `</>` developer mark
- do not use official GNU/Emacs artwork
- do not use official DNV branding unless explicitly authorized

---

## Security

Never ship:

- GitHub PATs
- GitHub App private keys
- OAuth client secrets
- internal credentials
- private API keys

Treat GitHub/Markdown/community content as untrusted input.

Prevent arbitrary HTML/script injection.

Prefer safe Markdown rendering.

Use an appropriate Content Security Policy when compatible with the chosen Scittle setup.

Prefer pinned/self-hosted dependencies where practical.

---

## Privacy

Assume public GitHub Pages content is public.

Do not scrape or expose corporate directory data.

Do not derive or publish:

- employee email
- organizational hierarchy
- team
- job title
- location
- internal identity metadata

unless explicitly provided for publication.

Membership records should be minimal by default.

---

## Error states

Prefer concise, useful messages.

Examples:

```text
*Messages*

No meetings are currently scheduled.

M-x propose-meeting
```

```text
*Messages*

Could not load GitHub discussion.

Retry
Open on GitHub
```

Do not overuse Emacs jargon when plain language is clearer.

---

## Testing expectations

At minimum, cover:

- command matching/filtering
- command execution
- reducer/state transitions
- routing
- Back/Forward behavior
- theme state
- data validation
- member parsing
- content parsing
- search indexing/search behavior
- GitHub failure states where applicable

Also manually verify:

- keyboard-only use
- touch use
- small mobile widths
- wide desktop widths
- light mode
- dark mode
- system mode
- reduced motion
- offline shell
- page refresh on nested routes

---

## CI/CD

Pull requests should:

1. validate structured data
2. run tests
3. run formatting/linting if configured
4. generate indexes
5. detect duplicate IDs
6. detect malformed member records
7. detect invalid dates/references
8. optionally perform static accessibility checks

The default branch should:

1. validate
2. generate static data
3. build/package the Pages artifact
4. deploy through GitHub Actions

Production must not require a server process.

---

## MVP scope

The MVP must include these buffers:

```text
*scratch-buffer*
*about*
*meetings*
*talks*
*people*
*snippets*
*packages*
*join*
```

The MVP must include:

- GitHub Pages deployment
- responsive layout
- fixed/sticky mode-line
- working `M-x`
- command filtering
- keyboard and pointer/touch operation
- buffer routing
- browser Back/Forward
- direct-link refresh support
- site search
- membership files
- people view
- join instructions
- light/dark/system themes
- persistent theme choice
- reduced-motion support
- favicon/PWA icon
- manifest
- useful service worker/offline shell
- GitHub Discussion linking or read-only integration
- graceful GitHub/network failure behavior

---

## Explicitly defer unless trivial

Do not expand MVP scope to include these unless the implementation is genuinely small and isolated:

- custom GitHub authentication
- native authenticated commenting
- scratch ClojureScript evaluator
- advanced offline synchronization
- push notifications
- complex member activity analysis
- social features
- elaborate animation systems
- custom CMS/editor

---

## Implementation order

Work in this order unless there is a strong technical reason not to:

### 1. Foundation
- HTML shell
- Scittle boot
- Replicant rendering
- Nexus actions/effects
- base state
- routing

### 2. Design system
- CSS tokens
- typography
- light/dark/system themes
- responsive content layout
- mode-line

### 3. Interaction grammar
- buffer navigation
- `M-x`
- command registry
- completion
- keyboard handling
- browser history
- focus behavior

### 4. Content
- content schemas
- scratch
- meetings
- talks
- snippets
- packages
- members
- join flow

### 5. GitHub
- generated indexes
- Discussion integration/links
- failure handling
- source/contribution links

### 6. PWA
- manifest
- favicon/icons
- service worker
- offline shell
- optional install command

### 7. Refinement
- motion
- reduced motion
- accessibility review
- responsive polish
- performance
- contributor documentation

---

## Decision rules

When implementation details are underspecified, prefer the option that is:

1. simpler
2. more semantic
3. more accessible
4. more browser-native
5. more data-driven
6. easier to maintain
7. less dependent on runtime GitHub APIs
8. less dependent on third-party JavaScript
9. faithful to the core interaction model without becoming theatrical

Avoid speculative abstractions.

Do not build infrastructure for hypothetical future features.

Do not add a dependency when a small browser-native implementation is clearer.

Do not create generic component systems before repeated patterns actually exist.

---

## Quality bar

Prioritize, in order:

1. interaction coherence
2. typography
3. accessibility
4. responsiveness
5. performance
6. maintainability
7. small implementation surface
8. playful Emacs details

When choosing between:

```text
15 mediocre Emacs gimmicks
```

and:

```text
4 extremely polished Emacs interactions
```

choose the latter.

---

## Final product constraint

At every stage, preserve this sentence:

> **DNV `*scratch-buffer*` is a modern community website that borrows Emacs' interaction grammar. It is not an Emacs simulator rendered in a browser.**
