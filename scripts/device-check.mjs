import { chromium } from '@playwright/test';
const browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
const pages = browser.contexts().flatMap((c) => c.pages());
const page = pages.find((p) => p.url().startsWith('http://localhost'));
if (!page) throw new Error('没有找到星星探险家 WebView');
console.log(
  await page.evaluate(() => ({
    width: innerWidth,
    height: innerHeight,
    userAgent: navigator.userAgent,
    url: location.href,
  })),
);
page.on('pageerror', (e) => console.log('PAGE ERROR:', e.message));
if (await page.getByRole('button', { name: '小朋友', exact: true }).count())
  await page.getByRole('button', { name: '小朋友', exact: true }).click();
await page.getByRole('heading', { name: '星星探险家', exact: true }).waitFor();
await page.screenshot({ path: 'docs/screenshots/tablet-webview.png' });
await page.getByRole('button', { name: '和小恐龙打招呼' }).click();
await page.getByRole('status').first().waitFor();
await page.getByRole('button', { name: '星星口袋', exact: true }).click();
await page.getByText('可用星星', { exact: true }).waitFor();
await page.screenshot({ path: 'docs/screenshots/tablet-device-wallet.png' });
await page.getByRole('button', { name: '今日任务', exact: true }).click();
await page.locator('.task-title').first().click();
await page.getByRole('dialog').waitFor();
console.log(
  'Native checks passed: child login, dinosaur interaction, wallet navigation, task dialog.',
);
await page.getByRole('button', { name: '关闭', exact: true }).click();
await browser.close();
