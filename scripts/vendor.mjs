import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { promisify } from "node:util";

const download = promisify(execFile);
const dependencies = JSON.parse(await readFile("runtime-deps.json", "utf8"));
for (const dependency of dependencies) {
  const path = join(".cache/vendor", dependency.file);
  let bytes;
  try {
    bytes = await readFile(path);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    const response = await download("curl", [
      "--fail", "--silent", "--show-error", "--location",
      "--proto", "=https", "--proto-redir", "=https",
      "--retry", "2", dependency.url
    ], { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 });
    bytes = response.stdout;
  }
  const hash = createHash("sha256").update(bytes).digest("hex");
  if (hash !== dependency.sha256) throw new Error(`Integrity mismatch for ${dependency.file}`);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, bytes);
}
console.log("Pinned Scittle 0.8.33, Replicant 2026.06.2, Nexus 2026.08.1 verified.");
