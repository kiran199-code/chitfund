import type { Me } from './types';

/**
 * Which logins may open which screens. Mirrors AccessPolicy.java on the server (the server is the one that enforces it; this only keeps
 * the menu and the buttons tidy). Everyone who signs in can read the chit data, the business date and the holiday list; ADMIN can do everything.
 *
 *  - CHIT_OPS: every change to chit data (create and activate groups, enrol, record and reverse payments, holidays) and the daily "run now" buttons.
 *    CHIT_OPS also owns invitations by e-mail.
 *  - BATCH_OPS: batch jobs, end of day, and the daily "run now" buttons.
 *  - ADMIN only: setting the business date by hand, and Maintenance.
 */
export type Role = 'ADMIN' | 'CHIT_OPS' | 'BATCH_OPS';

/** Screens that need a role. A path not listed here is an inquiry. An empty list means ADMIN only. */
const AREAS: [string, Role[]][] = [
  ['/chit/groups/new', ['CHIT_OPS']],
  ['/batch', ['BATCH_OPS']],
  ['/eod', ['BATCH_OPS']],
  ['/maintenance', []],
];

export function hasRole(me: Me | null, ...roles: Role[]): boolean {
  if (!me) return false;
  const have = me.roles ?? ['ADMIN'];
  return have.includes('ADMIN') || roles.some(r => have.includes(r));
}

export function canOpen(me: Me | null, path: string): boolean {
  if (!me) return false;
  const area = AREAS.find(([p]) => path === p || path.startsWith(`${p}/`));
  if (!area) return true;
  return hasRole(me, ...area[1]);
}

const LABEL: Record<Role, string> = {
  ADMIN: 'Administrator', CHIT_OPS: 'Chit operations', BATCH_OPS: 'Batch operations',
};
export function roleLabel(me: Me | null): string {
  const roles = (me?.roles ?? []) as Role[];
  return roles.map(r => LABEL[r] ?? r).join(', ');
}
