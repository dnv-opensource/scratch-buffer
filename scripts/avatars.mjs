import sharp from "sharp";
import { execFile } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { promisify } from "node:util";

const execute = promisify(execFile);
const response = await execute("bb", ["-cp", "src:scripts", "-e",
  "(require '[scratch.content :as content] '[cheshire.core :as json]) (println (json/generate-string (mapv :github (remove :example? (:members (content/load-data \".\"))))))"
]);
const handles = JSON.parse(response.stdout);
await mkdir(".cache/avatars", { recursive: true });
for (const handle of handles) {
  const path = `.cache/avatars/${handle}.png`;
  let bytes;
  try {
    bytes = await readFile(path);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    const download = await execute("curl", [
      "--fail", "--silent", "--show-error", "--location",
      "--proto", "=https", "--proto-redir", "=https",
      "--max-redirs", "3", "--max-time", "20", "--retry", "2",
      "--max-filesize", "1048576", `https://github.com/${handle}.png?size=96`
    ], { encoding: "buffer", maxBuffer: 1024 * 1024 });
    bytes = download.stdout;
  }
  const image = await sharp(bytes, { limitInputPixels: 16 * 1024 * 1024 })
    .rotate().resize(96, 96, { fit: "cover" }).png().toBuffer();
  await writeFile(path, image);
}
console.log(`Prepared ${handles.length} public GitHub avatar assets.`);
