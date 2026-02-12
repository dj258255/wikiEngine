"use client";

import { useState, useEffect, useRef, Suspense } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import Link from "next/link";
import UserMenu from "../components/UserMenu";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface SearchResult {
  id: string;
  title: string;
  viewCount: number;
  likeCount: number;
  createdAt: string;
}

export default function SearchPage() {
  return (
    <Suspense fallback={
      <div className="flex min-h-screen items-center justify-center bg-white dark:bg-zinc-950">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
      </div>
    }>
      <SearchPageContent />
    </Suspense>
  );
}

function SearchPageContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const query = searchParams.get("q") || "";
  const pageParam = Number(searchParams.get("page") || "0");
  const [searchQuery, setSearchQuery] = useState(query);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [currentPage, setCurrentPage] = useState(pageParam);
  const [aiSummary, setAiSummary] = useState("");
  const [loading, setLoading] = useState(false);
  const [aiLoading, setAiLoading] = useState(false);

  // 자동완성
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [selectedIdx, setSelectedIdx] = useState(-1);
  const inputRef = useRef<HTMLInputElement>(null);
  const suggestionsRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setSearchQuery(query);
    setCurrentPage(pageParam);
    if (query) {
      fetchResults(query, pageParam);
      fetchAiSummary(query);
    }
  }, [query, pageParam]);

  // 자동완성 debounce + AbortController
  useEffect(() => {
    const trimmed = searchQuery.trim();
    if (trimmed.length < 1 || trimmed === query) {
      setSuggestions([]);
      setShowSuggestions(false);
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const timeout = setTimeout(() => controller.abort(), 5000);
        const res = await fetch(
          `${API_URL}/api/v1.0/posts/autocomplete?prefix=${encodeURIComponent(trimmed)}`,
          { signal: controller.signal }
        );
        clearTimeout(timeout);
        if (res.ok) {
          const json = await res.json();
          const data: string[] = json.data || [];
          setSuggestions(data);
          setShowSuggestions(data.length > 0);
          setSelectedIdx(-1);
        }
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") return;
      }
    }, 300);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [searchQuery, query]);

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

  const fetchResults = async (q: string, page: number) => {
    setLoading(true);
    try {
      const res = await fetch(`/api/search?q=${encodeURIComponent(q)}&page=${page}`);
      const data = await res.json();
      setResults(data.results || []);
      setTotalPages(data.totalPages || 0);
      setTotalElements(data.totalElements || 0);
      setCurrentPage(data.currentPage || 0);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  };

  const fetchAiSummary = async (q: string) => {
    setAiLoading(true);
    setAiSummary("");
    try {
      const res = await fetch(`/api/ai-summary?q=${encodeURIComponent(q)}`);
      const data = await res.json();
      setAiSummary(data.summary || "");
    } catch {
      setAiSummary("");
    } finally {
      setAiLoading(false);
    }
  };

  const navigateSearch = (q: string, page = 0) => {
    const params = new URLSearchParams({ q });
    if (page > 0) params.set("page", String(page));
    router.push(`/search?${params.toString()}`);
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setShowSuggestions(false);
    if (searchQuery.trim()) {
      navigateSearch(searchQuery.trim());
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
      setSearchQuery(selected);
      setShowSuggestions(false);
      navigateSearch(selected);
    } else if (e.key === "Escape") {
      setShowSuggestions(false);
    }
  };

  const selectSuggestion = (s: string) => {
    setSearchQuery(s);
    setShowSuggestions(false);
    navigateSearch(s);
  };

  const formatDate = (iso: string) => {
    try {
      return new Date(iso).toLocaleDateString("ko-KR", { year: "numeric", month: "short", day: "numeric" });
    } catch { return ""; }
  };

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950">
      <header className="border-b border-zinc-200 bg-white px-6 py-4 dark:border-zinc-800 dark:bg-zinc-950">
        <div className="mx-auto flex max-w-5xl items-center gap-6">
          <Link href="/" className="shrink-0 text-xl font-bold text-zinc-900 dark:text-zinc-100">
            위키 검색
          </Link>
          <form onSubmit={handleSearch} className="relative flex-1">
            <div className="relative">
              <input
                ref={inputRef}
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyDown={handleKeyDown}
                onFocus={() => suggestions.length > 0 && setShowSuggestions(true)}
                placeholder="검색어를 입력하세요..."
                className="w-full rounded-full border border-zinc-300 bg-white px-5 py-2.5 text-zinc-900 outline-none transition-shadow focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:focus:ring-blue-800"
              />
              <button
                type="submit"
                className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded-full bg-blue-500 p-2 text-white transition-colors hover:bg-blue-600"
              >
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="h-4 w-4">
                  <path strokeLinecap="round" strokeLinejoin="round" d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z" />
                </svg>
              </button>
            </div>
            {/* 자동완성 드롭다운 */}
            {showSuggestions && suggestions.length > 0 && (
              <div
                ref={suggestionsRef}
                className="absolute left-0 right-0 top-full z-50 mt-1 overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-lg dark:border-zinc-700 dark:bg-zinc-900"
              >
                {suggestions.map((s, i) => (
                  <button
                    key={s}
                    type="button"
                    onClick={() => selectSuggestion(s)}
                    className={`flex w-full items-center gap-3 px-5 py-2.5 text-left text-sm transition-colors ${
                      i === selectedIdx
                        ? "bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300"
                        : "text-zinc-700 hover:bg-zinc-50 dark:text-zinc-300 dark:hover:bg-zinc-800"
                    }`}
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4 shrink-0 text-zinc-400">
                      <path strokeLinecap="round" strokeLinejoin="round" d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z" />
                    </svg>
                    {s}
                  </button>
                ))}
              </div>
            )}
          </form>
          <UserMenu />
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-6 py-8">
        {query && (
          <p className="mb-6 text-sm text-zinc-500 dark:text-zinc-400">
            &quot;{query}&quot; 검색 결과
            {totalElements > 0 && <span className="ml-1">({totalElements.toLocaleString()}건)</span>}
          </p>
        )}

        {/* AI 요약 섹션 */}
        {query && (
          <div className="mb-8 rounded-xl border border-blue-200 bg-gradient-to-r from-blue-50 to-indigo-50 p-5 dark:border-blue-800 dark:from-blue-950/50 dark:to-indigo-950/50">
            <div className="mb-3 flex items-center gap-2">
              <div className="flex h-6 w-6 items-center justify-center rounded-full bg-gradient-to-r from-blue-500 to-indigo-500">
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="white" className="h-4 w-4">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456ZM16.894 20.567 16.5 21.75l-.394-1.183a2.25 2.25 0 0 0-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 0 0 1.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 0 0 1.423 1.423l1.183.394-1.183.394a2.25 2.25 0 0 0-1.423 1.423Z" />
                </svg>
              </div>
              <span className="text-sm font-semibold text-blue-700 dark:text-blue-300">AI 요약</span>
            </div>
            {aiLoading ? (
              <div className="flex items-center gap-2">
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
                <span className="text-sm text-zinc-500">AI가 요약하는 중...</span>
              </div>
            ) : aiSummary ? (
              <p className="text-sm leading-relaxed text-zinc-700 dark:text-zinc-300">{aiSummary}</p>
            ) : (
              <p className="text-sm text-zinc-500 dark:text-zinc-400">요약을 생성할 수 없습니다.</p>
            )}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
          </div>
        ) : results.length > 0 ? (
          <>
            <ul className="space-y-3">
              {results.map((result) => (
                <li key={result.id}>
                  <Link
                    href={`/wiki/${result.id}`}
                    className="block rounded-lg border border-zinc-200 bg-white px-5 py-4 transition-colors hover:border-blue-300 hover:bg-blue-50/50 dark:border-zinc-800 dark:bg-zinc-900 dark:hover:border-blue-700 dark:hover:bg-zinc-800"
                  >
                    <h2 className="text-base font-medium text-blue-600 dark:text-blue-400">
                      {result.title}
                    </h2>
                    <div className="mt-1.5 flex items-center gap-4 text-xs text-zinc-400 dark:text-zinc-500">
                      <span className="flex items-center gap-1">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-3.5 w-3.5">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z" />
                          <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
                        </svg>
                        {result.viewCount.toLocaleString()}
                      </span>
                      <span className="flex items-center gap-1">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-3.5 w-3.5">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M6.633 10.25c.806 0 1.533-.446 2.031-1.08a9.041 9.041 0 0 1 2.861-2.4c.723-.384 1.35-.956 1.653-1.715a4.498 4.498 0 0 0 .322-1.672V2.75a.75.75 0 0 1 .75-.75 2.25 2.25 0 0 1 2.25 2.25c0 1.152-.26 2.243-.723 3.218-.266.558.107 1.282.725 1.282m0 0h3.126c1.026 0 1.945.694 2.054 1.715.045.422.068.85.068 1.285a11.95 11.95 0 0 1-2.649 7.521c-.388.482-.987.729-1.605.729H13.48c-.483 0-.964-.078-1.423-.23l-3.114-1.04a4.501 4.501 0 0 0-1.423-.23H5.904m10.598-9.75H14.25M5.904 18.5c.083.205.173.405.27.602.197.4-.078.898-.523.898h-.908c-.889 0-1.713-.518-1.972-1.368a12 12 0 0 1-.521-3.507c0-1.553.295-3.036.831-4.398C3.387 9.953 4.167 9.5 5 9.5h1.053c.472 0 .745.556.5.96a8.958 8.958 0 0 0-1.302 4.665c0 1.194.232 2.333.654 3.375Z" />
                        </svg>
                        {result.likeCount.toLocaleString()}
                      </span>
                      {result.createdAt && <span>{formatDate(result.createdAt)}</span>}
                    </div>
                  </Link>
                </li>
              ))}
            </ul>

            {/* 페이지네이션 */}
            {totalPages > 1 && (
              <div className="mt-8 flex items-center justify-center gap-2">
                <button
                  onClick={() => navigateSearch(query, currentPage - 1)}
                  disabled={currentPage <= 0}
                  className="rounded-lg border border-zinc-200 px-3 py-1.5 text-sm text-zinc-600 transition-colors hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                >
                  이전
                </button>
                <span className="px-3 text-sm text-zinc-500 dark:text-zinc-400">
                  {currentPage + 1} / {totalPages}
                </span>
                <button
                  onClick={() => navigateSearch(query, currentPage + 1)}
                  disabled={currentPage >= totalPages - 1}
                  className="rounded-lg border border-zinc-200 px-3 py-1.5 text-sm text-zinc-600 transition-colors hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                >
                  다음
                </button>
              </div>
            )}
          </>
        ) : query ? (
          <p className="py-12 text-center text-zinc-500 dark:text-zinc-400">
            검색 결과가 없습니다.
          </p>
        ) : null}
      </main>
    </div>
  );
}
