package org.opencds.cqf.tooling.operation;

import org.junit.Test;
import org.opencds.cqf.tooling.Operation;

public class GenerateCQLFromDroolOperationTest {
    @Test
    public void test_worked() {
        String operation = "GenerateCQLFromDrool";
        String inputFilePath = "../CQLGenerationDocs/NonGeneratedDocs/default.json";
        String outputPath = "../CQLGenerationDocs/GeneratedDocs";
        String encoding = "json";
        String[] args = { "-" + operation, "-ifp=" + inputFilePath, "-op=" + outputPath, "-e=" + encoding };
        Operation generateCQLFromDroolOperation = new GenerateCQLFromDroolOperation();
        generateCQLFromDroolOperation.execute(args);
    }
}