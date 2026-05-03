#!/usr/bin/env node
import { copyFile, cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

const args = process.argv.slice(2);
const options = new Map();
for (let i = 0; i < args.length; i += 2) {
  const key = args[i];
  const value = args[i + 1];
  if (!key?.startsWith("--") || value == null) {
    usage();
  }
  options.set(key.slice(2), value);
}

const version = options.get("version") ?? "0.2.0";
const outDir = options.get("out");
if (!outDir) {
  usage();
}

const resolvedOut = resolve(outDir);
const installDir = join(resolvedOut, "npm-install");
const rootDir = join(resolvedOut, "root");
const nodeModulesDir = join(installDir, "node_modules");
const runtimeNodeModulesDir = join(rootDir, "usr", "lib", "node_modules");
const runtimeBinDir = join(rootDir, "usr", "bin");
const registry = process.env.OPENCLAW_ANDROID_NPM_REGISTRY ?? "https://registry.npmmirror.com";

await rm(resolvedOut, { recursive: true, force: true });
await mkdir(installDir, { recursive: true });
await mkdir(runtimeNodeModulesDir, { recursive: true });
await mkdir(runtimeBinDir, { recursive: true });
await writeFile(
  join(installDir, "package.json"),
  JSON.stringify({ private: true, dependencies: {} }, null, 2),
);

const npmArgs = [
  "install",
  `openclaw-cn@${version}`,
  "--prefix",
  installDir,
  "--omit=dev",
  "--ignore-scripts",
  "--no-audit",
  "--no-fund",
  "--install-links=false",
  "--registry",
  registry,
];
const npmCommand = process.platform === "win32" ? "cmd.exe" : "npm";
const commandArgs =
  process.platform === "win32"
    ? ["/d", "/s", "/c", "npm.cmd", ...npmArgs]
    : npmArgs;
const npmResult = spawnSync(
  npmCommand,
  commandArgs,
  {
    cwd: installDir,
    stdio: "inherit",
    env: {
      ...process.env,
      npm_config_platform: "android",
      npm_config_arch: "arm64",
      npm_config_ignore_scripts: "true",
      npm_config_audit: "false",
      npm_config_fund: "false",
    },
  },
);
if (npmResult.error) {
  console.error(npmResult.error);
  process.exit(1);
}
if (npmResult.status !== 0) {
  process.exit(npmResult.status ?? 1);
}

if (!existsSync(join(nodeModulesDir, "openclaw-cn", "dist", "entry.js"))) {
  throw new Error("openclaw-cn runtime did not include dist/entry.js");
}

await cp(nodeModulesDir, runtimeNodeModulesDir, { recursive: true });
await patchGatewayClientCompatibility(
  join(runtimeNodeModulesDir, "openclaw-cn", "dist", "gateway", "protocol", "client-info.js"),
);
await patchAndroidClipboardFallback(
  join(runtimeNodeModulesDir, "@mariozechner", "clipboard", "index.js"),
);
await writeFile(
  join(runtimeBinDir, "openclaw-cn"),
  `#!/system/bin/sh
exec "\${OPENCLAW_NODE:-node}" "\${PREFIX}/lib/node_modules/openclaw-cn/dist/entry.js" "$@"
`,
);
await copyFile(join(runtimeBinDir, "openclaw-cn"), join(runtimeBinDir, "openclaw"));
await writeFile(
  join(rootDir, "usr", ".openclaw-android-runtime.version"),
  `openclaw-cn@${version}
android-runtime=3
`,
);

function usage() {
  console.error("Usage: prepare-openclaw-android-runtime.mjs --version <version> --out <dir>");
  process.exit(2);
}

async function patchAndroidClipboardFallback(clipboardIndexPath) {
  if (!existsSync(clipboardIndexPath)) {
    return;
  }

  const source = await readFile(clipboardIndexPath, "utf8");
  const marker = "if (!nativeBinding) {";
  if (!source.includes(marker)) {
    throw new Error("Unexpected @mariozechner/clipboard index.js layout");
  }

  const fallback = `
if (!nativeBinding && platform === 'android') {
  const missing = () => false
  const emptyText = () => ''
  const emptyFormats = () => []
  const emptyBinary = () => new Uint8Array()
  const unsupportedWrite = () => {
    throw new Error('Android clipboard native binding is unavailable')
  }
  nativeBinding = {
    availableFormats: emptyFormats,
    getText: emptyText,
    setText: unsupportedWrite,
    hasText: missing,
    getImageBinary: emptyBinary,
    getImageBase64: emptyText,
    setImageBinary: unsupportedWrite,
    setImageBase64: unsupportedWrite,
    hasImage: missing,
    getHtml: emptyText,
    setHtml: unsupportedWrite,
    hasHtml: missing,
    getRtf: emptyText,
    setRtf: unsupportedWrite,
    hasRtf: missing,
    clear: () => {},
    watch: () => ({ close: () => {} }),
    callThreadsafeFunction: () => {},
  }
}

`;
  await writeFile(clipboardIndexPath, source.replace(marker, `${fallback}${marker}`));
}

async function patchGatewayClientCompatibility(clientInfoPath) {
  if (!existsSync(clientInfoPath)) {
    return;
  }

  const source = await readFile(clientInfoPath, "utf8");
  if (source.includes('ANDROID_APP_OPENCLAW: "openclaw-android"')) {
    return;
  }

  const marker = '    ANDROID_APP: "clawdbot-android",';
  if (!source.includes(marker)) {
    throw new Error("Unexpected gateway client-info.js layout");
  }

  const openClawIds = `
    CONTROL_UI_OPENCLAW: "openclaw-control-ui",
    MACOS_APP_OPENCLAW: "openclaw-macos",
    IOS_APP_OPENCLAW: "openclaw-ios",
    ANDROID_APP_OPENCLAW: "openclaw-android",
    PROBE_OPENCLAW: "openclaw-probe",`;
  await writeFile(clientInfoPath, source.replace(marker, `${marker}${openClawIds}`));
}
