import { readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { downloadBackup, productionConnection } from './production-data.mjs';

const config = parseEnv(readFileSync('.env', 'utf8'));
await downloadBackup(productionConnection(config), process.argv[2] || 'before-deploy');
