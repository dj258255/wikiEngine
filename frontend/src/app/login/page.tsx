"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "../contexts/AuthContext";

export default function LoginPage() {
  const { login, user } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);

  useEffect(() => {
    if (user) router.replace("/");
  }, [user, router]);

  if (user) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    if (!username.trim() || !password) {
      setError("아이디와 비밀번호를 입력해주세요.");
      return;
    }
    setSubmitting(true);
    try {
      await login(username.trim(), password);
      router.push("/");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "로그인에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-zinc-50 px-6 dark:bg-zinc-950">
      {/* Background subtle gradient */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -left-40 -top-40 h-[500px] w-[500px] rounded-full bg-blue-500/5 blur-3xl dark:bg-blue-500/10" />
        <div className="absolute -bottom-40 -right-40 h-[500px] w-[500px] rounded-full bg-indigo-500/5 blur-3xl dark:bg-indigo-500/10" />
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
              로그인
            </h2>
            <p className="mt-2 text-sm text-zinc-500 dark:text-zinc-400">
              계정에 로그인하여 위키를 탐색하세요
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

          <form onSubmit={handleSubmit} className="space-y-5">
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
                  placeholder="아이디를 입력하세요"
                  maxLength={20}
                  className="w-full rounded-xl border border-zinc-200 bg-zinc-50 py-3 pl-11 pr-4 text-sm text-zinc-900 outline-none transition-all placeholder:text-zinc-400 focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100 dark:placeholder:text-zinc-500 dark:focus:border-blue-400 dark:focus:bg-zinc-800 dark:focus:ring-blue-400/10"
                  autoFocus
                  autoComplete="username"
                />
              </div>
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
                  placeholder="비밀번호를 입력하세요"
                  maxLength={16}
                  className="w-full rounded-xl border border-zinc-200 bg-zinc-50 py-3 pl-11 pr-11 text-sm text-zinc-900 outline-none transition-all placeholder:text-zinc-400 focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/10 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-100 dark:placeholder:text-zinc-500 dark:focus:border-blue-400 dark:focus:bg-zinc-800 dark:focus:ring-blue-400/10"
                  autoComplete="current-password"
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
            </div>

            <button
              type="submit"
              disabled={submitting}
              className="group relative w-full overflow-hidden rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 py-3 text-sm font-semibold text-white shadow-lg shadow-blue-500/25 transition-all hover:shadow-xl hover:shadow-blue-500/30 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span className="relative z-10 flex items-center justify-center gap-2">
                {submitting ? (
                  <>
                    <span className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                    로그인 중...
                  </>
                ) : (
                  "로그인"
                )}
              </span>
              <div className="absolute inset-0 -translate-x-full bg-gradient-to-r from-indigo-600 to-purple-600 transition-transform duration-300 group-hover:translate-x-0" />
            </button>
          </form>

          <div className="mt-8 text-center">
            <p className="text-sm text-zinc-500 dark:text-zinc-400">
              계정이 없으신가요?{" "}
              <Link
                href="/signup"
                className="font-semibold text-blue-600 transition-colors hover:text-blue-700 dark:text-blue-400 dark:hover:text-blue-300"
              >
                회원가입
              </Link>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
