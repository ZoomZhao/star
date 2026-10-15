import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

for (const kind of ['rules', 'rewards', 'families']) {
  test(`${kind} creation survives a committed request with a lost response and browser reload`, async ({
    page,
  }) => {
    await page.goto('/');
    await page.getByRole('button', { name: '管理员', exact: true }).click();
    await expect(page.getByRole('heading', { name: '家庭与数据管理', exact: true })).toBeVisible();
    const childId = await page.evaluate(async () => {
      const { api } = await import('/src/api.ts');
      return (await api<{ id: string }[]>('/api/children'))[0].id;
    });
    const path = kind === 'families' ? '/api/admin/families' : `/api/children/${childId}/${kind}`;
    const title = '创建重试 ' + randomUUID();
    const body =
      kind === 'families'
        ? { name: title }
        : kind === 'rules'
          ? { title, stars: 3, daily_limit: 10, schedule: 'daily' }
          : { title, cost: 7 };
    const keys: string[] = [];
    let savedId = '';
    await page.route('**' + path, async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      keys.push(route.request().headers()['idempotency-key']);
      const response = await route.fetch();
      expect(response.status()).toBe(201);
      const row = await response.json();
      if (keys.length === 1) {
        savedId = row.id;
        await route.fulfill({ status: 502, json: { error: '结果暂时无法确认' } });
      } else await route.fulfill({ response });
    });
    const create = () =>
      page.evaluate(
        async ({ path, body }) => {
          const { api, requestId } = await import('/src/api.ts');
          try {
            return await api<{ id: string }>(path, 'POST', body, requestId());
          } catch (error) {
            return { error: (error as Error).message };
          }
        },
        { path, body },
      );
    expect(await create()).toEqual({ error: '结果暂时无法确认' });
    await page.reload();
    await expect(page.getByRole('heading', { name: '家庭与数据管理', exact: true })).toBeVisible();
    expect(await create()).toMatchObject({ id: savedId });
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
    const count = await page.evaluate(
      async ({ kind, title }) => {
        const { api } = await import('/src/api.ts');
        const backup = await api<{ data: Record<string, { title?: string; name?: string }[]> }>(
          '/api/admin/backup',
        );
        return backup.data[kind].filter((row) => (row.title || row.name) === title).length;
      },
      { kind, title },
    );
    expect(count).toBe(1);
  });
}
