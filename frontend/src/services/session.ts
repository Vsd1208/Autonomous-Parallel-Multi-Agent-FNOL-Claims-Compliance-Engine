/**
 * Who is signed in to this browser tab. Kept in sessionStorage, so closing the tab signs out; the server also ends a session
 * after 8 hours. The workspace bundle carries no credentials of its own.
 */
export type Session = { token: string; username: string; displayName: string; role: 'ADJUSTER' | 'SENIOR_ADJUSTER' | 'SUPERVISOR' | 'INSPECTOR'; expiresAt: string };

/** Parts of the workspace that depend on role. For showing the right screens only: the server enforces every permission. */
export type Area = 'NEW_CLAIM' | 'GOVERNANCE' | 'OPERATIONS';

const KEY = 'fnol:session';

export const session = {
  get(): Session | null {
    try {
      return JSON.parse(window.sessionStorage.getItem(KEY) || 'null');
    } catch {
      return null;
    }
  },
  save(s: Session) {
    window.sessionStorage.setItem(KEY, JSON.stringify(s));
  },
  clear() {
    window.sessionStorage.removeItem(KEY);
  },
  /** The signed-in person's id; empty if nobody is. */
  id(): string {
    return this.get()?.username ?? '';
  },
  may(area: Area): boolean {
    const role = this.get()?.role;
    if (area === 'OPERATIONS') return role === 'SUPERVISOR';
    return role != null && role !== 'INSPECTOR';
  },
  isInspector(): boolean {
    return this.get()?.role === 'INSPECTOR';
  },
};
