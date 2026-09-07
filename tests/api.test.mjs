import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../server/app.mjs';
import { addDays, today, uid, get } from '../server/db.mjs';
async function setup(t) {
  const dir = mkdtempSync(join(tmpdir(), 'star-test-'));
  const { app, db } = createApp({
    databasePath: ':memory:',
    backupDir: dir,
    adminPassword: 'local-admin-2026',
    demo: true,
    origins: ['http://localhost:5173'],
  });
  const server = await new Promise((r) => {
    const s = app.listen(0, '127.0.0.1', () => r(s));
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  t.after(() => {
    server.close();
    db.close();
    rmSync(dir, { recursive: true, force: true });
  });
  async function call(path, method = 'GET', body, token, key) {
    const r = await fetch(base + path, {
      method,
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...(key ? { 'Idempotency-Key': key } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: r.status, data: await r.json() };
  }
  const login = async (name, password) => {
    const r = await call('/api/login', 'POST', { username: name, password });
    assert.equal(r.status, 200);
    return r.data;
  };
  const parent = await login('parent', 'parent123'),
    child = await login('child', 'child123'),
    admin = await login('admin', 'local-admin-2026');
  const dashboard = async (date = today()) =>
    (await call(`/api/children/${child.user.id}/dashboard?date=${date}`, 'GET', null, parent.token))
      .data;
  return { db, dir, call, parent, child, admin, dashboard, login };
}
test('pending counts toward cap; duplicate retries and parallel approvals never double-award', async (t) => {
  const s = await setup(t),
    d = await s.dashboard(),
    task = d.tasks.find((t) => t.icon === 'book');
  const key = uid();
  const r = await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, key);
  assert.equal(r.status, 200);
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance);
  const retry = await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, key);
  assert.equal(retry.data.id, r.data.id);
  const second = await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, uid());
  assert.equal(second.status, 200);
  assert.equal(
    (await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, uid())).status,
    409,
  );
  const approvals = await Promise.all(
    [1, 2].map(() =>
      s.call(`/api/submissions/${r.data.id}/review`, 'POST', { approve: true }, s.parent.token),
    ),
  );
  assert.deepEqual(approvals.map((x) => x.status).sort(), [200, 409]);
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + task.stars);
  await s.call(
    `/api/submissions/${second.data.id}/review`,
    'POST',
    { approve: false, note: '再读一次' },
    s.parent.token,
  );
  assert.equal(
    (await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, uid())).status,
    200,
  );
});
test('roles and family boundaries protect admin, review, and child data', async (t) => {
  const s = await setup(t);
  assert.equal((await s.call('/api/admin/backup', 'GET', null, s.child.token)).status, 403);
  assert.equal((await s.call('/api/admin/accounts', 'GET', null, s.parent.token)).status, 403);
  const family = (await s.call('/api/admin/families', 'POST', { name: '另一家' }, s.admin.token))
    .data;
  const foreign = (
    await s.call(
      '/api/admin/users',
      'POST',
      {
        family_id: family.id,
        username: 'otherchild',
        name: '别家孩子',
        role: 'child',
        password: 'password123',
      },
      s.admin.token,
    )
  ).data;
  assert.equal(
    (await s.call(`/api/children/${foreign.id}/dashboard`, 'GET', null, s.child.token)).status,
    403,
  );
  assert.equal(
    (await s.call(`/api/children/${foreign.id}/dashboard`, 'GET', null, s.parent.token)).status,
    403,
  );
  assert.equal(
    (
      await s.call(
        `/api/children/${s.child.user.id}/ledger`,
        'POST',
        { amount: 10, kind: 'bonus', title: '自行加分' },
        s.child.token,
        uid(),
      )
    ).status,
    403,
  );
});
test('template versions preserve old snapshots, daily overrides freeze after submission', async (t) => {
  const s = await setup(t),
    d = await s.dashboard(),
    old = d.tasks.find((t) => t.icon === 'book'),
    rule = d.rules.find((r) => r.icon === 'book');
  const change = { ...rule, title: '新绘本规则', stars: 5, daily_limit: 3, enabled: true };
  assert.equal(
    (await s.call(`/api/rules/${rule.rule_key}`, 'PATCH', change, s.parent.token)).status,
    200,
  );
  assert.equal((await s.dashboard()).tasks.find((t) => t.id === old.id).stars, 1);
  const tomorrow = await s.dashboard(addDays(today(), 1));
  assert.equal(tomorrow.tasks.find((t) => t.rule_key === rule.rule_key).stars, 5);
  assert.equal(
    (await s.call(`/api/tasks/${old.id}`, 'PATCH', { ...old, stars: 2 }, s.parent.token)).status,
    200,
  );
  await s.call(`/api/tasks/${old.id}/submit`, 'POST', {}, s.child.token, uid());
  assert.equal(
    (await s.call(`/api/tasks/${old.id}`, 'PATCH', { ...old, stars: 3 }, s.parent.token)).status,
    409,
  );
  assert.equal((await s.dashboard()).tasks.find((t) => t.id === old.id).stars, 2);
});
test('redemption cost snapshot, transactional nonnegative balances, reversals', async (t) => {
  const s = await setup(t),
    d = await s.dashboard(),
    reward = d.rewards[0];
  const request = await s.call(
    `/api/children/${s.child.user.id}/redemptions`,
    'POST',
    { reward_id: reward.id },
    s.child.token,
    uid(),
  );
  assert.equal(request.status, 200);
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance);
  await s.call(
    `/api/rewards/${reward.id}`,
    'PATCH',
    { ...reward, cost: 20, active: true },
    s.parent.token,
  );
  assert.equal(
    (
      await s.call(
        `/api/redemptions/${request.data.id}/review`,
        'POST',
        { approve: true },
        s.parent.token,
      )
    ).status,
    200,
  );
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance - reward.cost);
  const overspend = await s.call(
    `/api/children/${s.child.user.id}/ledger`,
    'POST',
    { amount: 100000, kind: 'spend', title: '买玩具' },
    s.parent.token,
    uid(),
  );
  assert.equal(overspend.status, 409);
  const e = (await s.dashboard()).ledger.find((x) => x.redemption_id === request.data.id);
  assert.equal(
    (await s.call(`/api/ledger/${e.id}/reverse`, 'POST', { note: '取消兑换' }, s.parent.token))
      .status,
    200,
  );
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance);
  assert.equal(
    (await s.call(`/api/ledger/${e.id}/reverse`, 'POST', { note: '再次撤销' }, s.parent.token))
      .status,
    409,
  );
});
test('backup round trip restores hashes, rules and balances, saves pre-restore backup and revokes sessions', async (t) => {
  const s = await setup(t),
    d = await s.dashboard();
  const b = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  await s.call(
    `/api/children/${s.child.user.id}/ledger`,
    'POST',
    { amount: 7, kind: 'bonus', title: '奖励' },
    s.parent.token,
    uid(),
  );
  const bad = structuredClone(b);
  bad.data.ledger[0].child_id = uid();
  assert.notEqual(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup: bad, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + 7);
  const r = await s.call(
    '/api/admin/restore',
    'POST',
    { backup: b, password: 'local-admin-2026' },
    s.admin.token,
  );
  assert.equal(r.status, 200);
  assert.equal(readdirSync(s.dir).length, 1);
  assert.equal((await s.call('/api/me', 'GET', null, s.parent.token)).status, 401);
  const p = await s.login('parent', 'parent123');
  assert.equal(
    (await s.call(`/api/children/${s.child.user.id}/dashboard`, 'GET', null, p.token)).data.wallet
      .balance,
    d.wallet.balance,
  );
  assert.equal(get(s.db, 'SELECT COUNT(*) n FROM rules').n, b.data.rules.length);
});
test('calendar schedules and parent completion of past tasks; child future submissions rejected', async (t) => {
  const s = await setup(t);
  const future = addDays(today(), 2);
  const r = await s.call(
    `/api/children/${s.child.user.id}/rules`,
    'POST',
    { title: '指定日', stars: 3, daily_limit: 2, schedule: 'once', on_date: future },
    s.parent.token,
  );
  assert.equal(r.status, 201);
  assert.equal(
    (await s.dashboard()).tasks.some((t) => t.title === '指定日'),
    false,
  );
  const f = (await s.dashboard(future)).tasks.find((t) => t.title === '指定日');
  assert.ok(f);
  assert.equal(
    (await s.call(`/api/tasks/${f.id}/submit`, 'POST', {}, s.child.token, uid())).status,
    400,
  );
  const past = (await s.dashboard(addDays(today(), -1))).tasks[0];
  const prior = (await s.dashboard()).wallet.balance;
  assert.equal(
    (await s.call(`/api/tasks/${past.id}/submit`, 'POST', {}, s.child.token, uid())).status,
    400,
  );
  assert.equal(
    (await s.call(`/api/tasks/${past.id}/submit`, 'POST', {}, s.parent.token, uid())).status,
    200,
  );
  assert.equal((await s.dashboard()).wallet.balance, prior + past.stars);
});
test('invalid dates, weak passwords, and unsupported backups are rejected without mutation', async (t) => {
  const s = await setup(t);
  assert.equal(
    (
      await s.call(
        `/api/children/${s.child.user.id}/dashboard?date=2026-02-30`,
        'GET',
        null,
        s.child.token,
      )
    ).status,
    400,
  );
  const b = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  b.version = 99;
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup: b, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    400,
  );
  assert.equal(readdirSync(s.dir).length, 0);
  assert.equal(
    (
      await s.call(
        '/api/password',
        'POST',
        { current: 'parent123', password: '123' },
        s.parent.token,
      )
    ).status,
    400,
  );
});
