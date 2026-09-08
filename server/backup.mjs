import { z } from 'zod';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { openDb, all, get, insert, tx, now } from './db.mjs';
const id = z.string().uuid(),
  text = z.string().max(2000),
  date = z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/)
    .refine((s) => !Number.isNaN(Date.parse(s)) && new Date(s).toISOString().slice(0, 10) === s),
  timestamp = z.string().datetime(),
  bit = z.number().int().min(0).max(1),
  positive = z.number().int().positive();
const rows = {
  families: z.object({ id, name: text, created_at: timestamp }).strict(),
  users: z
    .object({
      id,
      family_id: id.nullable(),
      username: text,
      name: text,
      role: z.enum(['admin', 'parent', 'child']),
      password_hash: z.string().regex(/^[a-f0-9]{32}:[a-f0-9]{128}$/),
      active: bit,
      created_at: timestamp,
    })
    .strict(),
  rules: z
    .object({
      id,
      rule_key: id,
      child_id: id,
      title: text,
      description: text,
      icon: text,
      subject: z.enum(['chinese', 'math', 'english', 'sports', 'other']).default('other'),
      stars: positive.max(100),
      daily_limit: positive.max(20),
      schedule: z.enum(['daily', 'weekly', 'once']),
      weekdays: z.string().refine((v) => {
        try {
          return z.array(z.number().int().min(0).max(6)).safeParse(JSON.parse(v)).success;
        } catch {
          return false;
        }
      }),
      on_date: date.nullable(),
      effective_from: date,
      enabled: bit,
      version: positive,
      created_at: timestamp,
    })
    .strict(),
  tasks: z
    .object({
      id,
      child_id: id,
      date,
      rule_key: id,
      rule_version: positive,
      title: text,
      description: text,
      icon: text,
      subject: z.enum(['chinese', 'math', 'english', 'sports', 'other']).default('other'),
      stars: positive.max(100),
      daily_limit: positive.max(20),
      created_at: timestamp,
    })
    .strict(),
  submissions: z
    .object({
      id,
      task_id: id,
      bonus: bit.default(0),
      child_id: id,
      status: z.enum(['pending', 'approved', 'rejected']),
      note: text,
      review_note: text,
      reviewed_by: id.nullable(),
      created_at: timestamp,
      reviewed_at: timestamp.nullable(),
      request_key: text,
    })
    .strict(),
  rewards: z
    .object({
      id,
      family_id: id,
      title: text,
      description: text,
      icon: text,
      cost: positive.max(100000),
      active: bit,
      created_at: timestamp,
    })
    .strict(),
  redemptions: z
    .object({
      id,
      reward_id: id,
      child_id: id,
      title: text,
      icon: text,
      cost: positive.max(100000),
      status: z.enum(['pending', 'approved', 'rejected']),
      review_note: text,
      reviewed_by: id.nullable(),
      created_at: timestamp,
      reviewed_at: timestamp.nullable(),
      request_key: text,
    })
    .strict(),
  ledger: z
    .object({
      id,
      child_id: id,
      amount: z
        .number()
        .int()
        .min(-100000)
        .max(100000)
        .refine((n) => n !== 0),
      kind: z.enum(['task', 'reward', 'bonus', 'spend', 'deduction', 'reversal']),
      title: text,
      note: text,
      date,
      created_at: timestamp,
      actor_id: id,
      submission_id: id.nullable(),
      redemption_id: id.nullable(),
      reversal_of: id.nullable(),
      request_key: text,
    })
    .strict(),
};
const tables = Object.keys(rows);
function invalid(message) {
  throw Object.assign(new Error(message), { status: 400 });
}
const backupSchema = z
  .object({
    format: z.literal('star-explorer'),
    version: z.literal(1),
    exported_at: timestamp,
    data: z
      .object(Object.fromEntries(tables.map((t) => [t, z.array(rows[t]).max(100000)])))
      .strict(),
  })
  .strict();
export function snapshot(db) {
  return {
    format: 'star-explorer',
    version: 1,
    exported_at: now(),
    data: Object.fromEntries(tables.map((t) => [t, all(db, `SELECT * FROM ${t}`)])),
  };
}
function importRows(db, data) {
  db.exec('PRAGMA defer_foreign_keys=ON;');
  for (const t of [...tables].reverse()) db.exec(`DELETE FROM ${t}`);
  for (const t of tables) for (const row of data[t]) insert(db, t, row);
}
export function restore(db, input, backupDir) {
  const content = backupSchema.parse(input);
  const candidate = openDb(':memory:');
  try {
    tx(candidate, () => importRows(candidate, content.data));
    if (!get(candidate, "SELECT id FROM users WHERE role='admin' AND active=1"))
      invalid('备份必须包含有效的超级管理员');
    if (all(candidate, 'SELECT child_id FROM ledger GROUP BY child_id HAVING SUM(amount)<0').length)
      invalid('备份含负余额');
    if (
      get(
        candidate,
        'SELECT s.id FROM submissions s JOIN tasks t ON t.id=s.task_id WHERE s.child_id!=t.child_id LIMIT 1',
      )
    )
      invalid('备份任务关系不一致');
    if (
      get(
        candidate,
        "SELECT t.id FROM tasks t JOIN users u ON u.id=t.child_id WHERE u.role!='child' LIMIT 1",
      )
    )
      invalid('备份孩子身份不一致');
    for (const table of ['rules', 'redemptions', 'ledger']) {
      if (
        get(
          candidate,
          `SELECT x.id FROM ${table} x JOIN users u ON u.id=x.child_id
          WHERE u.role!='child' LIMIT 1`,
        )
      )
        invalid('备份孩子身份不一致');
    }
    if (
      get(
        candidate,
        `SELECT r.id FROM redemptions r
        JOIN rewards w ON w.id=r.reward_id JOIN users c ON c.id=r.child_id
        WHERE w.family_id!=c.family_id LIMIT 1`,
      )
    )
      invalid('备份奖励家庭不一致');
    for (const [table, actor] of [
      ['ledger', 'actor_id'],
      ['submissions', 'reviewed_by'],
      ['redemptions', 'reviewed_by'],
    ]) {
      if (
        get(
          candidate,
          `SELECT x.id FROM ${table} x
          JOIN users a ON a.id=x.${actor} JOIN users c ON c.id=x.child_id
          WHERE a.role='child' OR (a.role='parent' AND a.family_id!=c.family_id) LIMIT 1`,
        )
      )
        invalid('备份操作人权限不一致');
    }
    if (
      get(
        candidate,
        `SELECT l.id FROM ledger l
        LEFT JOIN submissions s ON s.id=l.submission_id
        LEFT JOIN tasks t ON t.id=s.task_id
        LEFT JOIN redemptions r ON r.id=l.redemption_id
        LEFT JOIN ledger original ON original.id=l.reversal_of
        WHERE (l.kind='task' AND (s.id IS NULL OR s.status!='approved'
          OR s.child_id!=l.child_id OR l.amount!=t.stars+s.bonus))
        OR (l.kind='reward' AND (r.id IS NULL OR r.status!='approved'
          OR r.child_id!=l.child_id OR l.amount!=-r.cost))
        OR (l.kind='reversal' AND (original.id IS NULL OR original.kind='reversal'
          OR original.child_id!=l.child_id OR l.amount!=-original.amount))
        OR (l.submission_id IS NOT NULL AND l.kind!='task')
        OR (l.redemption_id IS NOT NULL AND l.kind!='reward')
        OR (l.reversal_of IS NOT NULL AND l.kind!='reversal')
        OR (l.kind='bonus' AND l.amount<0)
        OR (l.kind IN ('spend','deduction') AND l.amount>0) LIMIT 1`,
      )
    )
      invalid('备份流水与业务记录不一致');
    for (const [table, link] of [
      ['submissions', 'submission_id'],
      ['redemptions', 'redemption_id'],
    ]) {
      if (
        get(
          candidate,
          `SELECT x.id FROM ${table} x
          LEFT JOIN ledger l ON l.${link}=x.id
          WHERE ${table === 'submissions' ? "(x.status!='approved' AND x.bonus!=0) OR" : ''} (x.status='approved' AND l.id IS NULL)
          OR (x.status='pending' AND (x.reviewed_by IS NOT NULL OR x.reviewed_at IS NOT NULL))
          OR (x.status!='pending' AND (x.reviewed_by IS NULL OR x.reviewed_at IS NULL)) LIMIT 1`,
        )
      )
        invalid('备份审核状态不一致');
    }
    if (
      get(
        candidate,
        `SELECT t.id FROM tasks t JOIN submissions s ON s.task_id=t.id
        WHERE s.status IN ('pending','approved') GROUP BY t.id
        HAVING COUNT(*)>t.daily_limit LIMIT 1`,
      )
    )
      invalid('备份任务次数超出上限');
    if (
      get(
        candidate,
        `SELECT id FROM rules WHERE
        (schedule='once' AND on_date IS NULL) OR
        (schedule='weekly' AND json_array_length(weekdays)=0) LIMIT 1`,
      )
    )
      invalid('备份任务安排不完整');
    if (all(candidate, 'PRAGMA foreign_key_check').length) invalid('备份引用不完整');
  } finally {
    candidate.close();
  }
  mkdirSync(backupDir, { recursive: true });
  const saved = join(backupDir, `before-restore-${Date.now()}.json`);
  writeFileSync(saved, JSON.stringify(snapshot(db)), { mode: 0o600, flag: 'wx' });
  tx(db, () => {
    db.exec('DELETE FROM sessions');
    importRows(db, content.data);
  });
  return saved;
}
