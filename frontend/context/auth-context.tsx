"use client";

import { createContext, useContext, useEffect, useMemo, useState } from "react";

import { AuthSession, fetchDevSession, getStoredSession, SESSION_UPDATED_EVENT, storeSession } from "@/services/auth";

type AuthContextValue = {
  session: AuthSession | null;
  status: "loading" | "ready";
  setSession: (session: AuthSession | null) => void;
  clearSession: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [session, setSessionState] = useState<AuthSession | null>(null);
  const [status, setStatus] = useState<"loading" | "ready">("loading");

  useEffect(() => {
    const syncSession = () => setSessionState(getStoredSession());
    window.addEventListener(SESSION_UPDATED_EVENT, syncSession);

    const devAutoLogin = process.env.NEXT_PUBLIC_DEV_AUTO_LOGIN === "true";
    if (devAutoLogin) {
      const stored = getStoredSession();
      if (stored) {
        setSessionState(stored);
        setStatus("ready");
      } else {
        fetchDevSession()
          .then((session) => {
            storeSession(session);
            setSessionState(session);
          })
          .catch(() => {})
          .finally(() => setStatus("ready"));
      }
    } else {
      setSessionState(getStoredSession());
      setStatus("ready");
    }

    return () => window.removeEventListener(SESSION_UPDATED_EVENT, syncSession);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      status,
      setSession: (nextSession) => {
        setSessionState(nextSession);
        storeSession(nextSession);
      },
      clearSession: () => {
        setSessionState(null);
        storeSession(null);
      },
    }),
    [session, status],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === null) {
    throw new Error("useAuth must be used within AuthProvider");
  }

  return context;
}
