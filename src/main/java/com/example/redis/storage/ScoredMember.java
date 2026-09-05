package com.example.redis.storage;

/**
 * A single (score, member) pair, ordered first by score then lexicographically
 * by member name - the same tie-break rule real Redis uses for ZSET ordering.
 */
record ScoredMember(double score, String member) implements Comparable<ScoredMember> {

    @Override
    public int compareTo(ScoredMember other) {
        int scoreComparison = Double.compare(this.score, other.score);
        if (scoreComparison != 0) {
            return scoreComparison;
        }
        return this.member.compareTo(other.member);
    }
}
