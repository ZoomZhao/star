import { readFileSync, writeFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { randomBytes } from 'node:crypto';
let env = readFileSync('.env', 'utf8');
let config = parseEnv(env);
const origin = 'https://' + (config.DOMAIN || config.ECS_HOST);
let token;
async function call(path, method = 'GET', body) {
  const r = await fetch(origin + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await r.json();
  if (!r.ok) throw new Error(data.error);
  return data;
}
const auth = await call('/api/login', 'POST', {
  username: config.ADMIN_USERNAME || 'admin',
  password: config.ADMIN_PASSWORD,
});
token = auth.token;
const accounts = await call('/api/admin/accounts');
if (accounts.users.some((u) => u.role !== 'admin')) {
  console.log('已有家庭账号，保留现有设置。');
  process.exit(0);
}
for (const key of ['PARENT_PASSWORD', 'CHILD_PASSWORD'])
  if (!config[key]) {
    const value = randomBytes(12).toString('base64url');
    env += '\n' + key + '=' + value;
  }
writeFileSync('.env', env + '\n', { mode: 0o600 });
config = parseEnv(env);
const family =
  accounts.families[0] || (await call('/api/admin/families', 'POST', { name: '我的家庭' }));
await call('/api/admin/users', 'POST', {
  family_id: family.id,
  username: 'parent',
  name: '家长',
  role: 'parent',
  password: config.PARENT_PASSWORD,
});
const child = await call('/api/admin/users', 'POST', {
  family_id: family.id,
  username: 'child',
  name: '小朋友',
  role: 'child',
  password: config.CHILD_PASSWORD,
});
for (const [title, description, icon, stars, daily_limit] of [
  ['读英文绘本', '和家长一起读一段英文绘本，大声读出喜欢的句子。', 'book', 1, 2],
  ['自己刷牙', '早晚认真刷牙，每次坚持 2 分钟。', 'brush', 1, 2],
  ['收好小玩具', '让每个玩具回到自己的家。', 'blocks', 2, 1],
  ['户外动一动', '和家长一起开心运动至少 20 分钟。', 'leaf', 2, 1],
])
  await call(`/api/children/${child.id}/rules`, 'POST', {
    title,
    description,
    icon,
    stars,
    daily_limit,
    schedule: 'daily',
  });
for (const [title, description, icon, cost] of [
  ['甜甜冰淇淋', '和家长一起挑选喜欢的口味。', 'icecream', 5],
  ['新玩具伙伴', '一起挑选一个心仪的小玩具。', 'toy', 30],
])
  await call(`/api/children/${child.id}/rewards`, 'POST', { title, description, icon, cost });
const d = await call(`/api/children/${child.id}/dashboard`);
console.log(
  JSON.stringify({
    family: family.name,
    users: ['admin', 'parent', 'child'],
    tasks: d.tasks.length,
    balance: d.wallet.balance,
    totalPotential: d.tasks.reduce((s, t) => s + t.stars * t.daily_limit, 0),
    rewards: d.rewards.length,
  }),
);
const backup = await call('/api/admin/backup');
writeFileSync('backups/ecs-initial.json', JSON.stringify(backup, null, 2), { mode: 0o600 });
console.log('Verified initial backup saved locally.');
await call('/api/logout', 'POST');
