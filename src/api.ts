import { Capacitor } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
let token = localStorage.getItem('star-token') || sessionStorage.getItem('star-token') || '';
if (token) localStorage.setItem('star-token', token);
sessionStorage.removeItem('star-token');
let customUrl = localStorage.getItem('star-server') || '';
export const isNative = Capacitor.isNativePlatform();
export const serverUrl = () => customUrl || import.meta.env.VITE_API_URL || '';
// Other tabs must stop using an old account as soon as login/logout changes storage.
window.addEventListener('storage', (event) => {
  if (
    (event.key === 'star-token' || event.key === null) &&
    (localStorage.getItem('star-token') || '') !== token
  )
    window.location.reload();
});
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
  if (t) localStorage.setItem('star-token', t);
  else localStorage.removeItem('star-token');
  if (isNative) await Preferences.set({ key: 'star-token', value: t });
}
export async function api<T = Record<string, unknown>>(
  path: string,
  method = 'GET',
  body?: unknown,
  requestKey?: string,
): Promise<T> {
  const session = token;
  const operationKey = requestKey
    ? 'star-pending:' +
      serverUrl() +
      ':' +
      session.slice(0, 16) +
      ':' +
      method +
      ':' +
      path +
      ':' +
      JSON.stringify(body)
    : null;
  const stableKey = operationKey ? localStorage.getItem(operationKey) || requestKey : undefined;
  if (operationKey && stableKey) localStorage.setItem(operationKey, stableKey);
  const response = await fetch(serverUrl() + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(session ? { Authorization: 'Bearer ' + session } : {}),
      ...(stableKey ? { 'Idempotency-Key': stableKey } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20000),
  }).catch(() => {
    throw new Error('暂时连接不上服务器，请检查网络后重试');
  });
  const result = await response.json().catch(() => {
    throw new Error('服务器响应异常，请稍后重试');
  });
  // A gateway/server error can occur after the operation committed. Retain its
  // identity across retries and browser restarts until the result is definitive.
  if (operationKey && response.status < 500) localStorage.removeItem(operationKey);
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login' && token === session) {
      await saveToken('');
      window.dispatchEvent(new Event('star-session-expired'));
    }
    throw new Error(result.error || '操作未完成');
  }
  return result;
}
export const requestId = () => crypto.randomUUID();
