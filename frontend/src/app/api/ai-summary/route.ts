import { NextRequest } from "next/server";

const API_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

/**
 * Spring Boot SSE 스트리밍 엔드포인트로 프록시.
 * 백엔드에서 Lucene 검색 → Gemini SSE 스트리밍 → 토큰 단위 전달.
 *
 * 프론트엔드 fetch + ReadableStream으로 수신하여 타이핑 효과 구현.
 */
export async function GET(request: NextRequest) {
  const query = request.nextUrl.searchParams.get("q") || "";

  if (!query.trim()) {
    return new Response("data: [DONE]\n\n", {
      headers: { "Content-Type": "text/event-stream" },
    });
  }

  try {
    const res = await fetch(
      `${API_URL}/api/v1.0/posts/search/ai-summary?q=${encodeURIComponent(query)}`,
      {
        cache: "no-store",
        headers: { Accept: "text/event-stream" },
      }
    );

    if (!res.ok || !res.body) {
      return new Response(
        `event: error\ndata: AI 요약을 가져올 수 없습니다.\n\nevent: done\ndata: [DONE]\n\n`,
        { headers: { "Content-Type": "text/event-stream" } }
      );
    }

    // Spring Boot SSE 스트림을 그대로 패스스루
    return new Response(res.body, {
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        Connection: "keep-alive",
      },
    });
  } catch {
    return new Response(
      `event: error\ndata: AI 요약 연결에 실패했습니다.\n\nevent: done\ndata: [DONE]\n\n`,
      { headers: { "Content-Type": "text/event-stream" } }
    );
  }
}
