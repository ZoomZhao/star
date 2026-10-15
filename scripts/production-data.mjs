import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { isDeepStrictEqual } from 'node:util';
import { backupTables, validateBackup } from '../server/backup.mjs';

const rowKey = (table, row) =>
  table === 'task_order' ? `${row.child_id}:${row.rule_key}` : row.id;

function indexBackup(backup) {
  if (backup?.format !== 'star-explorer' || backup.version !== 1 || !backup.data)
    throw new Error('不是有效的完整备份');
  if (Object.keys(backup.data).some((table) => !backupTables.includes(table)))
    throw new Error('备份包含未识别的数据集合，请更新核对工具');
  return Object.fromEntries(
    backupTables.map((table) => {
      const rows = backup.data[table];
      if (!Array.isArray(rows)) throw new Error(`备份缺少 ${table} 集合`);
      const index = new Map();
      for (const row of rows) {
        const fields = table === 'task_order' ? ['child_id', 'rule_key'] : ['id'];
        if (!row || fields.some((field) => typeof row[field] !== 'string' || !row[field]))
          throw new Error(`${table} 含无效记录 ID`);
        const key = rowKey(table, row);
        if (index.has(key)) throw new Error(`${table} 含重复记录 ID`);
        index.set(key, row);
      }
      return [table, index];
    }),
  );
}

export function readBackup(path) {
  const backup = JSON.parse(readFileSync(path, 'utf8'));
  indexBackup(backup);
  return backup;
}

export function compareBackups(before, after) {
  const oldTables = indexBackup(before),
    newTables = indexBackup(after);
  return backupTables.map((table) => {
    const oldRows = oldTables[table],
      newRows = newTables[table];
    return {
      table,
      count: oldRows.size,
      missing: [...oldRows.keys()].filter((id) => !newRows.has(id)),
      added: [...newRows.keys()].filter((id) => !oldRows.has(id)),
      changed: [...oldRows.keys()].filter(
        (id) => newRows.has(id) && !isDeepStrictEqual(oldRows.get(id), newRows.get(id)),
      ),
    };
  });
}

export function verifyBackups(before, after, log = console.log) {
  const report = compareBackups(before, after);
  const differences = report.filter((r) => r.missing.length || r.added.length || r.changed.length);
  if (differences.length) {
    // Log identities only: backups include password hashes and private family data.
    const detail = differences
      .map(
        (r) =>
          `${r.table}: 缺少 ${r.missing.slice(0, 5).join(', ') || '无'}；` +
          `新增 ${r.added.slice(0, 5).join(', ') || '无'}；` +
          `变化 ${r.changed.slice(0, 5).join(', ') || '无'}`,
      )
      .join('\n');
    throw new Error(
      `${detail}\n部署前后数据存在差异；请先判断是否为用户并发操作，不要自动恢复旧备份`,
    );
  }
  for (const r of report) log(`${r.table}: ${r.count} 条记录完整一致`);
  log(
    `自定义每日上限 10 次规则：${before.data.rules.filter((r) => r.daily_limit === 10).length} 条完整保留`,
  );
  log('部署前后全部生产数据按记录 ID 核对一致');
}

export function productionConnection(config) {
  const host = config.DOMAIN || config.ECS_HOST;
  if (!host || !/^[a-zA-Z0-9.-]+$/.test(host) || !config.ADMIN_PASSWORD)
    throw new Error('缺少有效的生产域名或管理员配置');
  return {
    origin: `https://${host}`,
    username: config.ADMIN_USERNAME || 'admin',
    password: config.ADMIN_PASSWORD,
  };
}

export async function downloadBackup(connection, label, options = {}) {
  if (!/^[a-z0-9-]+$/i.test(label)) throw new Error('备份标签只能包含字母、数字和连字符');
  const { directory = 'artifacts', fetchImpl = fetch, log = console.log } = options;
  const { origin, username, password } = connection;
  const request = (path, init = {}) =>
    fetchImpl(origin + path, { ...init, signal: AbortSignal.timeout(30000) });
  const login = await request('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!login.ok) throw new Error('管理员登录失败，无法保存完整生产备份');
  const { token } = await login.json();
  if (typeof token !== 'string' || !token) throw new Error('管理员登录响应异常');
  const headers = { Authorization: `Bearer ${token}` };
  try {
    const response = await request('/api/admin/backup', { headers });
    if (!response.ok) throw new Error('完整生产备份下载失败');
    const backup = await response.json();
    indexBackup(backup);
    try {
      validateBackup(backup);
    } catch {
      throw new Error('生产备份未通过完整恢复校验，部署已中止');
    }
    mkdirSync(directory, { recursive: true });
    const path = join(directory, `${label}-${Date.now()}-${randomUUID()}.json`);
    writeFileSync(path, JSON.stringify(backup), { mode: 0o600, flag: 'wx' });
    log('完整生产备份已保存：' + path);
    return path;
  } finally {
    try {
      const response = await request('/api/logout', { method: 'POST', headers });
      if (!response.ok) throw new Error();
    } catch {
      log('备份会话退出未确认；已保存的备份不受影响');
    }
  }
}

export async function withProductionBackup(connection, deploy, options = {}) {
  const { fetchImpl = fetch, log = console.log } = options;
  const beforePath = await downloadBackup(connection, 'before-deploy', options);
  await deploy();
  let healthy = false;
  for (let attempt = 0; attempt < 30; attempt++) {
    try {
      const response = await fetchImpl(connection.origin + '/api/health', {
        signal: AbortSignal.timeout(3000),
      });
      if (response.ok && (await response.json()).ok === true) {
        healthy = true;
        break;
      }
    } catch {
      /* Give a restarting service time to become ready. */
    }
    await delay(1000);
  }
  if (!healthy) throw new Error(`部署后服务未就绪；部署前备份保留在 ${beforePath}`);
  const afterPath = await downloadBackup(connection, 'after-deploy', options);
  verifyBackups(readBackup(beforePath), readBackup(afterPath), log);
  return { beforePath, afterPath };
}
