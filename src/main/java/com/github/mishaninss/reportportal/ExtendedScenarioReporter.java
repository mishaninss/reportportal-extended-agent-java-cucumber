package com.github.mishaninss.reportportal;

import com.epam.reportportal.cucumber.Utils;
import com.epam.reportportal.listeners.ReportPortalListenerContext;
import com.epam.reportportal.listeners.Statuses;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import gherkin.formatter.model.Match;
import gherkin.formatter.model.Result;
import gherkin.formatter.model.Scenario;
import gherkin.formatter.model.Step;

import java.util.Calendar;

/**
 *
 * Created by Sergey_Mishanin on 3/13/17.
 */
public class ExtendedScenarioReporter extends ExtendedAbstractReporter {
    private static final String SEPARATOR = "-------------------------";

    private String rootSuiteId;

    public ExtendedScenarioReporter() {
        super();
        rootSuiteId = null;
    }

    @Override
    protected void beforeScenario(Scenario scenario, String outlineIteration) {
        super.beforeScenario(scenario, outlineIteration);
        ReportPortalListenerContext.setRunningNowItemId(currentScenario.getId());
    }

    @Override
    protected void afterScenario() {
        super.afterScenario();
        if (!Statuses.FAILED.equalsIgnoreCase(currentScenario.getStatus()) || retryNumber >= maxRetryCount) {
            ReportPortalListenerContext.setRunningNowItemId(null);
        }
    }

    @Override
    protected void beforeStep(Step step) {
        String decoratedStepName = decorateMessage(Utils.buildStatementName(step, stepPrefix, " ", null));
        String multilineArg = Utils.buildMultilineArgument(step);
        Utils.sendLog(getLogDestination(), decoratedStepName + multilineArg, "INFO", null);
    }

    @Override
    protected void afterStep(Result result) {
        if (!result.getStatus().equalsIgnoreCase(Statuses.PASSED)) {
            reportResult(result, decorateMessage("STEP " + result.getStatus().toUpperCase()));
        }
        String comments = Utils.buildIssueComments(result);
        if (comments != null) {
            currentScenario.appendIssue(comments);
        }
    }

    @Override
    protected void beforeHooks(Boolean isBefore) {
        String message = "------------------------- ";
        message += isBefore ? "BEFORE" : "AFTER";
        message += " HOOKS STARTED -------------------------";
        Utils.sendLog(getLogDestination(), message, "INFO", null);
    }

    @Override
    protected void afterHooks(Boolean isBefore) {
        String message = "------------------------- ";
        message += isBefore ? "BEFORE" : "AFTER";
        message += " HOOKS FINISHED -------------------------";
        Utils.sendLog(getLogDestination(), message, "INFO", null);
    }

    @Override
    protected void hookFinished(Match match, Result result, Boolean isBefore) {
        reportResult(result, null);
    }

    @Override
    protected String getLogDestination() {
        return currentScenario == null ? null : currentScenario.getId();
    }

    @Override
    protected String getFeatureTestItemType() {
        return "TEST";
    }

    @Override
    protected String getScenarioTestItemType() {
        return "STEP";
    }

    @Override
    protected void startRootItem() {
        StartTestItemRQ rq = new StartTestItemRQ();
        rq.setName("Root Test Suite");
        rq.setLaunchId(currentLaunchId);
        rq.setStartTime(Calendar.getInstance().getTime());
        rq.setType("SUITE");
        rootSuiteId = Utils.startTestItem(rq, null);
        launchProperties.setProperty(ROOT_SUITE_ID_PROPERTY_NAME, rootSuiteId);
    }

    @Override
    protected void finishRootItem() {
        Utils.finishTestItem(rootSuiteId);
        rootSuiteId = null;
    }

    @Override
    protected String getRootItemId() {
        return rootSuiteId;
    }

    /**
     * Add separators to log item to distinguish from real log messages
     *
     * @param message
     *            to decorate
     * @return decorated message
     */
    private String decorateMessage(String message) {
        return ExtendedScenarioReporter.SEPARATOR + message + ExtendedScenarioReporter.SEPARATOR;
    }

    @Override
    protected void setRootItemId(String rootItemId) {
        rootSuiteId = rootItemId;
    }
}
