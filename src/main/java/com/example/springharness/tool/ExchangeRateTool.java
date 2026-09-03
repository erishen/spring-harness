package com.example.springharness.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.web.client.RestClient;

import java.util.function.Function;

/**
 * 实时汇率查询工具，基于 open.er-api.com（免费，无需 API Key）。
 * 支持美元、欧元、日元、港币、英镑等主流货币兑人民币的实时汇率。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("查询实时汇率，支持美元(USD)、欧元(EUR)、日元(JPY)、港币(HKD)、英镑(GBP)等货币兑人民币(CNY)的实时汇率，返回汇率和更新时间")
public class ExchangeRateTool implements Function<ExchangeRateTool.Request, ExchangeRateTool.Response> {

    private final RestClient restClient;

    public ExchangeRateTool() {
        this.restClient = RestClient.builder()
                .baseUrl("https://open.er-api.com")
                .build();
    }

    public record Request(
            @JsonProperty(required = true, value = "from_currency")
            @JsonPropertyDescription("源货币代码，如 USD(美元), EUR(欧元), JPY(日元), HKD(港币), GBP(英镑)")
            String fromCurrency,
            @JsonProperty(value = "to_currency")
            @JsonPropertyDescription("目标货币代码，默认 CNY(人民币)，如 USD, EUR, JPY, HKD")
            String toCurrency
    ) {}

    public record Response(
            String fromCurrency,
            String toCurrency,
            String rate,
            String updateTime,
            String message
    ) {}

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();
        String from = request.fromCurrency() != null ? request.fromCurrency().toUpperCase() : "USD";
        String to = request.toCurrency() != null ? request.toCurrency().toUpperCase() : "CNY";

        try {
            String json = restClient.get()
                    .uri("/v6/latest/" + from)
                    .retrieve()
                    .body(String.class);

            if (json == null || !json.contains("rates")) {
                Response err = new Response(from, to, null, null,
                        "汇率查询失败，请检查货币代码是否正确");
                ToolCallRecorder.record("query_exchange_rate", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }

            // 提取目标货币汇率
            String rate = extractRate(json, to);
            String updateTime = extractField(json, "time_last_update_utc");

            if (rate == null) {
                Response err = new Response(from, to, null, null,
                        "不支持的目标货币：" + to);
                ToolCallRecorder.record("query_exchange_rate", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }

            Response response = new Response(from, to, rate, updateTime, null);
            ToolCallRecorder.record("query_exchange_rate", request, response.toString(), System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            Response err = new Response(from, to, null, null,
                    "汇率查询异常：" + safeErrorMessage(e.getMessage()));
            ToolCallRecorder.record("query_exchange_rate", request, err.toString(), System.currentTimeMillis() - start);
            return err;
        }
    }

    /** 过滤异常信息中的敏感内容（URL 等） */
    private String safeErrorMessage(String errorMsg) {
        if (errorMsg == null) return "未知错误";
        String filtered = errorMsg.replaceAll("https?://[^\\s\"']+", "[URL已隐藏]");
        if (filtered.length() > 200) {
            filtered = filtered.substring(0, 200) + "...";
        }
        return filtered;
    }

    private String extractRate(String json, String currency) {
        String key = "\"" + currency + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        int start = colon + 1;
        // 跳过空格
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
        if (end <= start) return null;
        return json.substring(start, end);
    }

    private String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        int start = json.indexOf("\"", colon + 1) + 1;
        int end = json.indexOf("\"", start);
        if (start <= 0 || end <= start) return null;
        return json.substring(start, end);
    }
}
