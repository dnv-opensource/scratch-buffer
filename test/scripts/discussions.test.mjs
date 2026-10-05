import { test } from "node:test";
import assert from "node:assert/strict";
import { exportDiscussions } from "../../scripts/discussions.mjs";

const repository = "https://github.com/example/community";
const date = "2026-10-05T14:00:00Z";
const page = (nodes, cursor = null) => ({ nodes, pageInfo: { hasNextPage: !!cursor, endCursor: cursor } });
const thread = number => ({
  number, title: `Thread ${number}`, url: `${repository}/discussions/${number}`,
  bodyText: "<script>not executable</script>", createdAt: date, updatedAt: date,
  author: { login: "example-member", url: "https://github.com/example-member" }
});
const comment = (id, replies = page([])) => ({
  id, url: `${repository}/discussions/2#discussioncomment-${id}`,
  bodyText: `Comment ${id}`, createdAt: date, author: null, isMinimized: false, replies
});
const response = data => ({ ok: true, json: async () => ({ data }) });

test("exports all discussion, comment, and reply pages without publishing credentials or API-only fields", async () => {
  const requests = [];
  const snapshot = await exportDiscussions({
    repository, token: "secret-test-token", now: () => date,
    fetcher: async (url, options) => {
      assert.equal(url, "https://api.github.com/graphql");
      assert.equal(options.headers.Authorization, "Bearer secret-test-token");
      const { query, variables } = JSON.parse(options.body);
      requests.push(variables);
      if (query.includes("hasDiscussionsEnabled")) return response({ repository: { isPrivate: false, hasDiscussionsEnabled: true } });
      if (query.includes("discussions(first")) {
        return response({ repository: { discussions: variables.after ? page([thread(1)]) : page([thread(2)], "thread-next") } });
      }
      if (query.includes("comments(first")) {
        const comments = variables.number === 1 ? page([]) :
          variables.after ? page([comment(20)]) : page([comment(10, page([comment(11)], "reply-next"))], "comment-next");
        return response({ repository: { discussion: { comments } } });
      }
      assert.equal(variables.id, 10);
      assert.equal(variables.after, "reply-next");
      return response({ node: { replies: page([comment(12)]) } });
    }
  });
  assert.equal(requests.length, 7);
  assert.equal(snapshot.status, "ready");
  assert.equal(snapshot["generated-at"], date);
  assert.deepEqual(snapshot.discussions.map(thread => thread.number), [2, 1]);
  assert.equal(snapshot.discussions[0].comments.length, 2);
  assert.equal(snapshot.discussions[0].comments[0].replies.length, 2);
  assert.equal(snapshot.discussions[0].body, "<script>not executable</script>");
  assert.equal(snapshot.discussions[0].comments[0].author, null);
  assert.equal(JSON.stringify(snapshot).includes("secret-test-token"), false);
  assert.equal(JSON.stringify(snapshot).includes("bodyHTML"), false);
  assert.equal("id" in snapshot.discussions[0].comments[0], false);
  assert.equal("replies" in snapshot.discussions[0].comments[0].replies[0], false);
});

test("a public repository with Discussions disabled is explicit and needs no discussion queries", async () => {
  let requests = 0;
  const snapshot = await exportDiscussions({
    repository, token: "test", now: () => date,
    fetcher: async () => { requests++; return response({ repository: { isPrivate: false, hasDiscussionsEnabled: false } }); }
  });
  assert.equal(requests, 1);
  assert.equal(snapshot.status, "disabled");
  assert.deepEqual(snapshot.discussions, []);
});

test("a public empty forum is a successful empty snapshot", async () => {
  const snapshot = await exportDiscussions({
    repository, token: "test",
    fetcher: async (_, options) => JSON.parse(options.body).query.includes("hasDiscussionsEnabled")
      ? response({ repository: { isPrivate: false, hasDiscussionsEnabled: true } })
      : response({ repository: { discussions: page([]) } })
  });
  assert.equal(snapshot.status, "ready");
  assert.deepEqual(snapshot.discussions, []);
});

test("minimized comments and replies are not republished", async () => {
  const snapshot = await exportDiscussions({
    repository, token: "test",
    fetcher: async (_, options) => {
      const { query } = JSON.parse(options.body);
      if (query.includes("hasDiscussionsEnabled")) return response({ repository: { isPrivate: false, hasDiscussionsEnabled: true } });
      if (query.includes("discussions(first")) return response({ repository: { discussions: page([thread(2)]) } });
      return response({ repository: { discussion: { comments: page([
        { ...comment(1), isMinimized: true, bodyText: "Hidden comment" },
        comment(2, page([{ ...comment(3), isMinimized: true, bodyText: "Hidden reply" }, comment(4)]))
      ]) } } });
    }
  });
  assert.equal(snapshot.discussions[0].comments.length, 1);
  assert.equal(snapshot.discussions[0].comments[0].replies.length, 1);
  assert.equal(JSON.stringify(snapshot).includes("Hidden"), false);
});

for (const [description, fetcher, message] of [
  ["private repositories", async () => response({ repository: { isPrivate: true, hasDiscussionsEnabled: true } }), /Private repository/],
  ["missing repositories", async () => response({ repository: null }), /resolve/],
  ["HTTP failures", async () => ({ ok: false, status: 403 }), /HTTP 403/],
  ["GraphQL failures", async () => ({ ok: true, json: async () => ({ errors: [{ message: "Denied" }] }) }), /could not read/],
  ["network failures", async () => { throw new Error("Network unavailable"); }, /Network unavailable/]
]) {
  test(`${description} fail instead of publishing an empty success`, async () => {
    await assert.rejects(exportDiscussions({ repository, token: "test", fetcher }), message);
  });
}

for (const cursor of [null, "same-cursor"]) {
  test(`invalid pagination (${cursor}) fails explicitly`, async () => {
    await assert.rejects(exportDiscussions({
      repository, token: "test",
      fetcher: async (_, options) => JSON.parse(options.body).query.includes("hasDiscussionsEnabled")
        ? response({ repository: { isPrivate: false, hasDiscussionsEnabled: true } })
        : response({ repository: { discussions: { nodes: [], pageInfo: { hasNextPage: true, endCursor: cursor } } } })
    }), /pagination cursor/);
  });
}

test("missing credentials and non-GitHub repository URLs are rejected before making requests", async () => {
  for (const options of [{ repository, token: "" }, { repository: "https://example.org", token: "test" }]) {
    await assert.rejects(exportDiscussions({ ...options, fetcher: () => assert.fail("Unexpected network request") }), /required/);
  }
});
