package dev.yks.benchmark;

import dev.yks.YDoc;
import dev.yks.YDocOptions;
import dev.yks.YDocRuntimeOptions;
import dev.yks.YStandardUpdatePolicy;
import dev.yks.YText;
import dev.yks.YThreadAccessPolicy;
import dev.yks.YUpdateLimits;
import dev.yks.YksUpdateLimitException;
import dev.yks.Snapshot;
import dev.yks.SnapshotKt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class YksCoreBenchmark {
    private byte[] standardUpdate;
    private YDoc encodeDocument;
    private YDoc limitedDocument;
    private YDoc standardAppendDocument;
    private YText standardAppendText;
    private int standardAppendBytes;
    private YDoc standardEmptyDocument;
    private YText readText;
    private byte[] formattedUpdate;
    private byte[] mapUpdate;
    private byte[] mapHistoryUpdate;
    private byte[] arrayUpdate;
    private byte[] nestedUpdate;
    private byte[] fragmentedUpdate;
    private byte[] concurrentUpdate;
    private List<byte[]> incrementalUpdates;
    private dev.yks.YArray arrayRead;
    private YText clockRangeText;
    private Snapshot clockRangeBefore;
    private Snapshot clockRangeAfter;
    private YText alternatingClockRangeText;
    private Snapshot alternatingClockRangeBefore;
    private Snapshot alternatingClockRangeAfter;

    @State(Scope.Thread)
    public static class LocalFormatState {
        private YText text;
        private int value;

        @Setup(Level.Trial)
        public void prepareDocument() {
            YDoc document = new YDoc();
            document.setGc(false);
            text = document.getText("body");
            for (int index = 0; index < 50_000; index++) {
                text.insert(0, "x", Map.of());
            }
        }
    }

    @State(Scope.Thread)
    public static class UnrelatedObserverState {
        private YText target;
        private long observed;

        @Setup(Level.Trial)
        public void prepareDocument() {
            YDoc document = new YDoc();
            document.setGc(false);
            YText observedText = document.getText("observed");
            target = document.getText("target");
            for (int index = 0; index < 50_000; index++) {
                target.insert(0, "x", Map.of());
            }
            observedText.observe(event -> {
                observed++;
                return Unit.INSTANCE;
            });
        }
    }

    @State(Scope.Thread)
    public static class SharedRootLookupState {
        private Map<String, dev.yks.AbstractYType> share;
        private String[] names;

        @Setup(Level.Trial)
        public void prepareDocument() {
            YDoc document = new YDoc();
            names = new String[10_000];
            for (int index = 0; index < names.length; index++) {
                String name = "root-" + index;
                names[index] = name;
                document.getText(name);
            }
            share = document.getShare();
        }
    }

    @State(Scope.Thread)
    public static class NestedDeleteState {
        private byte[] fixture;
        private YDoc document;
        private dev.yks.YArray root;

        @Setup(Level.Trial)
        public void prepareFixture() throws Exception {
            fixture = Files.readAllBytes(Path.of("build/performance/nested-array-3000-v1.bin"));
        }

        @Setup(Level.Invocation)
        public void prepareDocument() {
            document = new YDoc();
            document.setGc(false);
            document.applyUpdate(fixture, null);
            root = document.getArray("root");
        }
    }

    @State(Scope.Thread)
    public static class ObservedFragmentedState {
        private byte[] fixture;
        private YDoc document;
        private YText text;
        private long observed;

        @Setup(Level.Trial)
        public void prepareFixture() throws Exception {
            fixture = Files.readAllBytes(Path.of("build/performance/fragmented-text-5000-v1.bin"));
        }

        @Setup(Level.Invocation)
        public void prepareDocument() {
            observed = 0;
            document = new YDoc();
            document.setGc(false);
            document.applyUpdate(fixture, null);
            text = document.getText("body");
            document.observeAfterTransactions(event -> {
                observed++;
                return Unit.INSTANCE;
            });
        }
    }

    @State(Scope.Thread)
    public static class ObservedMapState {
        private byte[] fixture;
        private dev.yks.YMap map;
        private long observed;
        private int nextIndex;
        private final String[] keys = new String[5_000];

        @Setup(Level.Trial)
        public void prepareDocument() throws Exception {
            fixture = Files.readAllBytes(Path.of("build/performance/map-5000-v1.bin"));
            for (int index = 0; index < keys.length; index++) {
                keys[index] = "key-" + index;
            }
            observed = 0;
            YDoc document = new YDoc();
            document.setGc(false);
            document.applyUpdate(fixture, null);
            map = document.getMap("map");
            map.observe(event -> {
                observed++;
                return Unit.INSTANCE;
            });
        }
    }

    @State(Scope.Thread)
    public static class PackedUndoState {
        private YText text;
        private dev.yks.UndoManager undoManager;

        @Setup(Level.Invocation)
        public void prepareDocument() {
            YDoc document = new YDoc();
            text = document.getText("body");
            undoManager = new dev.yks.UndoManager(
                text,
                new dev.yks.UndoManagerOptions(
                    0,
                    null,
                    java.util.Collections.singleton(null),
                    null,
                    item -> true,
                    false,
                    false,
                    null
                )
            );
            text.insert(0, "x".repeat(20_000), Map.of());
        }
    }

    @State(Scope.Thread)
    public static class StandardTransactionState {
        private byte[] fixture;
        private YDoc document;
        private YText text;
        private int emittedBytes;

        @Setup(Level.Trial)
        public void prepareFixture() {
            YDoc source = new YDoc();
            source.setClientId(7L);
            YText left = source.getText("left");
            YText right = source.getText("right");
            for (int index = 0; index < 5_000; index++) {
                YText target = index % 2 == 0 ? left : right;
                target.insert(target.getLength(), "x", Map.of());
            }
            fixture = source.encodeStateAsUpdate(new byte[0]);
        }

        @Setup(Level.Invocation)
        public void prepareDocument() {
            emittedBytes = 0;
            document = new YDoc();
            document.applyUpdate(fixture, null);
            text = document.getText("left");
            document.observeUpdates((update, origin) -> {
                emittedBytes = update.length;
                return Unit.INSTANCE;
            });
        }
    }

    @Setup(Level.Trial)
    public void prepareFixtures() {
        YDoc source = new YDoc();
        source.setClientId(1L);
        YText left = source.getText("left");
        YText right = source.getText("right");
        for (int index = 0; index < 5_000; index++) {
            YText text = index % 2 == 0 ? left : right;
            text.insert(text.getLength(), "x", Map.of());
        }
        standardUpdate = source.encodeStateAsUpdate(new byte[0]);

        encodeDocument = new YDoc();
        encodeDocument.applyUpdate(standardUpdate, null);

        standardAppendDocument = new YDoc();
        standardAppendDocument.applyUpdate(standardUpdate, null);
        standardAppendText = standardAppendDocument.getText("left");
        standardAppendDocument.observeUpdates((update, origin) -> {
            standardAppendBytes = update.length;
            return Unit.INSTANCE;
        });

        standardEmptyDocument = new YDoc();
        standardEmptyDocument.applyUpdate(standardUpdate, null);
        standardEmptyDocument.observeUpdates((update, origin) -> Unit.INSTANCE);

        YDoc readDocument = new YDoc();
        readText = readDocument.getText("body");
        readText.insert(0, "x".repeat(5_000), Map.of());

        YUpdateLimits limits = new YUpdateLimits(standardUpdate.length - 1, 50_000, 50_000);
        limitedDocument = new YDoc(
            new YDocOptions(),
            new YDocRuntimeOptions(
                limits,
                YThreadAccessPolicy.ENFORCED,
                YStandardUpdatePolicy.ALLOW_LOSSLESS_EXTENSIONS
            )
        );

        try {
            formattedUpdate = Files.readAllBytes(Path.of("build/performance/formatted-text-5000-v1.bin"));
            mapUpdate = Files.readAllBytes(Path.of("build/performance/map-5000-v1.bin"));
            mapHistoryUpdate = Files.readAllBytes(Path.of("build/performance/map-history-5000-v1.bin"));
            nestedUpdate = Files.readAllBytes(Path.of("build/performance/nested-array-3000-v1.bin"));
            fragmentedUpdate = Files.readAllBytes(Path.of("build/performance/fragmented-text-5000-v1.bin"));
            concurrentUpdate = Files.readAllBytes(Path.of("build/performance/concurrent-text-1000-v1.bin"));
            incrementalUpdates = Files.readAllLines(Path.of("build/performance/incremental-text-1000-v1.txt"))
                .stream()
                .filter(line -> !line.isBlank())
                .map(Base64.getDecoder()::decode)
                .toList();
            arrayUpdate = Files.readAllBytes(Path.of("build/performance/array-5000-v1.bin"));
            YDoc arrayDocument = new YDoc();
            arrayDocument.applyUpdate(arrayUpdate, null);
            arrayRead = arrayDocument.getArray("array");
        } catch (Exception error) {
            throw new IllegalStateException("run benchmark:performance once to generate parity fixtures", error);
        }

        YDoc clockRangeDocument = new YDoc();
        clockRangeText = clockRangeDocument.getText("body");
        clockRangeText.insert(0, "x".repeat(20_000), Map.of());
        clockRangeBefore = SnapshotKt.snapshot(clockRangeDocument);
        clockRangeText.insert(10_000, "y", Map.of());
        clockRangeAfter = SnapshotKt.snapshot(clockRangeDocument);

        YDoc alternatingClockRangeDocument = new YDoc();
        alternatingClockRangeDocument.setGc(false);
        alternatingClockRangeText = alternatingClockRangeDocument.getText("body");
        alternatingClockRangeText.insert(0, "x".repeat(2_000), Map.of());
        alternatingClockRangeBefore = SnapshotKt.snapshot(alternatingClockRangeDocument);
        for (int index = 999; index >= 0; index--) {
            alternatingClockRangeText.delete(index * 2, 1);
        }
        alternatingClockRangeAfter = SnapshotKt.snapshot(alternatingClockRangeDocument);
    }

    @Benchmark
    public int insertTwentyThousandCharacters() {
        YDoc doc = new YDoc();
        YText text = doc.getText("body");
        text.insert(0, "x".repeat(20_000), Map.of());
        return text.getLength();
    }

    @Benchmark
    public int editMiddleOneThousandTimes() {
        YDoc doc = new YDoc();
        YText text = doc.getText("body");
        text.insert(0, "x".repeat(5_000), Map.of());
        for (int index = 0; index < 1_000; index++) {
            int middle = text.getLength() / 2;
            text.insert(middle, "y", Map.of());
            text.delete(middle, 1);
        }
        return text.getLength();
    }

    @Benchmark
    public int applyFiveThousandStructs() {
        YDoc doc = new YDoc();
        doc.applyUpdate(standardUpdate, null);
        return doc.getText("left").getLength() + doc.getText("right").getLength();
    }

    @Benchmark
    public int applyFiveThousandStructsToOpenRoots() {
        YDoc doc = new YDoc();
        doc.getText("left").toString();
        doc.getText("right").toString();
        doc.applyUpdate(standardUpdate, null);
        return doc.getText("left").getLength() + doc.getText("right").getLength();
    }

    @Benchmark
    public int applyOneThousandIncrementalUpdates() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        for (byte[] update : incrementalUpdates) {
            doc.applyUpdate(update, null);
        }
        return doc.getText("body").getLength();
    }

    @Benchmark
    public int encodeUnchangedFiveThousandStructState() {
        return encodeDocument.encodeStateAsUpdate(new byte[0]).length;
    }

    @Benchmark
    public int standardWireTransactionOnFiveThousandStructDocument(StandardTransactionState state) {
        state.text.insert(state.text.getLength(), "y", Map.of());
        return state.emittedBytes;
    }

    @Benchmark
    public int standardWireSteadyAppendOnFiveThousandStructDocument() {
        standardAppendText.insert(standardAppendText.getLength(), "y", Map.of());
        return standardAppendBytes;
    }

    @Benchmark
    public int standardWireCheckpointOnFiveThousandStructDocument(StandardTransactionState state) {
        state.document.transact(null, true, () -> Unit.INSTANCE);
        return state.emittedBytes;
    }

    @Benchmark
    public long standardWireEmptyTransactionSteady() {
        standardEmptyDocument.transact(null, true, () -> Unit.INSTANCE);
        return standardUpdate.length;
    }

    @Benchmark
    public long readCachedLengthTwentyThousandTimes() {
        long sum = 0;
        for (int index = 0; index < 20_000; index++) sum += readText.getLength();
        return sum;
    }

    @Benchmark
    public long readCachedStringOneHundredTimes() {
        long sum = 0;
        for (int index = 0; index < 100; index++) sum += readText.toString().length();
        return sum;
    }

    @Benchmark
    public void rejectOversizedStandardUpdateBeforeDecode(Blackhole blackhole) {
        try {
            limitedDocument.applyUpdate(standardUpdate, null);
        } catch (YksUpdateLimitException expected) {
            blackhole.consume(expected.getActual());
        }
    }

    @Benchmark
    public int applyFormattedFiveThousandStructs() {
        YDoc doc = new YDoc();
        doc.applyUpdate(formattedUpdate, null);
        return doc.getText("left").getLength() + doc.getText("right").getLength();
    }

    @Benchmark
    public int deleteThreeThousandNestedTypes(NestedDeleteState state) {
        state.root.delete(0, state.root.getLength());
        return state.root.getLength();
    }

    @Benchmark
    public int applyThreeThousandNestedTypes() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        doc.applyUpdate(nestedUpdate, null);
        return doc.getArray("root").getLength();
    }

    @Benchmark
    public int applyFiveThousandMapEntries() {
        YDoc doc = new YDoc();
        doc.applyUpdate(mapUpdate, null);
        return doc.getMap("map").getSize();
    }

    @Benchmark
    public long applyFiveThousandMapHistoryEntries() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        doc.applyUpdate(mapHistoryUpdate, null);
        return ((Number) doc.getMap("map").get("key")).longValue();
    }

    @Benchmark
    public long applyFiveThousandArrayValues() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        doc.applyUpdate(arrayUpdate, null);
        return ((Number) doc.getArray("array").get(2_500)).longValue();
    }

    @Benchmark
    public long insertFiveThousandArrayValues() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        dev.yks.YArray array = doc.getArray("array");
        java.util.List<Integer> values = java.util.stream.IntStream.range(0, 5_000).boxed().toList();
        array.insert(0, values);
        return ((Number) array.get(2_500)).longValue();
    }

    @Benchmark
    public long setFiveThousandMapHistoryEntries() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        dev.yks.YMap map = doc.getMap("map");
        doc.transact(null, true, () -> {
            for (int index = 0; index < 5_000; index++) map.set("key", index);
            return Unit.INSTANCE;
        });
        return ((Number) map.get("key")).longValue();
    }

    @Benchmark
    public int applyFiveThousandPrependedTextStructs() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        doc.applyUpdate(fragmentedUpdate, null);
        return doc.getText("body").getLength();
    }

    @Benchmark
    public int applyFiveThousandPrependedTextStructsToOpenRoot() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        doc.getText("body").toString();
        doc.applyUpdate(fragmentedUpdate, null);
        return doc.getText("body").getLength();
    }

    @Benchmark
    public int applyOneThousandConcurrentTextInserts() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        doc.applyUpdate(concurrentUpdate, null);
        return doc.getText("body").getLength();
    }

    @Benchmark
    public int applyOneThousandConcurrentTextInsertsToOpenRoot() {
        YDoc doc = new YDoc();
        doc.setGc(false);
        doc.getText("body").toString();
        doc.applyUpdate(concurrentUpdate, null);
        return doc.getText("body").getLength();
    }

    @Benchmark
    public int createAndReadTenThousandRoots() {
        YDoc doc = new YDoc();
        for (int index = 0; index < 10_000; index++) doc.getText("root-" + index);
        int sum = 0;
        for (int index = 0; index < 10_000; index++) sum += doc.getText("root-" + index).getLength();
        return doc.rootNames().size() + sum;
    }

    @Benchmark
    public int editOneThousandTimesWithTenThousandRoots() {
        YDoc doc = new YDoc();
        for (int index = 0; index < 10_000; index++) doc.getText("root-" + index);
        YText text = doc.getText("root-0");
        for (int index = 0; index < 1_000; index++) {
            text.insert(0, "x", Map.of());
            text.delete(0, 1);
        }
        return doc.rootNames().size() + text.getLength();
    }

    @Benchmark
    public int lookupTenThousandSharedRoots(SharedRootLookupState state) {
        int found = 0;
        for (String name : state.names) {
            if (state.share.containsKey(name)) found++;
        }
        return found;
    }

    @Benchmark
    public long readArrayLengthAndFirstItemOneHundredThousandTimes() {
        long sum = 0;
        for (int index = 0; index < 100_000; index++) {
            sum += arrayRead.getLength();
            sum += ((Number) arrayRead.get(0)).longValue();
        }
        return sum;
    }

    @Benchmark
    public long editObservedFragmentedTextFiveHundredTimes(ObservedFragmentedState state) {
        state.document.transact(null, true, () -> {
            for (int index = 0; index < 500; index++) {
                int middle = state.text.getLength() / 2;
                state.text.insert(middle, "y", Map.of());
                state.text.delete(middle, 1);
            }
            return Unit.INSTANCE;
        });
        return state.observed + state.text.getLength();
    }

    @Benchmark
    public long editObservedFiveThousandKeyMap(ObservedMapState state) {
        String key = state.keys[state.nextIndex % state.keys.length];
        int value = -(++state.nextIndex);
        state.map.set(key, value);
        return state.observed + ((Number) state.map.get(key)).longValue();
    }

    @Benchmark
    public int undoTwentyThousandPackedCharacters(PackedUndoState state) {
        state.undoManager.undo();
        return state.text.getLength();
    }

    @Benchmark
    public int readClockRangeSnapshotDelta() {
        return clockRangeText.toDelta(clockRangeAfter, clockRangeBefore, null).getOps().size();
    }

    @Benchmark
    public int readAlternatingClockRangeSnapshotDelta() {
        return alternatingClockRangeText
            .toDelta(alternatingClockRangeAfter, alternatingClockRangeBefore, null)
            .getOps()
            .size();
    }

    @Benchmark
    public int formatFirstCharacterOfFragmentedText(LocalFormatState state) {
        state.text.format(0, 1, Map.of("bold", (++state.value & 1) == 0));
        return state.text.getLength() + state.value;
    }

    @Benchmark
    public long editWithUnrelatedObserver(UnrelatedObserverState state) {
        state.target.insert(0, "y", Map.of());
        state.target.delete(0, 1);
        return state.target.getLength() + state.observed;
    }
}
