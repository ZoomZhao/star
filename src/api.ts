import { Capacitor } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
let token = sessionStorage.getItem('star-token') || '';
let customUrl = localStorage.getItem('star-server') || '';
export const isNative = Capacitor.isNativePlatform();
export const serverUrl = () => customUrl || import.meta.env.VITE_API_URL || '';
export function setServerUrl(value: string) {
  const s = value.trim().replace(/\/$/, '');
  if (s && !/^https:\/\//.test(s) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(s))
    throw new Error('请输入 HTTPS 服务地址');
  customUrl = s;
  localStorage.setItem('star-server', s);
}
export async function initToken() {
  if (isNative) {
    const r = await Preferences.get({ key: 'star-token' });
    token = r.value || '';
  }
}
export async function saveToken(t: string) {
  token = t;
  if (t) sessionStorage.setItem('star-token', t);
  else sessionStorage.removeItem('star-token');
  if (isNative) await Preferences.set({ key: 'star-token', value: t });
}
export async function api<T = Record<string, unknown>>(
  path: string,
  method = 'GET',
  body?: unknown,
  requestKey?: string,
): Promise<T> {
  const operationKey = requestKey
    ? 'star-pending:' + token.slice(0, 16) + ':' + method + ':' + path + ':' + JSON.stringify(body)
    : null;
  const stableKey = operationKey ? sessionStorage.getItem(operationKey) || requestKey : undefined;
  if (operationKey && stableKey) sessionStorage.setItem(operationKey, stableKey);
  const response = await fetch(serverUrl() + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(stableKey ? { 'Idempotency-Key': stableKey } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20000),
  }).catch(() => {
    throw new Error('暂时连接不上服务器，请检查网络后重试');
  });
  const result = await response.json();
  if (operationKey) sessionStorage.removeItem(operationKey);
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login') {
      await saveToken('');
      window.dispatchEvent(new Event('star-session-expired'));
    }
    throw new Error(result.error || '操作未完成');
  }
  return result;
}
export const requestId = () => crypto.randomUUID();
