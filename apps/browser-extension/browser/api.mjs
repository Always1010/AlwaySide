import { normalizeSettings, validateSettings } from "../core/settings.mjs";
import { MAX_SESSIONS, normalizeSession } from "../core/history.mjs";

export const MAX_TEXT_LENGTH = 30000;
export const MAX_IMAGE_EDGE = 1600;
const MAX_IMAGE_URL_LENGTH = 2_000_000;
const STORAGE_KEYS = { settings: "settings", sessions: "sessions", apiKey: "apiKey" };
let sessionsSnapshot;
const PERMISSION_HELP = "请回到目标网页，点击浏览器工具栏的 AlwaySide 按钮授权，再重新取材。切换标签或跨站导航后需要重新授权。";

function extensionApi() {
  if (!globalThis.chrome?.storage) throw new Error("请在 Edge / Chrome 的 AlwaySide 侧栏中使用此功能。");
  return globalThis.chrome;
}

function validSource(tab) {
  if (!Number.isInteger(tab?.id) || !Number.isInteger(tab?.windowId)) throw new Error("未找到当前网页，请先打开一个普通网页。");
  if (!tab.url) throw new Error(`尚未获得当前网页的访问权限。${PERMISSION_HELP}`);
  let url;
  try { url = new URL(tab.url); } catch { throw new Error("当前网页地址无效，无法取材。"); }
  if (!["http:", "https:"].includes(url.protocol)) throw new Error("仅支持普通 HTTP / HTTPS 网页；浏览器内部页、本地文件、扩展页和内置 PDF 阅读器无法取材。");
  if (url.username || url.password) throw new Error("包含账户密码的网页地址无法作为资料来源，请先移除地址中的凭证。");
  if (tab.url.length > 8192) throw new Error("网页地址过长，无法保存来源；请使用较短的页面地址。");
  if (tab.pendingUrl || tab.status === "loading") throw new Error("网页正在跳转或加载，请等待加载完成后重新取材。");
  return tab;
}

// Runs in the isolated world of the top frame only; never receives the API key.
function readPage(kind, limit) {
  const raw = kind === "selection" ? String(window.getSelection() ?? "") : (document.body?.innerText ?? "");
  return {
    url: location.href,
    title: document.title.slice(0, 1000),
    text: raw.slice(0, limit).trim(),
    truncated: raw.length > limit,
    capturedAt: new Date().toISOString(),
  };
}

function sourceGuard(api, source) {
  let changed = false;
  const activated = (info) => { if (info.windowId === source.windowId && info.tabId !== source.id) changed = true; };
  const updated = (id, info) => { if (id === source.id && (info.url || info.status === "loading")) changed = true; };
  const removed = (id) => { if (id === source.id) changed = true; };
  api.tabs.onActivated.addListener(activated);
  api.tabs.onUpdated.addListener(updated);
  api.tabs.onRemoved.addListener(removed);
  return {
    async check() {
      const [active] = await api.tabs.query({ active: true, windowId: source.windowId });
      const current = await api.tabs.get(source.id);
      if (changed || active?.id !== source.id || current.url !== source.url || current.pendingUrl || current.status === "loading") {
        throw new Error("取材期间页面或活动标签发生变化，本次资料已丢弃。请回到目标页面重新取材。");
      }
    },
    dispose() {
      api.tabs.onActivated.removeListener(activated);
      api.tabs.onUpdated.removeListener(updated);
      api.tabs.onRemoved.removeListener(removed);
    },
  };
}

async function resizeScreenshot(dataUrl) {
  if (typeof dataUrl !== "string" || !/^data:image\/(png|jpeg);base64,/.test(dataUrl) || dataUrl.length > 32 * 1024 * 1024) {
    throw new Error("截图无效或原始图像过大，请缩小浏览器窗口后重试。");
  }
  const image = new Image();
  image.src = dataUrl;
  await image.decode();
  if (!image.naturalWidth || !image.naturalHeight) throw new Error("截图尺寸无效，请重新取材。");
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(image.naturalWidth, image.naturalHeight));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
  const context = canvas.getContext("2d");
  if (!context) throw new Error("无法处理截图，请重新取材。");
  context.drawImage(image, 0, 0, canvas.width, canvas.height);
  let encoded = canvas.toDataURL("image/jpeg", 0.82);
  if (encoded.length > MAX_IMAGE_URL_LENGTH) encoded = canvas.toDataURL("image/jpeg", 0.6);
  if (encoded.length > MAX_IMAGE_URL_LENGTH) throw new Error("截图内容过大，请缩小浏览器窗口或改用文字取材。");
  // Scaling preserves the complete viewport; it is not content truncation.
  return { dataUrl: encoded };
}

export async function captureEvidence(kind) {
  if (!["selection", "page", "screenshot"].includes(kind)) throw new Error("不支持的取材方式。");
  const api = extensionApi();
  let guard;
  try {
    const window = await api.windows.getCurrent();
    const [source] = await api.tabs.query({ active: true, windowId: window.id });
    validSource(source);
    guard = sourceGuard(api, source);
    // A top-frame script verifies the source even for screenshots and rejects
    // restricted pages before captureVisibleTab can capture sensitive browser UI.
    const results = await api.scripting.executeScript({
      target: { tabId: source.id, frameIds: [0] },
      func: readPage,
      args: [kind === "screenshot" ? "selection" : kind, kind === "screenshot" ? 0 : MAX_TEXT_LENGTH],
    });
    const page = results?.[0]?.result;
    if (!page || page.url !== source.url) throw new Error("取材期间网页来源发生变化，请重新取材。");
    await guard.check();
    const base = { title: page.title || source.title?.slice(0, 1000) || "未命名网页", url: source.url, capturedAt: page.capturedAt };
    if (kind === "screenshot") {
      base.capturedAt = new Date().toISOString();
      const raw = await api.tabs.captureVisibleTab(source.windowId, { format: "jpeg", quality: 90 });
      await guard.check();
      const image = await resizeScreenshot(raw);
      await guard.check();
      return { ...base, kind: "image", ...image };
    }
    if (!page.text) throw new Error(kind === "selection" ? "没有选中文字。请在网页正文中选中文字后重新取材；暂不读取输入框或跨域框架内的选区。" : "网页没有可读取的正文。动态页面请等待内容显示后重试，或改用可见区域截图。");
    return { ...base, kind: "text", text: page.text, truncated: page.truncated };
  } catch (error) {
    if (/cannot access|missing host permission|permission|extensions gallery|cannot be scripted|not allowed|not been invoked/i.test(error?.message ?? "")) {
      throw new Error(`浏览器未允许访问此页面，或此页面受到保护。${PERMISSION_HELP}`);
    }
    // Do not echo browser errors: they may include a page's private URL.
    if (error instanceof Error && /[\u3400-\u9fff]/u.test(error.message)) throw error;
    throw new Error(`取材失败，页面可能已关闭、受到保护或暂不可用。${PERMISSION_HELP}`);
  } finally {
    guard?.dispose();
  }
}

async function storageArea(name) {
  const area = extensionApi().storage[name];
  if (!area) throw new Error(name === "session" ? "此浏览器不支持会话密钥存储，请升级浏览器；不会将密钥写入本地磁盘。" : "浏览器本地存储不可用。");
  // Explicitly keep even local history away from future content scripts.
  try { await area.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" }); }
  catch { throw new Error("无法限制扩展存储的访问范围，请重新加载扩展或更新浏览器。"); }
  return area;
}

async function readStorage(areaName, key) {
  const area = await storageArea(areaName);
  try { return (await area.get(key))[key]; }
  catch { throw new Error("读取浏览器存储失败；已有数据未被覆盖，请重新打开侧栏后重试。"); }
}

async function writeStorage(areaName, key, value) {
  const area = await storageArea(areaName);
  try { await area.set({ [key]: value }); }
  catch { throw new Error("保存失败：浏览器存储空间可能不足或暂不可用。当前内容仍保留在侧栏，请减少历史后重试。"); }
}

function checkedSettings(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) throw new Error("设置数据损坏，请重新填写并保存设置。");
  const error = validateSettings(raw);
  if (error) throw new Error(`设置无效：${error}`);
  // Only the known settings fields are ever written, so a key cannot leak from
  // a caller-provided object into storage.local.
  const normalized = normalizeSettings(raw);
  return Object.fromEntries(["model", "reasoning", "searchMode", "instructions", "defaultQuestion", "maxOutputTokens"].map((key) => [key, normalized[key]]));
}

export async function loadSettings() {
  const raw = await readStorage("local", STORAGE_KEYS.settings);
  return raw === undefined ? normalizeSettings({}) : checkedSettings(raw);
}

export async function saveSettings(settings) {
  await writeStorage("local", STORAGE_KEYS.settings, checkedSettings(settings));
}

function checkedKey(raw) {
  if (typeof raw !== "string" || raw.length > 1024 || /[^\x21-\x7e]/.test(raw)) throw new Error("API Key 应为不含空格的 ASCII 字符串，且不超过 1024 字符。");
  return raw;
}

export async function loadApiKey() {
  const raw = await readStorage("session", STORAGE_KEYS.apiKey);
  return raw === undefined ? "" : checkedKey(raw);
}

export async function saveApiKey(key) {
  await writeStorage("session", STORAGE_KEYS.apiKey, checkedKey(key));
}

function checkedSessions(raw) {
  if (!Array.isArray(raw)) throw new Error("历史数据损坏，未自动清空或覆盖已有历史。");
  if (raw.length > MAX_SESSIONS) throw new Error(`最多保存 ${MAX_SESSIONS} 个对话，请先删除不需要的历史。`);
  const normalized = raw.map(normalizeSession);
  if (new Set(normalized.map((session) => session.id)).size !== normalized.length) throw new Error("历史包含重复的对话标识，未自动覆盖已有历史。");
  return normalized;
}

export async function loadSessions() {
  return withHistoryLock(async () => {
    const raw = await readStorage("local", STORAGE_KEYS.sessions);
    const sessions = raw === undefined ? [] : checkedSessions(raw);
    sessionsSnapshot = JSON.stringify(raw ?? []);
    return sessions;
  });
}

export async function saveSessions(sessions) {
  const normalized = checkedSessions(sessions);
  await withHistoryLock(async () => {
    const raw = await readStorage("local", STORAGE_KEYS.sessions);
    const current = JSON.stringify(raw ?? []);
    if ((sessionsSnapshot === undefined && raw !== undefined) || (sessionsSnapshot !== undefined && sessionsSnapshot !== current)) {
      throw new Error("历史已在其他窗口更新，本次未覆盖。请重新加载历史后重试，当前对话仍保留在侧栏。");
    }
    await writeStorage("local", STORAGE_KEYS.sessions, normalized);
    sessionsSnapshot = JSON.stringify(normalized);
  });
}

async function withHistoryLock(work) {
  if (!globalThis.navigator?.locks?.request) throw new Error("此浏览器不支持安全保存多窗口历史，请更新浏览器后重试。");
  return globalThis.navigator.locks.request("alwayside-history", { mode: "exclusive" }, work);
}
