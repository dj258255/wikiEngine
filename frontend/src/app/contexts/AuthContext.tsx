"use client";

import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from "react";

interface User {
  username: string;
}

interface AuthContextType {
  user: User | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  signup: (username: string, nickname: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | null>(null);

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(`${API_URL}/api/v1.0/auth/me`, { credentials: "include" })
      .then(res => res.ok ? res.json() : null)
      .then(data => { if (data) setUser({ username: data.username }); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const handleAuthResponse = useCallback(async (res: Response) => {
    if (!res.ok) {
      const text = await res.text();
      let message = "요청에 실패했습니다.";
      try {
        const err = JSON.parse(text);
        message = err.message || err.error || message;
      } catch {
        if (text) message = text;
      }
      throw new Error(message);
    }
    const data = await res.json();
    setUser({ username: data.username });
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await fetch(`${API_URL}/api/v1.0/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ username, password }),
    });
    await handleAuthResponse(res);
  }, [handleAuthResponse]);

  const signup = useCallback(async (username: string, nickname: string, password: string) => {
    const res = await fetch(`${API_URL}/api/v1.0/auth/signup`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ username, nickname, password }),
    });
    await handleAuthResponse(res);
  }, [handleAuthResponse]);

  const logout = useCallback(async () => {
    try {
      await fetch(`${API_URL}/api/v1.0/auth/logout`, {
        method: "POST",
        credentials: "include",
      });
    } catch {
      // ignore logout API errors
    }
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, signup, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}
