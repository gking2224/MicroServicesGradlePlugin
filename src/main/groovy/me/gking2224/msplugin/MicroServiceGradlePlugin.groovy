package me.gking2224.msplugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project


class MicroServiceGradlePlugin implements Plugin<Project> {

    private static final String NAME = "me.gking2224.msplugin"

    Project project
	void apply(Project project) {
        
        this.project = project
		project.extensions.create(MicroServicePluginExtension.KEY, MicroServicePluginExtension, project)
        
        applyPlugins()
        configureDockerPublishTasks()
        
        configureInstanceDiscoveryTasks()
        configureLoadBalancerDiscoveryTasks()
        configureDeRegisterTasks()
        configureRegisterTasks()
        configureTagTasks()
        
        configureDeployTasks()
        configurePromoteTasks()
        configureRollbackTasks()
        
        configureEnsureSnapshot()
	}
    
    def getEnvs() {
        MicroServicePluginExtension ext = project.getExtensions().findByType(MicroServicePluginExtension.class)
        return ext.envs
    }
    
    def getTaskDefinitionSuffices() {
        MicroServicePluginExtension ext = project.getExtensions().findByType(MicroServicePluginExtension.class)
        return ext.taskDefinitionSuffices
    }
    
    def getTaskDefinitionPrefix() {
        MicroServicePluginExtension ext = project.getExtensions().findByType(MicroServicePluginExtension.class)
        return ext.taskDefinitionPrefix
    }
    
    def applyPlugins() {
        project.apply plugin: 'me.gking2224.dockerplugin'
        project.apply plugin: 'docker'
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
            apiEmail = "none"
        }
        project.tasks.buildDockerImage.doFirst {
//            if (ext.file == null) ext.file = "build/libs/${project.name}-${project.preReleaseVersion}-boot.jar"
//            if (ext.targetFile == null) ext.targetFile = "\$WORK_DIR/service.jar"
//            addFile new File(file), targetFile
            tagVersion = project.version
            setEnvironment "VERSION", project.version
            hostUrl = project.ecrRepository
            apiUsername = project.ecrUsername
            apiPassword = project.ecrPassword
            logger.debug("buildDockerImage with hostUrl=$hostUrl; apiUsername=$apiUsername; apiPassword=$apiPassword; version=$tagVersion")
        }
        project.tasks.buildDockerImage.dependsOn 'dockerLogin'
        project.task("tagImageForRemote", type: me.gking2224.dockerplugin.task.TagImage) {
            registry = { project.dockerRepositoryName + "/" + project.group + "/" + project.name }
            tag = {project.version}
            imageId = {project.tasks.buildDockerImage.tag}
        }
        project.tasks.tagImageForRemote.dependsOn 'ecrGetLogin'
        
        project.task("pushDockerImage", type: me.gking2224.dockerplugin.task.PushImage)
        project.tasks.pushDockerImage.doFirst {
            imageId = project.dockerRepositoryName + "/" + project.group + "/" + project.name + ":" + project.version
        }
        project.tasks.pushDockerImage.dependsOn 'tagImageForRemote'
        
        project.task("newTaskDefinitions")
        project.tasks["newTaskDefinitions"].dependsOn 'pushDockerImage'
        configureNewTaskDefinitionTasks()
        
        project.tasks.postReleaseHook.dependsOn('buildDockerImage', 'pushDockerImage', 'newDevTaskDefinition', 'updateAndTagNextDevInstance')
    }
    
    def configureNewTaskDefinitionTasks() {
        getEnvs().each {
            configureNewTaskDefinitionTasks(it)
        }
    }
    
    def configureNewTaskDefinitionTasks(String env) {
        String cEnv = env.capitalize()
        def prefix = getTaskDefinitionPrefix()
        project.task("new${cEnv}TaskDefinition", type: me.gking2224.awsplugin.task.ecs.RegisterTaskDefinition)
        project.tasks["new${cEnv}TaskDefinition"].doFirst {
            logger.info("using task definition prefix $prefix")
            family "${prefix}-${env}"
            getTaskDefinitionSuffices().each {
                family "${prefix}-${env}-${it}"
            }
            logger.debug("families: ${families}")
            image = project.tasks.pushDockerImage.imageId
        }
        project.tasks["new${cEnv}TaskDefinition"] << {
            project.ext["${env}TaskDefinitionNames"] = taskDefinitionNames
            project.ext["${env}TaskDefinitionArns"] = taskDefinitionArns
        }
        project.tasks["new${cEnv}TaskDefinition"].mustRunAfter project.tasks.pushDockerImage
        project.tasks["newTaskDefinitions"].dependsOn "new${cEnv}TaskDefinition"
    }
    
    def configureInstanceDiscoveryTasks() {
        getEnvs().each {
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
    
    def configureDeRegisterTasks() {
        getEnvs().each {
            configureDeRegisterTasks(it)
        }
    }
    def configureDeRegisterTasks(String environment) {
        String cEnv = environment.capitalize()
        
        project.task("deRegisterNext${cEnv}FromNext", type: me.gking2224.awsplugin.task.elb.DeRegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["deRegisterNext${cEnv}FromNext"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.next?.collect {it.instanceId}
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.next?.targetGroupArn
        }
        project.task("deRegisterCurrent${cEnv}FromCurrent", type: me.gking2224.awsplugin.task.elb.DeRegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["deRegisterCurrent${cEnv}FromCurrent"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.current?.targetGroupArn
        }
        project.task("deRegisterPrevious${cEnv}FromPrevious", type: me.gking2224.awsplugin.task.elb.DeRegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["deRegisterPrevious${cEnv}FromPrevious"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.previous?.targetGroupArn
        }
    }
    
    def configureRegisterTasks() {
        getEnvs().each {
            configureRegisterTasks(it)
        }
    }
    def configureRegisterTasks(String environment) {
        String cEnv = environment.capitalize()
        
        project.task("registerNext${cEnv}InCurrent", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerNext${cEnv}InCurrent"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.next?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as next")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.current?.targetGroupArn
        }
        project.task("registerCurrent${cEnv}InPrevious", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerCurrent${cEnv}InPrevious"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as current")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.previous?.targetGroupArn
        }
        project.task("registerPrevious${cEnv}InCurrent", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerPrevious${cEnv}InCurrent"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as previous")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.current?.targetGroupArn
        }
        project.task("registerCurrent${cEnv}InNext", type: me.gking2224.awsplugin.task.elb.RegisterTargets, dependsOn:["get${cEnv}Instances", "get${cEnv}TargetGroups"])
        project.tasks["registerCurrent${cEnv}InNext"].doFirst {
            instanceIds = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            if (instanceIds == null || instanceIds.isEmpty()) throw new GradleException("no instances tagged as current")
            targetGroupArn = project.tasks["get${cEnv}TargetGroups"].targetGroups.next?.targetGroupArn
        }
    }
    
    def configureTagTasks() {
        getEnvs().each {
            configureTagTasks(it)
        }
    }
    def configureTagTasks(String environment) {
        String cEnv = environment.capitalize()
        
        project.task("tagNext${cEnv}AsCurrent", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:"get${cEnv}Instances")
        project.tasks["tagNext${cEnv}AsCurrent"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.next?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "current"
        }
        project.task("tagCurrent${cEnv}AsPrevious", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:"get${cEnv}Instances")
        project.tasks["tagCurrent${cEnv}AsPrevious"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "previous"
        }
        project.task("tagPrevious${cEnv}AsNone", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:"get${cEnv}Instances")
        project.tasks["tagPrevious${cEnv}AsNone"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "none"
        }
        project.task("tagPrevious${cEnv}AsCurrent", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:"get${cEnv}Instances")
        project.tasks["tagPrevious${cEnv}AsCurrent"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.previous?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "current"
        }
        project.task("tagCurrent${cEnv}AsNext", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:"get${cEnv}Instances")
        project.tasks["tagCurrent${cEnv}AsNext"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.current?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "next"
        }
        project.task("tagNext${cEnv}AsNone", type: me.gking2224.awsplugin.task.ec2.TagInstance, dependsOn:"get${cEnv}Instances")
        project.tasks["tagNext${cEnv}AsNone"].doFirst {
            instanceId = project.tasks["get${cEnv}Instances"].instances.next?.collect {it.instanceId}
            tagKey = "version"
            tagValue = "none"
        }
    }
    
    def configureDeployTasks() {
        getEnvs().each {
            configureDeployTasks(it)
        }
    }
    def configureDeployTasks(String environment) {
        String cEnv = environment.capitalize()
        
        project.task("updateNext${cEnv}Service", type: me.gking2224.awsplugin.task.ecs.UpdateService, dependsOn:["get${cEnv}Instances"])
        project.tasks["updateNext${cEnv}Service"].doFirst {
            ext.instances = project.tasks["get${cEnv}Instances"].instances.next
            if (instances == null || instances.isEmpty()) instances = project.tasks["get${cEnv}Instances"].instances.none
            def suffix = null
            instances.each {
                it.getTags().find{it.getKey() == 'ecsServiceSuffix' }.each {
                    def ecsServiceSuffixTag = it.getValue()
                    if (suffix != null && suffix != ecsServiceSuffixTag) throw new GradleException("mismatching ecsServiceSuffix in $instances")
                    if (ecsServiceSuffixTag != null) suffix = ecsServiceSuffixTag
                }
            }
            if (suffix != null) {
                serviceSuffix = suffix
                suffix = "-$suffix"
            }
            else suffix = ""
            clusterName = "${project.name}-${environment}${suffix}"
            def region = getRegion()
            taskDefinitionArns = project["${environment}TaskDefinitionArns"]
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
            def instances = project.tasks["get${cEnv}Instances"].instances.next
            if (instances == null || instances.isEmpty()) instances = project.tasks["get${cEnv}Instances"].instances.none
            instanceId = instances.collect {it.instanceId}
            tagKey = "version"
            tagValue = "next"
        }
        project.task("updateAndTagNext${cEnv}Instance", dependsOn:["updateNext${cEnv}Service", "tagNext${cEnv}Instance", "registerNext${cEnv}InNext"])
    }
    
    def configureLoadBalancerDiscoveryTasks() {
        getEnvs().each {
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
        getEnvs().each {
            configurePromoteTasks(it)
        }
    }
    def configurePromoteTasks(String environment) {
        
        String cEnv = environment.capitalize()
        project.tasks["tagNext${cEnv}AsCurrent"].mustRunAfter "registerNext${cEnv}InCurrent"
        project.tasks["deRegisterNext${cEnv}FromNext"].mustRunAfter "registerNext${cEnv}InCurrent"
        project.task("moveNext${cEnv}ToCurrent", dependsOn:["registerNext${cEnv}InCurrent", "tagNext${cEnv}AsCurrent", "deRegisterNext${cEnv}FromNext"])
        
        
        project.tasks["deRegisterCurrent${cEnv}FromCurrent"].mustRunAfter "registerCurrent${cEnv}InPrevious"
        project.tasks["tagCurrent${cEnv}AsPrevious"].mustRunAfter "registerCurrent${cEnv}InPrevious"
        project.task("moveCurrent${cEnv}ToPrevious", dependsOn:["registerCurrent${cEnv}InPrevious", "deRegisterCurrent${cEnv}FromCurrent", "tagCurrent${cEnv}AsPrevious"])
        
        project.tasks["tagPrevious${cEnv}AsNone"].mustRunAfter "deRegisterPrevious${cEnv}FromPrevious"
        project.task("losePrevious${cEnv}", dependsOn:["deRegisterPrevious${cEnv}FromPrevious", "tagPrevious${cEnv}AsNone"])
        
        project.task("promoteNext${cEnv}", group: "Deployment", dependsOn: ["moveNext${cEnv}ToCurrent", "moveCurrent${cEnv}ToPrevious", "losePrevious${cEnv}"])
        project.tasks["moveCurrent${cEnv}ToPrevious"].mustRunAfter "moveNext${cEnv}ToCurrent"
        project.tasks["losePrevious${cEnv}"].mustRunAfter "moveCurrent${cEnv}ToPrevious"
        
    }
    
    def configureRollbackTasks() {
        getEnvs().each {
            configureRollbackTasks(it)
        }
    }
    def configureRollbackTasks(String environment) {
        
        String cEnv = environment.capitalize()
        
        project.tasks["deRegisterPrevious${cEnv}FromPrevious"].mustRunAfter "registerPrevious${cEnv}InCurrent"
        project.tasks["tagPrevious${cEnv}AsCurrent"].mustRunAfter "registerPrevious${cEnv}InCurrent"
        project.task("movePrevious${cEnv}ToCurrent", dependsOn:["registerPrevious${cEnv}InCurrent", "deRegisterPrevious${cEnv}FromPrevious", "tagPrevious${cEnv}AsCurrent"])
        
        project.tasks["deRegisterCurrent${cEnv}FromCurrent"].mustRunAfter "registerCurrent${cEnv}InNext"
        project.tasks["tagCurrent${cEnv}AsNext"].mustRunAfter "registerCurrent${cEnv}InNext"
        project.task("moveCurrent${cEnv}ToNext", dependsOn:["registerCurrent${cEnv}InNext", "deRegisterCurrent${cEnv}FromCurrent", "tagCurrent${cEnv}AsNext"])
        
        project.tasks["tagNext${cEnv}AsNone"].mustRunAfter "deRegisterNext${cEnv}FromNext"
        project.task("loseNext${cEnv}", dependsOn:["deRegisterNext${cEnv}FromNext", "tagNext${cEnv}AsNone"])
        
        project.task("rollback${cEnv}", group: "Deployment", dependsOn: ["movePrevious${cEnv}ToCurrent", "moveCurrent${cEnv}ToNext", "loseNext${cEnv}"])
        project.tasks["moveCurrent${cEnv}ToNext"].mustRunAfter "movePrevious${cEnv}ToCurrent"
        project.tasks["loseNext${cEnv}"].mustRunAfter "moveCurrent${cEnv}ToNext"
        
    }
    
    def configureEnsureSnapshot() {
        
    }
    
}

