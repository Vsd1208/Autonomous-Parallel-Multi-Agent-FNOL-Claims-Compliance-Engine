import React from 'react';
import { Navigate } from 'react-router-dom';
import { Area, session } from '../services/session';

/** Sends someone away from a page their role has no use for. A convenience only: the server enforces every permission. */
export function RoleGuard({ area, children }: { area: Area; children: React.ReactElement }) {
  return session.may(area) ? children : <Navigate to="/" replace />;
}
