import { NextRequest, NextResponse } from "next/server";

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const query = searchParams.get("q") || "";

  // TODO: 실제 검색 로직으로 교체 (위키피디아 덤프 데이터 검색)
  // 지금은 더미 데이터 반환
  const dummyResults = [
    {
      id: "1",
      title: "대한민국",
      excerpt:
        "대한민국은 동아시아의 한반도 남부에 위치한 민주공화국이다. 수도는 서울특별시이며, 국기는 태극기, 국가는 애국가, 공용어는 한국어이다.",
    },
    {
      id: "2",
      title: "서울특별시",
      excerpt:
        "서울특별시는 대한민국의 수도이자 최대 도시이다. 한반도 중앙에 위치하며, 한강을 사이에 두고 남북으로 나뉜다.",
    },
    {
      id: "3",
      title: "한국어",
      excerpt:
        "한국어는 대한민국과 조선민주주의인민공화국의 공용어로, 대한민국에서는 한국어, 북한에서는 조선어라고 부른다.",
    },
  ].filter((item) =>
    item.title.toLowerCase().includes(query.toLowerCase())
  );

  return NextResponse.json({ results: dummyResults, query });
}
