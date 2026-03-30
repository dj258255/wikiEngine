"use client";

import { useState, useEffect, use, useMemo } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import UserMenu from "../../components/UserMenu";
import { useAuth } from "../../contexts/AuthContext";
import { parseWikiContent } from "../../lib/wikiParser";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface Article {
  id: number;
  title: string;
  content: string;
  authorId: number;
  viewCount: number;
  likeCount: number;
  createdAt: string;
  updatedAt: string;
}

export default function WikiArticlePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const router = useRouter();
  const { user } = useAuth();
  const [article, setArticle] = useState<Article | null>(null);
  const [loading, setLoading] = useState(true);
  const [liked, setLiked] = useState(false);
  const [likeCount, setLikeCount] = useState(0);
  const [likeLoading, setLikeLoading] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    const fetchArticle = async () => {
      try {
        const res = await fetch(`${API_URL}/api/v1.0/posts/${id}`, {
          credentials: "include",
        });
        if (!res.ok) {
          setArticle(null);
          return;
        }
        const json = await res.json();
        const post = json.data;
        setArticle(post);
        setLikeCount(post.likeCount || 0);
      } catch {
        setArticle(null);
      } finally {
        setLoading(false);
      }
    };

    fetchArticle();
  }, [id]);

  // Phase 19: Dwell time 추적 — 페이지 이탈 시 Beacon API로 전송
  useEffect(() => {
    const pageLoadTime = Date.now();
    const sessionId = sessionStorage.getItem("search_session_id");
    if (!sessionId) return; // 검색에서 진입하지 않은 경우 추적 불필요

    const sendDwell = () => {
      const dwellTimeMs = Date.now() - pageLoadTime;
      if (dwellTimeMs < 500) return; // 0.5초 미만 = misclick 필터링
      navigator.sendBeacon(
        `${API_URL}/api/v1.0/posts/${id}/dwell?sessionId=${sessionId}&dwellTimeMs=${dwellTimeMs}`
      );
    };

    // visibilitychange가 pagehide보다 신뢰도 높음 (82.3% vs 73.4%, NicJ.net 벤치마크)
    const handleVisibilityChange = () => {
      if (document.visibilityState === "hidden") sendDwell();
    };
    document.addEventListener("visibilitychange", handleVisibilityChange);
    window.addEventListener("pagehide", sendDwell);

    return () => {
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      window.removeEventListener("pagehide", sendDwell);
    };
  }, [id]);

  // NOTE: parseWikiContent is a local parser that converts wiki markup to HTML.
  // The content comes from the backend (user-authored wiki text), and the parser
  // handles HTML escaping internally before applying markup transformations.
  const parsed = useMemo(() => {
    if (!article?.content) return null;
    return parseWikiContent(article.content);
  }, [article?.content]);

  const isAuthor = user && article && user.userId === article.authorId;

  const handleLike = async () => {
    if (!user || likeLoading) return;
    setLikeLoading(true);

    try {
      if (liked) {
        const res = await fetch(`${API_URL}/api/v1.0/posts/${id}/like`, {
          method: "DELETE",
          credentials: "include",
        });
        if (res.ok) {
          setLiked(false);
          setLikeCount((prev) => Math.max(0, prev - 1));
        }
      } else {
        const res = await fetch(`${API_URL}/api/v1.0/posts/${id}/like`, {
          method: "POST",
          credentials: "include",
        });
        if (res.ok) {
          setLiked(true);
          setLikeCount((prev) => prev + 1);
        } else if (res.status === 409) {
          setLiked(true);
        }
      }
    } catch {
      // 네트워크 오류 무시
    } finally {
      setLikeLoading(false);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      const res = await fetch(`${API_URL}/api/v1.0/posts/${id}`, {
        method: "DELETE",
        credentials: "include",
      });
      if (res.ok || res.status === 204) {
        router.push("/search");
      } else {
        const text = await res.text();
        let message = "삭제에 실패했습니다.";
        try {
          const err = JSON.parse(text);
          message = err.message || message;
        } catch {}
        alert(message);
      }
    } catch {
      alert("오류가 발생했습니다.");
    } finally {
      setDeleting(false);
      setShowDeleteConfirm(false);
    }
  };

  const formatDate = (iso: string) => {
    try {
      return new Date(iso).toLocaleDateString("ko-KR", {
        year: "numeric",
        month: "long",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      });
    } catch {
      return "";
    }
  };

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950">
      <header className="border-b border-zinc-200 bg-white px-6 py-4 dark:border-zinc-800 dark:bg-zinc-950">
        <div className="mx-auto flex max-w-5xl items-center gap-6">
          <Link
            href="/"
            className="shrink-0 text-xl font-bold text-zinc-900 dark:text-zinc-100"
          >
            위키 검색
          </Link>
          <Link
            href="/posts"
            className="text-sm font-medium text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
          >
            게시판
          </Link>
          <div className="flex-1" />
          <UserMenu />
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-6 py-8">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent"></div>
          </div>
        ) : article && parsed ? (
          <article>
            <div className="mb-6 border-b border-zinc-200 pb-6 dark:border-zinc-800">
              <div className="flex items-start justify-between gap-4">
                <h1 className="text-3xl font-bold text-zinc-900 dark:text-zinc-100">
                  {article.title}
                </h1>
                {isAuthor && (
                  <div className="flex shrink-0 items-center gap-2">
                    <Link
                      href={`/posts/${id}/edit`}
                      className="rounded-lg border border-zinc-300 px-3 py-1.5 text-sm font-medium text-zinc-600 transition-colors hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                    >
                      수정
                    </Link>
                    <button
                      onClick={() => setShowDeleteConfirm(true)}
                      className="rounded-lg border border-red-300 px-3 py-1.5 text-sm font-medium text-red-600 transition-colors hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-900/20"
                    >
                      삭제
                    </button>
                  </div>
                )}
              </div>
              <div className="mt-3 flex flex-wrap items-center gap-4 text-sm text-zinc-500 dark:text-zinc-400">
                {parsed.format !== "plain" && (
                  <span className="rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
                    {parsed.format === "namumark" ? "NamuMark" : "MediaWiki"}
                  </span>
                )}
                <span className="flex items-center gap-1">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z" />
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
                  </svg>
                  {article.viewCount.toLocaleString()}
                </span>
                {/* 좋아요 버튼 */}
                {user ? (
                  <button
                    onClick={handleLike}
                    disabled={likeLoading}
                    className={`flex items-center gap-1 rounded-full px-2 py-0.5 transition-colors ${
                      liked
                        ? "bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400"
                        : "hover:bg-zinc-100 dark:hover:bg-zinc-800"
                    }`}
                  >
                    <svg
                      xmlns="http://www.w3.org/2000/svg"
                      fill={liked ? "currentColor" : "none"}
                      viewBox="0 0 24 24"
                      strokeWidth={1.5}
                      stroke="currentColor"
                      className="h-4 w-4"
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12Z" />
                    </svg>
                    {likeCount.toLocaleString()}
                  </button>
                ) : (
                  <span className="flex items-center gap-1">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12Z" />
                    </svg>
                    {likeCount.toLocaleString()}
                  </span>
                )}
                {article.createdAt && (
                  <span>{formatDate(article.createdAt)}</span>
                )}
              </div>
            </div>

            {/* Categories */}
            {parsed.categories.length > 0 && (
              <div className="mb-6 flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium text-zinc-500 dark:text-zinc-400">분류:</span>
                {parsed.categories.map((cat, i) => (
                  <span
                    key={i}
                    className="rounded-full bg-blue-50 px-3 py-1 text-xs font-medium text-blue-700 dark:bg-blue-900/30 dark:text-blue-300"
                  >
                    {cat}
                  </span>
                ))}
              </div>
            )}

            {/* Parsed wiki content */}
            <div
              className="wiki-content prose prose-zinc max-w-none dark:prose-invert"
              dangerouslySetInnerHTML={{ __html: parsed.html }}
            />
          </article>
        ) : (
          <div className="py-12 text-center">
            <p className="text-zinc-500 dark:text-zinc-400">
              문서를 찾을 수 없습니다.
            </p>
            <Link
              href="/"
              className="mt-4 inline-block text-blue-600 hover:underline dark:text-blue-400"
            >
              홈으로 돌아가기
            </Link>
          </div>
        )}
      </main>

      {/* 삭제 확인 모달 */}
      {showDeleteConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="mx-4 w-full max-w-sm rounded-xl bg-white p-6 shadow-xl dark:bg-zinc-900">
            <h3 className="mb-2 text-lg font-bold text-zinc-900 dark:text-zinc-100">
              게시글 삭제
            </h3>
            <p className="mb-6 text-sm text-zinc-500 dark:text-zinc-400">
              정말 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setShowDeleteConfirm(false)}
                disabled={deleting}
                className="rounded-lg border border-zinc-300 px-4 py-2 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
              >
                취소
              </button>
              <button
                onClick={handleDelete}
                disabled={deleting}
                className="rounded-lg bg-red-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-red-600 disabled:opacity-50"
              >
                {deleting ? "삭제 중..." : "삭제"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
