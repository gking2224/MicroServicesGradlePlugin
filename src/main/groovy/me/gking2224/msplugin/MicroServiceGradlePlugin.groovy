package me.gking2224.msplugin

import java.util.logging.Logger;

import org.gradle.api.Plugin
import org.gradle.api.Project


class MicroServiceGradlePlugin implements Plugin<Project> {

    private static final String NAME = "me.gking2224.msplugin"

    Project project
	void apply(Project project) {
        
        this.project = project
		project.extensions.create(MicroServicePluginExtension.KEY, MicroServicePluginExtension, project)
        
        configureDockerPublishTasks()
	}
    
    def configureDockerPublishTasks() {
        
        
        project.task("ecrGetLogin", type: me.gking2224.awsplugin.task.ecr.GetLogin) {
            registryId = project.dockerEcrRegistryId
        }
        
        project.tasks.ecrGetLogin << {
            project.dockerplugin {
                registryUsername = project.ecrUsername
                registryPassword = project.ecrPassword
                registryUrl = project.ecrRepository
            }
        }
        
        project.task("dockerLogin", type: me.gking2224.dockerplugin.task.DockerLogin)
        project.tasks.dockerLogin.dependsOn 'ecrGetLogin'
        
        project.task("buildDockerImage", type: se.transmode.gradle.plugins.docker.DockerTask) {
            dockerfile = new File(project.projectDir, 'Dockerfile')
            applicationName = project.name
            tag = "${project.group}/${project.name}"
            tagVersion = project.version
            addFile new File("build/libs/${project.name}-${project.preReleaseVersion}-boot.jar"), "\$WORK_DIR/service.jar"
            addFile new File("logback.xml"), "\$WORK_DIR"
            hostUrl = { project.ecrRepository }
            apiUsername = { project.ecrUsername }
            apiPassword = { project.ecrPassword }
            apiEmail = "none"
        }
        project.tasks.buildDockerImage << {
            logger.info("Built $tag")
        }
        project.tasks.buildDockerImage.dependsOn 'dockerLogin'
        project.task("tagImageForRemote", type: me.gking2224.dockerplugin.task.TagImage) {
            registry = { project.dockerRepositoryName + "/" + project.group + "/" + project.name }
            tag = {project.version}
            imageId = {project.tasks.buildDockerImage.tag}
        }
        project.tasks.tagImageForRemote.dependsOn 'ecrGetLogin'
        
        project.task("pushImage", type: me.gking2224.dockerplugin.task.PushImage) {
            imageId = project.dockerRepositoryName + "/" + project.group + "/" + project.name + ":" + project.version
        }
        project.tasks.pushImage.dependsOn 'tagImageForRemote'
        
        project.task("newTaskDefinition", type: me.gking2224.awsplugin.task.ecs.RegisterTaskDefinition) {
            family = project.name+"-dev"
            image = {project.tasks.pushImage.imageId}
        }
        project.tasks.newTaskDefinition.dependsOn 'pushImage'
        
        project.tasks.postReleaseHook.dependsOn('buildDockerImage', 'pushImage', 'newTaskDefinition')
        
        
    }
}

