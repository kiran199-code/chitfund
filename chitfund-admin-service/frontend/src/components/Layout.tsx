import { useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { get } from '../api';
import { canOpen, roleLabel } from '../access';
import { useAuth } from '../auth';
import { date } from '../format';
import { useLoad } from '../hooks';
import type { BusinessDateView } from '../types';

/** Menu sections. A section with no title (Overview) is always shown; the others fold. */
const SECTIONS: { title: string | null; items: [string, string][] }[] = [
  { title: null, items: [['/', 'Overview']] },
  { title: 'Chit funds', items: [['/chit/groups', 'Groups'], ['/chit/groups/new', 'New group'], ['/chit/dues', 'Dues & payments']] },
  { title: 'Customer portal', items: [['/offers', 'Offers']] },
  { title: 'Calendar', items: [['/calendar', 'Business date & holidays']] },
  { title: 'Batch', items: [['/batch', 'Batch jobs'], ['/eod', 'End of day']] },
  { title: 'Administration', items: [['/maintenance', 'Maintenance']] },
];

const FOLD_KEY = 'chitfund-admin-menu-closed';
function readClosed(): string[] {
  try { return JSON.parse(sessionStorage.getItem(FOLD_KEY) ?? '[]') as string[]; } catch { return []; }
}
function writeClosed(v: string[]) {
  try { sessionStorage.setItem(FOLD_KEY, JSON.stringify(v)); } catch { /* storage blocked */ }
}

/** Whether a menu path is the page being shown (a group's detail page belongs to Groups). */
function inSection(items: [string, string][], path: string): boolean {
  return items.some(([to]) => (to === '/' ? path === '/' : path === to || path.startsWith(`${to}/`)));
}

export default function Layout() {
  const { me, logout } = useAuth();
  const bd = useLoad(() => get<BusinessDateView>('/business-date'), []);
  const b = bd.data;
  const { pathname } = useLocation();
  const [closed, setClosed] = useState<string[]>(readClosed);
  function toggle(title: string) {
    const next = closed.includes(title) ? closed.filter(t => t !== title) : [...closed, title];
    setClosed(next);
    writeClosed(next);
  }
  return (
    <div className="shell">
      <aside className="side">
        <div className="brand">Chitfund Admin</div>
        <nav aria-label="Main menu">
          {SECTIONS.map(sec => ({ ...sec, items: sec.items.filter(([to]) => canOpen(me, to)) })).filter(sec => sec.items.length > 0).map(s => {
            const links = (
              s.items.map(([to, text]) => (
                <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive && !(pathname !== to && s.items.some(([o]) => o === pathname)) ? 'nav active' : 'nav')}>
                  {text}
                </NavLink>
              ))
            );
            if (!s.title) return <div key="overview" className="nav-group">{links}</div>;
            // The section holding the current page stays open so the active entry is never hidden.
            const open = !closed.includes(s.title) || inSection(s.items, pathname);
            return (
              <div key={s.title} className="nav-group">
                <button type="button" className="nav-head" aria-expanded={open} onClick={() => toggle(s.title!)}>
                  <span>{s.title}</span><span aria-hidden="true" className="nav-caret">{open ? '▾' : '▸'}</span>
                </button>
                {open && <div className="nav-items">{links}</div>}
              </div>
            );
          })}
        </nav>
        {b && (
          <div className="side-foot" title={b.mode === 'SYSTEM' ? 'The chit service follows the wall-clock date' : 'Set by the chit service; moves when end of day closes it'}>
            <div className="muted">Business date</div>
            <strong>{date(b.businessDate)}</strong>
            <div className="muted">
              {b.mode === 'SYSTEM' ? 'wall clock' : b.status === 'EOD_RUNNING' ? 'end of day running' : 'open'}
            </div>
          </div>
        )}
        <div className="side-foot">
          <div className="muted">Signed in as</div>
          <strong>{me?.username}</strong>
          {roleLabel(me) && <div className="muted">{roleLabel(me)}</div>}
          <button className="btn btn-ghost" onClick={logout}>Sign out</button>
        </div>
      </aside>
      <main className="main">
        {canOpen(me, pathname)
          ? <Outlet />
          : <div className="card"><h2>No access</h2><p>Your login ({roleLabel(me) || me?.username}) does not have access to this screen. Ask an administrator if you need it.</p></div>}
      </main>
    </div>
  );
}
