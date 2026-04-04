"use client";

import { useState, useEffect, useRef, Suspense } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import Link from "next/link";
import UserMenu from "../components/UserMenu";
import { useAuth } from "../contexts/AuthContext";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

// Phase 19: 클릭 로그용 세션 ID (탭 단위, sessionStorage)
function getOrCreateSessionId(): string {
  let sid = sessionStorage.getItem("search_session_id");
  if (!sid) {
    sid = crypto.randomUUID();
    sessionStorage.setItem("search_session_id", sid);
  }
  return sid;
}

interface SearchResult {
  id: string;
  title: string;
  snippet?: string;
  viewCount: number;
  likeCount: number;
  createdAt: string;
}

interface Category {
  id: number;
  name: string;
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
  const { user } = useAuth();
  const query = searchParams.get("q") || "";
  const pageParam = Number(searchParams.get("page") || "0");
  const categoryParam = searchParams.get("categoryId") || "";
  const [searchQuery, setSearchQuery] = useState(query);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [hasNext, setHasNext] = useState(false);
  const [currentPage, setCurrentPage] = useState(pageParam);
  const [aiSummary, setAiSummary] = useState("");
  const [aiCitations, setAiCitations] = useState<{ docNumber: number; postId: number; title: string }[]>([]);
  const [aiFeedback, setAiFeedback] = useState<"up" | "down" | null>(null);
  const [loading, setLoading] = useState(false);
  const [aiLoading, setAiLoading] = useState(false);
  const [spellSuggestion, setSpellSuggestion] = useState<string | null>(null);

  // 카테고리 필터
  const [categories, setCategories] = useState<Category[]>([]);
  const [selectedCategory, setSelectedCategory] = useState(categoryParam);

  // AI 요약 텍스트에서 [문서 N]을 인라인 링크로 변환 (네이버 AI 브리핑 패턴)
  const renderAiSummaryWithLinks = (text: string, citations: { docNumber: number; postId: number; title: string }[]) => {
    const citationMap = new Map(citations.map(c => [c.docNumber, c]));
    const parts = text.split(/(\[문서\s*\d+\])/g);
    return parts.map((part, i) => {
      const match = part.match(/\[문서\s*(\d+)\]/);
      if (match) {
        const docNum = parseInt(match[1], 10);
        const citation = citationMap.get(docNum);
        if (citation) {
          return (
            <Link key={i} href={`/posts/${citation.postId}`}
              className="mx-0.5 inline-flex items-center rounded bg-blue-100 px-1.5 py-0.5 text-xs font-medium text-blue-700 hover:bg-blue-200 dark:bg-blue-900/50 dark:text-blue-300 dark:hover:bg-blue-800/50">
              {citation.title.length > 15 ? citation.title.slice(0, 15) + "…" : citation.title}
            </Link>
          );
        }
      }
      // 텍스트 부분 — 문장 끝(. 뒤)에서 줄바꿈하여 가독성 향상
      const sentences = part.split(/(?<=\.\s)/);
      return (
        <span key={i}>
          {sentences.map((s, j) => (
            <span key={j}>
              {s}
              {j < sentences.length - 1 && <br />}
            </span>
          ))}
        </span>
      );
    });
  };

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

  // 카테고리 목록 로드 (1회)
  useEffect(() => {
    fetch(`${API_URL}/api/v1.0/categories`)
      .then(res => res.ok ? res.json() : null)
      .then(json => { if (json?.data) setCategories(json.data); })
      .catch(() => {});
  }, []);

  useEffect(() => {
    setSearchQuery(query);
    setCurrentPage(pageParam);
    setSelectedCategory(categoryParam);
    if (query) {
      fetchResults(query, pageParam, categoryParam);
      fetchAiSummary(query);
    } else {
      setResults([]);
      setHasNext(false);
      setAiSummary("");
      setAiCitations([]);
      setAiFeedback(null);
    }
  }, [query, pageParam, categoryParam]);

  // 자동완성 — onChange/compositionEnd에서 직접 호출
  const acDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const acAbortRef = useRef<AbortController | null>(null);

  const fetchAutocompleteSuggestions = (value: string) => {
    if (acDebounceRef.current) clearTimeout(acDebounceRef.current);
    acAbortRef.current?.abort();

    const trimmed = value.trim();
    if (trimmed.length < 1 || trimmed === query) {
      setSuggestions([]);
      setShowSuggestions(false);
      return;
    }

    const controller = new AbortController();
    acAbortRef.current = controller;

    acDebounceRef.current = setTimeout(async () => {
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
      if (acDebounceRef.current) clearTimeout(acDebounceRef.current);
      acAbortRef.current?.abort();
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

  const fetchResults = async (q: string, page: number, catId = "") => {
    setLoading(true);
    setSpellSuggestion(null);
    try {
      let url = `/api/search?q=${encodeURIComponent(q)}&page=${page}`;
      if (catId) url += `&categoryId=${catId}`;
      const res = await fetch(url);
      const data = await res.json();
      setResults(data.results || []);
      setHasNext(data.hasNext || false);
      setCurrentPage(data.currentPage || 0);
      if (data.suggestion) {
        setSpellSuggestion(data.suggestion);
      }
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  };

  const fetchAiSummary = async (q: string) => {
    setAiLoading(true);
    setAiSummary("");
    setAiCitations([]);
    try {
      const res = await fetch(`/api/ai-summary?q=${encodeURIComponent(q)}`);
      if (!res.ok || !res.body) {
        setAiLoading(false);
        return;
      }

      // SSE 스트리밍 수신 — 토큰 단위로 타이핑 효과
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let currentEvent = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // SSE 형식: "event: xxx\ndata: yyy\n\n"
        while (buffer.includes("\n\n")) {
          const blockEnd = buffer.indexOf("\n\n");
          const block = buffer.slice(0, blockEnd);
          buffer = buffer.slice(blockEnd + 2);

          currentEvent = "";
          let data = "";
          for (const line of block.split("\n")) {
            if (line.startsWith("event:")) currentEvent = line.slice(6).trim();
            else if (line.startsWith("data:")) data = line.slice(5).trim();
          }

          if (currentEvent === "delta" && data) {
            setAiSummary(prev => prev + data);
            setAiLoading(false);
          } else if (currentEvent === "citations" && data) {
            try { setAiCitations(JSON.parse(data)); } catch { /* ignore */ }
          } else if (currentEvent === "skip" || currentEvent === "error" || currentEvent === "done") {
            setAiLoading(false);
          }
        }
      }
    } catch {
      setAiSummary("");
      setAiCitations([]);
    } finally {
      setAiLoading(false);
    }
  };

  const submitFeedback = async (rating: number) => {
    try {
      await fetch(`${API_URL}/api/v1.0/posts/search/ai-summary/feedback`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query, rating }),
      });
    } catch { /* 피드백 실패는 무시 */ }
  };

  const navigateSearch = (q: string, page = 0, catId = selectedCategory) => {
    const params = new URLSearchParams({ q });
    if (page > 0) params.set("page", String(page));
    if (catId) params.set("categoryId", catId);
    router.push(`/search?${params.toString()}`);
  };

  const handleCategoryChange = (catId: string) => {
    setSelectedCategory(catId);
    if (query) {
      navigateSearch(query, 0, catId);
    }
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
                onChange={(e) => { setSearchQuery(e.target.value); fetchAutocompleteSuggestions(e.target.value); }}
                onKeyDown={handleKeyDown}
                onFocus={() => suggestions.length > 0 && setShowSuggestions(true)}
                placeholder="검색어를 입력하세요..."
                className="w-full rounded-full border border-zinc-300 bg-white px-5 py-2.5 text-zinc-900 outline-none transition-shadow focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:focus:ring-blue-800"
                autoComplete="off"
                role="combobox"
                aria-expanded={showSuggestions}
                aria-autocomplete="list"
              />
              <button
                type="submit"
                aria-label="검색"
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
                    <span>{highlightMatch(s, searchQuery.trim())}</span>
                  </button>
                ))}
              </div>
            )}
          </form>
          <Link
            href="/posts"
            className="shrink-0 rounded-lg border border-zinc-300 px-4 py-2 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
          >
            최신 게시글
          </Link>
          {user && (
            <Link
              href="/posts/new"
              className="shrink-0 rounded-lg bg-blue-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-600"
            >
              글쓰기
            </Link>
          )}
          <UserMenu />
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-6 py-8">
        {query && (
          <p className="mb-6 text-sm text-zinc-500 dark:text-zinc-400">
            &quot;{query}&quot; 검색 결과
          </p>
        )}

        {/* AI 요약 섹션 — 로딩 중이거나 요약이 있을 때만 표시 (트리거 스킵 시 숨김) */}
        {query && (aiLoading || aiSummary) && (
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
              <div>
                <p className="text-sm leading-relaxed text-zinc-700 dark:text-zinc-300">
                  {renderAiSummaryWithLinks(aiSummary, aiCitations)}
                </p>
                {aiCitations.length > 0 && (
                  <div className="mt-3 border-t border-blue-100 pt-3 dark:border-blue-800">
                    <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">출처</span>
                    <div className="mt-1.5 flex flex-col gap-1">
                      {aiCitations.map((c) => (
                        <Link
                          key={c.postId}
                          href={`/posts/${c.postId}`}
                          className="text-xs text-blue-600 hover:underline dark:text-blue-400"
                        >
                          {c.title}
                        </Link>
                      ))}
                    </div>
                  </div>
                )}
                {/* 피드백 UI — "이 답변이 도움이 되었나요?" */}
                <div className="mt-4 flex items-center gap-3 border-t border-blue-100 pt-3 dark:border-blue-800">
                  <span className="text-xs text-zinc-500 dark:text-zinc-400">이 답변이 도움이 되었나요?</span>
                  <button
                    type="button"
                    onClick={() => { setAiFeedback("up"); submitFeedback(1); }}
                    className={`rounded-md p-1.5 transition-colors ${
                      aiFeedback === "up"
                        ? "bg-green-100 text-green-700 dark:bg-green-900/50 dark:text-green-300"
                        : "text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600 dark:hover:bg-zinc-800"
                    }`}
                    aria-label="도움이 되었습니다"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M6.633 10.25c.806 0 1.533-.446 2.031-1.08a9.041 9.041 0 0 1 2.861-2.4c.723-.384 1.35-.956 1.653-1.715a4.498 4.498 0 0 0 .322-1.672V2.75a.75.75 0 0 1 .75-.75 2.25 2.25 0 0 1 2.25 2.25c0 1.152-.26 2.243-.723 3.218-.266.558.107 1.282.725 1.282m0 0h3.126c1.026 0 1.945.694 2.054 1.715.045.422.068.85.068 1.285a11.95 11.95 0 0 1-2.649 7.521c-.388.482-.987.729-1.605.729H13.48c-.483 0-.964-.078-1.423-.23l-3.114-1.04a4.501 4.501 0 0 0-1.423-.23H5.904m10.598-9.75H14.25M5.904 18.5c.083.205.173.405.27.602.197.4-.078.898-.523.898h-.908c-.889 0-1.713-.518-1.972-1.368a12 12 0 0 1-.521-3.507c0-1.553.295-3.036.831-4.398C3.387 9.953 4.167 9.5 5 9.5h1.053c.472 0 .745.556.5.96a8.958 8.958 0 0 0-1.302 4.665c0 1.194.232 2.333.654 3.375Z" />
                    </svg>
                  </button>
                  <button
                    type="button"
                    onClick={() => { setAiFeedback("down"); submitFeedback(-1); }}
                    className={`rounded-md p-1.5 transition-colors ${
                      aiFeedback === "down"
                        ? "bg-red-100 text-red-700 dark:bg-red-900/50 dark:text-red-300"
                        : "text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600 dark:hover:bg-zinc-800"
                    }`}
                    aria-label="도움이 되지 않았습니다"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M7.498 15.25H4.372c-1.026 0-1.945-.694-2.054-1.715a12.137 12.137 0 0 1-.068-1.285c0-2.848.992-5.464 2.649-7.521C5.287 4.247 5.886 4 6.504 4h4.016a4.5 4.5 0 0 1 1.423.23l3.114 1.04a4.5 4.5 0 0 0 1.423.23h1.294M7.498 15.25c.618 0 .991.724.725 1.282A7.471 7.471 0 0 0 7.5 19.75 2.25 2.25 0 0 0 9.75 22a.75.75 0 0 0 .75-.75v-.633c0-.573.11-1.14.322-1.672.304-.76.93-1.33 1.653-1.715a9.04 9.04 0 0 0 2.86-2.4c.498-.634 1.226-1.08 2.032-1.08h.384m-10.253 1.5H9.7m8.075-9.75c.01.05.027.1.05.148.593 1.2.925 2.55.925 3.977 0 1.487-.36 2.89-.999 4.125m.023-8.25c-.076-.365.183-.75.575-.75h.908c.889 0 1.713.518 1.972 1.368.339 1.11.521 2.287.521 3.507 0 1.553-.295 3.036-.831 4.398-.306.774-1.086 1.227-1.918 1.227h-1.053c-.472 0-.745-.556-.5-.96a8.95 8.95 0 0 0 .303-.54" />
                    </svg>
                  </button>
                  {aiFeedback && (
                    <span className="text-xs text-zinc-500">피드백 감사합니다!</span>
                  )}
                </div>
              </div>
            ) : (
              <p className="text-sm text-zinc-500 dark:text-zinc-400">요약을 생성할 수 없습니다.</p>
            )}
          </div>
        )}

        {/* 오타 교정 제안 */}
        {spellSuggestion && (
          <div className="mb-4 text-sm text-zinc-600 dark:text-zinc-400">
            혹시{" "}
            <button
              onClick={() => navigateSearch(spellSuggestion, 0, selectedCategory)}
              className="font-semibold text-blue-600 hover:underline dark:text-blue-400"
            >
              &quot;{spellSuggestion}&quot;
            </button>
            을(를) 찾으셨나요?
          </div>
        )}

        {/* 카테고리 필터 */}
        {query && categories.length > 0 && (
          <div className="mb-6 flex flex-wrap items-center gap-2">
            <button
              onClick={() => handleCategoryChange("")}
              className={`rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
                !selectedCategory
                  ? "bg-blue-500 text-white"
                  : "border border-zinc-300 text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
              }`}
            >
              전체
            </button>
            {categories.map((cat) => (
              <button
                key={cat.id}
                onClick={() => handleCategoryChange(String(cat.id))}
                className={`rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
                  selectedCategory === String(cat.id)
                    ? "bg-blue-500 text-white"
                    : "border border-zinc-300 text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                }`}
              >
                {cat.name}
              </button>
            ))}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
          </div>
        ) : results.length > 0 ? (
          <>
            <ul className="space-y-3">
              {results.map((result, index) => (
                <li key={result.id}>
                  <Link
                    href={`/posts/${result.id}`}
                    onClick={() => {
                      // Phase 19: 클릭 로그 수집 (LTR implicit feedback)
                      const sessionId = getOrCreateSessionId();
                      const position = currentPage * 20 + index;
                      navigator.sendBeacon(
                        `${API_URL}/api/v1.0/posts/${result.id}/click?q=${encodeURIComponent(query)}&position=${position}&sessionId=${sessionId}`
                      );
                    }}
                    className="block rounded-lg border border-zinc-200 bg-white px-5 py-4 transition-colors hover:border-blue-300 hover:bg-blue-50/50 dark:border-zinc-800 dark:bg-zinc-900 dark:hover:border-blue-700 dark:hover:bg-zinc-800"
                  >
                    <h2 className="text-base font-medium text-blue-600 dark:text-blue-400">
                      {result.title}
                    </h2>
                    {result.snippet && (
                      <p className="mt-1 text-sm text-zinc-600 line-clamp-2 dark:text-zinc-400">
                        {result.snippet}
                      </p>
                    )}
                    <div className="mt-1.5 flex items-center gap-4 text-xs text-zinc-500 dark:text-zinc-400">
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

            {/* 페이지네이션 — 슬라이딩 윈도우 (Google 방식, 최대 15페이지) */}
            {(currentPage > 0 || hasNext) && (() => {
              const MAX_PAGE = 15;           // 백엔드 MAX_SEARCH_PAGE와 동기화
              const windowSize = 10;
              const canGoNext = hasNext && currentPage < MAX_PAGE - 1;
              const lastPage = canGoNext ? Math.min(currentPage + windowSize, MAX_PAGE - 1) : currentPage;
              const startPage = Math.max(0, Math.min(currentPage - Math.floor(windowSize / 2), lastPage - windowSize + 1));
              const endPage = Math.min(lastPage, startPage + windowSize - 1);
              const pages = Array.from({ length: endPage - startPage + 1 }, (_, i) => startPage + i);

              return (
                <div className="mt-8 flex items-center justify-center gap-1">
                  <button
                    onClick={() => navigateSearch(query, currentPage - 1)}
                    disabled={currentPage <= 0}
                    className="rounded-lg border border-zinc-200 px-3 py-1.5 text-sm text-zinc-600 transition-colors hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                  >
                    이전
                  </button>
                  {pages.map((page) => (
                    <button
                      key={page}
                      onClick={() => navigateSearch(query, page)}
                      className={`min-w-[36px] rounded-lg px-2 py-1.5 text-sm transition-colors ${
                        page === currentPage
                          ? "bg-blue-500 font-bold text-white"
                          : "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800"
                      }`}
                    >
                      {page + 1}
                    </button>
                  ))}
                  <button
                    onClick={() => navigateSearch(query, currentPage + 1)}
                    disabled={!canGoNext}
                    className="rounded-lg border border-zinc-200 px-3 py-1.5 text-sm text-zinc-600 transition-colors hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                  >
                    다음
                  </button>
                </div>
              );
            })()}
          </>
        ) : query ? (
          <p className="py-12 text-center text-zinc-500 dark:text-zinc-400">
            검색 결과가 없습니다.
          </p>
        ) : (
          <p className="py-12 text-center text-zinc-500 dark:text-zinc-400">
            검색어를 입력하세요.
          </p>
        )}
      </main>
    </div>
  );
}
