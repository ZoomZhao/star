import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { addDays, bootstrap, get, insert, materialize, openDb, today, uid } from '../server/db.mjs';
import { snapshot } from '../server/backup.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const plain = (value) => JSON.parse(JSON.stringify(value));
function setup(t) {
  const directory = mkdtempSync(join(tmpdir(), 'star-seed-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const databasePath = join(directory, 'star.sqlite');
  const backupDir = join(directory, 'backups');
  return {
    directory,
    databasePath,
    backupDir,
    execute(script, extraEnv = {}) {
      return spawnSync(process.execPath, [script], {
        cwd: root,
        encoding: 'utf8',
        env: { ...process.env, DATABASE_PATH: databasePath, BACKUP_DIR: backupDir, ...extraEnv },
      });
    },
  };
}

test('retired full-catalog upgrade refuses before opening any database', (t) => {
  const s = setup(t);
  const result = s.execute('server/seed-subjects.mjs');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /整套默认模板升级已停用/);
  assert.equal(existsSync(s.databasePath), false);
});

test('explicit homework upgrade backs up first, adds only three rules and preserves custom history on rerun', (t) => {
  const s = setup(t);
  let db = openDb(s.databasePath);
  bootstrap(db, { adminPassword: 'seed-fixture-password', demo: true });
  db.exec("DELETE FROM rules WHERE title='课内作业'");
  const child = get(db, "SELECT id FROM users WHERE role='child'");
  const rule = get(db, 'SELECT * FROM rules WHERE child_id=? LIMIT 1', child.id);
  db.prepare('UPDATE rules SET title=?,daily_limit=? WHERE id=?').run(
    '自定义十次任务',
    10,
    rule.id,
  );
  materialize(db, child.id, today());
  materialize(db, child.id, addDays(today(), 1));
  insert(db, 'rules', {
    ...rule,
    id: uid(),
    version: 2,
    title: '停用的自定义任务',
    description: '仍保留这个版本',
    stars: 8,
    daily_limit: 10,
    enabled: 0,
    effective_from: addDays(today(), 1),
  });
  const before = plain(snapshot(db));
  db.close();
  const first = s.execute('server/seed-homework.mjs');
  assert.equal(first.status, 0, first.stderr);
  assert.equal(readdirSync(s.backupDir).length, 1);
  const saved = JSON.parse(readFileSync(join(s.backupDir, readdirSync(s.backupDir)[0]), 'utf8'));
  assert.deepEqual(saved.data, before.data);
  db = openDb(s.databasePath);
  const after = plain(snapshot(db));
  db.close();
  for (const [table, rows] of Object.entries(before.data)) {
    if (table === 'rules') {
      for (const row of rows)
        assert.deepEqual(
          after.data.rules.find((r) => r.id === row.id),
          row,
        );
    } else assert.deepEqual(after.data[table], rows, table);
  }
  const added = after.data.rules.filter((r) => !before.data.rules.some((old) => old.id === r.id));
  assert.equal(added.length, 3);
  assert.ok(added.every((r) => r.title === '课内作业'));
  assert.deepEqual(added.map((r) => r.subject).sort(), ['chinese', 'english', 'math']);
  const again = s.execute('server/seed-homework.mjs');
  assert.equal(again.status, 0, again.stderr);
  db = openDb(s.databasePath);
  assert.deepEqual(plain(snapshot(db)).data, after.data);
  db.close();
  writeFileSync(join(s.directory, 'blocked-backup'), 'not a directory');
  const failed = s.execute('server/seed-homework.mjs', {
    BACKUP_DIR: join(s.directory, 'blocked-backup'),
  });
  assert.equal(failed.status, 1);
  db = openDb(s.databasePath);
  assert.deepEqual(plain(snapshot(db)).data, after.data);
  db.close();
});
