import { NextRequest, NextResponse } from "next/server";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const query = searchParams.get("q") || "";
  const page = searchParams.get("page") || "0";
  const size = searchParams.get("size") || "20";

  if (!query.trim()) {
    return NextResponse.json({ results: [], totalPages: 0, totalElements: 0 });
  }

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    const res = await fetch(
      `${API_URL}/api/v1.0/posts/search?q=${encodeURIComponent(query)}&page=${page}&size=${size}`,
      { cache: "no-store", signal: controller.signal }
    );
    clearTimeout(timeout);

    if (!res.ok) {
      return NextResponse.json({ results: [], totalPages: 0, totalElements: 0 });
    }

    const json = await res.json();
    const pageData = json.data;

    const results = (pageData.content || []).map(
      (post: { id: number; title: string; viewCount: number; likeCount: number; createdAt: string }) => ({
        id: String(post.id),
        title: post.title,
        viewCount: post.viewCount,
        likeCount: post.likeCount,
        createdAt: post.createdAt,
      })
    );

    return NextResponse.json({
      results,
      totalPages: pageData.totalPages || 0,
      totalElements: pageData.totalElements || 0,
      currentPage: pageData.number || 0,
    });
  } catch {
    return NextResponse.json({ results: [], totalPages: 0, totalElements: 0 });
  }
}
