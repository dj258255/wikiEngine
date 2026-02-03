"use client";

import { useState, useEffect, use } from "react";
import Link from "next/link";

interface Article {
  id: string;
  title: string;
  content: string;
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
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-6 py-8">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent"></div>
          </div>
        ) : article ? (
          <article>
            <h1 className="mb-6 text-3xl font-bold text-zinc-900 dark:text-zinc-100">
              {article.title}
            </h1>
            <div className="prose prose-zinc max-w-none dark:prose-invert">
              {article.content.split("\n").map((paragraph, index) => (
                <p key={index} className="mb-4 text-zinc-700 dark:text-zinc-300">
                  {paragraph}
                </p>
              ))}
            </div>
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
