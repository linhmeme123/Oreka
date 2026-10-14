package com.example.tktt.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class,
            org.apache.lucene.search.IndexSearcher.TooManyClauses.class})
    public ProblemDetail invalidQuery(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Tham số hoặc truy vấn không hợp lệ.");
    }

    @ExceptionHandler(java.io.IOException.class)
    public ProblemDetail storageError(Exception exception) {
        log.error("Search storage error", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Không đọc được chỉ mục tìm kiếm.");
    }
}
