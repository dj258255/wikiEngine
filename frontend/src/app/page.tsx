"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import UserMenu from "./components/UserMenu";

export default function Home() {
  const [query, setQuery] = useState("");
  const router = useRouter();

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (query.trim()) {
      router.push(`/search?q=${encodeURIComponent(query.trim())}`);
    }
  };

  return (
    <div className="relative flex min-h-screen flex-col items-center justify-center bg-white dark:bg-zinc-950">
      {/* Top-right auth area */}
      <div className="absolute right-6 top-6">
        <UserMenu />
      </div>

      <main className="flex flex-col items-center gap-8">
        <div className="flex flex-col items-center gap-1 text-sm text-zinc-400 dark:text-zinc-500">
          <p>위키피디아: 2026년 1월 2일 데이터 기준</p>
          <p>나무위키: 2021년 3월 데이터 기준</p>
        </div>
        <h1 className="text-4xl font-bold text-zinc-900 dark:text-zinc-100">
          위키 검색
        </h1>
        <form onSubmit={handleSearch} className="w-full min-w-[600px] max-w-4xl px-4">
          <div className="relative">
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="검색어를 입력하세요..."
              className="w-full rounded-full border border-zinc-300 bg-white px-6 py-4 text-lg text-zinc-900 shadow-sm outline-none transition-shadow focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:focus:ring-blue-800"
              autoFocus
            />
            <button
              type="submit"
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full bg-blue-500 p-3 text-white transition-colors hover:bg-blue-600"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                fill="none"
                viewBox="0 0 24 24"
                strokeWidth={2}
                stroke="currentColor"
                className="h-5 w-5"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z"
                />
              </svg>
            </button>
          </div>
        </form>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">
          한국어 위키피디아 검색 엔진
        </p>
      </main>
    </div>
  );
}
