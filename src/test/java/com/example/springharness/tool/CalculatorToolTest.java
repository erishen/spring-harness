package com.example.springharness.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CalculatorTool 单元测试：四则运算与除零保护。
 */
class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    private CalculatorTool.Response run(double a, double b, String op) {
        return tool.apply(new CalculatorTool.Request(a, b, op));
    }

    @Test
    void add() {
        assertThat(run(2, 3, "add").result()).isEqualTo(5);
        assertThat(run(2, 3, "add").expression()).isEqualTo("2.0 + 3.0 = 5.0");
    }

    @Test
    void subtract() {
        assertThat(run(10, 4, "subtract").result()).isEqualTo(6);
    }

    @Test
    void multiply() {
        assertThat(run(3, 7, "multiply").result()).isEqualTo(21);
    }

    @Test
    void divide() {
        assertThat(run(10, 4, "divide").result()).isEqualTo(2.5);
    }

    @Test
    void divideByZeroReturnsError() {
        CalculatorTool.Response r = run(5, 0, "divide");
        assertThat(r.result()).isZero();
        assertThat(r.expression()).contains("除数不能为零");
    }

    @Test
    void caseInsensitiveOperation() {
        assertThat(run(2, 3, "ADD").result()).isEqualTo(5);
        assertThat(run(2, 3, "Multiply").result()).isEqualTo(6);
    }

    @Test
    void negativeNumbers() {
        assertThat(run(-5, 3, "add").result()).isEqualTo(-2);
        assertThat(run(-5, -2, "multiply").result()).isEqualTo(10);
    }
}
