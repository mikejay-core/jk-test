
package com.core

class PipelineUtilities implements Serializable {
    def steps
    def npe = [ params: [], name: '']

    String registryPrefix
    String repoName
    String dockerTag = ""
    String puppetBranch = 'master'
    boolean imageExists = false

    PipelineUtilities(steps, registryPrefix, repoName) {
        this.steps = steps
        this.registryPrefix = registryPrefix
        this.repoName = repoName
    }

    // ---------- general ------------

    def bashScript(script) {
        steps.sh "${script}"
    }   

    def bashScriptReturn(script) {
        return steps.sh(returnStdout: true, script:"${script}")
    }

    // ------------- -----------------


    def String buildAndUnitTest(env) {
        steps.echo "Build and Unit Test"
        bashScriptReturn("./gradlew clean check jacocoTestReportUnit")
        return bashScriptReturn("git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
    }

    def String runIntegrationTests(env) {
        steps.echo "Run Integration Tests"
        bashScriptReturn("./gradlew integrationTest --info jacocoTestReportIntegration")
        return bashScriptReturn("git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}")
    }

    def createPackage(env, service_location) {
        steps.echo "Create Package"
        def service_prefix = service_location.split('-')[0]
        def gitHash = bashScriptReturn("git rev-parse --short ${env.GIT_COMMIT}").trim()

        if(service_prefix == "auth"){
            createLegacyPackage(env, service_location, gitHash)
            return
        }
        bashScript("cd ${service_location} && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash}")
    }

    def createLegacyPackage(env, service_location, gitHash) {
        steps.echo "Create Legacy Package"
        def files = steps.findFiles(glob: "${service_location}/config/qa.properties")
        def exists = files.length > 0 && files[0].length > 0
        def REPLACE_FILE = ""
        if (exists) {
            REPLACE_FILE = "config/qa.properties"
        }
        bashScript("cd auth-service && ./package.sh -b ${env.GIT_BRANCH} -c ${gitHash} -r ${REPLACE_FILE}")
        env.gitHash = gitHash
    }

    def buildDockerImage(env, service_location) {
        steps.echo "Build Docker Image"
        def service_prefix = service_location.split('-')[0]
        def escapedBranchName = env.GIT_BRANCH.replaceAll("_", "-")

        dockerTag = escapedBranchName + "-" + env.gitHash
        steps.echo "Docker tag = |${dockerTag}|"
        bashScript('$(aws ecr get-login --no-include-email --region eu-west-1)')
        def files = steps.findFiles(glob: "${service_location}/build/distributions/nexmo-${service_prefix}_*+" + escapedBranchName + '+' + env.gitHash + '-1_all.deb')
        def localPath = ""
        if (files.length > 0) {
            //set some variable to indicate the file to load
            localPath = files[0].path
        }
        bashScript("docker build -f Dockerfile --no-cache --network=host --build-arg service_name=${service_prefix} --build-arg local_deb_path=${localPath} -t ${registryPrefix}/${repoName}:${dockerTag} .")
        steps.echo "Push image"
        bashScript("docker images")

        try { 
            bashScriptReturn("aws ecr describe-images --repository-name=${repoName} --image-ids=imageTag=${dockerTag}")
            imageAlreadyExists = "true"
        } catch (Exception inf) {
            bashScript("docker push ${registryPrefix}/${repoName}:${dockerTag}")
        }
    }

    def retrieveConfiguration(env, service_location) {
        def npePreset = service_location.split('-')[0]
        npe.params = env.readJSON text: bashScriptReturn("curl -ks \"https://api.app.npe/presets/${npePreset}/default-params\"").trim()
        npe.params.param["env.puppet_branch"] = puppetBranch
        npe.params.param["metaconf.docker_tag"] = dockerTag
        npe.params.param["metaconf.auth_branch"] = env.GIT_BRANCH
        npe.params.param["env.core_config_db_db_url"] = "jdbc:mysql://mysql-db/config"
    }

    


}