
package com.core

class PipelineUtilities implements Serializable {
    def context
    def npe = [ params: [], name: '']

    String registryPrefix
    String repoName
    String dockerTag = ""
    String puppetBranch = "master"
    boolean imageExists = false

    PipelineUtilities(context, registryPrefix, repoName, puppetBranch) {
        this.context = context
        this.registryPrefix = registryPrefix
        this.repoName = repoName
        this.puppetBranch = puppetBranch
    }

    def String buildAndUnitTest() {
        this.context.echo "Build and Unit Test"
        Helper.runScript(this.context, "./gradlew clean --no-daemon check jacocoTestReportUnit")
        return Helper.runScript(this.context, "git log -n 1 --pretty=format:%s ${this.context.GIT_COMMIT}")
    }

    def String runIntegrationTests() {
        this.context.echo "Run Integration Tests"
        Helper.runScript(this.context, "./gradlew integrationTest --no-daemon --info jacocoTestReportIntegration")
        Helper.runScript(this.context, "git log -n 1 --pretty=format:%s ${this.context.GIT_COMMIT}")
    }

    def createPackage(service_location, legacy) {
        this.context.echo "Create Package"
        def gitHash = Helper.runScript(this.context, "git rev-parse --short ${this.context.GIT_COMMIT}").trim()
        if(legacy){
            createLegacyPackage(service_location, gitHash)
            return
        }
        Helper.runScript(this.context, "cd ${service_location} && ./package.sh -b ${this.context.GIT_BRANCH} -c ${gitHash}")
    }

    def createLegacyPackage(service_location, gitHash) {
        this.context.echo "Create Legacy Package"
        def files = this.context.findFiles(glob: "${service_location}/config/qa.properties")
        def exists = files.length > 0 && files[0].length > 0
        def REPLACE_FILE = ""
        if (exists) {
            REPLACE_FILE = "config/qa.properties"
        }
        Helper.runScript(this.context, "cd auth-service && ./package.sh -b ${this.context.GIT_BRANCH} -c ${gitHash} -r ${REPLACE_FILE}")
        this.context.gitHash = gitHash
    }

    def buildDockerImage(service_location) {
        def service_prefix = service_location.split('-')[0]
        def escapedBranchName = this.context.GIT_BRANCH.replaceAll("_", "-")
        dockerTag = escapedBranchName + "-" + this.context.gitHash
        this.context.echo "Docker tag = |${dockerTag}|"
        Helper.runScript(this.context, '$(aws ecr get-login --no-include-email --region eu-west-1)')
        def files = this.context.findFiles(glob: "${service_location}/build/distributions/nexmo-${service_prefix}_*+" + escapedBranchName + '+' + this.context.gitHash + '-1_all.deb')
        def localPath = ""
        if (files.length > 0) {
            //set some variable to indicate the file to load
            localPath = files[0].path
        }
        this.context.echo "Checkout shared repo"
        this.context.checkout([$class: 'GitSCM', branches: [[name: "CORE-3631_dockerfile"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cfb2df52-09d4-4f27-ad17-71a58c4995d9', url: 'https://github.com/nexmoinc/core-innovation']]])

        this.context.echo "Build Docker Image"
        Helper.runScript(this.context, "docker build -f Dockerfile --no-cache --network=host --build-arg service_name=${service_prefix} --build-arg local_deb_path=${localPath} -t ${registryPrefix}/${repoName}:${dockerTag} .")
        this.context.echo "Push image"
        Helper.runScript(this.context, "docker images")
        try { 
            Helper.runScript(this.context, "aws ecr describe-images --repository-name=${repoName} --image-ids=imageTag=${dockerTag}")
            imageAlreadyExists = "true"
        } catch (Exception inf) {
            Helper.runScript(this.context, "docker push ${registryPrefix}/${repoName}:${dockerTag}")
            Helper.runScript(this.context, "docker rmi -f ${registryPrefix}/${repoName}:${dockerTag}")
        }
    }

    def retrieveConfiguration(npePreset) {
        def projectName = repoName.split('-')[1]
        npe.params = this.context.readJSON text: Helper.runScript(this.context, "curl -ks \"https://api.app.npe/presets/${npePreset}/default-params\"").trim()
        npe.params.param["env.puppet_branch"] = puppetBranch
        npe.params.param["metaconf.docker_tag"] = dockerTag
        npe.params.param["metaconf.project_name"] = projectName
        npe.params.param["metaconf.project_branch"] = "master"
        npe.params.param["env.core_instance_id"] = projectName
        npe.params.param["env.core_config_db_db_url"] = "jdbc:mysql://mysql-db/config"
    }

    def buildNPE(npeKey, npeUser) {
        String rawResponse = Helper.runScript(this.context, "curl -ks -H \"Content-Type: application/json\" -d '${groovy.json.JsonOutput.toJson(npe.params)}' -X POST \"https://${npeUser}:${npeKey}@api.app.npe/envs\"").trim()
        Object response = this.context.readJSON text: rawResponse
        npe.name = response.data[0].name
        this.context.echo "NPE Name: ${npe.name}"
        return npe.name
    }

    def waitForNPEEnv(npeKey, npeUser) {
        int attempts = 0
        while(attempts < 40){
            Object response = this.context.readJSON text: Helper.runScript(this.context, "curl -ks \"https://${npeUser}:${npeKey}@api.app.npe/envs/${npe.name}/status\"").trim()
            if (response.data[0].available != false) { 
                return true
            } else {
                sleep 30000 //30 sec
            }
            attempts++
        }
        return false
    }

    def runQATests(qaTestSet, targetBranch) {
        this.context.echo "Checkout QA Tests with branch ${targetBranch}"
        this.context.checkout([$class: 'GitSCM', branches: [[name: "${targetBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cfb2df52-09d4-4f27-ad17-71a58c4995d9', url: 'https://github.com/nexmoinc/qatests']]])
        Helper.runScript(this.context, Helper.getQAShellScript(this.context, qaTestSet, npe.name))
    }

    def dropNPE(npeKey, npeUser) {
        this.context.echo "Deleting environment ${npe.name}"
        def resp = Helper.runScript(this.context, "curl -ks -X DELETE \"https://${npeUser}:${npeKey}@api.app.npe/envs/${npe.name}\"")
        this.context.echo "${resp}"
    }

    def pushLatestTag(){
        Helper.pushLatestTagToECR(this.context, this.imageExists, this.dockerTag, this.registryPrefix, this.repoName)
    }

    def deleteImage(){
        Helper.deleteImageFromECR(this.context, this.repoName, this.dockerTag) 
    }

}