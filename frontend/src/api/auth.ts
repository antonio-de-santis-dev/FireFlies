// Autenticazione dei gestori (API_CONTRACT §3). Il ruolo arriva sempre dal backend: il login invia solo email e password.
import type { Role } from '../store';
import { api } from './client';

export type ServerRole = 'OWNER' | 'STAFF';

export type SessionUser = { id: string; email: string; displayName: string; role: ServerRole };

type SessionResponse = { authenticated: boolean; user: SessionUser | null; reason: 'SESSION_EXPIRED' | null };

/** Nell'interfaccia (già approvata) i ruoli si chiamano «titolare» e «staff». */
export function uiRole(role: ServerRole): Role {
  return role === 'OWNER' ? 'titolare' : 'staff';
}

export async function fetchSession(): Promise<SessionResponse> {
  return api.get<SessionResponse>('/auth/session');
}

export async function login(email: string, password: string): Promise<SessionUser> {
  const response = await api.post<{ user: SessionUser }>('/auth/login', { email, password });
  return response.user;
}

export async function logout(): Promise<void> {
  await api.post<void>('/auth/logout');
}
