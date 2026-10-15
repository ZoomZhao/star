import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { backupTables, snapshot } from '../server/backup.mjs';
import { bootstrap, openDb } from '../server/db.mjs';
import {
  compareBackups,
  downloadBackup,
  readBackup,
  verifyBackups,
  withProductionBackup,
} from '../scripts/production-data.mjs';

const fixture = () => ({
  format: 'star-explorer',
  version: 1,
  exported_at: '2026-10-15T00:00:00.000Z',
  data: Object.fromEntries(
    backupTables.map((table) => [
      table,
      table === 'task_order'
        ? [{ child_id: 'child', rule_key: 'rule', position: 0 }]
        : [
            {
              id: table + '-1',
              ...(table === 'rules' ? { daily_limit: 10, title: '自定义任务', enabled: 0 } : {}),
            },
          ],
    ]),
  ),
});
const connection = { origin: 'https://fixture.invalid', username: 'fixture', password: 'fixture' };

function validFixture() {
  const db = openDb(':memory:');
  try {
    bootstrap(db, { adminPassword: 'deploy-fixture-password', demo: true });
    db.exec('UPDATE rules SET daily_limit=10 WHERE rowid=(SELECT MIN(rowid) FROM rules)');
    return JSON.parse(JSON.stringify(snapshot(db)));
  } finally {
    db.close();
  }
}

function harness(t, backup = validFixture()) {
  const directory = mkdtempSync(join(tmpdir(), 'star-deploy-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const events = [];
  const state = { backup, loginStatus: 200 };
  const options = {
    directory,
    log: () => {},
    fetchImpl: async (url) => {
      const path = new URL(url).pathname;
      events.push(path);
      if (path === '/api/login')
        return Response.json({ token: 'fixture-token' }, { status: state.loginStatus });
      if (path === '/api/admin/backup') return Response.json(state.backup);
      if (path === '/api/logout' || path === '/api/health') return Response.json({ ok: true });
      throw new Error('Unexpected request: ' + path);
    },
  };
  return { state, options, events, directory };
}

test('production comparison checks every collection and every existing field by identity', () => {
  const before = fixture(),
    after = structuredClone(before);
  after.exported_at = '2026-10-16T00:00:00.000Z';
  for (const table of backupTables) after.data[table] = after.data[table].reverse();
  const messages = [];
  verifyBackups(before, after, (line) => messages.push(line));
  assert.ok(messages.some((line) => line.includes('10 次规则：1 条完整保留')));
  for (const table of backupTables) {
    const changed = structuredClone(before);
    changed.data[table][0].new_field = 'not an approved migration';
    assert.deepEqual(compareBackups(before, changed).find((r) => r.table === table).changed, [
      table === 'task_order' ? 'child:rule' : table + '-1',
    ]);
    assert.throws(() => verifyBackups(before, changed), /不要自动恢复旧备份/);
  }
  const lost = structuredClone(before);
  lost.data.rules = [];
  assert.throws(() => verifyBackups(before, lost), /rules: 缺少 rules-1/);
  const added = structuredClone(before);
  added.data.tasks.push({ id: 'new-task' });
  assert.throws(() => verifyBackups(before, added), /新增 new-task/);
});

test('incomplete, duplicate and unknown collections cannot pass a production comparison', () => {
  const before = fixture();
  for (const mutate of [
    (b) => {
      delete b.data.ledger;
    },
    (b) => {
      b.data.tasks.push(b.data.tasks[0]);
    },
    (b) => {
      b.data.new_table = [];
    },
    (b) => {
      b.data.users[0].id = '';
    },
    (b) => {
      b.version = 2;
    },
  ]) {
    const invalid = structuredClone(before);
    mutate(invalid);
    assert.throws(() => compareBackups(before, invalid));
  }
});

test('deployment saves a private full backup before changes and verifies another after health', async (t) => {
  const h = harness(t);
  const result = await withProductionBackup(
    connection,
    async () => {
      assert.equal(readdirSync(h.directory).length, 1);
      assert.deepEqual(readBackup(join(h.directory, readdirSync(h.directory)[0])), h.state.backup);
      h.events.push('deploy');
    },
    h.options,
  );
  assert.deepEqual(h.events, [
    '/api/login',
    '/api/admin/backup',
    '/api/logout',
    'deploy',
    '/api/health',
    '/api/login',
    '/api/admin/backup',
    '/api/logout',
  ]);
  assert.notEqual(result.beforePath, result.afterPath);
  assert.equal(statSync(result.beforePath).mode & 0o777, 0o600);
  assert.equal(statSync(result.afterPath).mode & 0o777, 0o600);
});

test('failed login or incomplete backup blocks deployment before any change', async (t) => {
  const h = harness(t);
  let deployed = false;
  const deploy = async () => {
    deployed = true;
  };
  h.state.loginStatus = 401;
  await assert.rejects(withProductionBackup(connection, deploy, h.options), /管理员登录失败/);
  h.state.loginStatus = 200;
  delete h.state.backup.data.ledger;
  await assert.rejects(withProductionBackup(connection, deploy, h.options), /缺少 ledger/);
  assert.equal(deployed, false);
  assert.equal(readdirSync(h.directory).length, 0);
  assert.equal(h.events.at(-1), '/api/logout');
});

test('post-deploy differences keep both backups and never restore over concurrent edits', async (t) => {
  const h = harness(t);
  await assert.rejects(
    withProductionBackup(
      connection,
      async () => {
        h.state.backup.data.rules[0].daily_limit = 2;
      },
      h.options,
    ),
    /请先判断是否为用户并发操作/,
  );
  const files = readdirSync(h.directory);
  assert.equal(files.length, 2);
  assert.equal(
    readBackup(
      join(
        h.directory,
        files.find((f) => f.startsWith('before')),
      ),
    ).data.rules[0].daily_limit,
    10,
  );
  assert.equal(h.state.backup.data.rules[0].daily_limit, 2);
  assert.ok(!h.events.some((path) => path.includes('restore')));
});

test('a failed deployment retains the saved backup', async (t) => {
  const h = harness(t);
  await assert.rejects(
    withProductionBackup(
      connection,
      async () => {
        throw new Error('fixture deploy failed');
      },
      h.options,
    ),
    /fixture deploy failed/,
  );
  assert.equal(readdirSync(h.directory).length, 1);
});

test('a structurally complete but unrestorable backup blocks deployment', async (t) => {
  const h = harness(t, fixture());
  let deployed = false;
  await assert.rejects(
    withProductionBackup(
      connection,
      async () => {
        deployed = true;
      },
      h.options,
    ),
    /未通过完整恢复校验/,
  );
  assert.equal(deployed, false);
  assert.equal(readdirSync(h.directory).length, 0);
  assert.equal(h.events.at(-1), '/api/logout');
});

test('backup download closes its session even if backup validation fails', async (t) => {
  const h = harness(t, { format: 'wrong' });
  await assert.rejects(downloadBackup(connection, 'test', h.options), /完整备份/);
  assert.deepEqual(h.events, ['/api/login', '/api/admin/backup', '/api/logout']);
});
