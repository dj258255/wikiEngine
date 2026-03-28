"use client";

import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import UserMenu from "./components/UserMenu";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export default function Home() {
  const [query, setQuery] = useState("");
  const router = useRouter();

  // 자동완성 — 입력 prefix를 볼드 하이라이트
  const highlightMatch = (text: string, prefix: string) => {
    if (!prefix) return text;
    const idx = text.toLowerCase().indexOf(prefix.toLowerCase());
    if (idx === -1) return text;
    return (
      <>
        {text.slice(0, idx)}
        <b className="font-bold text-zinc-900 dark:text-zinc-100">{text.slice(idx, idx + prefix.length)}</b>
        {text.slice(idx + prefix.length)}
      </>
    );
  };

  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [selectedIdx, setSelectedIdx] = useState(-1);
  const inputRef = useRef<HTMLInputElement>(null);
  const suggestionsRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // 자동완성 — 조합 중에도 debounce로 요청 (네이버/구글 패턴)
  // 백엔드가 title_jamo 필드에서 자모 분해 PrefixQuery로 매칭하므로
  // "자ㅂ" → "ㅈㅏㅂ" → "자바", "자본" 등 매칭 가능
  const fetchSuggestions = (value: string) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    abortRef.current?.abort();

    const trimmed = value.trim();
    if (trimmed.length < 1) {
      setSuggestions([]);
      setShowSuggestions(false);
      return;
    }

    const controller = new AbortController();
    abortRef.current = controller;

    debounceRef.current = setTimeout(async () => {
      try {
        const res = await fetch(
          `${API_URL}/api/v1.0/posts/autocomplete?prefix=${encodeURIComponent(trimmed)}`,
          { signal: controller.signal }
        );
        if (res.ok && !controller.signal.aborted) {
          const json = await res.json();
          const data: string[] = json.data || [];
          setSuggestions(data);
          setShowSuggestions(data.length > 0);
          setSelectedIdx(-1);
        }
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") return;
      }
    }, 150);
  };

  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      abortRef.current?.abort();
    };
  }, []);

  // 외부 클릭 시 닫기
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (suggestionsRef.current && !suggestionsRef.current.contains(e.target as Node) &&
          inputRef.current && !inputRef.current.contains(e.target as Node)) {
        setShowSuggestions(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  const navigateSearch = (q: string) => {
    router.push(`/search?q=${encodeURIComponent(q)}`);
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setShowSuggestions(false);
    if (query.trim()) {
      navigateSearch(query.trim());
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!showSuggestions || suggestions.length === 0) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setSelectedIdx((prev) => (prev < suggestions.length - 1 ? prev + 1 : 0));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSelectedIdx((prev) => (prev > 0 ? prev - 1 : suggestions.length - 1));
    } else if (e.key === "Enter" && selectedIdx >= 0) {
      e.preventDefault();
      const selected = suggestions[selectedIdx];
      setQuery(selected);
      setShowSuggestions(false);
      navigateSearch(selected);
    } else if (e.key === "Escape") {
      setShowSuggestions(false);
    }
  };

  const selectSuggestion = (s: string) => {
    setQuery(s);
    setShowSuggestions(false);
    navigateSearch(s);
  };

  return (
    <div className="relative flex min-h-screen flex-col items-center justify-center bg-white dark:bg-zinc-950">
      {/* Top-right auth area */}
      <div className="absolute right-6 top-6">
        <UserMenu />
      </div>

      <main className="flex flex-col items-center gap-6 -mt-20">
        <h1 className="text-4xl font-bold text-zinc-900 dark:text-zinc-100">
          위키 검색
        </h1>
        <div className="flex flex-col items-center gap-1 text-xs text-zinc-400 dark:text-zinc-500">
          <p>위키피디아(한/영) + 나무위키 — 총 1,425만 건</p>
        </div>
        <form onSubmit={handleSearch} className="relative w-full min-w-[600px] max-w-4xl px-4">
          <div className="relative">
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => { setQuery(e.target.value); fetchSuggestions(e.target.value); }}
              onKeyDown={handleKeyDown}
              onFocus={() => suggestions.length > 0 && setShowSuggestions(true)}
              placeholder="검색어를 입력하세요..."
              className="w-full rounded-full border border-zinc-300 bg-white px-6 py-4 text-lg text-zinc-900 shadow-sm outline-none transition-shadow focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:focus:ring-blue-800"
              autoComplete="off"
              role="combobox"
              aria-expanded={showSuggestions}
              aria-autocomplete="list"
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
          {/* 자동완성 드롭다운 */}
          {showSuggestions && suggestions.length > 0 && (
            <div
              ref={suggestionsRef}
              className="absolute left-4 right-4 top-full z-50 mt-1 overflow-hidden rounded-2xl border border-zinc-200 bg-white shadow-lg dark:border-zinc-700 dark:bg-zinc-900"
            >
              {suggestions.map((s, i) => (
                <button
                  key={`${s}-${i}`}
                  type="button"
                  onClick={() => selectSuggestion(s)}
                  className={`flex w-full items-center gap-3 px-6 py-3 text-left text-sm transition-colors ${
                    i === selectedIdx
                      ? "bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300"
                      : "text-zinc-700 hover:bg-zinc-50 dark:text-zinc-300 dark:hover:bg-zinc-800"
                  }`}
                >
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4 shrink-0 text-zinc-400">
                    <path strokeLinecap="round" strokeLinejoin="round" d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z" />
                  </svg>
                  <span>{highlightMatch(s, query.trim())}</span>
                </button>
              ))}
            </div>
          )}
        </form>
        <Link
          href="/posts"
          className="text-sm font-medium text-blue-600 hover:underline dark:text-blue-400"
        >
          최신 게시글 보기
        </Link>
      </main>
    </div>
  );
}
