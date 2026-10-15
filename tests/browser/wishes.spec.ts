import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

const api = 'http://127.0.0.1:3002';

test('wish cards and icon picker fit phones and tablets without changing saved data', async ({
  page,
  request,
}) => {
  const child = await (
    await request.post(api + '/api/login', { data: { username: 'child', password: 'child123' } })
  ).json();
  const parent = await (
    await request.post(api + '/api/login', { data: { username: 'parent', password: 'parent123' } })
  ).json();
  const headers = { Authorization: 'Bearer ' + parent.token };
  const path = api + '/api/children/' + child.user.id;
  const title = '周末和爸爸妈妈一起去公园野餐并分享这周最开心的故事 ' + randomUUID().slice(0, 6);
  await request.post(path + '/rewards', {
    headers,
    data: {
      title,
      description: '保留完整的自定义说明和兑换价格。',
      icon: 'picnic',
      cost: 100000,
      active: true,
    },
  });
  const before = await (await request.get(path + '/dashboard', { headers })).json();
  await page.addInitScript((token) => localStorage.setItem('star-token', token), parent.token);
  await page.goto('/');
  await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
  const card = page.locator('.reward-card').filter({ hasText: title });
  for (const width of [320, 390, 744, 1280]) {
    await page.setViewportSize({ width, height: 844 });
    await expect(card.getByRole('heading')).toHaveText(title);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width + 1,
    );
    for (const button of await card.getByRole('button').all()) {
      const box = await button.boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(44);
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
    }
    await card.getByRole('button', { name: '编辑奖励', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '编辑奖励' });
    await expect(dialog.getByLabel('奖励名称', { exact: true })).toHaveValue(title);
    await expect(dialog.getByLabel('兑换所需星星')).toHaveValue('100000');
    await expect(dialog.getByRole('radio', { name: '野餐' })).toBeChecked();
    await expect(dialog.locator('.wish-icon-picker svg')).toHaveCount(5);
    await dialog.getByRole('radio', { name: '电影' }).check();
    await expect(dialog.getByRole('radio', { name: '电影' })).toBeChecked();
    await dialog.getByRole('button', { name: '关闭', exact: true }).click();
  }
  await page.getByRole('button', { name: '兑换记录', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '兑换记录', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  const after = await (await request.get(path + '/dashboard', { headers })).json();
  for (const key of ['rules', 'tasks', 'rewards', 'redemptions', 'ledger', 'wallet'])
    expect(after[key]).toEqual(before[key]);
});

test('pending wishes prevent repeat applications and history retains the original price', async ({
  page,
  request,
}) => {
  const child = await (
    await request.post(api + '/api/login', { data: { username: 'child', password: 'child123' } })
  ).json();
  const parent = await (
    await request.post(api + '/api/login', { data: { username: 'parent', password: 'parent123' } })
  ).json();
  const headers = { Authorization: 'Bearer ' + parent.token },
    childHeaders = { Authorization: 'Bearer ' + child.token };
  const path = api + '/api/children/' + child.user.id,
    title = '一起看星星 ' + randomUUID().slice(0, 6);
  const reward = await (
    await request.post(path + '/rewards', {
      headers,
      data: { title, description: '和家人一起的时光', icon: 'gift', cost: 1, active: true },
    })
  ).json();
  const before = await (await request.get(path + '/dashboard', { headers })).json();
  await request.post(path + '/redemptions', {
    headers: { ...childHeaders, 'Idempotency-Key': randomUUID() },
    data: { reward_id: reward.id },
  });
  const updated = await request.patch(api + '/api/rewards/' + reward.id, {
    headers,
    data: { title, description: '修改后仍保留原申请', icon: 'movie', cost: 9, active: true },
  });
  expect(updated.ok(), await updated.text()).toBeTruthy();
  await page.addInitScript((token) => localStorage.setItem('star-token', token), child.token);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
  const card = page.locator('.reward-card').filter({ hasText: title });
  await expect(card.getByRole('button', { name: '等待确认', exact: true })).toBeDisabled();
  await expect(card.locator('.wish-price')).toContainText('9');
  await page.getByRole('button', { name: '兑换记录', exact: true }).click();
  const entry = page.locator('.wish-history-row').filter({ hasText: title });
  await expect(entry).toContainText('1 星');
  await expect(entry).toContainText('等待确认');
  await expect(entry.locator('.wish-icon-gift')).toBeVisible();
  const after = await (await request.get(path + '/dashboard', { headers })).json();
  expect(after.wallet).toEqual(before.wallet);
  expect(after.redemptions.filter((r: any) => r.reward_id === reward.id)).toHaveLength(1);
});
