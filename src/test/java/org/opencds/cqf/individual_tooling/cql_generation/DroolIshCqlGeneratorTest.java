package org.opencds.cqf.individual_tooling.cql_generation;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import org.junit.Test;
import org.opencds.cqf.individual_tooling.cql_generation.drool.DroolIshCqlGenerator;

public class DroolIshCqlGeneratorTest {

    @Test
    public void test_worked() {
        String encodingPath = "C:\\src\\GitHub\\ChlamydiaConditionCriteriaRels.json";
        String outputPath = "C:\\src\\GitHub\\generatedCQL.cql";
        File file = new File(encodingPath);
        Boolean fileIsFile = file.isFile();
        if (fileIsFile) {
            URI encodingUri = file.toURI();

            DroolIshCqlGenerator droolIshCqlGenerator = new DroolIshCqlGenerator(outputPath);

            File outputFile = new File(outputPath);
            Boolean outputFileIsFile = outputFile.isFile();
            if (!outputFileIsFile) {
                try {
                    outputFile.createNewFile();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            droolIshCqlGenerator.generate(encodingUri);
        } else {
            System.out.println("I am Failure.");
        }
    }
}