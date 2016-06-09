package com.wikia.gradle.marathon

import com.wikia.gradle.marathon.common.Constraints
import com.wikia.gradle.marathon.common.Environment
import com.wikia.gradle.marathon.common.Healthchecks
import com.wikia.gradle.marathon.common.Resources
import com.wikia.gradle.marathon.common.Stage
import mesosphere.marathon.client.Marathon
import mesosphere.marathon.client.MarathonClient
import mesosphere.marathon.client.model.v2.App
import mesosphere.marathon.client.model.v2.Container
import mesosphere.marathon.client.model.v2.HealthCheck
import mesosphere.marathon.client.utils.MarathonException
import org.apache.commons.lang.exception.ExceptionUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException

class MarathonTask extends DefaultTask {
    public static final String FORCE_UPDATE = 'marathon.forceUpdate'
    public static final String PRESERVE_INSTANCE_ALLOCATION = 'marathon.preserveInstanceAllocation'
    Stage stage

    App prepareAppDescription() {
        return new AppFactory(this.stage, project).create()
    }

    def Optional<App> attemptGetExistingApp(Marathon client, String appId) {
        def app
        try {
            app = Optional.<App> of(client.getApp(appId).getApp())
        } catch (MarathonException ex) {
            getLogger().info("exception encountered while fetching data for app", ex)
            app = Optional.<App> empty()
        }
        return app
    }

    static def mergeAppDescriptions(Optional<App> existingApp, App appDescription,
                                    Project project) {

        if (existingApp.isPresent()){
            if (project.hasProperty(PRESERVE_INSTANCE_ALLOCATION) &&
                project.property(PRESERVE_INSTANCE_ALLOCATION).toString().toBoolean()) {
                appDescription.instances = existingApp.get().instances;
            }
        }
        return appDescription
    }

    @TaskAction
    def setupApp() {
        this.stage = stage.validate()
        Marathon marathon = MarathonClient.getInstance(this.stage.resolve(com.wikia.gradle.marathon.common.Marathon).getUrl())
        def appDescription = prepareAppDescription()

        def existingApp = attemptGetExistingApp(marathon, appDescription.getId())
        if (existingApp.isPresent()) {
            appDescription = mergeAppDescriptions(existingApp, appDescription, project)
            Boolean force = Optional.ofNullable(project.getProperties().get(FORCE_UPDATE))
                    .map({x -> x.toString().toBoolean()})
                    .orElse(false)

            try {
                marathon.updateApp(appDescription.getId(), appDescription, force)
            } catch (Exception e) {
                handleUpdateException(e)
            }
        } else {
            marathon.createApp(appDescription)
        }
    }

    def handleUpdateException(Exception e) {
        /**
         * the root cause of an exception should be a MarathonException, which sadly
         * does not expose its HTTP status directly
         */
        def cause = ExceptionUtils.getRootCause(e);

        switch (cause.getMessage().trim()) {
            case "Conflict (http status: 409)":
                def message = "cannot deploy, existing deployment already in progress"
                throw new RuntimeException(message)
            default:
                throw new TaskExecutionException(this, cause)
        }
    }
}