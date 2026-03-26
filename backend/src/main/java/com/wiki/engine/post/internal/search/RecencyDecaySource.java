package com.wiki.engine.post.internal.search;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.IndexSearcher;

import java.io.IOException;

/**
 * Exponential decay 기반 최신성 점수 소스.
 * createdAt(epoch millis)을 읽어 현재 시각 대비 경과일 수로 감쇠 점수를 계산한다.
 *
 * score = weight * exp(-lambda * ageDays)
 * lambda = ln(2) / halfLifeDays → 반감기가 지나면 가중치 절반
 *
 * LongField은 SORTED_NUMERIC DocValues를 저장하므로
 * getSortedNumericDocValues()로 읽어야 한다 (getNumericDocValues는 NUMERIC 전용).
 */
public final class RecencyDecaySource extends DoubleValuesSource {

    private final long nowMillis;
    private final double lambda;
    private final float weight;

    public RecencyDecaySource(long nowMillis, double lambda, float weight) {
        this.nowMillis = nowMillis;
        this.lambda = lambda;
        this.weight = weight;
    }

    @Override
    public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
        SortedNumericDocValues createdAtValues = ctx.reader().getSortedNumericDocValues("createdAt");
        if (createdAtValues == null) {
            return DoubleValues.EMPTY;
        }
        return new DoubleValues() {
            @Override
            public double doubleValue() throws IOException {
                long createdAtMillis = createdAtValues.nextValue();
                double ageDays = (nowMillis - createdAtMillis) / 86_400_000.0;
                return weight * Math.exp(-lambda * Math.max(ageDays, 0));
            }

            @Override
            public boolean advanceExact(int doc) throws IOException {
                return createdAtValues.advanceExact(doc);
            }
        };
    }

    @Override
    public boolean needsScores() {
        return false;
    }

    @Override
    public DoubleValuesSource rewrite(IndexSearcher searcher) {
        return this;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(nowMillis) * 31 + Float.hashCode(weight);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RecencyDecaySource other)) return false;
        return nowMillis == other.nowMillis && lambda == other.lambda && weight == other.weight;
    }

    @Override
    public String toString() {
        return "recencyDecay(weight=" + weight + ", lambda=" + lambda + ")";
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
        return false;
    }
}
