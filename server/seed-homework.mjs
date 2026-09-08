// Add only the three new presets, retaining existing rules and history.
import templates from '../shared/task-templates.json' with { type: 'json' };
import { openDb, all, seedTemplates, tx } from './db.mjs';
if (!process.env.DATABASE_PATH) throw new Error('DATABASE_PATH is required');
const db = openDb(process.env.DATABASE_PATH);
try {
  const children = all(db, "SELECT id FROM users WHERE role='child'");
  tx(db, () => {
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
