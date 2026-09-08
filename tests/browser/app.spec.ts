import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
async function login(page: any, role = 'child') {
  await page.goto('/');
  await page
    .getByRole('button', {
      name: role === 'child' ? '小朋友' : role === 'parent' ? '家长' : '管理员',
      exact: true,
    })
    .click();
  await expect(page.getByRole('button', { name: '设置', exact: true })).toBeVisible();
}
test('tablet child submits, parent approves, wallet and reward request complete', async ({
  page,
  request,
}) => {
  await page.setViewportSize({ width: 1120, height: 736 });
  await login(page);
  const childLogin = await request.post('http://127.0.0.1:3002/api/login', {
    data: { username: 'child', password: 'child123' },
  });
  const child = await childLogin.json();
  const parentLogin = await request.post('http://127.0.0.1:3002/api/login', {
    data: { username: 'parent', password: 'parent123' },
  });
  const parent = await parentLogin.json();
  const title = '测试阅读 ' + Date.now();
  const made = await request.post(`http://127.0.0.1:3002/api/children/${child.user.id}/rules`, {
    headers: { Authorization: 'Bearer ' + parent.token },
    data: { title, description: '读一小段', stars: 2, daily_limit: 2, schedule: 'daily' },
  });
  expect(made.ok()).toBeTruthy();
  await page.reload();
  const card = page.locator('.task-card').filter({ hasText: title });
  for (let i = 0; i < 10 && !(await card.count()); i++)
    await page.getByRole('button', { name: '下一页', exact: true }).click();
  await card.getByRole('button', { name: '我完成啦' }).click();
  await page.getByRole('textbox', { name: '想告诉家长的话（可选）' }).fill('今天我读了一个故事');
  await page.getByRole('button', { name: '我完成了 1 次，请家长确认' }).click();
  await expect(page.getByRole('heading', { name: '太棒啦，任务已提交！' }).first()).toBeVisible();
  await page.getByRole('button', { name: '继续我的探险' }).click();
  await expect(card).toContainText('1 次待确认');
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: '退出 / 更换账号' }).click();
  await page.getByRole('button', { name: '家长', exact: true }).click();
  await page.getByRole('button', { name: /^待确认/ }).click();
  const review = page.locator('.review-card').filter({ hasText: title });
  await expect(review).toContainText('今天我读了一个故事');
  await review.getByRole('button', { name: '通过 +2' }).click();
  await expect(review).toHaveCount(0);
  await page.getByRole('button', { name: '星星口袋', exact: true }).click();
  await expect(page.locator('.ledger-row').filter({ hasText: title })).toContainText('+2');
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: '退出 / 更换账号' }).click();
  await page.getByRole('button', { name: '小朋友', exact: true }).click();
  await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
  const reward = page.locator('.reward-card').filter({ hasText: '甜甜冰淇淋' });
  if (await reward.getByRole('button', { name: '我想兑换' }).count()) {
    await reward.getByRole('button', { name: '我想兑换' }).click();
    await page.getByRole('button', { name: '请家长帮我兑换' }).click();
    await expect(reward).toContainText('等待确认');
  }
});
test('mobile and tablet layouts fit width and dialog controls remain accessible', async ({
  page,
}) => {
  await login(page);
  for (const [width, height] of [
    [390, 844],
    [736, 1120],
    [1120, 736],
    [1280, 800],
  ]) {
    await page.setViewportSize({ width, height });
    await expect(page.locator('.task-card').first()).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
    ).toBeTruthy();
    if (width > 900) {
      expect(
        await page.locator('.adventure').evaluate((el) => el.getBoundingClientRect().right),
      ).toBeLessThan(
        await page.locator('.daily-content').evaluate((el) => el.getBoundingClientRect().left),
      );
    }
    await page.getByRole('button', { name: '设置', exact: true }).click();
    await expect(page.getByRole('button', { name: '退出 / 更换账号' })).toBeVisible();
    await page.getByRole('button', { name: '关闭', exact: true }).click();
  }
  await page.getByRole('button', { name: '星星口袋', exact: true }).click();
  await expect(page.getByText('可用星星', { exact: true })).toBeVisible();
  await page.getByLabel('收支筛选').selectOption('out');
  await expect(page.locator('.ledger')).toContainText('买冰淇淋');
  await page.getByLabel('流水日期').fill('2020-01-01');
  await expect(page.locator('.ledger')).toContainText('这一天还没有星星记录');
});
test('parent creates capped weekly rule and reward through UI', async ({ page }) => {
  await login(page, 'parent');
  await page.getByRole('button', { name: '任务规则', exact: true }).click();
  await page.getByRole('button', { name: '添加任务', exact: true }).click();
  const title = '练习拼图 ' + Date.now();
  await page.getByLabel('任务名称', { exact: true }).fill(title);
  await page.getByLabel('给孩子的具体说明').fill('每天练习十分钟');
  await page.getByLabel('每次获得星星').fill('2');
  await page.getByLabel('每天最多完成次数').fill('3');
  await page.getByLabel('重复安排').selectOption('weekly');
  await page.getByRole('button', { name: '保存任务规则' }).click();
  await expect(page.locator('.rule-card').filter({ hasText: title })).toContainText(
    '每日上限 3 次',
  );
  await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
  await page.getByRole('button', { name: '添加奖励', exact: true }).click();
  await page.getByLabel('奖励名称').fill('测试野餐 ' + Date.now());
  await page.getByLabel('奖励说明').fill('周末一起去公园');
  await page.getByLabel('兑换所需星星').fill('12');
  await page.getByRole('button', { name: '保存奖励' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('admin creates a family and allocated child account, downloads a backup', async ({ page }) => {
  await login(page, 'admin');
  const suffix = Date.now().toString();
  await page.getByLabel('家庭名称', { exact: true }).fill('测试家庭 ' + suffix);
  await page.getByRole('button', { name: '创建家庭', exact: true }).click();
  await expect(page.locator('.family-tags')).toContainText('测试家庭 ' + suffix);
  await page.getByLabel('家庭', { exact: true }).selectOption({ label: '测试家庭 ' + suffix });
  await page.getByLabel('称呼', { exact: true }).fill('测试宝宝');
  await page.getByLabel('登录账号', { exact: true }).fill('kid' + suffix);
  await page.getByLabel('初始密码（至少 8 位）').fill('test-password-2026');
  await page.getByRole('button', { name: '分配账号', exact: true }).click();
  await expect(page.locator('.account-list')).toContainText('kid' + suffix);
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载完整备份' }).click();
  expect((await download).suggestedFilename()).toContain('star-backup-');
});

test('remembered login, subject presets, skins, and landscape main actions stay visible', async ({
  page,
  context,
}) => {
  await page.setViewportSize({ width: 1120, height: 704 });
  await login(page);
  for (const label of ['语文', '数学', '英语', '体育', '其他']) {
    await page.getByRole('button', { name: label, exact: true }).click();
    await expect(page.locator('.subject-art').first()).toHaveAttribute('alt', label);
  }
  await page.getByRole('button', { name: '全部', exact: true }).click();
  for (const button of await page
    .locator('.task-card button.primary,.task-pagination button')
    .all()) {
    const box = await button.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.y + box!.height).toBeLessThanOrEqual(704);
  }
  await page.getByRole('button', { name: '换装', exact: true }).click();
  await page.getByRole('button', { name: /公主花园/ }).click();
  await expect(page.locator('html')).toHaveAttribute('data-skin', 'princess');
  await page.screenshot({ path: 'artifacts/web-princess-landscape.png', fullPage: true });
  const another = await context.newPage();
  await another.goto('/');
  await expect(another.getByRole('button', { name: '设置', exact: true })).toBeVisible();
  await expect(another.locator('html')).toHaveAttribute('data-skin', 'princess');
  await another.close();
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await page.getByRole('button', { name: '退出 / 更换账号' }).click();
  await expect(page.getByRole('button', { name: '出发，去星星岛', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: '出发，去星星岛', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '家长', exact: true }).click();
  await page.getByRole('button', { name: '任务规则', exact: true }).click();
  await page.getByRole('button', { name: '添加任务', exact: true }).click();
  await page.getByLabel('选择预制模板（可修改）').selectOption('3');
  await expect(page.getByLabel('一级科目')).toHaveValue('math');
  await expect(page.getByLabel('任务名称', { exact: true })).toHaveValue('数学趣味挑战');
});

test('parent can edit a pending reward, take it off sale and put it back', async ({
  page,
  request,
}) => {
  await login(page, 'parent');
  await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
  await expect(page.getByRole('heading', { name: '奖励小铺管理', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '添加奖励', exact: true }).click();
  const title = '家长管理奖励 ' + Date.now();
  await page.getByLabel('奖励名称', { exact: true }).fill(title);
  await page.getByLabel('兑换所需星星').fill('2');
  await page.getByRole('button', { name: '保存奖励', exact: true }).click();
  const card = page.locator('.reward-card').filter({ hasText: title });
  await expect(card).toContainText('已上架');
  const loginResponse = await request.post('http://127.0.0.1:3002/api/login', {
    data: { username: 'child', password: 'child123' },
  });
  const child = await loginResponse.json();
  const headers = { Authorization: 'Bearer ' + child.token };
  const dashboard = await (
    await request.get(`http://127.0.0.1:3002/api/children/${child.user.id}/dashboard`, { headers })
  ).json();
  const reward = dashboard.rewards.find((r: any) => r.title === title);
  await request.post(`http://127.0.0.1:3002/api/children/${child.user.id}/redemptions`, {
    headers: { ...headers, 'Idempotency-Key': randomUUID() },
    data: { reward_id: reward.id },
  });
  await page.reload();
  await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
  await card.getByRole('button', { name: '编辑奖励', exact: true }).click();
  await page.getByLabel('奖励说明').fill('家长修改的说明');
  await page.getByLabel('兑换所需星星').fill('4');
  await page.getByRole('button', { name: '保存奖励', exact: true }).click();
  await expect(card).toContainText('家长修改的说明');
  await card.getByRole('button', { name: '下架奖励', exact: true }).click();
  await expect(card).toContainText('已下架');
  await page.getByLabel('奖励状态', { exact: true }).selectOption('active');
  await expect(card).toHaveCount(0);
  await page.getByLabel('奖励状态', { exact: true }).selectOption('inactive');
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: '重新上架', exact: true }).click();
  await expect(card).toHaveCount(0);
  await page.getByLabel('奖励状态', { exact: true }).selectOption('active');
  await expect(card).toContainText('已上架');
  await page.getByRole('button', { name: '审核兑换申请', exact: true }).click();
  await expect(page.getByRole('heading', { name: '看看孩子的努力' })).toBeVisible();
});
