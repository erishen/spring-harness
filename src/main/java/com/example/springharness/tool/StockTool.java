package com.example.springharness.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 股票行情查询工具，基于 Finnhub API（免费 60次/分钟）。
 * 支持实时行情和历史数据查询。
 *
 * - 实时行情：不传 interval 参数，返回当前价格、涨跌幅等
 * - 历史数据：传 interval=daily/weekly/monthly，返回历史K线数据
 *
 * 支持美股（AAPL, MSFT, TSLA 等）、港股（0700.HK）、A股（600519.SS）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("查询股票实时行情（稳定可用，优先使用）。返回当前价格、涨跌幅、开盘价、最高价、最低价、成交量。支持美股(AAPL, MSFT, TSLA)、港股(0700.HK)、A股(600519.SS)。注意：默认只查实时行情，不要主动查历史数据。仅当用户明确要求'历史数据'、'K线'、'过去N天股价'时，才传interval=daily/weekly/monthly参数查询历史数据（历史数据有频率限制，可能失败）")
public class StockTool implements Function<StockTool.Request, StockTool.Response> {

    private final RestClient finnhubClient;
    private final RestClient yahooClient;
    private final String apiKey;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 上次请求时间，用于控制 Yahoo Finance 请求频率 */
    private static volatile long lastYahooRequestTime = 0;
    /** Yahoo Finance 请求最小间隔（毫秒） */
    private static final long MIN_YAHOO_INTERVAL = 5000;
    /** 上次遇到 429 的时间，用于全局冷却 */
    private static volatile long lastRateLimitTime = 0;
    /** 429 后冷却时间（毫秒），期间直接返回错误不再请求 */
    private static final long RATE_LIMIT_COOLDOWN = 60000;

    public StockTool(String apiKey) {
        this.apiKey = apiKey;
        this.finnhubClient = RestClient.builder()
                .baseUrl("https://finnhub.io")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .build();
        this.yahooClient = RestClient.builder()
                .baseUrl("https://query1.finance.yahoo.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json,text/plain,*/*")
                .defaultHeader("Accept-Language", "en-US,en;q=0.9")
                .build();
    }

    public record Request(
            @JsonProperty(required = true, value = "symbol")
            @JsonPropertyDescription("股票代码，如 AAPL(苹果), MSFT(微软), TSLA(特斯拉), 0700.HK(腾讯), 600519.SS(贵州茅台)")
            String symbol,

            @JsonProperty(required = false, value = "interval")
            @JsonPropertyDescription("历史数据周期，可选：daily(日线)/weekly(周线)/monthly(月线)。不传则查询实时行情。需要历史股价/K线数据时必须传此参数")
            String interval,

            @JsonProperty(required = false, value = "range")
            @JsonPropertyDescription("历史数据时间范围，可选：1mo(1个月)/3mo/6mo/1y(1年，默认)/2y/5y/10y/max(全部)。仅在查询历史数据时有效")
            String range
    ) {}

    public record HistoricalBar(
            String date,
            String open,
            String high,
            String low,
            String close,
            String volume
    ) {}

    public record Response(
            String symbol,
            String price,
            String open,
            String high,
            String low,
            String volume,
            String change,
            String changePercent,
            String currency,
            String exchange,
            String interval,
            Integer historicalCount,
            List<HistoricalBar> historicalData,
            String message
    ) {}

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();

        if (apiKey == null || apiKey.isBlank()) {
            return buildError(request.symbol(), null, "未配置 FINNHUB_API_KEY，无法查询股票行情");
        }

        boolean isHistorical = request.interval() != null && !request.interval().isBlank();

        if (isHistorical) {
            return queryHistorical(request, start);
        } else {
            return queryRealtime(request, start);
        }
    }

    /** 查询实时行情 */
    private Response queryRealtime(Request request, long start) {
        try {
            String json = finnhubClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/quote")
                            .queryParam("symbol", request.symbol())
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve()
                    .body(String.class);

            if (json == null || !json.contains("\"c\"")) {
                return buildError(request.symbol(), null,
                        "查询失败或股票代码无效：" + request.symbol());
            }

            // Finnhub quote 返回: c=当前价格, d=涨跌额, dp=涨跌幅, h=最高, l=最低, o=开盘, pc=前收盘, t=时间戳
            String price = extractJsonNumber(json, "c");
            String change = extractJsonNumber(json, "d");
            String changePercent = extractJsonNumber(json, "dp");
            String high = extractJsonNumber(json, "h");
            String low = extractJsonNumber(json, "l");
            String open = extractJsonNumber(json, "o");
            String previousClose = extractJsonNumber(json, "pc");

            // 格式化涨跌幅
            if (changePercent != null && !changePercent.endsWith("%")) {
                changePercent = changePercent + "%";
            }

            Response response = new Response(
                    request.symbol(), price, open, high, low, null,
                    change, changePercent, "USD", null,
                    null, null, null, null
            );
            ToolCallRecorder.record("query_stock", request,
                    String.format("实时行情: 价格=%s, 涨跌幅=%s", price, changePercent),
                    System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            return buildError(request.symbol(), null, "查询异常：" + safeErrorMessage(e.getMessage()));
        }
    }

    /** 查询历史数据（使用 Yahoo Finance API，带重试机制） */
    private Response queryHistorical(Request request, long start) {
        String interval = request.interval().toLowerCase();
        String yahooInterval;
        switch (interval) {
            case "weekly":
                yahooInterval = "1wk";
                break;
            case "monthly":
                yahooInterval = "1mo";
                break;
            case "daily":
            default:
                yahooInterval = "1d";
                break;
        }

        String range = (request.range() != null && !request.range().isBlank())
                ? request.range() : "1y";

        try {
            String json = doYahooRequestWithRetry("/v8/finance/chart/" + request.symbol(),
                    "interval", yahooInterval, "range", range);

            if (json == null || !json.contains("\"timestamp\"")) {
                return buildError(request.symbol(), interval,
                        "历史数据查询失败或股票代码无效：" + request.symbol());
            }

            List<HistoricalBar> bars = parseYahooHistoricalBars(json);
            // 最多返回 30 条，避免 token 消耗过大（按时间倒序，取最新的30条）
            List<HistoricalBar> limitedBars = bars.size() > 30
                    ? new ArrayList<>(bars.subList(0, 30))
                    : bars;

            String latestPrice = limitedBars.isEmpty() ? null : limitedBars.get(0).close();
            String currency = extractJsonValue(json, "currency");
            String exchange = extractJsonValue(json, "fullExchangeName");

            Response response = new Response(
                    request.symbol(),
                    latestPrice,
                    limitedBars.isEmpty() ? null : limitedBars.get(0).open(),
                    limitedBars.isEmpty() ? null : limitedBars.get(0).high(),
                    limitedBars.isEmpty() ? null : limitedBars.get(0).low(),
                    limitedBars.isEmpty() ? null : limitedBars.get(0).volume(),
                    null, null, currency, exchange,
                    interval, bars.size(), limitedBars, null
            );
            ToolCallRecorder.record("query_stock", request,
                    String.format("历史数据: %d 条(返回%d条), 最新价格: %s",
                            bars.size(), limitedBars.size(), latestPrice),
                    System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            return buildError(request.symbol(), interval,
                    "历史数据查询异常：" + safeErrorMessage(e.getMessage()));
        }
    }

    /** 带频率控制和重试的 Yahoo Finance 请求 */
    private String doYahooRequestWithRetry(String path, String... params) throws InterruptedException {
        // 全局冷却检查：如果最近 60 秒内遇到过 429，直接返回错误，避免大量线程阻塞
        long now = System.currentTimeMillis();
        if (now - lastRateLimitTime < RATE_LIMIT_COOLDOWN) {
            long waitSeconds = (RATE_LIMIT_COOLDOWN - (now - lastRateLimitTime)) / 1000;
            throw new RuntimeException("Yahoo Finance 限流冷却中，请 " + waitSeconds + " 秒后再试");
        }

        int maxRetries = 1; // 只重试 1 次，避免长时间阻塞
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 频率控制：确保请求间隔至少 5 秒
            long waitTime = MIN_YAHOO_INTERVAL - (System.currentTimeMillis() - lastYahooRequestTime);
            if (waitTime > 0) {
                Thread.sleep(waitTime);
            }
            lastYahooRequestTime = System.currentTimeMillis();

            try {
                String json = yahooClient.get()
                        .uri(uriBuilder -> {
                            var b = uriBuilder.path(path);
                            for (int i = 0; i < params.length; i += 2) {
                                b.queryParam(params[i], params[i + 1]);
                            }
                            return b.build();
                        })
                        .retrieve()
                        .body(String.class);
                return json;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("429")) {
                    // 记录 429 时间，触发全局冷却
                    lastRateLimitTime = System.currentTimeMillis();
                    if (attempt < maxRetries) {
                        System.out.println("[StockTool] Yahoo Finance 429 限流，重试 1 次，等待 5 秒...");
                        Thread.sleep(5000);
                        continue;
                    }
                    throw new RuntimeException("Yahoo Finance 限流（429），已进入 60 秒冷却，请稍后再试");
                }
                throw e;
            }
        }
        throw new RuntimeException("Yahoo Finance 请求失败");
    }

    /** 解析 Yahoo Finance 历史K线数据 */
    private List<HistoricalBar> parseYahooHistoricalBars(String json) {
        List<HistoricalBar> bars = new ArrayList<>();

        List<Long> timestamps = extractLongArray(json, "\"timestamp\"");
        List<Double> opens = extractDoubleArray(json, "\"open\"");
        List<Double> highs = extractDoubleArray(json, "\"high\"");
        List<Double> lows = extractDoubleArray(json, "\"low\"");
        List<Double> closes = extractDoubleArray(json, "\"close\"");
        List<Long> volumes = extractLongArray(json, "\"volume\"");

        if (timestamps.isEmpty()) return bars;

        // 按时间倒序组装（最新的在前）
        for (int i = timestamps.size() - 1; i >= 0; i--) {
            if (i >= closes.size() || closes.get(i) == null) continue;

            String date = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamps.get(i)),
                    ZoneId.of("America/New_York")
            ).format(DATE_FORMAT);

            bars.add(new HistoricalBar(
                    date,
                    formatDouble(opens, i),
                    formatDouble(highs, i),
                    formatDouble(lows, i),
                    String.format("%.2f", closes.get(i)),
                    i < volumes.size() && volumes.get(i) != null
                            ? String.valueOf(volumes.get(i)) : null
            ));
        }

        return bars;
    }

    private String formatDouble(List<Double> list, int idx) {
        if (idx >= list.size() || list.get(idx) == null) return null;
        return String.format("%.2f", list.get(idx));
    }

    /** 从 JSON 中提取 Long 数组 */
    private List<Long> extractLongArray(String json, String key) {
        List<Long> result = new ArrayList<>();
        int idx = json.indexOf(key);
        if (idx < 0) return result;
        int bracketStart = json.indexOf("[", idx);
        int bracketEnd = json.indexOf("]", bracketStart);
        if (bracketStart < 0 || bracketEnd < 0) return result;
        String arrayStr = json.substring(bracketStart + 1, bracketEnd);
        for (String s : arrayStr.split(",")) {
            s = s.trim();
            if (s.isEmpty() || s.equals("null")) {
                result.add(null);
            } else {
                try {
                    result.add(Long.parseLong(s));
                } catch (NumberFormatException e) {
                    result.add(null);
                }
            }
        }
        return result;
    }

    /** 从 JSON 中提取 Double 数组 */
    private List<Double> extractDoubleArray(String json, String key) {
        List<Double> result = new ArrayList<>();
        int idx = json.indexOf(key);
        if (idx < 0) return result;
        int bracketStart = json.indexOf("[", idx);
        int bracketEnd = json.indexOf("]", bracketStart);
        if (bracketStart < 0 || bracketEnd < 0) return result;
        String arrayStr = json.substring(bracketStart + 1, bracketEnd);
        for (String s : arrayStr.split(",")) {
            s = s.trim();
            if (s.isEmpty() || s.equals("null")) {
                result.add(null);
            } else {
                try {
                    result.add(Double.parseDouble(s));
                } catch (NumberFormatException e) {
                    result.add(null);
                }
            }
        }
        return result;
    }

    /** 从 JSON 中提取单个数值 */
    private String extractJsonNumber(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-'
                || json.charAt(end) == 'e' || json.charAt(end) == 'E')) {
            end++;
        }
        String value = json.substring(start, end);
        return value.isEmpty() ? null : value;
    }

    /** 从 JSON 中提取单个字符串值 */
    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = json.indexOf("\"", start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    /** 过滤异常信息中的敏感内容（API Key、token、URL 等） */
    private String safeErrorMessage(String errorMsg) {
        if (errorMsg == null) return "未知错误";
        // 过滤 API Key
        String filtered = errorMsg.replaceAll("(?i)(token|apikey|api_key|key)=([^&\\s\"']+)", "$1=***");
        // 过滤 URL 中的查询参数（可能包含 token）
        filtered = filtered.replaceAll("https?://[^\\s\"']+", "[URL已隐藏]");
        // 限制长度
        if (filtered.length() > 200) {
            filtered = filtered.substring(0, 200) + "...";
        }
        return filtered;
    }

    private Response buildError(String symbol, String interval, String message) {
        return new Response(symbol, null, null, null, null, null, null, null,
                null, null, interval, null, null, message);
    }
}
