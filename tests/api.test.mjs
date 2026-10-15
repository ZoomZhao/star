import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../server/app.mjs';
import { addDays, today, uid, get, seedTemplates } from '../server/db.mjs';
import { cardDayLedgerId } from '../server/card-identity.mjs';
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
test('physical card identities remain compatible with existing ledger records', () => {
  assert.equal(
    cardDayLedgerId('123e4567-e89b-42d3-a456-426614174000', '2026-10-15', '01020304'),
    '1919a842-08ab-52f2-ab68-01fc245830c4',
  );
});

test('creation retries preserve one rule, reward and family across sessions and backup restore', async (t) => {
  const s = await setup(t);
  const examples = [
    {
      path: `/api/children/${s.child.user.id}/rules`,
      token: s.parent.token,
      body: { title: '可安全重试的规则', stars: 3, daily_limit: 10, schedule: 'daily' },
    },
    {
      path: `/api/children/${s.child.user.id}/rewards`,
      token: s.parent.token,
      body: { title: '可安全重试的奖励', cost: 7 },
    },
    { path: '/api/admin/families', token: s.admin.token, body: { name: '可安全重试的家庭' } },
  ];
  for (const example of examples) {
    example.key = uid();
    const created = await s.call(example.path, 'POST', example.body, example.token, example.key);
    assert.equal(created.status, 201);
    example.row = created.data;
    const retry = await s.call(example.path, 'POST', example.body, example.token, example.key);
    assert.deepEqual(retry.data, created.data);
    const changed = {
      ...example.body,
      ...(example.body.name ? { name: '另一家庭' } : { title: '另一内容' }),
    };
    assert.equal(
      (await s.call(example.path, 'POST', changed, example.token, example.key)).status,
      409,
    );
  }
  const before = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        {
          backup: before,
          password: 'local-admin-2026',
        },
        s.admin.token,
      )
    ).status,
    200,
  );
  const parent = await s.login('parent', 'parent123');
  const admin = await s.login('admin', 'local-admin-2026');
  for (const example of examples) {
    const token = example.path === '/api/admin/families' ? admin.token : parent.token;
    const retry = await s.call(example.path, 'POST', example.body, token, example.key);
    assert.deepEqual(retry.data, example.row);
  }
  assert.deepEqual(
    (await s.call('/api/admin/backup', 'GET', null, admin.token)).data.data,
    before.data,
  );
  // Even an identical UUID from a different account has its own identity.
  const reward = examples[1];
  const separate = await s.call(reward.path, 'POST', reward.body, admin.token, reward.key);
  assert.equal(separate.status, 201);
  assert.notEqual(separate.data.id, reward.row.id);
});

test('reward status changes preserve details edited by another parent', async (t) => {
  const s = await setup(t);
  const reward = (await s.dashboard()).rewards[0];
  const path = `/api/rewards/${reward.id}`;
  const edited = {
    ...reward,
    title: '新心愿名称',
    description: '新的家庭约定',
    icon: 'picnic',
    cost: 23,
    active: true,
  };
  assert.equal((await s.call(path, 'PATCH', edited, s.parent.token)).status, 200);
  for (const active of [false, true]) {
    assert.equal((await s.call(path, 'PATCH', { active }, s.parent.token)).status, 200);
    const current = (await s.dashboard()).rewards.find((r) => r.id === reward.id);
    assert.deepEqual(current, { ...edited, active: Number(active) });
  }
  assert.equal((await s.call(path, 'PATCH', { cost: 0 }, s.parent.token)).status, 400);
});
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

test('five subjects have editable presets and three homework tasks; category snapshots survive edits and backup', async (t) => {
  const s = await setup(t);
  const presets = await s.call('/api/task-templates', 'GET', null, s.parent.token);
  assert.equal(presets.data.length, 13);
  for (const subject of ['chinese', 'math', 'english', 'sports', 'other']) {
    const count = ['sports', 'other'].includes(subject) ? 2 : 3;
    assert.equal(presets.data.filter((p) => p.subject === subject).length, count);
    assert.equal((await s.dashboard()).tasks.filter((p) => p.subject === subject).length, count);
  }
  const d = await s.dashboard();
  const task = d.tasks.find((t) => t.subject === 'english');
  assert.equal(
    (await s.call(`/api/tasks/${task.id}`, 'PATCH', { ...task, subject: 'math' }, s.parent.token))
      .status,
    200,
  );
  assert.equal((await s.dashboard()).tasks.find((t) => t.id === task.id).subject, 'math');
  const rule = d.rules.find((r) => r.subject === 'english');
  assert.equal(
    (
      await s.call(
        `/api/rules/${rule.rule_key}`,
        'PATCH',
        { ...rule, subject: 'chinese', enabled: true },
        s.parent.token,
      )
    ).status,
    200,
  );
  assert.equal(
    (await s.dashboard()).tasks.find((t) => t.rule_key === rule.rule_key).subject,
    task.rule_key === rule.rule_key ? 'math' : 'english',
  );
  assert.equal(
    (await s.dashboard(addDays(today(), 1))).tasks.find((t) => t.rule_key === rule.rule_key)
      .subject,
    'chinese',
  );
  assert.equal(
    (
      await s.call(
        `/api/tasks/${task.id}`,
        'PATCH',
        { ...task, subject: 'invalid' },
        s.parent.token,
      )
    ).status,
    400,
  );
  const backup = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.ok(backup.data.tasks.every((t) => typeof t.subject === 'string'));
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
  const p = await s.login('parent', 'parent123');
  const restored = (
    await s.call(`/api/children/${s.child.user.id}/dashboard`, 'GET', null, p.token)
  ).data;
  assert.equal(restored.tasks.find((t) => t.id === task.id).subject, 'math');
});

test('remembered sessions last 180 days, renew on use, logout and password reset revoke', async (t) => {
  const s = await setup(t);
  const expiry = get(
    s.db,
    'SELECT expires_at FROM sessions WHERE user_id=?',
    s.child.user.id,
  ).expires_at;
  assert.ok(Date.parse(expiry) - Date.now() > 179 * 86400000);
  s.db
    .prepare('UPDATE sessions SET expires_at=? WHERE user_id=?')
    .run(new Date(Date.now() + 86400000).toISOString(), s.child.user.id);
  assert.equal((await s.call('/api/me', 'GET', null, s.child.token)).status, 200);
  assert.ok(
    Date.parse(
      get(s.db, 'SELECT expires_at FROM sessions WHERE user_id=?', s.child.user.id).expires_at,
    ) -
      Date.now() >
      179 * 86400000,
  );
  await s.call('/api/logout', 'POST', {}, s.child.token);
  assert.equal((await s.call('/api/me', 'GET', null, s.child.token)).status, 401);
  const c = await s.login('child', 'child123');
  await s.call(
    '/api/password',
    'POST',
    { current: 'child123', password: 'new-child-password' },
    c.token,
  );
  assert.equal((await s.call('/api/me', 'GET', null, c.token)).status, 401);
});

test('parents manage active and inactive family rewards; children only see and redeem active items', async (t) => {
  const s = await setup(t),
    path = `/api/children/${s.child.user.id}`;
  const created = await s.call(
    path + '/rewards',
    'POST',
    { title: '管理测试奖励', description: '初始说明', icon: 'gift', cost: 3 },
    s.parent.token,
  );
  assert.equal(created.status, 201);
  const reward = created.data;
  const pending = await s.call(
    path + '/redemptions',
    'POST',
    { reward_id: reward.id },
    s.child.token,
    uid(),
  );
  assert.equal(pending.status, 200);
  const edit = {
    title: '修改后的奖励',
    description: '新说明',
    icon: 'toy',
    cost: 8,
    active: false,
  };
  assert.equal(
    (await s.call(`/api/rewards/${reward.id}`, 'PATCH', edit, s.child.token)).status,
    403,
  );
  assert.equal((await s.call(path + '/rewards', 'POST', edit, s.child.token)).status, 403);
  const otherFamily = (
    await s.call('/api/admin/families', 'POST', { name: '其他家庭' }, s.admin.token)
  ).data;
  await s.call(
    '/api/admin/users',
    'POST',
    {
      family_id: otherFamily.id,
      username: 'rewardparent',
      name: '另一个家长',
      role: 'parent',
      password: 'reward-parent-pass',
    },
    s.admin.token,
  );
  const foreign = await s.login('rewardparent', 'reward-parent-pass');
  assert.equal(
    (await s.call(`/api/rewards/${reward.id}`, 'PATCH', edit, foreign.token)).status,
    403,
  );
  assert.equal((await s.call(path + '/dashboard', 'GET', null, foreign.token)).status, 403);
  assert.equal(
    (await s.call(`/api/rewards/${reward.id}`, 'PATCH', edit, s.parent.token)).status,
    200,
  );
  const parentData = await s.dashboard();
  const inactive = parentData.rewards.find((r) => r.id === reward.id);
  assert.equal(inactive.active, 0);
  assert.equal(inactive.cost, 8);
  const childData = (await s.call(path + '/dashboard', 'GET', null, s.child.token)).data;
  assert.ok(!childData.rewards.some((r) => r.id === reward.id));
  assert.equal(
    (await s.call(path + '/redemptions', 'POST', { reward_id: reward.id }, s.child.token, uid()))
      .status,
    404,
  );
  assert.equal(parentData.redemptions.find((r) => r.id === pending.data.id).title, '管理测试奖励');
  assert.equal(parentData.redemptions.find((r) => r.id === pending.data.id).cost, 3);
  assert.equal(
    (await s.call(`/api/rewards/${reward.id}`, 'PATCH', { ...edit, active: true }, s.parent.token))
      .status,
    200,
  );
  const visible = (await s.call(path + '/dashboard', 'GET', null, s.child.token)).data.rewards.find(
    (r) => r.id === reward.id,
  );
  assert.equal(visible.title, '修改后的奖励');
  assert.equal(visible.cost, 8);
});

test('template edits preserve every existing snapshot and custom cap; only ungenerated dates use new versions', async (t) => {
  const s = await setup(t),
    todayData = await s.dashboard();
  const rule = todayData.rules[0],
    tomorrow = addDays(today(), 1),
    later = addDays(today(), 2);
  const customized = {
    ...rule,
    title: '自定义十次任务',
    description: '保留全部快照字段',
    subject: 'math',
    icon: 'pencil',
    stars: 3,
    daily_limit: 10,
    enabled: true,
  };
  assert.equal(
    (await s.call(`/api/rules/${rule.rule_key}`, 'PATCH', customized, s.parent.token)).status,
    200,
  );
  const preview = (await s.dashboard(tomorrow)).tasks.find((x) => x.rule_key === rule.rule_key);
  const override = (await s.dashboard(later)).tasks.find((x) => x.rule_key === rule.rule_key);
  assert.equal(
    (await s.call(`/api/tasks/${override.id}`, 'PATCH', { ...override, stars: 9 }, s.parent.token))
      .status,
    200,
  );
  const baseline = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  const assertExistingRowsPreserved = async () => {
    const after = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
    for (const [table, rows] of Object.entries(baseline.data)) {
      for (const row of rows) {
        const current = after.data[table].find((candidate) =>
          table === 'task_order'
            ? candidate.child_id === row.child_id && candidate.rule_key === row.rule_key
            : candidate.id === row.id,
        );
        assert.deepEqual(current, row, `${table}: existing record must remain intact`);
      }
    }
  };
  const change = { ...customized, title: '新版任务', stars: 4, daily_limit: 2 };
  assert.equal(
    (await s.call(`/api/rules/${rule.rule_key}`, 'PATCH', change, s.parent.token)).status,
    200,
  );
  const fresh = (await s.dashboard(tomorrow)).tasks.find((x) => x.rule_key === rule.rule_key);
  assert.deepEqual(fresh, preview);
  assert.equal(fresh.daily_limit, 10);
  assert.equal(
    (await s.dashboard()).tasks.find((x) => x.rule_key === rule.rule_key).stars,
    rule.stars,
  );
  assert.equal((await s.dashboard(later)).tasks.find((x) => x.id === override.id).stars, 9);
  const unseen = (await s.dashboard(addDays(today(), 3))).tasks.find(
    (x) => x.rule_key === rule.rule_key,
  );
  assert.equal(unseen.stars, 4);
  assert.equal(unseen.daily_limit, 2);
  assert.equal(unseen.title, '新版任务');
  await assertExistingRowsPreserved();
  assert.equal(
    (
      await s.call(
        `/api/rules/${rule.rule_key}`,
        'PATCH',
        { ...change, enabled: false },
        s.parent.token,
      )
    ).status,
    200,
  );
  assert.deepEqual(
    (await s.dashboard(tomorrow)).tasks.find((x) => x.id === preview.id),
    preview,
  );
  assert.ok(
    !(await s.dashboard(addDays(today(), 4))).tasks.some((x) => x.rule_key === rule.rule_key),
  );
  assert.equal(
    (
      await s.call(
        `/api/rules/${rule.rule_key}`,
        'PATCH',
        { ...change, schedule: 'once', on_date: addDays(today(), 5) },
        s.parent.token,
      )
    ).status,
    200,
  );
  assert.deepEqual(
    (await s.dashboard(tomorrow)).tasks.find((x) => x.id === preview.id),
    preview,
  );
  assert.equal((await s.dashboard(later)).tasks.find((x) => x.id === override.id).stars, 9);
  assert.ok(
    !(await s.dashboard(addDays(today(), 4))).tasks.some((x) => x.rule_key === rule.rule_key),
  );
  assert.ok(
    (await s.dashboard(addDays(today(), 5))).tasks.some((x) => x.rule_key === rule.rule_key),
  );
  const weeklyDate = addDays(today(), 7);
  assert.equal(
    (
      await s.call(
        `/api/rules/${rule.rule_key}`,
        'PATCH',
        {
          ...change,
          schedule: 'weekly',
          weekdays: [new Date(weeklyDate + 'T12:00:00Z').getUTCDay()],
        },
        s.parent.token,
      )
    ).status,
    200,
  );
  assert.ok(
    !(await s.dashboard(addDays(today(), 6))).tasks.some((x) => x.rule_key === rule.rule_key),
  );
  assert.ok((await s.dashboard(weeklyDate)).tasks.some((x) => x.rule_key === rule.rule_key));
  await assertExistingRowsPreserved();
});

test('idempotency rejects changed payloads and replays successful redemptions after delisting', async (t) => {
  const s = await setup(t),
    d = await s.dashboard(),
    path = `/api/children/${s.child.user.id}`;
  const key = uid(),
    body = { amount: 2, kind: 'bonus', title: '稳定重试' };
  const first = await s.call(path + '/ledger', 'POST', body, s.parent.token, key);
  assert.equal(
    (await s.call(path + '/ledger', 'POST', body, s.parent.token, key)).data.id,
    first.data.id,
  );
  assert.equal(
    (await s.call(path + '/ledger', 'POST', { ...body, amount: 3 }, s.parent.token, key)).status,
    409,
  );
  const taskKey = uid(),
    firstTask = d.tasks[0];
  const submit = await s.call(
    `/api/tasks/${firstTask.id}/submit`,
    'POST',
    {},
    s.child.token,
    taskKey,
  );
  assert.equal(submit.status, 200);
  assert.equal(
    (await s.call(`/api/tasks/${d.tasks[1].id}/submit`, 'POST', {}, s.child.token, taskKey)).status,
    409,
  );
  // Simulate retrying a confirmed request after the calendar has rolled over.
  s.db.prepare('UPDATE tasks SET date=? WHERE id=?').run(addDays(today(), -1), firstTask.id);
  assert.equal(
    (await s.call(`/api/tasks/${firstTask.id}/submit`, 'POST', {}, s.child.token, taskKey)).data.id,
    submit.data.id,
  );
  const reward = d.rewards[0],
    rewardKey = uid(),
    redemptionBody = { reward_id: reward.id };
  const redemption = await s.call(
    path + '/redemptions',
    'POST',
    redemptionBody,
    s.child.token,
    rewardKey,
  );
  await s.call(`/api/rewards/${reward.id}`, 'PATCH', { ...reward, active: false }, s.parent.token);
  assert.equal(
    (await s.call(path + '/redemptions', 'POST', redemptionBody, s.child.token, rewardKey)).data.id,
    redemption.data.id,
  );
  assert.equal(
    (
      await s.call(
        path + '/redemptions',
        'POST',
        { reward_id: d.rewards[1].id },
        s.child.token,
        rewardKey,
      )
    ).status,
    409,
  );
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + 2);
});

test('restore rejects invalid ownership, approvals and ledger links without touching live data or sessions', async (t) => {
  const s = await setup(t),
    d = await s.dashboard(),
    path = `/api/children/${s.child.user.id}`;
  await s.call(`/api/tasks/${d.tasks[0].id}/submit`, 'POST', {}, s.parent.token, uid());
  const redemption = await s.call(
    path + '/redemptions',
    'POST',
    { reward_id: d.rewards[0].id },
    s.child.token,
    uid(),
  );
  await s.call(
    `/api/redemptions/${redemption.data.id}/review`,
    'POST',
    { approve: true },
    s.parent.token,
  );
  const spend = (await s.dashboard()).ledger.find((x) => x.redemption_id === redemption.data.id);
  await s.call(`/api/ledger/${spend.id}/reverse`, 'POST', { note: '退回兑换' }, s.parent.token);
  const baseline = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  const mutations = [
    (b) => {
      b.data.rules[0].child_id = s.parent.user.id;
    },
    (b) => {
      b.data.ledger[0].actor_id = s.child.user.id;
    },
    (b) => {
      b.data.ledger.find((x) => x.kind === 'task').amount += 1;
    },
    (b) => {
      b.data.ledger = b.data.ledger.filter((x) => x.kind !== 'task');
    },
    (b) => {
      b.data.submissions[0].status = 'pending';
    },
    (b) => {
      b.data.rules[0].schedule = 'once';
      b.data.rules[0].on_date = null;
    },
    (b) => {
      b.data.rules[0].schedule = 'weekly';
      b.data.rules[0].weekdays = '[]';
    },
    (b) => {
      const family = { ...b.data.families[0], id: uid() };
      b.data.families.push(family);
      b.data.rewards[0].family_id = family.id;
    },
  ];
  for (const mutate of mutations) {
    const bad = structuredClone(baseline);
    mutate(bad);
    const result = await s.call(
      '/api/admin/restore',
      'POST',
      { backup: bad, password: 'local-admin-2026' },
      s.admin.token,
    );
    assert.equal(result.status, 400, JSON.stringify(result.data));
    assert.equal((await s.call('/api/me', 'GET', null, s.parent.token)).status, 200);
    const after = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
    assert.deepEqual(after.data, baseline.data);
    assert.equal(readdirSync(s.dir).length, 0);
  }
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup: baseline, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
});

test('homework seeds once per subject and all tasks support optional parent bonus', async (t) => {
  const s = await setup(t);
  seedTemplates(s.db, s.child.user.id);
  seedTemplates(s.db, s.child.user.id);
  const d = await s.dashboard();
  const homework = d.tasks.filter((t) => t.title === '课内作业');
  assert.deepEqual(homework.map((t) => t.subject).sort(), ['chinese', 'english', 'math']);
  assert.ok(homework.every((t) => t.stars === 2 && t.daily_limit === 1));
  assert.equal(d.rules.filter((t) => t.title === '课内作业').length, 3);
  const task = d.tasks.find((t) => t.subject === 'sports');
  assert.equal(
    (await s.call(`/api/tasks/${task.id}/submit`, 'POST', { bonus: true }, s.child.token, uid()))
      .status,
    400,
  );
  const submission = await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, uid());
  assert.equal(
    (
      await s.call(
        `/api/submissions/${submission.data.id}/review`,
        'POST',
        { approve: false, bonus: true },
        s.parent.token,
      )
    ).status,
    400,
  );
  const results = await Promise.all(
    [1, 2].map(() =>
      s.call(
        `/api/submissions/${submission.data.id}/review`,
        'POST',
        { approve: true, bonus: true },
        s.parent.token,
      ),
    ),
  );
  assert.deepEqual(results.map((r) => r.status).sort(), [200, 409]);
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + task.stars + 1);
  const key = uid();
  for (let i = 0; i < 2; i++)
    assert.equal(
      (
        await s.call(
          `/api/tasks/${homework[0].id}/submit`,
          'POST',
          { bonus: true },
          s.parent.token,
          key,
        )
      ).status,
      200,
    );
  assert.equal(
    (await s.call(`/api/tasks/${homework[0].id}/submit`, 'POST', {}, s.parent.token, key)).status,
    409,
  );
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + task.stars + 4);
  const backup = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
});

test('adding homework preserves customized rule versions and daily task snapshots', async (t) => {
  const s = await setup(t);
  const d = await s.dashboard();
  const task = d.tasks.find((t) => t.title === '读英文绘本');
  const rule = d.rules.find((r) => r.rule_key === task.rule_key);
  assert.equal(
    (await s.call(`/api/tasks/${task.id}`, 'PATCH', { ...task, daily_limit: 7 }, s.parent.token))
      .status,
    200,
  );
  assert.equal(
    (
      await s.call(
        `/api/rules/${rule.rule_key}`,
        'PATCH',
        { ...rule, daily_limit: 10, enabled: true },
        s.parent.token,
      )
    ).status,
    200,
  );
  const before = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  const presets = (await s.call('/api/task-templates', 'GET', null, s.parent.token)).data;
  seedTemplates(
    s.db,
    s.child.user.id,
    presets.filter((t) => t.title === '课内作业'),
  );
  const after = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.deepEqual(after.data.rules, before.data.rules);
  assert.deepEqual(after.data.tasks, before.data.tasks);
  assert.equal((await s.dashboard()).tasks.find((t) => t.id === task.id).daily_limit, 7);
  assert.equal(
    (await s.dashboard(addDays(today(), 1))).tasks.find((t) => t.rule_key === rule.rule_key)
      .daily_limit,
    10,
  );
});

test('parents may award one fewer star per completion with atomic accounting and backup restore', async (t) => {
  const s = await setup(t),
    d = await s.dashboard();
  const task = d.tasks.find((t) => t.stars === 2);
  const one = d.tasks.find((t) => t.stars === 1);
  const submit = (task, body, token = s.parent.token, key = uid()) =>
    s.call(`/api/tasks/${task.id}/submit`, 'POST', body, token, key);
  assert.equal((await submit(task, { deduction: true }, s.child.token)).status, 400);
  assert.equal((await submit(one, { deduction: true })).status, 400);
  assert.equal((await submit(task, { deduction: true, bonus: true })).status, 400);
  const pending = await submit(task, {}, s.child.token);
  const review = (body) =>
    s.call(`/api/submissions/${pending.data.id}/review`, 'POST', body, s.parent.token);
  assert.equal((await review({ approve: false, deduction: true })).status, 400);
  assert.equal((await review({ approve: true, deduction: true, bonus: true })).status, 400);
  const results = await Promise.all([
    review({ approve: true, deduction: true }),
    review({ approve: true, deduction: true }),
  ]);
  assert.deepEqual(results.map((r) => r.status).sort(), [200, 409]);
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + 1);
  const another = d.tasks.find((t) => t.stars === 2 && t.id !== task.id);
  const key = uid();
  for (let i = 0; i < 2; i++)
    assert.equal((await submit(another, { deduction: true }, s.parent.token, key)).status, 200);
  assert.equal((await submit(another, {}, s.parent.token, key)).status, 409);
  const after = await s.dashboard();
  assert.equal(after.wallet.balance, d.wallet.balance + 2);
  assert.equal(after.tasks.find((t) => t.id === task.id).stars, 2);
  const entry = after.ledger.find((l) => l.kind === 'task' && l.amount === 1);
  assert.equal(
    (
      await s.call(
        `/api/ledger/${entry.id}/reverse`,
        'POST',
        { note: '撤销本次实发星星' },
        s.parent.token,
        uid(),
      )
    ).status,
    200,
  );
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + 1);
  const backup = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
});

test('custom award quantities respect remaining capacity, replay safely and restore with task order', async (t) => {
  const s = await setup(t),
    d = await s.dashboard();
  const rule = d.rules.find((r) => r.subject === 'chinese');
  const task = d.tasks.find((t) => t.rule_key === rule.rule_key);
  assert.equal(
    (await s.call(`/api/tasks/${task.id}`, 'PATCH', { ...task, daily_limit: 10 }, s.parent.token))
      .status,
    200,
  );
  const submit = (body, token = s.parent.token, key = uid()) =>
    s.call(`/api/tasks/${task.id}/submit`, 'POST', body, token, key);
  assert.equal((await submit({ unit_stars: 4, quantity: 3 }, s.child.token)).status, 400);
  const pending = await submit({}, s.child.token);
  const review = (body) =>
    s.call(`/api/submissions/${pending.data.id}/review`, 'POST', body, s.parent.token);
  assert.equal((await review({ approve: true, unit_stars: 3, quantity: 11 })).status, 409);
  assert.equal((await review({ approve: true, unit_stars: 0, quantity: 2 })).status, 400);
  assert.equal((await review({ approve: true, unit_stars: 3, quantity: 4 })).status, 200);
  assert.equal((await s.dashboard()).tasks.find((t) => t.id === task.id).approved, 4);
  assert.equal((await s.dashboard()).wallet.balance, d.wallet.balance + 12);
  const key = uid();
  for (let i = 0; i < 2; i++)
    assert.equal((await submit({ unit_stars: 2, quantity: 3 }, s.parent.token, key)).status, 200);
  assert.equal((await submit({ unit_stars: 2, quantity: 2 }, s.parent.token, key)).status, 409);
  const concurrent = await Promise.all([
    submit({ unit_stars: 1, quantity: 2 }),
    submit({ unit_stars: 1, quantity: 2 }),
  ]);
  assert.deepEqual(concurrent.map((r) => r.status).sort(), [200, 409]);
  assert.equal((await s.dashboard()).tasks.find((t) => t.id === task.id).approved, 9);
  const baseline = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  const group = d.rules.filter((r) => r.subject === 'chinese'),
    moving = group[1];
  const reorder = (token) =>
    s.call(
      `/api/children/${s.child.user.id}/task-order`,
      'POST',
      { rule_key: moving.rule_key, direction: 'up' },
      token,
    );
  assert.equal((await reorder(s.child.token)).status, 403);
  assert.equal((await reorder(s.parent.token)).status, 200);
  assert.equal(
    (await s.dashboard()).tasks.filter((t) => t.subject === 'chinese')[0].rule_key,
    moving.rule_key,
  );
  const backup = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.deepEqual(backup.data.rules, baseline.data.rules);
  assert.deepEqual(backup.data.tasks, baseline.data.tasks);
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
  const parent = await s.login('parent', 'parent123');
  const restored = (
    await s.call(`/api/children/${s.child.user.id}/dashboard`, 'GET', null, parent.token)
  ).data;
  assert.equal(restored.tasks.filter((t) => t.subject === 'chinese')[0].rule_key, moving.rule_key);
  assert.equal(restored.tasks.find((t) => t.id === task.id).approved, 9);
  assert.equal(restored.wallet.balance, d.wallet.balance + 20);
});

async function cardRule(s, stars = 3, daily_limit = 10) {
  const result = await s.call(
    `/api/children/${s.child.user.id}/rules`,
    'POST',
    {
      title: '自定义实体任务卡',
      description: '保留规则和今日快照',
      subject: 'chinese',
      icon: 'book',
      stars,
      daily_limit,
      schedule: 'daily',
    },
    s.parent.token,
  );
  assert.equal(result.status, 201);
  return (await s.dashboard()).tasks.find((task) => task.rule_key === result.data.rule_key);
}
function claim(
  s,
  task,
  operation = uid(),
  token = s.parent.token,
  date = today(),
  cardUid = operation.replaceAll('-', '').slice(0, 14).toUpperCase(),
) {
  return s.call(
    `/api/children/${task.child_id}/task-cards/claim`,
    'POST',
    { rule_key: task.rule_key, date, card_uid: cardUid },
    token,
    operation,
  );
}
function adjust(s, entry, amount, operation = uid(), token = s.parent.token) {
  return s.call(
    `/api/ledger/${entry.id}/adjust`,
    'POST',
    { amount, expected_amount: entry.amount },
    token,
    operation,
  );
}

test('card instantly approves the oldest pending submission using today snapshot; retry never grants twice', async (t) => {
  const s = await setup(t),
    task = await cardRule(s);
  const pending = await s.call(
    `/api/tasks/${task.id}/submit`,
    'POST',
    { note: '孩子已完成' },
    s.child.token,
    uid(),
  );
  assert.equal(pending.status, 200);
  await s.call(
    `/api/rules/${task.rule_key}`,
    'PATCH',
    { ...task, title: '明天规则', subject: 'english', stars: 9, daily_limit: 1, schedule: 'daily' },
    s.parent.token,
  );
  const before = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data.data;
  const initialBalance = (await s.dashboard()).wallet.balance,
    operation = uid();
  const results = await Promise.all([claim(s, task, operation), claim(s, task, operation)]);
  assert.ok(results.every((r) => r.status === 200));
  assert.equal(results[0].data.entry.id, results[1].data.entry.id);
  assert.equal(results[0].data.submission.id, pending.data.id);
  assert.equal(results[0].data.entry.amount, 3);
  assert.equal(results[0].data.task.daily_limit, 10);
  assert.equal(results[0].data.task.title, task.title);
  const after = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data.data;
  assert.deepEqual(after.rules, before.rules);
  assert.deepEqual(after.tasks, before.tasks);
  assert.equal(after.submissions.length, before.submissions.length);
  assert.equal(after.ledger.length, before.ledger.length + 1);
  assert.equal((await s.dashboard()).wallet.balance, initialBalance + 3);
  const changedPayload = await claim(s, { ...task, rule_key: uid() }, operation);
  assert.equal(changedPayload.status, 409);
});

test('card respects ten completions, permissions and dates; no pending submission creates one completion', async (t) => {
  const s = await setup(t),
    task = await cardRule(s, 2);
  await s.call(`/api/tasks/${task.id}/submit`, 'POST', { quantity: 9 }, s.parent.token, uid());
  const before = await s.dashboard();
  assert.equal((await claim(s, task)).status, 200);
  assert.equal((await claim(s, task)).status, 409);
  assert.equal((await claim(s, task, uid(), s.child.token)).status, 403);
  assert.equal((await claim(s, task, uid(), s.parent.token, addDays(today(), 1))).status, 400);
  assert.equal((await claim(s, { ...task, rule_key: uid() })).status, 404);
  const family = (await s.call('/api/admin/families', 'POST', { name: '其他家庭' }, s.admin.token))
    .data;
  const foreign = (
    await s.call(
      '/api/admin/users',
      'POST',
      {
        family_id: family.id,
        role: 'child',
        username: 'foreign-card',
        name: '其他孩子',
        password: 'password123',
      },
      s.admin.token,
    )
  ).data;
  assert.equal((await claim(s, { ...task, child_id: foreign.id })).status, 403);
  const after = await s.dashboard();
  assert.equal(after.wallet.balance, before.wallet.balance + 2);
  assert.equal(after.tasks.find((x) => x.id === task.id).approved, 10);
  assert.equal(after.tasks.find((x) => x.id === task.id).daily_limit, 10);
});

test('award corrections append auditable records, replay safely, preserve completions and restore from backup', async (t) => {
  const s = await setup(t),
    task = await cardRule(s),
    initial = await s.dashboard();
  const award = (await claim(s, task)).data.entry;
  const before = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  const operation = uid(),
    increased = await adjust(s, award, 5, operation);
  assert.equal(increased.status, 200);
  const replay = await adjust(s, award, 5, operation);
  assert.equal(replay.data.entry.id, increased.data.entry.id);
  assert.equal((await adjust(s, award, 4, operation)).status, 409);
  assert.equal((await adjust(s, award, 6)).status, 409);
  const decreased = await adjust(s, increased.data.entry, 2);
  assert.equal(decreased.status, 200);
  const after = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  for (const table of ['rules', 'tasks', 'submissions', 'users', 'families'])
    assert.deepEqual(after.data[table], before.data[table]);
  for (const entry of before.data.ledger)
    assert.deepEqual(
      after.data.ledger.find((x) => x.id === entry.id),
      entry,
    );
  assert.equal(after.data.ledger.length, before.data.ledger.length + 4);
  const current = await s.dashboard();
  assert.equal(current.wallet.balance, initial.wallet.balance + 2);
  assert.equal(current.wallet.earned, initial.wallet.earned + 2);
  assert.equal(current.wallet.spent, initial.wallet.spent);
  assert.equal(current.dayEarned, initial.dayEarned + 2);
  assert.equal(current.week.at(-1).earned, initial.week.at(-1).earned + 2);
  assert.equal(current.tasks.find((x) => x.id === task.id).approved, 1);
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup: after, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
  s.parent = await s.login('parent', 'parent123');
  const restored = await s.call(
    `/api/children/${s.child.user.id}/dashboard`,
    'GET',
    null,
    s.parent.token,
  );
  assert.equal(restored.data.wallet.balance, initial.wallet.balance + 2);
});

test('corrections check final balance atomically and concurrent editors cannot replace an award twice', async (t) => {
  const s = await setup(t),
    task = await cardRule(s, 5);
  const award = (await claim(s, task)).data.entry;
  const wallet = (await s.dashboard()).wallet;
  await s.call(
    `/api/children/${task.child_id}/ledger`,
    'POST',
    { amount: wallet.balance, kind: 'spend', title: '用完余额' },
    s.parent.token,
    uid(),
  );
  const before = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.equal((await adjust(s, award, 2)).status, 409);
  assert.deepEqual(
    (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data.data,
    before.data,
  );
  const competing = await Promise.all([adjust(s, award, 6), adjust(s, award, 7)]);
  assert.deepEqual(competing.map((r) => r.status).sort(), [200, 409]);
  const successful = competing.find((r) => r.status === 200).data.entry;
  assert.equal((await s.dashboard()).wallet.balance, successful.amount - award.amount);
  assert.equal((await adjust(s, successful, 9, uid(), s.child.token)).status, 403);
  const family = (await s.call('/api/admin/families', 'POST', { name: '另一家庭' }, s.admin.token))
    .data;
  await s.call(
    '/api/admin/users',
    'POST',
    {
      family_id: family.id,
      role: 'parent',
      username: 'other-parent',
      name: '其他家长',
      password: 'password123',
    },
    s.admin.token,
  );
  const stranger = await s.login('other-parent', 'password123');
  assert.equal((await adjust(s, successful, 9, uid(), stranger.token)).status, 403);
  const bonus = (
    await s.call(
      `/api/children/${task.child_id}/ledger`,
      'POST',
      { amount: 2, kind: 'bonus', title: '普通奖励' },
      s.parent.token,
      uid(),
    )
  ).data;
  assert.equal((await adjust(s, bonus, 3)).status, 409);
});

test('editing a past award attributes corrected income to its original day without changing history snapshots', async (t) => {
  const s = await setup(t),
    date = addDays(today(), -1);
  const past = await s.dashboard(date),
    task = past.tasks[0];
  const award = await s.call(
    `/api/tasks/${task.id}/submit`,
    'POST',
    { unit_stars: 4 },
    s.parent.token,
    uid(),
  );
  assert.equal(award.status, 200);
  const entry = (await s.dashboard(date)).ledger.find((x) => x.submission_id === award.data.id);
  const current = await s.dashboard(),
    history = await s.dashboard(date);
  const changed = await adjust(s, entry, 7);
  assert.equal(changed.status, 200);
  assert.equal(changed.data.entry.date, date);
  const after = await s.dashboard(),
    afterHistory = await s.dashboard(date);
  assert.equal(after.dayEarned, current.dayEarned);
  assert.equal(afterHistory.dayEarned, history.dayEarned + 3);
  assert.equal(after.wallet.balance, current.wallet.balance + 3);
  assert.equal(after.wallet.earned, current.wallet.earned + 3);
  assert.deepEqual(afterHistory.tasks, history.tasks);
});

test('card inspection identifies unscheduled cards without materializing snapshots or changing any records', async (t) => {
  const s = await setup(t);
  const rule = (
    await s.call(
      `/api/children/${s.child.user.id}/rules`,
      'POST',
      {
        title: '只读检测卡',
        subject: 'math',
        stars: 3,
        daily_limit: 10,
        schedule: 'daily',
      },
      s.parent.token,
    )
  ).data;
  const before = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data.data;
  const path = `/api/children/${s.child.user.id}/task-cards/${rule.rule_key}`;
  for (let i = 0; i < 3; i++) {
    const inspected = await s.call(path, 'GET', null, s.parent.token);
    assert.equal(inspected.status, 200);
    assert.equal(inspected.data.child.id, s.child.user.id);
    assert.equal(inspected.data.rule.title, rule.title);
    assert.equal(inspected.data.rule.daily_limit, 10);
    assert.equal(inspected.data.today_task, null);
    assert.equal(inspected.data.scheduled_today, true);
    assert.ok(!('password_hash' in inspected.data.child));
  }
  assert.equal((await s.call(path, 'GET', null, s.child.token)).status, 403);
  assert.equal(
    (
      await s.call(
        `/api/children/${s.child.user.id}/task-cards/${uid()}`,
        'GET',
        null,
        s.parent.token,
      )
    ).status,
    404,
  );
  assert.deepEqual(
    (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data.data,
    before,
  );
  const unscheduled = (
    await s.call(
      `/api/children/${s.child.user.id}/rules`,
      'POST',
      {
        title: '尚未安排卡',
        subject: 'sports',
        stars: 2,
        daily_limit: 1,
        schedule: 'once',
        on_date: '2099-01-01',
      },
      s.parent.token,
    )
  ).data;
  const result = await s.call(
    `/api/children/${s.child.user.id}/task-cards/${unscheduled.rule_key}`,
    'GET',
    null,
    s.parent.token,
  );
  assert.equal(result.status, 200);
  assert.equal(result.data.rule.title, '尚未安排卡');
  assert.equal(result.data.scheduled_today, false);
  assert.equal(result.data.today_task, null);
});

test('card inspection shows latest name and original today snapshot separately and enforces family boundaries', async (t) => {
  const s = await setup(t),
    task = await cardRule(s, 3, 10);
  const pending = await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, uid());
  await s.call(
    `/api/rules/${task.rule_key}`,
    'PATCH',
    {
      ...task,
      title: '明天的新卡名',
      subject: 'english',
      stars: 9,
      daily_limit: 1,
      schedule: 'daily',
      enabled: false,
    },
    s.parent.token,
  );
  const family = (
    await s.call('/api/admin/families', 'POST', { name: '检测权限家庭' }, s.admin.token)
  ).data;
  const foreign = (
    await s.call(
      '/api/admin/users',
      'POST',
      {
        family_id: family.id,
        role: 'child',
        username: 'inspect-other',
        name: '其他孩子',
        password: 'password123',
      },
      s.admin.token,
    )
  ).data;
  const before = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data.data;
  const result = await s.call(
    `/api/children/${task.child_id}/task-cards/${task.rule_key}`,
    'GET',
    null,
    s.parent.token,
  );
  assert.equal(result.status, 200);
  assert.equal(result.data.rule.title, '明天的新卡名');
  assert.equal(result.data.rule.enabled, 0);
  assert.equal(result.data.today_task.title, task.title);
  assert.equal(result.data.today_task.stars, 3);
  assert.equal(result.data.today_task.daily_limit, 10);
  assert.equal(result.data.today_task.pending, 1);
  assert.equal(result.data.scheduled_today, true);
  assert.equal(
    get(s.db, 'SELECT status FROM submissions WHERE id=?', pending.data.id).status,
    'pending',
  );
  assert.equal(
    (
      await s.call(
        `/api/children/${foreign.id}/task-cards/${task.rule_key}`,
        'GET',
        null,
        s.parent.token,
      )
    ).status,
    403,
  );
  assert.deepEqual(
    (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data.data,
    before,
  );
});

test('one physical card per family per day survives new requests and other parents; ten different cards respect cap', async (t) => {
  const s = await setup(t),
    task = await cardRule(s, 2, 10),
    before = await s.dashboard();
  const card = '04112233445566';
  const results = await Promise.all(
    Array.from({ length: 5 }, () => claim(s, task, uid(), s.parent.token, today(), card)),
  );
  assert.ok(results.every((r) => r.status === 200));
  assert.equal(results.filter((r) => !r.data.already_claimed).length, 1);
  assert.equal(new Set(results.map((r) => r.data.entry.id)).size, 1);
  await s.call(
    '/api/admin/users',
    'POST',
    {
      family_id: s.parent.user.family_id,
      role: 'parent',
      username: 'second-card-parent',
      name: '另一位家长',
      password: 'password123',
    },
    s.admin.token,
  );
  const secondParent = await s.login('second-card-parent', 'password123');
  const duplicate = await claim(s, task, uid(), secondParent.token, today(), card);
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.data.already_claimed, true);
  for (let i = 1; i <= 9; i++) {
    const different = '040000000000' + i.toString(16).padStart(2, '0');
    const result = await claim(s, task, uid(), s.parent.token, today(), different);
    assert.equal(result.status, 200);
    assert.equal(result.data.already_claimed, false);
  }
  assert.equal(
    (await claim(s, task, uid(), s.parent.token, today(), '04FFFFFFFFFFFF')).status,
    409,
  );
  assert.equal(
    (await claim(s, task, uid(), s.parent.token, today(), card)).data.already_claimed,
    true,
  );
  const after = await s.dashboard();
  assert.equal(after.tasks.find((x) => x.id === task.id).approved, 10);
  assert.equal(after.tasks.find((x) => x.id === task.id).daily_limit, 10);
  assert.equal(after.wallet.balance, before.wallet.balance + 20);
});

test('daily card use does not approve another pending task and persists through correction, reversal and backup restore', async (t) => {
  const s = await setup(t),
    task = await cardRule(s, 3, 10),
    card = '04AABBCCDDEEFF';
  for (let i = 0; i < 2; i++)
    assert.equal(
      (await s.call(`/api/tasks/${task.id}/submit`, 'POST', {}, s.child.token, uid())).status,
      200,
    );
  const first = await claim(s, task, uid(), s.parent.token, today(), card);
  const corrected = await adjust(s, first.data.entry, 5);
  const duplicate = await claim(s, task, uid(), s.parent.token, today(), card);
  assert.equal(duplicate.data.already_claimed, true);
  assert.equal(duplicate.data.entry.id, corrected.data.entry.id);
  assert.equal(duplicate.data.entry.amount, 5);
  assert.equal((await s.dashboard()).tasks.find((x) => x.id === task.id).pending, 1);
  await s.call(
    `/api/ledger/${corrected.data.entry.id}/reverse`,
    'POST',
    { note: '取消积分但不释放实体卡' },
    s.parent.token,
  );
  const changedBinding = await cardRule(s, 4, 10);
  const rebound = await claim(s, changedBinding, uid(), s.parent.token, today(), card);
  assert.equal(rebound.data.already_claimed, true);
  assert.equal(rebound.data.task.id, task.id);
  assert.equal((await s.dashboard()).tasks.find((x) => x.id === changedBinding.id).approved, 0);
  const inspected = await s.call(
    `/api/children/${task.child_id}/task-cards/${task.rule_key}?card_uid=${card.toLowerCase()}`,
    'GET',
    null,
    s.parent.token,
  );
  assert.equal(inspected.data.claimed_today, true);
  assert.equal(inspected.data.card_uid, card);
  const backup = (await s.call('/api/admin/backup', 'GET', null, s.admin.token)).data;
  assert.equal(
    (
      await s.call(
        '/api/admin/restore',
        'POST',
        { backup, password: 'local-admin-2026' },
        s.admin.token,
      )
    ).status,
    200,
  );
  s.parent = await s.login('parent', 'parent123');
  const restored = await claim(s, task, uid(), s.parent.token, today(), card);
  assert.equal(restored.data.already_claimed, true);
  const next = await claim(s, task, uid(), s.parent.token, today(), '04000000000002');
  assert.equal(next.status, 200);
  assert.equal(next.data.already_claimed, false);
  const d = (await s.call(`/api/children/${task.child_id}/dashboard`, 'GET', null, s.parent.token))
    .data;
  assert.equal(d.tasks.find((x) => x.id === task.id).approved, 2);
  assert.equal(d.tasks.find((x) => x.id === task.id).pending, 0);
});

test('the same physical card is reusable after Shanghai midnight, with a new operation and daily cap', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-10-11T15:59:59Z').getTime() });
  const s = await setup(t),
    task = await cardRule(s, 2, 1),
    card = '04111122223333';
  const first = await claim(s, task, uid(), s.parent.token, today(), card);
  assert.equal(first.status, 200);
  assert.equal(first.data.already_claimed, false);
  assert.equal(
    (await claim(s, task, uid(), s.parent.token, today(), card)).data.already_claimed,
    true,
  );
  t.mock.timers.tick(2000);
  assert.equal(today(), '2026-10-12');
  const nextDay = await claim(s, task, uid(), s.parent.token, today(), card);
  assert.equal(nextDay.status, 200);
  assert.equal(nextDay.data.already_claimed, false);
  assert.notEqual(nextDay.data.entry.id, first.data.entry.id);
  assert.equal(nextDay.data.task.daily_limit, 1);
  assert.equal(
    (await claim(s, task, uid(), s.parent.token, today(), card)).data.already_claimed,
    true,
  );
});

test('physical card identity is required and operation identifiers cannot be reused for a different card', async (t) => {
  const s = await setup(t),
    task = await cardRule(s),
    operation = uid();
  const path = `/api/children/${task.child_id}/task-cards/claim`;
  for (const card_uid of [undefined, '', 'not-a-uid', '0411']) {
    const result = await s.call(
      path,
      'POST',
      { rule_key: task.rule_key, date: today(), card_uid },
      s.parent.token,
      uid(),
    );
    assert.equal(result.status, 400);
  }
  const first = await claim(s, task, operation, s.parent.token, today(), '04112233445566');
  assert.equal(first.status, 200);
  assert.equal(
    (await claim(s, task, operation, s.parent.token, today(), '04AABBCCDDEEFF')).status,
    409,
  );
  const replay = await claim(s, task, operation, s.parent.token, today(), '04112233445566');
  assert.equal(replay.data.replayed, true);
  assert.equal(replay.data.entry.id, first.data.entry.id);
});
