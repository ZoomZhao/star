import { createHash } from 'node:crypto';

// UUID v5: one immutable ledger identity per physical card, family and Shanghai day.
// Existing ledger IDs survive backup/restore, corrections and reversals.
// Keep the namespace and name format stable: changing either would unlock used cards.
export function cardDayLedgerId(familyId, date, cardUid) {
  const bytes = createHash('sha1')
    .update(Buffer.from('6ba7b8109dad11d180b400c04fd430c8', 'hex'))
    .update(`star-task-card:v1:${familyId}:${date}:${cardUid.toUpperCase()}`)
    .digest()
    .subarray(0, 16);
  bytes[6] = (bytes[6] & 15) | 0x50;
  bytes[8] = (bytes[8] & 63) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
