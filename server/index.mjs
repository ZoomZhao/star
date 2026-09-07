const environment = process.env.APP_ENV || process.env.NODE_ENV || 'development';
import { createApp } from './app.mjs';
const demo = process.env.DEMO_MODE === 'true';
if (environment === 'production' && demo) throw new Error('生产环境禁止开启演示账号');
const origins = [
  process.env.APP_ORIGIN,
  'https://localhost',
  'capacitor://localhost',
  ...(environment !== 'production'
    ? [
        'http://localhost:5173',
        'http://127.0.0.1:5173',
        'http://localhost:3001',
        'http://127.0.0.1:3001',
        'http://localhost',
      ]
    : []),
].filter(Boolean);
const { app, db } = createApp({
  databasePath: process.env.DATABASE_PATH,
  backupDir: process.env.BACKUP_DIR,
  adminUsername: process.env.ADMIN_USERNAME,
  adminPassword: process.env.ADMIN_PASSWORD,
  demo,
  origins,
  trustProxy: environment === 'production',
});
const server = app.listen(Number(process.env.PORT || 3001), process.env.HOST || '127.0.0.1', () =>
  console.log('Star server ready on port ' + (process.env.PORT || 3001)),
);
for (const signal of ['SIGTERM', 'SIGINT'])
  process.on(signal, () =>
    server.close(() => {
      db.close();
      process.exit(0);
    }),
  );
