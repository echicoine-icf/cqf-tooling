package org.opencds.cqf.tooling.processor;

import ca.uhn.fhir.context.FhirContext;
import org.apache.commons.io.FilenameUtils;
import org.cqframework.cql.cql2elm.CqlCompilerException;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.opencds.cqf.tooling.common.ThreadUtils;
import org.opencds.cqf.tooling.cql.exception.CqlTranslatorException;
import org.opencds.cqf.tooling.library.LibraryProcessor;
import org.opencds.cqf.tooling.utilities.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An abstract base class for bundlers that handle the bundling of various types of resources within an ig.
 * This class provides methods for bundling resources, including dependencies and test cases, and handles the execution of associated tasks.
 * Subclasses must implement specific methods for gathering, processing, and persisting resources.
 */
public abstract class AbstractBundler {
    public static final String separator = System.getProperty("file.separator");
    public static final String NEWLINE_INDENT2 = "\n\t\t";
    public static final String NEWLINE_INDENT = "\r\n\t";
    public static final String NEWLINE = "\r\n";
    /**
     * The logger for logging messages specific to the implementing class.
     */
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * The resource type constant for Questionnaire.
     */
    protected final String TYPE_QUESTIONNAIRE = "Questionnaire";

    /**
     * The resource type constant for PlanDefinition.
     */
    protected final String TYPE_PLAN_DEFINITION = "PlanDefinition";

    /**
     * The resource type constant for Measure.
     */
    protected final String TYPE_MEASURE = "Measure";
    private List<Object> identifiers;
    private CDSHooksProcessor cdsHooksProcessor;
    private LibraryProcessor libraryProcessor;

    /**
     * Sets the LibraryProcessor for handling library-related tasks.
     *
     * @param libraryProcessor The LibraryProcessor instance to set.
     */
    protected void setLibraryProcessor(LibraryProcessor libraryProcessor) {
        this.libraryProcessor = libraryProcessor;
    }

    /**
     * Sets the CDSHooksProcessor for handling CDS Hooks-related tasks.
     *
     * @param cdsHooksProcessor The CDSHooksProcessor instance to set.
     */
    protected void setCDSHooksProcessor(CDSHooksProcessor cdsHooksProcessor) {
        this.cdsHooksProcessor = cdsHooksProcessor;
    }

    protected List<Object> getIdentifiers() {
        if (identifiers == null) {
            identifiers = new CopyOnWriteArrayList<>();
        }
        return identifiers;
    }

    private String getResourcePrefix() {
        return getResourceProcessorType().toLowerCase() + "-";
    }

    protected abstract Set<String> getPaths(FhirContext fhirContext);

    protected abstract String getSourcePath(FhirContext fhirContext, Map.Entry<String, IBaseResource> resourceEntry);

    /**
     * Handled by the child class in gathering specific IBaseResources
     */
    protected abstract Map<String, IBaseResource> getResources(FhirContext fhirContext);

    protected abstract String getResourceProcessorType();


    /**
     * Bundles resources within an Implementation Guide based on specified options.
     *
     * @param refreshedLibraryNames   A list of refreshed library names.
     * @param igPath                  The path to the IG.
     * @param binaryPaths             The list of binary paths.
     * @param includeDependencies     Flag indicating whether to include dependencies.
     * @param includeTerminology      Flag indicating whether to include terminology.
     * @param includePatientScenarios Flag indicating whether to include patient scenarios.
     * @param includeVersion          Flag indicating whether to include version information.
     * @param addBundleTimestamp      Flag indicating whether to add a timestamp to the bundle.
     * @param fhirContext             The FHIR context.
     * @param fhirUri                 The FHIR server URI.
     * @param encoding                The encoding type for processing resources.
     */
    public void bundleResources(ArrayList<String> refreshedLibraryNames, String igPath, List<String> binaryPaths, Boolean includeDependencies,
                                Boolean includeTerminology, Boolean includePatientScenarios, Boolean includeVersion, Boolean addBundleTimestamp,
                                FhirContext fhirContext, String fhirUri, IOUtils.Encoding encoding, Boolean includeErrors) {

        final Map<String, IBaseResource> resourcesMap = getResources(fhirContext);
        final List<String> bundledResources = new CopyOnWriteArrayList<>();

        //for keeping track of progress:
        final List<String> processedResources = new CopyOnWriteArrayList<>();

        //for keeping track of failed reasons:
        final Map<String, String> failedExceptionMessages = new ConcurrentHashMap<>();

        final Map<String, List<CqlCompilerException>> cqlTranslatorErrorMessages = new ConcurrentHashMap<>();

        //build list of tasks via for loop:
        List<Callable<Void>> tasks = new ArrayList<>();
        try {
            final StringBuilder bundleTestFileStringBuilder = new StringBuilder();
            final Map<String, IBaseResource> libraryUrlMap = IOUtils.getLibraryUrlMap(fhirContext);
            final Map<String, IBaseResource> libraries = IOUtils.getLibraries(fhirContext);
            final Map<String, String> libraryPathMap = IOUtils.getLibraryPathMap(fhirContext);

            for (Map.Entry<String, IBaseResource> resourceEntry : resourcesMap.entrySet()) {
                String resourceId = "";

                if (resourceEntry.getValue() != null) {
                    resourceId = resourceEntry.getValue()
                            .getIdElement().getIdPart();
                } else {
                    continue;
                }

                //no path for this resource:
                if (resourceEntry.getKey() == null ||
                        resourceEntry.getKey().equalsIgnoreCase("null")) {
                    if (resourceId != null && !resourceId.isEmpty()) {
                        failedExceptionMessages.put(resourceId, "Path is null for " + resourceId);
                    }
                    continue;
                }

                final String resourceSourcePath = getSourcePath(fhirContext, resourceEntry);

                tasks.add(() -> {
                    //check if resourceSourcePath has been processed before:
                    if (processedResources.contains(resourceSourcePath)) {
                        System.out.println(getResourceProcessorType() + " processed already: " + resourceSourcePath);
                        return null;
                    }
                    String resourceName = FilenameUtils.getBaseName(resourceSourcePath).replace(getResourcePrefix(), "");

                    try {
                        Map<String, IBaseResource> resources = new ConcurrentHashMap<>();

                        Boolean shouldPersist = ResourceUtils.safeAddResource(resourceSourcePath, resources, fhirContext);
                        if (!resources.containsKey(getResourceProcessorType() + "/" + resourceEntry.getKey())) {
                            throw new IllegalArgumentException(String.format("Could not retrieve base resource for " + getResourceProcessorType() + " %s", resourceName));
                        }

                        IBaseResource resource = resources.get(getResourceProcessorType() + "/" + resourceEntry.getKey());
                        String primaryLibraryUrl = ResourceUtils.getPrimaryLibraryUrl(resource, fhirContext);
                        IBaseResource primaryLibrary;
                        if (primaryLibraryUrl != null && primaryLibraryUrl.startsWith("http")) {
                            primaryLibrary = libraryUrlMap.get(primaryLibraryUrl);
                        } else {
                            primaryLibrary = libraries.get(primaryLibraryUrl);
                        }

                        if (primaryLibrary == null)
                            throw new IllegalArgumentException(String.format("Could not resolve library url %s", primaryLibraryUrl));

                        String primaryLibrarySourcePath = libraryPathMap.get(primaryLibrary.getIdElement().getIdPart());
                        String primaryLibraryName = ResourceUtils.getName(primaryLibrary, fhirContext);
                        if (includeVersion) {
                            primaryLibraryName = primaryLibraryName + "-" +
                                    fhirContext.newFhirPath().evaluateFirst(primaryLibrary, "version", IBase.class).get().toString();
                        }

                        shouldPersist = shouldPersist
                                & ResourceUtils.safeAddResource(primaryLibrarySourcePath, resources, fhirContext);

                        String cqlFileName = IOUtils.formatFileName(primaryLibraryName, IOUtils.Encoding.CQL, fhirContext);

                        String cqlLibrarySourcePath = IOUtils.getCqlLibrarySourcePath(primaryLibraryName, cqlFileName, binaryPaths);

                        if (cqlLibrarySourcePath == null) {
                            failedExceptionMessages.put(resourceSourcePath, String.format("Could not determine CqlLibrarySource path for library %s", primaryLibraryName));
                            //exit from task:
                            return null;
                        }

                        if (includeTerminology) {
                            //throws CQLTranslatorException if failed with severe errors, which will be logged and reported it in the final summary
                            try {
                                ValueSetsProcessor.bundleValueSets(cqlLibrarySourcePath, igPath, fhirContext, resources, encoding, includeDependencies, includeVersion);
                            } catch (CqlTranslatorException cqlTranslatorException) {
                                cqlTranslatorErrorMessages.put(primaryLibraryName, cqlTranslatorException.getErrors());
                            }
                        }

                        if (includeDependencies) {
                            if (libraryProcessor == null) libraryProcessor = new LibraryProcessor();

                            boolean result = libraryProcessor.bundleLibraryDependencies(primaryLibrarySourcePath, fhirContext, resources, encoding, includeVersion);
                            if (shouldPersist && !result) {
                                failedExceptionMessages.put(resourceSourcePath, getResourceProcessorType() + " will not be bundled because Library Dependency bundling failed.");
                                //exit from task:
                                return null;
                            }
                            shouldPersist = shouldPersist & result;
                        }

                        if (includePatientScenarios) {
                            boolean result = TestCaseProcessor.bundleTestCases(igPath, getResourceTestGroupName(), primaryLibraryName, fhirContext, resources);
                            if (shouldPersist && !result) {
                                failedExceptionMessages.put(resourceSourcePath, getResourceProcessorType() + " will not be bundled because Test Case bundling failed.");
                                //exit from task:
                                return null;
                            }
                            shouldPersist = shouldPersist & result;
                        }

                        if (shouldPersist) {

                            String bundleDestPath = FilenameUtils.concat(FilenameUtils.concat(IGProcessor.getBundlesPath(igPath), getResourceTestGroupName()), resourceName);

                            //create bundle, post bundle to fhir server:
                            String builtBundleDestPath = persistBundle(bundleDestPath, resourceName, encoding, fhirContext, new ArrayList<>(resources.values()), fhirUri, addBundleTimestamp);

                            String possibleBundleTestMessage = bundleFiles(igPath, bundleDestPath, resourceName, binaryPaths, resourceSourcePath,
                                    primaryLibrarySourcePath, fhirContext, encoding, includeTerminology, includeDependencies, includePatientScenarios,
                                    includeVersion, addBundleTimestamp, cqlTranslatorErrorMessages);

                            //Keep track of which files have been persisted
                            List<String> persistedFiles = new ArrayList<>();
                            persistedFiles.add(builtBundleDestPath);

                            //All tests-* files must be prior to remaining files.
                            persistedFiles.addAll(persistTestFiles(bundleDestPath, resourceName, encoding, fhirContext, fhirUri));

                            //find all files that end with .cql, .xml, or .json, and haven't already been posted and recorded to persistedFiles list:
                            persistedFiles.addAll(persistRemainingFiles(bundleDestPath, resourceName, encoding, fhirContext, fhirUri, persistedFiles));

                            if (cdsHooksProcessor != null) {
                                List<String> activityDefinitionPaths = CDSHooksProcessor.bundleActivityDefinitions(resourceSourcePath, fhirContext, resources, encoding, includeVersion, shouldPersist);
                                cdsHooksProcessor.addActivityDefinitionFilesToBundle(igPath, bundleDestPath, activityDefinitionPaths, fhirContext, encoding);
                            }

                            //Inform user of total # of files copied during  Bundle Test Case Files process:
                            if (!possibleBundleTestMessage.isEmpty()) {
                                bundleTestFileStringBuilder.append(possibleBundleTestMessage);
                            }

                            //If user supplied a fhir server url, inform them of total # of files to be persisted to the server:
                            if (fhirUri != null && !fhirUri.isEmpty()) {
                                bundleTestFileStringBuilder.append("\r\n")
                                        .append(persistedFiles.size())
                                        .append(" total files will be posted to ")
                                        .append(fhirUri)
                                        .append(" for ")
                                        .append(resourceName);
                            }

                            bundledResources.add(resourceSourcePath);
                        }


                    } catch (Exception e) {
                        if (resourceSourcePath == null) {
                            failedExceptionMessages.put(resourceEntry.getValue().getIdElement().getIdPart(), e.getMessage());
                        } else {
                            failedExceptionMessages.put(resourceSourcePath, e.getMessage());
                        }
                    }

                    processedResources.add(resourceSourcePath);
                    reportProgress(processedResources.size(), tasks.size());

                    //task requires return statement
                    return null;
                });

            }//end for loop

            ThreadUtils.executeTasks(tasks);

            //Test file information:
            String bundleTestFileMessage = bundleTestFileStringBuilder.toString();
            if (!bundleTestFileMessage.isEmpty()) {
                System.out.println(bundleTestFileMessage);
            }


        } catch (Exception e) {
            LogUtils.putException("bundleResources: " + getResourceProcessorType(), e);
        }


        StringBuilder message = new StringBuilder(NEWLINE + bundledResources.size() + " " + getResourceProcessorType() + "(s) successfully bundled:");
        for (String bundledResource : bundledResources) {
            message.append(NEWLINE_INDENT).append(bundledResource).append(" BUNDLED");
        }

        List<String> resourcePathLibraryNames = new ArrayList<>(getPaths(fhirContext));

        //gather which resources didn't make it
        ArrayList<String> failedResources = new ArrayList<>(resourcePathLibraryNames);

        resourcePathLibraryNames.removeAll(bundledResources);
        resourcePathLibraryNames.retainAll(refreshedLibraryNames);
        message.append(NEWLINE).append(resourcePathLibraryNames.size()).append(" ").append(getResourceProcessorType()).append("(s) refreshed, but not bundled (due to issues):");
        for (String notBundled : resourcePathLibraryNames) {
            message.append(NEWLINE_INDENT).append(notBundled).append(" REFRESHED");
        }

        //attempt to give some kind of informational message:
        failedResources.removeAll(bundledResources);
        failedResources.removeAll(resourcePathLibraryNames);
        message.append(NEWLINE).append(failedResources.size()).append(" ").append(getResourceProcessorType()).append("(s) failed refresh:");
        for (String failed : failedResources) {
            if (failedExceptionMessages.containsKey(failed)) {
                message.append(NEWLINE_INDENT).append(failed).append(" FAILED: ").append(failedExceptionMessages.get(failed));
            } else {
                message.append(NEWLINE_INDENT).append(failed).append(" FAILED");
            }
        }

        //Exceptions stemming from IOUtils.translate that did not prevent process from completing for file:
        if (!cqlTranslatorErrorMessages.isEmpty()) {
            message.append(NEWLINE).append(cqlTranslatorErrorMessages.size()).append(" ").append(getResourceProcessorType()).append("(s) encountered CQL translator errors:" + NEWLINE_INDENT);

            for (String library : cqlTranslatorErrorMessages.keySet()) {
                message.append(
                        CqlProcessor.buildStatusMessage(cqlTranslatorErrorMessages.get(library), library, includeErrors, false, NEWLINE_INDENT2)
                ).append(NEWLINE);
            }
        }

        System.out.println(message.toString());
    }


    private void reportProgress(int count, int total) {
        double percentage = (double) count / total * 100;
        System.out.print("\rBundle " + getResourceProcessorType() + "s: " + String.format("%.2f%%", percentage) + " processed.");
    }

    private String getResourceTestGroupName() {
        return getResourceProcessorType().toLowerCase();
    }

    private String persistBundle(String bundleDestPath, String libraryName, IOUtils.Encoding encoding, FhirContext fhirContext, List<IBaseResource> resources, String fhirUri, Boolean addBundleTimestamp) {
        IOUtils.initializeDirectory(bundleDestPath);

        Object bundle = BundleUtils.bundleArtifacts(libraryName, resources, fhirContext, addBundleTimestamp, this.getIdentifiers());
        IOUtils.writeBundle(bundle, bundleDestPath, encoding, fhirContext);
        try {
            HttpClientUtils.post(fhirUri, (IBaseResource) bundle, encoding, fhirContext, bundleDestPath, true);
        } catch (IOException e) {
            LogUtils.putException(bundleDestPath, "Error posting to FHIR Server: " + fhirUri + ".  Bundle not posted.");
        }
        return bundleDestPath;
    }

    protected List<String> persistFileList(IOUtils.Encoding encoding, FhirContext fhirContext, String fhirUri, File[] filesInDir, boolean withPriority) {

        List<String> postedResourcesFileLocations = new ArrayList<>();

        if (!(filesInDir == null || filesInDir.length == 0)) {
            for (File file : filesInDir) {
                String failMsg = "Bundle Measures failed to persist resource " + file.getAbsolutePath();
                try {
                    //ensure the resource can be posted
                    try {
                        IBaseResource resource = IOUtils.readResource(file.getAbsolutePath(), fhirContext, true);
                        if (resource != null) {
                            try {
                                HttpClientUtils.post(fhirUri, resource, encoding, fhirContext, file.getAbsolutePath(), withPriority);
                                postedResourcesFileLocations.add(file.getAbsolutePath());
                            } catch (IOException e) {
                                LogUtils.putException(file.getAbsolutePath(), "Error posting to FHIR Server: " + fhirUri + ".  Bundle not posted.");
                            }
                        }
                    } catch (Exception e) {
                        System.out.println(failMsg);
                    }

                } catch (Exception e) {
                    //resource is likely not IBaseResource
                    logger.error("persistTestFiles", e);
                }
            }
        }
        return postedResourcesFileLocations;
    }

    private List<String> persistTestFiles(String bundleDestPath, String libraryName, IOUtils.Encoding encoding, FhirContext fhirContext, String fhirUri) {
        List<String> postedResourcesFileLocations = new ArrayList<>();
        String filesLoc = bundleDestPath + File.separator + libraryName + "-files";
        File directory = new File(filesLoc);
        if (directory.exists()) {
            File[] filesInDir = directory.listFiles((dir, name) -> name.toLowerCase().startsWith("tests-"));
            postedResourcesFileLocations.addAll(persistFileList(encoding, fhirContext, fhirUri, filesInDir, true));
        }
        return postedResourcesFileLocations;
    }

    private List<String> persistRemainingFiles(String bundleDestPath, String libraryName, IOUtils.Encoding encoding, FhirContext fhirContext, String fhirUri, List<String> previouslyPostedResourcesFileLocations) {
        List<String> postedResourcesFileLocations = new ArrayList<>();
        String filesLoc = bundleDestPath + File.separator + libraryName + "-files";
        File directory = new File(filesLoc);
        if (directory.exists()) {
            File[] filesInDir = directory.listFiles((dir, name) ->
                    (name.toLowerCase().endsWith(".cql") ||
                            name.toLowerCase().endsWith(".xml") ||
                            name.toLowerCase().endsWith(".json"))
                            &&
                            (!previouslyPostedResourcesFileLocations.contains(dir + separator + name))
            );
            postedResourcesFileLocations.addAll(persistFileList(encoding, fhirContext, fhirUri, filesInDir, false));
        }
        return postedResourcesFileLocations;
    }

    private String bundleFiles(String igPath, String bundleDestPath, String primaryLibraryName, List<String> binaryPaths, String resourceFocusSourcePath,
                               String librarySourcePath, FhirContext fhirContext, IOUtils.Encoding encoding, Boolean includeTerminology, Boolean includeDependencies, Boolean includePatientScenarios,
                               Boolean includeVersion, Boolean addBundleTimestamp, Map<String, List<CqlCompilerException>> translatorWarningMessages) {
        String bundleMessage = "";

        String bundleDestFilesPath = FilenameUtils.concat(bundleDestPath, primaryLibraryName + "-" + IGBundleProcessor.bundleFilesPathElement);
        IOUtils.initializeDirectory(bundleDestFilesPath);

        IOUtils.copyFile(resourceFocusSourcePath, FilenameUtils.concat(bundleDestFilesPath, FilenameUtils.getName(resourceFocusSourcePath)));
        IOUtils.copyFile(librarySourcePath, FilenameUtils.concat(bundleDestFilesPath, FilenameUtils.getName(librarySourcePath)));

        String cqlFileName = IOUtils.formatFileName(FilenameUtils.getBaseName(librarySourcePath), IOUtils.Encoding.CQL, fhirContext);
        if (cqlFileName.toLowerCase().startsWith("library-")) {
            cqlFileName = cqlFileName.substring(8);
        }
        String cqlLibrarySourcePath = IOUtils.getCqlLibrarySourcePath(primaryLibraryName, cqlFileName, binaryPaths);
        String cqlDestPath = FilenameUtils.concat(bundleDestFilesPath, cqlFileName);
        IOUtils.copyFile(cqlLibrarySourcePath, cqlDestPath);

        if (includeTerminology) {
            try {
                Map<String, IBaseResource> valueSets = ResourceUtils.getDepValueSetResources(cqlLibrarySourcePath, igPath, fhirContext, includeDependencies, includeVersion);
                if (!valueSets.isEmpty()) {
                    Object bundle = BundleUtils.bundleArtifacts(ValueSetsProcessor.getId(primaryLibraryName), new ArrayList<IBaseResource>(valueSets.values()), fhirContext, addBundleTimestamp, this.getIdentifiers());
                    IOUtils.writeBundle(bundle, bundleDestFilesPath, encoding, fhirContext);
                }
            } catch (CqlTranslatorException cqlTranslatorException) {
                translatorWarningMessages.put(primaryLibraryName, cqlTranslatorException.getErrors());
            }
        }

        if (includeDependencies) {
            Map<String, IBaseResource> depLibraries = ResourceUtils.getDepLibraryResources(librarySourcePath, fhirContext, encoding, includeVersion, logger);
            if (!depLibraries.isEmpty()) {
                String depLibrariesID = "library-deps-" + primaryLibraryName;
                Object bundle = BundleUtils.bundleArtifacts(depLibrariesID, new ArrayList<IBaseResource>(depLibraries.values()), fhirContext, addBundleTimestamp, this.getIdentifiers());
                IOUtils.writeBundle(bundle, bundleDestFilesPath, encoding, fhirContext);
            }
        }

        if (includePatientScenarios) {
            bundleMessage = TestCaseProcessor.bundleTestCaseFiles(igPath, getResourceTestGroupName(), primaryLibraryName, bundleDestFilesPath, fhirContext);
        }

        return bundleMessage;
    }


}