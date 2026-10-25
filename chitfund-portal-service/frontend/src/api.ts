// Thin fetch wrapper for chitfund-portal-service. The session is an HttpOnly cookie the browser sends by itself; this code never sees it.
// Every POST carries X-Portal-Client: web, which the server requires (together with an allowed Origin) as its cross-site request guard.

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (method === 'POST') {
    headers['Content-Type'] = 'application/json';
    headers['X-Portal-Client'] = 'web';
  }
  let res: Response;
  try {
    res = await fetch('/api/v1/portal' + path, {
      method,
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Could not reach the server. Check your connection and try again.');
  }
  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  let data: { code?: string; message?: string } | null = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? `Request failed (${res.status})`);
  }
  return data as T;
}

export interface Member {
  id: number;
  email: string;
  displayName: string | null;
}

export interface GroupInfo {
  status: string;
  chitValue: number;
  subscription: number;
  cycles: number;
  tickets: number;
  startDate: string;
  seatsLeft?: number;
}

export interface Invitation {
  groupCode: string;
  groupName: string;
  tickets: number;
  status: string;
  expiresAt: string;
  group?: GroupInfo;
  groupUnavailable: boolean;
  canDecline: boolean;
}

export interface PortalConfig {
  googleClientId: string;
}

export const api = {
  config: () => call<PortalConfig>('GET', '/config'),
  me: () => call<Member>('GET', '/me'),
  signIn: (idToken: string) => call<Member>('POST', '/auth/google', { idToken }),
  signOut: () => call<void>('POST', '/auth/logout'),
  invitation: (token: string) => call<Invitation>('GET', `/invitations/${encodeURIComponent(token)}`),
  decline: (token: string) => call<Invitation>('POST', `/invitations/${encodeURIComponent(token)}/decline`, {}),
};
