// Explicit one-time upgrade command; never resets accounts, balances, or task history.
import { openDb, all, seedTemplates, tx } from './db.mjs';
import { snapshot } from './backup.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
if (!process.env.DATABASE_PATH) throw new Error('DATABASE_PATH is required');
const db = openDb(process.env.DATABASE_PATH);
try {
  const dir = process.env.BACKUP_DIR || './backups';
  mkdirSync(dir, { recursive: true });
  const path = join(dir, 'before-subject-presets-' + Date.now() + '.json');
  writeFileSync(path, JSON.stringify(snapshot(db)), { mode: 0o600, flag: 'wx' });
  const children = all(db, "SELECT id FROM users WHERE role='child'");
  tx(db, () => {
    for (const child of children) seedTemplates(db, child.id);
  });
  console.log(
    'Subject presets ready for ' +
      children.length +
      ' children; accounts and balances retained. Backup: ' +
      path,
  );
} finally {
  db.close();
}
