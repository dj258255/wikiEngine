"use client";

import { useState, useEffect, use } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import UserMenu from "../../../components/UserMenu";
import RichEditor from "../../../components/RichEditor";
import { useAuth } from "../../../contexts/AuthContext";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export default function EditPostPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();

  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    fetch(`${API_URL}/api/v1.0/posts/${id}`, { credentials: "include" })
      .then((res) => {
        if (!res.ok) throw new Error("게시글을 찾을 수 없습니다.");
        return res.json();
      })
      .then((json) => {
        const post = json.data;
        setTitle(post.title || "");
        setContent(post.content || "");
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));
  }, [id]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !content.trim()) {
      setError("제목과 내용을 모두 입력해주세요.");
      return;
    }

    setSubmitting(true);
    setError("");

    try {
      const res = await fetch(`${API_URL}/api/v1.0/posts/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ title: title.trim(), content: content.trim() }),
      });

      if (!res.ok) {
        const text = await res.text();
        let message = "수정에 실패했습니다.";
        try {
          const err = JSON.parse(text);
          message = err.message || message;
        } catch {}
        throw new Error(message);
      }

      router.push(`/posts/${id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "오류가 발생했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  if (authLoading || loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-white dark:bg-zinc-950">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (!user) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-white dark:bg-zinc-950">
        <p className="text-zinc-500 dark:text-zinc-400">로그인이 필요합니다.</p>
        <Link href="/login" className="text-blue-600 hover:underline dark:text-blue-400">
          로그인하기
        </Link>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-white dark:bg-zinc-950">
      <header className="border-b border-zinc-200 bg-white px-6 py-4 dark:border-zinc-800 dark:bg-zinc-950">
        <div className="mx-auto flex max-w-5xl items-center gap-6">
          <Link href="/" className="shrink-0 text-xl font-bold text-zinc-900 dark:text-zinc-100">
            위키 검색
          </Link>
          <div className="flex-1" />
          <UserMenu />
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-6 py-8">
        <h1 className="mb-6 text-2xl font-bold text-zinc-900 dark:text-zinc-100">글 수정</h1>

        {error && (
          <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="title" className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
              제목
            </label>
            <input
              id="title"
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={512}
              className="w-full rounded-lg border border-zinc-300 bg-white px-4 py-3 text-zinc-900 outline-none transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:focus:ring-blue-800"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
              내용
            </label>
            {!loading && (
              <RichEditor content={content} onChange={setContent} />
            )}
          </div>

          <div className="flex items-center justify-end gap-3">
            <Link
              href={`/posts/${id}`}
              className="rounded-lg border border-zinc-300 px-5 py-2.5 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
            >
              취소
            </Link>
            <button
              type="submit"
              disabled={submitting}
              className="rounded-lg bg-blue-500 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {submitting ? "수정 중..." : "수정하기"}
            </button>
          </div>
        </form>
      </main>
    </div>
  );
}
