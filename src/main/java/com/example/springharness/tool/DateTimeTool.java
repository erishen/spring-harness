package com.example.springharness.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 获取当前日期和时间的工具。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("获取当前日期和时间，包括星期几")
public class DateTimeTool implements Function<DateTimeTool.Request, DateTimeTool.Response> {

    public record Request(
            @JsonProperty(value = "format")
            @JsonPropertyDescription("时间格式，默认 yyyy-MM-dd HH:mm:ss")
            String format
    ) {}

    public record Response(String datetime, String date, String time, String weekday) {}

    private static final String[] WEEKDAYS = {
            "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"
    };

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        String pattern = (request.format() != null && !request.format().isBlank())
                ? request.format() : "yyyy-MM-dd HH:mm:ss";
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(pattern);
        Response response = new Response(
                now.format(dtf),
                now.toLocalDate().toString(),
                now.toLocalTime().withNano(0).toString(),
                WEEKDAYS[now.getDayOfWeek().getValue() % 7]
        );
        ToolCallRecorder.record("get_datetime", request, response.toString(), System.currentTimeMillis() - start);
        return response;
    }
}
