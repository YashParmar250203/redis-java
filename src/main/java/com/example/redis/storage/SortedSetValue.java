package com.example.redis.storage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Backing structure for a Redis sorted set (ZSET).
 * <p>
 * Mirrors real Redis's own internal representation: a hash table for O(1)
 * average score lookup/update by member, plus a structure ordered by
 * (score, member) for range queries. The two structures must move together,
 * so writes are synchronized on this instance; reads are not, meaning there
 * is a theoretical, extremely short window during a concurrent write where
 * a reader could observe them briefly out of sync. That's a deliberate
 * trade-off: locking scoped to one ZSET key instead of the whole store, in
 * exchange for reads never blocking on writers.
 * <p>
 * Known limitation: {@link #membersInOrder()} walks the ordered set from the
 * front, so range queries end up O(n) to reach an arbitrary start index
 * rather than the O(log n) a rank-augmented skip list (span counters per
 * level, which is what real Redis's skip list actually carries) would give.
 * Building that structure is a natural, interview-worthy next step.
 */
class SortedSetValue implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<String, Double> scoresByMember = new ConcurrentHashMap<>();
    private final ConcurrentSkipListSet<ScoredMember> orderedByScore = new ConcurrentSkipListSet<>();

    /**
     * @return true if this member is newly added, false if it already existed (score updated).
     */
    synchronized boolean add(String member, double score) {
        Double previousScore = scoresByMember.put(member, score);
        if (previousScore != null) {
            orderedByScore.remove(new ScoredMember(previousScore, member));
        }
        orderedByScore.add(new ScoredMember(score, member));
        return previousScore == null;
    }

    synchronized boolean remove(String member) {
        Double previousScore = scoresByMember.remove(member);
        if (previousScore == null) {
            return false;
        }
        orderedByScore.remove(new ScoredMember(previousScore, member));
        return true;
    }

    int size() {
        return scoresByMember.size();
    }

    /**
     * @return member names in ascending score order (ties broken lexicographically).
     */
    List<String> membersInOrder() {
        List<String> members = new ArrayList<>(orderedByScore.size());
        for (ScoredMember scoredMember : orderedByScore) {
            members.add(scoredMember.member());
        }
        return members;
    }
}
