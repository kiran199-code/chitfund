import { ReactNode } from 'react';

// Honest Savers Chit Fund: logo, icons and illustrations. All inline SVG (no image files), drawn in the brand colours.

export const BRAND = { name: 'Honest Savers Chit Fund', short: 'Honest Savers', tagline: 'Honest dealings. Happy savers.' };

export function LogoMark({ size = 40 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" role="img" aria-label="Honest Savers logo">
      <circle cx="24" cy="24" r="24" fill="#0f5c4d" />
      <circle cx="24" cy="18.5" r="9" fill="#f2a93b" />
      <path d="M21 18.5h6M24 15.5v6" stroke="#0f5c4d" strokeWidth="2.2" strokeLinecap="round" />
      <path d="M9.5 29c4.5 1 9 6.5 14.5 6.5S34 30 38.5 29" stroke="#fbf7ef" strokeWidth="3.6" fill="none" strokeLinecap="round" />
    </svg>
  );
}

export function Logo({ compact = false, light = false }: { compact?: boolean; light?: boolean }) {
  return (
    <span className={light ? 'logo logo-light' : 'logo'}>
      <LogoMark size={compact ? 34 : 44} />
      <span className="logo-words">
        <strong>{compact ? BRAND.short : BRAND.name}</strong>
        {!compact && <em>{BRAND.tagline}</em>}
      </span>
    </span>
  );
}

const P: Record<string, ReactNode> = {
  home: <path d="M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10" />,
  ticket: <path d="M3 8a2 2 0 0 0 0 4v0a2 2 0 0 1 0 4v2h18v-2a2 2 0 0 1 0-4v0a2 2 0 0 0 0-4V6H3zM14 6v12" />,
  rupee: <path d="M7 5h10M7 9h10M7 5c6 0 6 8 0 8h-1l8 6" />,
  gavel: <path d="M14 4l6 6-3 3-6-6zM9 9l6 6M4 20l7-7M3 21h9" />,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  gift: <path d="M4 10h16v10H4zM3 7h18v3H3zM12 7v13M12 7c-3 0-5-1-4-3s4 0 4 3zm0 0c3 0 5-1 4-3s-4 0-4 3z" />,
  user: <><circle cx="12" cy="8" r="4" /><path d="M4 21c1-5 4-7 8-7s7 2 8 7" /></>,
  phone: <path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z" />,
  check: <path d="M5 12l5 5 9-10" />,
  alert: <><path d="M12 3l10 18H2z" /><path d="M12 10v5M12 18v.5" /></>,
  more: <><circle cx="5" cy="12" r="1.6" /><circle cx="12" cy="12" r="1.6" /><circle cx="19" cy="12" r="1.6" /></>,
  arrow: <path d="M5 12h14M13 6l6 6-6 6" />,
  out: <path d="M10 4H5v16h5M15 8l4 4-4 4M19 12H9" />,
};

export function Icon({ name, size = 22 }: { name: keyof typeof P | string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {P[name] ?? P.more}
    </svg>
  );
}

const G = '#0f5c4d', GOLD = '#f2a93b', PAPER = '#fbf7ef', INK = '#1e2a28', SKY = '#d9efe9', CLAY = '#e07a5f', PLUM = '#6d597a';

function Person({ x, skin, shirt, hair }: { x: number; skin: string; shirt: string; hair: string }) {
  return (
    <g transform={`translate(${x} 0)`}>
      <path d={`M-34 250c0-44 16-70 34-70s34 26 34 70z`} fill={shirt} />
      <circle cx="0" cy="150" r="24" fill={skin} />
      <path d="M-25 146c2-22 48-22 50 0-12-8-38-8-50 0z" fill={hair} />
      <path d="M-9 158q9 8 18 0" stroke={INK} strokeWidth="2.4" fill="none" strokeLinecap="round" />
      <circle cx="-8" cy="150" r="2" fill={INK} /><circle cx="8" cy="150" r="2" fill={INK} />
    </g>
  );
}

/** Neighbours around a table, passbook and coins: the hero picture. */
export function HeroArt() {
  return (
    <svg viewBox="0 0 480 340" className="art" role="img" aria-label="Friends smiling around a table with a passbook and coins">
      <defs><clipPath id="hero-clip"><rect width="480" height="340" rx="28" /></clipPath></defs>
      <rect width="480" height="340" rx="28" fill={SKY} />
      <g clipPath="url(#hero-clip)">
      <circle cx="395" cy="70" r="40" fill={GOLD} opacity=".9" />
      <path d="M0 250q120-40 240-10t240-20v110H0z" fill="#bfe3d9" />
      <Person x={130} skin="#c68642" shirt={CLAY} hair={INK} />
      <Person x={240} skin="#e0ac69" shirt={G} hair="#3b2a20" />
      <Person x={350} skin="#8d5524" shirt={PLUM} hair={INK} />
      <rect x="40" y="248" width="400" height="22" rx="11" fill="#8a5a33" />
      <rect x="64" y="268" width="14" height="52" fill="#6f4627" /><rect x="402" y="268" width="14" height="52" fill="#6f4627" />
      <rect x="200" y="226" width="80" height="26" rx="4" fill={PAPER} stroke={G} strokeWidth="3" />
      <path d="M240 226v26" stroke={G} strokeWidth="2" /><path d="M210 236h22M210 243h16M250 236h22M250 243h16" stroke={G} strokeWidth="1.6" />
      <ellipse cx="320" cy="248" rx="22" ry="6" fill={GOLD} /><ellipse cx="320" cy="241" rx="22" ry="6" fill="#f7c566" /><ellipse cx="320" cy="234" rx="22" ry="6" fill={GOLD} />
      <ellipse cx="160" cy="247" rx="16" ry="5" fill={GOLD} /><ellipse cx="160" cy="241" rx="16" ry="5" fill="#f7c566" />
      </g>
    </svg>
  );
}

export function CoinsArt() {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label="Piggy bank with coins">
      <rect width="160" height="120" rx="18" fill={SKY} />
      <ellipse cx="80" cy="70" rx="42" ry="30" fill="#e8998d" />
      <circle cx="116" cy="60" r="12" fill="#e8998d" /><circle cx="119" cy="57" r="2" fill={INK} />
      <path d="M58 94v12M98 94v12" stroke="#c97b70" strokeWidth="9" strokeLinecap="round" />
      <path d="M62 44l-6-12 14 6z" fill="#e8998d" />
      <rect x="70" y="50" width="22" height="5" rx="2.5" fill="#b8665a" />
      <circle cx="82" cy="24" r="10" fill={GOLD} /><path d="M78 24h8M82 20v8" stroke="#b97a10" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

export function GavelArt() {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label="Auction gavel and tea cup">
      <rect width="160" height="120" rx="18" fill="#fdeccb" />
      <g transform="rotate(-28 70 50)"><rect x="44" y="30" width="52" height="24" rx="6" fill={G} /><rect x="64" y="52" width="12" height="46" rx="6" fill="#8a5a33" /></g>
      <rect x="22" y="98" width="70" height="9" rx="4.5" fill="#8a5a33" />
      <path d="M104 62h34v14a17 17 0 0 1-34 0z" fill={PAPER} stroke={G} strokeWidth="3" /><path d="M138 66q10 0 10 8t-10 8" stroke={G} strokeWidth="3" fill="none" />
      <path d="M114 54q-3-6 0-10M124 54q-3-6 0-10" stroke={GOLD} strokeWidth="3" fill="none" strokeLinecap="round" />
    </svg>
  );
}

export function LampArt() {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label="Festival lamp">
      <rect width="160" height="120" rx="18" fill="#fde1d8" />
      <path d="M40 72h80c0 22-18 34-40 34S40 94 40 72z" fill={CLAY} />
      <path d="M40 72h80" stroke="#b9553c" strokeWidth="4" />
      <path d="M80 28c14 14 14 26 0 38-14-12-14-24 0-38z" fill={GOLD} /><path d="M80 44c6 7 6 13 0 19-6-6-6-12 0-19z" fill="#fff4d6" />
      <circle cx="30" cy="40" r="3" fill={GOLD} /><circle cx="132" cy="30" r="3" fill={GOLD} /><circle cx="124" cy="52" r="2" fill={GOLD} />
    </svg>
  );
}

export function PassbookArt() {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label="An empty passbook">
      <rect width="160" height="120" rx="18" fill={SKY} />
      <rect x="40" y="22" width="80" height="78" rx="8" fill={G} />
      <rect x="48" y="30" width="64" height="62" rx="4" fill={PAPER} />
      <path d="M56 46h48M56 58h48M56 70h30" stroke="#c9c3b3" strokeWidth="3" strokeLinecap="round" />
      <circle cx="108" cy="82" r="9" fill={GOLD} />
    </svg>
  );
}

export function RelaxArt() {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label="A calm cup of tea">
      <rect width="160" height="120" rx="18" fill={SKY} />
      <path d="M48 58h60v22a30 30 0 0 1-60 0z" fill={PAPER} stroke={G} strokeWidth="3.5" /><path d="M108 64q16 0 16 12t-16 12" stroke={G} strokeWidth="3.5" fill="none" />
      <path d="M66 46q-5-8 0-16M82 46q-5-8 0-16M98 46q-5-8 0-16" stroke={GOLD} strokeWidth="3.5" fill="none" strokeLinecap="round" />
      <path d="M20 104c10-14 22-14 34 0z" fill="#7fbfa9" /><path d="M118 106c8-12 18-12 26 0z" fill="#7fbfa9" />
    </svg>
  );
}

export function ConfettiArt() {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label="Celebration">
      <rect width="160" height="120" rx="18" fill="#fdeccb" />
      <circle cx="80" cy="62" r="28" fill={GOLD} /><path d="M70 62l8 9 14-17" stroke={G} strokeWidth="6" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      {[[24, 24, CLAY], [132, 28, G], [30, 92, PLUM], [128, 90, CLAY], [100, 16, G], [50, 14, GOLD]].map(([x, y, c], i) => (
        <rect key={i} x={x as number} y={y as number} width="10" height="10" rx="2" fill={c as string} transform={`rotate(${i * 25} ${x} ${y})`} />
      ))}
    </svg>
  );
}

/** A cover picture for a group: stable pattern and colour chosen from its code, so the same group always looks the same. */
export function GroupCover({ code }: { code: string }) {
  let h = 0;
  for (let i = 0; i < code.length; i++) h = (h * 31 + code.charCodeAt(i)) >>> 0;
  const palettes = [[G, GOLD], ['#14507a', '#f2a93b'], ['#7a3b2e', '#f2cf8a'], ['#4a3f7a', '#f2a93b'], ['#2d6a4f', '#f4d58d']];
  const [a, b] = palettes[h % palettes.length];
  return (
    <svg viewBox="0 0 400 110" preserveAspectRatio="xMidYMid slice" className="cover" aria-hidden="true">
      <rect width="400" height="110" fill={a} />
      {[0, 1, 2, 3, 4, 5].map(i => (
        <circle key={i} cx={30 + ((h >> (i + 2)) % 340)} cy={20 + ((h >> (i + 5)) % 70)} r={14 + ((h >> i) % 26)} fill={b} opacity={0.16 + (i % 3) * 0.08} />
      ))}
      <circle cx="350" cy="55" r="30" fill={b} /><path d="M338 55h24M350 43v24" stroke={a} strokeWidth="5" strokeLinecap="round" />
    </svg>
  );
}
