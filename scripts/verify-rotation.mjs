import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
const browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { noDefaults: true });
try {
  const page = browser
    .contexts()
    .flatMap((c) => c.pages())
    .find((p) => p.url().startsWith('http://localhost'));
  await page.waitForFunction(() => innerWidth < innerHeight);
  console.log(
    'Portrait viewport',
    await page.evaluate(() => ({
      width: innerWidth,
      height: innerHeight,
      overflow: document.documentElement.scrollWidth > innerWidth,
    })),
  );
  await page.screenshot({ path: 'docs/screenshots/tablet-device-portrait.png' });
} finally {
  execFileSync('adb', ['shell', 'settings', 'put', 'system', 'user_rotation', '1']);
  execFileSync('adb', ['shell', 'settings', 'put', 'system', 'accelerometer_rotation', '1']);
  await browser.close();
}
