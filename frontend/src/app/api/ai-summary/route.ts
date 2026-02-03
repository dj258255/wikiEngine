import { NextRequest, NextResponse } from "next/server";

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const query = searchParams.get("q") || "";

  // TODO: 실제 AI API (OpenAI, Claude 등) 연동으로 교체
  // 지금은 더미 요약 반환
  const dummySummaries: Record<string, string> = {
    대한민국:
      "대한민국은 동아시아 한반도 남부에 위치한 민주공화국입니다. 수도는 서울이며, 약 5,100만 명의 인구가 거주하고 있습니다. 세계 10위권의 경제 대국으로, 반도체, 자동차, K-pop 등으로 유명합니다.",
    서울:
      "서울특별시는 대한민국의 수도이자 최대 도시입니다. 인구 약 950만 명이 거주하며, 한강을 중심으로 남북으로 나뉩니다. 600년 이상의 역사를 가진 도시로, 경복궁 등 조선시대 유적과 현대적 도시 경관이 공존합니다.",
    한국어:
      "한국어는 약 7,700만 명이 모국어로 사용하는 언어로, 세계 13위 사용 언어입니다. 1443년 세종대왕이 창제한 한글로 표기하며, 과학적이고 배우기 쉬운 문자 체계로 평가받습니다.",
  };

  // 쿼리와 매칭되는 요약 찾기
  let summary = "";
  for (const [key, value] of Object.entries(dummySummaries)) {
    if (query.includes(key) || key.includes(query)) {
      summary = value;
      break;
    }
  }

  // 매칭 안되면 기본 메시지
  if (!summary && query) {
    summary = `"${query}"에 대한 검색 결과를 분석 중입니다. 위키피디아 문서들을 기반으로 관련 정보를 찾아보세요.`;
  }

  return NextResponse.json({ summary, query });
}
