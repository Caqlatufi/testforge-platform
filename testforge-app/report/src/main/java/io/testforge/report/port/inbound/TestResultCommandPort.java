package io.testforge.report.port.inbound;

import io.testforge.report.model.TestResultCommand;

/**
 * 接收确定性测试结果的报告模块入站端口。
 */
public interface TestResultCommandPort {

    void record(TestResultCommand command);
}
