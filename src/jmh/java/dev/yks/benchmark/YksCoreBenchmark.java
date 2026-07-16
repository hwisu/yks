package dev.yks.benchmark;

import dev.yks.YDoc;
import dev.yks.YDocOptions;
import dev.yks.YDocRuntimeOptions;
import dev.yks.YStandardUpdatePolicy;
import dev.yks.YText;
import dev.yks.YThreadAccessPolicy;
import dev.yks.YUpdateLimits;
import dev.yks.YksUpdateLimitException;
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

        YUpdateLimits limits = new YUpdateLimits(standardUpdate.length - 1, 50_000, 50_000);
        limitedDocument = new YDoc(
            new YDocOptions(),
            new YDocRuntimeOptions(
                limits,
                YThreadAccessPolicy.ENFORCED,
                YStandardUpdatePolicy.ALLOW_LOSSLESS_EXTENSIONS
            )
        );
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
    public void rejectOversizedStandardUpdateBeforeDecode(Blackhole blackhole) {
        try {
            limitedDocument.applyUpdate(standardUpdate, null);
        } catch (YksUpdateLimitException expected) {
            blackhole.consume(expected.getActual());
        }
    }
}
