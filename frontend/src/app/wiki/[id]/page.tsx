"use client";

import { useState, useEffect, use, useMemo } from "react";
import Link from "next/link";
import UserMenu from "../../components/UserMenu";
import { parseWikiContent } from "../../lib/wikiParser";

interface Article {
  id: string;
  title: string;
  content: string;
  viewCount?: number;
  likeCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export default function WikiArticlePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const [article, setArticle] = useState<Article | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchArticle = async () => {
      try {
        const res = await fetch(`/api/wiki/${id}`);
        const data = await res.json();
        setArticle(data.article || null);
      } catch (error) {
        console.error("Fetch error:", error);
        setArticle(null);
      } finally {
        setLoading(false);
      }
    };

    fetchArticle();
  }, [id]);

  const parsed = useMemo(() => {
    if (!article?.content) return null;
    return parseWikiContent(article.content);
  }, [article?.content]);

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
              <h1 className="text-3xl font-bold text-zinc-900 dark:text-zinc-100">
                {article.title}
              </h1>
              <div className="mt-3 flex flex-wrap items-center gap-4 text-sm text-zinc-500 dark:text-zinc-400">
                {parsed.format !== "plain" && (
                  <span className="rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
                    {parsed.format === "namumark" ? "NamuMark" : "MediaWiki"}
                  </span>
                )}
                {article.viewCount !== undefined && (
                  <span className="flex items-center gap-1">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z" />
                      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" />
                    </svg>
                    {article.viewCount.toLocaleString()}
                  </span>
                )}
                {article.likeCount !== undefined && article.likeCount > 0 && (
                  <span className="flex items-center gap-1">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="h-4 w-4">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M6.633 10.25c.806 0 1.533-.446 2.031-1.08a9.041 9.041 0 0 1 2.861-2.4c.723-.384 1.35-.956 1.653-1.715a4.498 4.498 0 0 0 .322-1.672V2.75a.75.75 0 0 1 .75-.75 2.25 2.25 0 0 1 2.25 2.25c0 1.152-.26 2.243-.723 3.218-.266.558.107 1.282.725 1.282m0 0h3.126c1.026 0 1.945.694 2.054 1.715.045.422.068.85.068 1.285a11.95 11.95 0 0 1-2.649 7.521c-.388.482-.987.729-1.605.729H13.48c-.483 0-.964-.078-1.423-.23l-3.114-1.04a4.501 4.501 0 0 0-1.423-.23H5.904m7.594 0H5.904m0 0a48.667 48.667 0 0 0-.713.189 1.071 1.071 0 0 0-.712.993c.017.394.049.786.096 1.175.139 1.15-.867 2.143-2.025 2.143H2.25a.75.75 0 0 1-.75-.75v-4.566c0-.344.072-.682.21-.994a11.993 11.993 0 0 0 3.894-5.24A2.26 2.26 0 0 1 5.904 9.75" />
                    </svg>
                    {article.likeCount.toLocaleString()}
                  </span>
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
    </div>
  );
}
