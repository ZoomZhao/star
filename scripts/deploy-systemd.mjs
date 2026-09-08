import { readFileSync, writeFileSync, cpSync, mkdirSync, rmSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
const c = parseEnv(readFileSync('.env', 'utf8'));
if (!c.ECS_PASSWORD || !c.ECS_HOST || !c.ADMIN_PASSWORD)
  throw new Error('缺少 ECS 或应用管理员配置');
const base = c.ECS_APP_DIR || '/opt/star';
if (base !== '/opt/star') throw new Error('systemd 模板当前使用 /opt/star，请同步修改模板后再部署');
const host = c.DOMAIN || c.ECS_HOST;
if (!/^[a-zA-Z0-9.-]+$/.test(host)) throw new Error('无效的域名或 IP');
const sshEnv = {
  ...process.env,
  SSH_ASKPASS: resolve('scripts/ssh-askpass.mjs'),
  SSH_ASKPASS_REQUIRE: 'force',
  DISPLAY: process.env.DISPLAY || 'codex',
};
const sshArgs = [
  '-o',
  'StrictHostKeyChecking=accept-new',
  '-o',
  'NumberOfPasswordPrompts=1',
  '-o',
  'ConnectTimeout=15',
  '-p',
  c.ECS_SSH_PORT || '22',
  `${c.ECS_USER || 'root'}@${c.ECS_HOST}`,
];
function remote(command, input) {
  const r = spawnSync('ssh', [...sshArgs, 'set -eu; ' + command], {
    env: sshEnv,
    input,
    stdio: [input ? 'pipe' : 'ignore', 'inherit', 'inherit'],
  });
  if (r.status !== 0) throw new Error('远程操作失败');
}
const release = new Date()
  .toISOString()
  .replace(/[^0-9]/g, '')
  .slice(0, 14);
mkdirSync('artifacts/runtime', { recursive: true });
const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'));
writeFileSync(
  'artifacts/runtime/package.json',
  JSON.stringify(
    {
      name: 'star-server',
      private: true,
      type: 'module',
      dependencies: Object.fromEntries(
        ['express', 'express-rate-limit', 'helmet', 'zod'].map((k) => [
          k,
          lock.packages['node_modules/' + k].version,
        ]),
      ),
    },
    null,
    2,
  ),
);
const install = spawnSync(
  'npm',
  [
    'install',
    '--prefix',
    'artifacts/runtime',
    '--omit=dev',
    '--ignore-scripts',
    '--no-audit',
    '--no-fund',
  ],
  { stdio: 'inherit' },
);
if (install.status !== 0) throw new Error('准备运行依赖失败');
rmSync('artifacts/runtime/dist', { recursive: true, force: true });
rmSync('artifacts/runtime/server', { recursive: true, force: true });
cpSync('dist', 'artifacts/runtime/dist', { recursive: true });
cpSync('server', 'artifacts/runtime/server', { recursive: true });
cpSync('shared', 'artifacts/runtime/shared', { recursive: true });
const tar = spawnSync('tar', [
  ...(process.platform === 'darwin' ? ['--no-xattrs', '--disable-copyfile'] : []),
  '-czf',
  'artifacts/star-runtime.tgz',
  '-C',
  'artifacts/runtime',
  'package.json',
  'package-lock.json',
  'node_modules',
  'dist',
  'server',
  'shared',
]);
if (tar.status !== 0) throw new Error('打包失败');
remote(
  `umask 022; mkdir -p '${base}/releases/${release}'; tar -xzf - -C '${base}/releases/${release}'`,
  readFileSync('artifacts/star-runtime.tgz'),
);
const env = {
  APP_ENV: 'production',
  HOST: '127.0.0.1',
  PORT: '3001',
  DATABASE_PATH: base + '/data/star.sqlite',
  BACKUP_DIR: base + '/backups',
  ADMIN_USERNAME: c.ADMIN_USERNAME || 'admin',
  ADMIN_PASSWORD: c.ADMIN_PASSWORD,
  APP_ORIGIN: 'https://' + host,
  DEMO_MODE: 'false',
  NODE_OPTIONS: '--max-old-space-size=128',
};
remote(
  `umask 077; cat > '${base}/server.env'`,
  Object.entries(env)
    .map(([k, v]) => k + '=' + JSON.stringify(v))
    .join('\n') + '\n',
);
remote(
  'mkdir -p /etc/star-caddy; cat > /etc/systemd/system/star.service',
  readFileSync('deploy/star.service'),
);
remote('cat > /etc/systemd/system/star-web.service', readFileSync('deploy/star-web.service'));
const isIp = /^\d+\.\d+\.\d+\.\d+$/.test(host);
let caddy = `${isIp ? `{\n  default_sni ${host}\n}\n` : ''}${host} {\n${isIp ? '  tls {\n    issuer acme {\n      profile shortlived\n    }\n  }\n' : ''}  encode gzip\n  reverse_proxy 127.0.0.1:3001\n}\n`;
if (!isIp && /^\d+\.\d+\.\d+\.\d+$/.test(c.ECS_HOST)) {
  caddy =
    `{\n default_sni ${c.ECS_HOST}\n}\n` +
    caddy +
    `\nhttps://${c.ECS_HOST} {\n tls {\n  issuer acme {\n   profile shortlived\n  }\n }\n redir https://${host}{uri} permanent\n}\n`;
}
remote('cat > /etc/star-caddy/Caddyfile', caddy);
remote(
  `id star >/dev/null 2>&1 || useradd --system --home '${base}' --shell /usr/sbin/nologin star; install -d -o star -g star -m 700 '${base}/data' '${base}/backups'; chmod -R a+rX '${base}/releases/${release}'; ln -sfn '${base}/releases/${release}' '${base}/current'; /opt/star/runtime/caddy validate --config /etc/star-caddy/Caddyfile --adapter caddyfile; systemctl daemon-reload; systemctl enable star star-web; systemctl restart star; systemctl restart star-web; systemctl is-active star star-web`,
);
console.log('Deployed release ' + release + '; HTTPS endpoint https://' + host);
writeFileSync(
  'artifacts/deployment.json',
  JSON.stringify({ release, origin: 'https://' + host }, null, 2),
);
