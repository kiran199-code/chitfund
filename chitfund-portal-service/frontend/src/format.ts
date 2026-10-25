export function rupees(n: number | null | undefined): string {
  if (n === null || n === undefined) return '';
  return 'Rs. ' + n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function dateOnly(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

/** Whole rupees for tiles and lists ("Rs. 5,100"); amounts with paise keep them. */
export function rupeesShort(n: number | null | undefined): string {
  if (n === null || n === undefined) return '';
  const whole = Number.isInteger(n);
  return 'Rs. ' + n.toLocaleString('en-IN', { minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 });
}

export function titleCase(s: string | null | undefined): string {
  if (!s) return '';
  const t = s.replace(/_/g, ' ').toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/** A new idempotency key; randomUUID needs a secure context so fall back for plain http. */
export function newKey(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

/** Only http(s) addresses are ever put in a link or picture; the server checks too, this is the second lock. */
export function safeUrl(u: string | null | undefined): string | undefined {
  if (!u) return undefined;
  try {
    const x = new URL(u);
    return x.protocol === 'https:' || x.protocol === 'http:' ? x.toString() : undefined;
  } catch {
    return undefined;
  }
}
