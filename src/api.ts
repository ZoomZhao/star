// Keep existing Web installations on their configured server. Native clients now
// use their own networking; this legacy override is read-only in the Web UI.
const baseUrl = localStorage.getItem('star-server') || import.meta.env.VITE_API_URL || '';
export const serverUrl = () => baseUrl;

type Session = { token: string; userId: string };
const savedToken = localStorage.getItem('star-token') || sessionStorage.getItem('star-token') || '';
if (savedToken) localStorage.setItem('star-token', savedToken);
sessionStorage.removeItem('star-token');
let session: Session = { token: savedToken, userId: '' };
let sessionVersion = 0;
try {
  const identity = JSON.parse(localStorage.getItem('star-session-identity') || 'null');
  if (
    identity?.token === savedToken &&
    identity?.server === baseUrl &&
    typeof identity.userId === 'string'
  )
    session.userId = identity.userId;
} catch {
  // The server will establish identity again through /api/me.
}
export const getSessionVersion = () => sessionVersion;

function pendingPrefix(userId: string) {
  return 'star-pending:v2:' + JSON.stringify([baseUrl, userId]) + ':';
}
function rememberIdentity(current: Session, userId: string) {
  current.userId = userId;
  if (current === session)
    localStorage.setItem(
      'star-session-identity',
      JSON.stringify({ token: current.token, server: baseUrl, userId }),
    );
  // Only migrate a token whose account was established by login or /api/me.
  // Unattributed expired legacy tokens are retained, never assigned to another user.
  const legacyPrefix = 'star-pending:' + baseUrl + ':' + current.token.slice(0, 16) + ':';
  for (const legacyKey of Object.keys(localStorage)) {
    if (!legacyKey.startsWith(legacyPrefix)) continue;
    const value = localStorage.getItem(legacyKey);
    if (!value) continue;
    const key = pendingPrefix(userId) + legacyKey.slice(legacyPrefix.length);
    const existing = localStorage.getItem(key);
    if (!existing) localStorage.setItem(key, value);
    if (!existing || existing === value) localStorage.removeItem(legacyKey);
  }
}
if (session.token && session.userId) rememberIdentity(session, session.userId);

// Other tabs must stop using an old account when login/logout changes storage.
window.addEventListener('storage', (event) => {
  if (
    (event.key === 'star-token' || event.key === null) &&
    (localStorage.getItem('star-token') || '') !== session.token
  )
    window.location.reload();
});
export function saveToken(token: string, userId = '') {
  session = { token, userId };
  sessionVersion++;
  if (token) localStorage.setItem('star-token', token);
  else localStorage.removeItem('star-token');
  localStorage.removeItem('star-session-identity');
  if (token && userId) rememberIdentity(session, userId);
}

async function send<T>(
  current: Session,
  path: string,
  method: string,
  body?: unknown,
  requestKey?: string,
): Promise<T> {
  if (requestKey && current.token && !current.userId) {
    await send(current, '/api/me', 'GET');
    if (current !== session) throw new Error('登录状态已变化，请重新操作');
  }
  const operationKey =
    requestKey && current.userId
      ? pendingPrefix(current.userId) + method + ':' + path + ':' + JSON.stringify(body)
      : null;
  const stableKey = operationKey ? localStorage.getItem(operationKey) || requestKey : requestKey;
  if (operationKey && stableKey) localStorage.setItem(operationKey, stableKey);
  const response = await fetch(baseUrl + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(current.token ? { Authorization: 'Bearer ' + current.token } : {}),
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
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login' && current === session) {
      saveToken('');
      window.dispatchEvent(new Event('star-session-expired'));
    }
    // An authentication failure on a retry cannot rule out an earlier commit.
    // Keep the operation identity until a successful response confirms its result.
    throw new Error(result.error || '操作未完成');
  }
  if (path === '/api/me' && current === session && typeof result.id === 'string')
    rememberIdentity(current, result.id);
  if (operationKey && localStorage.getItem(operationKey) === stableKey)
    localStorage.removeItem(operationKey);
  return result;
}

export function api<T = Record<string, unknown>>(
  path: string,
  method = 'GET',
  body?: unknown,
  requestKey?: string,
): Promise<T> {
  return send<T>(session, path, method, body, requestKey);
}

export function endSession(): Promise<void> {
  const previous = session;
  if (!previous.token) return Promise.resolve();
  saveToken('');
  // Revoke the captured token, without allowing a late response to clear a new login.
  return send(previous, '/api/logout', 'POST').then(
    () => {},
    () => {},
  );
}

export const requestId = () => crypto.randomUUID();
