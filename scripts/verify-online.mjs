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
    const presets = await get('/api/task-templates');
    // Dashboard GET creates daily snapshots. Inspect the backup instead, and never
    // assume an existing family's rules still match the default template catalog.
    const backup = await get('/api/admin/backup');
    if (backup.format !== 'star-explorer') throw Error('完整备份格式错误');
    return {
      presetCount: presets.length,
      subjects: [...new Set(presets.map((preset) => preset.subject))].sort(),
      children: backup.data.users.filter((user) => user.role === 'child').length,
      rules: backup.data.rules.length,
      snapshots: backup.data.tasks.length,
      customTenCaps: backup.data.rules.filter((rule) => rule.daily_limit === 10).length,
    };
  });
  if (result.presetCount !== 13 || result.subjects.length !== 5) throw Error('科目模板检查失败');
  for (const key of ['chinese', 'math', 'english', 'sports', 'other']) {
    const r = await context.request.get(origin + '/assets/subjects/' + key + '.webp');
    if (!r.ok()) throw Error('科目图片缺失');
  }
  console.log(
    'Production HTTPS verified: persistent admin login, five category images and thirteen presets. ' +
      JSON.stringify(result),
  );
  console.log(
    'Existing parent/child passwords were not changed; UI flows are tested with isolated fixture accounts.',
  );
} finally {
  await browser.close();
}
