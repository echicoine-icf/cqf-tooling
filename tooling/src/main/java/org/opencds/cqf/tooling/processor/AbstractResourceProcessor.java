package org.opencds.cqf.tooling.processor;

import ca.uhn.fhir.context.FhirContext;
import org.apache.commons.io.FilenameUtils;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.opencds.cqf.tooling.common.ThreadUtils;
import org.opencds.cqf.tooling.library.LibraryProcessor;
import org.opencds.cqf.tooling.utilities.BundleUtils;
import org.opencds.cqf.tooling.utilities.IOUtils;
import org.opencds.cqf.tooling.utilities.LogUtils;
import org.opencds.cqf.tooling.utilities.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class AbstractResourceProcessor extends BaseProcessor {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    protected final String TYPE_QUESTIONNAIRE = "Questionnaire";
    protected final String TYPE_PLAN_DEFINITION = "PlanDefinition";
    protected final String TYPE_MEASURE = "Measure";
    private List<Object> identifiers;
    private CDSHooksProcessor cdsHooksProcessor;
    private LibraryProcessor libraryProcessor;

    protected void setLibraryProcessor(LibraryProcessor libraryProcessor) {
        this.libraryProcessor = libraryProcessor;
    }

    protected void setCDSHooksProcessor(CDSHooksProcessor cdsHooksProcessor) {
        this.cdsHooksProcessor = cdsHooksProcessor;
    }

    protected List<Object> getIdentifiers() {
        if (identifiers == null) {
            identifiers = new CopyOnWriteArrayList<>();
        }
        return identifiers;
    }

    public void bundleResources(ArrayList<String> refreshedLibraryNames, String igPath, List<String> binaryPaths, Boolean includeDependencies,
                                Boolean includeTerminology, Boolean includePatientScenarios, Boolean includeVersion, Boolean addBundleTimestamp,
                                FhirContext fhirContext, String fhirUri, IOUtils.Encoding encoding) {

        Map<String, IBaseResource> resourcesMap = getResources(fhirContext);
        List<String> bundledResources = new CopyOnWriteArrayList<>();

        //for keeping track of progress:
        List<String> processedResources = new CopyOnWriteArrayList<>();

        //for keeping track of failed reasons:
        Map<String, String> failedExceptionMessages = new ConcurrentHashMap<>();

        int totalResources = resourcesMap.size();

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
                    if (!resourceId.isEmpty()) {
                        failedExceptionMessages.put(resourceId, "Path is null for " + resourceId);
                    }
                    continue;
                }

                final String resourceSourcePath = getSourcePath(fhirContext, resourceEntry);
                tasks.add(() -> {
                    //check if resourceSourcePath has been processed before:
                    if (processedResources.contains(resourceSourcePath)) {
                        LogUtils.info(getResourceProcessorType() + " processed already: " + resourceSourcePath);
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
                            //ValueSetsProcessor.bundleValueSets modified to throw Exception so we can collect it and report it in the final "report"
                            boolean result = ValueSetsProcessor.bundleValueSets(cqlLibrarySourcePath, igPath, fhirContext, resources, encoding, includeDependencies, includeVersion);
//                            if (shouldPersist && !result) {
//                                LogUtils.info(getResourceProcessorType() + " will not be bundled because ValueSet bundling failed: " + resourceSourcePath  + "\n");
//                            }
                            shouldPersist = shouldPersist & result;
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
                            persistBundle(igPath, bundleDestPath, resourceName, encoding, fhirContext, new ArrayList<IBaseResource>(resources.values()), fhirUri, addBundleTimestamp);
                            String possibleBundleTestMessage = bundleFiles(igPath, bundleDestPath, resourceName, binaryPaths, resourceSourcePath, primaryLibrarySourcePath, fhirContext, encoding, includeTerminology, includeDependencies, includePatientScenarios, includeVersion, addBundleTimestamp);

                            if (cdsHooksProcessor != null){
                                List<String> activityDefinitionPaths = CDSHooksProcessor.bundleActivityDefinitions(resourceSourcePath, fhirContext, resources, encoding, includeVersion, shouldPersist);
                                cdsHooksProcessor.addActivityDefinitionFilesToBundle(igPath, bundleDestPath, activityDefinitionPaths, fhirContext, encoding);
                            }

                            if (!possibleBundleTestMessage.isEmpty()) {
                                bundleTestFileStringBuilder.append(possibleBundleTestMessage);
                            }

                            bundledResources.add(resourceSourcePath);
                        }


                    } catch (Exception e) {
                        LogUtils.putException(resourceName, e);
                        failedExceptionMessages.put(resourceSourcePath, e.getMessage());
                    }


                    processedResources.add(resourceSourcePath);

                    synchronized (this) {
                        double percentage = (double) processedResources.size() / totalResources * 100;
                        LogUtils.info("Bundle " + getResourceProcessorType() + "s Progress: " + String.format("%.2f%%", percentage) + " PROCESSED: " + resourceEntry.getKey());
                    }
                    //task requires return statement
                    return null;
                });

            }//end for loop

            ThreadUtils.executeTasks(tasks);

            //Test file information:
            String bundleTestFileMessage = bundleTestFileStringBuilder.toString();
            if (!bundleTestFileMessage.isEmpty()) {
                LogUtils.info(bundleTestFileMessage);
            }


        } catch (Exception e) {
            LogUtils.putException("bundleResources: " + getResourceProcessorType(), e);
        }


        StringBuilder message = new StringBuilder("\r\n" + bundledResources.size() + " " + getResourceProcessorType() + "s successfully bundled:");
        for (String bundledResource : bundledResources) {
            message.append("\r\n     ").append(bundledResource).append(" BUNDLED");
        }

        List<String> resourcePathLibraryNames = new ArrayList<>(getPaths(fhirContext));

        //gather which resources didn't make it
        ArrayList<String> failedResources = new ArrayList<>(resourcePathLibraryNames);

        resourcePathLibraryNames.removeAll(bundledResources);
        resourcePathLibraryNames.retainAll(refreshedLibraryNames);
        message.append("\r\n").append(resourcePathLibraryNames.size()).append(" ").append(getResourceProcessorType()).append("s refreshed, but not bundled (due to issues):");
        for (String notBundled : resourcePathLibraryNames) {
            message.append("\r\n     ").append(notBundled).append(" REFRESHED");
        }


        //attempt to give some kind of informational message:
        failedResources.removeAll(bundledResources);
        failedResources.removeAll(resourcePathLibraryNames);
        message.append("\r\n").append(failedResources.size()).append(" ").append(getResourceProcessorType()).append("s failed refresh:");
        for (String failed : failedResources) {
            if (failedExceptionMessages.containsKey(failed)) {
                message.append("\r\n     ").append(failed).append(" FAILED: ").append(failedExceptionMessages.get(failed));
            } else {
                message.append("\r\n     ").append(failed).append(" FAILED");
            }
        }

        LogUtils.info(message.toString());
    }

    private String getResourcePrefix() {
        return getResourceProcessorType().toLowerCase() + "-";
    }

    protected abstract Set<String> getPaths(FhirContext fhirContext);

    private String bundleFiles(String igPath, String bundleDestPath, String libraryName, List<String> binaryPaths, String resourceFocusSourcePath,
                               String librarySourcePath, FhirContext fhirContext, IOUtils.Encoding encoding, Boolean includeTerminology, Boolean includeDependencies, Boolean includePatientScenarios,
                               Boolean includeVersion, Boolean addBundleTimestamp) {
        String bundleMessage = "";

        String bundleDestFilesPath = FilenameUtils.concat(bundleDestPath, libraryName + "-" + IGBundleProcessor.bundleFilesPathElement);
        IOUtils.initializeDirectory(bundleDestFilesPath);

        IOUtils.copyFile(resourceFocusSourcePath, FilenameUtils.concat(bundleDestFilesPath, FilenameUtils.getName(resourceFocusSourcePath)));
        IOUtils.copyFile(librarySourcePath, FilenameUtils.concat(bundleDestFilesPath, FilenameUtils.getName(librarySourcePath)));

        String cqlFileName = IOUtils.formatFileName(FilenameUtils.getBaseName(librarySourcePath), IOUtils.Encoding.CQL, fhirContext);
        if (cqlFileName.toLowerCase().startsWith("library-")) {
            cqlFileName = cqlFileName.substring(8);
        }
        String cqlLibrarySourcePath = IOUtils.getCqlLibrarySourcePath(libraryName, cqlFileName, binaryPaths);
        String cqlDestPath = FilenameUtils.concat(bundleDestFilesPath, cqlFileName);
        IOUtils.copyFile(cqlLibrarySourcePath, cqlDestPath);

        if (includeTerminology) {
            try {
                Map<String, IBaseResource> valueSets = ResourceUtils.getDepValueSetResources(cqlLibrarySourcePath, igPath, fhirContext, includeDependencies, includeVersion);
                if (!valueSets.isEmpty()) {
                    Object bundle = BundleUtils.bundleArtifacts(ValueSetsProcessor.getId(libraryName), new ArrayList<IBaseResource>(valueSets.values()), fhirContext, addBundleTimestamp, this.getIdentifiers());
                    IOUtils.writeBundle(bundle, bundleDestFilesPath, encoding, fhirContext);
                }
            } catch (Exception e) {
                LogUtils.putException(libraryName, e.getMessage());
            }
        }

        if (includeDependencies) {
            Map<String, IBaseResource> depLibraries = ResourceUtils.getDepLibraryResources(librarySourcePath, fhirContext, encoding, includeVersion, logger);
            if (!depLibraries.isEmpty()) {
                String depLibrariesID = "library-deps-" + libraryName;
                Object bundle = BundleUtils.bundleArtifacts(depLibrariesID, new ArrayList<IBaseResource>(depLibraries.values()), fhirContext, addBundleTimestamp, this.getIdentifiers());
                IOUtils.writeBundle(bundle, bundleDestFilesPath, encoding, fhirContext);
            }
        }

        if (includePatientScenarios) {
            bundleMessage = TestCaseProcessor.bundleTestCaseFiles(igPath, getResourceTestGroupName(), libraryName, bundleDestFilesPath, fhirContext);
        }

        return bundleMessage;
    }


    private void persistBundle(String igPath, String bundleDestPath, String libraryName, IOUtils.Encoding encoding, FhirContext fhirContext, List<IBaseResource> resources, String fhirUri, Boolean addBundleTimestamp) {
        //Check for test files in bundleDestPath + "-files", loop through if exists,
        // find all files that start with "tests-", post to fhir server following same folder structure:
        persistTestFiles(bundleDestPath, libraryName, encoding, fhirContext, fhirUri);

        IOUtils.initializeDirectory(bundleDestPath);
        Object bundle = BundleUtils.bundleArtifacts(libraryName, resources, fhirContext, addBundleTimestamp, this.getIdentifiers());
        IOUtils.writeBundle(bundle, bundleDestPath, encoding, fhirContext);

        BundleUtils.postBundle(encoding, fhirContext, fhirUri, (IBaseResource) bundle);
    }

    protected abstract void persistTestFiles(String bundleDestPath, String libraryName, IOUtils.Encoding encoding, FhirContext fhirContext, String fhirUri);

    protected abstract String getSourcePath(FhirContext fhirContext, Map.Entry<String, IBaseResource> resourceEntry);

    /**
     * Handled by the child class in gathering specific IBaseResources
     *
     * @return
     */
    protected abstract Map<String, IBaseResource> getResources(FhirContext fhirContext);

    protected abstract String getResourceProcessorType();

    private String getResourceTestGroupName() {
        return getResourceProcessorType().toLowerCase();
    }


}