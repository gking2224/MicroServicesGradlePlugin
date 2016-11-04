package me.gking2224.msplugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project


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
            apiEmail = "none"
        }
        project.tasks.buildDockerImage.doFirst {
            tagVersion = project.version
            setEnvironment "VERSION", project.version
            hostUrl = project.ecrRepository
            apiUsername = project.ecrUsername
            apiPassword = project.ecrPassword
            logger.debug("buildDockerImage with hostUrl=$hostUrl; apiUsername=$apiUsername; apiPassword=$apiPassword; version=$tagVersion")
        }
        project.tasks.buildDockerImage << {
            logger.debug("Built $tag")
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
        
        project.tasks.postReleaseHook.dependsOn('buildDockerImage', 'pushImage', 'newTaskDefinitions', 'updateAndTagNextDevInstance')
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
            version = ["none","next"]
        }
        
        project.task("updateNext${cEnv}Service", type: me.gking2224.awsplugin.task.ecs.UpdateService, dependsOn:["getNext${cEnv}Instances"])
        project.tasks["updateNext${cEnv}Service"].doFirst {
            def instances = project.tasks["getNext${cEnv}Instances"].instances.none
            if (instances == null) instances = project.tasks["getNext${cEnv}Instances"].instances.next
            instances.each {
                it.getTags().find{it.getKey() == 'ecsCluster' }.each {
                    def ecsClusterTag = it.getValue()
                    if (clusterName != null && clusterName != ecsClusterTag) throw new GradleException("mismatching ecsCluster in $instances")
                    else clusterName = ecsClusterTag
                }
            }
            instances.each {
                it.getTags().find{it.getKey() == 'ecsServiceArn' }.each {
                    def ecsServiceArnTag = it.getValue()
                    if (service != null && service != ecsServiceArnTag) throw new GradleException("mismatching ecsServiceArn in $instances")
                    service = ecsServiceArnTag
                }
            }
            taskDefinitionArn = project["${environment}TaskDefinitionArn"]
        }
        
        project.task("tagNext${cEnv}Instance", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:["getNext${cEnv}Instances"])
        project.tasks["tagNext${cEnv}Instance"].doFirst {
            def instances = project.tasks["getNext${cEnv}Instances"].instances.none
            if (instances == null) instances = project.tasks["getNext${cEnv}Instances"].instances.next
            instanceId = instances.collect {it.instanceId}
            tagKey = "version"
            tagValue = "next"
        }
        project.task("updateAndTagNext${cEnv}Instance", dependsOn:["updateNext${cEnv}Service", "tagNext${cEnv}Instance"])
    }
}

