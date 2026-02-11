import { NextRequest, NextResponse } from "next/server";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params;

  try {
    const res = await fetch(`${API_URL}/api/v1.0/posts/${id}`, {
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
    });

    if (!res.ok) {
      return NextResponse.json({ article: null }, { status: res.status });
    }

    const data = await res.json();
    const post = data.data;
    const article = {
      id: String(post.id),
      title: post.title,
      content: post.content || "",
      authorId: post.authorId,
      categoryId: post.categoryId,
      viewCount: post.viewCount,
      likeCount: post.likeCount,
      createdAt: post.createdAt,
      updatedAt: post.updatedAt,
    };

    return NextResponse.json({ article });
  } catch {
    return NextResponse.json({ article: null }, { status: 502 });
  }
}
