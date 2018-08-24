package io.sailrocket.core.impl;

import io.sailrocket.api.HttpClientPool;
import io.sailrocket.api.Sequence;
import io.sailrocket.api.Statistics;
import io.sailrocket.core.api.SequenceContext;
import io.sailrocket.core.api.Worker;
import io.sailrocket.core.client.Pacer;
import io.sailrocket.api.ValidatorResults;

@Deprecated
public class SequenceContextImpl implements SequenceContext {

    private final HttpClientPool httpClientPool;
    private final Worker worker;
    private final Sequence sequence;
    private final Pacer pacer;
    private final long startTime;
    private Statistics sequenceStats;
    private ValidatorResults validatorResults;

    public SequenceContextImpl(Sequence sequence, Worker worker, Pacer pacer, long startTime) {
        this(sequence, worker, pacer, startTime, new Statistics());
    }

    public SequenceContextImpl(Sequence sequence, Worker worker, Pacer pacer, long startTime, Statistics statistics) {
        this.worker = worker;
        this.httpClientPool = worker.clientPool();
        this.sequence = sequence;
        this.pacer = pacer;
        this.startTime = startTime;
        sequenceStats = statistics;
        validatorResults = new ValidatorResults();
    }

    @Override
    public HttpClientPool clientPool() {
        return httpClientPool;
    }

    @Override
    public Worker worker() {
        return worker;
    }

    @Override
    public Sequence sequence() {
        return sequence;
    }

    @Override
    public Statistics sequenceStats() {
        return sequenceStats;
    }

    @Override
    public void sequenceStats(Statistics statistics) {
        this.sequenceStats = statistics;
    }

    @Override
    public ValidatorResults validatorResults() {
        return validatorResults;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public Pacer pacer() {
        return pacer;
    }
}
