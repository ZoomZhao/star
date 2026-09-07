import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';
mkdirSync('docs/screenshots', { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({
  viewport: { width: 1120, height: 736 },
  deviceScaleFactor: 1,
});
page.on('pageerror', (e) => console.error(e));
await page.goto('http://127.0.0.1:5173');
await page.getByRole('button', { name: '小朋友', exact: true }).click();
await page.getByRole('heading', { name: '星星探险家', exact: true }).waitFor();
await page.screenshot({ path: 'docs/screenshots/tablet-landscape.png', fullPage: true });
await page.setViewportSize({ width: 390, height: 844 });
await page.screenshot({ path: 'docs/screenshots/mobile-daily.png', fullPage: true });
await page.getByRole('button', { name: '星星口袋', exact: true }).click();
await page.screenshot({ path: 'docs/screenshots/mobile-wallet.png', fullPage: true });
await browser.close();
