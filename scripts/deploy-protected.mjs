import { readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { spawnSync } from 'node:child_process';
import { productionConnection, withProductionBackup } from './production-data.mjs';

const config = parseEnv(readFileSync('.env', 'utf8'));
await withProductionBackup(productionConnection(config), async () => {
  const result = spawnSync('bash', ['scripts/deploy.sh', '--backed-up'], { stdio: 'inherit' });
  if (result.status !== 0) throw new Error('Docker 部署失败；部署前备份已保留');
});
