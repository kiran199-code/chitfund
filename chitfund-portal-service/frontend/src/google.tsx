import { useEffect, useRef, useState } from 'react';

// Google Identity Services ("Sign in with Google"). The browser gets an ID token from Google and we pass it to the server, which verifies it.
// The calls below (accounts.id.initialize / renderButton) follow Google's published web API; check them against Google's current docs
// if the button does not appear.

interface GoogleId {
  initialize(config: { client_id: string; callback: (response: { credential: string }) => void; ux_mode?: 'popup' | 'redirect' }): void;
  renderButton(parent: HTMLElement, options: Record<string, unknown>): void;
}
declare global {
  interface Window {
    google?: { accounts: { id: GoogleId } };
  }
}

const SCRIPT_SRC = 'https://accounts.google.com/gsi/client';
let scriptPromise: Promise<void> | null = null;

function loadScript(): Promise<void> {
  if (window.google?.accounts?.id) return Promise.resolve();
  if (!scriptPromise) {
    scriptPromise = new Promise<void>((resolve, reject) => {
      const s = document.createElement('script');
      s.src = SCRIPT_SRC;
      s.async = true;
      s.defer = true;
      s.onload = () => resolve();
      s.onerror = () => {
        scriptPromise = null;
        reject(new Error('load'));
      };
      document.head.appendChild(s);
    });
  }
  return scriptPromise;
}

export default function GoogleButton({ clientId, onCredential }: { clientId: string; onCredential: (idToken: string) => void }) {
  const holder = useRef<HTMLDivElement>(null);
  const latest = useRef(onCredential);
  latest.current = onCredential;
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadScript()
      .then(() => {
        const g = window.google?.accounts?.id;
        if (cancelled || !g || !holder.current) return;
        g.initialize({ client_id: clientId, callback: r => latest.current(r.credential), ux_mode: 'popup' });
        holder.current.innerHTML = '';
        g.renderButton(holder.current, { type: 'standard', theme: 'outline', size: 'large', text: 'signin_with', shape: 'rectangular', logo_alignment: 'left' });
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [clientId]);

  if (failed) {
    return <p className="error">Google sign-in could not be loaded. Check your connection or any blocker for accounts.google.com, then reload the page.</p>;
  }
  return <div ref={holder} className="google-button" aria-label="Sign in with Google" />;
}
