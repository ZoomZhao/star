import { chromium } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { execFileSync } from 'node:child_process';
const c = parseEnv(readFileSync('.env', 'utf8')),
  origin = 'https://' + c.DOMAIN;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await page.goto(origin);
  await page.getByLabel('账号', { exact: true }).fill('parent');
  await page.getByLabel('密码', { exact: true }).fill(c.PARENT_PASSWORD);
  await page.getByRole('button', { name: '出发，去星星岛' }).click();
  await page.locator('.task-card').first().waitFor();
  if ((await page.locator('.task-card').count()) !== 4) throw new Error('服务器任务数量不正确');
  await page.getByRole('button', { name: '任务规则', exact: true }).click();
  await page.locator('.rule-card').first().waitFor();
  await page.screenshot({ path: 'docs/screenshots/production-mobile-parent.png', fullPage: true });
  console.log('Public HTTPS Web verified: valid certificate, parent login, 4 live rules.');
} finally {
  await browser.close();
}
const pid = execFileSync('adb', ['shell', 'pidof', 'com.starexplorer.app'], {
  encoding: 'utf8',
}).trim();
execFileSync('adb', ['forward', 'tcp:9223', 'localabstract:webview_devtools_remote_' + pid]);
const native = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
try {
  const page = native
    .contexts()
    .flatMap((c) => c.pages())
    .find((p) => /^https?:\/\/localhost/.test(p.url()));
  if (!page) throw new Error('App WebView 未找到');
  await page.waitForFunction(
    () => document.querySelector('.login') || document.querySelector('.app'),
  );
  if (await page.locator('.login').count()) {
    await page.getByLabel('账号', { exact: true }).fill('child');
    await page.getByLabel('密码', { exact: true }).fill(c.CHILD_PASSWORD);
    await page.getByRole('button', { name: '出发，去星星岛' }).click();
  }
  await page.locator('.task-card').first().waitFor();
  if ((await page.locator('.task-card').count()) !== 4) throw new Error('平板任务数量不正确');
  execFileSync('adb', ['reverse', '--remove', 'tcp:3001']);
  await page.reload();
  await page.locator('.task-card').first().waitFor();
  await page.screenshot({ path: 'docs/screenshots/production-tablet-landscape.png' });
  await page.getByRole('button', { name: '星星口袋', exact: true }).click();
  await page.getByText('可用星星', { exact: true }).waitFor();
  await page.getByRole('button', { name: '今日任务', exact: true }).click();
  console.log(
    'Android verified without USB network forwarding: live child login, 4 tasks, wallet.',
  );
} finally {
  await native.close();
}
