package com.immerok.cookbook;

import static com.immerok.cookbook.PatternMatchingCEP.TOPIC;
import static com.immerok.cookbook.records.SensorReading.HOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.immerok.cookbook.extensions.FlinkMiniClusterExtension;
import com.immerok.cookbook.patterns.MatcherV1;
import com.immerok.cookbook.patterns.MatcherV2;
import com.immerok.cookbook.patterns.MatcherV3;
import com.immerok.cookbook.patterns.PatternMatcher;
import com.immerok.cookbook.records.OscillatingSensorReadingSupplier;
import com.immerok.cookbook.records.RisingSensorReadingSupplier;
import com.immerok.cookbook.records.SensorReading;
import com.immerok.cookbook.records.SensorReadingDeserializationSchema;
import com.immerok.cookbook.utils.CookbookKafkaCluster;
import com.immerok.cookbook.utils.DataStreamCollectUtil;
import com.immerok.cookbook.utils.DataStreamCollector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.typeutils.runtime.PojoSerializer;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(FlinkMiniClusterExtension.class)
class PatternMatchingCEPTest {

    private static final int EVENTS_PER_SECOND = 10;

    /**
     * Verify that Flink recognizes the SensorReading type as a POJO that it can serialize
     * efficiently.
     */
    @Test
    void sensorReadingsAreAPOJOs() {
        TypeSerializer<SensorReading> serializer =
                TypeInformation.of(SensorReading.class).createSerializer(new ExecutionConfig());

        assertThat(serializer).isInstanceOf(PojoSerializer.class);
    }

    @Test
    void matcherV2FindsMoreThanOneRisingHotStreak() throws Exception {
        Duration limitOfHeatTolerance = Duration.ofSeconds(1);
        long secondsOfData = 3;

        PatternMatcher<SensorReading, SensorReading> matcherV2 = new MatcherV2();
        assertThat(risingHotStreaks(HOT - 1, secondsOfData, matcherV2, limitOfHeatTolerance))
                .hasSizeGreaterThan(1);
    }

    @Test
    void matcherV3FindsOneRisingHotStreak() throws Exception {
        Duration limitOfHeatTolerance = Duration.ofSeconds(1);
        long secondsOfData = 3;

        PatternMatcher<SensorReading, SensorReading> matcherV3 = new MatcherV3();
        assertThat(risingHotStreaks(HOT - 1, secondsOfData, matcherV3, limitOfHeatTolerance))
                .hasSize(1);
    }

    @Test
    void matcherV2FindsAnOscillatingHotStreak() throws Exception {
        long secondsOfHeat = 3;
        Duration limitOfHeatTolerance = Duration.ofSeconds(2);

        PatternMatcher<SensorReading, SensorReading> matcherV2 = new MatcherV2();
        assertThat(oscillatingHotStreaks(secondsOfHeat, matcherV2, limitOfHeatTolerance))
                .isNotEmpty();
    }

    @Test
    void matcherV3FindsAnOscillatingHotStreak() throws Exception {
        long secondsOfHeat = 3;
        Duration limitOfHeatTolerance = Duration.ofSeconds(2);

        PatternMatcher<SensorReading, SensorReading> matcherV3 = new MatcherV3();
        assertThat(oscillatingHotStreaks(secondsOfHeat, matcherV3, limitOfHeatTolerance))
                .isNotEmpty();
    }

    @Test
    void matcherV1ErroneouslyFindsOscillatingHotStreaks() {
        long secondsOfHeat = 1;
        Duration limitOfHeatTolerance = Duration.ofSeconds(2);

        PatternMatcher<SensorReading, SensorReading> matcherV1 = new MatcherV1();

        assertThrows(
                AssertionError.class,
                () ->
                        assertThat(
                                        oscillatingHotStreaks(
                                                secondsOfHeat, matcherV1, limitOfHeatTolerance))
                                .isEmpty());
    }

    @Test
    void matcherV2FindsNoOscillatingHotStreaks() throws Exception {
        long secondsOfHeat = 1;
        Duration limitOfHeatTolerance = Duration.ofSeconds(2);

        PatternMatcher<SensorReading, SensorReading> matcherV2 = new MatcherV2();
        assertThat(oscillatingHotStreaks(secondsOfHeat, matcherV2, limitOfHeatTolerance)).isEmpty();
    }

    @Test
    void matcherV3FindsNoOscillatingHotStreaks() throws Exception {
        long secondsOfHeat = 1;
        Duration limitOfHeatTolerance = Duration.ofSeconds(2);

        PatternMatcher<SensorReading, SensorReading> matcherV3 = new MatcherV3();
        assertThat(oscillatingHotStreaks(secondsOfHeat, matcherV3, limitOfHeatTolerance)).isEmpty();
    }

    private List<SensorReading> risingHotStreaks(
            long initialTemp,
            long secondsOfData,
            PatternMatcher<SensorReading, SensorReading> matcher,
            Duration limitOfHeatTolerance)
            throws Exception {

        Stream<SensorReading> readings =
                Stream.generate(new RisingSensorReadingSupplier(initialTemp))
                        .limit(secondsOfData * EVENTS_PER_SECOND);

        return runTestJob(readings, matcher, limitOfHeatTolerance);
    }

    private List<SensorReading> oscillatingHotStreaks(
            long secondsOfHeat,
            PatternMatcher<SensorReading, SensorReading> matcher,
            Duration limitOfHeatTolerance)
            throws Exception {

        Stream<SensorReading> readings =
                Stream.generate(new OscillatingSensorReadingSupplier(secondsOfHeat))
                        .limit(3 * secondsOfHeat * EVENTS_PER_SECOND);

        return runTestJob(readings, matcher, limitOfHeatTolerance);
    }

    private List<SensorReading> runTestJob(
            Stream<SensorReading> stream,
            PatternMatcher<SensorReading, SensorReading> patternMatcher,
            Duration limitOfHeatTolerance)
            throws Exception {

        try (final CookbookKafkaCluster kafka = new CookbookKafkaCluster(EVENTS_PER_SECOND)) {
            kafka.createTopic(TOPIC, stream);

            KafkaSource<SensorReading> source =
                    KafkaSource.<SensorReading>builder()
                            .setBootstrapServers("localhost:9092")
                            .setTopics(TOPIC)
                            .setStartingOffsets(OffsetsInitializer.earliest())
                            // set an upper bound so that the job (and this test) will end
                            .setBounded(OffsetsInitializer.latest())
                            .setValueOnlyDeserializer(new SensorReadingDeserializationSchema())
                            .build();

            final DataStreamCollectUtil dataStreamCollector = new DataStreamCollectUtil();
            final DataStreamCollector<SensorReading> resultSink = new DataStreamCollector<>();
            final DataStreamCollector<SensorReading> eventSink = new DataStreamCollector<>();

            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            PatternMatchingCEP.defineWorkflow(
                    env,
                    source,
                    patternMatcher,
                    limitOfHeatTolerance,
                    workflow -> dataStreamCollector.collectAsync(workflow, resultSink),
                    workflow -> dataStreamCollector.collectAsync(workflow, eventSink));

            dataStreamCollector.startCollect(env.executeAsync());

            List<SensorReading> results = new ArrayList<>();
            resultSink.getOutput().forEachRemaining(results::add);
            return results;
        }
    }
}
