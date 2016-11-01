package me.gking2224.msplugin

import org.gradle.api.Project
import org.slf4j.LoggerFactory


class MicroServicePluginExtension {
    
    
    def logger = LoggerFactory.getLogger(MicroServicePluginExtension.class)
    
    private static final String KEY = "microservice"
    
    private Project project;
    
    public MicroServicePluginExtension(Project project) {
        this.project = project;
    }
    
}
