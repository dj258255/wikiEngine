"""
나무위키 Parquet → JSON 변환 스크립트.
NamuWikiJsonParser가 기대하는 JSON 배열 형식으로 변환한다.

사용법:
  python3 scripts/convert-parquet-to-json.py data/dump/namuwiki_20210301.parquet data/dump/namuwiki_20210301.json
"""
import pyarrow.parquet as pq
import pandas as pd
import json
import sys

if len(sys.argv) < 3:
    print("Usage: python3 convert-parquet-to-json.py <input.parquet> <output.json>")
    sys.exit(1)

input_path = sys.argv[1]
output_path = sys.argv[2]

print(f"Reading {input_path}...")
pf = pq.ParquetFile(input_path)
print(f"Total rows: {pf.metadata.num_rows}")

with open(output_path, "w", encoding="utf-8") as f:
    f.write("[\n")
    first = True
    total = 0

    for batch in pf.iter_batches(batch_size=10000, columns=["title", "text"]):
        df = batch.to_pandas()
        for _, row in df.iterrows():
            if not first:
                f.write(",\n")
            json.dump({
                "namespace": 0,
                "title": row["title"] if pd.notna(row["title"]) else "",
                "text": row["text"] if pd.notna(row["text"]) else ""
            }, f, ensure_ascii=False)
            first = False
            total += 1

        if total % 100000 == 0:
            print(f"  {total} rows...")

    f.write("\n]")

print(f"Done: {output_path} ({total} records)")
