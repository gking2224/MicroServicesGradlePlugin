package me.gking2224.msplugin

import java.util.logging.Logger;

import org.gradle.api.Plugin
import org.gradle.api.Project

import me.gking2224.buildtools.plugin.ProjectConfigurer;


class MicroServiceGradlePlugin implements Plugin<Project> {

    private static final String NAME = "me.gking2224.msplugin"
    private String[] envs = ["dev"]

    Project project
	void apply(Project project) {
        
        this.project = project
		project.extensions.create(MicroServicePluginExtension.KEY, MicroServicePluginExtension, project)
        
        configureDockerPublishTasks()
        configureDeployTasks()
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
        project.tasks.buildDockerImage.doFirst {
            tagVersion = project.version
            setEnvironment "VERSION", project.version
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
        
        project.task("pushImage", type: me.gking2224.dockerplugin.task.PushImage)
        project.tasks.pushImage.doFirst {
            imageId = project.dockerRepositoryName + "/" + project.group + "/" + project.name + ":" + project.version
        }
        project.tasks.pushImage.dependsOn 'tagImageForRemote'
        
        project.task("newTaskDefinitions")
        project.tasks["newTaskDefinitions"].dependsOn 'pushImage'
        configureNewTaskDefinitionTasks()
        
        project.tasks.postReleaseHook.dependsOn('buildDockerImage', 'pushImage', 'newTaskDefinitions', 'updateNextDevService')
    }
    
    def configureNewTaskDefinitionTasks() {
        envs.each {
            configureNewTaskDefinitionTasks(it)
        }
    }
    
    def configureNewTaskDefinitionTasks(String env) {
        String cEnv = env.capitalize()
        project.task("new${cEnv}TaskDefinition", type: me.gking2224.awsplugin.task.ecs.RegisterTaskDefinition) {
            family = "${project.name}-${env}"
        }
        project.tasks["new${cEnv}TaskDefinition"].doFirst {
            image = project.tasks.pushImage.imageId
        }
        project.tasks["new${cEnv}TaskDefinition"] << {
            project.ext["${env}TaskDefinitionName"] = taskDefinitionName
            project.ext["${env}TaskDefinitionArn"] = taskDefinitionArn
        }
        project.tasks["new${cEnv}TaskDefinition"].mustRunAfter project.tasks.pushImage
        project.tasks["newTaskDefinitions"].dependsOn "new${cEnv}TaskDefinition"
    }
    
    def configureDeployTasks() {
        envs.each {
            configureDeployTasks(it)
        }
    }
    def configureDeployTasks(String environment) {
        
        String cEnv = environment.capitalize()
        
        project.task("getNext${cEnv}Instances", type: me.gking2224.awsplugin.task.ec2.GetInstances) {
            service = project.name
            env = environment
            version = "next"
        }
        
        project.task("getNext${cEnv}ScalingGroup", type: me.gking2224.awsplugin.task.autoscaling.GetAutoScalingGroups, dependsOn:["getNext${cEnv}Instances"])
        project.tasks["getNext${cEnv}ScalingGroup"].doFirst {
            names = [] as Set
            project.tasks["getNext${cEnv}Instances"].instances.each {
                it.getTags().findAll {it.getKey() == 'aws:autoscaling:groupName' }.each {
                    def value = it.getValue()
                    assert (names.isEmpty() || (names.size() == 1 && names.iterator().next() == value))
                    names << it.getValue()
                }
            }
        }
        
        project.task("getNext${cEnv}Services", type: me.gking2224.awsplugin.task.ecs.ListServices, dependsOn:["getNext${cEnv}ScalingGroup"])
        project.tasks["getNext${cEnv}Services"].doFirst {
            project.tasks["getNextDevScalingGroup"].autoScalingGroups.each {
                clusterName = it.getLaunchConfigurationName()
            }
        }
        
        project.task("updateNext${cEnv}Service", type: me.gking2224.awsplugin.task.ecs.UpdateService, dependsOn:["getNext${cEnv}Services"])
        project.tasks["updateNext${cEnv}Service"].doFirst {
            clusterName = project.tasks["getNext${cEnv}Services"].clusterName
            service = project.tasks["getNext${cEnv}Services"].serviceArns[0]
            taskDefinitionArn = project["${environment}TaskDefinitionArn"]
        }
        
        project.tasks["updateNext${cEnv}Service"] << {
            println updatedService
        }
    }
}

