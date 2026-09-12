import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { parseEnv } from 'node:util';
const config = parseEnv(readFileSync('.env', 'utf8'));
const origin = 'https://' + config.DOMAIN;
const label = process.argv[2] || 'before-deploy';
if (!/^[a-z0-9-]+$/i.test(label)) throw new Error('备份标签只能包含字母、数字和连字符');
const login = await fetch(origin + '/api/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: config.ADMIN_USERNAME || 'admin',
    password: config.ADMIN_PASSWORD,
  }),
});
if (!login.ok) throw new Error('管理员登录失败');
const { token } = await login.json();
try {
  const response = await fetch(origin + '/api/admin/backup', {
    headers: { Authorization: 'Bearer ' + token },
  });
  if (!response.ok) throw new Error('备份下载失败');
  const backup = await response.json();
  if (backup.format !== 'star-explorer') throw new Error('备份格式异常');
  mkdirSync('artifacts', { recursive: true });
  const path = `artifacts/${label}-${Date.now()}.json`;
  writeFileSync(path, JSON.stringify(backup), { mode: 0o600, flag: 'wx' });
  console.log('Production backup saved: ' + path + '; accounts: ' + backup.data.users.length);
} finally {
  await fetch(origin + '/api/logout', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
  });
}
