package com.github.mishaninss.reportportal;

import com.epam.reportportal.cucumber.AbstractReporter;
import com.epam.reportportal.cucumber.Utils;
import com.epam.reportportal.listeners.Statuses;
import com.epam.reportportal.restclient.endpoint.RestEndpoint;
import com.epam.reportportal.restclient.endpoint.exception.RestEndpointIOException;
import com.epam.reportportal.service.ReportPortalService;
import com.epam.ta.reportportal.ws.model.OperationCompletionRS;
import com.epam.ta.reportportal.ws.model.item.UpdateTestItemRQ;
import com.google.common.base.Supplier;
import gherkin.formatter.model.Examples;
import gherkin.formatter.model.Feature;
import gherkin.formatter.model.Scenario;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Extended Cucumber JVM client for Report Portal with support of parallel execution and retries
 * Created by Sergey_Mishanin on 3/13/17.
 */
public abstract class ExtendedAbstractReporter extends AbstractReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtendedAbstractReporter.class);

    private static final java.io.File LAUNCH_PROP_FILE = new java.io.File("./target/rp_launch.properties");
    private static final String LAUNCH_ID_PROPERTY_NAME = "launchId";
    private static final String FEATURE_ID_PROPERTY_NAME = "featureId";
    private static final String FORK_NUMBER_PROPERTY_NAME = "fork.number";
    private static final String CUCUMBER_RETRY_COUNT_PROPERTY_NAME = "cucumber.retry.count";
    private static final String RUN_CLASSES_PROPERTY_NAME = "run.classes";
    private static final String IN_PROGRESS_FILE_NAME = "./target/rp_launch_class_%d.inprogress";
    private static final String FEATURE_LOCK_FILE_NAME = "./target/feature_%s.lock";
    private static final String FEATURE_PROP_FILE_NAME = "./target/feature_%s.properties";
    static final String ROOT_SUITE_ID_PROPERTY_NAME = "rootSuiteId";

    private Scenario currentGherkinScenario;
    protected Properties launchProperties = new Properties();
    private Properties featureProperties = new Properties();
    protected int retryNumber = 0;
    protected int maxRetryCount = 0;

    protected static class ExtendedScenarioModel extends ScenarioModel {
        private Set<String> tags;
        private String description;

        ExtendedScenarioModel(String newId) {
            super(newId);
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Set<String> getTags() {
            return tags;
        }

        public void setTags(Set<String> tags) {
            this.tags = tags;
        }

        public void setStatus(String status){
            try {
                FieldUtils.writeField(this, "status", status, true);
            } catch (IllegalAccessException e) {
                LOGGER.debug("Could not set scenario status to " + status, e);
            }
        }
    }

    protected ExtendedAbstractReporter() {
        super();
        try {
            maxRetryCount = Integer.parseInt(System.getProperty(CUCUMBER_RETRY_COUNT_PROPERTY_NAME, "0"));
        } catch (NumberFormatException e){
            maxRetryCount = 0;
        }
    }

    private static int calculateRunClassesCount(){
        String runClasses = System.getProperty(RUN_CLASSES_PROPERTY_NAME);
        if (StringUtils.isNoneBlank(runClasses)) {
            String[] filters = runClasses.split("/");
            String fileFilterStr = filters[filters.length - 1].trim();
            java.io.File dir = new java.io.File("./target/test-classes");
            IOFileFilter fileFilter = new WildcardFileFilter(fileFilterStr);
            return FileUtils.listFiles(dir, fileFilter, TrueFileFilter.INSTANCE).size();
        } else {
            return 0;
        }
    }

    private static void createTestClassesMarkers(){
        int runClassesCount = calculateRunClassesCount();
        for (int i=1; i<=runClassesCount; i++){
            String filename = String.format(IN_PROGRESS_FILE_NAME, i);
            java.io.File file = new java.io.File(filename);
            try {
                FileUtils.write(file, "in progress");
            } catch (IOException ex){
                throw new RuntimeException("Could not create in progress file", ex);
            }
        }
    }

    private void readLaunchProperties(){
        if (LAUNCH_PROP_FILE.exists()){
            try {
                launchProperties = loadProperties(LAUNCH_PROP_FILE);
            } catch (IOException ex) {
                throw new RuntimeException("Could not load launch properties file", ex);
            }
        }
    }

    private void createLaunchPropFile(){
        if (!LAUNCH_PROP_FILE.exists()){
            try (FileOutputStream fos = new FileOutputStream(LAUNCH_PROP_FILE)){
                launchProperties.store(fos, null);
            } catch (IOException ex) {
                throw new RuntimeException("Could not save launch properties file", ex);
            }
        }
    }

    private void updateTestItem(String itemId, String description, Set<String> tags) throws IllegalAccessException, RestEndpointIOException {
        Supplier<ReportPortalService> reportPortalService = (Supplier<ReportPortalService>) FieldUtils.readStaticField(Utils.class, "reportPortalService", true);
        RestEndpoint endpoint = (RestEndpoint) FieldUtils.readField(reportPortalService.get(), "endpoint", true);
        String apiBase = (String) FieldUtils.readField(reportPortalService.get(), "apiBase", true);
        String project = (String) FieldUtils.readField(reportPortalService.get(), "project", true);
        UpdateTestItemRQ rq = new UpdateTestItemRQ();
        rq.setDescription(description);
        rq.setTags(tags);
        endpoint.put(apiBase + "/" + project + "/item/" + itemId + "/update", rq, OperationCompletionRS.class);
    }

    private static void closeAllFeatures(){
        Collection<java.io.File> featurePropertiesFiles = FileUtils.listFiles(new java.io.File("./target"), new String[]{"properties"}, false);
        for (java.io.File file: featurePropertiesFiles){
            if (file.getName().startsWith("feature_")){
                try {
                    Properties featureProperties = loadProperties(file);
                    String featureId = featureProperties.getProperty(FEATURE_ID_PROPERTY_NAME);
                    if (StringUtils.isNoneBlank(featureId)){
                        Utils.finishTestItem(featureId);
                    }
                } catch (IOException ex){
                    throw new RuntimeException("Could not load feature properties", ex);
                }
            }
        }
    }

    private static boolean waitForLaunchProperties(){
        if (!LAUNCH_PROP_FILE.exists()){
            Date start = new Date();
            while(!LAUNCH_PROP_FILE.exists() && (new Date().getTime()-start.getTime()<60000)){
                sleep(2000);
            }
            return LAUNCH_PROP_FILE.exists();
        } else {
            return true;
        }
    }

    private void createFeaturePropFile(Feature feature){
        String featureId = feature.getId();

        java.io.File featureLockFile = new java.io.File(String.format(FEATURE_LOCK_FILE_NAME, featureId));
        try {
            FileUtils.write(featureLockFile, "in progress");
        } catch (IOException ex) {
            throw new RuntimeException("Could create feature lock file", ex);
        }

        currentFeatureId = Utils.startNonLeafNode(currentLaunchId, getRootItemId(),
                Utils.buildStatementName(feature, null, ExtendedAbstractReporter.COLON_INFIX, null), currentFeatureUri, feature.getTags(),
                getFeatureTestItemType());

        featureProperties = new Properties();
        featureProperties.setProperty(FEATURE_ID_PROPERTY_NAME, currentFeatureId);
        java.io.File featurePropertiesFile = new java.io.File(String.format(FEATURE_PROP_FILE_NAME, featureId));
        try (FileOutputStream fos = new FileOutputStream(featurePropertiesFile)){
            featureProperties.store(fos, null);
        } catch (IOException ex) {
            throw new RuntimeException("Could save feature properties file", ex);
        }

        FileUtils.deleteQuietly(featureLockFile);
    }

    private boolean readFeatureProperties(String featureId){
        java.io.File featurePropertiesFile = new java.io.File(String.format(FEATURE_PROP_FILE_NAME, featureId));
        if (featurePropertiesFile.exists()){
            try {
                featureProperties = loadProperties(featurePropertiesFile);
                return true;
            } catch (IOException ex) {
                LOGGER.debug("Could not load feature properties", ex);
                return false;
            }
        } else {
            return false;
        }
    }

    private static Properties loadProperties(java.io.File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)){
            Properties properties = new Properties();
            properties.load(fis);
            return properties;
        }
    }

    private boolean loadFeatureProperties(String featureId)
    {
        java.io.File featurePropertiesFile = new java.io.File(String.format(FEATURE_PROP_FILE_NAME, featureId));
        if (featurePropertiesFile.exists()){
            return readFeatureProperties(featureId);
        } else {
            java.io.File featureInProgressFile = new java.io.File(String.format(FEATURE_LOCK_FILE_NAME, featureId));
            if (featureInProgressFile.exists()) {
                Date start = new Date();
                while (featureInProgressFile.exists() && (new Date().getTime() - start.getTime() < 60000)) {
                    sleep(2000);
                }
                return readFeatureProperties(featureId);
            } else {
                return false;
            }
        }
    }

    private String formatExampleString(List<String> cells){
        String value = " " + Arrays.toString(cells.toArray());
        if (value.length() > 156){
            value = value.substring(0, 152) + "...]";
        }
        return value;
    }

    private static void sleep(int timeout){
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ex) {
            LOGGER.debug("Ignored InterruptedException exception", ex);
        }
    }

    protected abstract void setRootItemId(String rootItemId);

    //****************************************************************************
    // Overwrote original client methods
    //****************************************************************************

    /**
     * Start Cucumber feature
     *
     * @param feature - current Cucumber feature
     */
    @Override
    protected void beforeFeature(Feature feature) {
        if (loadFeatureProperties(feature.getId())){
            currentFeatureId = featureProperties.getProperty(FEATURE_ID_PROPERTY_NAME);
        } else {
            createFeaturePropFile(feature);
        }
    }

    /**
     * Finish Cucumber feature
     */
    @Override
    protected void afterFeature() {
        currentFeatureId = null;
    }

    /**
     * Start Cucumber scenario
     *
     * @param scenario - current Cucumber scenario
     * @param outlineIteration
     *            - suffix to append to scenario name, can be null
     */
    @Override
    protected void beforeScenario(Scenario scenario, String outlineIteration) {
        String id = Utils.startNonLeafNode(currentLaunchId, currentFeatureId,
                Utils.buildStatementName(scenario, null, ExtendedAbstractReporter.COLON_INFIX, outlineIteration),
                currentFeatureUri + ":" + scenario.getLine(), scenario.getTags(), getScenarioTestItemType());
        currentScenario = new ExtendedScenarioModel(id);
        ((ExtendedScenarioModel)currentScenario).setTags(Utils.extractTags(scenario.getTags()));
        ((ExtendedScenarioModel)currentScenario).setDescription(currentFeatureUri + ":" + scenario.getLine());
    }

    /**
     * Finish Cucumber scenario
     */
    @Override
    protected void afterScenario() {
        if (Statuses.FAILED.equalsIgnoreCase(currentScenario.getStatus()) && retryNumber < maxRetryCount){
            ((ExtendedScenarioModel)currentScenario).setStatus(Statuses.PASSED);
            return;
        }

        if (currentScenario.getStatus().equals(Statuses.PASSED) && retryNumber > 0) {
            Set<String> tags = ((ExtendedScenarioModel)currentScenario).getTags();
            tags.add("@Retry");
            try {
                updateTestItem(currentScenario.getId(), ((ExtendedScenarioModel)currentScenario).getDescription(), tags);
            } catch (Exception ex) {
                LOGGER.debug("Unable to update test item", ex);
            }
        }

        currentGherkinScenario = null;
        retryNumber = 0;
        super.afterScenario();
    }

    //****************************************************************************
    // Cucumber interfaces implementations
    //****************************************************************************

    @Override
    public void feature(Feature feature) {
        int forkNumber = 1;
        try {
            forkNumber = Integer.parseInt(System.getProperty(FORK_NUMBER_PROPERTY_NAME).trim());
        } catch (NumberFormatException ex) {
            LOGGER.debug("Incorrect fork number value", ex);
        }

        if (forkNumber != 1 && !waitForLaunchProperties()){
            throw new RuntimeException("Launch properties file hasn't been created within a minute");
        }

        readLaunchProperties();
        currentLaunchId = launchProperties.getProperty(LAUNCH_ID_PROPERTY_NAME);

        if (currentLaunchId == null) {
            beforeLaunch();
            launchProperties.setProperty(LAUNCH_ID_PROPERTY_NAME, currentLaunchId);
            startRootItem();
            createTestClassesMarkers();
            createLaunchPropFile();
        } else {
            setRootItemId(launchProperties.getProperty(ROOT_SUITE_ID_PROPERTY_NAME));
        }

        beforeFeature(feature);
    }

    @Override
    public void examples(Examples examples) {
        int num = examples.getRows().size();
        // examples always have headers; therefore up to num - 1
        Queue<String> outlineIterations = new ArrayDeque<>();
        for (int i = 1; i < num; i++) {
            outlineIterations.add(formatExampleString(examples.getRows().get(i).getCells()));
        }
        try {
            FieldUtils.writeField(this, "outlineIterations", outlineIterations, true);
        } catch (IllegalAccessException e) {
            LOGGER.debug("Could not write outlineIterations", e);
        }
    }

    @Override
    public void startOfScenarioLifeCycle(Scenario scenario) {
        if (currentGherkinScenario != null && currentGherkinScenario.getId().equals(scenario.getId())){
            retryNumber++;
            Utils.sendLog(getLogDestination(), "", "INFO", null);
            Utils.sendLog(getLogDestination(),  "------------------------- RETRY " + retryNumber + " -------------------------", "INFO", null);
            Utils.sendLog(getLogDestination(), "", "INFO", null);
            beforeHooks(true);
            return;
        }

        currentGherkinScenario = scenario;
        super.startOfScenarioLifeCycle(scenario);
    }

    @Override
    public void close() {
        if (currentLaunchId != null) {
            Collection<java.io.File> inProgressFiles = FileUtils.listFiles(new java.io.File("./target"), new String[]{"inprogress"}, false);
            if (!inProgressFiles.isEmpty()){
                Iterator<java.io.File> iterator = inProgressFiles.iterator();
                int i = 0;
                boolean deleted;
                do {
                    java.io.File file = iterator.next();
                    deleted = FileUtils.deleteQuietly(file);
                    i++;
                } while (!deleted && i<5);
            }

            inProgressFiles = FileUtils.listFiles(new java.io.File("./target"), new String[]{"inprogress"}, false);
            if (inProgressFiles.isEmpty()) {
                closeAllFeatures();
                finishRootItem();
                afterLaunch();
            }
        }
    }

    @Override
    public void eof() {
        afterFeature();
    }
}