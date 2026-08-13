package dev.yks;

import java.util.Map;

public final class YEventChanges<D> {
    public YEventChanges(IdSet added, IdSet deleted, D delta, Map<String, YMapChange> keys) {
        throw new AssertionError("compile-time ABI stub must not be loaded");
    }

    public IdSet getAdded() { throw new AssertionError(); }
    public IdSet getDeleted() { throw new AssertionError(); }
    public IdSet component1() { throw new AssertionError(); }
    public IdSet component2() { throw new AssertionError(); }
    public YEventChanges<D> copy(IdSet added, IdSet deleted, D delta, Map<String, YMapChange> keys) {
        throw new AssertionError();
    }
}
