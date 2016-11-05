package me.gking2224.msplugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project


class MicroServiceGradlePlugin implements Plugin<Project> {

    private static final String NAME = "me.gking2224.msplugin"
    private String[] envs = ["dev", "test"]

    Project project
	void apply(Project project) {
        
        this.project = project
		project.extensions.create(MicroServicePluginExtension.KEY, MicroServicePluginExtension, project)
        
        configureDockerPublishTasks()
        
        configureInstanceDiscoveryTasks()
        configureLoadBalancerDiscoveryTasks()
        
        configureDeployTasks()
        configurePromoteTasks()
        configureRollbackTasks()
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
        
        project.tasks.postReleaseHook.dependsOn('buildDockerImage', 'pushImage', 'newDevTaskDefinition', 'updateAndTagNextDevInstance')
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
    
    def configureInstanceDiscoveryTasks() {
        envs.each {
            configureInstanceDiscoveryTasks(it)
        }
    }
    def configureInstanceDiscoveryTasks(String environment) {
        
        String cEnv = environment.capitalize()
        
        project.task("get${cEnv}Instances", type: me.gking2224.awsplugin.task.ec2.GetInstances) {
            env = environment
            version = ["current", "next", "previous", "none"]
            service = project.name
        }
        
    }
    
    def configureDeployTasks() {
        envs.each {
            configureDeployTasks(it)
        }
    }
    def configureDeployTasks(String environment) {
        
        String cEnv = environment.capitalize()
        
//        project.task("getNext${cEnv}Instances", type: me.gking2224.awsplugin.task.ec2.GetInstances) {
//            service = project.name
//            env = environment
//            version = ["none","next"]
//        }
        
        project.task("updateNext${cEnv}Service", type: me.gking2224.awsplugin.task.ecs.UpdateService, dependsOn:["get${cEnv}Instances"])
        project.tasks["updateNext${cEnv}Service"].doFirst {
            ext.instances = project.tasks["get${cEnv}Instances"].instances.none
            if (instances == null || instances.isEmpty()) instances = project.tasks["get${cEnv}Instances"].instances.next
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
        
        project.task("registerNext${cEnv}InNext", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["updateNext${cEnv}Service", "get${cEnv}TargetGroups"])
        project.tasks["registerNext${cEnv}InNext"].doFirst {
            instanceIds = project.tasks["updateNext${cEnv}Service"].instances.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as next")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.next?.targetGroupArn
        }
        project.tasks["registerNext${cEnv}InNext"].mustRunAfter "tagNext${cEnv}Instance"
        
        project.task("tagNext${cEnv}Instance", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:["get${cEnv}Instances"])
        project.tasks["tagNext${cEnv}Instance"].doFirst {
            def instances = project.tasks["get${cEnv}Instances"].instances.none
            if (instances == null || instances.isEmpty()) instances = project.tasks["get${cEnv}Instances"].instances.next
            instanceId = instances.collect {it.instanceId}
            tagKey = "version"
            tagValue = "next"
        }
        project.task("updateAndTagNext${cEnv}Instance", dependsOn:["updateNext${cEnv}Service", "tagNext${cEnv}Instance", "registerNext${cEnv}InNext"])
    }
    
    def configureLoadBalancerDiscoveryTasks() {
        envs.each {
            configureLoadBalancerDiscoveryTasks(it)
        }
    }
    def configureLoadBalancerDiscoveryTasks(String environment) {
        
        String cEnv = environment.capitalize()
        
        project.task("get${cEnv}TargetGroups", type: me.gking2224.awsplugin.task.elb.GetTargetGroups) {
            env = "dev"
            version = ["current", "next", "previous", "none"]
            service = "securityms"
        }
    }
    
    def configurePromoteTasks() {
        envs.each {
            configurePromoteTasks(it)
        }
    }
    def configurePromoteTasks(String environment) {
        
        String cEnv = environment.capitalize()
        
        project.task("registerNext${cEnv}InCurrent", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerNext${cEnv}InCurrent"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.next?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as next")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.current?.targetGroupArn
        }
        project.task("tagNext${cEnv}AsCurrent", type: me.gking2224.awsplugin.task.ec2.TagInstance)
        project.tasks["tagNext${cEnv}AsCurrent"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.next?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "current"
        }
        project.tasks["tagNext${cEnv}AsCurrent"].mustRunAfter "registerNext${cEnv}InCurrent"
        project.task("moveNext${cEnv}ToCurrent", dependsOn:["registerNext${cEnv}InCurrent", "tagNext${cEnv}AsCurrent"])
        
        
        project.task("registerCurrent${cEnv}InPrevious", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerCurrent${cEnv}InPrevious"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as current")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.previous?.targetGroupArn
        }
        project.task("deRegisterCurrent${cEnv}FromCurrent", type: me.gking2224.awsplugin.task.elb.DeRegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["deRegisterCurrent${cEnv}FromCurrent"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.current?.targetGroupArn
        }
        project.tasks["deRegisterCurrent${cEnv}FromCurrent"].mustRunAfter "registerCurrent${cEnv}InPrevious"
        project.task("tagCurrent${cEnv}AsPrevious", type: me.gking2224.awsplugin.task.ec2.TagInstance)
        project.tasks["tagCurrent${cEnv}AsPrevious"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "previous"
        }
        project.tasks["tagCurrent${cEnv}AsPrevious"].mustRunAfter "registerCurrent${cEnv}InPrevious", "deRegisterCurrent${cEnv}FromCurrent"
        project.task("moveCurrent${cEnv}ToPrevious", dependsOn:["registerCurrent${cEnv}InPrevious", "deRegisterCurrent${cEnv}FromCurrent", "tagCurrent${cEnv}AsPrevious"])
        
        
        project.task("deRegisterPrevious${cEnv}FromPrevious", type: me.gking2224.awsplugin.task.elb.DeRegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["deRegisterPrevious${cEnv}FromPrevious"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.previous?.targetGroupArn
        }
        
        project.task("deRegisterNext${cEnv}FromNext", type: me.gking2224.awsplugin.task.elb.DeRegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["deRegisterNext${cEnv}FromNext"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.previous?.next {it.instanceId}
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.next?.targetGroupArn
        }
        project.task("tagPrevious${cEnv}AsNone", type: me.gking2224.awsplugin.task.ec2.TagInstance)
        project.tasks["tagPrevious${cEnv}AsNone"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "none"
        }
        project.tasks["tagPrevious${cEnv}AsNone"].mustRunAfter "deRegisterPrevious${cEnv}FromPrevious"
        project.task("losePrevious${cEnv}", dependsOn:["deRegisterPrevious${cEnv}FromPrevious", "tagPrevious${cEnv}AsNone"])
        
        
        project.task("promoteNext${cEnv}", group: "Deployment", dependsOn: ["moveNext${cEnv}ToCurrent", "moveCurrent${cEnv}ToPrevious", "losePrevious${cEnv}", "deRegisterNext${cEnv}FromNext"])
        project.tasks["moveCurrent${cEnv}ToPrevious"].mustRunAfter "moveNext${cEnv}ToCurrent"
        project.tasks["deRegisterNext${cEnv}FromNext"].mustRunAfter "moveNext${cEnv}ToCurrent"
        project.tasks["losePrevious${cEnv}"].mustRunAfter "moveCurrent${cEnv}ToPrevious"
        
    }
    
    def configureRollbackTasks() {
        envs.each {
            configureRollbackTasks(it)
        }
    }
    def configureRollbackTasks(String environment) {
        
        String cEnv = environment.capitalize()
        
        project.task("registerPrevious${cEnv}InCurrent", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerPrevious${cEnv}InCurrent"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as previous")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.current?.targetGroupArn
        }
        project.tasks["deRegisterPrevious${cEnv}FromPrevious"].mustRunAfter "registerPrevious${cEnv}InCurrent"
        project.task("tagPrevious${cEnv}AsCurrent", type: me.gking2224.awsplugin.task.ec2.TagInstance)
        project.tasks["tagPrevious${cEnv}AsCurrent"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "current"
        }
        project.tasks["tagPrevious${cEnv}AsCurrent"].mustRunAfter "registerPrevious${cEnv}InCurrent", "deRegisterPrevious${cEnv}FromPrevious"
        project.task("movePrevious${cEnv}ToCurrent", dependsOn:["registerPrevious${cEnv}InCurrent", "deRegisterPrevious${cEnv}FromPrevious", "tagPrevious${cEnv}AsCurrent"])
        
        project.task("registerCurrent${cEnv}InNext", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerCurrent${cEnv}InNext"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as current")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.next?.targetGroupArn
        }
        project.tasks["deRegisterCurrent${cEnv}FromCurrent"].mustRunAfter "registerCurrent${cEnv}InNext"
        project.task("tagCurrent${cEnv}AsNext", type: me.gking2224.awsplugin.task.ec2.TagInstance)
        project.tasks["tagCurrent${cEnv}AsNext"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "next"
        }
        project.tasks["tagCurrent${cEnv}AsNext"].mustRunAfter "registerCurrent${cEnv}InPrevious", "deRegisterCurrent${cEnv}FromCurrent"
        project.task("moveCurrent${cEnv}ToNext", dependsOn:["registerCurrent${cEnv}InNext", "deRegisterCurrent${cEnv}FromCurrent", "tagCurrent${cEnv}AsNext"])
        
        
        project.task("rollback${cEnv}", group: "Deployment", dependsOn: ["movePrevious${cEnv}ToCurrent", "moveCurrent${cEnv}ToNext"])
        project.tasks["moveCurrent${cEnv}ToNext"].mustRunAfter "movePrevious${cEnv}ToCurrent"
        
    }
    
}

