"use client";

import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from "react";

interface User {
  username: string;
}

interface AuthContextType {
  user: User | null;
  token: string | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  signup: (username: string, nickname: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | null>(null);

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

function parseJwt(token: string): { sub: string; username: string; exp: number } | null {
  try {
    const base64Url = token.split(".")[1];
    const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split("")
        .map((c) => "%" + ("00" + c.charCodeAt(0).toString(16)).slice(-2))
        .join("")
    );
    return JSON.parse(jsonPayload);
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const savedToken = localStorage.getItem("token");
    if (savedToken) {
      const payload = parseJwt(savedToken);
      if (payload && payload.exp * 1000 > Date.now()) {
        setToken(savedToken);
        setUser({ username: payload.username });
      } else {
        localStorage.removeItem("token");
      }
    }
    setLoading(false);
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
    const jwt = data.token;
    const payload = parseJwt(jwt);
    localStorage.setItem("token", jwt);
    setToken(jwt);
    setUser({ username: payload?.username || "" });
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await fetch(`${API_URL}/api/v1.0/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    await handleAuthResponse(res);
  }, [handleAuthResponse]);

  const signup = useCallback(async (username: string, nickname: string, password: string) => {
    const res = await fetch(`${API_URL}/api/v1.0/auth/signup`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, nickname, password }),
    });
    await handleAuthResponse(res);
  }, [handleAuthResponse]);

  const logout = useCallback(async () => {
    if (token) {
      try {
        await fetch(`${API_URL}/api/v1.0/auth/logout`, {
          method: "POST",
          headers: { Authorization: `Bearer ${token}` },
        });
      } catch {
        // ignore logout API errors
      }
    }
    localStorage.removeItem("token");
    setToken(null);
    setUser(null);
  }, [token]);

  return (
    <AuthContext.Provider value={{ user, token, loading, login, signup, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}
