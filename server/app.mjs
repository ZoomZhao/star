import templates from '../shared/task-templates.json' with { type: 'json' };
import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { randomBytes, createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { existsSync } from 'node:fs';
import {
  openDb,
  bootstrap,
  all,
  get,
  run,
  insert,
  tx,
  uid,
  now,
  today,
  addDays,
  balance,
  materialize,
  verifyPassword,
  hashPassword,
  createUser,
  publicUser,
} from './db.mjs';
import { snapshot, restore } from './backup.mjs';
const dateSchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/)
  .refine(
    (s) => !Number.isNaN(Date.parse(s)) && new Date(s).toISOString().slice(0, 10) === s,
    '日期无效',
  );
const str = z.string().trim().min(1).max(100),
  description = z.string().trim().max(1000).default(''),
  id = z.string().uuid();
const taskFields = z.object({
  title: str,
  subject: z.enum(['chinese', 'math', 'english', 'sports', 'other']).default('other'),
  description,
  icon: z.enum(['book', 'brush', 'blocks', 'leaf', 'bed', 'pencil', 'heart']).default('book'),
  stars: z.number().int().min(1).max(100),
  daily_limit: z.number().int().min(1).max(20),
});
const ruleFields = taskFields
  .extend({
    schedule: z.enum(['daily', 'weekly', 'once']),
    weekdays: z.array(z.number().int().min(0).max(6)).max(7).default([]),
    on_date: dateSchema.nullable().default(null),
  })
  .refine((v) => v.schedule !== 'weekly' || v.weekdays.length > 0, '请至少选择一天')
  .refine((v) => v.schedule !== 'once' || v.on_date !== null, '请选择日期');
const rewardFields = z.object({
  title: str,
  description,
  icon: z.enum(['icecream', 'toy', 'movie', 'picnic', 'gift']).default('gift'),
  cost: z.number().int().min(1).max(100000),
  active: z.boolean().default(true),
});
const fail = (code, message) => {
  const e = new Error(message);
  e.status = code;
  throw e;
};
const hash = (s) => createHash('sha256').update(s).digest('hex');
export function createApp(config = {}) {
  const db = openDb(config.databasePath || './data/star.sqlite');
  bootstrap(db, config);
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', config.trustProxy ? 1 : false);
  app.use(
    helmet({
      contentSecurityPolicy: {
        directives: { 'connect-src': ["'self'", ...(config.origins || [])] },
      },
    }),
  );
  app.use((req, res, next) => {
    const origin = req.headers.origin;
    if (origin) {
      const allowed = (config.origins || []).includes(origin);
      if (!allowed) return res.status(403).json({ error: '来源未授权' });
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, Idempotency-Key');
      res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PATCH,OPTIONS');
    }
    if (req.method === 'OPTIONS') return res.sendStatus(204);
    next();
  });
  app.use(express.json({ limit: '20mb' }));
  app.use('/api', (req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    next();
  });
  app.get('/api/health', (_req, res) => res.json({ ok: true }));
  app.get('/api/config', (_req, res) => res.json({ demo: !!config.demo, today: today() }));
  app.post(
    '/api/login',
    rateLimit({
      windowMs: 15 * 60 * 1000,
      limit: 30,
      standardHeaders: 'draft-8',
      legacyHeaders: false,
      message: { error: '尝试过于频繁，请稍后再试' },
    }),
    (req, res) => {
      const v = z.object({ username: str, password: z.string().min(1).max(200) }).parse(req.body);
      const user = get(db, 'SELECT * FROM users WHERE username=?', v.username);
      if (!user || !user.active || !verifyPassword(v.password, user.password_hash))
        fail(401, '账号或密码不正确');
      const token = randomBytes(32).toString('hex');
      run(db, 'DELETE FROM sessions WHERE expires_at<?', now());
      insert(db, 'sessions', {
        token_hash: hash(token),
        user_id: user.id,
        expires_at: new Date(Date.now() + 180 * 86400000).toISOString(),
      });
      res.json({ token, user: publicUser(user) });
    },
  );
  app.use('/api', (req, _res, next) => {
    const token = req.headers.authorization?.replace(/^Bearer /, '');
    if (!token) fail(401, '请先登录');
    const u = get(
      db,
      'SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>? AND u.active=1',
      hash(token),
      now(),
    );
    if (!u) fail(401, '登录已过期，请重新登录');
    run(
      db,
      'UPDATE sessions SET expires_at=? WHERE token_hash=? AND expires_at<?',
      new Date(Date.now() + 180 * 86400000).toISOString(),
      hash(token),
      new Date(Date.now() + 179 * 86400000).toISOString(),
    );
    req.user = u;
    req.token = token;
    next();
  });
  const role = (req, ...roles) => {
    if (!roles.includes(req.user.role)) fail(403, '当前账号没有权限');
  };
  const child = (req, childId) => {
    const c = get(db, "SELECT * FROM users WHERE id=? AND role='child'", id.parse(childId));
    if (!c) fail(404, '小朋友不存在');
    if (req.user.role === 'child' && req.user.id !== c.id) fail(403, '只能查看自己的数据');
    if (req.user.role === 'parent' && req.user.family_id !== c.family_id)
      fail(403, '只能管理自己的家庭');
    return c;
  };
  const key = (req) => {
    const k = req.headers['idempotency-key'];
    if (typeof k !== 'string' || !z.string().uuid().safeParse(k).success)
      fail(400, '缺少有效的操作编号，请重试');
    return req.user.id + ':' + k;
  };
  const postLedger = ({
    childId,
    amount,
    kind,
    title,
    note = '',
    date = today(),
    actor,
    submission = null,
    redemption = null,
    reversal = null,
    requestKey,
  }) => {
    if (balance(db, childId) + amount < 0) fail(409, '星星余额不足');
    const row = {
      id: uid(),
      child_id: childId,
      amount,
      kind,
      title,
      note,
      date,
      created_at: now(),
      actor_id: actor,
      submission_id: submission,
      redemption_id: redemption,
      reversal_of: reversal,
      request_key: requestKey,
    };
    insert(db, 'ledger', row);
    return row;
  };
  app.get('/api/task-templates', (_req, res) => res.json(templates));
  app.get('/api/me', (req, res) => res.json(publicUser(req.user)));
  app.post('/api/logout', (req, res) => {
    run(db, 'DELETE FROM sessions WHERE token_hash=?', hash(req.token));
    res.json({ ok: true });
  });
  app.post('/api/password', (req, res) => {
    const v = z
      .object({ current: z.string().max(200), password: z.string().min(8).max(200) })
      .parse(req.body);
    if (!verifyPassword(v.current, req.user.password_hash)) fail(400, '原密码不正确');
    tx(db, () => {
      run(db, 'UPDATE users SET password_hash=? WHERE id=?', hashPassword(v.password), req.user.id);
      run(db, 'DELETE FROM sessions WHERE user_id=?', req.user.id);
    });
    res.json({ ok: true });
  });
  app.get('/api/children', (req, res) => {
    if (req.user.role === 'child') return res.json([publicUser(req.user)]);
    const cs =
      req.user.role === 'admin'
        ? all(db, "SELECT * FROM users WHERE role='child' AND active=1")
        : all(
            db,
            "SELECT * FROM users WHERE family_id=? AND role='child' AND active=1",
            req.user.family_id,
          );
    res.json(cs.map(publicUser));
  });
  app.get('/api/children/:childId/dashboard', (req, res) => {
    const c = child(req, req.params.childId);
    const date = dateSchema.parse(req.query.date || today());
    if (date < addDays(today(), -3660) || date > addDays(today(), 366))
      fail(400, '请选择近十年或未来一年内的日期');
    tx(db, () => materialize(db, c.id, date));
    const tasks = all(
      db,
      `SELECT t.*, (SELECT COALESCE(SUM(quantity),0) FROM submissions s WHERE s.task_id=t.id AND s.status='approved') approved, (SELECT COALESCE(SUM(quantity),0) FROM submissions s WHERE s.task_id=t.id AND s.status='pending') pending FROM tasks t WHERE child_id=? AND date=? ORDER BY CASE subject WHEN 'chinese' THEN 0 WHEN 'math' THEN 1 WHEN 'english' THEN 2 WHEN 'sports' THEN 3 ELSE 4 END,COALESCE((SELECT position FROM task_order o WHERE o.child_id=t.child_id AND o.rule_key=t.rule_key),2147483647),(SELECT MIN(x.rowid) FROM rules x WHERE x.rule_key=t.rule_key),created_at,title,id`,
      c.id,
      date,
    );
    for (const t of tasks)
      t.submissions = all(
        db,
        'SELECT id,status,note,review_note,created_at,quantity,unit_stars FROM submissions WHERE task_id=? ORDER BY created_at DESC',
        t.id,
      );
    const totals = get(
      db,
      'SELECT COALESCE(SUM(CASE WHEN amount>0 THEN amount ELSE 0 END),0) earned, COALESCE(-SUM(CASE WHEN amount<0 THEN amount ELSE 0 END),0) spent FROM ledger WHERE child_id=?',
      c.id,
    );
    const ledger = all(
      db,
      'SELECT l.*, (SELECT id FROM ledger x WHERE x.reversal_of=l.id) reversed_by FROM ledger l WHERE child_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1000',
      c.id,
    );
    const week = Array.from({ length: 7 }, (_, i) => {
      const d = addDays(date, i - 6);
      return {
        date: d,
        ...get(
          db,
          'SELECT COALESCE(SUM(CASE WHEN amount>0 THEN amount ELSE 0 END),0) earned, COALESCE(-SUM(CASE WHEN amount<0 THEN amount ELSE 0 END),0) spent FROM ledger WHERE child_id=? AND date=?',
          c.id,
          d,
        ),
      };
    });
    const reviews =
      req.user.role === 'child'
        ? []
        : all(
            db,
            `SELECT s.*,t.title,t.icon,t.subject,t.stars,t.daily_limit,t.date,t.description, (t.daily_limit-(SELECT COALESCE(SUM(x.quantity),0) FROM submissions x WHERE x.task_id=t.id AND x.id!=s.id AND x.status IN ('pending','approved'))) remaining FROM submissions s JOIN tasks t ON t.id=s.task_id WHERE s.child_id=? AND s.status='pending' ORDER BY s.created_at`,
            c.id,
          );
    const rules =
      req.user.role === 'child'
        ? []
        : all(
            db,
            'SELECT r.* FROM rules r WHERE child_id=? AND version=(SELECT MAX(version) FROM rules x WHERE x.rule_key=r.rule_key) ORDER BY COALESCE((SELECT position FROM task_order o WHERE o.child_id=r.child_id AND o.rule_key=r.rule_key),2147483647),(SELECT MIN(x.rowid) FROM rules x WHERE x.rule_key=r.rule_key)',
            c.id,
          ).map((r) => ({ ...r, weekdays: JSON.parse(r.weekdays) }));
    res.json({
      today: today(),
      date,
      child: publicUser(c),
      tasks,
      wallet: { balance: balance(db, c.id), ...totals },
      ledger,
      week,
      reviews,
      rules,
      rewards: all(
        db,
        `SELECT * FROM rewards WHERE family_id=? ${req.user.role === 'child' ? 'AND active=1' : ''} ORDER BY active DESC,cost,created_at,id`,
        c.family_id,
      ),
      redemptions: all(
        db,
        'SELECT * FROM redemptions WHERE child_id=? ORDER BY created_at DESC LIMIT 200',
        c.id,
      ),
      dayEarned: get(
        db,
        'SELECT COALESCE(SUM(amount),0) n FROM ledger WHERE child_id=? AND date=? AND amount>0',
        c.id,
        date,
      ).n,
    });
  });
  app.post('/api/tasks/:taskId/submit', (req, res) => {
    const task = get(db, 'SELECT * FROM tasks WHERE id=?', id.parse(req.params.taskId));
    if (!task) fail(404, '任务不存在');
    child(req, task.child_id);
    const v = z
      .object({
        note: description,
        bonus: z.boolean().default(false),
        deduction: z.boolean().default(false),
        quantity: z.number().int().min(1).max(20).default(1),
        unit_stars: z.number().int().min(1).max(101).optional(),
      })
      .parse(req.body);
    if (
      (v.bonus || v.deduction || v.unit_stars !== undefined || v.quantity !== 1) &&
      req.user.role === 'child'
    )
      fail(400, '不能发放额外奖励');
    if (v.deduction && (v.bonus || task.stars <= 1))
      fail(400, '少发 1 星后至少保留 1 星，不能同时额外奖励');
    if (v.unit_stars !== undefined && (v.bonus || v.deduction))
      fail(400, '不能混合使用星星调整方式');
    const unitStars = v.unit_stars ?? task.stars + Number(v.bonus) - Number(v.deduction);
    const requestKey = key(req);
    const result = tx(db, () => {
      const existing = get(db, 'SELECT * FROM submissions WHERE request_key=?', requestKey);
      if (existing) {
        if (
          existing.task_id !== task.id ||
          existing.note !== v.note ||
          (req.user.role !== 'child' &&
            (existing.bonus !== Number(v.bonus) ||
              existing.deduction !== Number(v.deduction) ||
              existing.quantity !== v.quantity ||
              (existing.unit_stars ?? task.stars + existing.bonus - existing.deduction) !==
                unitStars))
        )
          fail(409, '操作编号已用于其他内容，请刷新后重试');
        return existing;
      }
      if (task.date > today() || (req.user.role === 'child' && task.date !== today()))
        fail(400, '只能完成当天任务');
      const used = get(
        db,
        "SELECT COALESCE(SUM(quantity),0) n FROM submissions WHERE task_id=? AND status IN ('pending','approved')",
        task.id,
      ).n;
      if (used + v.quantity > task.daily_limit) fail(409, '已达到今日上限（含待确认次数）');
      const approved = req.user.role !== 'child';
      const row = {
        id: uid(),
        task_id: task.id,
        child_id: task.child_id,
        status: approved ? 'approved' : 'pending',
        bonus: Number(v.bonus),
        deduction: Number(v.deduction),
        quantity: v.quantity,
        unit_stars: approved ? unitStars : null,
        note: v.note,
        review_note: '',
        reviewed_by: approved ? req.user.id : null,
        created_at: now(),
        reviewed_at: approved ? now() : null,
        request_key: requestKey,
      };
      insert(db, 'submissions', row);
      if (approved)
        postLedger({
          childId: task.child_id,
          amount: unitStars * v.quantity,
          kind: 'task',
          title: task.title,
          note: `家长代完成：${unitStars} 星 × ${v.quantity} 次`,
          date: task.date,
          actor: req.user.id,
          submission: row.id,
          requestKey,
        });
      return row;
    });
    res.json(result);
  });
  app.post('/api/submissions/:submissionId/review', (req, res) => {
    role(req, 'parent', 'admin');
    const s = get(
      db,
      'SELECT s.*,t.stars,t.daily_limit,t.title,t.date FROM submissions s JOIN tasks t ON t.id=s.task_id WHERE s.id=?',
      id.parse(req.params.submissionId),
    );
    if (!s) fail(404, '提交不存在');
    child(req, s.child_id);
    const v = z
      .object({
        approve: z.boolean(),
        note: description,
        bonus: z.boolean().default(false),
        deduction: z.boolean().default(false),
        quantity: z.number().int().min(1).max(20).default(1),
        unit_stars: z.number().int().min(1).max(101).optional(),
      })
      .parse(req.body);
    if ((v.bonus || v.deduction || v.unit_stars !== undefined || v.quantity !== 1) && !v.approve)
      fail(400, '退回时不能调整星星');
    if (v.deduction && (v.bonus || s.stars <= 1))
      fail(400, '少发 1 星后至少保留 1 星，不能同时额外奖励');
    if (v.unit_stars !== undefined && (v.bonus || v.deduction))
      fail(400, '不能混合使用星星调整方式');
    const unitStars = v.unit_stars ?? s.stars + Number(v.bonus) - Number(v.deduction);
    tx(db, () => {
      const current = get(db, 'SELECT status FROM submissions WHERE id=?', s.id);
      if (current.status !== 'pending') fail(409, '这条任务已经处理过了');
      const used = get(
        db,
        "SELECT COALESCE(SUM(quantity),0) n FROM submissions WHERE task_id=? AND id!=? AND status IN ('pending','approved')",
        s.task_id,
        s.id,
      ).n;
      if (v.approve && used + v.quantity > s.daily_limit)
        fail(409, '完成次数超过每日剩余上限（含待确认次数）');
      run(
        db,
        'UPDATE submissions SET status=?,review_note=?,reviewed_by=?,reviewed_at=?,bonus=?,deduction=?,quantity=?,unit_stars=? WHERE id=?',
        v.approve ? 'approved' : 'rejected',
        v.note,
        req.user.id,
        now(),
        Number(v.bonus),
        Number(v.deduction),
        v.quantity,
        v.approve ? unitStars : null,
        s.id,
      );
      if (v.approve)
        postLedger({
          childId: s.child_id,
          amount: unitStars * v.quantity,
          kind: 'task',
          title: s.title,
          note: [s.note, `${unitStars} 星 × ${v.quantity} 次`].filter(Boolean).join('；'),
          date: s.date,
          actor: req.user.id,
          submission: s.id,
          requestKey: 'approval:' + s.id,
        });
    });
    res.json({ ok: true });
  });
  app.post('/api/children/:childId/task-order', (req, res) => {
    role(req, 'parent', 'admin');
    const c = child(req, req.params.childId);
    const v = z.object({ rule_key: id, direction: z.enum(['up', 'down']) }).parse(req.body);
    tx(db, () => {
      const rules = all(
        db,
        `SELECT r.* FROM rules r WHERE child_id=? AND version=(SELECT MAX(version) FROM rules x WHERE x.rule_key=r.rule_key) ORDER BY COALESCE((SELECT position FROM task_order o WHERE o.child_id=r.child_id AND o.rule_key=r.rule_key),2147483647),(SELECT MIN(x.rowid) FROM rules x WHERE x.rule_key=r.rule_key)`,
        c.id,
      );
      const selected = rules.find((r) => r.rule_key === v.rule_key);
      if (!selected) fail(404, '规则不存在');
      const group = rules.filter((r) => r.subject === selected.subject);
      const index = group.findIndex((r) => r.rule_key === v.rule_key),
        target = index + (v.direction === 'up' ? -1 : 1);
      if (target < 0 || target >= group.length) return;
      [group[index], group[target]] = [group[target], group[index]];
      group.forEach((r, i) =>
        run(
          db,
          'INSERT INTO task_order(child_id,rule_key,position) VALUES(?,?,?) ON CONFLICT(child_id,rule_key) DO UPDATE SET position=excluded.position',
          c.id,
          r.rule_key,
          i,
        ),
      );
    });
    res.json({ ok: true });
  });
  app.post('/api/children/:childId/rules', (req, res) => {
    role(req, 'parent', 'admin');
    const c = child(req, req.params.childId);
    const v = ruleFields.parse(req.body);
    if (v.schedule === 'once' && v.on_date < today()) fail(400, '指定日期不能早于今天');
    const row = {
      ...v,
      id: uid(),
      rule_key: uid(),
      child_id: c.id,
      weekdays: JSON.stringify(v.weekdays),
      effective_from: today(),
      enabled: req.body.enabled === false ? 0 : 1,
      version: 1,
      created_at: now(),
    };
    insert(db, 'rules', row);
    res.status(201).json(row);
  });
  app.patch('/api/rules/:ruleKey', (req, res) => {
    role(req, 'parent', 'admin');
    const old = get(
      db,
      'SELECT * FROM rules WHERE rule_key=? ORDER BY version DESC LIMIT 1',
      id.parse(req.params.ruleKey),
    );
    if (!old) fail(404, '规则不存在');
    child(req, old.child_id);
    const v = ruleFields.parse(req.body);
    const enabled = z.boolean().default(true).parse(req.body.enabled);
    const effective = addDays(today(), 1);
    if (v.schedule === 'once' && v.on_date < effective)
      fail(400, '模板变更从明日起生效，单日变更请编辑当日任务');
    tx(db, () => {
      // A preview must not freeze tomorrow's template. Preserve explicit day edits
      // (fields differ from their source version), today's snapshots and submissions.
      run(
        db,
        `DELETE FROM tasks WHERE rule_key=? AND date>=?
        AND NOT EXISTS (SELECT 1 FROM submissions s WHERE s.task_id=tasks.id)
        AND EXISTS (SELECT 1 FROM rules r WHERE r.rule_key=tasks.rule_key
          AND r.version=tasks.rule_version AND r.title=tasks.title
          AND r.description=tasks.description AND r.icon=tasks.icon
          AND r.subject=tasks.subject AND r.stars=tasks.stars
          AND r.daily_limit=tasks.daily_limit)`,
        old.rule_key,
        effective,
      );
      insert(db, 'rules', {
        ...old,
        ...v,
        id: uid(),
        weekdays: JSON.stringify(v.weekdays),
        enabled: enabled ? 1 : 0,
        effective_from: effective,
        version: old.version + 1,
        created_at: now(),
      });
    });
    res.json({ ok: true, effective_from: effective });
  });
  app.patch('/api/tasks/:taskId', (req, res) => {
    role(req, 'parent', 'admin');
    const t = get(db, 'SELECT * FROM tasks WHERE id=?', id.parse(req.params.taskId));
    if (!t) fail(404, '任务不存在');
    child(req, t.child_id);
    if (t.date < today()) fail(400, '历史规则不可修改');
    const v = taskFields.parse(req.body);
    tx(db, () => {
      if (get(db, 'SELECT id FROM submissions WHERE task_id=?', t.id))
        fail(409, '任务已有提交，规则已冻结');
      run(
        db,
        'UPDATE tasks SET title=?,description=?,icon=?,subject=?,stars=?,daily_limit=? WHERE id=?',
        v.title,
        v.description,
        v.icon,
        v.subject,
        v.stars,
        v.daily_limit,
        t.id,
      );
    });
    res.json({ ok: true });
  });
  app.post('/api/children/:childId/ledger', (req, res) => {
    role(req, 'parent', 'admin');
    const c = child(req, req.params.childId);
    const v = z
      .object({
        amount: z.number().int().min(1).max(100000),
        kind: z.enum(['bonus', 'spend', 'deduction']),
        title: str,
        note: description,
      })
      .parse(req.body);
    const requestKey = key(req);
    const result = tx(db, () => {
      const existing = get(db, 'SELECT * FROM ledger WHERE request_key=?', requestKey);
      const amount = v.kind === 'bonus' ? v.amount : -v.amount;
      if (existing) {
        if (
          existing.child_id !== c.id ||
          existing.kind !== v.kind ||
          existing.amount !== amount ||
          existing.title !== v.title ||
          existing.note !== v.note
        )
          fail(409, '操作编号已用于其他内容，请刷新后重试');
        return existing;
      }
      return postLedger({
        childId: c.id,
        amount: v.kind === 'bonus' ? v.amount : -v.amount,
        kind: v.kind,
        title: v.title,
        note: v.note,
        actor: req.user.id,
        requestKey,
      });
    });
    res.json(result);
  });
  app.post('/api/ledger/:ledgerId/reverse', (req, res) => {
    role(req, 'parent', 'admin');
    const l = get(db, 'SELECT * FROM ledger WHERE id=?', id.parse(req.params.ledgerId));
    if (!l) fail(404, '流水不存在');
    child(req, l.child_id);
    const v = z.object({ note: str }).parse(req.body);
    tx(db, () => {
      if (l.kind === 'reversal' || get(db, 'SELECT id FROM ledger WHERE reversal_of=?', l.id))
        fail(409, '这条流水不可再次撤销');
      postLedger({
        childId: l.child_id,
        amount: -l.amount,
        kind: 'reversal',
        title: '撤销：' + l.title,
        note: v.note,
        actor: req.user.id,
        reversal: l.id,
        requestKey: 'reversal:' + l.id,
      });
    });
    res.json({ ok: true });
  });
  app.post('/api/children/:childId/rewards', (req, res) => {
    role(req, 'parent', 'admin');
    const c = child(req, req.params.childId),
      v = rewardFields.parse(req.body);
    const row = {
      ...v,
      id: uid(),
      family_id: c.family_id,
      active: v.active ? 1 : 0,
      created_at: now(),
    };
    insert(db, 'rewards', row);
    res.status(201).json(row);
  });
  app.patch('/api/rewards/:rewardId', (req, res) => {
    role(req, 'parent', 'admin');
    const r = get(db, 'SELECT * FROM rewards WHERE id=?', id.parse(req.params.rewardId));
    if (!r) fail(404, '奖励不存在');
    if (req.user.role === 'parent' && req.user.family_id !== r.family_id)
      fail(403, '不能修改其他家庭奖励');
    const v = rewardFields.parse(req.body);
    run(
      db,
      'UPDATE rewards SET title=?,description=?,icon=?,cost=?,active=? WHERE id=?',
      v.title,
      v.description,
      v.icon,
      v.cost,
      v.active ? 1 : 0,
      r.id,
    );
    res.json({ ok: true });
  });
  app.post('/api/children/:childId/redemptions', (req, res) => {
    const c = child(req, req.params.childId);
    const { reward_id } = z.object({ reward_id: id }).parse(req.body);
    const requestKey = key(req);
    const result = tx(db, () => {
      const existing = get(db, 'SELECT * FROM redemptions WHERE request_key=?', requestKey);
      if (existing) {
        if (existing.child_id !== c.id || existing.reward_id !== reward_id)
          fail(409, '操作编号已用于其他内容，请刷新后重试');
        return existing;
      }
      const r = get(
        db,
        'SELECT * FROM rewards WHERE id=? AND family_id=? AND active=1',
        reward_id,
        c.family_id,
      );
      if (!r) fail(404, '奖励已下架');
      if (balance(db, c.id) < r.cost) fail(409, '星星还不够，再完成几个任务吧');
      if (
        get(
          db,
          "SELECT id FROM redemptions WHERE child_id=? AND reward_id=? AND status='pending'",
          c.id,
          r.id,
        )
      )
        fail(409, '已申请过这个奖励，等家长确认吧');
      const row = {
        id: uid(),
        reward_id: r.id,
        child_id: c.id,
        title: r.title,
        icon: r.icon,
        cost: r.cost,
        status: 'pending',
        review_note: '',
        reviewed_by: null,
        created_at: now(),
        reviewed_at: null,
        request_key: requestKey,
      };
      insert(db, 'redemptions', row);
      return row;
    });
    res.json(result);
  });
  app.post('/api/redemptions/:redemptionId/review', (req, res) => {
    role(req, 'parent', 'admin');
    const r = get(db, 'SELECT * FROM redemptions WHERE id=?', id.parse(req.params.redemptionId));
    if (!r) fail(404, '兑换申请不存在');
    child(req, r.child_id);
    const v = z.object({ approve: z.boolean(), note: description }).parse(req.body);
    tx(db, () => {
      if (get(db, 'SELECT status FROM redemptions WHERE id=?', r.id).status !== 'pending')
        fail(409, '申请已处理');
      if (v.approve)
        postLedger({
          childId: r.child_id,
          amount: -r.cost,
          kind: 'reward',
          title: r.title,
          note: v.note,
          actor: req.user.id,
          redemption: r.id,
          requestKey: 'redeem:' + r.id,
        });
      run(
        db,
        'UPDATE redemptions SET status=?,review_note=?,reviewed_by=?,reviewed_at=? WHERE id=?',
        v.approve ? 'approved' : 'rejected',
        v.note,
        req.user.id,
        now(),
        r.id,
      );
    });
    res.json({ ok: true });
  });
  app.get('/api/admin/accounts', (req, res) => {
    role(req, 'admin');
    res.json({
      families: all(db, 'SELECT * FROM families ORDER BY created_at'),
      users: all(db, 'SELECT * FROM users ORDER BY created_at').map(publicUser),
    });
  });
  app.post('/api/admin/families', (req, res) => {
    role(req, 'admin');
    const { name } = z.object({ name: str }).parse(req.body);
    const f = { id: uid(), name, created_at: now() };
    insert(db, 'families', f);
    res.status(201).json(f);
  });
  app.post('/api/admin/users', (req, res) => {
    role(req, 'admin');
    const v = z
      .object({
        family_id: id,
        username: z.string().regex(/^[a-zA-Z0-9_-]{3,40}$/),
        name: str,
        role: z.enum(['parent', 'child']),
        password: z.string().min(8).max(200),
      })
      .parse(req.body);
    if (!get(db, 'SELECT id FROM families WHERE id=?', v.family_id)) fail(400, '家庭不存在');
    res.status(201).json(tx(db, () => createUser(db, v)));
  });
  app.patch('/api/admin/users/:userId', (req, res) => {
    role(req, 'admin');
    const u = get(db, 'SELECT * FROM users WHERE id=?', id.parse(req.params.userId));
    if (!u) fail(404, '账号不存在');
    if (u.role === 'admin') fail(400, '管理员请在设置中修改自己的密码');
    const v = z
      .object({
        active: z.boolean().optional(),
        password: z.string().min(8).max(200).optional(),
        name: str.optional(),
      })
      .parse(req.body);
    tx(db, () => {
      run(
        db,
        'UPDATE users SET active=?,password_hash=?,name=? WHERE id=?',
        v.active === undefined ? u.active : Number(v.active),
        v.password ? hashPassword(v.password) : u.password_hash,
        v.name || u.name,
        u.id,
      );
      run(db, 'DELETE FROM sessions WHERE user_id=?', u.id);
    });
    res.json({ ok: true });
  });
  app.get('/api/admin/backup', (req, res) => {
    role(req, 'admin');
    res.setHeader('Content-Disposition', `attachment; filename="star-backup-${today()}.json"`);
    res.json(snapshot(db));
  });
  app.post('/api/admin/restore', (req, res) => {
    role(req, 'admin');
    if (!verifyPassword(String(req.body.password || ''), req.user.password_hash))
      fail(403, '请输入当前管理员密码确认恢复');
    const saved = restore(db, req.body.backup, config.backupDir || './backups');
    res.json({ ok: true, previous_backup: saved, message: '恢复成功，请用备份中的账号重新登录' });
  });
  app.use('/api', (_req, res) => res.status(404).json({ error: '接口不存在' }));
  const dist = resolve('dist');
  if (existsSync(dist)) {
    app.use(express.static(dist));
    app.get('/{*path}', (_req, res) => res.sendFile(resolve(dist, 'index.html')));
  }
  app.use((err, _req, res, _next) => {
    if (err instanceof z.ZodError)
      return res.status(400).json({
        error: '请检查输入内容',
        details: err.issues.map((i) => i.path.join('.') + ': ' + i.message),
      });
    if (err.type === 'entity.too.large')
      return res.status(413).json({ error: '备份文件超过 20MB 上限' });
    if (err instanceof SyntaxError && err.status === 400)
      return res.status(400).json({ error: 'JSON 文件格式不正确' });
    if (err.code?.startsWith('ERR_SQLITE'))
      return res.status(409).json({ error: '数据冲突，请检查账号是否重复、备份引用是否完整' });
    if (!err.status) console.error(err);
    res
      .status(err.status || 500)
      .json({ error: err.status ? err.message : '操作未完成，请检查数据后重试' });
  });
  return { app, db };
}
