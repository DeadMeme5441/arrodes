import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, test } from "bun:test";
import { writePayload } from "./build-release";

test("release payload is compressed gzip and extracts every original byte", async () => {
  const root = mkdtempSync(join(tmpdir(), "arrodes-payload-"));
  try {
    const source = join(root, "source");
    mkdirSync(join(source, "nested"), { recursive: true });
    const text = "retained runtime content\n".repeat(50_000);
    const binary = new Uint8Array([0, 255, 128, 10, 13, 42]);
    writeFileSync(join(source, "nested", "content.txt"), text);
    writeFileSync(join(source, "native.bin"), binary);
    const destination = join(root, "payload.tar.gz");
    await writePayload(source, destination);
    const bytes = readFileSync(destination);
    expect([...bytes.subarray(0, 3)]).toEqual([0x1f, 0x8b, 0x08]);
    expect(bytes.byteLength).toBeLessThan(Buffer.byteLength(text) / 4);
    const extracted = join(root, "extracted");
    mkdirSync(extracted);
    await new Bun.Archive(bytes).extract(extracted);
    expect(readFileSync(join(extracted, "nested", "content.txt"), "utf8")).toBe(text);
    expect([...readFileSync(join(extracted, "native.bin"))]).toEqual([...binary]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
