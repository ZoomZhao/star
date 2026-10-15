import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import type { Dashboard, User } from '../../src/types';

type Account = { id: string; username: string; password: string };
async function call<T = unknown>(
  page: Page,
  path: string,
  method = 'GET',
  body?: unknown,
  keyed = false,
): Promise<T> {
  return page.evaluate(
    async ({ path, method, body, keyed }) => {
      const { api, requestId } = await import('/src/api.ts');
      return api(path, method, body, keyed ? requestId() : undefined);
    },
    { path, method, body, keyed },
  );
}
async function signOut(page: Page) {
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: '退出 / 更换账号', exact: true }).click();
  await expect(page.getByRole('button', { name: '出发，去星星岛', exact: true })).toBeVisible();
}
async function signIn(page: Page, account: Account) {
  await page.getByLabel('账号', { exact: true }).fill(account.username);
  await page.getByLabel('密码', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: '出发，去星星岛', exact: true }).click();
  await expect(page.locator('.balance-pill')).toBeVisible();
}
async function family(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: '管理员', exact: true }).click();
  await expect(page.getByRole('heading', { name: '家庭与数据管理', exact: true })).toBeVisible();
  const suffix = randomUUID().slice(0, 8);
  const group = await call<{ id: string }>(
    page,
    '/api/admin/families',
    'POST',
    { name: '会话回归 ' + suffix },
    true,
  );
  const password = randomUUID();
  const accounts: Account[] = [];
  for (const [index, role] of ['parent', 'parent', 'child', 'child'].entries()) {
    const user = await call<{ id: string; username: string }>(page, '/api/admin/users', 'POST', {
      family_id: group.id,
      name: `回归${index} ${suffix}`,
      username: `session_${suffix}_${index}`,
      role,
      password,
    });
    accounts.push({ id: user.id, username: user.username, password });
  }
  await signOut(page);
  await signIn(page, accounts[0]);
  return { parents: accounts.slice(0, 2), children: accounts.slice(2) };
}
function gate() {
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { held, release };
}

for (const status of [200, 503]) {
  test(`a delayed ${status} mutation cannot replace another child's data or dialog`, async ({
    page,
  }) => {
    const { children } = await family(page);
    for (const [index, child] of children.entries())
      await call(
        page,
        `/api/children/${child.id}/ledger`,
        'POST',
        { title: '切换孩子测试', kind: 'bonus', amount: (index + 1) * 11 },
        true,
      );
    const reward = await call<{ id: string }>(
      page,
      `/api/children/${children[0].id}/rewards`,
      'POST',
      {
        title: '延迟操作奖励',
        description: '保存过的说明',
        icon: 'gift',
        cost: 2,
        active: true,
      },
      true,
    );
    await page.reload();
    await page.getByLabel('选择小朋友', { exact: true }).selectOption(children[0].id);
    await expect(page.locator('.balance-pill')).toHaveText('11');
    await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
    // Another editor changes the reward while this Dashboard still has its old fields.
    await call(page, `/api/rewards/${reward.id}`, 'PATCH', {
      title: '另一位家长的新名称',
      cost: 7,
    });
    const pending = gate();
    let payload: unknown;
    let oldDashboardReads = 0;
    await page.route(`**/api/rewards/${reward.id}`, async (route) => {
      payload = route.request().postDataJSON();
      await pending.held;
      if (status === 200) await route.continue();
      else await route.fulfill({ status, json: { error: '旧孩子操作失败' } });
    });
    await page.getByRole('button', { name: '下架奖励', exact: true }).click();
    await page.getByLabel('选择小朋友', { exact: true }).selectOption(children[1].id);
    await expect(page.locator('.balance-pill')).toHaveText('22');
    page.on('request', (request) => {
      if (request.url().includes(`/children/${children[0].id}/dashboard`)) oldDashboardReads++;
    });
    await page.getByRole('button', { name: '添加奖励', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '添加奖励', exact: true });
    await dialog.getByLabel('奖励名称', { exact: true }).fill('新孩子的未保存输入');
    try {
      const finished = page.waitForResponse((response) =>
        response.url().endsWith(`/api/rewards/${reward.id}`),
      );
      pending.release();
      await (await finished).finished();
      // Let the delayed request's promise and React updates settle before checking absence.
      await page.waitForTimeout(150);
      expect(payload).toEqual({ active: false });
      expect(oldDashboardReads).toBe(0);
      await expect(page.locator('.balance-pill')).toHaveText('22');
      await expect(dialog.getByLabel('奖励名称', { exact: true })).toHaveValue(
        '新孩子的未保存输入',
      );
      await expect(dialog.getByRole('alert')).toHaveCount(0);
      await expect(page.getByRole('status').filter({ hasText: '奖励已下架' })).toHaveCount(0);
      const dashboard = await call<Dashboard>(page, `/api/children/${children[0].id}/dashboard`);
      expect(dashboard.rewards.find((item) => item.id === reward.id)).toMatchObject({
        title: '另一位家长的新名称',
        cost: 7,
      });
    } finally {
      pending.release();
    }
  });
}

test('local logout is immediate and its delayed response cannot clear a new login', async ({
  page,
}) => {
  const { parents } = await family(page);
  const pending = gate();
  let revocations = 0;
  await page.route('**/api/logout', async (route) => {
    revocations++;
    await pending.held;
    await route.continue();
  });
  try {
    await page.getByRole('button', { name: '设置', exact: true }).click();
    await page.getByRole('button', { name: '退出 / 更换账号', exact: true }).evaluate((button) => {
      (button as HTMLButtonElement).click();
      (button as HTMLButtonElement).click();
    });
    await expect(page.getByRole('button', { name: '出发，去星星岛', exact: true })).toBeVisible();
    await signIn(page, parents[1]);
    const response = page.waitForResponse((item) => item.url().endsWith('/api/logout'));
    pending.release();
    await (await response).finished();
    expect(revocations).toBe(1);
    expect((await call<User>(page, '/api/me')).id).toBe(parents[1].id);
    await expect(page.locator('.balance-pill')).toBeVisible();
  } finally {
    pending.release();
  }
});

test('uncertain operations migrate legacy storage, survive re-login, and stay isolated by account', async ({
  page,
}) => {
  const { parents, children } = await family(page);
  const path = `/api/children/${children[0].id}/ledger`;
  const body = { title: '重新登录重试 ' + randomUUID(), kind: 'bonus', amount: 1 };
  const keys: string[] = [];
  await page.route('**' + path, async (route) => {
    keys.push(route.request().headers()['idempotency-key']);
    if (keys.length === 2) {
      await route.fulfill({ status: 401, json: { error: '请重新登录' } });
      return;
    }
    const response = await route.fetch();
    if (keys.length === 1)
      await route.fulfill({ status: 502, json: { error: '结果暂时无法确认' } });
    else await route.fulfill({ response });
  });
  const invoke = () =>
    call(page, path, 'POST', body, true).then(
      () => '',
      (error: Error) => error.message,
    );
  expect(await invoke()).toContain('结果暂时无法确认');
  // Simulate an older Web installation's unresolved entry, then establish its
  // account with /api/me during reload before rotating the token.
  await page.evaluate(
    async ({ path, body }) => {
      const { serverUrl } = await import('/src/api.ts');
      const key = Object.keys(localStorage).find(
        (item) => item.startsWith('star-pending:v2:') && item.includes(body.title),
      )!;
      const legacy =
        'star-pending:' +
        serverUrl() +
        ':' +
        localStorage.getItem('star-token')!.slice(0, 16) +
        ':POST:' +
        path +
        ':' +
        JSON.stringify(body);
      localStorage.setItem(legacy, localStorage.getItem(key)!);
      localStorage.removeItem(key);
      localStorage.removeItem('star-session-identity');
    },
    { path, body },
  );
  await page.reload();
  await expect(page.locator('.balance-pill')).toBeVisible();
  expect(
    await page.evaluate(
      (title) =>
        Object.keys(localStorage)
          .filter((key) => key.includes(title))
          .every((key) => key.startsWith('star-pending:v2:')),
      body.title,
    ),
  ).toBe(true);
  expect(await invoke()).toContain('请重新登录');
  await expect(page.getByRole('button', { name: '出发，去星星岛', exact: true })).toBeVisible();
  await signIn(page, parents[1]);
  expect(await invoke()).toBe('');
  await signOut(page);
  await signIn(page, parents[0]);
  expect(await invoke()).toBe('');
  expect(keys).toHaveLength(4);
  expect(keys[1]).toBe(keys[0]);
  expect(keys[2]).not.toBe(keys[0]);
  expect(keys[3]).toBe(keys[0]);
  expect(
    (await call<Dashboard>(page, `/api/children/${children[0].id}/dashboard`)).wallet.balance,
  ).toBe(2);
});
