package me.gking2224.msplugin

import org.gradle.api.Project
import org.slf4j.LoggerFactory


class MicroServicePluginExtension {
    def logger = LoggerFactory.getLogger(MicroServicePluginExtension.class)
    
    public static final String KEY = "microservice"
    
    def taskDefinitionSuffices = [] as Set
    
    def envs = [] as Set
    
    def project;
    
    public MicroServicePluginExtension(Project project) {
        this.project = project;
        envs << "dev"
    }
    
    def env(String... e) {
        e.each { envs << e }
    }
    def taskDefinitionSuffix(String suffix) {
        taskDefinitionSuffices << suffix
    }
}
