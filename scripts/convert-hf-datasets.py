"""
HuggingFace 데이터셋 → NamuWikiJsonParser 호환 JSON 변환 스크립트.

3개 데이터셋을 다운로드하여 기존 WikiImportService가 읽을 수 있는
JSON 배열 형식으로 변환한다.

출력 형식 (NamuWikiJsonParser 호환):
[
  {"namespace": 0, "title": "제목", "text": "본문..."},
  ...
]

사용법:
  # 전체 변환 (3개 데이터셋)
  python3 scripts/convert-hf-datasets.py --output-dir data/dump

  # 개별 데이터셋만
  python3 scripts/convert-hf-datasets.py --output-dir data/dump --dataset newstext
  python3 scripts/convert-hf-datasets.py --output-dir data/dump --dataset webtext
  python3 scripts/convert-hf-datasets.py --output-dir data/dump --dataset c4ko

필요 패키지:
  pip3 install datasets pyarrow pandas
"""
import argparse
import json
import os
import re
import sys
import time
from datasets import load_dataset


# ── 제목 추출 ──────────────────────────────────────────────────

# 첫 문장 구분 패턴: 마침표/물음표/느낌표 + 공백 또는 줄바꿈
SENTENCE_END = re.compile(r'[.?!。]\s')

def extract_title_from_text(text, max_title_len=120):
    """본문 첫 문장을 제목으로 추출한다. 없으면 앞부분을 자른다."""
    if not text:
        return "", ""

    # 첫 줄이 짧으면(80자 이하) 그것을 제목으로
    first_line_end = text.find('\n')
    if 0 < first_line_end <= 80:
        title = text[:first_line_end].strip()
        body = text[first_line_end:].strip()
        if title and body:
            return title, body

    # 첫 문장 경계 탐색
    match = SENTENCE_END.search(text, 10)  # 최소 10자 이후부터
    if match and match.end() <= max_title_len:
        title = text[:match.start() + 1].strip()
        body = text[match.end():].strip()
        if title and body:
            return title, body

    # 문장 경계 없으면 max_title_len 지점에서 공백 기준 자르기
    if len(text) > max_title_len:
        cut = text.rfind(' ', 0, max_title_len)
        if cut > 20:
            return text[:cut].strip(), text[cut:].strip()

    # 짧은 텍스트는 그대로
    return text[:max_title_len].strip(), text


# ── 1. Korean Newstext Dump ────────────────────────────────────

def convert_newstext(output_path):
    """
    sieu-n/korean-newstext-dump 변환.
    row 구조: '제목: xxx' / '내용: xxx' / 본문 단락 / 빈줄
    여러 row를 합쳐 하나의 기사(title + text)로 재구성한다.
    """
    print(f"[newstext] 다운로드 시작...")
    ds = load_dataset('sieu-n/korean-newstext-dump', split='train', streaming=True)

    article_count = 0
    current_title = None
    current_paragraphs = []

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write('[\n')
        first = True

        def flush_article():
            nonlocal first, article_count
            if current_title and current_paragraphs:
                body = '\n\n'.join(current_paragraphs)
                if len(body) < 50:  # 너무 짧은 기사 제외
                    return
                if not first:
                    f.write(',\n')
                json.dump({
                    "namespace": 0,
                    "title": current_title[:512],
                    "text": body
                }, f, ensure_ascii=False)
                first = False
                article_count += 1
                if article_count % 10000 == 0:
                    print(f"  [newstext] {article_count} articles...")

        for row in ds:
            text = row.get('text', '')
            stripped = text.strip() if text else ''

            if stripped.startswith('제목:'):
                # 이전 기사 저장
                flush_article()
                current_title = stripped[3:].strip()
                current_paragraphs = []

            elif stripped.startswith('내용:'):
                paragraph = stripped[3:].strip()
                if paragraph:
                    current_paragraphs.append(paragraph)

            elif stripped:
                # 일반 본문 단락
                if current_title is not None:
                    current_paragraphs.append(stripped)

        # 마지막 기사
        flush_article()
        f.write('\n]')

    print(f"[newstext] 완료: {output_path} ({article_count} articles)")
    return article_count


# ── 2. KOREAN-WEBTEXT ──────────────────────────────────────────

def convert_webtext(output_path):
    """
    HAERAE-HUB/KOREAN-WEBTEXT 변환.
    text 필드만 존재 → 첫 문장/줄을 title로 추출.
    """
    print(f"[webtext] 다운로드 시작...")
    ds = load_dataset('HAERAE-HUB/KOREAN-WEBTEXT', split='train', streaming=True)

    count = 0
    skipped = 0

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write('[\n')
        first = True

        for row in ds:
            text = row.get('text', '')
            if not text or len(text) < 100:
                skipped += 1
                continue

            title, body = extract_title_from_text(text)
            if not title or len(body) < 50:
                skipped += 1
                continue

            if not first:
                f.write(',\n')
            json.dump({
                "namespace": 0,
                "title": title[:512],
                "text": body
            }, f, ensure_ascii=False)
            first = False
            count += 1

            if count % 50000 == 0:
                print(f"  [webtext] {count} docs... (skipped: {skipped})")

        f.write('\n]')

    print(f"[webtext] 완료: {output_path} ({count} docs, skipped: {skipped})")
    return count


# ── 3. c4-ko-cleaned-2 ────────────────────────────────────────

def convert_c4ko(output_path):
    """
    blueapple8259/c4-ko-cleaned-2 변환.
    text 필드만 존재 → 첫 문장/줄을 title로 추출.
    """
    print(f"[c4ko] 다운로드 시작...")
    ds = load_dataset('blueapple8259/c4-ko-cleaned-2', split='train', streaming=True)

    count = 0
    skipped = 0

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write('[\n')
        first = True

        for row in ds:
            text = row.get('text', '')
            if not text or len(text) < 100:
                skipped += 1
                continue

            title, body = extract_title_from_text(text)
            if not title or len(body) < 50:
                skipped += 1
                continue

            if not first:
                f.write(',\n')
            json.dump({
                "namespace": 0,
                "title": title[:512],
                "text": body
            }, f, ensure_ascii=False)
            first = False
            count += 1

            if count % 50000 == 0:
                print(f"  [c4ko] {count} docs... (skipped: {skipped})")

        f.write('\n]')

    print(f"[c4ko] 완료: {output_path} ({count} docs, skipped: {skipped})")
    return count


# ── main ───────────────────────────────────────────────────────

DATASETS = {
    'newstext': ('korean-newstext.json', convert_newstext),
    'webtext':  ('korean-webtext.json',  convert_webtext),
    'c4ko':     ('c4-ko-cleaned.json',   convert_c4ko),
}

def main():
    parser = argparse.ArgumentParser(description='HuggingFace 데이터셋 → JSON 변환')
    parser.add_argument('--output-dir', required=True, help='출력 디렉토리 (예: data/dump)')
    parser.add_argument('--dataset', choices=list(DATASETS.keys()),
                        help='특정 데이터셋만 변환 (생략 시 전체)')
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)

    targets = [args.dataset] if args.dataset else list(DATASETS.keys())

    total_start = time.time()
    total_count = 0

    for name in targets:
        filename, converter = DATASETS[name]
        output_path = os.path.join(args.output_dir, filename)
        print(f"\n{'='*60}")
        print(f"변환 시작: {name} → {output_path}")
        print(f"{'='*60}")
        start = time.time()
        count = converter(output_path)
        elapsed = time.time() - start
        total_count += count
        print(f"소요 시간: {elapsed:.0f}초")

    total_elapsed = time.time() - total_start
    print(f"\n{'='*60}")
    print(f"전체 완료: {total_count}건, {total_elapsed:.0f}초")
    print(f"{'='*60}")
    print(f"\n임포트 방법:")
    print(f"  .env 파일:")
    print(f"    WIKI_IMPORT_ENABLED=true")
    for name in targets:
        filename, _ = DATASETS[name]
        path = os.path.join(args.output_dir, filename)
        print(f"    # {path}")
    paths = ','.join(os.path.join(args.output_dir, DATASETS[n][0]) for n in targets)
    print(f"    WIKI_IMPORT_PATH={paths}")


if __name__ == '__main__':
    main()
