import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';

async function loginAdmin(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: '管理员', exact: true }).click();
  await expect(page.getByRole('heading', { name: '家庭与数据管理', exact: true })).toBeVisible();
  await expect(page.locator('.admin-account-row').filter({ hasText: '超级管理员' })).toBeVisible();
}

test('admin account filters search names and families, reset empty results, and keep admin read-only', async ({
  page,
}) => {
  await loginAdmin(page);
  const accounts = page.locator('.admin-accounts');
  const toolbar = accounts.locator('.admin-toolbar');
  const rows = accounts.locator('.admin-account-row');
  const search = toolbar.getByRole('searchbox', { name: '搜索账号', exact: true });
  const role = toolbar.getByRole('combobox', { name: '筛选身份', exact: true });
  const family = toolbar.getByRole('combobox', { name: '筛选家庭', exact: true });
  const state = toolbar.getByRole('combobox', { name: '账号状态', exact: true });
  await expect(search).toHaveAttribute('placeholder', '搜索称呼、账号或家庭');

  await search.fill('parent');
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText('恐龙妈妈');
  await search.fill('恐龙妈妈');
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText('parent');
  await search.fill('小恐龙一家');
  await expect(rows.filter({ hasText: '恐龙妈妈' })).toBeVisible();
  await expect(rows.filter({ hasText: '小星' })).toBeVisible();
  await expect(rows.filter({ hasText: '超级管理员' })).toHaveCount(0);

  await search.fill('');
  await role.selectOption('parent');
  await expect(rows.filter({ hasText: '恐龙妈妈' })).toBeVisible();
  await expect(rows.filter({ hasText: '小星' })).toHaveCount(0);
  await role.selectOption('child');
  await expect(rows.filter({ hasText: '小星' })).toBeVisible();
  await expect(rows.filter({ hasText: '恐龙妈妈' })).toHaveCount(0);
  await role.selectOption('all');
  await family.selectOption({ label: '小恐龙一家' });
  await expect(rows.filter({ hasText: '恐龙妈妈' })).toBeVisible();
  await expect(rows.filter({ hasText: '小星' })).toBeVisible();
  await expect(rows.filter({ hasText: '超级管理员' })).toHaveCount(0);

  await state.selectOption('inactive');
  await search.fill('没有这个账号 ' + randomUUID());
  await expect(rows).toHaveCount(0);
  await expect(accounts.getByText('没有找到匹配的账号', { exact: true })).toBeVisible();
  await accounts.getByRole('button', { name: '清除筛选', exact: true }).click();
  await expect(search).toHaveValue('');
  await expect(role).toHaveValue('all');
  await expect(family).toHaveValue('all');
  await expect(state).toHaveValue('all');
  for (const name of ['恐龙妈妈', '小星']) {
    const row = rows.filter({ hasText: name });
    await expect(row.getByRole('button', { name: '停用', exact: true })).toBeVisible();
    await expect(row.getByRole('button', { name: '重置密码', exact: true })).toBeVisible();
  }
  await role.selectOption('admin');
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText('超级管理员');
  await expect(rows.getByRole('button', { name: /^(停用|启用|重置密码)$/ })).toHaveCount(0);
});

test('creating an account selects its new family, clears filters, and supports reversible state changes', async ({
  page,
}) => {
  await loginAdmin(page);
  const suffix = randomUUID().slice(0, 8);
  const familyName = '交互测试家庭 ' + suffix;
  const username = 'fluid_' + suffix;
  const displayName = '测试宝宝 ' + suffix;
  const toolbar = page.locator('.admin-accounts .admin-toolbar');
  const search = toolbar.getByRole('searchbox', { name: '搜索账号', exact: true });
  const role = toolbar.getByRole('combobox', { name: '筛选身份', exact: true });
  const family = toolbar.getByRole('combobox', { name: '筛选家庭', exact: true });
  const state = toolbar.getByRole('combobox', { name: '账号状态', exact: true });
  await search.fill('不匹配的筛选 ' + suffix);
  await role.selectOption('parent');
  await family.selectOption({ label: '小恐龙一家' });
  await state.selectOption('inactive');

  await page.getByLabel('家庭名称', { exact: true }).fill(familyName);
  await page.getByRole('button', { name: '创建家庭', exact: true }).click();
  const allocationFamily = page.getByLabel('家庭', { exact: true });
  await expect(allocationFamily.locator('option:checked')).toHaveText(familyName);
  await page.getByRole('button', { name: '添加账号', exact: true }).click();
  const name = page.getByLabel('称呼', { exact: true });
  await expect(name).toBeFocused();
  await name.fill(displayName);
  await page.getByLabel('身份', { exact: true }).selectOption('child');
  await page.getByLabel('登录账号', { exact: true }).fill(username);
  await page.getByLabel('初始密码（至少 8 位）', { exact: true }).fill('admin-ui-test-2026');
  await page.getByRole('button', { name: '分配账号', exact: true }).click();
  const row = page.locator('.admin-account-row').filter({ hasText: username });
  await expect(row).toBeVisible();
  await expect(row).toContainText(displayName);
  await expect(row).toContainText(familyName);
  await expect(search).toHaveValue('');
  await expect(role).toHaveValue('all');
  await expect(family).toHaveValue('all');
  await expect(state).toHaveValue('all');
  await expect(row.locator('.admin-state')).toHaveAttribute('data-active', 'true');

  await row.getByRole('button', { name: '停用', exact: true }).click();
  await expect(row.locator('.admin-state')).toHaveAttribute('data-active', 'false');
  await state.selectOption('inactive');
  await expect(row).toBeVisible();
  await expect(page.locator('.admin-account-row .admin-state[data-active="true"]')).toHaveCount(0);
  await row.getByRole('button', { name: '启用', exact: true }).click();
  await expect(row).toHaveCount(0);
  await state.selectOption('active');
  await expect(row).toBeVisible();
  await expect(row.locator('.admin-state')).toHaveAttribute('data-active', 'true');
  await expect(page.locator('.admin-account-row .admin-state[data-active="false"]')).toHaveCount(0);
});

test('a family stays selected after its refresh fails so the next account is allocated correctly', async ({
  page,
}) => {
  await loginAdmin(page);
  const suffix = randomUUID().slice(0, 8);
  const familyName = '刷新恢复家庭 ' + suffix;
  const username = 'recover_' + suffix;
  const allocationFamily = page.getByLabel('家庭', { exact: true });
  await allocationFamily.selectOption({ label: '小恐龙一家' });
  let refreshes = 0;
  const message = '账号列表暂时无法刷新';
  await page.route('**/api/admin/accounts', async (route) => {
    if (route.request().method() === 'GET' && ++refreshes === 1)
      await route.fulfill({ status: 503, json: { error: message } });
    else await route.continue();
  });

  await page.getByLabel('家庭名称', { exact: true }).fill(familyName);
  await page.getByRole('button', { name: '创建家庭', exact: true }).click();
  const error = page.locator('.admin-page .error[role="alert"]');
  await expect(error).toContainText(message);
  await expect(allocationFamily.locator('option:checked')).toHaveText(familyName);
  await expect(page.locator('.family-tags')).toContainText(familyName);

  await page.getByLabel('称呼', { exact: true }).fill('刷新恢复宝宝 ' + suffix);
  await page.getByLabel('身份', { exact: true }).selectOption('child');
  await page.getByLabel('登录账号', { exact: true }).fill(username);
  await page.getByLabel('初始密码（至少 8 位）', { exact: true }).fill('admin-ui-test-2026');
  await page.getByRole('button', { name: '分配账号', exact: true }).click();
  const row = page.locator('.admin-account-row').filter({ hasText: username });
  await expect(row).toBeVisible();
  await expect(row.locator('.admin-account-family')).toHaveText(familyName);
  await expect(error).toHaveCount(0);
  expect(refreshes).toBe(2);
});

test('admin overview and controls fit desktop, tablet, and mobile widths', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await loginAdmin(page);
  const overview = page.locator('.admin-overview');
  await expect(overview.locator('.admin-stat')).toHaveCount(4);
  for (const label of ['家庭', '家长', '小朋友', '已停用'])
    await expect(overview.getByText(label, { exact: true })).toBeVisible();

  for (const { width, height, artifact } of [
    { width: 1440, height: 1000, artifact: 'artifacts/admin-fluid-desktop.png' },
    { width: 1120, height: 800, artifact: '' },
    { width: 390, height: 844, artifact: 'artifacts/admin-fluid-mobile.png' },
  ]) {
    await page.setViewportSize({ width, height });
    await expect(page.locator('.admin-page')).toBeVisible();
    await expect(page.getByRole('button', { name: '添加账号', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '主题', exact: true })).toBeVisible();
    await expect(page.getByRole('searchbox', { name: '搜索账号', exact: true })).toBeVisible();
    await expect(page.locator('.balance-pill')).toHaveCount(0);
    await expect(page.locator('.sound-toggle')).toHaveCount(0);
    if (width <= 900) await expect(page.locator('.navigation')).toBeHidden();
    else await expect(page.locator('.navigation')).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
    ).toBeTruthy();
    if (artifact)
      await page.screenshot({ path: artifact, fullPage: width === 390, animations: 'disabled' });
  }
});

test('restore controls stay folded and reject invalid JSON without a restore request', async ({
  page,
}) => {
  await loginAdmin(page);
  const restore = page.locator('details.admin-restore');
  const summary = restore.locator('summary');
  const file = restore.getByLabel('选择备份文件（JSON，最多 20MB）', { exact: true });
  const confirm = restore.getByRole('button', { name: '确认恢复备份', exact: true });
  let restores = 0;
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/admin/restore')
      restores++;
  });
  await expect(summary).toContainText('恢复已有备份');
  await expect(restore).not.toHaveAttribute('open', '');
  await expect(file).toBeHidden();
  await summary.click();
  await expect(file).toBeVisible();
  await expect(confirm).toBeDisabled();
  await file.setInputFiles({
    name: 'invalid-backup.json',
    mimeType: 'application/json',
    buffer: Buffer.from('{ invalid json'),
  });
  await expect(restore.getByRole('alert')).toContainText('备份不是有效的 JSON 文件');
  await expect(confirm).toBeDisabled();
  await summary.click();
  await expect(file).toBeHidden();
  await expect(page.getByRole('heading', { name: '家庭与数据管理', exact: true })).toBeVisible();
  expect(restores).toBe(0);
});
