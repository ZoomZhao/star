import { namedId } from './identity.mjs';

// UUID v5: one immutable ledger identity per physical card, family and Shanghai day.
// Existing ledger IDs survive backup/restore, corrections and reversals.
// Keep the namespace and name format stable: changing either would unlock used cards.
export function cardDayLedgerId(familyId, date, cardUid) {
  return namedId(`star-task-card:v1:${familyId}:${date}:${cardUid.toUpperCase()}`);
}
