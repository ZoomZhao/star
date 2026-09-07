import { readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
const c = parseEnv(readFileSync('.env', 'utf8'));
if (!c.ECS_HOST || !c.ECS_PASSWORD) throw new Error('请填写 ECS_HOST 和 ECS_PASSWORD');
const args = [
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
  'uname -m; cat /etc/os-release; id; command -v docker; docker compose version 2>/dev/null; ss -lntp; df -h /; free -m',
];
const result = spawnSync('ssh', args, {
  env: {
    ...process.env,
    SSH_ASKPASS: resolve('scripts/ssh-askpass.mjs'),
    SSH_ASKPASS_REQUIRE: 'force',
    DISPLAY: process.env.DISPLAY || 'codex',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
  encoding: 'utf8',
  timeout: 45000,
});
process.stdout.write(result.stdout || '');
process.stderr.write(result.stderr || '');
process.exit(result.status ?? 1);
