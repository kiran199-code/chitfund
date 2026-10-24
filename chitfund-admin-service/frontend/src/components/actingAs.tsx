import { useState, useSyncExternalStore } from 'react';
import { getActingAs, setActingAs, subscribeActingAs } from '../api';
import { useAuth } from '../auth';
import type { Notice } from './ops';
import { Card } from './ui';

/** The name that will be recorded against what is done: the one typed as "acting as", else the login name. Re-renders on change. */
export function useActingAs(): string {
  const { me } = useAuth();
  const typed = useSyncExternalStore(subscribeActingAs, getActingAs);
  return typed.trim() || me?.username || '';
}

export const validPerson = (v: string) => /^[A-Za-z0-9._@-]{2,40}$/.test(v.trim());

export function NoticeBanner({ notice }: { notice: Notice }) {
  if (!notice) return null;
  return <div className={`banner banner-${notice.tone}`} role={notice.tone === 'bad' ? 'alert' : 'status'}>{notice.text}</div>;
}

/**
 * The console has one login per role, so the person says who they are acting as. The chit service records that name against groups,
 * payments, reversals and holidays. The names are typed text, not verified identities.
 */
export function ActingAsBar() {
  const { me } = useAuth();
  const [name, setName] = useState(getActingAs());
  const bad = name.trim() !== '' && !validPerson(name);
  return (
    <Card title="Acting as">
      <div className="filters">
        <input value={name} placeholder={me?.username ? `Defaults to ${me.username}` : 'Your name'} aria-label="Acting as"
          onChange={e => { setName(e.target.value); setActingAs(e.target.value); }} />
        <span className="muted">The name recorded against what you do here.</span>
      </div>
      {bad && <div className="banner banner-bad" role="alert">Use 2 to 40 letters, digits, dot, dash, underscore or @.</div>}
      <p className="muted" style={{ marginBottom: 0 }}>
        These names are typed, not verified: the console has one login per role, so one person could type any name. Treat it as a record
        of who did what until sign-in by person (IAM) is in place.
      </p>
    </Card>
  );
}
