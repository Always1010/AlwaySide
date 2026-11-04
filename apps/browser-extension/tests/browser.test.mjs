import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { captureEvidence, loadApiKey, saveApiKey, loadSettings, saveSettings, loadSessions, saveSessions, MAX_TEXT_LENGTH } from "../browser/api.mjs";

function event() {
  const listeners = new Set();
  return { addListener: (fn) => listeners.add(fn), removeListener: (fn) => listeners.delete(fn), fire: (...args) => { for (const fn of listeners) fn(...args); }, listeners };
}

function mockBrowser() {
  const source = { id: 4, windowId: 7, url: "https://example.org/article?private=1", title: "Source", status: "complete" };
  const calls = [];
  const localData = {};
  const sessionData = {};
  const area = (data, name) => ({
    async setAccessLevel(options) { calls.push(["access", name, options]); },
    async get(key) { return { [key]: data[key] }; },
    async set(value) { calls.push(["set", name, value]); Object.assign(data, value); },
  });
  const api = {
    windows: { async getCurrent() { return { id: 7 }; } },
    tabs: {
      async query() { return [{ ...source }]; },
      async get() { return { ...source }; },
      async captureVisibleTab() { calls.push(["screenshot"]); return "data:image/jpeg;base64,aGVsbG8="; },
      onActivated: event(), onUpdated: event(), onRemoved: event(),
    },
    scripting: { async executeScript(options) { calls.push(["script", options]); return [{ result: { url: source.url, title: source.title, capturedAt: "2026-11-04T01:00:00.000Z", text: "正文", truncated: false } }]; } },
    storage: { local: area(localData, "local"), session: area(sessionData, "session") },
  };
  globalThis.chrome = api;
  Object.defineProperty(globalThis.navigator, "locks", { configurable: true, value: { request: async (_name, _options, work) => work() } });
  return { api, source, calls, localData, sessionData };
}

test.afterEach(() => { delete globalThis.chrome; delete globalThis.Image; delete globalThis.document; delete globalThis.window; delete globalThis.location; delete globalThis.navigator.locks; });

test("manifest only declares temporary page access and OpenAI endpoint", async () => {
  const manifest = JSON.parse(await readFile(new URL("../manifest.json", import.meta.url), "utf8"));
  assert.deepEqual(manifest.permissions, ["sidePanel", "activeTab", "scripting", "storage"]);
  assert.deepEqual(manifest.host_permissions, ["https://api.openai.com/*"]);
  assert.equal(manifest.manifest_version, 3);
  assert.equal(manifest.content_scripts, undefined);
  assert.equal(manifest.action.default_popup, undefined);
});

test("capture fixes page metadata and injects only a bounded top-frame reader", async () => {
  const { calls, api } = mockBrowser();
  const evidence = await captureEvidence("page");
  assert.equal(evidence.url, "https://example.org/article?private=1");
  assert.equal(evidence.capturedAt, "2026-11-04T01:00:00.000Z");
  const request = calls.find(([kind]) => kind === "script")[1];
  assert.deepEqual(request.target, { tabId: 4, frameIds: [0] });
  assert.deepEqual(request.args, ["page", MAX_TEXT_LENGTH]);
  globalThis.document = { title: "Title", body: { innerText: "x".repeat(MAX_TEXT_LENGTH + 2) } };
  globalThis.location = { href: evidence.url };
  const bounded = request.func(...request.args);
  assert.equal(bounded.text.length, MAX_TEXT_LENGTH);
  assert.equal(bounded.truncated, true);
  assert.equal(api.tabs.onUpdated.listeners.size, 0);
});

test("unsupported pages and absent activeTab access never capture", async () => {
  const { source, calls } = mockBrowser();
  source.url = "edge://settings";
  await assert.rejects(captureEvidence("screenshot"), /仅支持普通/);
  delete source.url;
  await assert.rejects(captureEvidence("page"), /工具栏/);
  assert.equal(calls.length, 0);
});

test("tab switch away and back or navigation during capture discards the result", async () => {
  const { api, source } = mockBrowser();
  api.scripting.executeScript = async () => {
    api.tabs.onActivated.fire({ windowId: source.windowId, tabId: 9 });
    api.tabs.onActivated.fire({ windowId: source.windowId, tabId: source.id });
    return [{ result: { url: source.url, text: "不能误归属" } }];
  };
  await assert.rejects(captureEvidence("page"), /本次资料已丢弃/);
  assert.equal(api.tabs.onActivated.listeners.size, 0);
  api.scripting.executeScript = async () => [{ result: { url: "https://other.example/", text: "new" } }];
  await assert.rejects(captureEvidence("page"), /网页来源发生变化/);
});

test("empty selection reports a useful error and does not fall back to page", async () => {
  const { api, source, calls } = mockBrowser();
  api.scripting.executeScript = async () => [{ result: { url: source.url, text: "" } }];
  await assert.rejects(captureEvidence("selection"), /没有选中文字/);
  assert.equal(calls.filter(([kind]) => kind === "screenshot").length, 0);
});

test("screenshots shrink to 1600px and fail on changed source after capture", async () => {
  const { api } = mockBrowser();
  let dimensions;
  globalThis.Image = class { naturalWidth = 3200; naturalHeight = 1800; async decode() {} };
  globalThis.document = { createElement() { return { getContext() { return { drawImage: (...args) => { dimensions = args.slice(3); } }; }, toDataURL() { return "data:image/jpeg;base64,aGVsbG8="; } }; } };
  const evidence = await captureEvidence("screenshot");
  assert.deepEqual(dimensions, [1600, 900]);
  assert.equal(evidence.kind, "image");
  assert.notEqual(evidence.truncated, true);
  assert.match(evidence.capturedAt, /^2026-/);
  api.tabs.captureVisibleTab = async () => { api.tabs.onUpdated.fire(4, { status: "loading" }); return "data:image/jpeg;base64,aGVsbG8="; };
  await assert.rejects(captureEvidence("screenshot"), /本次资料已丢弃/);
});

test("API key stays in trusted session storage and settings discard unrelated fields", async () => {
  const { localData, sessionData, calls } = mockBrowser();
  await saveApiKey("test-key-no-network");
  assert.equal(await loadApiKey(), "test-key-no-network");
  assert.equal(localData.apiKey, undefined);
  assert.equal(sessionData.apiKey, "test-key-no-network");
  const settings = await loadSettings();
  await saveSettings({ ...settings, apiKey: "must-not-persist" });
  assert.equal(localData.settings.apiKey, undefined);
  assert.ok(calls.some(([kind, name, options]) => kind === "access" && name === "session" && options.accessLevel === "TRUSTED_CONTEXTS"));
  delete globalThis.chrome.storage.session;
  await assert.rejects(saveApiKey("key"), /不会将密钥写入本地磁盘/);
});

test("storage failures and corrupt history remain visible without silently overwriting", async () => {
  const { api, localData } = mockBrowser();
  localData.sessions = { broken: true };
  await assert.rejects(loadSessions(), /历史数据损坏/);
  assert.deepEqual(localData.sessions, { broken: true });
  await assert.rejects(saveSessions([{}]), Error);
  delete localData.sessions;
  await loadSessions();
  api.storage.local.set = async () => { throw new Error("QUOTA_BYTES quota exceeded"); };
  await assert.rejects(saveSessions([]), /保存失败.*空间/);
  api.storage.local.get = async () => { throw new Error("storage unavailable"); };
  await assert.rejects(loadSettings(), /已有数据未被覆盖/);
});

test("a stale history snapshot cannot overwrite another window's updates", async () => {
  const { localData } = mockBrowser();
  await loadSessions();
  localData.sessions = [{ external: "new data is preserved" }];
  await assert.rejects(saveSessions([]), /历史已在其他窗口更新/);
  assert.deepEqual(localData.sessions, [{ external: "new data is preserved" }]);
});

test("storage property reordering does not cause a false conflict on consecutive saves", async () => {
  const { api } = mockBrowser();
  const get = api.storage.local.get;
  api.storage.local.get = async (key) => JSON.parse(JSON.stringify(await get(key), (_name, value) => value && typeof value === "object" && !Array.isArray(value)
    ? Object.fromEntries(Object.keys(value).sort().reverse().map((name) => [name, value[name]])) : value));
  await loadSessions();
  const now = "2026-11-04T01:00:00.000Z";
  const session = { version: 1, id: "ordering", title: "保存顺序", createdAt: now, updatedAt: now, evidence: { kind: "text", text: "资料", title: "来源", url: "https://example.org/", capturedAt: now }, turns: [] };
  await saveSessions([session]);
  await saveSessions([{ ...session, title: "再次保存" }]);
  assert.equal((await loadSessions())[0].title, "再次保存");
});
