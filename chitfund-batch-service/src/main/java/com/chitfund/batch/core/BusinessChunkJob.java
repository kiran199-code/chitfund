package com.chitfund.batch.core;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;

/**
 * Marker + factory for a chunk-oriented, partitionable business job — bulk record processing
 * (none among the chit jobs today, which are tasklets). {@code BatchJobRegistry} wraps the
 * reader/processor/writer this returns with the shared partitioning + chunking + retry/skip
 * infrastructure built from this job's {@code batch_job_config} row; implementations never
 * construct a {@link org.springframework.batch.core.Step} or {@link org.springframework.batch.core.Job}
 * themselves.
 *
 * <p>Reader implementations should stay partition-range-aware: {@code minId}/{@code maxId} bound
 * the id range this partition (one thread of the grid) is responsible for, produced by
 * {@link com.chitfund.batch.core.partition.ColumnRangePartitioner}. The job supplies its own reader
 * (one row of business logic per row of the partitioned table, see {@code BatchJobRegistry}'s partition table).
 *
 * <p>To add a new chunk-style job: implement this interface as a {@code @Component}, insert a
 * matching {@code batch_job_config} row with {@code job_type = 'CHUNK'}, and reload config (or
 * restart) — no framework code changes. See README "Adding a new batch job".
 *
 * @param <I> item type read from the source table/range
 * @param <O> item type after business processing, handed to the writer
 */
public interface BusinessChunkJob<I, O> {

    /** Must exactly match the job_name of this job's row in batch_job_config. */
    String getJobName();

    /**
     * Reads items for the given id partition range [minId, maxId].
     */
    ItemReader<I> reader(Long minId, Long maxId);

    /** The business logic for this job — stubbed with // TODO markers in the initial scaffolding pass. */
    ItemProcessor<I, O> processor();

    /** Persists/side-effects processed items. May be a no-op logging writer in the scaffolding pass. */
    ItemWriter<O> writer();
}
