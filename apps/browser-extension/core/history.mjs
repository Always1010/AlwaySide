export const MAX_SESSIONS = 30;
export const MAX_TURNS = 60;
export const MAX_ANSWER_LENGTH = 12000;
export const MAX_IMAGE_BYTES = 3 * 1024 * 1024;
const SEARCH_STATES = ['notUsed', 'completed', 'completedWithoutCitations', 'failed', 'requiredNotPerformed'];

function invalid(detail) { throw new Error(`本地会话数据无效：${detail}。`); }
function record(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid(label);
}
function string(value, limit, label, empty = false) {
  if (typeof value !== 'string' || (!empty && !value.trim()) || value.length > limit) invalid(label);
  return value;
}
function timestamp(value, label) {
  string(value, 100, label);
  if (!Number.isFinite(Date.parse(value))) invalid(label);
  return value;
}
export function safeHttpUrl(value) {
  if (typeof value !== 'string' || value.length > 8192) return null;
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && url.hostname && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

export function normalizeEvidence(raw) {
  if (raw === null) return null;
  record(raw, '资料格式');
  if (!['text', 'image'].includes(raw.kind)) invalid('资料类型');
  const title = string(raw.title, 1000, '资料标题', true);
  if (!safeHttpUrl(raw.url)) invalid('资料来源网址');
  const evidence = { kind: raw.kind, title, url: raw.url, capturedAt: timestamp(raw.capturedAt, '资料采集时间') };
  if (raw.truncated !== undefined && typeof raw.truncated !== 'boolean') invalid('资料截断标记');
  if (raw.truncated !== undefined) evidence.truncated = raw.truncated;
  if (raw.kind === 'text') evidence.text = string(raw.text, 30000, '资料文字');
  else {
    if (typeof raw.dataUrl !== 'string' || raw.dataUrl.length > 4194400) invalid('截图大小');
    const match = /^data:image\/(?:png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/.exec(raw.dataUrl);
    if (!match || match[1].length % 4 !== 0) invalid('截图编码');
    const bytes = match[1].length / 4 * 3 - (match[1].endsWith('==') ? 2 : match[1].endsWith('=') ? 1 : 0);
    if (bytes < 1 || bytes > MAX_IMAGE_BYTES) invalid('截图大小');
    evidence.dataUrl = raw.dataUrl;
  }
  return evidence;
}

function normalizeAnswer(raw) {
  record(raw, '回答格式');
  if (typeof raw.complete !== 'boolean' || !SEARCH_STATES.includes(raw.searchState) || !Array.isArray(raw.citations) || raw.citations.length > 100) invalid('回答状态');
  const answer = { text: string(raw.text, MAX_ANSWER_LENGTH, '回答文字', true), complete: raw.complete,
    searchState: raw.searchState, citations: raw.citations.map(citation => {
      record(citation, '引用格式');
      if (!safeHttpUrl(citation.url)) invalid('引用网址');
      return { url: citation.url, title: string(citation.title, 300, '引用标题', true) };
    }) };
  if (raw.truncated !== undefined) {
    if (typeof raw.truncated !== 'boolean') invalid('回答截断标记');
    answer.truncated = raw.truncated;
  }
  if (answer.complete && (!answer.text.trim() || answer.truncated || ['failed', 'requiredNotPerformed'].includes(answer.searchState))) invalid('完整回答状态冲突');
  return answer;
}

export function normalizeSession(raw) {
  record(raw, '会话格式');
  if (raw.version !== 1) invalid('不支持的会话版本');
  if (!Array.isArray(raw.turns)) invalid('问答列表');
  if (raw.turns.length > MAX_TURNS) throw new Error(`每段会话最多保存 ${MAX_TURNS} 轮，请新建对话；原历史未删除。`);
  const ids = new Set();
  return {
    version: 1, id: string(raw.id, 200, '会话标识'), title: string(raw.title, 300, '会话标题'),
    createdAt: timestamp(raw.createdAt, '会话创建时间'), updatedAt: timestamp(raw.updatedAt, '会话更新时间'),
    evidence: normalizeEvidence(raw.evidence),
    turns: raw.turns.map(turn => {
      record(turn, '问答格式');
      const id = string(turn.id, 200, '问答标识');
      if (ids.has(id)) invalid('重复问答标识');
      ids.add(id);
      return { id, question: string(turn.question, 2000, '问题'), answer: normalizeAnswer(turn.answer), createdAt: timestamp(turn.createdAt, '问答时间') };
    }),
  };
}

function normalizeSessions(sessions) {
  if (!Array.isArray(sessions)) invalid('会话列表');
  if (sessions.length > MAX_SESSIONS) throw new Error(`最多保存 ${MAX_SESSIONS} 段会话，请先明确删除不需要的历史。`);
  const result = sessions.map(normalizeSession);
  if (new Set(result.map(session => session.id)).size !== result.length) invalid('重复会话标识');
  return result;
}

export function upsertSession(sessions, session) {
  const result = normalizeSessions(sessions);
  const next = normalizeSession(session);
  const index = result.findIndex(item => item.id === next.id);
  if (index < 0) {
    if (result.length >= MAX_SESSIONS) throw new Error(`最多保存 ${MAX_SESSIONS} 段会话，请先明确删除不需要的历史；本次内容仍保留在侧栏。`);
    result.unshift(next);
  } else result[index] = next;
  return result.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
}

export function removeSession(sessions, id) {
  string(id, 200, '会话标识');
  return normalizeSessions(sessions).filter(session => session.id !== id);
}
