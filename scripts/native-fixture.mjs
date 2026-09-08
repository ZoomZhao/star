import { createApp } from '../server/app.mjs';
const { app } = createApp({
  databasePath: ':memory:',
  demo: true,
  adminPassword: 'native-test-admin',
  origins: [],
});
app.listen(3004, '127.0.0.1', () => console.log('Isolated native fixture: http://127.0.0.1:3004'));
