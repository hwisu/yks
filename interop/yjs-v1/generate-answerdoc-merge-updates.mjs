import * as Y from "yjs";
import { readFileSync } from "node:fs";

const seedDocument = new Y.Doc();
const question = new Y.Map();
question.set("id", 42);
question.set("status", "IN_PROGRESS");
question.set("lastAppliedSourceId", null);
question.set("assignUser", ["user-1"]);
question.set("answer", {
  type: "doc",
  content: [
    {
      type: "paragraph",
      attrs: { index: 0, node_ids: ["node-seed"] },
      content: [{ type: "text", text: "초기 답변 😀" }],
    },
  ],
});
question.set("lastMutationActorId", null);
question.set("lastMutationActorName", null);
question.set("lastMutationAt", null);
question.set("lastMutationId", null);
seedDocument.getMap("questions").set("42", question);
replaceAnswer(seedDocument, "초기 답변 😀", "node-seed");

const seed = process.argv[2]
  ? new Uint8Array(readFileSync(process.argv[2]))
  : Y.encodeStateAsUpdate(seedDocument);
const client = new Y.Doc();
Y.applyUpdate(client, seed);
const incremental = [];
client.on("update", (update) => incremental.push(update));
const clientQuestion = client.getMap("questions").get("42");
if (process.argv[3] === "typing") {
  const paragraph = client.getXmlFragment("42").get(0);
  const text = paragraph.get(0);
  for (const value of "Ktor 수정") text.insert(text.length, value);
  clientQuestion.set("lastMutationActorId", "user-1");
  clientQuestion.set("lastMutationActorName", "Collab Owner");
  clientQuestion.set("lastMutationAt", "2026-08-02T00:00:00.000Z");
  clientQuestion.set("lastMutationId", "marker");
} else {
  clientQuestion.set("status", "IN_REVIEW");
  clientQuestion.set("lastMutationActorId", "user-1");
  clientQuestion.set("lastMutationActorName", "Collab Owner");
  clientQuestion.set("lastMutationAt", "2026-08-02T00:00:00.000Z");
  clientQuestion.set("lastMutationId", "marker");
  replaceAnswer(client, "Ktor 동기화 𠮷", "node-updated");
}

const server = new Y.Doc();
Y.applyUpdate(server, seed);
incremental.forEach((update) => Y.applyUpdate(server, update));
const fullState = Y.encodeStateAsUpdate(server);
process.stdout.write(
  [seed, ...incremental, fullState]
    .map((update) => Buffer.from(update).toString("base64"))
    .join("\n"),
);

function replaceAnswer(document, value, nodeId) {
  const fragment = document.getXmlFragment("42");
  if (fragment.length > 0) fragment.delete(0, fragment.length);
  const paragraph = new Y.XmlElement("paragraph");
  paragraph.setAttribute("index", 0);
  paragraph.setAttribute("node_ids", [nodeId]);
  const text = new Y.XmlText();
  text.insert(0, value);
  paragraph.insert(0, [text]);
  fragment.insert(0, [paragraph]);
}
