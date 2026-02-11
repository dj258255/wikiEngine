"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "../contexts/AuthContext";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

type CheckStatus = "idle" | "checking" | "available" | "taken";

export default function SignupPage() {
  const { signup, user } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [nickname, setNickname] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [usernameStatus, setUsernameStatus] = useState<CheckStatus>("idle");
  const [nicknameStatus, setNicknameStatus] = useState<CheckStatus>("idle");

  // 아이디 중복 확인 (debounce 500ms + AbortController)
  useEffect(() => {
    const trimmed = username.trim();
    if (trimmed.length < 5) {
      setUsernameStatus("idle");
      return;
    }
    setUsernameStatus("checking");
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const res = await fetch(
          `${API_URL}/api/v1.0/auth/check-username?value=${encodeURIComponent(trimmed)}`,
          { credentials: "include", signal: controller.signal }
        );
        if (res.ok) {
          const data = await res.json();
          setUsernameStatus(data.data.available ? "available" : "taken");
        } else {
          setUsernameStatus("idle");
        }
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") return;
        setUsernameStatus("idle");
      }
    }, 500);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [username]);

  // 닉네임 중복 확인 (debounce 500ms + AbortController)
  useEffect(() => {
    const trimmed = nickname.trim();
    if (trimmed.length < 2) {
      setNicknameStatus("idle");
      return;
    }
    setNicknameStatus("checking");
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const res = await fetch(
          `${API_URL}/api/v1.0/auth/check-nickname?value=${encodeURIComponent(trimmed)}`,
          { credentials: "include", signal: controller.signal }
        );
        if (res.ok) {
          const data = await res.json();
          setNicknameStatus(data.data.available ? "available" : "taken");
        } else {
          setNicknameStatus("idle");
        }
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") return;
        setNicknameStatus("idle");
      }
    }, 500);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [nickname]);

  if (user) {
    router.replace("/");
    return null;
  }

  const passwordStrength = (() => {
    if (!password) return { level: 0, label: "", color: "" };
    let score = 0;
    if (password.length >= 8) score++;
    if (password.length >= 12) score++;
    if (/[A-Z]/.test(password) && /[a-z]/.test(password)) score++;
    if (/\d/.test(password)) score++;
    if (/[^A-Za-z0-9]/.test(password)) score++;
    if (score <= 1) return { level: 1, label: "약함", color: "bg-red-500" };
    if (score <= 2) return { level: 2, label: "보통", color: "bg-yellow-500" };
    if (score <= 3) return { level: 3, label: "강함", color: "bg-blue-500" };
    return { level: 4, label: "매우 강함", color: "bg-green-500" };
  })();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    if (!username.trim() || !nickname.trim() || !password) {
      setError("모든 항목을 입력해주세요.");
      return;
    }
    if (username.trim().length < 5 || username.trim().length > 20) {
      setError("아이디는 5~20자여야 합니다.");
      return;
    }
    if (nickname.trim().length < 2 || nickname.trim().length > 12) {
      setError("닉네임은 2~12자여야 합니다.");
      return;
    }
    if (password.length < 8 || password.length > 16) {
      setError("비밀번호는 8~16자여야 합니다.");
      return;
    }
    if (password !== passwordConfirm) {
      setError("비밀번호가 일치하지 않습니다.");
      return;
    }
    if (usernameStatus === "taken") {
      setError("이미 사용 중인 아이디입니다.");
      return;
    }
    if (nicknameStatus === "taken") {
      setError("이미 사용 중인 닉네임입니다.");
      return;
    }
    setSubmitting(true);
    try {
      await signup(username.trim(), nickname.trim(), password);
      router.push("/");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "회원가입에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  const statusIcon = (status: CheckStatus) => {
    if (status === "checking") {
      return <span className="h-4 w-4 animate-spin rounded-full border-2 border-zinc-300 border-t-blue-500" />;
    }
    if (status === "available") {
      return (
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="h-5 w-5 text-green-500">
          <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 12.75 6 6 9-13.5" />
        </svg>
      );
    }
    if (status === "taken") {
      return (
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="h-5 w-5 text-red-500">
          <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
        </svg>
      );
    }
    return null;
  };

  const statusMessage = (status: CheckStatus, takenMsg: string) => {
    if (status === "available") return <p className="mt-1 text-xs text-green-600 dark:text-green-400">사용 가능합니다</p>;
    if (status === "taken") return <p className="mt-1 text-xs text-red-600 dark:text-red-400">{takenMsg}</p>;
    return null;
  };

  const inputBorder = (status: CheckStatus) => {
    if (status === "available") return "border-green-300 focus:border-green-500 focus:ring-green-500/10 dark:border-green-700 dark:focus:border-green-400 dark:focus:ring-green-400/10";
    if (status === "taken") return "border-red-300 focus:border-red-500 focus:ring-red-500/10 dark:border-red-700 dark:focus:border-red-400 dark:focus:ring-red-400/10";
    return "border-zinc-200 focus:border-blue-500 focus:ring-blue-500/10 dark:border-zinc-700 dark:focus:border-blue-400 dark:focus:ring-blue-400/10";
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      {/* Background subtle gradient */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -right-40 -top-40 h-[500px] w-[500px] rounded-full bg-indigo-500/5 blur-3xl dark:bg-indigo-500/10" />
        <div className="absolute -bottom-40 -left-40 h-[500px] w-[500px] rounded-full bg-purple-500/5 blur-3xl dark:bg-purple-500/10" />
      </div>

      <div className="relative w-full max-w-md">
        <div className="mb-8 text-center">
          <Link href="/" className="inline-block text-2xl font-bold text-zinc-900 dark:text-zinc-100">
            위키 검색
          </Link>
        </div>

        <div className="rounded-2xl border border-zinc-200/60 bg-white p-8 shadow-xl shadow-zinc-200/40 dark:border-zinc-800 dark:bg-zinc-900 dark:shadow-none sm:p-10">
          <div className="mb-8">
            <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-100">
              회원가입
            </h2>
            <p className="mt-2 text-sm text-zinc-500 dark:text-zinc-400">
              새 계정을 만들어 시작하세요
            </p>
          </div>

          {error && (
            <div className="mb-6 flex items-center gap-3 rounded-xl bg-red-50 px-4 py-3 dark:bg-red-900/20">
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-5 w-5 shrink-0 text-red-500">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z" />
              </svg>
              <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label htmlFor="username" className="mb-2 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
                아이디
              </label>
              <div className="relative">
                <div className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-zinc-400">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-5 w-5">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 12 21.75c-2.676 0-5.216-.584-7.499-1.632Z" />
                  </svg>
                </div>
                <input
                  id="username"
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="5~20자"
                  maxLength={20}
                  className={`w-full rounded-xl border bg-zinc-50 py-3 pl-11 pr-10 text-sm text-zinc-900 outline-none transition-all placeholder:text-zinc-400 focus:bg-white focus:ring-4 dark:bg-zinc-800 dark:text-zinc-100 dark:placeholder:text-zinc-500 dark:focus:bg-zinc-800 ${inputBorder(usernameStatus)}`}
                  autoFocus
                  autoComplete="username"
                />
                {usernameStatus !== "idle" && (
                  <div className="absolute right-3.5 top-1/2 -translate-y-1/2">
                    {statusIcon(usernameStatus)}
                  </div>
                )}
              </div>
              {statusMessage(usernameStatus, "이미 사용 중인 아이디입니다")}
            </div>

            <div>
              <label htmlFor="nickname" className="mb-2 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
                닉네임
              </label>
              <div className="relative">
                <div className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-zinc-400">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-5 w-5">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9.568 3H5.25A2.25 2.25 0 0 0 3 5.25v4.318c0 .597.237 1.17.659 1.591l9.581 9.581c.699.699 1.78.872 2.607.33a18.095 18.095 0 0 0 5.223-5.223c.542-.827.369-1.908-.33-2.607L11.16 3.66A2.25 2.25 0 0 0 9.568 3Z" />
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 6h.008v.008H6V6Z" />
                  </svg>
                </div>
                <input
                  id="nickname"
                  type="text"
                  value={nickname}
                  onChange={(e) => setNickname(e.target.value)}
                  placeholder="2~12자"
                  maxLength={12}
                  className={`w-full rounded-xl border bg-zinc-50 py-3 pl-11 pr-10 text-sm text-zinc-900 outline-none transition-all placeholder:text-zinc-400 focus:bg-white focus:ring-4 dark:bg-zinc-800 dark:text-zinc-100 dark:placeholder:text-zinc-500 dark:focus:bg-zinc-800 ${inputBorder(nicknameStatus)}`}
                  autoComplete="nickname"
                />
                {nicknameStatus !== "idle" && (
                  <div className="absolute right-3.5 top-1/2 -translate-y-1/2">
                    {statusIcon(nicknameStatus)}
                  </div>
                )}
              </div>
              {statusMessage(nicknameStatus, "이미 사용 중인 닉네임입니다")}
            </div>

            <div>
              <label htmlFor="password" className="mb-2 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
                비밀번호
              </label>
              <div className="relative">
                <div className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-zinc-400">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-5 w-5">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-6.75a2.25 2.25 0 0 0-2.25-2.25H6.75a2.25 2.25 0 0 0-2.25 2.25v6.75a2.25 2.25 0 0 0 2.25 2.25Z" />
                  </svg>
                </div>
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="8~16자"
                  maxLength={16}
                  className="w-full rounded-xl border border-zinc-200 bg-zinc-50 py-3 pl-11 pr-11 text-sm text-zinc-900 outline-none transition-all placeholder:text-zinc-400 focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100 dark:placeholder:text-zinc-500 dark:focus:border-blue-400 dark:focus:bg-zinc-800 dark:focus:ring-blue-400/10"
                  autoComplete="new-password"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3.5 top-1/2 -translate-y-1/2 text-zinc-400 transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
                >
                  {showPassword ? (
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-5 w-5">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M3.98 8.223A10.477 10.477 0 0 0 1.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228A10.451 10.451 0 0 1 12 4.5c4.756 0 8.773 3.162 10.065 7.498a10.522 10.522 0 0 1-4.293 5.774M6.228 6.228 3 3m3.228 3.228 3.65 3.65m7.894 7.894L21 21m-3.228-3.228-3.65-3.65m0 0a3 3 0 1 0-4.243-4.243m4.242 4.242L9.88 9.88" />
                    </svg>
                  ) : (
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-5 w-5">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z" />
                      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
                    </svg>
                  )}
                </button>
              </div>
              {password && (
                <div className="mt-2">
                  <div className="flex gap-1">
                    {[1, 2, 3, 4].map((i) => (
                      <div
                        key={i}
                        className={`h-1 flex-1 rounded-full transition-all ${
                          i <= passwordStrength.level ? passwordStrength.color : "bg-zinc-200 dark:bg-zinc-700"
                        }`}
                      />
                    ))}
                  </div>
                  <p className={`mt-1 text-xs ${
                    passwordStrength.level <= 1 ? "text-red-500" :
                    passwordStrength.level <= 2 ? "text-yellow-600 dark:text-yellow-400" :
                    passwordStrength.level <= 3 ? "text-blue-600 dark:text-blue-400" :
                    "text-green-600 dark:text-green-400"
                  }`}>
                    비밀번호 강도: {passwordStrength.label}
                  </p>
                </div>
              )}
            </div>

            <div>
              <label htmlFor="passwordConfirm" className="mb-2 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
                비밀번호 확인
              </label>
              <div className="relative">
                <div className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-zinc-400">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-5 w-5">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75 11.25 15 15 9.75m-3-7.036A11.959 11.959 0 0 1 3.598 6 11.99 11.99 0 0 0 3 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285Z" />
                  </svg>
                </div>
                <input
                  id="passwordConfirm"
                  type={showPassword ? "text" : "password"}
                  value={passwordConfirm}
                  onChange={(e) => setPasswordConfirm(e.target.value)}
                  placeholder="비밀번호를 다시 입력하세요"
                  maxLength={16}
                  className={`w-full rounded-xl border bg-zinc-50 py-3 pl-11 pr-11 text-sm text-zinc-900 outline-none transition-all placeholder:text-zinc-400 focus:bg-white focus:ring-4 dark:bg-zinc-800 dark:text-zinc-100 dark:placeholder:text-zinc-500 dark:focus:bg-zinc-800 ${
                    passwordConfirm && password !== passwordConfirm
                      ? "border-red-300 focus:border-red-500 focus:ring-red-500/10 dark:border-red-700 dark:focus:border-red-400 dark:focus:ring-red-400/10"
                      : passwordConfirm && password === passwordConfirm
                      ? "border-green-300 focus:border-green-500 focus:ring-green-500/10 dark:border-green-700 dark:focus:border-green-400 dark:focus:ring-green-400/10"
                      : "border-zinc-200 focus:border-blue-500 focus:ring-blue-500/10 dark:border-zinc-700 dark:focus:border-blue-400 dark:focus:ring-blue-400/10"
                  }`}
                  autoComplete="new-password"
                />
                {passwordConfirm && (
                  <div className="absolute right-3.5 top-1/2 -translate-y-1/2">
                    {password === passwordConfirm ? (
                      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="h-5 w-5 text-green-500">
                        <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 12.75 6 6 9-13.5" />
                      </svg>
                    ) : (
                      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="h-5 w-5 text-red-500">
                        <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
                      </svg>
                    )}
                  </div>
                )}
              </div>
            </div>

            <button
              type="submit"
              disabled={submitting}
              className="group relative mt-2 w-full overflow-hidden rounded-xl bg-gradient-to-r from-indigo-600 to-purple-600 py-3 text-sm font-semibold text-white shadow-lg shadow-indigo-500/25 transition-all hover:shadow-xl hover:shadow-indigo-500/30 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span className="relative z-10 flex items-center justify-center gap-2">
                {submitting ? (
                  <>
                    <span className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                    가입 중...
                  </>
                ) : (
                  "회원가입"
                )}
              </span>
              <div className="absolute inset-0 -translate-x-full bg-gradient-to-r from-purple-600 to-pink-600 transition-transform duration-300 group-hover:translate-x-0" />
            </button>
          </form>

          <div className="mt-8 text-center">
            <p className="text-sm text-zinc-500 dark:text-zinc-400">
              이미 계정이 있으신가요?{" "}
              <Link
                href="/login"
                className="font-semibold text-indigo-600 transition-colors hover:text-indigo-700 dark:text-indigo-400 dark:hover:text-indigo-300"
              >
                로그인
              </Link>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
