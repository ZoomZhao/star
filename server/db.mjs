import templates from '../shared/task-templates.json' with { type: 'json' };
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { randomBytes, scryptSync, timingSafeEqual, randomUUID } from 'node:crypto';

export const uid = () => randomUUID();
export const now = () => new Date().toISOString();
export const today = () =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
export function addDays(date, days) {
  const d = new Date(`${date}T12:00:00+08:00`);
  d.setUTCDate(d.getUTCDate() + days);
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(d);
}
export function hashPassword(password) {
  const salt = randomBytes(16).toString('hex');
  return salt + ':' + scryptSync(password, salt, 64).toString('hex');
}
export function verifyPassword(password, hash) {
  try {
    const [salt, key] = hash.split(':');
    return timingSafeEqual(scryptSync(password, salt, 64), Buffer.from(key, 'hex'));
  } catch {
    return false;
  }
}
export const schema = `
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS families(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, family_id TEXT REFERENCES families(id), username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('admin','parent','child')), password_hash TEXT NOT NULL, active INTEGER NOT NULL CHECK(active IN (0,1)), created_at TEXT NOT NULL, CHECK((role='admin' AND family_id IS NULL) OR (role!='admin' AND family_id IS NOT NULL)));
CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), expires_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS rules(id TEXT PRIMARY KEY, rule_key TEXT NOT NULL, child_id TEXT NOT NULL REFERENCES users(id), title TEXT NOT NULL, description TEXT NOT NULL, icon TEXT NOT NULL, subject TEXT NOT NULL DEFAULT 'other' CHECK(subject IN ('chinese','math','english','sports','other')), stars INTEGER NOT NULL CHECK(stars BETWEEN 1 AND 100), daily_limit INTEGER NOT NULL CHECK(daily_limit BETWEEN 1 AND 20), schedule TEXT NOT NULL CHECK(schedule IN ('daily','weekly','once')), weekdays TEXT NOT NULL, on_date TEXT, effective_from TEXT NOT NULL, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)), version INTEGER NOT NULL CHECK(version>0), created_at TEXT NOT NULL, UNIQUE(rule_key,version));
CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY, child_id TEXT NOT NULL REFERENCES users(id), date TEXT NOT NULL, rule_key TEXT NOT NULL, rule_version INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, icon TEXT NOT NULL, subject TEXT NOT NULL DEFAULT 'other' CHECK(subject IN ('chinese','math','english','sports','other')), stars INTEGER NOT NULL CHECK(stars BETWEEN 1 AND 100), daily_limit INTEGER NOT NULL CHECK(daily_limit BETWEEN 1 AND 20), created_at TEXT NOT NULL, UNIQUE(child_id,date,rule_key));
CREATE TABLE IF NOT EXISTS submissions(id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id), child_id TEXT NOT NULL REFERENCES users(id), status TEXT NOT NULL CHECK(status IN ('pending','approved','rejected')), note TEXT NOT NULL, review_note TEXT NOT NULL, reviewed_by TEXT REFERENCES users(id), created_at TEXT NOT NULL, reviewed_at TEXT, request_key TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS rewards(id TEXT PRIMARY KEY, family_id TEXT NOT NULL REFERENCES families(id), title TEXT NOT NULL, description TEXT NOT NULL, icon TEXT NOT NULL, cost INTEGER NOT NULL CHECK(cost BETWEEN 1 AND 100000), active INTEGER NOT NULL CHECK(active IN (0,1)), created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS redemptions(id TEXT PRIMARY KEY, reward_id TEXT NOT NULL REFERENCES rewards(id), child_id TEXT NOT NULL REFERENCES users(id), title TEXT NOT NULL, icon TEXT NOT NULL, cost INTEGER NOT NULL CHECK(cost>0), status TEXT NOT NULL CHECK(status IN ('pending','approved','rejected')), review_note TEXT NOT NULL, reviewed_by TEXT REFERENCES users(id), created_at TEXT NOT NULL, reviewed_at TEXT, request_key TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS ledger(id TEXT PRIMARY KEY, child_id TEXT NOT NULL REFERENCES users(id), amount INTEGER NOT NULL CHECK(amount!=0), kind TEXT NOT NULL CHECK(kind IN ('task','reward','bonus','spend','deduction','reversal')), title TEXT NOT NULL, note TEXT NOT NULL, date TEXT NOT NULL, created_at TEXT NOT NULL, actor_id TEXT NOT NULL REFERENCES users(id), submission_id TEXT UNIQUE REFERENCES submissions(id), redemption_id TEXT UNIQUE REFERENCES redemptions(id), reversal_of TEXT UNIQUE REFERENCES ledger(id), request_key TEXT UNIQUE NOT NULL);
CREATE INDEX IF NOT EXISTS task_child_date ON tasks(child_id,date);
CREATE INDEX IF NOT EXISTS ledger_child_date ON ledger(child_id,date);
CREATE INDEX IF NOT EXISTS submission_status ON submissions(child_id,status);
PRAGMA user_version=1;
`;
export function openDb(path) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path, { timeout: 5000 });
  db.exec(schema);
  for (const table of ['rules', 'tasks']) {
    if (
      !db
        .prepare(`PRAGMA table_info(${table})`)
        .all()
        .some((c) => c.name === 'subject')
    ) {
      db.exec(
        `ALTER TABLE ${table} ADD COLUMN subject TEXT NOT NULL DEFAULT 'other' CHECK(subject IN ('chinese','math','english','sports','other'))`,
      );
      db.exec(
        `UPDATE ${table} SET subject=CASE WHEN title LIKE '%英文%' OR title LIKE '%英语%' THEN 'english' WHEN icon='leaf' THEN 'sports' ELSE 'other' END`,
      );
    }
  }
  db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;');
  return db;
}
export const all = (db, sql, ...args) => db.prepare(sql).all(...args);
export const get = (db, sql, ...args) => db.prepare(sql).get(...args);
export const run = (db, sql, ...args) => db.prepare(sql).run(...args);
export function tx(db, fn) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
}
export const balance = (db, id) =>
  get(db, 'SELECT COALESCE(SUM(amount),0) AS n FROM ledger WHERE child_id=?', id).n;
export function insert(db, table, row) {
  const keys = Object.keys(row);
  return run(
    db,
    `INSERT INTO ${table} (${keys.join(',')}) VALUES (${keys.map(() => '?').join(',')})`,
    ...keys.map((k) => row[k]),
  );
}
export const publicUser = ({ password_hash, ...u }) => u;
export function createUser(db, { family_id = null, username, name, role, password }) {
  const user = {
    id: uid(),
    family_id,
    username,
    name,
    role,
    password_hash: hashPassword(password),
    active: 1,
    created_at: now(),
  };
  insert(db, 'users', user);
  if (role === 'child') seedTemplates(db, user.id);
  return publicUser(user);
}
export function seedTemplates(db, childId) {
  for (const preset of templates) {
    if (get(db, 'SELECT id FROM rules WHERE child_id=? AND title=?', childId, preset.title))
      continue;
    insert(db, 'rules', {
      ...preset,
      id: uid(),
      rule_key: uid(),
      child_id: childId,
      schedule: 'daily',
      weekdays: '[]',
      on_date: null,
      effective_from: today(),
      enabled: 1,
      version: 1,
      created_at: now(),
    });
  }
}
export function materialize(db, childId, date) {
  const rules = all(
    db,
    `SELECT r.* FROM rules r WHERE child_id=? AND effective_from<=? AND version=(SELECT MAX(version) FROM rules x WHERE x.rule_key=r.rule_key AND x.effective_from<=?)`,
    childId,
    date,
    date,
  );
  const weekday = new Date(date + 'T12:00:00+08:00').getUTCDay();
  for (const r of rules) {
    if (
      !r.enabled ||
      (r.schedule === 'once' && r.on_date !== date) ||
      (r.schedule === 'weekly' && !JSON.parse(r.weekdays).includes(weekday))
    )
      continue;
    if (
      !get(
        db,
        'SELECT id FROM tasks WHERE child_id=? AND date=? AND rule_key=?',
        childId,
        date,
        r.rule_key,
      )
    )
      insert(db, 'tasks', {
        id: uid(),
        child_id: childId,
        date,
        rule_key: r.rule_key,
        rule_version: r.version,
        title: r.title,
        description: r.description,
        icon: r.icon,
        subject: r.subject,
        stars: r.stars,
        daily_limit: r.daily_limit,
        created_at: now(),
      });
  }
}
export function bootstrap(db, config) {
  if (!get(db, "SELECT id FROM users WHERE role='admin'")) {
    if (!config.adminPassword || config.adminPassword.length < 12)
      throw new Error('首次启动请在 .env 设置至少 12 位 ADMIN_PASSWORD');
    createUser(db, {
      username: config.adminUsername || 'admin',
      name: '超级管理员',
      role: 'admin',
      password: config.adminPassword,
    });
  }
  if (!config.demo || get(db, "SELECT id FROM users WHERE username='child'")) return;
  tx(db, () => {
    const familyId = uid();
    insert(db, 'families', { id: familyId, name: '小恐龙一家', created_at: now() });
    const parent = createUser(db, {
      family_id: familyId,
      username: 'parent',
      name: '恐龙妈妈',
      role: 'parent',
      password: 'parent123',
    });
    const child = createUser(db, {
      family_id: familyId,
      username: 'child',
      name: '小星',
      role: 'child',
      password: 'child123',
    });
    run(db, 'UPDATE rules SET effective_from=? WHERE child_id=?', addDays(today(), -7), child.id);
    [
      ['甜甜冰淇淋', '和家长一起挑选喜欢的口味。', 'icecream', 5],
      ['新玩具伙伴', '一起挑选一个心仪的小玩具。', 'toy', 30],
      ['周末电影夜', '选一部喜欢的动画电影，全家一起看。', 'movie', 15],
      ['公园野餐', '带上小点心，去大自然里探险。', 'picnic', 20],
    ].forEach(([title, description, icon, cost]) =>
      insert(db, 'rewards', {
        id: uid(),
        family_id: familyId,
        title,
        description,
        icon,
        cost,
        active: 1,
        created_at: now(),
      }),
    );
    [2, 4, 3, 5, 4, 6, 5].forEach((amount, i) =>
      insert(db, 'ledger', {
        id: uid(),
        child_id: child.id,
        amount,
        kind: 'bonus',
        title: '努力成长的奖励',
        note: '演示数据',
        date: addDays(today(), i - 6),
        created_at: now(),
        actor_id: parent.id,
        submission_id: null,
        redemption_id: null,
        reversal_of: null,
        request_key: uid(),
      }),
    );
    insert(db, 'ledger', {
      id: uid(),
      child_id: child.id,
      amount: -5,
      kind: 'spend',
      title: '买冰淇淋',
      note: '演示数据',
      date: addDays(today(), -1),
      created_at: now(),
      actor_id: parent.id,
      submission_id: null,
      redemption_id: null,
      reversal_of: null,
      request_key: uid(),
    });
  });
}
