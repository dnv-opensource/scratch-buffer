# `*scratch-buffer*`

A small, static website for the DNV Emacs User Group. Pages are buffers, the
footer is a mode-line, and `M-x` opens the command menu. Ordinary links work too.

Built with ClojureScript via Scittle, Replicant, and Nexus, and hosted on GitHub
Pages. No application backend is required.

An independent community initiative, not an official DNV publication.

## Build and run

Requires Node.js 24+, [Babashka](https://babashka.org/) 1.12.218+, and `curl`.

```sh
npm ci
npm run build
npm start
```

Open **http://127.0.0.1:4173/**. Rebuild after changing source or content; the
development server serves `_site/`. Use `PORT=4174 npm start` to change the port.
Builds cache the pinned runtimes and public GitHub avatars locally; published
assets are self-hosted.

## Checks

```sh
npm run validate
npx playwright install chromium
npm test
```

`npm test` runs unit tests, builds the site, and runs 11 core browser checks.
Use `npm run test:unit` for unit tests, `npm run test:browser:smoke` for the core
browser checks, or `npm run test:browser` for the full browser suite against the
current build. Pull requests and ordinary publishes run the core checks;
scheduled and manual workflow runs use the full suite.

## Publish

Review the public URLs and site information in `site.edn`. In the repository's
**Settings → Pages**, select **GitHub Actions** as the source. The included
workflow checks pull requests and deploys pushes to `main`. Discussion and comment
changes, a six-hour schedule, and manual runs also refresh the published site.

The workflow builds for `/<repository-name>/`. To try that locally:

```sh
BASE_PATH=/scratch-buffer/ npm run build
BASE_PATH=/scratch-buffer/ npm start
```

For a custom domain or an organization/user Pages site, set the workflow's
`BASE_PATH` to `/`. The publishable artifact is `_site/`.

Enable GitHub Discussions for community conversation. Actions uses its
read-only `GITHUB_TOKEN` permission to publish public threads, comments, and
replies as JSON; no token is shipped to the browser. Buffers load this snapshot
on opening, show its update time, and keep posting on GitHub. Set a content
record's `:discussion-number` to show a specific thread; other buffers show
recent discussions. Snapshots can lag behind GitHub and remain readable offline.

Local builds reuse `.cache/discussions/snapshot.json` if present, otherwise show
an explicit unavailable state. To refresh locally, supply a server-side
`GITHUB_TOKEN` with Discussions read access when running `npm run build`.
Private repository discussions are never exported; API failures stop deployment.

## Contribute

Published content lives in `content/`, and member records in `members/`.
Generated output in `data/generated/` and `_site/` must not be edited by hand.

See [CONTRIBUTING.md](CONTRIBUTING.md) for content formats and development
guidelines, and [members/README.md](members/README.md) for joining the group.
