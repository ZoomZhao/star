import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { backupTables, restore, snapshot, validateBackup } from '../server/backup.mjs';
import {
  all,
  createUser,
  get,
  insert,
  materialize,
  now,
  openDb,
  run,
  today,
  tx,
  uid,
} from '../server/db.mjs';

const plain = (value) => JSON.parse(JSON.stringify(value));

function setup(t, { file = false, secondChild = false } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'star-backup-test-'));
  const databasePath = join(dir, 'star.sqlite');
  const db = openDb(file ? databasePath : ':memory:');
  const connections = [db];
  t.after(() => {
    for (const connection of connections.reverse()) connection.close();
    rmSync(dir, { recursive: true, force: true });
  });
  const admin = createUser(db, {
    username: 'backup-admin',
    name: '管理员',
    role: 'admin',
    password: uid(),
  });
  const familyId = uid();
  insert(db, 'families', { id: familyId, name: '备份测试家庭', created_at: now() });
  const parent = createUser(db, {
    family_id: familyId,
    username: 'backup-parent',
    name: '家长',
    role: 'parent',
    password: uid(),
  });
  const child = createUser(db, {
    family_id: familyId,
    username: 'backup-child',
    name: '孩子',
    role: 'child',
    password: uid(),
  });
  let otherChild;
  if (secondChild) {
    const otherFamily = uid();
    insert(db, 'families', { id: otherFamily, name: '另一个家庭', created_at: now() });
    otherChild = createUser(db, {
      family_id: otherFamily,
      username: 'other-child',
      name: '另一个孩子',
      role: 'child',
      password: uid(),
    });
  }
  materialize(db, child.id, today());
  insert(db, 'ledger', {
    id: uid(),
    child_id: child.id,
    amount: 7,
    kind: 'bonus',
    title: '已有奖励',
    note: '应完整保留',
    date: today(),
    created_at: now(),
    actor_id: parent.id,
    submission_id: null,
    redemption_id: null,
    reversal_of: null,
    request_key: uid(),
  });
  for (const user of [admin, parent])
    insert(db, 'sessions', {
      token_hash: uid(),
      user_id: user.id,
      expires_at: new Date(Date.now() + 86400000).toISOString(),
    });
  const writer = () => {
    assert.ok(file);
    const connection = openDb(databasePath);
    connections.push(connection);
    return connection;
  };
  return { db, dir, admin, parent, child, otherChild, writer };
}

// Coordinate an external connection at a table boundary without changing production code.
function afterTableRead(db, table, callback) {
  let called = false;
  return {
    get isTransaction() {
      return db.isTransaction;
    },
    exec(sql) {
      return db.exec(sql);
    },
    prepare(sql) {
      const statement = db.prepare(sql);
      if (sql !== `SELECT * FROM ${table}`) return statement;
      return {
        all(...args) {
          const result = statement.all(...args);
          if (!called) {
            called = true;
            callback();
          }
          return result;
        },
      };
    },
  };
}

test('snapshot remains consistent while another connection inserts related records', (t) => {
  const s = setup(t, { file: true });
  const writer = s.writer();
  const baseline = plain(snapshot(s.db));
  const familyId = uid();
  const hooked = afterTableRead(s.db, 'families', () => {
    tx(writer, () => {
      insert(writer, 'families', { id: familyId, name: '同时新增的家庭', created_at: now() });
      createUser(writer, {
        family_id: familyId,
        username: 'concurrent-parent',
        name: '同时新增的家长',
        role: 'parent',
        password: uid(),
      });
    });
  });
  const exported = plain(snapshot(hooked));
  assert.deepEqual(exported.data, baseline.data);
  assert.equal(s.db.isTransaction, false);
  assert.ok(get(s.db, 'SELECT id FROM users WHERE family_id=?', familyId));
  const saved = restore(s.db, exported, join(s.dir, 'backups'));
  assert.deepEqual(plain(snapshot(s.db)).data, exported.data);
  const previous = JSON.parse(readFileSync(saved, 'utf8'));
  assert.ok(previous.data.families.some((family) => family.id === familyId));
  assert.ok(previous.data.users.some((user) => user.family_id === familyId));
  assert.deepEqual(Object.keys(exported.data), backupTables);
  assert.ok(Object.isFrozen(backupTables));
});

test('snapshot uses an existing transaction without committing the caller changes', (t) => {
  const s = setup(t);
  const baseline = plain(snapshot(s.db));
  s.db.exec('BEGIN');
  run(s.db, 'UPDATE users SET name=? WHERE id=?', '尚未提交的称呼', s.child.id);
  const exported = snapshot(s.db);
  assert.equal(exported.data.users.find((user) => user.id === s.child.id).name, '尚未提交的称呼');
  assert.equal(s.db.isTransaction, true);
  s.db.exec('ROLLBACK');
  assert.deepEqual(plain(snapshot(s.db)).data, baseline.data);
});

test('snapshot releases its own transaction when a table read fails', (t) => {
  const s = setup(t);
  const baseline = plain(snapshot(s.db));
  const failure = new Error('模拟读取失败');
  const hooked = afterTableRead(s.db, 'families', () => {
    throw failure;
  });
  assert.throws(
    () => snapshot(hooked),
    (error) => error === failure,
  );
  assert.equal(s.db.isTransaction, false);
  assert.deepEqual(plain(snapshot(s.db)).data, baseline.data);
});

test('standalone validation returns normalized data without changing input, live data or files', (t) => {
  const s = setup(t);
  const task = get(s.db, 'SELECT * FROM tasks LIMIT 1');
  insert(s.db, 'submissions', {
    id: uid(),
    task_id: task.id,
    child_id: task.child_id,
    status: 'pending',
    note: '',
    review_note: '',
    reviewed_by: null,
    created_at: now(),
    reviewed_at: null,
    request_key: uid(),
  });
  const baseline = plain(snapshot(s.db));
  const sessions = plain(all(s.db, 'SELECT * FROM sessions'));
  const files = readdirSync(s.dir);
  const incoming = structuredClone(baseline);
  delete incoming.data.task_order;
  delete incoming.data.rules[0].subject;
  delete incoming.data.tasks[0].subject;
  for (const field of ['bonus', 'deduction', 'quantity', 'unit_stars'])
    delete incoming.data.submissions[0][field];
  const originalInput = structuredClone(incoming);
  const validated = validateBackup(incoming);
  assert.deepEqual(validated.data.task_order, []);
  assert.equal(validated.data.rules[0].subject, 'other');
  assert.equal(validated.data.tasks[0].subject, 'other');
  assert.deepEqual(validated.data.submissions[0], baseline.data.submissions[0]);
  assert.deepEqual(incoming, originalInput);
  assert.deepEqual(plain(snapshot(s.db)).data, baseline.data);
  assert.deepEqual(plain(all(s.db, 'SELECT * FROM sessions')), sessions);
  assert.deepEqual(readdirSync(s.dir), files);
});

test('standalone validation rejects malformed and inconsistent backups without side effects', (t) => {
  const s = setup(t);
  const baseline = plain(snapshot(s.db));
  const sessions = plain(all(s.db, 'SELECT * FROM sessions'));
  const files = readdirSync(s.dir);
  for (const change of [
    (backup) => {
      backup.data.users[0] = { id: backup.data.users[0].id };
    },
    (backup) => {
      backup.data.tasks[0].rule_key = uid();
    },
  ]) {
    const incoming = structuredClone(baseline);
    change(incoming);
    const originalInput = structuredClone(incoming);
    assert.throws(() => validateBackup(incoming));
    assert.deepEqual(incoming, originalInput);
    assert.deepEqual(plain(snapshot(s.db)).data, baseline.data);
    assert.deepEqual(plain(all(s.db, 'SELECT * FROM sessions')), sessions);
    assert.deepEqual(readdirSync(s.dir), files);
  }
});

test('restore rejects cross-child rule keys and broken snapshot sources without mutation', (t) => {
  const s = setup(t, { secondChild: true });
  const baseline = plain(snapshot(s.db));
  const sessions = plain(all(s.db, 'SELECT * FROM sessions'));
  const original = baseline.data.rules.find((rule) => rule.child_id === s.child.id);
  const foreign = baseline.data.rules.find((rule) => rule.child_id === s.otherChild.id);
  const changes = {
    'cross-child-rule': (backup) => {
      backup.data.rules.push({ ...original, id: uid(), child_id: s.otherChild.id, version: 2 });
    },
    'missing-rule': (backup) => {
      backup.data.tasks[0].rule_key = uid();
    },
    'missing-version': (backup) => {
      backup.data.tasks[0].rule_version = 999;
    },
    'foreign-rule': (backup) => {
      backup.data.tasks[0].rule_key = foreign.rule_key;
      backup.data.tasks[0].rule_version = foreign.version;
    },
  };
  for (const [name, change] of Object.entries(changes)) {
    const bad = structuredClone(baseline);
    change(bad);
    const backupDir = join(s.dir, name);
    assert.throws(() => restore(s.db, bad, backupDir), { status: 400 }, name);
    assert.deepEqual(plain(snapshot(s.db)).data, baseline.data, name);
    assert.deepEqual(plain(all(s.db, 'SELECT * FROM sessions')), sessions, name);
    assert.equal(existsSync(backupDir), false, name);
  }
});

test('restore preserves daily overrides and saves all previous data before revoking sessions', (t) => {
  const s = setup(t);
  const before = plain(snapshot(s.db));
  const incoming = structuredClone(before);
  const overridden = incoming.data.tasks[0];
  Object.assign(overridden, {
    title: '自定义单日任务',
    description: '只覆盖这一天',
    icon: 'heart',
    subject: 'sports',
    stars: 8,
    daily_limit: 10,
  });
  const saved = restore(s.db, incoming, join(s.dir, 'backups'));
  assert.deepEqual(JSON.parse(readFileSync(saved, 'utf8')).data, before.data);
  assert.deepEqual(plain(snapshot(s.db)).data, incoming.data);
  assert.deepEqual(all(s.db, 'SELECT * FROM sessions'), []);
});

test('restore holds the write lock while saving the state it will replace', (t) => {
  const s = setup(t, { file: true });
  const writer = s.writer();
  writer.exec('PRAGMA busy_timeout=1');
  const before = plain(snapshot(s.db));
  const incoming = structuredClone(before);
  incoming.data.users.find((user) => user.id === s.child.id).name = '恢复后的称呼';
  let attempted = false;
  const familyId = uid();
  const hooked = afterTableRead(s.db, 'task_order', () => {
    attempted = true;
    assert.throws(
      () => insert(writer, 'families', { id: familyId, name: '并发写入', created_at: now() }),
      (error) => error.errcode === 5,
    );
  });
  const saved = restore(hooked, incoming, join(s.dir, 'backups'));
  assert.equal(attempted, true);
  assert.deepEqual(JSON.parse(readFileSync(saved, 'utf8')).data, before.data);
  assert.deepEqual(plain(snapshot(s.db)).data, incoming.data);
  assert.equal(get(writer, 'SELECT id FROM families WHERE id=?', familyId), undefined);
});

test('failed pre-restore backup writes preserve data and sessions', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-10-15T04:00:00Z').getTime() });
  const s = setup(t);
  const before = plain(snapshot(s.db));
  const sessions = plain(all(s.db, 'SELECT * FROM sessions'));
  const incoming = structuredClone(before);
  incoming.data.users.find((user) => user.id === s.child.id).name = '不应写入的称呼';
  const backupDir = join(s.dir, 'backups');
  mkdirSync(backupDir);
  const existing = join(backupDir, `before-restore-${Date.now()}.json`);
  writeFileSync(existing, '既有文件');
  assert.throws(() => restore(s.db, incoming, backupDir), { code: 'EEXIST' });
  assert.deepEqual(plain(snapshot(s.db)).data, before.data);
  assert.deepEqual(plain(all(s.db, 'SELECT * FROM sessions')), sessions);
  assert.equal(s.db.isTransaction, false);
  assert.equal(readFileSync(existing, 'utf8'), '既有文件');
});
