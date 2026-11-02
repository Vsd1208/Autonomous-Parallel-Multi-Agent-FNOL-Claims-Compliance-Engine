/**
 * Who is signed in to this browser tab. Kept in sessionStorage, so closing the tab signs out; the server also ends a session
 * after 8 hours. The workspace bundle carries no credentials of its own.
 */
export type Session = { token: string; username: string; displayName: string; role: 'ADJUSTER' | 'INSPECTOR'; expiresAt: string };

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
  isInspector(): boolean {
    return this.get()?.role === 'INSPECTOR';
  },
};
