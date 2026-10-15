// Add only the three new presets, retaining existing rules and history.
import templates from '../shared/task-templates.json' with { type: 'json' };
import { openDb, all, seedTemplates, tx } from './db.mjs';
import { snapshot } from './backup.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
if (!process.env.DATABASE_PATH) throw new Error('DATABASE_PATH is required');
const db = openDb(process.env.DATABASE_PATH);
try {
  let children;
  tx(db, () => {
    const dir = process.env.BACKUP_DIR || './backups';
    mkdirSync(dir, { recursive: true });
    const path = join(dir, `before-homework-${Date.now()}.json`);
    writeFileSync(path, JSON.stringify(snapshot(db)), { mode: 0o600, flag: 'wx' });
    console.log('Before-upgrade backup saved: ' + path);
    children = all(db, "SELECT id FROM users WHERE role='child'");
    for (const child of children)
      seedTemplates(
        db,
        child.id,
        templates.filter((t) => t.title === '课内作业'),
      );
  });
  console.log('Homework presets ready for ' + children.length + ' children.');
} finally {
  db.close();
}
