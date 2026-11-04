// Capabilities checked against the official model pages on 2026-11-04.
// Documented capability does not imply this account has access to a model.
const ALL_EFFORTS = Object.freeze(['none', 'low', 'medium', 'high', 'xhigh', 'max']);
export const MODEL_CATALOG = Object.freeze([
  ['gpt-6-luna', 'GPT-6 Luna', ALL_EFFORTS],
  ['gpt-6.1-terra', 'GPT-6.1 Terra', ALL_EFFORTS],
  ['gpt-6.1-sol', 'GPT-6.1 Sol', ALL_EFFORTS.slice(1)],
  ['gpt-6-astra', 'GPT-6 Astra', ALL_EFFORTS.slice(1)],
].map(([id, name, supportedReasoning]) => Object.freeze({ id, name, supportedReasoning: Object.freeze(supportedReasoning) })));

// Keep the shipped iPad defaults; browser-specific safety rules are added at send time.
export const DEFAULT_SETTINGS = Object.freeze({
  model: 'gpt-6-luna', reasoning: 'low', searchMode: 'auto',
  instructions: '你是问答助手，帮助用户翻译或解释屏幕上的内容。',
  defaultQuestion: '请优先将内容翻译成中文；有必要时再简要解释。',
  maxOutputTokens: 32768,
});

export function normalizeSettings(raw) {
  const value = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  return Object.fromEntries(Object.entries(DEFAULT_SETTINGS).map(([key, fallback]) =>
    [key, Object.hasOwn(value, key) ? value[key] : fallback]));
}

export function validateSettings(settings) {
  if (!settings || typeof settings !== 'object') return '设置格式无效。';
  const model = MODEL_CATALOG.find(item => item.id === settings.model);
  if (!model) return '此型号尚未核对图片、推理和搜索能力，请选择目录中的型号。';
  if (!model.supportedReasoning.includes(settings.reasoning)) return `${model.name} 不支持所选推理档位，请明确选择受支持的档位。`;
  if (!['off', 'auto', 'required'].includes(settings.searchMode)) return '请选择有效的联网搜索模式。';
  if (!Number.isInteger(settings.maxOutputTokens) || settings.maxOutputTokens < 1024 || settings.maxOutputTokens > 128000) return '输出及推理总预算应为 1,024–128,000 tokens。';
  if (typeof settings.instructions !== 'string' || settings.instructions.length > 20000) return '应用指令应为文字，且不超过 20,000 字。';
  if (typeof settings.defaultQuestion !== 'string' || !settings.defaultQuestion.trim() || settings.defaultQuestion.length > 2000) return '默认问题应为 1–2,000 字。';
  return null;
}
