import { readBackup, verifyBackups } from './production-data.mjs';

const [beforePath, afterPath] = process.argv.slice(2);
if (!beforePath || !afterPath)
  throw new Error('用法：node scripts/verify-production-data.mjs <部署前备份> <部署后备份>');
verifyBackups(readBackup(beforePath), readBackup(afterPath));
