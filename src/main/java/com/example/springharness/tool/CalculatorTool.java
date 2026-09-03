package com.example.springharness.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.function.Function;

/**
 * 通用计算器工具，支持加减乘除运算。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("通用计算器，支持加减乘除运算")
public class CalculatorTool implements Function<CalculatorTool.Request, CalculatorTool.Response> {

    public record Request(
            @JsonProperty(required = true, value = "a")
            @JsonPropertyDescription("第一个操作数")
            double a,

            @JsonProperty(required = true, value = "b")
            @JsonPropertyDescription("第二个操作数")
            double b,

            @JsonProperty(required = true, value = "operation")
            @JsonPropertyDescription("运算类型：add(加), subtract(减), multiply(乘), divide(除)")
            String operation
    ) {}

    public record Response(String expression, double result) {}

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();
        double result;
        String expression;
        switch (request.operation().toLowerCase()) {
            case "add" -> {
                result = request.a() + request.b();
                expression = request.a() + " + " + request.b();
            }
            case "subtract" -> {
                result = request.a() - request.b();
                expression = request.a() + " - " + request.b();
            }
            case "multiply" -> {
                result = request.a() * request.b();
                expression = request.a() + " × " + request.b();
            }
            case "divide" -> {
                if (request.b() == 0) {
                    Response err = new Response("错误：除数不能为零", 0);
                    ToolCallRecorder.record("calculator", request, err.toString(), System.currentTimeMillis() - start);
                    return err;
                }
                result = request.a() / request.b();
                expression = request.a() + " ÷ " + request.b();
            }
            default -> {
                Response err = new Response("不支持的运算类型：" + request.operation(), 0);
                ToolCallRecorder.record("calculator", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }
        }
        Response response = new Response(expression + " = " + result, result);
        ToolCallRecorder.record("calculator", request, response.toString(), System.currentTimeMillis() - start);
        return response;
    }
}
