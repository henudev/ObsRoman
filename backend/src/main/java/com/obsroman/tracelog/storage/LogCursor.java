package com.obsroman.tracelog.storage;

import com.obsroman.tracelog.model.LogRecord;

import java.util.List;

/**
 * 导出游标：流式分批拉取日志（查询一批 → 写一批 → 继续查询），
 * 禁止一次性加载全部数据进内存。
 */
public interface LogCursor extends AutoCloseable {

    /**
     * 拉取下一批日志，最多 maxRows 条；返回空列表表示已读完。
     */
    List<LogRecord> nextBatch(int maxRows);

    @Override
    void close();
}
