import { ReactNode } from 'react';

// Honest Savers Chit Fund: logo, icons and illustrations. All inline SVG (no image files), drawn in the brand colours.

export const BRAND = { name: 'Honest Savers Chit Fund', short: 'Honest Savers', tagline: 'Honest dealings. Happy savers.' };

export function LogoMark({ size = 40 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" role="img" aria-label="Honest Savers logo">
      <defs>
        <linearGradient id="lm-r" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#ff5a60" /><stop offset="1" stopColor="#a9121a" /></linearGradient>
      </defs>
      <circle cx="24" cy="24" r="23" fill="url(#lm-r)" stroke="#c9cbcf" strokeWidth="2" />
      <circle cx="24" cy="19" r="9.5" fill="#fff" />
      <circle cx="24" cy="19" r="7.2" fill="none" stroke="#eceef0" strokeWidth="1.2" />
      <path d="M19.5 19l3.2 3.2 5.8-6.4" stroke="#d71920" strokeWidth="2.6" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.5 31q14.5 12 29 0" stroke="#c9cbcf" strokeWidth="3.6" fill="none" strokeLinecap="round" />
      <path d="M13 36.5q11 7 22 0" stroke="#8b8f94" strokeWidth="2" fill="none" strokeLinecap="round" opacity=".8" />
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

const R = '#d71920', RL = '#ff5a60', RD = '#8f0f15', K = '#111214', K2 = '#1d1f23', K3 = '#2b2d31', G1 = '#4a4d52', G2 = '#8b8f94', G3 = '#c9cbcf', G4 = '#eceef0', W = '#ffffff', INK = '#15161a';

function Person({ x, skin, shade, shirt, hair }: { x: number; skin: string; shade: string; shirt: string; hair: string }) {
  return (
    <g transform={`translate(${x} 0)`}>
      <ellipse cx="0" cy="252" rx="52" ry="8" fill="#000" opacity=".35" />
      <path d="M-36 252c0-46 16-72 36-72s36 26 36 72z" fill={shirt} />
      <path d="M-36 252c0-46 16-72 36-72-14 14-18 46-14 72z" fill="#fff" opacity=".12" />
      <path d="M-12 182l12 14 12-14" fill="none" stroke="#fff" strokeWidth="2.5" opacity=".7" strokeLinejoin="round" />
      <rect x="-8" y="168" width="16" height="16" rx="6" fill={shade} />
      <circle cx="0" cy="150" r="25" fill={skin} />
      <ellipse cx="0" cy="168" rx="14" ry="5" fill={shade} opacity=".55" />
      <circle cx="-25" cy="152" r="5" fill={skin} /><circle cx="25" cy="152" r="5" fill={skin} />
      <path d="M-27 146c1-26 53-26 54 0-13-9-41-9-54 0z" fill={hair} />
      <path d="M-14 134c8-6 18-6 26-2" stroke="#fff" strokeWidth="2" opacity=".25" fill="none" strokeLinecap="round" />
      <path d="M-10 159q10 9 20 0" stroke={INK} strokeWidth="2.4" fill="none" strokeLinecap="round" />
      <circle cx="-9" cy="150" r="2.2" fill={INK} /><circle cx="9" cy="150" r="2.2" fill={INK} />
      <circle cx="-15" cy="157" r="4" fill={RL} opacity=".25" /><circle cx="15" cy="157" r="4" fill={RL} opacity=".25" />
    </g>
  );
}

function Coin({ cx, cy, rx = 20, fill = G3, edge = G2 }: { cx: number; cy: number; rx?: number; fill?: string; edge?: string }) {
  return (<g><ellipse cx={cx} cy={cy + 4} rx={rx} ry={rx * 0.3} fill={edge} /><ellipse cx={cx} cy={cy} rx={rx} ry={rx * 0.3} fill={fill} /><ellipse cx={cx} cy={cy} rx={rx * 0.62} ry={rx * 0.18} fill="none" stroke={edge} strokeWidth="1" opacity=".7" /></g>);
}

/** Neighbours around a table, passbook and coins, under bunting against a city skyline: the hero picture. */
export function HeroArt() {
  const bld: Array<[number, number, number]> = [[10, 150, 40], [56, 120, 34], [96, 170, 46], [148, 105, 38], [192, 160, 36], [234, 130, 44], [284, 175, 40], [330, 112, 36], [372, 150, 44], [422, 126, 40]];
  return (
    <svg viewBox="0 0 480 340" className="art" role="img" aria-label="Friends smiling around a table with a passbook and coins, under red and white bunting">
      <defs>
        <clipPath id="hero-clip"><rect width="480" height="340" rx="28" /></clipPath>
        <linearGradient id="hero-bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#2b2d31" /><stop offset="1" stopColor="#0c0d0f" /></linearGradient>
        <radialGradient id="hero-glow" cx=".5" cy=".42" r=".6"><stop offset="0" stopColor={R} stopOpacity=".75" /><stop offset="1" stopColor={R} stopOpacity="0" /></radialGradient>
        <linearGradient id="hero-table" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={G3} /><stop offset="1" stopColor={G1} /></linearGradient>
        <linearGradient id="hero-floor" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={K3} /><stop offset="1" stopColor={K} /></linearGradient>
      </defs>
      <rect width="480" height="340" rx="28" fill="url(#hero-bg)" />
      <g clipPath="url(#hero-clip)">
        <rect width="480" height="340" fill="url(#hero-glow)" />
        <circle cx="240" cy="120" r="150" fill="none" stroke={W} strokeOpacity=".06" strokeWidth="2" /><circle cx="240" cy="120" r="110" fill="none" stroke={W} strokeOpacity=".06" strokeWidth="2" />
        {bld.map(([x, h, w], i) => (
          <g key={i}>
            <rect x={x} y={262 - h} width={w} height={h} fill={i % 2 ? '#202226' : '#17181b'} />
            {[0, 1, 2, 3, 4].map(r => [0, 1].map(c => (r * 2 + c + i) % 3 === 0 ? <rect key={`${r}${c}`} x={x + 7 + c * (w / 2 - 3)} y={262 - h + 12 + r * 22} width="7" height="9" fill={i % 4 === 0 ? RL : G2} opacity=".75" /> : null))}
          </g>
        ))}
        <path d="M-10 28Q120 74 240 40T490 34" fill="none" stroke={G2} strokeWidth="2" />
        {[0, 1, 2, 3, 4, 5, 6, 7, 8].map(i => {
          const x = 24 + i * 52, y = 40 + Math.sin(i * 0.8) * 10 + (i % 2) * 2;
          return <path key={i} d={`M${x - 11} ${y}L${x + 11} ${y + 2}L${x} ${y + 26}z`} fill={i % 3 === 0 ? R : i % 3 === 1 ? W : G2} />;
        })}
        <rect y="248" width="480" height="92" fill="url(#hero-floor)" />
        <Person x={128} skin="#c68642" shade="#a96c2f" shirt={R} hair={INK} />
        <Person x={240} skin="#e0ac69" shade="#c28a4c" shirt={W} hair="#3b2a20" />
        <Person x={352} skin="#8d5524" shade="#6f4119" shirt={G1} hair={INK} />
        <rect x="36" y="246" width="408" height="22" rx="11" fill="url(#hero-table)" />
        <rect x="36" y="246" width="408" height="5" rx="2.5" fill={W} opacity=".35" />
        <rect x="62" y="266" width="14" height="56" fill={K3} /><rect x="404" y="266" width="14" height="56" fill={K3} />
        <rect x="196" y="224" width="88" height="28" rx="4" fill={R} /><rect x="200" y="227" width="80" height="22" rx="3" fill={W} />
        <path d="M240 227v22" stroke={G3} strokeWidth="2" /><path d="M208 235h24M208 242h16M248 235h24M248 242h16" stroke={G2} strokeWidth="1.6" strokeLinecap="round" />
        <path d="M196 224h88" stroke={RL} strokeWidth="2" />
        <Coin cx={324} cy={246} rx={22} /><Coin cx={324} cy={238} rx={22} fill={R} edge={RD} /><Coin cx={324} cy={230} rx={22} /><Coin cx={324} cy={222} rx={22} fill={R} edge={RD} />
        <Coin cx={158} cy={245} rx={16} fill={R} edge={RD} /><Coin cx={158} cy={238} rx={16} />
        {[[60, 90], [420, 86], [92, 140], [392, 150], [300, 70]].map(([x, y], i) => <path key={i} d={`M${x} ${y - 6}L${x + 2} ${y - 2}L${x + 6} ${y}L${x + 2} ${y + 2}L${x} ${y + 6}L${x - 2} ${y + 2}L${x - 6} ${y}L${x - 2} ${y - 2}z`} fill={W} opacity={0.5 + (i % 3) * 0.15} />)}
      </g>
    </svg>
  );
}

function Frame({ id, light = true, children, label }: { id: string; light?: boolean; children: ReactNode; label: string }) {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label={label}>
      <defs>
        <linearGradient id={`${id}-bg`} x1="0" y1="0" x2="1" y2="1">{light ? <><stop offset="0" stopColor={W} /><stop offset="1" stopColor={G3} /></> : <><stop offset="0" stopColor={K3} /><stop offset="1" stopColor={K} /></>}</linearGradient>
        <radialGradient id={`${id}-gl`} cx=".5" cy=".45" r=".6"><stop offset="0" stopColor={R} stopOpacity={light ? 0.28 : 0.7} /><stop offset="1" stopColor={R} stopOpacity="0" /></radialGradient>
        <pattern id={`${id}-dots`} width="12" height="12" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r="1.1" fill={light ? G2 : W} opacity=".25" /></pattern>
        <clipPath id={`${id}-cl`}><rect width="160" height="120" rx="18" /></clipPath>
      </defs>
      <rect width="160" height="120" rx="18" fill={`url(#${id}-bg)`} />
      <g clipPath={`url(#${id}-cl)`}><rect width="160" height="120" fill={`url(#${id}-dots)`} /><rect width="160" height="120" fill={`url(#${id}-gl)`} />{children}</g>
    </svg>
  );
}

export function CoinsArt() {
  return (
    <Frame id="coins" label="Red piggy bank with a coin dropping in">
      <ellipse cx="80" cy="106" rx="50" ry="6" fill={K} opacity=".18" />
      <path d="M60 94v14M100 94v14" stroke={RD} strokeWidth="10" strokeLinecap="round" />
      <ellipse cx="80" cy="70" rx="45" ry="32" fill={R} />
      <ellipse cx="68" cy="56" rx="26" ry="12" fill={W} opacity=".18" />
      <circle cx="120" cy="62" r="13" fill={R} /><ellipse cx="130" cy="64" rx="6" ry="8" fill={RD} /><circle cx="119" cy="57" r="2.4" fill={INK} />
      <path d="M62 44l-7-14 16 6z" fill={RD} />
      <path d="M36 66q-12-4-8-14" stroke={R} strokeWidth="5" fill="none" strokeLinecap="round" />
      <rect x="70" y="46" width="24" height="5.5" rx="2.7" fill={RD} />
      <ellipse cx="84" cy="22" rx="12" ry="12" fill={G4} stroke={G2} strokeWidth="2" />
      <path d="M79 18h10M79 22h10M79 18c7 0 7 8 0 8h-1l6 6" stroke={G1} strokeWidth="1.8" fill="none" strokeLinecap="round" />
    </Frame>
  );
}

export function GavelArt() {
  return (
    <Frame id="gavel" light={false} label="Auction gavel striking a sound block">
      <ellipse cx="82" cy="108" rx="52" ry="6" fill="#000" opacity=".4" />
      <rect x="30" y="94" width="104" height="14" rx="5" fill={G3} /><rect x="30" y="94" width="104" height="4" rx="2" fill={W} opacity=".6" />
      <g transform="rotate(-32 84 50)">
        <rect x="76" y="48" width="10" height="56" rx="5" fill={G2} />
        <rect x="52" y="24" width="60" height="30" rx="8" fill={R} />
        <rect x="52" y="24" width="60" height="9" rx="4.5" fill={RL} opacity=".7" />
        <rect x="46" y="26" width="8" height="26" rx="3" fill={K3} /><rect x="110" y="26" width="8" height="26" rx="3" fill={K3} />
      </g>
      <path d="M118 78l12-6M120 88l14 0M114 68l8-12" stroke={W} strokeWidth="2.5" strokeLinecap="round" opacity=".8" />
      <circle cx="26" cy="30" r="2.2" fill={W} opacity=".7" /><circle cx="140" cy="26" r="2.2" fill={RL} /><circle cx="36" cy="52" r="1.6" fill={RL} />
    </Frame>
  );
}

export function LampArt() {
  return (
    <svg viewBox="0 0 160 120" className="art" role="img" aria-label="Festival lamp">
      <defs>
        <linearGradient id="lamp-bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor={RL} /><stop offset="1" stopColor={RD} /></linearGradient>
        <radialGradient id="lamp-gl" cx=".5" cy=".4" r=".55"><stop offset="0" stopColor={W} stopOpacity=".6" /><stop offset="1" stopColor={W} stopOpacity="0" /></radialGradient>
        <clipPath id="lamp-cl"><rect width="160" height="120" rx="18" /></clipPath>
      </defs>
      <rect width="160" height="120" rx="18" fill="url(#lamp-bg)" />
      <g clipPath="url(#lamp-cl)">
        <circle cx="80" cy="52" r="52" fill="url(#lamp-gl)" />
        {[0, 1, 2, 3, 4, 5, 6, 7].map(i => <path key={i} d={`M80 62L${80 + Math.cos(i * 0.785) * 90} ${62 + Math.sin(i * 0.785) * 90}`} stroke={W} strokeOpacity=".08" strokeWidth="10" />)}
        <path d="M80 22c15 15 15 28 0 41-15-13-15-26 0-41z" fill={W} />
        <path d="M80 38c7 8 7 15 0 22-7-7-7-14 0-22z" fill={R} opacity=".85" />
        <path d="M36 72h88c0 24-19 36-44 36S36 96 36 72z" fill={K} />
        <path d="M36 72h88" stroke={G3} strokeWidth="5" strokeLinecap="round" />
        <path d="M50 82q30 12 60 0" stroke={G2} strokeWidth="2.5" fill="none" opacity=".7" />
        <circle cx="26" cy="38" r="3" fill={W} opacity=".8" /><circle cx="136" cy="30" r="3" fill={W} opacity=".8" /><circle cx="130" cy="58" r="2" fill={W} opacity=".7" /><circle cx="30" cy="62" r="2" fill={W} opacity=".7" />
      </g>
    </svg>
  );
}

export function PassbookArt() {
  return (
    <Frame id="pass" label="A passbook with a red cover and a silver coin">
      <ellipse cx="80" cy="106" rx="42" ry="5" fill={K} opacity=".15" />
      <rect x="42" y="24" width="78" height="80" rx="8" fill={RD} />
      <rect x="38" y="20" width="78" height="80" rx="8" fill={R} />
      <rect x="38" y="20" width="12" height="80" rx="6" fill={RD} opacity=".6" />
      <rect x="58" y="34" width="46" height="28" rx="4" fill={W} />
      <path d="M64 44h34M64 52h22" stroke={G2} strokeWidth="3" strokeLinecap="round" />
      <path d="M58 76h46M58 86h30" stroke={W} strokeOpacity=".5" strokeWidth="3" strokeLinecap="round" />
      <circle cx="112" cy="86" r="14" fill={G4} stroke={G2} strokeWidth="2" /><path d="M106 84l5 5 9-10" stroke={R} strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </Frame>
  );
}

export function RelaxArt() {
  return (
    <Frame id="relax" label="A calm cup of tea">
      <ellipse cx="80" cy="104" rx="46" ry="5" fill={K} opacity=".15" />
      <path d="M46 56h64v24a32 32 0 0 1-64 0z" fill={W} stroke={G2} strokeWidth="3" />
      <path d="M46 70h64" stroke={R} strokeWidth="7" />
      <path d="M110 62q18 0 18 14t-18 14" stroke={G2} strokeWidth="4" fill="none" />
      <ellipse cx="78" cy="56" rx="32" ry="5" fill={RD} opacity=".85" />
      <path d="M64 44q-6-9 0-18M80 44q-6-9 0-18M96 44q-6-9 0-18" stroke={G2} strokeWidth="3.5" fill="none" strokeLinecap="round" />
      <path d="M16 106c8-18 22-20 36-4z" fill={G1} /><path d="M120 108c8-14 20-16 28-2z" fill={G2} /><path d="M28 106c2-12 10-18 18-14" stroke={K} strokeWidth="2" fill="none" opacity=".5" />
    </Frame>
  );
}

export function ConfettiArt() {
  return (
    <Frame id="conf" label="Celebration with a tick in a red circle">
      <circle cx="80" cy="62" r="30" fill={R} /><circle cx="80" cy="62" r="30" fill="none" stroke={W} strokeWidth="3" opacity=".6" />
      <circle cx="80" cy="62" r="42" fill="none" stroke={R} strokeWidth="2" opacity=".3" />
      <path d="M68 62l9 10 16-20" stroke={W} strokeWidth="7" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      {[[22, 24, R], [132, 26, K], [28, 92, G2], [130, 92, R], [100, 14, G1], [50, 12, R], [140, 60, W], [16, 58, K]].map(([x, y, c], i) => (
        <rect key={i} x={x as number} y={y as number} width="10" height="10" rx="2" fill={c as string} stroke={c === W ? G2 : 'none'} transform={`rotate(${i * 27} ${x} ${y})`} />
      ))}
    </Frame>
  );
}

/** A cover picture for a group: stable pattern and colours chosen from its code, so the same group always looks the same. */
export function GroupCover({ code }: { code: string }) {
  let h = 0;
  for (let i = 0; i < code.length; i++) h = (h * 31 + code.charCodeAt(i)) >>> 0;
  const palettes = [[K, R], [RD, G3], [G1, R], [K3, RL], [R, K]];
  const [a, b] = palettes[h % palettes.length];
  const id = `gc${h}`;
  return (
    <svg viewBox="0 0 400 110" preserveAspectRatio="xMidYMid slice" className="cover" aria-hidden="true">
      <defs>
        <linearGradient id={`${id}-g`} x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor={a} /><stop offset="1" stopColor={a} stopOpacity=".75" /></linearGradient>
        <pattern id={`${id}-p`} width="18" height="18" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="9" height="18" fill={W} opacity=".05" /></pattern>
      </defs>
      <rect width="400" height="110" fill={`url(#${id}-g)`} /><rect width="400" height="110" fill={`url(#${id}-p)`} />
      {[0, 1, 2, 3, 4, 5].map(i => (
        <circle key={i} cx={30 + ((h >> (i + 2)) % 340)} cy={20 + ((h >> (i + 5)) % 70)} r={14 + ((h >> i) % 26)} fill={b} opacity={0.14 + (i % 3) * 0.07} />
      ))}
      <circle cx="350" cy="55" r="32" fill={b} /><circle cx="350" cy="55" r="32" fill="none" stroke={W} strokeOpacity=".5" strokeWidth="2" /><circle cx="350" cy="55" r="24" fill="none" stroke={W} strokeOpacity=".35" strokeWidth="1.5" />
      <path d="M340 55l7 7 13-14" stroke={W} strokeWidth="5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
