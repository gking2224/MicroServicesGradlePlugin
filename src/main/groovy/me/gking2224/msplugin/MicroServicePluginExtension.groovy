package me.gking2224.msplugin

import org.gradle.api.Project
import org.slf4j.LoggerFactory


class MicroServicePluginExtension {
    def logger = LoggerFactory.getLogger(MicroServicePluginExtension.class)
    
    public static final String KEY = "microservice"
    
    def taskDefinitionSuffices = [] as Set
    
    def taskDefinitionPrefix
    
    def instanceService
    
    def envs = [] as Set
    
    def project;
    
    public MicroServicePluginExtension(Project project) {
        this.project = project;
        this.taskDefinitionPrefix = project.name
        this.instanceService = project.name
        envs << "dev"
    }
    
    def env(String... e) {
        e.each { envs << e }
    }
    def taskDefinitionSuffix(String suffix) {
        taskDefinitionSuffices << suffix
    }
    def taskDefinitionPrefix(String prefix) {
        taskDefinitionPrefix = prefix
    }
    def instanceService(String service) {
        instanceService = service
    }
}
