import sharp from "sharp";
import { mkdir } from "node:fs/promises";

await mkdir(".cache/icons", { recursive: true });
for (const size of [32, 180, 192, 512]) {
  await sharp("assets/icons/mark.svg").resize(size, size).png()
    .toFile(`.cache/icons/icon-${size}.png`);
}
console.log("Raster icons generated from the vector source.");
