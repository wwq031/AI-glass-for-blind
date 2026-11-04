import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { copyFile, mkdir } from "node:fs/promises";
import { parseArgs } from "node:util";
import { resolve } from "node:path";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
// 同一份 Agent 可以进入不同 Android 宿主，避免复制业务代码。
const { values } = parseArgs({ options: { outdir: { type: "string" } } });
const output = values.outdir ? resolve(values.outdir) :
  join(root, "apps/phone-companion/android/src/main/assets/agent");
await mkdir(output, { recursive: true });
await build({
  entryPoints: [join(root, "apps/phone-companion/src/android/agent-browser.ts")],
  outfile: join(output, "agent.js"),
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "chrome120",
});
const html = join(root, "apps/phone-companion/android/src/main/assets/agent/index.html");
if (resolve(html) !== resolve(output, "index.html")) {
  await copyFile(html, join(output, "index.html"));
}
