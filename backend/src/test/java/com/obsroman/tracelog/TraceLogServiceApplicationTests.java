package com.obsroman.tracelog;

import com.obsroman.tracelog.queue.LogQueue;
import com.obsroman.tracelog.queue.LogWorker;
import com.obsroman.tracelog.storage.LogStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TraceLogServiceApplicationTests {

    @Autowired
    private LogStorage logStorage;

    @Autowired
    private LogQueue logQueue;

    @Autowired
    private LogWorker logWorker;

    @Test
    void contextLoadsAndWiresCoreBeans() {
        assertThat(logStorage).isNotNull();
        assertThat(logQueue).isNotNull();
        assertThat(logWorker.isAlive()).isTrue();
        assertThat(logWorker.workerCount()).isGreaterThanOrEqualTo(1);
    }
}
