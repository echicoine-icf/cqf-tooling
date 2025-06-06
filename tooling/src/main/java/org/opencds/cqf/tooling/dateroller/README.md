# RollTestDataDates

This operation takes a file or a directory and rolls forward dates in resources and cds hook requests. It then 
overwrites the original files with the updated ones.

## Description of Operation

If a resource in a xml or json file has an extension

    http://fhir.org/guides/cdc/opioid-cds/StructureDefinition/dataDateRoller 
and if the current date is greater than the valueDuration set in that extension (i.e. 30 days) that resource will have 
its date, period, dateTimeType, etc. fields changed according to the relation of the date in that field to the 
dateLastUpdated value in the extension. This also applies to cds hook request test data. If the extension is not 
present, that resource is skipped. If the current date is not more than the duration from the lastUpdated date, that 
resource is skipped.

It may be done based on a file name or a directory.
An example command line would be:

    JAVA -jar tooling-cli-2.1.0-SNAPSHOT.jar -RollTestsDataDates -v=r4 -ip="$USER_HOME$/sandbox/rollDate/files/"

OR

    JAVA -jar tooling-cli-2.1.0-SNAPSHOT.jar -RollTestsDataDates -v=r4 -ip="$USER_HOME$/sandbox/rollDate/files/bundle-example-rec-02-true-make-recommendations.json"


Sample extension:
    
    "extension": [
        {
            "url": "http://fhir.org/guides/cdc/opioid-cds/StructureDefinition/dataDateRoller",
            "extension": [
                {
                    "url": "dateLastUpdated",
                    "valueDateTime": "2022-01-28"
                },
                {
                    "url": "frequency",
                    "valueDuration": {
                        "value": 30.0,
                        "unit": "days",
                        "system": "http://unitsofmeasure.org",
                        "code": "d"
                    }
                }
            ]
        }
    ],
