import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as Y from "yjs";

if (process.argv[2] === "verify") {
  const document = new Y.Doc({ gc: false });
  const fragment = document.getXmlFragment("9253");
  for (const path of process.argv.slice(3)) {
    Y.applyUpdate(document, new Uint8Array(readFileSync(path)));
  }
  assert.equal(
    fragment.toString(),
    '<paragraph index="0" node_ids="source-node-1" prompt="Explain the control">클라이언트 호환성 𠮷 / live edit</paragraph>',
  );
  process.exit(0);
}

// Exact bytes emitted by ddq-client's real AnswerDocAttrs schema through
// @tiptap/y-tiptap 3.0.8 and Yjs 13.6.32. The fixture contains deleted legacy
// Y.Array history and the current Y.XmlFragment under the same root. Source:
// tests/collab/ddq-yjs-fixture.ts in ddq-client, SHA-256
// 05a2a534e1fe5ddb207b566218a523a130541d1c730a993f74e5b18b6699ffd4.
const initial = Buffer.from(
  "AgbKAQAHAQQ5MjUzAwlwYXJhZ3JhcGgHAMoBAAYEAMoBAR7tgbTrnbzsnbTslrjtirgg7Zi47ZmY7ISxIPCgrrcoAMoBAAVpbmRleAF9ACgAygEACG5vZGVfaWRzAXUBdw1zb3VyY2Utbm9kZS0xKADKAQAGcHJvbXB0AXcTRXhwbGFpbiB0aGUgY29udHJvbAFlAAgBBDkyNTMBdgEGc2NoZW1hdwxsZWdhY3ktYXJyYXkBZQEAAQ==",
  "base64",
);

const editor = new Y.Doc({ gc: false });
editor.clientID = 303;
const editorFragment = editor.getXmlFragment("9253");
Y.applyUpdate(editor, initial);
const incremental = [];
editor.on("update", (update) => incremental.push(update));
const paragraph = editorFragment.get(0);
assert.ok(paragraph instanceof Y.XmlElement);
const text = paragraph.get(0);
assert.ok(text instanceof Y.XmlText);
text.insert(text.length, " / live edit");

process.stdout.write(
  [initial, ...incremental, Y.encodeStateAsUpdate(editor)]
    .map((update) => Buffer.from(update).toString("base64"))
    .join("\n"),
);
