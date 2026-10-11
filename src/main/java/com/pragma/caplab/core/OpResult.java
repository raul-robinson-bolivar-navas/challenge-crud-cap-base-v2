package com.pragma.caplab.core;

import java.util.function.Function;

public record OpResult<T>(
        T data,
        OpMeta meta
) {

    public <R> OpResult<R> map(Function<T, R> mapper) {
        return new OpResult<>(mapper.apply(data), meta);
    }

    public OpResult<T> withLatency(long ms) {
        return new OpResult<>(data, meta.withLatency(ms));
    }
}
