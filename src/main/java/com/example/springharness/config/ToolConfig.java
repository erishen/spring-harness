package com.example.springharness.config;

import com.example.springharness.tool.CalculatorTool;
import com.example.springharness.tool.CodeExecutionTool;
import com.example.springharness.tool.DateTimeTool;
import com.example.springharness.tool.ExchangeRateTool;
import com.example.springharness.tool.FileReadTool;
import com.example.springharness.tool.RagSearchTool;
import com.example.springharness.tool.SkillRunTool;
import com.example.springharness.tool.StockTool;
import com.example.springharness.service.SkillService;
import com.example.springharness.sandbox.DockerSandboxExecutor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具配置类：注册三个工具回调。
 * 注意：Spring AI 1.1 的 ChatClient.Builder.defaultTools() 只接受 @Tool 注解对象，
 * 不接受 ToolCallback。工具在 Controller 调用时通过 .tools() 传入。
 */
@Configuration
public class ToolConfig {

    @Value("${FINNHUB_API_KEY:}")
    private String finnhubApiKey;

    @Bean
    public ToolCallback calculatorTool() {
        return FunctionToolCallback.builder("calculator", new CalculatorTool())
                .description("通用计算器，支持加减乘除运算")
                .inputType(CalculatorTool.Request.class)
                .build();
    }

    @Bean
    public ToolCallback dateTimeTool() {
        return FunctionToolCallback.builder("get_datetime", new DateTimeTool())
                .description("获取当前日期和时间，包括星期几")
                .inputType(DateTimeTool.Request.class)
                .build();
    }

    @Bean
    public ToolCallback stockTool() {
        return FunctionToolCallback.builder("query_stock", new StockTool(finnhubApiKey))
                .description("查询股票实时行情（稳定可用，优先使用）。返回当前价格、涨跌幅、开盘价、最高价、最低价、成交量。支持美股(AAPL, MSFT, TSLA)、港股(0700.HK)、A股(600519.SS)。注意：默认只查实时行情，不要主动查历史数据。仅当用户明确要求'历史数据'、'K线'、'过去N天股价'时，才传interval=daily/weekly/monthly参数查询历史数据（历史数据有频率限制，可能失败）")
                .inputType(StockTool.Request.class)
                .build();
    }

    @Bean
    public ToolCallback exchangeRateTool() {
        return FunctionToolCallback.builder("query_exchange_rate", new ExchangeRateTool())
                .description("查询实时汇率，支持美元(USD)、欧元(EUR)、日元(JPY)、港币(HKD)、英镑(GBP)等货币兑人民币(CNY)的实时汇率，返回汇率和更新时间。需要换算货币时必须调用此工具，不要自行估算汇率。")
                .inputType(ExchangeRateTool.Request.class)
                .build();
    }

    @Bean
    public ToolCallback codeExecutionTool(DockerSandboxExecutor sandboxExecutor) {
        return FunctionToolCallback.builder("execute_code", new CodeExecutionTool(sandboxExecutor))
                .description("在 Docker 沙箱中执行代码，支持 python、javascript、shell、java、go、rust、c、cpp。返回标准输出、标准错误、退出码和执行时间。适用于复杂计算、数据分析、算法验证、文件处理等需要实际运行代码的场景。")
                .inputType(CodeExecutionTool.Request.class)
                .build();
    }

    @Bean
    public ToolCallback skillRunTool(SkillService skillService) {
        return FunctionToolCallback.builder("skill_run", new SkillRunTool(skillService))
                .description("加载指定技能的完整指令。当用户的需求匹配某个技能时，先调用此工具加载技能的详细步骤和规则，再按技能要求执行。参数为技能名称。")
                .inputType(SkillRunTool.Request.class)
                .build();
    }

    @Bean
    public ToolCallback searchKnowledgeCallback(RagSearchTool ragSearchTool) {
        return FunctionToolCallback.builder("search_knowledge", ragSearchTool)
                .description("知识库语义检索（Rerank 精排）：基于已上传的知识库文档（PDF/TXT/MD）检索并精排与问题最相关的文本片段。当用户问题涉及已上传文档的内容（如产品资料、项目文档、规章制度等）时，先调用此工具检索相关上下文，再基于检索结果回答；不要凭印象编造知识库内容。参数：query（检索问题/关键词）、topK（返回片段数，默认4，最大8）。")
                .inputType(RagSearchTool.Request.class)
                .build();
    }

    @Bean
    public ToolCallback fileReadTool() {
        return FunctionToolCallback.builder("read_project_file", new FileReadTool())
                .description("读取项目目录下的本地文本文件内容。当需要审查代码、查看配置、分析文件内容、执行 code-review 等技能时使用。参数为文件路径（相对于项目根目录，如 src/main/java/com/example/MyClass.java）。安全限制：只能读取项目目录下的文本文件，不能读取 .env 等敏感文件，单文件最大 100KB。注意：与 MCP 的 read_file 不同，此工具专门读取项目源代码文件。")
                .inputType(FileReadTool.Request.class)
                .build();
    }
}
