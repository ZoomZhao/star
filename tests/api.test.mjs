import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../server/app.mjs';
import { addDays, today, uid, get, seedTemplates } from '../server/db.mjs';
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

test('future previews follow template edits while daily overrides and history stay intact', async (t) => {
  const s = await setup(t),
    todayData = await s.dashboard();
  const rule = todayData.rules[0],
    tomorrow = addDays(today(), 1),
    later = addDays(today(), 2);
  const preview = (await s.dashboard(tomorrow)).tasks.find((x) => x.rule_key === rule.rule_key);
  const override = (await s.dashboard(later)).tasks.find((x) => x.rule_key === rule.rule_key);
  assert.equal(
    (await s.call(`/api/tasks/${override.id}`, 'PATCH', { ...override, stars: 9 }, s.parent.token))
      .status,
    200,
  );
  const change = { ...rule, stars: 4, enabled: true };
  assert.equal(
    (await s.call(`/api/rules/${rule.rule_key}`, 'PATCH', change, s.parent.token)).status,
    200,
  );
  const fresh = (await s.dashboard(tomorrow)).tasks.find((x) => x.rule_key === rule.rule_key);
  assert.notEqual(fresh.id, preview.id);
  assert.equal(fresh.stars, 4);
  assert.equal(
    (await s.dashboard()).tasks.find((x) => x.rule_key === rule.rule_key).stars,
    rule.stars,
  );
  assert.equal((await s.dashboard(later)).tasks.find((x) => x.id === override.id).stars, 9);
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
  assert.ok(!(await s.dashboard(tomorrow)).tasks.some((x) => x.rule_key === rule.rule_key));
  assert.equal(
    (
      await s.call(
        `/api/rules/${rule.rule_key}`,
        'PATCH',
        { ...change, schedule: 'once', on_date: later },
        s.parent.token,
      )
    ).status,
    200,
  );
  assert.ok(!(await s.dashboard(tomorrow)).tasks.some((x) => x.rule_key === rule.rule_key));
  assert.equal((await s.dashboard(later)).tasks.find((x) => x.id === override.id).stars, 9);
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
