package compat.v029;

import dev.yks.ItemStruct;
import dev.yks.YEventChanges;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CurrentConsumer {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Set<ItemStruct> added = Set.of();
        Set<ItemStruct> deleted = Set.of();
        var changes = new YEventChanges<>(added, deleted, List.of("delta"), Map.of());

        require(changes.getAdded().isEmpty(), "0.2.9 getAdded descriptor did not link");
        require(changes.getDeleted().isEmpty(), "0.2.9 getDeleted descriptor did not link");
        require(changes.component1().isEmpty(), "0.2.9 component1 descriptor did not link");
        require(changes.component2().isEmpty(), "0.2.9 component2 descriptor did not link");
        require(changes.copy(added, deleted, List.of("copy"), Map.of()).getAdded().isEmpty(),
            "0.2.9 copy descriptor did not link");

        Method copyDefault = null;
        for (var method : YEventChanges.class.getDeclaredMethods()) {
            var parameters = method.getParameterTypes();
            if (method.getName().equals("copy$default")
                && Modifier.isStatic(method.getModifiers())
                && parameters.length == 7
                && parameters[1] == Set.class
                && parameters[2] == Set.class) {
                copyDefault = method;
                break;
            }
        }
        require(copyDefault != null, "0.2.9 copy$default descriptor is missing");
        var defaultCopy = (YEventChanges<?>) copyDefault.invoke(
            null, changes, null, null, null, null, 15, null
        );
        require(defaultCopy.getAdded().isEmpty(), "0.2.9 copy$default descriptor did not execute");
        System.out.println("YKS 0.2.9 ABI consumer passed");
    }
}
