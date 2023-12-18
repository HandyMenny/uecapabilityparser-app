package java.util;

import java.util.function.Supplier;

public final class ServiceLoader<S> {
    public interface Provider<S> extends Supplier<S> {
        Class<? extends S> type();

        S get();
    }
}