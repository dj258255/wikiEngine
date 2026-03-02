"use client";

import { useState, useEffect, Suspense } from "react";
import Link from "next/link";
import { useSearchParams, useRouter } from "next/navigation";
import UserMenu from "../components/UserMenu";
import { useAuth } from "../contexts/AuthContext";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface PostSummary {
  id: number;
  title: string;
  authorId: number;
  categoryId: number | null;
  viewCount: number;
  likeCount: number;
  createdAt: string;
}

interface PageData {
  content: PostSummary[];
  totalPages: number;
  totalElements: number;
  number: number;
}

export default function PostListPage() {
  return (
    <Suspense fallback={
      <div className="flex min-h-screen items-center justify-center bg-white dark:bg-zinc-950">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
      </div>
    }>
      <PostListContent />
    </Suspense>
  );
}

function PostListContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { user } = useAuth();
  const page = Number(searchParams.get("page") || "0");

  const [pageData, setPageData] = useState<PageData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetch(`${API_URL}/api/v1.0/posts?page=${page}&size=20`)
      .then((res) => res.json())
      .then((json) => setPageData(json.data))
      .catch(() => setPageData(null))
      .finally(() => setLoading(false));
  }, [page]);

  const goToPage = (p: number) => {
    router.push(`/posts?page=${p}`);
  };

  const formatDate = (iso: string) => {
    const d = new Date(iso);
    return d.toLocaleDateString("ko-KR", { year: "numeric", month: "short", day: "numeric" });
  };

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950">
      <header className="border-b border-zinc-200 bg-white px-6 py-4 dark:border-zinc-800 dark:bg-zinc-950">
        <div className="mx-auto flex max-w-5xl items-center gap-6">
          <Link href="/" className="shrink-0 text-xl font-bold text-zinc-900 dark:text-zinc-100">
            위키 검색
          </Link>
          <div className="flex-1" />
          {user && (
            <Link
              href="/posts/new"
              className="rounded-lg bg-blue-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-600"
            >
              글쓰기
            </Link>
          )}
          <UserMenu />
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-6 py-8">
        <h1 className="mb-6 text-2xl font-bold text-zinc-900 dark:text-zinc-100">게시판</h1>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
          </div>
        ) : !pageData || pageData.content.length === 0 ? (
          <p className="py-12 text-center text-zinc-500 dark:text-zinc-400">게시글이 없습니다.</p>
        ) : (
          <>
            <div className="overflow-hidden rounded-lg border border-zinc-200 dark:border-zinc-800">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-zinc-200 bg-zinc-50 dark:border-zinc-800 dark:bg-zinc-900">
                  <tr>
                    <th className="px-4 py-3 font-medium text-zinc-500 dark:text-zinc-400">제목</th>
                    <th className="hidden w-24 px-4 py-3 text-center font-medium text-zinc-500 sm:table-cell dark:text-zinc-400">조회</th>
                    <th className="hidden w-24 px-4 py-3 text-center font-medium text-zinc-500 sm:table-cell dark:text-zinc-400">좋아요</th>
                    <th className="hidden w-32 px-4 py-3 text-right font-medium text-zinc-500 sm:table-cell dark:text-zinc-400">날짜</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800">
                  {pageData.content.map((post) => (
                    <tr key={post.id} className="transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-900/50">
                      <td className="px-4 py-3">
                        <Link
                          href={`/wiki/${post.id}`}
                          className="font-medium text-zinc-900 hover:text-blue-600 dark:text-zinc-100 dark:hover:text-blue-400"
                        >
                          {post.title}
                        </Link>
                        <div className="mt-1 flex items-center gap-3 text-xs text-zinc-400 sm:hidden dark:text-zinc-500">
                          <span>조회 {post.viewCount.toLocaleString()}</span>
                          <span>좋아요 {post.likeCount.toLocaleString()}</span>
                          <span>{formatDate(post.createdAt)}</span>
                        </div>
                      </td>
                      <td className="hidden px-4 py-3 text-center text-zinc-500 sm:table-cell dark:text-zinc-400">
                        {post.viewCount.toLocaleString()}
                      </td>
                      <td className="hidden px-4 py-3 text-center text-zinc-500 sm:table-cell dark:text-zinc-400">
                        {post.likeCount.toLocaleString()}
                      </td>
                      <td className="hidden px-4 py-3 text-right text-zinc-500 sm:table-cell dark:text-zinc-400">
                        {formatDate(post.createdAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            <div className="mt-6 flex items-center justify-between">
              <p className="text-sm text-zinc-500 dark:text-zinc-400">
                총 {pageData.totalElements.toLocaleString()}건
              </p>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => goToPage(page - 1)}
                  disabled={page === 0}
                  className="rounded-lg border border-zinc-300 px-3 py-2 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                >
                  이전
                </button>
                <span className="px-2 text-sm text-zinc-500 dark:text-zinc-400">
                  {page + 1} / {pageData.totalPages}
                </span>
                <button
                  onClick={() => goToPage(page + 1)}
                  disabled={page + 1 >= pageData.totalPages}
                  className="rounded-lg border border-zinc-300 px-3 py-2 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                >
                  다음
                </button>
              </div>
            </div>
          </>
        )}
      </main>
    </div>
  );
}
