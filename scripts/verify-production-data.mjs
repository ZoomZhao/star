import { readFileSync } from 'node:fs';

const [beforePath, afterPath] = process.argv.slice(2);
if (!beforePath || !afterPath)
  throw new Error('用法：node scripts/verify-production-data.mjs <部署前备份> <部署后备份>');

const read = (path) => {
  const backup = JSON.parse(readFileSync(path, 'utf8'));
  if (backup.format !== 'star-explorer' || backup.version !== 1 || !backup.data)
    throw new Error(`${path} 不是有效的完整备份`);
  return backup.data;
};
const before = read(beforePath);
const after = read(afterPath);
const tables = [
  'families',
  'users',
  'rules',
  'tasks',
  'submissions',
  'rewards',
  'redemptions',
  'ledger',
  'task_order',
];
const key = (table, row) => (table === 'task_order' ? `${row.child_id}:${row.rule_key}` : row.id);
const canonical = (value) =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((name) => [name, canonical(value[name])]),
        )
      : value;
const equal = (left, right) => JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));

const differences = [];
for (const table of tables) {
  if (!Array.isArray(before[table]) || !Array.isArray(after[table]))
    throw new Error(`备份缺少 ${table} 集合`);
  const oldRows = new Map(before[table].map((row) => [key(table, row), row]));
  const newRows = new Map(after[table].map((row) => [key(table, row), row]));
  const missing = [...oldRows.keys()].filter((id) => !newRows.has(id));
  const added = [...newRows.keys()].filter((id) => !oldRows.has(id));
  const changed = [...oldRows.keys()].filter(
    (id) => newRows.has(id) && !equal(oldRows.get(id), newRows.get(id)),
  );
  if (missing.length || added.length || changed.length)
    differences.push({ table, missing, added, changed });
  else console.log(`${table}: ${oldRows.size} 条记录完整一致`);
}

const customCaps = before.rules.filter((rule) => rule.daily_limit === 10);
for (const rule of customCaps) {
  const current = after.rules.find((candidate) => candidate.id === rule.id);
  if (!current || !equal(rule, current))
    throw new Error(`自定义每日上限规则 ${rule.id} 未完整保留`);
}
console.log(`自定义每日上限 10 次规则：${customCaps.length} 条完整保留`);

if (differences.length) {
  for (const difference of differences)
    console.error(
      `${difference.table}: 缺少 ${difference.missing.slice(0, 5).join(', ') || '无'}；` +
        `新增 ${difference.added.slice(0, 5).join(', ') || '无'}；` +
        `变化 ${difference.changed.slice(0, 5).join(', ') || '无'}`,
    );
  throw new Error('部署前后数据存在差异；请先判断是否为用户并发操作，不要自动恢复旧备份');
}

console.log('部署前后全部生产数据按记录 ID 核对一致');
