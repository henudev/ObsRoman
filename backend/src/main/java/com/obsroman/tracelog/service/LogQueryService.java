package com.obsroman.tracelog.service;

import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogSearchRequest;
import com.obsroman.tracelog.model.LogSearchResult;
import com.obsroman.tracelog.storage.LogStorage;
import org.springframework.stereotype.Service;

/**
 * 日志搜索服务：负责查询条件规范化（LogQueryFactory），存储访问一律经 LogStorage。
 * StorageException 由 GlobalExceptionHandler 统一映射为 502/504。
 */
@Service
public class LogQueryService {

    private final LogStorage storage;
    private final LogQueryFactory queryFactory;

    public LogQueryService(LogStorage storage, LogQueryFactory queryFactory) {
        this.storage = storage;
        this.queryFactory = queryFactory;
    }

    public LogSearchResult search(LogSearchRequest request) {
        LogQuery query = queryFactory.create(request, false);
        return storage.search(query);
    }
}
