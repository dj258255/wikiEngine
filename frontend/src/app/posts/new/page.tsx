"use client";

import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import UserMenu from "../../components/UserMenu";
import RichEditor from "../../components/RichEditor";
import { useAuth } from "../../contexts/AuthContext";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export default function NewPostPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();

  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [categories, setCategories] = useState<{ id: number; name: string }[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [catOpen, setCatOpen] = useState(false);
  const catRef = useRef<HTMLDivElement>(null);

  // 외부 클릭 시 드롭다운 닫기
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (catRef.current && !catRef.current.contains(e.target as Node)) setCatOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  useEffect(() => {
    fetch(`${API_URL}/api/v1.0/categories`)
      .then(res => res.ok ? res.json() : null)
      .then(json => { if (json?.data) setCategories(json.data); })
      .catch(() => {});
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !content.trim()) {
      setError("제목과 내용을 모두 입력해주세요.");
      return;
    }
    if (!categoryId) {
      setError("카테고리를 선택해주세요.");
      return;
    }

    setSubmitting(true);
    setError("");

    try {
      const res = await fetch(`${API_URL}/api/v1.0/posts`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({
          title: title.trim(),
          content: content.trim(),
          categoryId: categoryId ? Number(categoryId) : null,
        }),
      });

      if (!res.ok) {
        const text = await res.text();
        let message = "게시글 작성에 실패했습니다.";
        try {
          const err = JSON.parse(text);
          message = err.message || message;
        } catch {}
        throw new Error(message);
      }

      const json = await res.json();
      const postId = json.data?.id;
      router.push(postId ? `/posts/${postId}` : "/posts");
    } catch (err) {
      setError(err instanceof Error ? err.message : "오류가 발생했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  if (authLoading) {
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
          <Link href="/posts" className="text-sm font-medium text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200">
            게시판
          </Link>
          <div className="flex-1" />
          <UserMenu />
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-6 py-8">
        <h1 className="mb-6 text-2xl font-bold text-zinc-900 dark:text-zinc-100">새 글 작성</h1>

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
              placeholder="제목을 입력하세요"
              className="w-full rounded-lg border border-zinc-300 bg-white px-4 py-3 text-zinc-900 outline-none transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:focus:ring-blue-800"
              autoFocus
            />
          </div>

          <div ref={catRef} className="relative">
            <label className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
              카테고리
            </label>
            <button
              type="button"
              onClick={() => setCatOpen(!catOpen)}
              className={`flex w-full items-center justify-between rounded-2xl border px-4 py-3 text-left text-sm outline-none transition-all ${
                catOpen
                  ? "border-blue-400 ring-2 ring-blue-200 dark:border-blue-500 dark:ring-blue-800"
                  : "border-zinc-300 dark:border-zinc-700"
              } bg-white/80 backdrop-blur-xl dark:bg-zinc-900/80`}
            >
              <span className={categoryId ? "text-zinc-900 dark:text-zinc-100" : "text-zinc-400 dark:text-zinc-500"}>
                {categoryId ? categories.find(c => String(c.id) === categoryId)?.name : "카테고리를 선택하세요"}
              </span>
              <svg
                xmlns="http://www.w3.org/2000/svg"
                fill="none"
                viewBox="0 0 24 24"
                strokeWidth={2}
                stroke="currentColor"
                className={`h-4 w-4 text-zinc-400 transition-transform ${catOpen ? "rotate-180" : ""}`}
              >
                <path strokeLinecap="round" strokeLinejoin="round" d="m19.5 8.25-7.5 7.5-7.5-7.5" />
              </svg>
            </button>
            {catOpen && (
              <div className="absolute left-0 right-0 top-full z-50 mt-1.5 max-h-[220px] overflow-y-auto rounded-2xl border border-zinc-200/60 bg-white/70 shadow-xl shadow-zinc-200/40 backdrop-blur-2xl dark:border-zinc-700/60 dark:bg-zinc-900/70 dark:shadow-zinc-900/40">
                {categories.map((cat) => (
                  <button
                    key={cat.id}
                    type="button"
                    onClick={() => { setCategoryId(String(cat.id)); setCatOpen(false); }}
                    className={`flex w-full items-center px-4 py-2.5 text-left text-sm transition-colors ${
                      String(cat.id) === categoryId
                        ? "bg-blue-50/80 font-medium text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
                        : "text-zinc-700 hover:bg-zinc-50/80 dark:text-zinc-300 dark:hover:bg-zinc-800/60"
                    }`}
                  >
                    {cat.name}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
              내용
            </label>
            <RichEditor content="" onChange={setContent} placeholder="내용을 입력하세요..." />
          </div>

          <div className="flex items-center justify-end gap-3">
            <Link
              href="/posts"
              className="rounded-lg border border-zinc-300 px-5 py-2.5 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
            >
              취소
            </Link>
            <button
              type="submit"
              disabled={submitting}
              className="rounded-lg bg-blue-500 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {submitting ? "작성 중..." : "작성하기"}
            </button>
          </div>
        </form>
      </main>
    </div>
  );
}
