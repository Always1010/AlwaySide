import { DEFAULT_SETTINGS, MODEL_CATALOG, normalizeSettings, validateSettings } from './core/settings.mjs';
import { streamAnswer } from './core/client.mjs';
import { normalizeSession, upsertSession, removeSession, MAX_TURNS } from './core/history.mjs';
import { captureEvidence, loadSettings, saveSettings, loadApiKey, saveApiKey, loadSessions, saveSessions } from './browser/api.mjs';

const $ = (id) => document.getElementById(id);
const clone = (value) => structuredClone(value);
const freshSession = () => {
  const now = new Date().toISOString();
  return { version: 1, id: crypto.randomUUID(), title: '新对话', createdAt: now, updatedAt: now, evidence: null, turns: [] };
};
const emptyAnswer = () => ({ text: '', complete: false, citations: [], searchState: 'notUsed' });
let settings = normalizeSettings(DEFAULT_SETTINGS);
let settingsReady = false;
let settingsLoadError = '';
let apiKey = '';
let sessions = [];
// Retain unsaved conversations even when the user navigates to another conversation.
const drafts = new Map();
let session = freshSession();
let ready = false;
let capturing = false;
let captureFailed = false;
let captureEpoch = 0;
let requestEpoch = 0;
let activeRequest = null;
let historyWritable = true;
let pendingSaves = 0;
let saveError = '';
let storageQueue = Promise.resolve();
let noticeMessage = '';
let requestStatus = '正在读取本地设置…';

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function iconButton(symbol, label, className = '') {
  const button = el('button', `icon-button ${className}`);
  button.type = 'button';
  button.setAttribute('aria-label', label);
  button.dataset.tip = label;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `#i-${symbol}`);
  svg.append(use);
  button.append(svg);
  return button;
}

function safeUrl(value) {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url : null;
  } catch { return null; }
}

function dateText(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function showNotice(message = '') {
  noticeMessage = message;
  renderNotice();
}

function renderNotice() {
  const messages = [settingsLoadError, noticeMessage, saveError].filter(Boolean);
  $('notice').hidden = !messages.length;
  $('notice-text').textContent = messages.join(' ');
  $('retry-save').hidden = !saveError || !historyWritable;
}

function updateControls() {
  const busy = Boolean(activeRequest);
  $('send').hidden = busy;
  $('stop').hidden = !busy;
  $('send').disabled = !ready || capturing || captureFailed;
  $('open-settings').disabled = !ready;
  $('open-history').disabled = !ready;
  document.querySelectorAll('[data-capture]').forEach((button) => { button.disabled = !ready || capturing; });
  $('request-status').textContent = requestStatus;
  $('request-status').classList.toggle('busy', busy || capturing);
  $('composer-hint').textContent = captureFailed ? '取材失败 · 请重新取材或开启新对话' : 'Enter 换行 · Ctrl / ⌘ + Enter 发送';
}

function renderEvidence() {
  const slot = $('evidence-slot');
  slot.replaceChildren();
  if (capturing) { slot.append(el('div', 'capture-loading', '正在读取当前网页…')); return; }
  if (!session.evidence) return;
  const evidence = session.evidence;
  const card = el('article', 'evidence-card');
  const top = el('div', 'evidence-top');
  top.append(el('span', 'evidence-type', evidence.kind === 'image' ? '当前可见区域 · 固定资料' : '网页文字 · 固定资料'));
  const clear = iconButton('close', '移除资料并新建对话');
  clear.addEventListener('click', newConversation);
  top.append(clear);
  card.append(top, el('h2', '', evidence.title || '未命名网页'));
  const source = safeUrl(evidence.url);
  if (source) {
    const link = el('a', 'source-link', source.hostname + source.pathname);
    link.href = source.href;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.title = source.href;
    card.append(link);
  }
  const size = evidence.kind === 'text' ? ` · ${(evidence.text?.length ?? 0).toLocaleString('zh-CN')} 字符` : '';
  card.append(el('div', 'evidence-meta', `采集于 ${dateText(evidence.capturedAt)}${size}`));
  if (evidence.kind === 'image' && /^data:image\/(png|jpeg|webp);base64,/.test(evidence.dataUrl || '')) {
    const image = el('img', 'evidence-image');
    image.src = evidence.dataUrl;
    image.alt = '本次取材的网页可见区域截图';
    image.addEventListener('error', () => {
      if (session.evidence === evidence) {
        // A broken screenshot must never silently become a text-only request.
        stopRequest('截图预览失败，已停止回答。');
        captureFailed = true;
        showNotice('截图无法读取。请重新取材后再发送。');
        updateControls();
      }
    }, { once: true });
    card.append(image);
  } else if (evidence.kind === 'text') {
    const details = el('details');
    details.append(el('summary', '', '查看本次文字资料'), el('p', 'evidence-text', evidence.text || ''));
    card.append(details);
  } else {
    captureFailed = true;
    card.append(el('p', 'evidence-warning', '截图无效，请重新取材。'));
  }
  if (evidence.truncated && evidence.kind === 'text') card.append(el('p', 'evidence-warning', '资料过长，已截取部分内容；回答仅依据预览资料。'));
  slot.append(card);
}

function searchLabel(answer) {
  return { notUsed: '未使用联网搜索', completed: '已联网搜索', completedWithoutCitations: '已搜索，未提供可核对来源', failed: '联网搜索失败', requiredNotPerformed: '未完成要求的联网搜索' }[answer.searchState] || '';
}

function renderTurns() {
  const root = $('turns');
  const nearEnd = $('main').scrollHeight - $('main').scrollTop - $('main').clientHeight < 100;
  root.replaceChildren();
  $('welcome').hidden = session.turns.length > 0;
  for (const turn of session.turns) {
    const article = el('article', 'turn');
    article.append(el('div', 'question-label', `你 · ${dateText(turn.createdAt)}`), el('p', 'question-text', turn.question));
    article.append(el('div', 'answer-label', 'ALWAYSIDE'), el('div', 'answer-text', turn.answer.text));
    const running = activeRequest?.turnId === turn.id;
    const state = running ? '正在回答…' : turn.answer.complete ? '回答完成' : turn.answer.truncated ? '回答未完成 · 达到输出上限' : '回答未完成 · 已停止或中断';
    article.append(el('div', `turn-status${!turn.answer.complete && !running ? ' incomplete' : ''}`, `${state}${!running ? ` · ${searchLabel(turn.answer)}` : ''}`));
    if (turn.answer.citations?.length) {
      const citations = el('div', 'citations');
      turn.answer.citations.forEach((citation, index) => {
        const url = safeUrl(citation.url);
        if (!url) return;
        const link = el('a', '', `${index + 1}. ${citation.title || url.hostname}`);
        link.href = url.href;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.title = url.href;
        citations.append(link);
      });
      if (citations.childElementCount) article.append(citations);
    }
    root.append(article);
  }
  if (nearEnd) $('main').scrollTop = $('main').scrollHeight;
}

function renderHistory() {
  const list = $('history-list');
  list.replaceChildren();
  const visible = new Map(sessions.map((saved) => [saved.id, saved]));
  drafts.forEach((draft, id) => visible.set(id, draft));
  if (!visible.size) { list.append(el('p', 'empty-history', historyWritable ? '还没有保存的对话。' : '历史无法读取，当前内容仍可使用。')); return; }
  [...visible.values()].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).forEach((saved) => {
    const row = el('div', 'history-row');
    const restore = el('button', 'history-restore');
    restore.type = 'button';
    restore.append(el('strong', '', saved.title), el('span', '', `${dateText(saved.updatedAt)} · ${saved.turns.length} 轮对话${saved.evidence?.kind === 'image' ? ' · 截图' : ''}${drafts.has(saved.id) ? ' · 尚未保存' : ''}`));
    restore.addEventListener('click', () => restoreSession(saved.id));
    const remove = iconButton('trash', `删除对话：${saved.title}`, 'history-delete');
    remove.dataset.tip = '删除对话';
    remove.addEventListener('click', () => deleteSession(saved.id));
    row.append(restore, remove);
    list.append(row);
  });
}

function renderAll() { renderEvidence(); renderTurns(); updateControls(); renderNotice(); }

// Queue full snapshots in order so a late storage write cannot resurrect deleted history.
function persistHistory() {
  if (!historyWritable) {
    saveError = '历史尚无法读取，本次对话未保存。当前内容仍保留在侧栏；请打开本地历史，重新读取后重试保存，请勿关闭侧栏。';
    renderNotice();
    return Promise.resolve(false);
  }
  const snapshot = clone(sessions);
  pendingSaves += 1;
  const operation = storageQueue.then(() => saveSessions(snapshot));
  storageQueue = operation.catch(() => {});
  return operation.then(() => {
    pendingSaves -= 1;
    for (const saved of snapshot) {
      const draft = drafts.get(saved.id);
      if (draft && JSON.stringify(normalizeSession(draft)) === JSON.stringify(saved)) drafts.delete(saved.id);
    }
    saveError = drafts.size ? '仍有未保存的对话保留在侧栏内存中。请删除部分历史后重试保存，请勿关闭侧栏。' : '';
    renderNotice();
    return true;
  }).catch((error) => {
    pendingSaves -= 1;
    saveError = `本地历史未保存：${error.message || '存储失败'} 当前资料与回答仍保留在侧栏，请勿关闭。`;
    renderNotice();
    return false;
  });
}

function saveCurrent() {
  if (!session.turns.length) return Promise.resolve(true);
  session.updatedAt = new Date().toISOString();
  if (session.title === '新对话') session.title = session.turns[0].question.slice(0, 70);
  drafts.set(session.id, clone(session));
  try { sessions = upsertSession(sessions, clone(session)); }
  catch (error) {
    saveError = `本地历史未保存：${error.message} 当前内容仍保留在侧栏。请删除部分历史后重试保存。`;
    renderNotice();
    return Promise.resolve(false);
  }
  return persistHistory();
}

function stopRequest(message = '已停止回答，已保留收到的内容。') {
  if (!activeRequest) return;
  const previous = activeRequest;
  activeRequest = null;
  requestEpoch += 1;
  previous.controller.abort();
  requestStatus = message;
  void saveCurrent();
  renderTurns();
  updateControls();
}

function resetConversation(message) {
  captureEpoch += 1;
  stopRequest();
  session = freshSession();
  capturing = false;
  captureFailed = false;
  $('question').value = '';
  requestStatus = message;
  showNotice();
  renderAll();
}

function newConversation() {
  resetConversation('新对话已准备好');
  $('question').focus();
}

async function capture(kind) {
  // Reset before awaiting capture: failure must not leave the previous evidence available.
  resetConversation('正在读取当前网页…');
  capturing = true;
  const epoch = captureEpoch;
  renderAll();
  try {
    const evidence = await captureEvidence(kind);
    if (epoch !== captureEpoch) return;
    session.evidence = evidence;
    requestStatus = '资料已就绪 · 点击发送才会上传';
  } catch (error) {
    if (epoch !== captureEpoch) return;
    session.evidence = null;
    captureFailed = true;
    requestStatus = '取材失败 · 未使用之前的资料';
    showNotice(error.message || '读取失败。请在目标网页点击工具栏扩展图标，再试一次。');
  } finally {
    if (epoch === captureEpoch) { capturing = false; renderAll(); }
  }
}

async function sendQuestion(event) {
  event?.preventDefault();
  if (!ready || capturing || captureFailed || activeRequest) return;
  const question = $('question').value.trim() || settings.defaultQuestion;
  if (question.length > 2000) { showNotice('问题最多为 2,000 字，请缩短后发送。'); return; }
  const validation = validateSettings(settings);
  if (!settingsReady || validation || !apiKey.trim()) {
    openSettings();
    $('settings-error').textContent = !settingsReady ? '原设置读取失败。请明确检查并保存设置后再发送，不会自动使用默认模型。' : validation || '请先填写本次浏览器会话使用的 API Key。';
    $('settings-error').hidden = false;
    return;
  }
  if (session.turns.length >= MAX_TURNS) { showNotice(`本对话已达到 ${MAX_TURNS} 轮上限，请开启新对话。`); return; }
  showNotice();
  const snapshot = { settings: clone(settings), apiKey, evidence: clone(session.evidence), question, turns: clone(session.turns) };
  const turn = { id: crypto.randomUUID(), question, answer: emptyAnswer(), createdAt: new Date().toISOString() };
  const controller = new AbortController();
  const epoch = ++requestEpoch;
  activeRequest = { controller, epoch, turnId: turn.id };
  session.turns.push(turn);
  $('question').value = '';
  requestStatus = '正在连接 OpenAI…';
  renderTurns();
  $('main').scrollTop = $('main').scrollHeight;
  updateControls();
  try {
    const answer = await streamAnswer({ ...snapshot, signal: controller.signal, onEvent: (event) => {
      if (epoch !== requestEpoch || activeRequest?.epoch !== epoch) return;
      if (event.type === 'text') { turn.answer.text = event.text; renderTurns(); }
      else if (event.type === 'status') { requestStatus = event.status; updateControls(); }
    } });
    if (epoch !== requestEpoch || activeRequest?.epoch !== epoch) return;
    turn.answer = answer;
    requestStatus = answer.complete ? '回答完成 · 可以继续追问' : '回答未完成 · 可调整设置后重新提问';
    if (!answer.complete) showNotice(answer.truncated ? '回答达到输出上限，已保留部分内容。本轮不会作为完整问答加入后续上下文。' : '回答未完整结束，已保留收到的内容。本轮不会作为完整问答加入后续上下文。');
  } catch (error) {
    if (epoch !== requestEpoch || activeRequest?.epoch !== epoch) return;
    if (error.partialAnswer) turn.answer = { ...error.partialAnswer, complete: false };
    requestStatus = error.name === 'AbortError' ? '回答已停止' : '回答中断 · 已保留当前内容';
    showNotice(error.message || '请求失败，请检查网络与设置后重新提问。');
  } finally {
    if (epoch === requestEpoch && activeRequest?.epoch === epoch) {
      activeRequest = null;
      renderTurns();
      updateControls();
      await saveCurrent();
    }
  }
}

function restoreSession(id) {
  stopRequest();
  const saved = drafts.get(id) || sessions.find((entry) => entry.id === id);
  if (!saved) return;
  resetConversation('已恢复本地对话 · 资料保持原采集时间');
  session = clone(saved);
  $('history-dialog').close();
  renderAll();
}

async function deleteSession(id) {
  // This confirmation is local UI for irreversible user data deletion.
  if (!confirm('删除这段本地对话？删除后不可恢复。')) return;
  if (session.id === id) resetConversation('对话已删除，已开启新对话');
  drafts.delete(id);
  sessions = removeSession(sessions, id);
  renderHistory();
  const saved = await persistHistory();
  if (!saved) {
    $('history-dialog').close();
    showNotice('删除尚未保存到本机。请重试保存后再关闭侧栏。');
  }
}

function openSettings() {
  $('api-key').value = apiKey;
  $('model').value = settings.model;
  renderReasoning(settings.reasoning);
  $('search-mode').value = settings.searchMode;
  $('max-tokens').value = settings.maxOutputTokens;
  $('default-question').value = settings.defaultQuestion;
  $('instructions').value = settings.instructions;
  $('settings-error').textContent = settingsLoadError;
  $('settings-error').hidden = !settingsLoadError;
  if (!$('settings-dialog').open) $('settings-dialog').showModal();
}

function renderReasoning(selected = $('reasoning').value) {
  const supported = MODEL_CATALOG.find((model) => model.id === $('model').value)?.supportedReasoning || [];
  const labels = { none: '无', low: '低', medium: '中', high: '高', xhigh: '更高', max: '最高' };
  $('reasoning').replaceChildren();
  supported.forEach((value) => {
    const option = el('option', '', labels[value] || value);
    option.value = value;
    $('reasoning').append(option);
  });
  if (supported.includes(selected)) $('reasoning').value = selected;
  else {
    const placeholder = el('option', '', '请选择推理程度');
    placeholder.value = '';
    placeholder.disabled = true;
    placeholder.selected = true;
    $('reasoning').prepend(placeholder);
  }
}

async function reloadHistory() {
  $('reload-history').disabled = true;
  $('history-status').textContent = '正在重新读取…';
  try {
    await storageQueue;
    sessions = (await loadSessions()).map(normalizeSession);
    historyWritable = true;
    $('history-status').textContent = '已读取最新历史。当前对话和未保存内容仍保留；如有未保存内容，请重试保存。';
    renderHistory();
  } catch (error) {
    $('history-status').textContent = `读取失败：${error.message} 当前对话未改变。`;
  } finally { $('reload-history').disabled = false; renderNotice(); }
}

async function retrySave() {
  if (!historyWritable) return;
  if (session.turns.length) {
    session.updatedAt = new Date().toISOString();
    if (session.title === '新对话') session.title = session.turns[0].question.slice(0, 70);
    drafts.set(session.id, clone(session));
  }
  try {
    let next = sessions;
    for (const draft of drafts.values()) next = upsertSession(next, draft);
    sessions = next;
    await persistHistory();
  } catch (error) {
    saveError = `本地历史未保存：${error.message} 未保存内容仍可在历史列表中恢复；请勿关闭侧栏。`;
    renderNotice();
  }
  renderHistory();
}

async function submitSettings(event) {
  event.preventDefault();
  const proposed = normalizeSettings({ model: $('model').value.trim(), reasoning: $('reasoning').value, searchMode: $('search-mode').value, maxOutputTokens: Number($('max-tokens').value), defaultQuestion: $('default-question').value.trim(), instructions: $('instructions').value.trim() });
  const error = validateSettings(proposed);
  if (error) { $('settings-error').textContent = error; $('settings-error').hidden = false; return; }
  $('save-settings').disabled = true;
  try {
    await saveSettings(proposed);
    settings = proposed;
    settingsReady = true;
    settingsLoadError = '';
    const nextKey = $('api-key').value.trim();
    await saveApiKey(nextKey);
    apiKey = nextKey;
    if (!activeRequest && !capturing) requestStatus = apiKey ? '准备好了 · 取材或直接提问' : '请在设置中填写会话密钥';
    $('settings-dialog').close();
    showNotice('设置已保存；正在进行的回答仍使用发送时的设置。');
    updateControls();
  } catch (error) {
    $('settings-error').textContent = `保存未完成：${error.message} 请检查后重试。`;
    $('settings-error').hidden = false;
  } finally { $('save-settings').disabled = false; }
}

async function initialize() {
  const loaded = await Promise.allSettled([loadSettings(), loadApiKey(), loadSessions()]);
  const failures = [];
  if (loaded[0].status === 'fulfilled') {
    try {
      settings = normalizeSettings(loaded[0].value);
      const validation = validateSettings(settings);
      if (validation) throw new Error(validation);
      settingsReady = true;
    } catch (error) { settingsLoadError = `设置读取失败：${error.message} 请明确检查并保存设置后再发送。`; }
  } else settingsLoadError = `设置读取失败：${loaded[0].reason.message} 请明确检查并保存设置后再发送。`;
  if (loaded[1].status === 'fulfilled') apiKey = loaded[1].value || '';
  else failures.push(`会话密钥读取失败：${loaded[1].reason.message}`);
  if (loaded[2].status === 'fulfilled') {
    try { sessions = loaded[2].value.map(normalizeSession); }
    catch (error) { historyWritable = false; failures.push(`历史读取失败：${error.message} 为保护已有记录，本次不覆盖历史。`); }
  } else { historyWritable = false; failures.push(`历史读取失败：${loaded[2].reason.message} 为保护已有记录，本次不覆盖历史。`); }
  ready = true;
  requestStatus = !settingsReady ? '请检查并保存设置后再发送' : apiKey ? '准备好了 · 取材或直接提问' : '准备好了 · 请在设置中填写会话密钥';
  showNotice(failures.join(' '));
  renderAll();
}

$('new-chat').addEventListener('click', newConversation);
$('open-settings').addEventListener('click', openSettings);
$('open-history').addEventListener('click', () => { renderHistory(); $('history-dialog').showModal(); });
$('question-form').addEventListener('submit', sendQuestion);
$('settings-form').addEventListener('submit', submitSettings);
$('stop').addEventListener('click', () => stopRequest());
$('retry-save').addEventListener('click', () => { void retrySave(); });
$('reload-history').addEventListener('click', () => { void reloadHistory(); });
$('model').addEventListener('change', () => renderReasoning());
MODEL_CATALOG.forEach((model) => {
  const option = el('option', '', model.name);
  option.value = model.id;
  $('model').append(option);
});
document.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', () => $(button.dataset.close).close()));
document.querySelectorAll('[data-capture]').forEach((button) => button.addEventListener('click', () => { void capture(button.dataset.capture); }));
document.querySelectorAll('[data-prompt]').forEach((button) => button.addEventListener('click', () => { $('question').value = button.dataset.prompt; $('question').focus(); }));
$('question').addEventListener('keydown', (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && !event.isComposing) { event.preventDefault(); void sendQuestion(); }
});
// Keep drafts in the current panel if local persistence has not completed.
window.addEventListener('beforeunload', (event) => {
  if (activeRequest || pendingSaves || saveError || drafts.size || (!historyWritable && session.turns.length)) { event.preventDefault(); event.returnValue = ''; }
});
updateControls();
void initialize();
