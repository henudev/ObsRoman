package com.obsroman.tracelog.queue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LogQueueTest {

    @Test
    void dropsWhenFullAndCounts() {
        LogQueue queue = new LogQueue(3);
        for (int i = 0; i < 5; i++) {
            queue.offer(new com.obsroman.tracelog.model.LogRecord());
        }
        assertThat(queue.size()).isEqualTo(3);
        assertThat(queue.queueFullDropped()).isEqualTo(2);
        assertThat(queue.enqueuedTotal()).isEqualTo(3);
        assertThat(queue.totalDropped()).isEqualTo(2);
    }

    @Test
    void drainToTakesAvailableRecords() throws InterruptedException {
        LogQueue queue = new LogQueue(10);
        for (int i = 0; i < 4; i++) {
            queue.offer(new com.obsroman.tracelog.model.LogRecord());
        }
        List<com.obsroman.tracelog.model.LogRecord> sink = new ArrayList<>();
        queue.drainTo(sink, 3);
        assertThat(sink).hasSize(3);
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void writeFailureIsCountedNotThrown() {
        LogQueue queue = new LogQueue(10);
        queue.recordWriteFailed(7);
        assertThat(queue.writeFailedDropped()).isEqualTo(7);
        assertThat(queue.totalDropped()).isEqualTo(7);
    }
}
