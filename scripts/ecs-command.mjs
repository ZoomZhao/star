import { readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
const c = parseEnv(readFileSync('.env', 'utf8'));
if (!c.ECS_HOST || !c.ECS_PASSWORD) throw new Error('缺少 ECS 连接配置');
const command = process.argv.slice(2).join(' ');
if (!command) throw new Error('缺少远程命令');
const r = spawnSync(
  'ssh',
  [
    '-o',
    'StrictHostKeyChecking=accept-new',
    '-o',
    'PreferredAuthentications=password',
    '-o',
    'PubkeyAuthentication=no',
    '-o',
    'NumberOfPasswordPrompts=1',
    '-o',
    'ConnectTimeout=15',
    '-p',
    c.ECS_SSH_PORT || '22',
    `${c.ECS_USER || 'root'}@${c.ECS_HOST}`,
    command,
  ],
  {
    env: {
      ...process.env,
      SSH_ASKPASS: resolve('scripts/ssh-askpass.mjs'),
      SSH_ASKPASS_REQUIRE: 'force',
      DISPLAY: process.env.DISPLAY || 'codex',
    },
    stdio: 'inherit',
  },
);
process.exit(r.status ?? 1);
