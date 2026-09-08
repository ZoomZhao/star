import { test, expect, type Locator, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';

async function login(page: Page, role: 'child' | 'parent' = 'child') {
  await page.goto('/');
  await page
    .getByRole('button', { name: role === 'child' ? '小朋友' : '家长', exact: true })
    .click();
  await expect(page.getByRole('button', { name: '设置', exact: true })).toBeVisible();
  await expect(page.locator('.task-card').first()).toBeVisible();
}

async function addReward(page: Page, title: string) {
  await page.getByRole('button', { name: '奖励小铺', exact: true }).click();
  await page.getByRole('button', { name: '添加奖励', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '添加奖励', exact: true });
  await dialog.getByLabel('奖励名称', { exact: true }).fill(title);
  await dialog.getByLabel('奖励说明', { exact: true }).fill('周末一起去公园野餐');
  await dialog.getByLabel('兑换所需星星', { exact: true }).fill('12');
  return dialog;
}

async function buttonRects(group: Locator) {
  return group.getByRole('button').evaluateAll((buttons) =>
    buttons.map((button) => {
      const { x, y, width, height } = button.getBoundingClientRect();
      return { x, y, width, height };
    }),
  );
}

async function hasIdentityTransform(locator: Locator) {
  return locator.evaluate((element) => {
    const transform = getComputedStyle(element).transform;
    return transform === 'none' || new DOMMatrixReadOnly(transform).isIdentity;
  });
}

test('named native dialogs keep padding clicks inside and restore focus after dismissal', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1120, height: 736 });
  await login(page);
  const trigger = page.getByRole('button', { name: '设置', exact: true });
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: '账号与设置', exact: true });
  await expect(dialog).toBeVisible();
  expect(
    await dialog.evaluate((element) => element instanceof HTMLDialogElement && element.open),
  ).toBeTruthy();
  const headingId = await dialog.getByRole('heading', { name: '账号与设置' }).getAttribute('id');
  expect(headingId).toBeTruthy();
  await expect(dialog).toHaveAttribute('aria-labelledby', headingId!);

  const box = await dialog.boundingBox();
  expect(box).not.toBeNull();
  // The dialog itself receives clicks in its padding, just as it does on the backdrop.
  await dialog.click({ position: { x: 3, y: box!.height / 2 } });
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();

  await trigger.click();
  await expect(dialog).toBeVisible();
  await page.mouse.click(4, 4);
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test('a failed reward save keeps the form and announces an error before a successful retry', async ({
  page,
}) => {
  await login(page, 'parent');
  let attempts = 0;
  const message = '奖励暂时无法保存，请重试';
  await page.route('**/api/children/*/rewards', async (route) => {
    if (route.request().method() === 'POST' && ++attempts === 1)
      await route.fulfill({ status: 400, json: { error: message } });
    else await route.continue();
  });
  const title = '保留输入的野餐 ' + randomUUID();
  const dialog = await addReward(page, title);
  const save = dialog.getByRole('button', { name: '保存奖励', exact: true });
  await save.click();
  await expect(dialog.locator('.error')).toHaveAttribute('role', 'alert');
  await expect(dialog.getByRole('alert')).toContainText(message);
  await expect(dialog.getByLabel('奖励名称', { exact: true })).toHaveValue(title);
  await expect(dialog.getByLabel('奖励说明', { exact: true })).toHaveValue('周末一起去公园野餐');
  await expect(dialog.getByLabel('兑换所需星星', { exact: true })).toHaveValue('12');
  await expect(save).toBeEnabled();
  await save.click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.reward-card').filter({ hasText: title })).toHaveCount(1);
  expect(attempts).toBe(2);
});

test('completing the last allowed task returns focus to its title when the trigger becomes disabled', async ({
  page,
}) => {
  await login(page, 'parent');
  await page.getByRole('button', { name: '任务规则', exact: true }).click();
  await page.getByRole('button', { name: '添加任务', exact: true }).click();
  const title = '完成后焦点测试 ' + randomUUID();
  await page.getByLabel('任务名称', { exact: true }).fill(title);
  await page.getByLabel('每次获得星星', { exact: true }).fill('1');
  await page.getByLabel('每天最多完成次数', { exact: true }).fill('1');
  await page.getByRole('button', { name: '保存任务规则', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '今日任务', exact: true }).click();
  const card = page.locator('.task-card').filter({ hasText: title });
  for (let index = 0; index < 10 && !(await card.count()); index++)
    await page.getByRole('button', { name: '下一页', exact: true }).click();
  await expect(card).toBeVisible();
  const trigger = card.locator('.task-bottom button');
  await trigger.click();
  await page.getByRole('button', { name: '代完成 1 次，发放 1 星', exact: true }).click();
  const celebration = page.getByRole('dialog', { name: '星星到账啦！', exact: true });
  await expect(celebration).toBeVisible();
  await celebration.getByRole('button', { name: '继续我的探险' }).click();
  await expect(celebration).toHaveCount(0);
  await expect(trigger).toBeDisabled();
  await expect(trigger).toHaveText('完成啦');
  await expect(card.locator('.task-title')).toBeFocused();
});

test('saving keeps the button label and size while preventing dialog dismissal', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1120, height: 736 });
  await login(page, 'parent');
  const title = '等待保存的野餐 ' + randomUUID();
  const dialog = await addReward(page, title);
  const save = dialog.getByRole('button', { name: '保存奖励', exact: true });
  await expect.poll(() => hasIdentityTransform(dialog)).toBe(true);
  const before = await save.boundingBox();
  expect(before).not.toBeNull();
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let requests = 0;
  await page.route('**/api/children/*/rewards', async (route) => {
    if (route.request().method() === 'POST') {
      requests++;
      await held;
    }
    await route.continue();
  });

  try {
    await save.click();
    await expect(save).toHaveAttribute('aria-busy', 'true');
    await expect(save).toHaveAttribute('data-loading', 'true');
    await expect(save).toHaveAccessibleName('保存奖励');
    await expect(save).toBeDisabled();
    await expect(dialog).toHaveAttribute('aria-busy', 'true');
    await expect(dialog.getByRole('button', { name: '关闭', exact: true })).toBeDisabled();
    await page.mouse.move(4, 4);
    await expect
      .poll(async () => {
        const after = await save.boundingBox();
        return after
          ? Math.max(Math.abs(after.width - before!.width), Math.abs(after.height - before!.height))
          : Infinity;
      })
      .toBeLessThanOrEqual(1);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeVisible();
    await page.mouse.click(4, 4);
    await expect(dialog).toBeVisible();
    expect(requests).toBe(1);
  } finally {
    release();
  }

  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.reward-card').filter({ hasText: title })).toHaveCount(1);
});

test('toast focus survives pointer leave and dismissing a paused toast does not pause the next one', async ({
  page,
}) => {
  await login(page, 'parent');
  const first = await addReward(page, '专注提示的野餐 ' + randomUUID());
  await first.getByRole('button', { name: '保存奖励', exact: true }).click();
  await expect(first).toHaveCount(0);
  const toast = page.getByRole('status').filter({ hasText: '奖励已保存' });
  const close = toast.getByRole('button', { name: '关闭提示', exact: true });
  await expect(toast).toBeVisible();
  await close.focus();
  await close.hover();
  await page.mouse.move(4, 4);
  await expect(close).toBeFocused();
  // Use real time: the browser animation timeline is independent of fake timers.
  await page.waitForTimeout(7000);
  await expect(toast).toBeVisible();
  await expect(close).toBeFocused();

  // Dismiss while focused and hovered, then create another notice without touching it.
  await close.click();
  await expect(toast).toHaveCount(0);
  await page.mouse.move(4, 4);
  const second = await addReward(page, '自动消失提示的野餐 ' + randomUUID());
  await second.getByRole('button', { name: '保存奖励', exact: true }).click();
  await expect(second).toHaveCount(0);
  await expect(toast).toBeVisible();
  await page.getByRole('button', { name: '添加奖励', exact: true }).focus();
  await page.mouse.move(4, 4);
  await expect(toast).toHaveCount(0, { timeout: 8000 });
});

test('subject selection exposes its state without shifting controls across skins and widths', async ({
  page,
}) => {
  await login(page);
  const group = page.getByRole('group', { name: '科目分类', exact: true });
  const labels = ['语文', '数学', '英语', '体育', '其他'];

  for (const { width, height, skin } of [
    { width: 390, height: 844, skin: 'dino' },
    { width: 1120, height: 736, skin: 'princess' },
  ]) {
    await page.setViewportSize({ width, height });
    if (skin === 'princess') {
      await page.getByRole('button', { name: '换装', exact: true }).click();
      await page.getByRole('button', { name: /公主花园/ }).click();
    }
    await expect(page.locator('html')).toHaveAttribute('data-skin', skin);
    await expect(group.getByRole('button')).toHaveCount(labels.length);
    const before = await buttonRects(group);
    for (const selected of ['英语', '数学', '语文']) {
      await group.getByRole('button', { name: selected, exact: true }).click();
      for (const label of labels)
        await expect(group.getByRole('button', { name: label, exact: true })).toHaveAttribute(
          'aria-pressed',
          String(label === selected),
        );
      await expect(group.locator('.selection-indicator')).toHaveCount(1);
      await expect
        .poll(async () => {
          const after = await buttonRects(group);
          return Math.max(
            ...after.flatMap((rect, index) =>
              (['x', 'y', 'width', 'height'] as const).map((key) =>
                Math.abs(rect[key] - before[index][key]),
              ),
            ),
          );
        })
        .toBeLessThanOrEqual(1);
    }
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
    ).toBeTruthy();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect.poll(() => hasIdentityTransform(group.locator('.selection-indicator'))).toBe(true);
    await page.mouse.move(4, 4);
    const artifact = skin === 'dino' ? 'fluid-dino-mobile' : 'fluid-princess-tablet';
    await page.screenshot({ path: `artifacts/${artifact}.png`, fullPage: true });
    await page.getByRole('button', { name: '设置', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '账号与设置', exact: true });
    await expect.poll(() => hasIdentityTransform(dialog)).toBe(true);
    await page.screenshot({ path: `artifacts/${artifact}-dialog.png` });
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
  }
});

test('date selection keeps a Monday-to-Sunday week and offers a return to today', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  const input = page.getByLabel('任务日期', { exact: true });
  const today = await input.inputValue();
  const group = page.getByRole('group', { name: '选择任务日期', exact: true });
  const days = group.locator('button[aria-pressed]');
  await expect(days).toHaveCount(7);
  await expect(group.locator('button[aria-current="date"]')).toHaveCount(1);
  await expect(group.locator('button[aria-current="date"]')).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
  const monday = new Date(today + 'T12:00:00Z');
  monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
  for (let index = 0; index < weekdays.length; index++) {
    const date = new Date(monday);
    date.setUTCDate(date.getUTCDate() + index);
    await expect(days.nth(index)).toHaveAttribute(
      'aria-label',
      new RegExp(`${date.getUTCMonth() + 1}月${date.getUTCDate()}日.*星期${weekdays[index]}`),
    );
  }

  await group.getByRole('button', { name: '前一周', exact: true }).click();
  await expect(group.locator('button[aria-current="date"]')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '回到今天', exact: true })).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
  ).toBeTruthy();
  const previousWeek = await days.evaluateAll((buttons) =>
    buttons.map((button) => button.getAttribute('aria-label')),
  );
  await days.last().click();
  await expect(days.last()).toHaveAttribute('aria-pressed', 'true');
  await expect(group.locator('button[aria-pressed="true"]')).toHaveCount(1);
  expect(
    await days.evaluateAll((buttons) => buttons.map((button) => button.getAttribute('aria-label'))),
  ).toEqual(previousWeek);
  const sunday = new Date(monday);
  sunday.setUTCDate(sunday.getUTCDate() - 1);
  await expect(input).toHaveValue(sunday.toISOString().slice(0, 10));
  await page.getByRole('button', { name: '回到今天', exact: true }).click();
  await expect(input).toHaveValue(today);
  await expect(group.locator('button[aria-current="date"]')).toHaveAttribute(
    'aria-pressed',
    'true',
  );
});

test('reduced motion leaves selection and dialog content at their resting positions', async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  const group = page.getByRole('group', { name: '科目分类', exact: true });
  await group.getByRole('button', { name: '英语', exact: true }).click();
  await expect(group.getByRole('button', { name: '英语', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect.poll(() => hasIdentityTransform(group)).toBe(true);
  await expect.poll(() => hasIdentityTransform(group.locator('.selection-indicator'))).toBe(true);
  await page.getByRole('button', { name: '设置', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '账号与设置', exact: true });
  await expect(dialog).toBeVisible();
  await expect.poll(() => hasIdentityTransform(dialog)).toBe(true);
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
});
