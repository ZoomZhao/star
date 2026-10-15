import { createHash } from 'node:crypto';

// UUID v5, using the DNS namespace. Callers own a versioned name prefix.
// These identifiers persist in backups: never change the namespace or name format.
export function namedId(name) {
  const bytes = createHash('sha1')
    .update(Buffer.from('6ba7b8109dad11d180b400c04fd430c8', 'hex'))
    .update(name)
    .digest()
    .subarray(0, 16);
  bytes[6] = (bytes[6] & 15) | 0x50;
  bytes[8] = (bytes[8] & 63) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
