package com.obsroman.tracelog.storage;

import com.obsroman.tracelog.model.DashboardQuery;
import com.obsroman.tracelog.model.DashboardResult;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.LogSearchResult;

import java.util.List;

/**
 * 日志存储统一接口。所有 OpenObserve 查询 SQL 必须集中在 OpenObserveLogStorage，
 * Controller / Service / Dashboard / Export 一律禁止拼接存储层 SQL。
 */
public interface LogStorage {

    /** 批量写入（异步队列的 Worker 调用），失败抛 StorageException 由 Worker 处理 */
    void write(List<LogRecord> records);

    /** 条件查询（分页），排序：timestamp DESC */
    LogSearchResult search(LogQuery query);

    /** trace_id 完整链路查询，日志按 timestamp ASC 排序 */
    List<LogRecord> findByTraceId(String traceId);

    /** Dashboard 聚合统计 */
    DashboardResult aggregate(DashboardQuery query);

    /** 统计符合条件日志总数（导出限额预检查用） */
    long count(LogQuery query);

    /** 流式导出游标：timestamp ASC 分批读取 */
    LogCursor searchForExport(LogQuery query);
}
