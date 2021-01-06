package org.opencds.cqf.tooling.processor.argument;

import static java.util.Arrays.asList;

import org.opencds.cqf.tooling.parameter.GenerateCQLFromDroolParameters;
import org.opencds.cqf.tooling.utilities.ArgUtils;
import org.opencds.cqf.tooling.utilities.IOUtils.Encoding;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;

public class GenerateCQLFromDroolArgumentProcessor {

    public static final String[] OPERATION_OPTIONS = {"GenerateCQLFromDrool"};

    public static final String[] OUTPUT_PATH_OPTIONS = {"op", "outputPath", "outputpath", "o", "output"};
    public static final String[] ENCODING_OPTIONS = {"e", "encoding"};
    public static final String[] COMMAND_OPTIONS = {"c", "command"};
    public static final String[] INPUT_FILE_PATH_OPTIONS = {"ip", "inputPath", "input-path", "ifp", "inputFilePath", "input-file-path", "input-filePath"};

    public OptionParser build() {
        OptionParser parser = new OptionParser();

        OptionSpecBuilder outputBuilder = parser.acceptsAll(asList(OUTPUT_PATH_OPTIONS),"Will be created if file path does not currently exist.");
        OptionSpecBuilder encodingBuilder = parser.acceptsAll(asList(ENCODING_OPTIONS), "If omitted, encoding input will be expected to be json.");
        OptionSpecBuilder inputFilePathBuilder = parser.acceptsAll(asList(INPUT_FILE_PATH_OPTIONS),"Must be a path to encoded logic export required for cql generation.");
        OptionSpecBuilder commandBuilder = parser.acceptsAll(asList(COMMAND_OPTIONS),"If omitted, command will be cql");

        OptionSpec<String> outputPath = outputBuilder.withRequiredArg().describedAs("path to desired cql generation output");
        OptionSpec<String> encoding = encodingBuilder.withOptionalArg().describedAs("input encoding (as of now can only be json)").defaultsTo("json"); 
        OptionSpec<String> inputFilePath = inputFilePathBuilder.withRequiredArg().describedAs("input encoded file path");
        OptionSpec<String> command = commandBuilder.withOptionalArg().describedAs("generation command to be executed (cql, modeling, html");

        parser.acceptsAll(asList(OPERATION_OPTIONS),"The operation to run.");

        OptionSpec<Void> help = parser.acceptsAll(asList(ArgUtils.HELP_OPTIONS), "Show this help page").forHelp();

        return parser;
    }

	public GenerateCQLFromDroolParameters parseAndConvert(String[] args) {
		OptionParser parser = build();
        OptionSet options = ArgUtils.parse(args, parser);

        ArgUtils.ensure(OPERATION_OPTIONS[0], options);

        String outputPath = (String)options.valueOf(OUTPUT_PATH_OPTIONS[0]);
        String inputFilePath = (String)options.valueOf(INPUT_FILE_PATH_OPTIONS[0]);
        String command = (String)options.valueOf(COMMAND_OPTIONS[0]);
        if (command == null) {
            command = "cql";
        }
        String encoding = (String)options.valueOf(ENCODING_OPTIONS[0]);
        Encoding encodingEnum = Encoding.parse(encoding.toLowerCase());
    
        GenerateCQLFromDroolParameters gcdp = new GenerateCQLFromDroolParameters();
        gcdp.outputPath = outputPath;
        gcdp.encoding = encodingEnum;
        gcdp.inputFilePath = inputFilePath;
        gcdp.command = command;
       
        return gcdp;
	}

}