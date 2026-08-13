package dev.yks;

import java.util.Map;
import java.util.Set;

public final class YEventChanges<D> {
    public YEventChanges(Set<ItemStruct> added, Set<ItemStruct> deleted, D delta, Map<String, YMapChange> keys) {
        throw new AssertionError("compile-time ABI stub must not be loaded");
    }

    public Set<ItemStruct> getAdded() { throw new AssertionError(); }
    public Set<ItemStruct> getDeleted() { throw new AssertionError(); }
    public Set<ItemStruct> component1() { throw new AssertionError(); }
    public Set<ItemStruct> component2() { throw new AssertionError(); }
    public YEventChanges<D> copy(
        Set<ItemStruct> added,
        Set<ItemStruct> deleted,
        D delta,
        Map<String, YMapChange> keys
    ) {
        throw new AssertionError();
    }
}
