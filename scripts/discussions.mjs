import { execFile } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execute = promisify(execFile);
const threadFields = "number title url bodyText createdAt updatedAt author { login url }";
const commentFields = "id url bodyText createdAt isMinimized author { login url }";
const pageFields = "pageInfo { hasNextPage endCursor }";

async function connection(query, variables, request) {
  const nodes = [];
  const cursors = new Set();
  let after = null;
  do {
    const page = await request(query, { ...variables, after });
    if (!page || !Array.isArray(page.nodes) || typeof page.pageInfo?.hasNextPage !== "boolean") {
      throw new Error("GitHub returned an invalid discussion connection.");
    }
    nodes.push(...page.nodes);
    if (!page.pageInfo.hasNextPage) return nodes;
    after = page.pageInfo.endCursor;
    if (typeof after !== "string" || !after || cursors.has(after)) {
      throw new Error("GitHub returned an invalid or repeated pagination cursor.");
    }
    cursors.add(after);
  } while (after);
}

function author(actor) {
  return actor ? { login: actor.login, url: actor.url } : null;
}

function comment(node) {
  return { url: node.url, body: node.bodyText, "created-at": node.createdAt, author: author(node.author) };
}

function visible(node) {
  if (typeof node.isMinimized !== "boolean") throw new Error("GitHub returned invalid comment moderation metadata.");
  return !node.isMinimized;
}

export async function exportDiscussions({ repository, token, fetcher = fetch, now = () => new Date().toISOString() }) {
  const match = /^https:\/\/github\.com\/([A-Za-z0-9-]+)\/([A-Za-z0-9._-]+)$/.exec(repository);
  if (!match || !token) throw new Error("A GitHub repository and server-side token are required.");
  const [, owner, name] = match;
  const request = async (query, variables) => {
    const response = await fetcher("https://api.github.com/graphql", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ query, variables }),
      signal: AbortSignal.timeout(20000)
    });
    if (!response.ok) throw new Error(`GitHub discussion request failed (HTTP ${response.status}).`);
    const result = await response.json();
    if (result.errors?.length || !result.data) {
      throw new Error("GitHub could not read the discussions. Check repository access and discussions:read permission.");
    }
    return result.data;
  };
  const variables = { owner, name };
  const metadata = (await request(`query($owner: String!, $name: String!) {
    repository(owner: $owner, name: $name) { isPrivate hasDiscussionsEnabled }
  }`, variables)).repository;
  if (!metadata || typeof metadata.isPrivate !== "boolean" || typeof metadata.hasDiscussionsEnabled !== "boolean") {
    throw new Error("GitHub could not resolve the configured repository.");
  }
  if (metadata.isPrivate) throw new Error("Private repository discussions must not be published to Pages.");
  const snapshot = { version: 1, repository, "generated-at": now(), status: "disabled", discussions: [] };
  if (!metadata.hasDiscussionsEnabled) return snapshot;
  const threads = await connection(`query($owner: String!, $name: String!, $after: String) {
    repository(owner: $owner, name: $name) {
      discussions(first: 50, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
        ${pageFields} nodes { ${threadFields} }
      }
    }
  }`, variables, async (query, vars) => (await request(query, vars)).repository?.discussions);
  const seen = new Set();
  for (const thread of threads) {
    if (!Number.isSafeInteger(thread.number) || thread.number < 1 || seen.has(thread.number)) {
      throw new Error("GitHub returned an invalid or duplicate discussion number.");
    }
    seen.add(thread.number);
    const comments = await connection(`query($owner: String!, $name: String!, $number: Int!, $after: String) {
      repository(owner: $owner, name: $name) {
        discussion(number: $number) {
          comments(first: 50, after: $after) { ${pageFields}
            nodes { ${commentFields} replies(first: 50) { ${pageFields} nodes { ${commentFields} } } }
          }
        }
      }
    }`, { ...variables, number: thread.number },
    async (query, vars) => (await request(query, vars)).repository?.discussion?.comments);
    const normalized = [];
    for (const node of comments) {
      if (!visible(node)) continue;
      let first = true;
      const replies = await connection(`query($id: ID!, $after: String) {
        node(id: $id) { ... on DiscussionComment {
          replies(first: 50, after: $after) { ${pageFields} nodes { ${commentFields} } }
        } }
      }`, { id: node.id }, async (query, vars) => {
        if (first) { first = false; return node.replies; }
        return (await request(query, vars)).node?.replies;
      });
      normalized.push({ ...comment(node), replies: replies.filter(visible).map(comment) });
    }
    snapshot.discussions.push({
      number: thread.number, title: thread.title, url: thread.url, body: thread.bodyText,
      "created-at": thread.createdAt, "updated-at": thread.updatedAt,
      author: author(thread.author), comments: normalized
    });
  }
  snapshot.status = "ready";
  snapshot["generated-at"] = now();
  return snapshot;
}

async function main() {
  const { stdout } = await execute("bb", ["-cp", "src:scripts", "-e",
    "(require '[scratch.content :as content]) (print (get-in (content/load-data \".\") [:site :repository]))"
  ]);
  const repository = stdout.trim();
  const path = ".cache/discussions/snapshot.json";
  let snapshot;
  if (process.env.GITHUB_TOKEN) {
    snapshot = await exportDiscussions({ repository, token: process.env.GITHUB_TOKEN });
  } else {
    try {
      snapshot = JSON.parse(await readFile(path, "utf8"));
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      snapshot = { version: 1, repository, "generated-at": null, status: "unavailable", discussions: [] };
    }
    console.warn("No GITHUB_TOKEN provided; using the local discussion snapshot, if available. No live GitHub request made.");
  }
  if (snapshot.repository !== repository) throw new Error("Cached discussions belong to a different repository.");
  await mkdir(".cache/discussions", { recursive: true });
  await writeFile(path, JSON.stringify(snapshot));
  console.log(`Prepared ${snapshot.discussions.length} public discussions (${snapshot.status}).`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
