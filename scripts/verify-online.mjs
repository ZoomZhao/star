import { chromium } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
const config = parseEnv(readFileSync('.env', 'utf8')),
  origin = 'https://' + config.DOMAIN;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } }),
    page = await context.newPage();
  await page.goto(origin);
  await page.getByLabel('账号', { exact: true }).fill(config.ADMIN_USERNAME || 'admin');
  await page.getByLabel('密码', { exact: true }).fill(config.ADMIN_PASSWORD);
  await page.getByRole('button', { name: '出发，去星星岛' }).click();
  await page.getByRole('button', { name: '下载完整备份', exact: true }).waitFor();
  const reopened = await context.newPage();
  await reopened.goto(origin);
  await reopened.getByRole('button', { name: '下载完整备份', exact: true }).waitFor();
  await reopened.close();
  await page.screenshot({
    path: 'docs/screenshots/production-admin-subject-upgrade.png',
    fullPage: true,
  });
  // Read-only application requests use the authenticated administrator session.
  const result = await page.evaluate(async () => {
    const headers = { Authorization: 'Bearer ' + localStorage.getItem('star-token') };
    const get = async (path) => {
      const r = await fetch(path, { headers });
      if (!r.ok) throw Error('API ' + r.status);
      return r.json();
    };
    const children = await get('/api/children'),
      presets = await get('/api/task-templates');
    const dashboards = [];
    for (const c of children) {
      const d = await get('/api/children/' + c.id + '/dashboard');
      dashboards.push({
        tasks: d.tasks.length,
        subjects: [...new Set(d.tasks.map((t) => t.subject))].sort(),
        rules: d.rules.length,
      });
    }
    return { presetCount: presets.length, dashboards };
  });
  if (result.presetCount !== 10 || result.dashboards.some((d) => d.subjects.length !== 5))
    throw Error('科目模板检查失败');
  for (const key of ['chinese', 'math', 'english', 'sports', 'other']) {
    const r = await context.request.get(origin + '/assets/subjects/' + key + '.webp');
    if (!r.ok()) throw Error('科目图片缺失');
  }
  console.log(
    'Production HTTPS verified: persistent admin login, five category images and ten presets. ' +
      JSON.stringify(result),
  );
  console.log(
    'Existing parent/child passwords were not changed; UI flows are tested with isolated fixture accounts.',
  );
} finally {
  await browser.close();
}
