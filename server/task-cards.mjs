import { z } from 'zod';
import { get, run, insert, tx, uid, now, today, materialize, publicUser } from './db.mjs';
import { cardDayLedgerId } from './card-identity.mjs';

const physicalUid = z
  .string()
  .regex(/^(?:[0-9a-f]{8}|[0-9a-f]{14}|[0-9a-f]{20})$/i, '缺少有效的实体卡编号，请更新 App 后重试')
  .transform((s) => s.toUpperCase());

/** Card claims and corrections are atomic; rules and daily snapshots remain immutable. */
export function registerTaskCardRoutes({ app, db, role, child, key, postLedger, fail }) {
  app.get('/api/children/:childId/task-cards/:ruleKey', (req, res) => {
    role(req, 'parent', 'admin');
    const c = child(req, req.params.childId);
    const ruleKey = z.string().uuid().parse(req.params.ruleKey);
    const cardUid = physicalUid.optional().parse(req.query.card_uid);
    const result = tx(db, () => {
      const rule = get(
        db,
        'SELECT * FROM rules WHERE child_id=? AND rule_key=? ORDER BY version DESC LIMIT 1',
        c.id,
        ruleKey,
      );
      if (!rule) fail(404, '未找到这张卡绑定的任务，请检查当前登录的家庭和服务器');
      const date = today();
      const todayRule = get(
        db,
        'SELECT * FROM rules WHERE child_id=? AND rule_key=? AND effective_from<=? ORDER BY version DESC LIMIT 1',
        c.id,
        ruleKey,
        date,
      );
      const task = get(
        db,
        `SELECT t.*,
        (SELECT COALESCE(SUM(quantity),0) FROM submissions s WHERE s.task_id=t.id AND s.status='approved') approved,
        (SELECT COALESCE(SUM(quantity),0) FROM submissions s WHERE s.task_id=t.id AND s.status='pending') pending
        FROM tasks t WHERE child_id=? AND rule_key=? AND date=?`,
        c.id,
        ruleKey,
        date,
      );
      const scheduled =
        !!todayRule?.enabled &&
        (todayRule.schedule === 'daily' ||
          (todayRule.schedule === 'once' && todayRule.on_date === date) ||
          (todayRule.schedule === 'weekly' &&
            JSON.parse(todayRule.weekdays).includes(
              new Date(date + 'T12:00:00+08:00').getUTCDay(),
            )));
      // Deliberately do not materialize snapshots, approve submissions, or write ledger rows.
      return {
        child: publicUser(c),
        rule: { ...rule, weekdays: JSON.parse(rule.weekdays) },
        today: date,
        today_task: task || null,
        today_rule: todayRule ? { ...todayRule, weekdays: JSON.parse(todayRule.weekdays) } : null,
        scheduled_today: !!task || scheduled,
        card_uid: cardUid || null,
        claimed_today: cardUid
          ? !!get(
              db,
              'SELECT id FROM ledger WHERE id=?',
              cardDayLedgerId(c.family_id, date, cardUid),
            )
          : null,
      };
    });
    res.json(result);
  });

  app.post('/api/children/:childId/task-cards/claim', (req, res) => {
    role(req, 'parent', 'admin');
    const c = child(req, req.params.childId);
    const v = z
      .object({
        rule_key: z.string().uuid(),
        date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
        card_uid: physicalUid,
      })
      .parse(req.body);
    const requestKey = 'card:' + key(req);
    const entryId = cardDayLedgerId(c.family_id, v.date, v.card_uid);
    const receipt = (entry, replayed, alreadyClaimed) => {
      const submission = get(db, 'SELECT * FROM submissions WHERE id=?', entry.submission_id);
      const task = get(db, 'SELECT * FROM tasks WHERE id=?', submission.task_id);
      // Resolve corrections to the current award while retaining the original daily identity.
      let current = entry;
      for (;;) {
        const reversal = get(db, 'SELECT * FROM ledger WHERE reversal_of=?', current.id);
        if (!reversal?.request_key.startsWith('award-adjust-reversal:')) break;
        const next = get(
          db,
          'SELECT * FROM ledger WHERE request_key=?',
          reversal.request_key.replace('award-adjust-reversal:', 'award-adjust:'),
        );
        if (!next) break;
        current = next;
      }
      return {
        entry: current,
        submission,
        task,
        child: publicUser(get(db, 'SELECT * FROM users WHERE id=?', task.child_id)),
        card_uid: v.card_uid,
        replayed,
        already_claimed: alreadyClaimed,
      };
    };
    const result = tx(db, () => {
      const existing = get(db, 'SELECT * FROM ledger WHERE request_key=?', requestKey);
      if (existing) {
        const submission = get(db, 'SELECT * FROM submissions WHERE id=?', existing.submission_id);
        const task = get(db, 'SELECT * FROM tasks WHERE id=?', submission.task_id);
        if (
          existing.id !== entryId ||
          task.child_id !== c.id ||
          task.rule_key !== v.rule_key ||
          task.date !== v.date
        )
          fail(409, '操作编号已用于其他内容，请刷新后重试');
        return receipt(existing, true, false);
      }
      if (v.date !== today()) fail(400, '日期已变化，请重新碰卡读取今天的任务');
      const consumed = get(db, 'SELECT * FROM ledger WHERE id=?', entryId);
      if (consumed) return receipt(consumed, false, true);
      materialize(db, c.id, v.date);
      const task = get(
        db,
        'SELECT * FROM tasks WHERE child_id=? AND rule_key=? AND date=?',
        c.id,
        v.rule_key,
        v.date,
      );
      if (!task) fail(404, '今天没有安排这张卡对应的任务，请检查任务安排和启停状态');
      const pending = get(
        db,
        "SELECT * FROM submissions WHERE task_id=? AND status='pending' ORDER BY created_at,rowid LIMIT 1",
        task.id,
      );
      const used = get(
        db,
        "SELECT COALESCE(SUM(quantity),0) n FROM submissions WHERE task_id=? AND status IN ('pending','approved')",
        task.id,
      ).n;
      if (used + (pending ? 0 : 1) > task.daily_limit) fail(409, '今日次数已用完（含待确认次数）');
      let submission;
      if (pending) {
        run(
          db,
          "UPDATE submissions SET status='approved',review_note=?,reviewed_by=?,reviewed_at=?,bonus=0,deduction=0,unit_stars=? WHERE id=?",
          '任务卡确认',
          req.user.id,
          now(),
          task.stars,
          pending.id,
        );
        submission = get(db, 'SELECT * FROM submissions WHERE id=?', pending.id);
      } else {
        submission = {
          id: uid(),
          task_id: task.id,
          child_id: c.id,
          status: 'approved',
          bonus: 0,
          deduction: 0,
          quantity: 1,
          unit_stars: task.stars,
          note: '任务卡确认',
          review_note: '',
          reviewed_by: req.user.id,
          created_at: now(),
          reviewed_at: now(),
          request_key: requestKey,
        };
        insert(db, 'submissions', submission);
      }
      const entry = postLedger({
        entryId,
        childId: c.id,
        amount: task.stars * submission.quantity,
        kind: 'task',
        title: task.title,
        note: `任务卡确认：${task.stars} 星 × ${submission.quantity} 次`,
        date: task.date,
        actor: req.user.id,
        submission: submission.id,
        requestKey,
      });
      return receipt(entry, false, false);
    });
    res.json(result);
  });

  app.post('/api/ledger/:ledgerId/adjust', (req, res) => {
    role(req, 'parent', 'admin');
    const original = get(
      db,
      'SELECT * FROM ledger WHERE id=?',
      z.string().uuid().parse(req.params.ledgerId),
    );
    if (!original) fail(404, '流水不存在');
    child(req, original.child_id);
    const v = z
      .object({
        amount: z.number().int().min(1).max(2020),
        expected_amount: z.number().int().min(1).max(2020),
      })
      .parse(req.body);
    const operation = key(req);
    const result = tx(db, () => {
      const existing = get(
        db,
        'SELECT * FROM ledger WHERE request_key=?',
        'award-adjust:' + operation,
      );
      if (existing) {
        const reversal = get(
          db,
          'SELECT * FROM ledger WHERE request_key=?',
          'award-adjust-reversal:' + operation,
        );
        if (
          reversal?.reversal_of !== original.id ||
          existing.amount !== v.amount ||
          original.amount !== v.expected_amount
        )
          fail(409, '操作编号已用于其他内容，请刷新后重试');
        return { entry: existing, previous_entry_id: original.id };
      }
      const editable =
        original.amount > 0 &&
        (original.kind === 'task' ||
          (original.kind === 'bonus' && original.request_key.startsWith('award-adjust:')));
      if (!editable || get(db, 'SELECT id FROM ledger WHERE reversal_of=?', original.id))
        fail(409, '这笔积分已修改或撤销，请刷新后选择最新记录');
      if (original.amount !== v.expected_amount) fail(409, '积分已变化，请刷新后重试');
      if (original.amount === v.amount) return { entry: original, previous_entry_id: original.id };
      // Insert the replacement first, so only the final balance must be nonnegative.
      // Both rows commit together or roll back together, including insufficient funds.
      const entry = postLedger({
        childId: original.child_id,
        amount: v.amount,
        kind: 'bonus',
        title: original.title,
        note: `本次积分修改：${original.amount} → ${v.amount} 星；原记录 ${original.id}`,
        date: original.date,
        actor: req.user.id,
        requestKey: 'award-adjust:' + operation,
      });
      postLedger({
        childId: original.child_id,
        amount: -original.amount,
        kind: 'reversal',
        title: '撤销：' + original.title,
        note: `修改本次积分，替换为 ${v.amount} 星；新记录 ${entry.id}`,
        date: original.date,
        actor: req.user.id,
        reversal: original.id,
        requestKey: 'award-adjust-reversal:' + operation,
      });
      return { entry, previous_entry_id: original.id };
    });
    res.json(result);
  });
}
