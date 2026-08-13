package compat.v028;

import dev.yks.IdSet;
import dev.yks.YEventChanges;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

public final class LegacyConsumer {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var added = new IdSet();
        added.add(7, 11, 2);
        var deleted = new IdSet();
        deleted.add(8, 13, 1);
        var changes = new YEventChanges<>(added, deleted, List.of("delta"), Map.of());

        require(changes.getAdded().has(7, 11), "legacy getAdded descriptor did not link");
        require(changes.getDeleted().has(8, 13), "legacy getDeleted descriptor did not link");
        require(changes.component1().has(7, 12), "legacy component1 descriptor did not link");
        require(changes.component2().has(8, 13), "legacy component2 descriptor did not link");
        require(changes.copy(added, deleted, List.of("copy"), Map.of()).getAdded().has(7, 11),
            "legacy copy descriptor did not link");

        Method copyDefault = null;
        for (var method : YEventChanges.class.getDeclaredMethods()) {
            var parameters = method.getParameterTypes();
            if (method.getName().equals("copy$default")
                && Modifier.isStatic(method.getModifiers())
                && parameters.length == 7
                && parameters[1] == IdSet.class
                && parameters[2] == IdSet.class) {
                copyDefault = method;
                break;
            }
        }
        require(copyDefault != null, "legacy copy$default descriptor is missing");
        var defaultCopy = (YEventChanges<?>) copyDefault.invoke(
            null, changes, null, null, null, null, 15, null
        );
        require(defaultCopy.getAdded().has(7, 11), "legacy copy$default descriptor did not execute");
        System.out.println("YKS 0.2.8 ABI consumer passed");
    }
}
